#include "app_server_process.h"

#include <cerrno>
#include <chrono>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <thread>
#include <utility>

#include <fcntl.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

namespace agentcodi {
namespace {

constexpr int kStillRunning = INT_MIN;

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

bool set_child_environment(const ProcessConfig& config) {
  const std::string path = config.library_directory + ":/system/bin:/system/xbin";
  return setenv("HOME", config.home_directory.c_str(), 1) == 0
      && setenv("CODEX_HOME", config.codex_home.c_str(), 1) == 0
      && setenv("TMPDIR", config.temporary_directory.c_str(), 1) == 0
      && setenv("TMP", config.temporary_directory.c_str(), 1) == 0
      && setenv("TEMP", config.temporary_directory.c_str(), 1) == 0
      && setenv("PATH", path.c_str(), 1) == 0
      && setenv("SHELL", "/system/bin/sh", 1) == 0
      && setenv("LD_LIBRARY_PATH", config.library_directory.c_str(), 1) == 0
      && setenv("CODEX_SELF_EXE", config.executable.c_str(), 1) == 0
      && setenv(
          "CODEX_CODE_MODE_HOST_PATH",
          config.code_mode_host_executable.c_str(),
          1) == 0;
}

}  // namespace

std::vector<std::string> CodexAppServerArguments() {
  return {
      "app-server",
      "--stdio",
      "--strict-config",
      "-c",
      "cli_auth_credentials_store=\"file\"",
      "-c",
      "approval_policy=\"on-request\"",
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
      || !canonical_directory(
          requested_config.working_directory,
          "Workspace",
          &config.working_directory,
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
  if (contains_path(config.working_directory, config.code_mode_host_executable)
      || contains_path(config.codex_home, config.code_mode_host_executable)) {
    *error = "Code-mode host must remain outside workspace and Codex home";
    return nullptr;
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
    if (chdir(config.working_directory.c_str()) != 0
        || !set_child_environment(config)) {
      report_child_error_and_exit(exec_status[1], errno);
    }
    execv(config.executable.c_str(), arguments.data());
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
  return std::shared_ptr<AppServerProcess>(new AppServerProcess(pid, communication[0]));
}

AppServerProcess::AppServerProcess(pid_t pid, int socket_fd)
    : pid_(pid), socket_fd_(socket_fd), exit_code_(kStillRunning) {}

AppServerProcess::~AppServerProcess() {
  Stop(100);
}

bool AppServerProcess::WriteLine(
    const std::string& line,
    std::size_t maximum_bytes,
    std::string* error) {
  if (error == nullptr) {
    return false;
  }
  error->clear();
  if (line.empty() || line.size() > maximum_bytes
      || line.find('\0') != std::string::npos
      || line.find('\n') != std::string::npos
      || line.find('\r') != std::string::npos) {
    *error = "Outgoing app-server line violates the framing limit";
    return false;
  }

  std::lock_guard<std::mutex> write_guard(write_mutex_);
  int descriptor = DuplicateSocket(error);
  if (descriptor < 0) {
    return false;
  }
  const std::string framed = line + '\n';
  std::size_t written = 0;
  while (written < framed.size()) {
    const ssize_t count = send(
        descriptor,
        framed.data() + written,
        framed.size() - written,
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
      if (newline > maximum_bytes) {
        read_buffer_.erase(0, newline + 1U);
        *error = "Incoming app-server line exceeds the framing limit";
        close_if_open(descriptor);
        return LineReadStatus::kTooLarge;
      }
      *line = read_buffer_.substr(0, newline);
      read_buffer_.erase(0, newline + 1U);
      if (!line->empty() && line->back() == '\r') {
        line->pop_back();
      }
      close_if_open(descriptor);
      return LineReadStatus::kLine;
    }
    if (read_buffer_.size() > maximum_bytes) {
      read_buffer_.clear();
      *error = "Incoming app-server line exceeds the framing limit";
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
      return LineReadStatus::kEndOfStream;
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
