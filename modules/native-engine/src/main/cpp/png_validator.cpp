#include "png_validator.h"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include <zlib.h>

namespace agentcodi {
namespace {

constexpr std::size_t kPngSignatureBytes = 8U;
constexpr std::size_t kMaximumPngChunks = 65536U;
constexpr std::uint64_t kMaximumInflatedPngBytes =
    256ULL * 1024ULL * 1024ULL;

constexpr unsigned char kPngSignature[kPngSignatureBytes] = {
    0x89U, 0x50U, 0x4eU, 0x47U, 0x0dU, 0x0aU, 0x1aU, 0x0aU,
};

constexpr unsigned char kAdam7XStart[7] = {0U, 4U, 0U, 2U, 0U, 1U, 0U};
constexpr unsigned char kAdam7YStart[7] = {0U, 0U, 4U, 0U, 2U, 0U, 1U};
constexpr unsigned char kAdam7XStep[7] = {8U, 8U, 4U, 4U, 2U, 2U, 1U};
constexpr unsigned char kAdam7YStep[7] = {8U, 8U, 8U, 4U, 4U, 2U, 2U};

void set_error(std::string* error, const char* detail) {
  if (error != nullptr) {
    *error = std::string("Generated image is not a complete valid bounded PNG: ")
        + detail;
  }
}

std::uint32_t read_big_endian_u32(const unsigned char* bytes) {
  return (static_cast<std::uint32_t>(bytes[0]) << 24U)
      | (static_cast<std::uint32_t>(bytes[1]) << 16U)
      | (static_cast<std::uint32_t>(bytes[2]) << 8U)
      | static_cast<std::uint32_t>(bytes[3]);
}

bool is_ascii_letter(unsigned char value) {
  return (value >= 'A' && value <= 'Z')
      || (value >= 'a' && value <= 'z');
}

bool chunk_type_is(
    const unsigned char* type,
    const char* expected) {
  return std::memcmp(type, expected, 4U) == 0;
}

bool is_valid_bit_depth(unsigned char color_type, unsigned char bit_depth) {
  switch (color_type) {
    case 0U:
      return bit_depth == 1U || bit_depth == 2U || bit_depth == 4U
          || bit_depth == 8U || bit_depth == 16U;
    case 2U:
      return bit_depth == 8U || bit_depth == 16U;
    case 3U:
      return bit_depth == 1U || bit_depth == 2U || bit_depth == 4U
          || bit_depth == 8U;
    case 4U:
      return bit_depth == 8U || bit_depth == 16U;
    case 6U:
      return bit_depth == 8U || bit_depth == 16U;
    default:
      return false;
  }
}

std::uint64_t channel_count(unsigned char color_type) {
  switch (color_type) {
    case 0U:
    case 3U:
      return 1U;
    case 2U:
      return 3U;
    case 4U:
      return 2U;
    case 6U:
      return 4U;
    default:
      return 0U;
  }
}

struct PassShape {
  std::uint64_t row_bytes;
  std::uint64_t rows;
};

class InflatedShapeValidator final {
 public:
  bool Initialize(
      std::uint32_t width,
      std::uint32_t height,
      unsigned char bit_depth,
      unsigned char color_type,
      unsigned char interlace,
      std::string* error) {
    passes_.clear();
    pass_index_ = 0U;
    rows_remaining_ = 0U;
    row_data_remaining_ = 0U;
    expected_bytes_ = 0U;
    received_bytes_ = 0U;

    const std::uint64_t channels = channel_count(color_type);
    if (width == 0U || height == 0U || channels == 0U
        || !is_valid_bit_depth(color_type, bit_depth)
        || interlace > 1U) {
      set_error(error, "IHDR contains an invalid image shape");
      return false;
    }
    const std::uint64_t bits_per_pixel = channels * bit_depth;
    const std::size_t pass_count = interlace == 0U ? 1U : 7U;
    for (std::size_t index = 0U; index < pass_count; ++index) {
      const std::uint64_t x_start = interlace == 0U ? 0U : kAdam7XStart[index];
      const std::uint64_t y_start = interlace == 0U ? 0U : kAdam7YStart[index];
      const std::uint64_t x_step = interlace == 0U ? 1U : kAdam7XStep[index];
      const std::uint64_t y_step = interlace == 0U ? 1U : kAdam7YStep[index];
      if (width <= x_start || height <= y_start) {
        continue;
      }
      const std::uint64_t pass_width =
          (static_cast<std::uint64_t>(width) - x_start + x_step - 1U) / x_step;
      const std::uint64_t pass_height =
          (static_cast<std::uint64_t>(height) - y_start + y_step - 1U) / y_step;
      if (pass_width == 0U || pass_height == 0U
          || pass_width > (std::numeric_limits<std::uint64_t>::max() - 7U)
              / bits_per_pixel) {
        set_error(error, "IHDR dimensions overflow the scanline shape");
        return false;
      }
      const std::uint64_t row_bits = pass_width * bits_per_pixel;
      const std::uint64_t row_bytes = (row_bits + 7U) / 8U;
      if (row_bytes >= kMaximumInflatedPngBytes
          || pass_height > (kMaximumInflatedPngBytes - expected_bytes_)
              / (row_bytes + 1U)) {
        set_error(error, "inflated scanline shape exceeds the validation limit");
        return false;
      }
      expected_bytes_ += pass_height * (row_bytes + 1U);
      passes_.push_back(PassShape {row_bytes, pass_height});
    }
    if (passes_.empty() || expected_bytes_ == 0U
        || expected_bytes_ > kMaximumInflatedPngBytes) {
      set_error(error, "IHDR does not describe a bounded non-empty image");
      return false;
    }
    rows_remaining_ = passes_[0].rows;
    return true;
  }

