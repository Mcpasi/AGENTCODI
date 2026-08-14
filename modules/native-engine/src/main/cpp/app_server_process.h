#ifndef AGENTCODI_APP_SERVER_PROCESS_H
#define AGENTCODI_APP_SERVER_PROCESS_H

#include <atomic>
#include <cstddef>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <sys/types.h>

namespace agentcodi {

struct ProcessConfig {
  std::string executable;
  std::string code_mode_host_executable;
  std::string working_directory;
  std::string codex_home;
  std::string home_directory;
  std::string temporary_directory;
  std::string library_directory;
  std::vector<std::string> arguments;
};

enum class LineReadStatus {
  kLine,
  kEndOfStream,
  kError,
  kTooLarge,
};

enum class InboundLineCompactionStatus {
  kNotApplicable,
  kCompacted,
  kInvalid,
};

std::vector<std::string> CodexAppServerArguments(const ProcessConfig& config);

InboundLineCompactionStatus CompactInboundImagePayloads(
    const std::string& line,
    std::size_t maximum_bytes,
    std::string* compacted);

InboundLineCompactionStatus MaterializeAndCompactInboundImagePayloads(
    const std::string& line,
    std::size_t maximum_bytes,
    const std::string& workspace_directory,
    const std::string& temporary_directory,
    std::string* prepared,
    std::string* error);

class AppServerProcess final {
 public:
  static std::shared_ptr<AppServerProcess> Start(
      const ProcessConfig& config,
      std::string* error);

  ~AppServerProcess();

  AppServerProcess(const AppServerProcess&) = delete;
  AppServerProcess& operator=(const AppServerProcess&) = delete;

  bool WriteLine(
      const std::string& line,
      std::size_t maximum_bytes,
      std::string* error);
  bool WriteBytes(
      std::vector<unsigned char>* line,
      std::size_t length,
      std::size_t maximum_bytes,
      std::string* error);
  LineReadStatus ReadLine(
      std::size_t maximum_bytes,
      std::string* line,
      std::string* error);
  int PollExitCode();
  int Stop(int timeout_milliseconds);

 private:
  AppServerProcess(
      pid_t pid,
      int socket_fd,
      std::string workspace_directory,
      std::string temporary_directory);

  int DuplicateSocket(std::string* error);

  std::mutex state_mutex_;
  std::mutex read_mutex_;
  std::mutex write_mutex_;
  pid_t pid_;
  std::atomic<int> socket_fd_;
  int exit_code_;
  const std::string workspace_directory_;
  const std::string temporary_directory_;
  std::string read_buffer_;
};

}  // namespace agentcodi

#endif
