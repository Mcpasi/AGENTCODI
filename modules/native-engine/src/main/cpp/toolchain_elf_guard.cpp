#include "toolchain_policy.h"

#include <cerrno>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

#ifndef AGENTCODI_GUARDED_TOOL
#error "AGENTCODI_GUARDED_TOOL must select Node (1), Python (2), or ripgrep (3)"
#endif

namespace {

constexpr std::size_t kMaximumCommandLineBytes = 256U * 1024U;
constexpr std::size_t kMaximumArgumentCount = 4096U;

#if AGENTCODI_GUARDED_TOOL == 1
constexpr agentcodi::GuardedTool kGuardedTool = agentcodi::GuardedTool::kNode;
constexpr const char* kExpectedExecutableName = "libnode.so";
#elif AGENTCODI_GUARDED_TOOL == 2
constexpr agentcodi::GuardedTool kGuardedTool = agentcodi::GuardedTool::kPython;
constexpr const char* kExpectedExecutableName = "libpython-bin.so";
#elif AGENTCODI_GUARDED_TOOL == 3
constexpr agentcodi::GuardedTool kGuardedTool = agentcodi::GuardedTool::kRipgrep;
constexpr const char* kExpectedExecutableName = "libripgrep.so";
#else
#error "Unsupported AGENTCODI_GUARDED_TOOL value"
#endif

std::string executable_name(const std::string& value) {
  const std::size_t separator = value.rfind('/');
  return separator == std::string::npos
      ? value
      : value.substr(separator + 1U);
}

bool read_process_arguments(
    std::vector<std::string>* arguments,
    std::string* error) {
  const int descriptor = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
  if (descriptor < 0) {
    *error = std::string("Guarded tool command line: ") + std::strerror(errno);
    return false;
  }
  std::string bytes;
  char buffer[4096];
  while (bytes.size() <= kMaximumCommandLineBytes) {
    const ssize_t count = read(descriptor, buffer, sizeof(buffer));
    if (count > 0) {
      bytes.append(buffer, static_cast<std::size_t>(count));
    } else if (count == 0) {
      break;
    } else if (errno != EINTR) {
      const int saved_errno = errno;
      close(descriptor);
      *error = std::string("Guarded tool command line: ")
          + std::strerror(saved_errno);
      return false;
    }
  }
  close(descriptor);
  if (bytes.empty() || bytes.size() > kMaximumCommandLineBytes
      || bytes.back() != '\0') {
    *error = "Guarded tool command line is malformed or oversized";
    return false;
  }
  std::size_t begin = 0U;
  std::vector<std::string> values;
  while (begin < bytes.size()) {
    const std::size_t end = bytes.find('\0', begin);
    if (end == std::string::npos || values.size() >= kMaximumArgumentCount) {
      *error = "Guarded tool command line has too many arguments";
      return false;
    }
    values.push_back(bytes.substr(begin, end - begin));
    begin = end + 1U;
  }
  if (values.empty()) {
    *error = "Guarded tool command line omitted argv[0]";
    return false;
  }
  arguments->assign(values.begin() + 1, values.end());
  return true;
}

bool validate_executable_identity(std::string* error) {
  char executable[4096];
  const ssize_t length = readlink(
      "/proc/self/exe",
      executable,
      sizeof(executable) - 1U);
  if (length <= 0 || static_cast<std::size_t>(length) >= sizeof(executable)) {
    *error = "Guarded tool executable identity is unavailable";
    return false;
  }
  executable[length] = '\0';
  if (executable_name(executable) != kExpectedExecutableName) {
    *error = "Guarded tool rejected a non-canonical executable entry point";
    return false;
  }
  return true;
}

void write_error(const std::string& error) {
  const std::string line = error + "\n";
  std::size_t written = 0U;
  while (written < line.size()) {
    const ssize_t count = write(
        STDERR_FILENO,
        line.data() + written,
        line.size() - written);
    if (count > 0) {
      written += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      break;
    }
  }
}

__attribute__((constructor)) void enforce_guarded_invocation() {
  std::string error;
  std::vector<std::string> arguments;
  int exit_code = 126;
  if (!validate_executable_identity(&error)
      || !read_process_arguments(&arguments, &error)
      || !agentcodi::PrepareGuardedToolInvocation(
          kGuardedTool,
          arguments,
          &error,
          &exit_code)) {
    if (error.empty()) {
      error = "Guarded tool invocation failed closed";
    }
    write_error(error);
    _exit(exit_code);
  }
}

}  // namespace

extern "C" __attribute__((visibility("default")))
void AgentCodiToolGuardLinked() {
}