  bool Accept(
      const unsigned char* bytes,
      std::size_t length,
      std::string* error) {
    std::size_t offset = 0U;
    while (offset < length) {
      if (received_bytes_ >= expected_bytes_) {
        set_error(error, "IDAT expands beyond the IHDR scanline shape");
        return false;
      }
      if (row_data_remaining_ == 0U) {
        while (pass_index_ < passes_.size() && rows_remaining_ == 0U) {
          ++pass_index_;
          if (pass_index_ < passes_.size()) {
            rows_remaining_ = passes_[pass_index_].rows;
          }
        }
        if (pass_index_ >= passes_.size()) {
          set_error(error, "IDAT contains excess decompressed bytes");
          return false;
        }
        const unsigned char filter = bytes[offset++];
        ++received_bytes_;
        if (filter > 4U) {
          set_error(error, "IDAT contains an invalid scanline filter");
          return false;
        }
        --rows_remaining_;
        row_data_remaining_ = passes_[pass_index_].row_bytes;
        continue;
      }
      const std::size_t available = length - offset;
      const std::uint64_t consumed = std::min<std::uint64_t>(
          row_data_remaining_,
          available);
      offset += static_cast<std::size_t>(consumed);
      row_data_remaining_ -= consumed;
      received_bytes_ += consumed;
    }
    return true;
  }

  bool IsComplete() const {
    return received_bytes_ == expected_bytes_ && row_data_remaining_ == 0U;
  }

 private:
  std::vector<PassShape> passes_;
  std::size_t pass_index_ = 0U;
  std::uint64_t rows_remaining_ = 0U;
  std::uint64_t row_data_remaining_ = 0U;
  std::uint64_t expected_bytes_ = 0U;
  std::uint64_t received_bytes_ = 0U;
};

class PngInflater final {
 public:
  PngInflater() = default;

  ~PngInflater() {
    if (initialized_) {
      inflateEnd(&stream_);
    }
  }

  PngInflater(const PngInflater&) = delete;
  PngInflater& operator=(const PngInflater&) = delete;

