#include "app_server_process.h"
#include "png_validator.h"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <thread>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

namespace agentcodi {
namespace {

constexpr int kStillRunning = INT_MIN;
constexpr std::size_t kMaximumInboundWireBytes = 64U * 1024U * 1024U;
constexpr std::size_t kImageResultCompactionThreshold = 32U * 1024U;
constexpr std::size_t kMaximumMaterializedImageBytes = 64U * 1024U * 1024U;
constexpr std::size_t kMaximumImagesPerLine = 240U;
constexpr std::size_t kMaximumDecodedMetadataBytes = 256U;
constexpr unsigned int kRenameNoReplace = 1U;
constexpr int kExitStatusObservationAttempts = 25;
constexpr const char* kSystemShell = "/system/bin/sh";
constexpr const char* kCompactedImageResult =
    "\"<generated-image-data-omitted>\"";
constexpr const char* kGeneratedImagesDirectory = "generated_images";

struct JsonStringSpan {
  std::size_t begin;
  std::size_t end;
};

struct JsonValueSpan {
  std::size_t begin;
  std::size_t end;
};

struct ImagePayload {
  std::string id;
  std::string status;
  JsonStringSpan result_span {};
  JsonValueSpan saved_path_span {};
  std::size_t object_end = 0U;
  bool has_saved_path = false;
};

struct ImageScanResult {
  std::vector<JsonStringSpan> raw_result_spans;
  std::vector<ImagePayload> image_payloads;
};

struct JsonReplacement {
  std::size_t begin;
  std::size_t end;
  std::string value;
};

bool is_valid_utf8(const std::string& input) {
  std::size_t index = 0U;
  while (index < input.size()) {
    const unsigned char first = static_cast<unsigned char>(input[index++]);
    if (first <= 0x7fU) {
      continue;
    }

    std::size_t continuation_count = 0U;
    unsigned char second_minimum = 0x80U;
    unsigned char second_maximum = 0xbfU;
    if (first >= 0xc2U && first <= 0xdfU) {
      continuation_count = 1U;
    } else if (first >= 0xe0U && first <= 0xefU) {
      continuation_count = 2U;
      if (first == 0xe0U) {
        second_minimum = 0xa0U;
      } else if (first == 0xedU) {
        second_maximum = 0x9fU;
      }
    } else if (first >= 0xf0U && first <= 0xf4U) {
      continuation_count = 3U;
      if (first == 0xf0U) {
        second_minimum = 0x90U;
      } else if (first == 0xf4U) {
        second_maximum = 0x8fU;
      }
    } else {
      return false;
    }

    if (input.size() - index < continuation_count) {
      return false;
    }
    const unsigned char second = static_cast<unsigned char>(input[index]);
    if (second < second_minimum || second > second_maximum) {
      return false;
    }
    ++index;
    for (std::size_t continuation = 1U;
         continuation < continuation_count;
         ++continuation) {
      const unsigned char value = static_cast<unsigned char>(input[index++]);
      if (value < 0x80U || value > 0xbfU) {
        return false;
      }
    }
  }
  return true;
}

class ImagePayloadScanner final {
 public:
  explicit ImagePayloadScanner(const std::string& input)
      : input_(input), position_(0U) {}

  bool Scan(ImageScanResult* result) {
    if (result == nullptr || !is_valid_utf8(input_)) {
      return false;
    }
    result_ = result;
    result_->raw_result_spans.clear();
    result_->image_payloads.clear();
    SkipWhitespace();
    if (!ParseValue(0U)) {
      return false;
    }
    SkipWhitespace();
    return position_ == input_.size();
  }

 private:
  bool ParseValue(std::size_t depth) {
    if (depth > 64U) {
      return false;
    }
    SkipWhitespace();
    if (position_ >= input_.size()) {
      return false;
    }
    const char value = input_[position_];
    if (value == '{') {
      return ParseObject(depth + 1U);
    }
    if (value == '[') {
      return ParseArray(depth + 1U);
    }
    if (value == '"') {
      return ParseString(nullptr, nullptr);
    }
    if (value == 't') {
      return ParseLiteral("true");
    }
    if (value == 'f') {
      return ParseLiteral("false");
    }
    if (value == 'n') {
      return ParseLiteral("null");
    }
    return ParseNumber();
  }

  bool ParseObject(std::size_t depth) {
    ++position_;
    SkipWhitespace();
    if (Consume('}')) {
      return true;
    }

    std::string object_type;
    std::string object_id;
    std::string object_status;
    std::vector<JsonStringSpan> result_spans;
    JsonValueSpan saved_path_span {};
    std::size_t type_count = 0U;
    std::size_t id_count = 0U;
    std::size_t status_count = 0U;
    std::size_t result_count = 0U;
    std::size_t saved_path_count = 0U;
    bool saw_image_generation_type = false;
    bool saw_raw_image_generation_type = false;
    bool saved_path_valid = false;
    std::size_t entries = 0U;
    while (position_ < input_.size()) {
      if (++entries > 10000U) {
        return false;
      }
      std::string key;
      if (!ParseString(&key, nullptr)) {
        return false;
      }
      SkipWhitespace();
      if (!Consume(':')) {
        return false;
      }
      SkipWhitespace();

      if (key == "type") {
        ++type_count;
        if (Peek('"')) {
          if (!ParseString(&object_type, nullptr)) {
            return false;
          }
          saw_image_generation_type = saw_image_generation_type
              || object_type == "imageGeneration";
          saw_raw_image_generation_type = saw_raw_image_generation_type
              || object_type == "image_generation_call";
        } else if (!ParseValue(depth)) {
          return false;
        }
      } else if (key == "id") {
        ++id_count;
        if (Peek('"')) {
          if (!ParseString(&object_id, nullptr)) {
            return false;
          }
        } else if (!ParseValue(depth)) {
          return false;
        }
      } else if (key == "status") {
        ++status_count;
        if (Peek('"')) {
          if (!ParseString(&object_status, nullptr)) {
            return false;
          }
        } else if (!ParseValue(depth)) {
          return false;
        }
      } else if (key == "result") {
        ++result_count;
        if (Peek('"')) {
          JsonStringSpan span {};
          if (!ParseString(nullptr, &span)) {
            return false;
          }
          result_spans.push_back(span);
        } else if (!ParseValue(depth)) {
          return false;
        }
      } else if (key == "savedPath") {
        ++saved_path_count;
        saved_path_span.begin = position_;
        if (Peek('"')) {
          saved_path_valid = ParseString(nullptr, nullptr);
        } else if (Peek('n')) {
          saved_path_valid = ParseLiteral("null");
        } else {
          saved_path_valid = false;
          if (!ParseValue(depth)) {
            return false;
          }
        }
        saved_path_span.end = position_;
        if (!saved_path_valid) {
          return false;
        }
      } else if (!ParseValue(depth)) {
        return false;
      }

      SkipWhitespace();
      if (Consume('}')) {
        break;
      }
      if (!Consume(',')) {
        return false;
      }
      SkipWhitespace();
    }
    if (position_ > input_.size()
        || (position_ == input_.size() && input_[position_ - 1U] != '}')) {
      return false;
    }

    if (saw_image_generation_type) {
      if (object_type != "imageGeneration" || type_count != 1U
          || id_count != 1U || status_count != 1U
          || result_count != 1U || result_spans.size() != 1U
          || saved_path_count > 1U || object_id.empty()
          || object_status.empty()
          || result_->image_payloads.size() >= kMaximumImagesPerLine) {
        return false;
      }
      ImagePayload payload;
      payload.id = object_id;
      payload.status = object_status;
      payload.result_span = result_spans.front();
      payload.saved_path_span = saved_path_span;
      payload.object_end = position_ - 1U;
      payload.has_saved_path = saved_path_count == 1U;
      result_->image_payloads.push_back(std::move(payload));
    } else if (saw_raw_image_generation_type) {
      if (object_type != "image_generation_call" || type_count != 1U
          || result_count != 1U
          || result_spans.size() != 1U) {
        return false;
      }
      for (const JsonStringSpan& span : result_spans) {
        if (span.end - span.begin > kImageResultCompactionThreshold) {
          result_->raw_result_spans.push_back(span);
        }
      }
    }
    return true;
  }

