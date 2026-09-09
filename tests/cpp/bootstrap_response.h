#ifndef AGENTCODI_TESTS_BOOTSTRAP_RESPONSE_H
#define AGENTCODI_TESTS_BOOTSTRAP_RESPONSE_H

#include <cstddef>
#include <string>

namespace agentcodi_test {

// Inspect only the bounded response envelope. Nested tool/thread errors and
// quoted output are not RPC failures. Diagnostics never copy runtime messages.
struct BootstrapResponseEnvelope {
  int id = 0;
  bool error = false;
  bool has_exit_code = false;
  int exit_code = 0;
};

inline BootstrapResponseEnvelope BootstrapResponse(const std::string& line) {
  if (line.size() > 1024U * 1024U) return {};
  int depth = 0;
  int response_id = 0;
  bool error_object = false;
  bool in_result = false;
  bool has_exit_code = false;
  int exit_code = 0;
  for (std::size_t index = 0; index < line.size(); ++index) {
    const char character = line[index];
    if (character == '"') {
      const std::size_t begin = index++;
      for (; index < line.size(); ++index) {
        if (line[index] == '\\') ++index;
        else if (line[index] == '"') break;
      }
      if (index >= line.size()) return {};
      if (depth != 1 && !(depth == 2 && in_result)) continue;
      std::size_t value = line.find_first_not_of(" \r\n\t", index + 1U);
      if (value == std::string::npos || line[value] != ':') continue;
      value = line.find_first_not_of(" \r\n\t", value + 1U);
      if (value == std::string::npos) return {};
      const std::string key = line.substr(begin, index - begin + 1U);
      if (depth == 1 && key == "\"result\"") {
        in_result = line[value] == '{';
      } else if (depth == 1 && key == "\"error\"") {
        error_object = line[value] == '{';
      } else if ((depth == 1 && key == "\"id\"")
                 || (depth == 2 && in_result && key == "\"exitCode\"")) {
        const bool is_exit_code = depth == 2;
        if (is_exit_code && has_exit_code) return {};
        int parsed = 0;
        std::size_t end = value;
        const bool negative = is_exit_code && line[end] == '-';
        if (negative) ++end;
        const std::size_t digits = end;
        while (end < line.size() && line[end] >= '0' && line[end] <= '9') {
          if (parsed > 100000) return {};
          parsed = parsed * 10 + line[end++] - '0';
        }
        if (end == digits || end == line.size()
            || (end - digits > 1U && line[digits] == '0')
            || std::string(",} \r\n\t").find(line[end]) == std::string::npos) return {};
        if (is_exit_code) {
          has_exit_code = true;
          exit_code = negative ? -parsed : parsed;
        } else {
          response_id = parsed;
        }
      }
    } else if (character == '{' || character == '[') {
      if (++depth > 128) return {};
    } else if (character == '}' || character == ']') {
      if (--depth < 0) return {};
      if (depth == 1) in_result = false;
    }
  }
  return depth == 0 ? BootstrapResponseEnvelope {
                         response_id, error_object, has_exit_code, exit_code}
                    : BootstrapResponseEnvelope {};
}

inline int BootstrapRpcErrorId(const std::string& line) {
  const BootstrapResponseEnvelope response = BootstrapResponse(line);
  return response.error ? response.id : 0;
}

// Fixed classifications also cover a command result's stderr without copying
// arbitrary paths, instructions or other runtime-provided text into CI logs.
inline const char* BootstrapSandboxFailureReason(const std::string& line) {
  if (line.find("SIGSYS") != std::string::npos) {
    return "The Android sandbox was terminated by SIGSYS; check the device seccomp policy and the runtime's isolated capability probes.";
  }
  if (line.find("refusing an unverifiable syscall") != std::string::npos) {
    if (line.find("Invalid argument (os error 22)") != std::string::npos) {
      return "The Android sandbox could not inspect a syscall argument (EINVAL); check arm64 tagged-pointer handling in the packaged runtime.";
    }
    return "The Android sandbox could not inspect a syscall argument.";
  }
  if (line.find("filesystem syscall interception could not be verified") != std::string::npos
      || line.find("filesystem syscall interception produced no events") != std::string::npos) {
    return "The Android sandbox could not verify syscall interception before command execution.";
  }
  if (line.find("failed to load AGENTS.md instructions") != std::string::npos) {
    return "The packaged runtime could not load workspace AGENTS.md through its filesystem sandbox.";
  }
  if (line.find("read-restricted or deny-read filesystem policies") != std::string::npos) {
    return "The packaged Android sandbox cannot enforce the required restricted filesystem reads.";
  }
  if (line.find("filesystem sandbox cannot be enforced on this executor") != std::string::npos) {
    return "The packaged runtime has no usable filesystem sandbox for this executor.";
  }
  if (line.find("codex-linux-sandbox: failed to execute") != std::string::npos) {
    return "The Android sandbox could not start the packaged command executable.";
  }
  return nullptr;
}

inline const char* BootstrapRpcErrorReason(const std::string& line) {
  if (const char* reason = BootstrapSandboxFailureReason(line)) return reason;
  if (line.find("no active command/exec for process id") != std::string::npos) {
    return "Terminal control failed because command/exec did not start or already ended.";
  }
  return "The packaged app-server rejected the bootstrap RPC.";
}

inline std::string BootstrapCommandFailure(const std::string& line) {
  const BootstrapResponseEnvelope response = BootstrapResponse(line);
  const std::string prefix = "Bootstrap RPC " + std::to_string(response.id);
  if (!response.has_exit_code) {
    return prefix + " returned a command completion without a valid exit code.";
  }
  if (response.exit_code == 0) {
    return prefix + " returned malformed command output despite exit code 0.";
  }
  std::string failure = prefix + " command exited with code "
      + std::to_string(response.exit_code) + ".";
  if (response.exit_code == 124) {
    failure += " The command exceeded its execution deadline.";
  } else if (response.exit_code == 41) {
    failure += " The protected executor could not read the workspace fixture.";
  } else if (response.exit_code == 42) {
    failure += " The protected executor could not write the workspace fixture.";
  } else if (response.exit_code == 43 || response.exit_code == 44) {
    failure += " The protected executor allowed access to the synthetic private sibling.";
  }
  if (const char* reason = BootstrapSandboxFailureReason(line)) {
    failure += " ";
    failure += reason;
  }
  return failure;
}

}  // namespace agentcodi_test

#endif