  bool Initialize(InflatedShapeValidator* shape, std::string* error) {
    if (initialized_ || shape == nullptr) {
      set_error(error, "IDAT inflater state is invalid");
      return false;
    }
    shape_ = shape;
    stream_ = z_stream {};
    if (inflateInit(&stream_) != Z_OK) {
      set_error(error, "IDAT inflater could not be initialized");
      return false;
    }
    initialized_ = true;
    return true;
  }

  bool Feed(
      const unsigned char* bytes,
      std::size_t length,
      std::string* error) {
    if (!initialized_) {
      set_error(error, "IDAT appeared before inflater initialization");
      return false;
    }
    if (length == 0U) {
      return true;
    }
    if (finished_ || length > std::numeric_limits<uInt>::max()) {
      set_error(error, "IDAT contains bytes after the zlib stream");
      return false;
    }
    stream_.next_in = const_cast<Bytef*>(
        reinterpret_cast<const Bytef*>(bytes));
    stream_.avail_in = static_cast<uInt>(length);
    std::array<unsigned char, 8192U> output {};
    while (true) {
      stream_.next_out = output.data();
      stream_.avail_out = static_cast<uInt>(output.size());
      const uInt before_input = stream_.avail_in;
      const int status = inflate(&stream_, Z_NO_FLUSH);
      const std::size_t produced = output.size() - stream_.avail_out;
      if (produced > 0U && !shape_->Accept(output.data(), produced, error)) {
        return false;
      }
      if (status == Z_STREAM_END) {
        finished_ = true;
        if (stream_.avail_in != 0U) {
          set_error(error, "IDAT contains trailing compressed data");
          return false;
        }
        return true;
      }
      if (status == Z_BUF_ERROR && stream_.avail_in == 0U
          && produced == 0U) {
        return true;
      }
      if (status != Z_OK) {
        set_error(error, "IDAT is not a valid zlib stream");
        return false;
      }
      if (stream_.avail_in == before_input && produced == 0U) {
        set_error(error, "IDAT inflater made no bounded progress");
        return false;
      }
      if (stream_.avail_in == 0U && stream_.avail_out != 0U) {
        return true;
      }
    }
  }

  bool IsComplete() const {
    return initialized_ && finished_ && shape_ != nullptr
        && shape_->IsComplete();
  }