  bool ParseArray(std::size_t depth) {
    ++position_;
    SkipWhitespace();
    if (Consume(']')) {
      return true;
    }
    std::size_t entries = 0U;
    while (position_ < input_.size()) {
      if (++entries > 10000U || !ParseValue(depth)) {
        return false;
      }
      SkipWhitespace();
      if (Consume(']')) {
        return true;
      }
      if (!Consume(',')) {
        return false;
      }
      SkipWhitespace();
    }
    return false;
  }

  bool ParseString(std::string* decoded, JsonStringSpan* span) {
    if (!Consume('"')) {
      return false;
    }
    const std::size_t begin = position_ - 1U;
    if (decoded != nullptr) {
      decoded->clear();
    }
    bool decoded_too_long = false;
    while (position_ < input_.size()) {
      const unsigned char value = static_cast<unsigned char>(input_[position_++]);
      if (value == '"') {
        if (span != nullptr) {
          span->begin = begin;
          span->end = position_;
        }
        if (decoded != nullptr && decoded_too_long) {
          decoded->clear();
        }
        return true;
      }
      if (value < 0x20U) {
        return false;
      }
      if (value != '\\') {
        AppendDecoded(decoded, static_cast<char>(value), &decoded_too_long);
        continue;
      }
      if (position_ >= input_.size()) {
        return false;
      }
      const char escape = input_[position_++];
      switch (escape) {
        case '"':
        case '\\':
        case '/':
          AppendDecoded(decoded, escape, &decoded_too_long);
          break;
        case 'b':
          AppendDecoded(decoded, '\b', &decoded_too_long);
          break;
        case 'f':
          AppendDecoded(decoded, '\f', &decoded_too_long);
          break;
        case 'n':
          AppendDecoded(decoded, '\n', &decoded_too_long);
          break;
        case 'r':
          AppendDecoded(decoded, '\r', &decoded_too_long);
          break;
        case 't':
          AppendDecoded(decoded, '\t', &decoded_too_long);
          break;
        case 'u': {
          unsigned int code_point = 0U;
          if (!ParseUnicodeCodeUnit(&code_point)) {
            return false;
          }
          if (code_point >= 0xd800U && code_point <= 0xdbffU) {
            if (position_ + 2U > input_.size()
                || input_[position_] != '\\'
                || input_[position_ + 1U] != 'u') {
              return false;
            }
            position_ += 2U;
            unsigned int low_surrogate = 0U;
            if (!ParseUnicodeCodeUnit(&low_surrogate)
                || low_surrogate < 0xdc00U
                || low_surrogate > 0xdfffU) {
              return false;
            }
            code_point = 0x10000U
                + ((code_point - 0xd800U) << 10U)
                + (low_surrogate - 0xdc00U);
          } else if (code_point >= 0xdc00U && code_point <= 0xdfffU) {
            return false;
          }
          AppendDecoded(
              decoded,
              code_point <= 0x7fU ? static_cast<char>(code_point) : '?',
              &decoded_too_long);
          break;
        }
        default:
          return false;
      }
    }
    return false;
  }

  bool ParseNumber() {
    const std::size_t start = position_;
    Consume('-');
    if (Consume('0')) {
      if (position_ < input_.size()
          && input_[position_] >= '0' && input_[position_] <= '9') {
        return false;
      }
    } else {
      const std::size_t integer_start = position_;
      while (position_ < input_.size()
          && input_[position_] >= '0' && input_[position_] <= '9') {
        ++position_;
      }
      if (integer_start == position_) {
        return false;
      }
    }
    if (Consume('.')) {
      const std::size_t fraction_start = position_;
      while (position_ < input_.size()
          && input_[position_] >= '0' && input_[position_] <= '9') {
        ++position_;
      }
      if (fraction_start == position_) {
        return false;
      }
    }
    if (position_ < input_.size()
        && (input_[position_] == 'e' || input_[position_] == 'E')) {
      ++position_;
      if (position_ < input_.size()
          && (input_[position_] == '+' || input_[position_] == '-')) {
        ++position_;
      }
      const std::size_t exponent_start = position_;
      while (position_ < input_.size()
          && input_[position_] >= '0' && input_[position_] <= '9') {
        ++position_;
      }
      if (exponent_start == position_) {
        return false;
      }
    }
    return position_ > start;
  }

  bool ParseLiteral(const char* literal) {
    const std::size_t length = std::strlen(literal);
    if (input_.compare(position_, length, literal) != 0) {
      return false;
    }
    position_ += length;
    return true;
  }

  void SkipWhitespace() {
    while (position_ < input_.size()) {
      const char value = input_[position_];
      if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
        return;
      }
      ++position_;
    }
  }

  bool Peek(char value) const {
    return position_ < input_.size() && input_[position_] == value;
  }

  bool Consume(char value) {
    if (!Peek(value)) {
      return false;
    }
    ++position_;
    return true;
  }

  static void AppendDecoded(
      std::string* decoded,
      char value,
      bool* decoded_too_long) {
    if (decoded == nullptr || *decoded_too_long) {
      return;
    }
    if (decoded->size() >= kMaximumDecodedMetadataBytes) {
      decoded->clear();
      *decoded_too_long = true;
      return;
    }
    decoded->push_back(value);
  }

  static int HexDigit(char value) {
    if (value >= '0' && value <= '9') {
      return value - '0';
    }
    if (value >= 'a' && value <= 'f') {
      return value - 'a' + 10;
    }
    if (value >= 'A' && value <= 'F') {
      return value - 'A' + 10;
    }
    return -1;
  }

  bool ParseUnicodeCodeUnit(unsigned int* value) {
    if (value == nullptr) {
      return false;
    }
    *value = 0U;
    for (int index = 0; index < 4; ++index) {
      if (position_ >= input_.size()) {
        return false;
      }
      const int digit = HexDigit(input_[position_++]);
      if (digit < 0) {
        return false;
      }
      *value = (*value << 4U) | static_cast<unsigned int>(digit);
    }
    return true;
  }

  const std::string& input_;
  std::size_t position_;
  ImageScanResult* result_ = nullptr;
};

class ScopedDescriptor final {
 public:
  explicit ScopedDescriptor(int descriptor = -1) : descriptor_(descriptor) {}

  ~ScopedDescriptor() {
    Reset();
  }

  ScopedDescriptor(const ScopedDescriptor&) = delete;
  ScopedDescriptor& operator=(const ScopedDescriptor&) = delete;

  int Get() const {
    return descriptor_;
  }

  void Reset(int descriptor = -1) {
    if (descriptor_ >= 0) {
      while (close(descriptor_) == -1 && errno == EINTR) {
      }
    }
    descriptor_ = descriptor;
  }

