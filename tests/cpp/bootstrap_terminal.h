#ifndef AGENTCODI_TESTS_BOOTSTRAP_TERMINAL_H
#define AGENTCODI_TESTS_BOOTSTRAP_TERMINAL_H

#include "bootstrap_response.h"

#include <cstddef>
#include <string>

namespace agentcodi_test {

inline bool ExtractBootstrapJsonString(
    const std::string& line, const std::string& field, std::string* value) {
  const std::string marker = "\"" + field + "\":\"";
  const std::size_t begin = line.find(marker);
  if (begin == std::string::npos) return false;
  const std::size_t value_begin = begin + marker.size();
  for (std::size_t end = value_begin; end < line.size(); ++end) {
    if (line[end] == '\\') {
      if (++end == line.size()) return false;
    } else if (line[end] == '"') {
      // Keep JSON escapes; bootstrap markers and Base64 use plain ASCII.
      *value = line.substr(value_begin, end - value_begin);
      return true;
    }
  }
  return false;
}

inline bool ReadBootstrapCommandOutput(const std::string& line, std::string* output) {
  // Android libc can report unavailable vendor properties on stderr even when
  // a command succeeds. Validate both bounded string fields and exit status;
  // only stdout may satisfy the command's required functional markers.
  std::string standard_error;
  constexpr std::size_t kMaximumEscapedStreamBytes = 65536U * 6U;
  const BootstrapResponseEnvelope response = BootstrapResponse(line);
  return response.id != 0 && !response.error
      && response.has_exit_code && response.exit_code == 0
      && ExtractBootstrapJsonString(line, "stdout", output)
      && ExtractBootstrapJsonString(line, "stderr", &standard_error)
      && output->size() <= kMaximumEscapedStreamBytes
      && standard_error.size() <= kMaximumEscapedStreamBytes;
}

inline int BootstrapBase64Value(char character) {
  if (character >= 'A' && character <= 'Z') return character - 'A';
  if (character >= 'a' && character <= 'z') return character - 'a' + 26;
  if (character >= '0' && character <= '9') return character - '0' + 52;
  if (character == '+') return 62;
  if (character == '/') return 63;
  return -1;
}

constexpr std::size_t kMaximumBootstrapOutputBytes = 128U * 1024U;

inline bool AppendBootstrapBase64(const std::string& encoded, std::string* decoded) {
  if (encoded.size() % 4U != 0U || encoded.size() > 88U * 1024U) return false;
  for (std::size_t index = 0U; index < encoded.size(); index += 4U) {
    const int first = BootstrapBase64Value(encoded[index]);
    const int second = BootstrapBase64Value(encoded[index + 1U]);
    const bool third_padding = encoded[index + 2U] == '=';
    const bool fourth_padding = encoded[index + 3U] == '=';
    const int third = third_padding ? 0 : BootstrapBase64Value(encoded[index + 2U]);
    const int fourth = fourth_padding ? 0 : BootstrapBase64Value(encoded[index + 3U]);
    if (first < 0 || second < 0 || third < 0 || fourth < 0
        || (third_padding && !fourth_padding)
        || (third_padding && (second & 15) != 0)
        || (fourth_padding && (third & 3) != 0)
        || ((third_padding || fourth_padding) && index + 4U != encoded.size())) return false;
    const std::size_t bytes = third_padding ? 1U : (fourth_padding ? 2U : 3U);
    if (decoded->size() > kMaximumBootstrapOutputBytes - bytes) return false;
    decoded->push_back(static_cast<char>((first << 2) | (second >> 4)));
    if (!third_padding) decoded->push_back(static_cast<char>((second << 4) | (third >> 2)));
    if (!fourth_padding) decoded->push_back(static_cast<char>((third << 6) | fourth));
  }
  return true;
}

enum class BootstrapTerminalEvent { kPending, kSendInput, kComplete, kFailed };

// PTY chunk boundaries depend on scheduling and sandbox syscall interception.
// Bound useful output by bytes; count only events that add no output against
// the notification budget. Even one-byte chunks remain finite (128 KiB + 64
// events), under the command deadline and the build's outer bootstrap timeout.
class BootstrapTerminal {
 public:
  BootstrapTerminalEvent Consume(const std::string& line) {
    if (!failure_.empty()) return BootstrapTerminalEvent::kFailed;
    if (line.size() > 1024U * 1024U) return Fail("Terminal frame exceeded its byte limit");
    const BootstrapResponseEnvelope response = BootstrapResponse(line);
    if (response.error) return Fail(BootstrapRpcErrorReason(line));

    const std::size_t previous_bytes = output_.size();
    bool send_input = false;
    std::string method;
    std::string process_id;
    if (ExtractBootstrapJsonString(line, "method", &method)
        && method == "command/exec/outputDelta"
        && ExtractBootstrapJsonString(line, "processId", &process_id)
        && process_id == "agentcodi-build-terminal") {
      std::string delta;
      if (!ExtractBootstrapJsonString(line, "deltaBase64", &delta)
          || !AppendBootstrapBase64(delta, &output_)) {
        return Fail("Terminal output notification was not bounded Base64");
      }
      if (line.find("\"capReached\":true") != std::string::npos) {
        return Fail("Terminal output was truncated by the app-server");
      }
    } else if (response.id == 7) {
      if (resize_acknowledged_ || line.find("\"result\":{}") == std::string::npos) {
        return Fail("Terminal resize response was malformed or duplicated");
      }
      resize_acknowledged_ = true;
      send_input = true;
    } else if (response.id == 8) {
      if (!resize_acknowledged_ || write_acknowledged_
          || line.find("\"result\":{}") == std::string::npos) {
        return Fail("Terminal input response was malformed or duplicated");
      }
      write_acknowledged_ = true;
    } else if (response.id == 6) {
      if (response.has_exit_code && response.exit_code != 0) {
        return Fail(BootstrapCommandFailure(line));
      }
      if (command_completed_ || !response.has_exit_code
          || line.find("\"stdout\":\"\"") == std::string::npos
          || line.find("\"stderr\":\"\"") == std::string::npos) {
        return Fail("Terminal command failed or returned a malformed completion");
      }
      command_completed_ = true;
    }
    if (output_.size() == previous_bytes && ++non_output_events_ > 64U) {
      return Fail("Terminal bootstrap received too many events without output progress");
    }
    if (resize_acknowledged_ && write_acknowledged_ && command_completed_ && HasExpectedOutput()) {
      return BootstrapTerminalEvent::kComplete;
    }
    return send_input ? BootstrapTerminalEvent::kSendInput : BootstrapTerminalEvent::kPending;
  }

  const char* failure() const { return failure_.c_str(); }

  std::string IncompleteSummary() const {
    return std::string("Terminal bootstrap incomplete: resize=")
        + (resize_acknowledged_ ? "yes" : "no")
        + ", input=" + (write_acknowledged_ ? "yes" : "no")
        + ", completion=" + (command_completed_ ? "yes" : "no")
        + ", expected-output=" + (HasExpectedOutput() ? "yes" : "no");
  }

 private:
  bool HasExpectedOutput() const {
    return output_.find("terminal-protocol-smoke") != std::string::npos
        && output_.find("Enabled packaged Node.js 24.18.0") != std::string::npos
        && output_.find("Enabled packaged ripgrep 15.2.0") != std::string::npos
        && output_.find("v24.18.0") != std::string::npos
        && output_.find("ripgrep 15.2.0") != std::string::npos;
  }

  BootstrapTerminalEvent Fail(const std::string& reason) {
    failure_ = reason;
    return BootstrapTerminalEvent::kFailed;
  }

  std::string output_;
  std::size_t non_output_events_ = 0U;
  bool resize_acknowledged_ = false;
  bool write_acknowledged_ = false;
  bool command_completed_ = false;
  std::string failure_;
};

}  // namespace agentcodi_test

#endif