 private:
  z_stream stream_ {};
  InflatedShapeValidator* shape_ = nullptr;
  bool initialized_ = false;
  bool finished_ = false;
};

bool validate_chunk_type(const unsigned char* type, std::string* error) {
  for (std::size_t index = 0U; index < 4U; ++index) {
    if (!is_ascii_letter(type[index])) {
      set_error(error, "chunk type contains a non-letter byte");
      return false;
    }
  }
  if ((type[2] & 0x20U) != 0U) {
    set_error(error, "chunk type uses the reserved lowercase bit");
    return false;
  }
  return true;
}

bool validate_chunk_crc(
    const unsigned char* type,
    const unsigned char* data,
    std::uint32_t length,
    std::uint32_t expected_crc,
    std::string* error) {
  uLong crc = crc32(0L, Z_NULL, 0U);
  crc = crc32(crc, reinterpret_cast<const Bytef*>(type), 4U);
  if (length > 0U) {
    crc = crc32(
        crc,
        reinterpret_cast<const Bytef*>(data),
        static_cast<uInt>(length));
  }
  if (static_cast<std::uint32_t>(crc) != expected_crc) {
    set_error(error, "chunk CRC does not match its type and data");
    return false;
  }
  return true;
}

}  // namespace

bool ValidatePngImage(
    const std::vector<unsigned char>& bytes,
    std::string* error) {
  if (error == nullptr) {
    return false;
  }
  error->clear();
  if (bytes.size() < kPngSignatureBytes
      || !std::equal(
          kPngSignature,
          kPngSignature + kPngSignatureBytes,
          bytes.begin())) {
    set_error(error, "signature is missing or invalid");
    return false;
  }

  bool ihdr_seen = false;
  bool plte_seen = false;
  bool idat_seen = false;
  bool idat_closed = false;
  unsigned char bit_depth = 0U;
  unsigned char color_type = 0U;
  InflatedShapeValidator shape;
  PngInflater inflater;
  std::size_t offset = kPngSignatureBytes;
  std::size_t chunk_count = 0U;

  while (offset < bytes.size()) {
    if (++chunk_count > kMaximumPngChunks) {
      set_error(error, "chunk count exceeds the validation limit");
      return false;
    }
    if (bytes.size() - offset < 12U) {
      set_error(error, "chunk header, data, or CRC is truncated");
      return false;
    }
    const std::uint32_t length = read_big_endian_u32(bytes.data() + offset);
    if (length > 0x7fffffffU
        || static_cast<std::uint64_t>(length) + 12U
            > bytes.size() - offset) {
      set_error(error, "chunk length crosses the PNG boundary");
      return false;
    }
    const unsigned char* type = bytes.data() + offset + 4U;
    const unsigned char* data = type + 4U;
    const std::size_t crc_offset = offset + 8U + length;
    const std::uint32_t expected_crc = read_big_endian_u32(
        bytes.data() + crc_offset);
    if (!validate_chunk_type(type, error)
        || !validate_chunk_crc(type, data, length, expected_crc, error)) {
      return false;
    }

    const bool is_ihdr = chunk_type_is(type, "IHDR");
    const bool is_plte = chunk_type_is(type, "PLTE");
    const bool is_idat = chunk_type_is(type, "IDAT");
    const bool is_iend = chunk_type_is(type, "IEND");
    if (!ihdr_seen) {
      if (!is_ihdr || chunk_count != 1U || length != 13U) {
        set_error(error, "IHDR is not the unique first 13-byte chunk");
        return false;
      }
      const std::uint32_t width = read_big_endian_u32(data);
      const std::uint32_t height = read_big_endian_u32(data + 4U);
      bit_depth = data[8U];
      color_type = data[9U];
      if ((width & 0x80000000U) != 0U || (height & 0x80000000U) != 0U
          || width == 0U || height == 0U
          || !is_valid_bit_depth(color_type, bit_depth)
          || data[10U] != 0U || data[11U] != 0U || data[12U] > 1U
          || !shape.Initialize(
              width,
              height,
              bit_depth,
              color_type,
              data[12U],
              error)) {
        if (error->empty()) {
          set_error(error, "IHDR fields or dimensions are invalid");
        }
        return false;
      }
      ihdr_seen = true;
    } else if (is_ihdr) {
      set_error(error, "IHDR appears more than once");
      return false;
    } else if (is_plte) {
      if (plte_seen || idat_seen || color_type == 0U || color_type == 4U
          || length == 0U || length > 768U || length % 3U != 0U
          || (color_type == 3U && length / 3U > (1U << bit_depth))) {
        set_error(error, "PLTE length or ordering is invalid for IHDR");
        return false;
      }
      plte_seen = true;
    } else if (is_idat) {
      if (idat_closed || (color_type == 3U && !plte_seen)) {
        set_error(error, "IDAT ordering is invalid for IHDR and PLTE");
        return false;
      }
      if (!idat_seen) {
        if (!inflater.Initialize(&shape, error)) {
          return false;
        }
        idat_seen = true;
      }
      if (!inflater.Feed(data, length, error)) {
        return false;
      }
    } else if (is_iend) {
      if (length != 0U || !idat_seen || !inflater.IsComplete()) {
        set_error(error, "IEND precedes a complete IHDR-shaped IDAT stream");
        return false;
      }
      offset = crc_offset + 4U;
      if (offset != bytes.size()) {
        set_error(error, "bytes or chunks follow IEND");
        return false;
      }
      return true;
    } else {
      if ((type[0] & 0x20U) == 0U) {
        set_error(error, "unknown critical chunk is not supported");
        return false;
      }
      if (idat_seen) {
        idat_closed = true;
      }
    }
    offset = crc_offset + 4U;
  }

  set_error(error, "IEND is missing");
  return false;
}

}  // namespace agentcodi