 private:
  int descriptor_;
};

bool result_string_is_empty(
    const std::string& line,
    const JsonStringSpan& span) {
  return span.end == span.begin + 2U
      && span.end <= line.size()
      && line[span.begin] == '"'
      && line[span.begin + 1U] == '"';
}

bool result_string_is_compacted(
    const std::string& line,
    const JsonStringSpan& span) {
  return span.end >= span.begin
      && span.end <= line.size()
      && line.compare(
          span.begin,
          span.end - span.begin,
          kCompactedImageResult) == 0;
}

int base64_digit(unsigned char value) {
  if (value >= 'A' && value <= 'Z') {
    return value - 'A';
  }
  if (value >= 'a' && value <= 'z') {
    return value - 'a' + 26;
  }
  if (value >= '0' && value <= '9') {
    return value - '0' + 52;
  }
  if (value == '+') {
    return 62;
  }
  if (value == '/') {
    return 63;
  }
  return -1;
}

bool decode_png_result(
    const std::string& line,
    const JsonStringSpan& span,
    std::vector<unsigned char>* bytes,
    std::string* error) {
  if (bytes == nullptr || error == nullptr || span.end <= span.begin + 2U
      || span.end > line.size() || line[span.begin] != '"'
      || line[span.end - 1U] != '"') {
    if (error != nullptr) {
      *error = "Generated image payload has an invalid JSON span";
    }
    return false;
  }
  std::size_t begin = span.begin + 1U;
  std::size_t end = span.end - 1U;
  while (begin < end && line[begin] == ' ') {
    ++begin;
  }
  while (end > begin && line[end - 1U] == ' ') {
    --end;
  }
  const std::size_t encoded_size = end - begin;
  if (encoded_size == 0U || encoded_size % 4U != 0U
      || encoded_size / 4U > kMaximumMaterializedImageBytes / 3U + 1U) {
    *error = "Generated image payload is not bounded base64 data";
    return false;
  }

  bytes->clear();
  bytes->reserve((encoded_size / 4U) * 3U);
  for (std::size_t offset = 0U; offset < encoded_size; offset += 4U) {
    const bool final_group = offset + 4U == encoded_size;
    const unsigned char first = static_cast<unsigned char>(line[begin + offset]);
    const unsigned char second = static_cast<unsigned char>(line[begin + offset + 1U]);
    const unsigned char third = static_cast<unsigned char>(line[begin + offset + 2U]);
    const unsigned char fourth = static_cast<unsigned char>(line[begin + offset + 3U]);
    const int first_value = base64_digit(first);
    const int second_value = base64_digit(second);
    if (first_value < 0 || second_value < 0) {
      bytes->clear();
      *error = "Generated image payload contains invalid base64 data";
      return false;
    }
    const bool third_padding = third == '=';
    const bool fourth_padding = fourth == '=';
    if (third_padding && (!fourth_padding || !final_group)) {
      bytes->clear();
      *error = "Generated image payload contains invalid base64 padding";
      return false;
    }
    if (fourth_padding && !final_group) {
      bytes->clear();
      *error = "Generated image payload contains invalid base64 padding";
      return false;
    }
    const int third_value = third_padding ? 0 : base64_digit(third);
    const int fourth_value = fourth_padding ? 0 : base64_digit(fourth);
    if (third_value < 0 || fourth_value < 0) {
      bytes->clear();
      *error = "Generated image payload contains invalid base64 data";
      return false;
    }
    if ((third_padding && (second_value & 0x0f) != 0)
        || (fourth_padding && !third_padding && (third_value & 0x03) != 0)) {
      bytes->clear();
      *error = "Generated image payload contains non-canonical base64 padding";
      return false;
    }
    bytes->push_back(static_cast<unsigned char>(
        (first_value << 2U) | (second_value >> 4U)));
    if (!third_padding) {
      bytes->push_back(static_cast<unsigned char>(
          ((second_value & 0x0f) << 4U) | (third_value >> 2U)));
    }
    if (!fourth_padding) {
      bytes->push_back(static_cast<unsigned char>(
          ((third_value & 0x03) << 6U) | fourth_value));
    }
    if (bytes->size() > kMaximumMaterializedImageBytes) {
      bytes->clear();
      *error = "Generated image exceeds the materialization limit";
      return false;
    }
  }
  if (!ValidatePngImage(*bytes, error)) {
    bytes->clear();
    return false;
  }
  return true;
}

std::string materialized_image_name(const std::string& id) {
  std::string safe_id;
  safe_id.reserve(std::min<std::size_t>(id.size(), 48U));
  std::uint64_t hash = 1469598103934665603ULL;
  for (unsigned char value : id) {
    hash ^= static_cast<std::uint64_t>(value);
    hash *= 1099511628211ULL;
    if (safe_id.size() < 48U) {
      const bool safe = (value >= 'A' && value <= 'Z')
          || (value >= 'a' && value <= 'z')
          || (value >= '0' && value <= '9')
          || value == '-' || value == '_';
      safe_id.push_back(safe ? static_cast<char>(value) : '_');
    }
  }
  if (safe_id.empty()) {
    safe_id = "generated_image";
  }
  std::ostringstream name;
  name << safe_id << '-' << std::hex << std::setw(16) << std::setfill('0')
       << hash << ".png";
  return name.str();
}

bool descriptor_matches(
    int descriptor,
    const std::vector<unsigned char>& expected,
    struct stat* verified_metadata,
    std::string* error) {
  struct stat metadata {};
  if (fstat(descriptor, &metadata) != 0 || !S_ISREG(metadata.st_mode)
      || metadata.st_nlink != 1
      || metadata.st_size < 0
      || static_cast<std::uint64_t>(metadata.st_size) != expected.size()
      || lseek(descriptor, 0, SEEK_SET) != 0) {
    *error = "Existing generated image conflicts with the completed image";
    return false;
  }
  std::size_t offset = 0U;
  unsigned char buffer[8192];
  while (offset < expected.size()) {
    const std::size_t remaining = expected.size() - offset;
    const ssize_t count = read(
        descriptor,
        buffer,
        std::min<std::size_t>(sizeof(buffer), remaining));
    if (count > 0) {
      if (!std::equal(
              buffer,
              buffer + count,
              expected.begin() + static_cast<std::ptrdiff_t>(offset))) {
        *error = "Existing generated image conflicts with the completed image";
        return false;
      }
      offset += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      *error = "Existing generated image could not be verified";
      return false;
    }
  }
  unsigned char trailing = 0U;
  ssize_t trailing_count;
  do {
    trailing_count = read(descriptor, &trailing, 1U);
  } while (trailing_count == -1 && errno == EINTR);
  if (trailing_count != 0) {
    *error = "Existing generated image changed during verification";
    return false;
  }
  if (verified_metadata != nullptr) {
    *verified_metadata = metadata;
  }
  return true;
}

bool read_matches(
    int directory,
    const std::string& name,
    const std::vector<unsigned char>& expected,
    bool* exists,
    std::string* error) {
  *exists = false;
  ScopedDescriptor descriptor(openat(
      directory,
      name.c_str(),
      O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
  if (descriptor.Get() < 0) {
    if (errno == ENOENT) {
      return true;
    }
    *error = "Existing generated image could not be opened safely";
    return false;
  }
  if (!descriptor_matches(
          descriptor.Get(),
          expected,
          nullptr,
          error)) {
    return false;
  }
  *exists = true;
  return true;
}

bool open_generated_images_directory(
    const std::string& workspace_directory,
    bool create,
    ScopedDescriptor* directory,
    std::string* error) {
  ScopedDescriptor workspace(open(
      workspace_directory.c_str(),
      O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
  if (workspace.Get() < 0) {
    *error = "Private workspace could not be opened for image materialization";
    return false;
  }
  if (create && mkdirat(workspace.Get(), kGeneratedImagesDirectory, 0700) != 0
      && errno != EEXIST) {
    *error = "Generated-image directory could not be created in the workspace";
    return false;
  }
  const int generated = openat(
      workspace.Get(),
      kGeneratedImagesDirectory,
      O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (generated < 0) {
    if (!create && errno == ENOENT) {
      directory->Reset();
      return true;
    }
    *error = "Generated-image workspace directory is not a safe directory";
    return false;
  }
  directory->Reset(generated);
  struct stat metadata {};
  if (fstat(directory->Get(), &metadata) != 0 || !S_ISDIR(metadata.st_mode)
      || fchmod(directory->Get(), 0700) != 0) {
    directory->Reset();
    *error = "Generated-image workspace directory is not private";
    return false;
  }
  return true;
}

bool materialize_png(
    const std::string& workspace_directory,
    const std::string& temporary_directory,
    const std::string& image_id,
    const std::vector<unsigned char>& bytes,
    std::string* saved_path,
    std::string* error) {
  ScopedDescriptor directory;
  if (!open_generated_images_directory(
          workspace_directory,
          true,
          &directory,
          error)) {
    return false;
  }
  const std::string name = materialized_image_name(image_id);
  bool existing = false;
  if (!read_matches(directory.Get(), name, bytes, &existing, error)) {
    return false;
  }
  if (!existing) {
    ScopedDescriptor temporary_root(open(
        temporary_directory.c_str(),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (temporary_root.Get() < 0) {
      *error = "Private temporary directory could not be opened for image materialization";
      return false;
    }
    static std::atomic<unsigned long long> temporary_sequence(1ULL);
    std::ostringstream temporary_name_builder;
    temporary_name_builder << ".agentcodi-image-" << getpid() << '-'
                           << temporary_sequence.fetch_add(1ULL) << ".tmp";
    const std::string temporary_name = temporary_name_builder.str();
    ScopedDescriptor output(openat(
        temporary_root.Get(),
        temporary_name.c_str(),
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        0600));
    if (output.Get() < 0) {
      *error = "Generated image temporary file could not be created privately";
      return false;
    }
    std::size_t written = 0U;
    while (written < bytes.size()) {
      const ssize_t count = write(
          output.Get(),
          bytes.data() + written,
          bytes.size() - written);
      if (count > 0) {
        written += static_cast<std::size_t>(count);
      } else if (count == -1 && errno == EINTR) {
        continue;
      } else {
        output.Reset();
        unlinkat(temporary_root.Get(), temporary_name.c_str(), 0);
        *error = "Generated image could not be written to private temporary storage";
        return false;
      }
    }
    if (fchmod(output.Get(), 0600) != 0 || fsync(output.Get()) != 0) {
      output.Reset();
      unlinkat(temporary_root.Get(), temporary_name.c_str(), 0);
      *error = "Generated image temporary file could not be synchronized";
      return false;
    }
    output.Reset();
    if (syscall(
            SYS_renameat2,
            temporary_root.Get(),
            temporary_name.c_str(),
            directory.Get(),
            name.c_str(),
            kRenameNoReplace) != 0) {
      const int saved_errno = errno;
      unlinkat(temporary_root.Get(), temporary_name.c_str(), 0);
      if (saved_errno != EEXIST
          || !read_matches(directory.Get(), name, bytes, &existing, error)
          || !existing) {
        if (error->empty()) {
          *error = "Generated image could not be installed atomically in the workspace";
        }
        return false;
      }
    }
    if (fsync(directory.Get()) != 0 || fsync(temporary_root.Get()) != 0) {
      *error = "Generated image directory update could not be synchronized";
      return false;
    }
  }
  *saved_path = workspace_directory + '/' + kGeneratedImagesDirectory + '/' + name;
  ScopedDescriptor installed(openat(
      directory.Get(),
      name.c_str(),
      O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
  char resolved[PATH_MAX] {};
  struct stat installed_metadata {};
  struct stat path_metadata {};
  if (installed.Get() < 0
      || !descriptor_matches(
          installed.Get(),
          bytes,
          &installed_metadata,
          error)
      || realpath(saved_path->c_str(), resolved) == nullptr
      || *saved_path != resolved
      || lstat(saved_path->c_str(), &path_metadata) != 0
      || !S_ISREG(path_metadata.st_mode)
      || path_metadata.st_dev != installed_metadata.st_dev
      || path_metadata.st_ino != installed_metadata.st_ino
      || path_metadata.st_nlink != 1
      || (installed_metadata.st_mode & (S_IRWXG | S_IRWXO)) != 0) {
    saved_path->clear();
    if (error->empty()) {
      *error = "Generated image did not remain a private canonical workspace file";
    }
    return false;
  }
  return true;
}

bool same_materialized_image_snapshot(
    const struct stat& first,
    const struct stat& second) {
  return first.st_dev == second.st_dev
      && first.st_ino == second.st_ino
      && first.st_mode == second.st_mode
      && first.st_nlink == second.st_nlink
      && first.st_uid == second.st_uid
      && first.st_gid == second.st_gid
      && first.st_size == second.st_size
      && first.st_mtim.tv_sec == second.st_mtim.tv_sec
      && first.st_mtim.tv_nsec == second.st_mtim.tv_nsec
      && first.st_ctim.tv_sec == second.st_ctim.tv_sec
      && first.st_ctim.tv_nsec == second.st_ctim.tv_nsec;
}

bool read_and_validate_materialized_png(
    int descriptor,
    std::vector<unsigned char>* bytes,
    struct stat* verified_metadata,
    std::string* error) {
  if (bytes == nullptr || verified_metadata == nullptr || error == nullptr) {
    return false;
  }
  struct stat before {};
  if (fstat(descriptor, &before) != 0 || !S_ISREG(before.st_mode)
      || before.st_nlink != 1 || before.st_size <= 0
      || static_cast<std::uint64_t>(before.st_size)
          > kMaximumMaterializedImageBytes
      || lseek(descriptor, 0, SEEK_SET) != 0) {
    *error = "Materialized generated image failed workspace validation";
    return false;
  }
  const std::size_t expected_size = static_cast<std::size_t>(before.st_size);
  bytes->clear();
  bytes->resize(expected_size);
  std::size_t received = 0U;
  while (received < expected_size) {
    const ssize_t count = read(
        descriptor,
        bytes->data() + received,
        expected_size - received);
    if (count > 0) {
      received += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      bytes->clear();
      *error = "Materialized generated image is truncated";
      return false;
    }
  }
  unsigned char trailing = 0U;
  ssize_t trailing_count;
  do {
    trailing_count = read(descriptor, &trailing, 1U);
  } while (trailing_count == -1 && errno == EINTR);
  struct stat after {};
  if (trailing_count != 0 || fstat(descriptor, &after) != 0
      || !same_materialized_image_snapshot(before, after)) {
    bytes->clear();
    *error = "Materialized generated image changed during validation";
    return false;
  }
  if (!ValidatePngImage(*bytes, error)) {
    bytes->clear();
    return false;
  }
  *verified_metadata = after;
  return true;
}

bool find_materialized_png(
    const std::string& workspace_directory,
    const std::string& image_id,
    std::string* saved_path,
    std::string* error) {
  ScopedDescriptor directory;
  if (!open_generated_images_directory(
          workspace_directory,
          false,
          &directory,
          error)) {
    return false;
  }
  if (directory.Get() < 0) {
    saved_path->clear();
    return true;
  }
  const std::string name = materialized_image_name(image_id);
  ScopedDescriptor image(openat(
      directory.Get(),
      name.c_str(),
      O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
  if (image.Get() < 0) {
    if (errno == ENOENT) {
      saved_path->clear();
      return true;
    }
    *error = "Materialized generated image could not be opened safely";
    return false;
  }
  struct stat metadata {};
  std::vector<unsigned char> bytes;
  if (!read_and_validate_materialized_png(
          image.Get(),
          &bytes,
          &metadata,
          error)) {
    return false;
  }
  *saved_path = workspace_directory + '/' + kGeneratedImagesDirectory + '/' + name;
  char resolved[PATH_MAX] {};
  struct stat path_metadata {};
  if (realpath(saved_path->c_str(), resolved) == nullptr
      || *saved_path != resolved
      || lstat(saved_path->c_str(), &path_metadata) != 0
      || !S_ISREG(path_metadata.st_mode)
      || path_metadata.st_dev != metadata.st_dev
      || path_metadata.st_ino != metadata.st_ino
      || path_metadata.st_nlink != 1
      || (metadata.st_mode & (S_IRWXG | S_IRWXO)) != 0) {
    saved_path->clear();
    *error = "Materialized generated image is not a private canonical workspace file";
    return false;
  }
  return true;
}

std::string quoted_json_string(const std::string& value) {
  std::ostringstream escaped;
  escaped << '"';
  for (unsigned char character : value) {
    switch (character) {
      case '"':
        escaped << "\\\"";
        break;
      case '\\':
        escaped << "\\\\";
        break;
      case '\b':
        escaped << "\\b";
        break;
      case '\f':
        escaped << "\\f";
        break;
      case '\n':
        escaped << "\\n";
        break;
      case '\r':
        escaped << "\\r";
        break;
      case '\t':
        escaped << "\\t";
        break;
      default:
        if (character < 0x20U) {
          escaped << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                  << static_cast<unsigned int>(character) << std::dec;
        } else {
          escaped << static_cast<char>(character);
        }
        break;
    }
  }
  escaped << '"';
  return escaped.str();
}

bool apply_json_replacements(
    const std::string& line,
    std::size_t maximum_bytes,
    std::vector<JsonReplacement>* replacements,
    std::string* output) {
  std::sort(
      replacements->begin(),
      replacements->end(),
      [](const JsonReplacement& left, const JsonReplacement& right) {
        if (left.begin != right.begin) {
          return left.begin < right.begin;
        }
        return left.end < right.end;
      });
  output->clear();
  std::size_t cursor = 0U;
  for (const JsonReplacement& replacement : *replacements) {
    if (replacement.begin < cursor || replacement.end < replacement.begin
        || replacement.end > line.size()) {
      output->clear();
      return false;
    }
    output->append(line, cursor, replacement.begin - cursor);
    output->append(replacement.value);
    cursor = replacement.end;
    if (output->size() > maximum_bytes) {
      output->clear();
      return false;
    }
  }
  output->append(line, cursor, line.size() - cursor);
  if (output->empty() || output->size() > maximum_bytes) {
    output->clear();
    return false;
  }
  return true;
}

bool set_close_on_exec(int descriptor) {
  const int flags = fcntl(descriptor, F_GETFD);
  return flags >= 0 && fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) == 0;
}

void close_if_open(int descriptor) {
  if (descriptor >= 0) {
    while (close(descriptor) == -1 && errno == EINTR) {
    }
  }
}

std::string errno_message(const char* operation, int error_number) {
  std::ostringstream message;
  message << operation << " failed with errno " << error_number;
  return message.str();
}

bool canonical_regular_executable(
    const std::string& path,
    const char* label,
    std::string* canonical,
    std::string* error) {
  char resolved[PATH_MAX];
  if (path.empty() || realpath(path.c_str(), resolved) == nullptr) {
    *error = std::string(label) + " could not be resolved";
    return false;
  }
  struct stat metadata {};
  if (lstat(resolved, &metadata) != 0 || !S_ISREG(metadata.st_mode)
      || access(resolved, X_OK) != 0) {
    *error = std::string(label) + " is not a regular executable file";
    return false;
  }
  *canonical = resolved;
  return true;
}

bool canonical_directory(
    const std::string& path,
    const char* label,
    std::string* canonical,
    std::string* error) {
  char resolved[PATH_MAX];
  if (path.empty() || realpath(path.c_str(), resolved) == nullptr) {
    *error = std::string(label) + " directory could not be resolved";
    return false;
  }
  struct stat metadata {};
  if (lstat(resolved, &metadata) != 0 || !S_ISDIR(metadata.st_mode)) {
    *error = std::string(label) + " path is not a directory";
    return false;
  }
  *canonical = resolved;
  return true;
}

bool validate_tool_alias(
    const std::string& directory,
    const char* name,
    const std::string& expected_executable,
    std::string* error) {
  const std::string alias = directory + "/" + name;
  struct stat alias_metadata {};
  if (lstat(alias.c_str(), &alias_metadata) != 0
      || !S_ISLNK(alias_metadata.st_mode)
      || alias_metadata.st_uid != geteuid()
      || alias_metadata.st_nlink != 1) {
    *error = std::string("Packaged tool alias is invalid: ") + name;
    return false;
  }
  char resolved[PATH_MAX];
  if (realpath(alias.c_str(), resolved) == nullptr
      || expected_executable != resolved) {
    *error = std::string("Packaged tool alias target is invalid: ") + name;
    return false;
  }
  return true;
}

bool validate_codex_configuration_files(
    const std::string& codex_home,
    std::string* error) {
  constexpr const char* kConfigurationFiles[] = {
      "config.toml",
      "requirements.toml",
      "hooks.json",
  };
  for (const char* name : kConfigurationFiles) {
    const std::string path = codex_home + "/" + name;
    struct stat metadata {};
    if (lstat(path.c_str(), &metadata) == 0) {
      if (!S_ISREG(metadata.st_mode)
          || metadata.st_uid != geteuid()
          || metadata.st_nlink != 1
          || (metadata.st_mode & 077) != 0) {
        *error = "Codex configuration must be a private regular file";
        return false;
      }
      continue;
    }
    if (errno != ENOENT) {
      *error = errno_message("Codex configuration metadata", errno);
      return false;
    }
  }
  return true;
}

bool contains_path(const std::string& parent, const std::string& child) {
  if (parent == child) {
    return true;
  }
  return child.size() > parent.size()
      && child.compare(0, parent.size(), parent) == 0
      && child[parent.size()] == '/';
}

bool validate_argument(const std::string& value) {
  return value.find('\0') == std::string::npos
      && value.find('\n') == std::string::npos
      && value.find('\r') == std::string::npos;
}

std::string toml_string(const std::string& value) {
  std::string encoded;
  encoded.reserve(value.size() + 2U);
  encoded.push_back('"');
  for (const unsigned char character : value) {
    if (character == '"' || character == '\\') {
      encoded.push_back('\\');
      encoded.push_back(static_cast<char>(character));
    } else if (character == '\b') {
      encoded.append("\\b");
    } else if (character == '\t') {
      encoded.append("\\t");
    } else if (character == '\n') {
      encoded.append("\\n");
    } else if (character == '\f') {
      encoded.append("\\f");
    } else if (character == '\r') {
      encoded.append("\\r");
    } else {
      encoded.push_back(static_cast<char>(character));
    }
  }
  encoded.push_back('"');
  return encoded;
}

void wipe_bytes(std::vector<unsigned char>* bytes) {
  if (bytes == nullptr || bytes->empty()) {
    return;
  }
  volatile unsigned char* cursor = bytes->data();
  for (std::size_t index = 0U; index < bytes->size(); ++index) {
    cursor[index] = 0U;
  }
}

class ScopedByteWiper final {
 public:
  explicit ScopedByteWiper(std::vector<unsigned char>* bytes) : bytes_(bytes) {}

  ~ScopedByteWiper() {
    wipe_bytes(bytes_);
  }

  ScopedByteWiper(const ScopedByteWiper&) = delete;
  ScopedByteWiper& operator=(const ScopedByteWiper&) = delete;

 private:
  std::vector<unsigned char>* bytes_;
};

int decode_wait_status(int status) {
  if (WIFEXITED(status)) {
    return WEXITSTATUS(status);
  }
  if (WIFSIGNALED(status)) {
    return 128 + WTERMSIG(status);
  }
  return -1;
}

[[noreturn]] void report_child_error_and_exit(int descriptor, int error_number) {
  const int saved_errno = error_number;
  const char* bytes = reinterpret_cast<const char*>(&saved_errno);
  std::size_t written = 0;
  while (written < sizeof(saved_errno)) {
    const ssize_t count = write(
        descriptor,
        bytes + written,
        sizeof(saved_errno) - written);
    if (count > 0) {
      written += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      break;
    }
  }
  _exit(127);
}

std::vector<std::string> child_environment(const ProcessConfig& config) {
  const std::string path = config.tool_binary_directory + ":"
      + config.library_directory + ":/system/bin:/system/xbin";
  return {
      "HOME=" + config.home_directory,
      "CODEX_HOME=" + config.codex_home,
      "TMPDIR=" + config.temporary_directory,
      "TMP=" + config.temporary_directory,
      "TEMP=" + config.temporary_directory,
      "PATH=" + path,
      "SHELL=" + std::string(kSystemShell),
      "LD_LIBRARY_PATH=" + config.library_directory,
      "HISTFILE=/dev/null",
      "NODE_REPL_HISTORY=/dev/null",
      "SSL_CERT_DIR=/system/etc/security/cacerts",
      "AGENTCODI_WORKSPACE=" + config.working_directory,
      "AGENTCODI_TOOLCHAIN=" + config.toolchain_directory,
      "AGENTCODI_TOOL_BIN=" + config.tool_binary_directory,
      "AGENTCODI_TOOL_RUNTIME=" + config.tool_runtime_directory,
      "AGENTCODI_NODE_VERSION=24.18.0",
      "AGENTCODI_NPM_VERSION=11.19.0",
      "AGENTCODI_PYTHON_VERSION=3.14.6",
      "AGENTCODI_TOOLCHAIN_COMMAND=agentcodi-toolchain",
      "AGENTCODI_TOOLCHAIN_PACKAGES=node,npm,python",
      "CODEX_SELF_EXE=" + config.executable,
      "CODEX_CODE_MODE_HOST_PATH=" + config.code_mode_host_executable,
  };
}

}  // namespace

InboundLineCompactionStatus CompactInboundImagePayloads(
    const std::string& line,
    std::size_t maximum_bytes,
    std::string* compacted) {
  if (compacted == nullptr || maximum_bytes == 0U) {
    return InboundLineCompactionStatus::kInvalid;
  }
  compacted->clear();
  ImageScanResult scan_result;
  ImagePayloadScanner scanner(line);
  if (!scanner.Scan(&scan_result)) {
    return InboundLineCompactionStatus::kInvalid;
  }
  std::vector<JsonReplacement> replacements;
  for (const JsonStringSpan& span : scan_result.raw_result_spans) {
    replacements.push_back(JsonReplacement {
        span.begin,
        span.end,
        kCompactedImageResult,
    });
  }
  for (const ImagePayload& payload : scan_result.image_payloads) {
    if (payload.result_span.end - payload.result_span.begin
        > kImageResultCompactionThreshold) {
      replacements.push_back(JsonReplacement {
          payload.result_span.begin,
          payload.result_span.end,
          kCompactedImageResult,
      });
    }
  }
  if (replacements.empty()) {
    return InboundLineCompactionStatus::kNotApplicable;
  }
  if (!apply_json_replacements(
          line,
          maximum_bytes,
          &replacements,
          compacted)) {
    return InboundLineCompactionStatus::kInvalid;
  }
  return InboundLineCompactionStatus::kCompacted;
}

InboundLineCompactionStatus MaterializeAndCompactInboundImagePayloads(
    const std::string& line,
    std::size_t maximum_bytes,
    const std::string& workspace_directory,
    const std::string& temporary_directory,
    std::string* prepared,
    std::string* error) {
  if (prepared == nullptr || error == nullptr || maximum_bytes == 0U
      || workspace_directory.empty() || workspace_directory[0] != '/'
      || temporary_directory.empty() || temporary_directory[0] != '/') {
    return InboundLineCompactionStatus::kInvalid;
  }
  prepared->clear();
  error->clear();
  ImageScanResult scan_result;
  ImagePayloadScanner scanner(line);
  if (!scanner.Scan(&scan_result)) {
    *error = "Incoming app-server image event is not valid bounded JSON";
    return InboundLineCompactionStatus::kInvalid;
  }

  std::vector<JsonReplacement> replacements;
  for (const JsonStringSpan& span : scan_result.raw_result_spans) {
    replacements.push_back(JsonReplacement {
        span.begin,
        span.end,
        kCompactedImageResult,
    });
  }
  for (const ImagePayload& payload : scan_result.image_payloads) {
    const bool result_empty = result_string_is_empty(line, payload.result_span);
    const bool result_compacted = result_string_is_compacted(
        line,
        payload.result_span);
    if (payload.status != "completed") {
      if (!result_empty) {
        *error = "Non-completed image event unexpectedly contains image data";
        return InboundLineCompactionStatus::kInvalid;
      }
      continue;
    }

    std::string materialized_path;
    if (!result_empty && !result_compacted) {
      std::vector<unsigned char> image_bytes;
      if (!decode_png_result(
              line,
              payload.result_span,
              &image_bytes,
              error)
          || !materialize_png(
              workspace_directory,
              temporary_directory,
              payload.id,
              image_bytes,
              &materialized_path,
              error)) {
        return InboundLineCompactionStatus::kInvalid;
      }
      replacements.push_back(JsonReplacement {
          payload.result_span.begin,
          payload.result_span.end,
          kCompactedImageResult,
      });
    } else if (!find_materialized_png(
                   workspace_directory,
                   payload.id,
                   &materialized_path,
                   error)) {
      return InboundLineCompactionStatus::kInvalid;
    }

    if (materialized_path.empty()) {
      continue;
    }
    const std::string quoted_path = quoted_json_string(materialized_path);
    if (payload.has_saved_path) {
      replacements.push_back(JsonReplacement {
          payload.saved_path_span.begin,
          payload.saved_path_span.end,
          quoted_path,
      });
    } else {
      replacements.push_back(JsonReplacement {
          payload.object_end,
          payload.object_end,
          ",\"savedPath\":" + quoted_path,
      });
    }
  }

  if (replacements.empty()) {
    return InboundLineCompactionStatus::kNotApplicable;
  }
  if (!apply_json_replacements(
          line,
          maximum_bytes,
          &replacements,
          prepared)) {
    *error = "Prepared generated-image event exceeds the Java framing limit";
    return InboundLineCompactionStatus::kInvalid;
  }
  return InboundLineCompactionStatus::kCompacted;
}

std::vector<std::string> CodexAppServerArguments(const ProcessConfig& config) {
  const std::string child_path =
      config.tool_binary_directory + ":" + config.library_directory
      + ":/system/bin:/system/xbin";
  const std::string shell_environment =
      "shell_environment_policy={inherit=\"none\","
      "ignore_default_excludes=false,set={PATH=" + toml_string(child_path)
      + ",SHELL=" + toml_string(kSystemShell)
      + ",HOME=" + toml_string(config.home_directory)
      + ",TMPDIR=" + toml_string(config.temporary_directory)
      + ",TMP=" + toml_string(config.temporary_directory)
      + ",TEMP=" + toml_string(config.temporary_directory)
      + ",LD_LIBRARY_PATH=" + toml_string(config.library_directory)
      + ",HISTFILE=\"/dev/null\""
      + ",NODE_REPL_HISTORY=\"/dev/null\""
      + ",SSL_CERT_DIR=\"/system/etc/security/cacerts\""
      + ",AGENTCODI_WORKSPACE=" + toml_string(config.working_directory)
      + ",AGENTCODI_TOOLCHAIN=" + toml_string(config.toolchain_directory)
      + ",AGENTCODI_TOOL_BIN=" + toml_string(config.tool_binary_directory)
      + ",AGENTCODI_TOOL_RUNTIME=" + toml_string(config.tool_runtime_directory)
      + ",AGENTCODI_NODE_VERSION=\"24.18.0\""
      + ",AGENTCODI_NPM_VERSION=\"11.19.0\""
      + ",AGENTCODI_PYTHON_VERSION=\"3.14.6\""
      + ",AGENTCODI_TOOLCHAIN_COMMAND=\"agentcodi-toolchain\""
      + ",AGENTCODI_TOOLCHAIN_PACKAGES=\"node,npm,python\"}}";
  return {
      "app-server",
      "--stdio",
      "--strict-config",
      "-c",
      "cli_auth_credentials_store=\"file\"",
      "-c",
      "approval_policy=\"on-request\"",
      "-c",
      shell_environment,
      "-c",
      "analytics.enabled=false",
      "-c",
      "otel.exporter=\"none\"",
      "-c",
      "otel.log_user_prompt=false",
      "-c",
      "feedback.enabled=false",
      "-c",
      "check_for_update_on_startup=false",
      "-c",
      "allow_login_shell=false",
      // The built-in OpenAI provider enables Responses-over-WebSocket. Some
      // ChatGPT sessions accept the upgrade and then close it by policy, so
      // Codex spends all five stream retries before falling back to HTTPS.
      // A provider with no explicit base URL preserves Codex's auth-dependent
      // OpenAI/ChatGPT endpoint selection while making HTTPS the primary
      // transport for every model from the first turn onward.
      "-c",
      "model_provider=\"agentcodi-openai-http\"",
      "-c",
      "model_providers.agentcodi-openai-http.name=\"OpenAI\"",
      "-c",
      "model_providers.agentcodi-openai-http.wire_api=\"responses\"",
      "-c",
      "model_providers.agentcodi-openai-http.requires_openai_auth=true",
      "-c",
      "model_providers.agentcodi-openai-http.supports_websockets=false",
      "-c",
      "model_providers.agentcodi-openai-http.supports_standalone_web_search=true",
      "-c",
      "default_permissions=\"agentcodi-workspace\"",
      "-c",
      "permissions.agentcodi-workspace.description=\"AGENTCODI private workspace\"",
      "-c",
      "permissions.agentcodi-workspace.filesystem={\":minimal\"=\"read\","
      + toml_string(config.tool_binary_directory) + "=\"read\","
      + toml_string(config.tool_runtime_directory) + "=\"read\","
      "\":workspace_roots\"={\".\"=\"write\"}}",
  };
}

std::shared_ptr<AppServerProcess> AppServerProcess::Start(
    const ProcessConfig& requested_config,
    std::string* error) {
  if (error == nullptr) {
    return nullptr;
  }
  error->clear();

  ProcessConfig config = requested_config;
  if (!canonical_regular_executable(
          requested_config.executable,
          "App-server executable",
          &config.executable,
          error)
      || !canonical_regular_executable(
          requested_config.code_mode_host_executable,
          "Code-mode host executable",
          &config.code_mode_host_executable,
          error)
      || !canonical_regular_executable(
          requested_config.shell_executable,
          "Terminal shell executable",
          &config.shell_executable,
          error)
      || !canonical_regular_executable(
          requested_config.node_executable,
          "Node executable",
          &config.node_executable,
          error)
      || !canonical_regular_executable(
          requested_config.python_executable,
          "Python executable",
          &config.python_executable,
          error)
      || !canonical_directory(
          requested_config.working_directory,
          "Workspace",
          &config.working_directory,
          error)
      || !canonical_directory(
          requested_config.toolchain_directory,
          "Toolchain",
          &config.toolchain_directory,
          error)
      || !canonical_directory(
          requested_config.tool_binary_directory,
          "Packaged tool binary",
          &config.tool_binary_directory,
          error)
      || !canonical_directory(
          requested_config.tool_runtime_directory,
          "Packaged tool runtime",
          &config.tool_runtime_directory,
          error)
      || !canonical_directory(
          requested_config.codex_home,
          "Codex home",
          &config.codex_home,
          error)
      || !canonical_directory(
          requested_config.home_directory,
          "Home",
          &config.home_directory,
          error)
      || !canonical_directory(
          requested_config.temporary_directory,
          "Temporary",
          &config.temporary_directory,
          error)
      || !canonical_directory(
          requested_config.library_directory,
          "Native library",
          &config.library_directory,
          error)) {
    return nullptr;
  }
  if (contains_path(config.working_directory, config.codex_home)
      || contains_path(config.codex_home, config.working_directory)) {
    *error = "Codex home must remain separate from the workspace";
    return nullptr;
  }
  if (config.toolchain_directory == config.working_directory
      || !contains_path(config.working_directory, config.toolchain_directory)) {
    *error = "Toolchain must remain below the canonical workspace";
    return nullptr;
  }
  struct stat tool_binary_metadata {};
  if (lstat(config.tool_binary_directory.c_str(), &tool_binary_metadata) != 0
      || !S_ISDIR(tool_binary_metadata.st_mode)
      || tool_binary_metadata.st_uid != geteuid()
      || (tool_binary_metadata.st_mode & 077) != 0
      || contains_path(config.working_directory, config.tool_binary_directory)
      || contains_path(config.tool_binary_directory, config.working_directory)
      || contains_path(config.codex_home, config.tool_binary_directory)
      || contains_path(config.tool_binary_directory, config.codex_home)) {
    *error = "Packaged tool directory must be private and separate";
    return nullptr;
  }
  struct stat tool_runtime_metadata {};
  if (lstat(config.tool_runtime_directory.c_str(), &tool_runtime_metadata) != 0
      || !S_ISDIR(tool_runtime_metadata.st_mode)
      || tool_runtime_metadata.st_uid != geteuid()
      || (tool_runtime_metadata.st_mode & 077) != 0
      || contains_path(config.working_directory, config.tool_runtime_directory)
      || contains_path(config.tool_runtime_directory, config.working_directory)
      || contains_path(config.codex_home, config.tool_runtime_directory)
      || contains_path(config.tool_runtime_directory, config.codex_home)
      || contains_path(config.tool_binary_directory, config.tool_runtime_directory)
      || contains_path(config.tool_runtime_directory, config.tool_binary_directory)) {
    *error = "Packaged tool runtime must be private and separate";
    return nullptr;
  }
  if (!validate_tool_alias(
          config.tool_binary_directory,
          "node",
          config.shell_executable,
          error)
      || !validate_tool_alias(
          config.tool_binary_directory,
          "npm",
          config.shell_executable,
          error)
      || !validate_tool_alias(
          config.tool_binary_directory,
          "python",
          config.shell_executable,
          error)
      || !validate_tool_alias(
          config.tool_binary_directory,
          "python3",
          config.shell_executable,
          error)
      || !validate_tool_alias(
          config.tool_binary_directory,
          "agentcodi-toolchain",
          config.shell_executable,
          error)) {
    return nullptr;
  }
  if (!validate_codex_configuration_files(config.codex_home, error)) {
    return nullptr;
  }
  if (contains_path(config.working_directory, config.code_mode_host_executable)
      || contains_path(config.codex_home, config.code_mode_host_executable)
      || contains_path(config.working_directory, config.shell_executable)
      || contains_path(config.codex_home, config.shell_executable)
      || contains_path(config.working_directory, config.node_executable)
      || contains_path(config.codex_home, config.node_executable)) {
    *error = "Packaged executables must remain outside workspace and Codex home";
    return nullptr;
  }
  if (contains_path(config.working_directory, config.python_executable)
      || contains_path(config.codex_home, config.python_executable)) {
    *error = "Packaged executables must remain outside workspace and Codex home";
    return nullptr;
  }
  if (config.arguments.empty()) {
    config.arguments = CodexAppServerArguments(config);
  }
  for (const std::string& argument : config.arguments) {
    if (!validate_argument(argument)) {
      *error = "App-server argument contains a forbidden character";
      return nullptr;
    }
  }

  int communication[2] = {-1, -1};
  int exec_status[2] = {-1, -1};
  if (socketpair(AF_UNIX, SOCK_STREAM, 0, communication) != 0) {
    *error = errno_message("socketpair", errno);
    return nullptr;
  }
  if (pipe(exec_status) != 0) {
    const int saved_errno = errno;
    close_if_open(communication[0]);
    close_if_open(communication[1]);
    *error = errno_message("exec status pipe", saved_errno);
    return nullptr;
  }
  if (!set_close_on_exec(communication[0])
      || !set_close_on_exec(communication[1])
      || !set_close_on_exec(exec_status[0])
      || !set_close_on_exec(exec_status[1])) {
    const int saved_errno = errno;
    close_if_open(communication[0]);
    close_if_open(communication[1]);
    close_if_open(exec_status[0]);
    close_if_open(exec_status[1]);
    *error = errno_message("close-on-exec", saved_errno);
    return nullptr;
  }

  std::vector<std::string> argument_storage;
  argument_storage.reserve(config.arguments.size() + 1U);
  argument_storage.push_back(config.executable);
  argument_storage.insert(
      argument_storage.end(),
      config.arguments.begin(),
      config.arguments.end());
  std::vector<char*> arguments;
  arguments.reserve(argument_storage.size() + 1U);
  for (std::string& argument : argument_storage) {
    arguments.push_back(const_cast<char*>(argument.c_str()));
  }
  arguments.push_back(nullptr);

  // Android starts this supervisor from a multithreaded ART process. Build all
  // storage before fork and pass an explicit environment to execve: clearenv
  // and setenv may allocate or touch libc global state and are not safe in the
  // post-fork child. The explicit envp keeps the same fail-closed allowlist
  // without mutating inherited process state between fork and exec.
  std::vector<std::string> environment_storage = child_environment(config);
  std::vector<char*> environment;
  environment.reserve(environment_storage.size() + 1U);
  for (std::string& value : environment_storage) {
    environment.push_back(const_cast<char*>(value.c_str()));
  }
  environment.push_back(nullptr);

  const pid_t pid = fork();
  if (pid == -1) {
    const int saved_errno = errno;
    close_if_open(communication[0]);
    close_if_open(communication[1]);
    close_if_open(exec_status[0]);
    close_if_open(exec_status[1]);
    *error = errno_message("fork", saved_errno);
    return nullptr;
  }

  if (pid == 0) {
    close_if_open(communication[0]);
    close_if_open(exec_status[0]);
    if (dup2(communication[1], STDIN_FILENO) == -1
        || dup2(communication[1], STDOUT_FILENO) == -1) {
      report_child_error_and_exit(exec_status[1], errno);
    }
    const int null_output = open("/dev/null", O_WRONLY | O_CLOEXEC);
    if (null_output == -1 || dup2(null_output, STDERR_FILENO) == -1) {
      report_child_error_and_exit(exec_status[1], errno);
    }
    close_if_open(null_output);
    close_if_open(communication[1]);
    umask(0077);
    if (chdir(config.working_directory.c_str()) != 0) {
      report_child_error_and_exit(exec_status[1], errno);
    }
    execve(config.executable.c_str(), arguments.data(), environment.data());
    report_child_error_and_exit(exec_status[1], errno);
  }

  close_if_open(communication[1]);
  close_if_open(exec_status[1]);
  int child_errno = 0;
  std::size_t received = 0;
  while (received < sizeof(child_errno)) {
    const ssize_t count = read(
        exec_status[0],
        reinterpret_cast<char*>(&child_errno) + received,
        sizeof(child_errno) - received);
    if (count > 0) {
      received += static_cast<std::size_t>(count);
    } else if (count == 0) {
      break;
    } else if (errno == EINTR) {
      continue;
    } else {
      child_errno = errno;
      received = sizeof(child_errno);
      break;
    }
  }
  close_if_open(exec_status[0]);
  if (received != 0U) {
    close_if_open(communication[0]);
    int status = 0;
    while (waitpid(pid, &status, 0) == -1 && errno == EINTR) {
    }
    *error = errno_message("App-server exec", child_errno);
    return nullptr;
  }
  return std::shared_ptr<AppServerProcess>(new AppServerProcess(
      pid,
      communication[0],
      config.working_directory,
      config.temporary_directory));
}

AppServerProcess::AppServerProcess(
    pid_t pid,
    int socket_fd,
    std::string workspace_directory,
    std::string temporary_directory)
    : pid_(pid),
      socket_fd_(socket_fd),
      exit_code_(kStillRunning),
      workspace_directory_(std::move(workspace_directory)),
      temporary_directory_(std::move(temporary_directory)) {}

AppServerProcess::~AppServerProcess() {
  Stop(100);
}

bool AppServerProcess::WriteLine(
    const std::string& line,
    std::size_t maximum_bytes,
    std::string* error) {
  std::vector<unsigned char> bytes(line.begin(), line.end());
  return WriteBytes(&bytes, bytes.size(), maximum_bytes, error);
}

bool AppServerProcess::WriteBytes(
    std::vector<unsigned char>* line,
    std::size_t length,
    std::size_t maximum_bytes,
    std::string* error) {
  ScopedByteWiper wipe_line(line);
  if (error == nullptr) {
    return false;
  }
  error->clear();
  if (line == nullptr || length == 0U || length > line->size()
      || length > maximum_bytes
      || std::find(line->begin(), line->begin() + length, 0U)
          != line->begin() + length
      || std::find(line->begin(), line->begin() + length, '\n')
          != line->begin() + length
      || std::find(line->begin(), line->begin() + length, '\r')
          != line->begin() + length) {
    *error = "Outgoing app-server line violates the framing limit";
    return false;
  }

  std::lock_guard<std::mutex> write_guard(write_mutex_);
  int descriptor = DuplicateSocket(error);
  if (descriptor < 0) {
    return false;
  }
  std::size_t written = 0;
  while (written < length) {
    const ssize_t count = send(
        descriptor,
        line->data() + written,
        length - written,
        MSG_NOSIGNAL);
    if (count > 0) {
      written += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      const int saved_errno = count == 0 ? EPIPE : errno;
      close_if_open(descriptor);
      *error = errno_message("App-server write", saved_errno);
      return false;
    }
  }
  const unsigned char newline = '\n';
  while (true) {
    const ssize_t count = send(descriptor, &newline, 1U, MSG_NOSIGNAL);
    if (count == 1) {
      break;
    }
    if (count == -1 && errno == EINTR) {
      continue;
    }
    const int saved_errno = count == 0 ? EPIPE : errno;
    close_if_open(descriptor);
    *error = errno_message("App-server write", saved_errno);
    return false;
  }
  close_if_open(descriptor);
  return true;
}

LineReadStatus AppServerProcess::ReadLine(
    std::size_t maximum_bytes,
    std::string* line,
    std::string* error) {
  if (line == nullptr || error == nullptr || maximum_bytes == 0U) {
    return LineReadStatus::kError;
  }
  line->clear();
  error->clear();
  std::lock_guard<std::mutex> read_guard(read_mutex_);

  int descriptor = -1;
  while (true) {
    const std::size_t newline = read_buffer_.find('\n');
    if (newline != std::string::npos) {
      std::string candidate = read_buffer_.substr(0, newline);
      read_buffer_.erase(0, newline + 1U);
      if (!candidate.empty() && candidate.back() == '\r') {
        candidate.pop_back();
      }
      if (candidate.size() > kMaximumInboundWireBytes) {
        *error = "Incoming app-server line exceeds the bounded wire limit";
        close_if_open(descriptor);
        return LineReadStatus::kTooLarge;
      }
      InboundLineCompactionStatus compaction_status =
          InboundLineCompactionStatus::kNotApplicable;
      const bool may_contain_image =
          candidate.find("\"imageGeneration\"") != std::string::npos
          || candidate.find("\"image_generation_call\"")
              != std::string::npos;
      if (candidate.size() > kImageResultCompactionThreshold
          || may_contain_image) {
        compaction_status = MaterializeAndCompactInboundImagePayloads(
            candidate,
            maximum_bytes,
            workspace_directory_,
            temporary_directory_,
            line,
            error);
      }
      if (compaction_status == InboundLineCompactionStatus::kInvalid) {
        if (error->empty()) {
          *error = "Incoming app-server image payload is invalid";
        }
        close_if_open(descriptor);
        return LineReadStatus::kError;
      }
      if (compaction_status == InboundLineCompactionStatus::kNotApplicable) {
        if (candidate.size() > maximum_bytes) {
          *error = "Incoming app-server line exceeds the framing limit";
          close_if_open(descriptor);
          return LineReadStatus::kTooLarge;
        }
        *line = std::move(candidate);
      }
      close_if_open(descriptor);
      return LineReadStatus::kLine;
    }
    if (read_buffer_.size() > kMaximumInboundWireBytes) {
      read_buffer_.clear();
      *error = "Incoming app-server line exceeds the bounded wire limit";
      close_if_open(descriptor);
      return LineReadStatus::kTooLarge;
    }
    if (descriptor < 0) {
      descriptor = DuplicateSocket(error);
      if (descriptor < 0) {
        return LineReadStatus::kEndOfStream;
      }
    }

    char buffer[8192];
    const ssize_t count = recv(descriptor, buffer, sizeof(buffer), 0);
    if (count > 0) {
      if (std::memchr(buffer, '\0', static_cast<std::size_t>(count)) != nullptr) {
        close_if_open(descriptor);
        *error = "Incoming app-server line contains a NUL byte";
        return LineReadStatus::kError;
      }
      read_buffer_.append(buffer, static_cast<std::size_t>(count));
    } else if (count == 0) {
      close_if_open(descriptor);
      if (!read_buffer_.empty()) {
        read_buffer_.clear();
        *error = "App-server ended with an incomplete JSON line";
        return LineReadStatus::kError;
      }
      int exit_code = PollExitCode();
      for (int attempt = 0;
           exit_code == kStillRunning
               && attempt < kExitStatusObservationAttempts;
           ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        exit_code = PollExitCode();
      }
      if (exit_code == kStillRunning) {
        *error = "Codex app-server closed stdout while terminating";
      } else {
        *error = "Codex app-server exited with code "
            + std::to_string(exit_code);
      }
      return LineReadStatus::kError;
    } else if (errno != EINTR) {
      const int saved_errno = errno;
      close_if_open(descriptor);
      if (saved_errno == ECONNRESET || saved_errno == EBADF) {
        return LineReadStatus::kEndOfStream;
      }
      *error = errno_message("App-server read", saved_errno);
      return LineReadStatus::kError;
    }
  }
}

int AppServerProcess::PollExitCode() {
  std::lock_guard<std::mutex> state_guard(state_mutex_);
  if (exit_code_ != kStillRunning) {
    return exit_code_;
  }
  if (pid_ <= 0) {
    return -1;
  }
  int status = 0;
  const pid_t result = waitpid(pid_, &status, WNOHANG);
  if (result == pid_) {
    exit_code_ = decode_wait_status(status);
    pid_ = -1;
    return exit_code_;
  }
  if (result == -1 && errno == ECHILD) {
    exit_code_ = -1;
    pid_ = -1;
    return exit_code_;
  }
  return kStillRunning;
}

int AppServerProcess::Stop(int timeout_milliseconds) {
  if (timeout_milliseconds < 0) {
    timeout_milliseconds = 0;
  }
  std::lock_guard<std::mutex> state_guard(state_mutex_);
  const int descriptor = socket_fd_.exchange(-1);
  if (descriptor >= 0) {
    shutdown(descriptor, SHUT_RDWR);
    close_if_open(descriptor);
  }
  if (exit_code_ != kStillRunning) {
    return exit_code_;
  }
  if (pid_ <= 0) {
    exit_code_ = -1;
    return exit_code_;
  }

  kill(pid_, SIGTERM);
  const auto deadline = std::chrono::steady_clock::now()
      + std::chrono::milliseconds(timeout_milliseconds);
  int status = 0;
  while (true) {
    const pid_t result = waitpid(pid_, &status, WNOHANG);
    if (result == pid_) {
      exit_code_ = decode_wait_status(status);
      pid_ = -1;
      return exit_code_;
    }
    if (result == -1 && errno == ECHILD) {
      exit_code_ = -1;
      pid_ = -1;
      return exit_code_;
    }
    if (std::chrono::steady_clock::now() >= deadline) {
      break;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }

  kill(pid_, SIGKILL);
  while (waitpid(pid_, &status, 0) == -1 && errno == EINTR) {
  }
  exit_code_ = decode_wait_status(status);
  pid_ = -1;
  return exit_code_;
}

int AppServerProcess::DuplicateSocket(std::string* error) {
  const int descriptor = socket_fd_.load();
  if (descriptor < 0) {
    if (error != nullptr) {
      *error = "App-server transport is closed";
    }
    return -1;
  }
  const int duplicate = dup(descriptor);
  if (duplicate < 0 && error != nullptr) {
    *error = errno_message("App-server transport duplication", errno);
  }
  return duplicate;
}

}  // namespace agentcodi
