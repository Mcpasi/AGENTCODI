#ifndef AGENTCODI_APP_SERVER_PROCESS_H
#define AGENTCODI_APP_SERVER_PROCESS_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <sys/types.h>

namespace agentcodi {

struct ProcessConfig {
  std::string executable;
  std::string code_mode_host_executable;
  std::string shell_executable;
  std::string node_executable;
  std::string python_executable;
  std::string ripgrep_executable;
  std::string working_directory;
  std::string toolchain_directory;
  std::string tool_binary_directory;
  std::string tool_runtime_directory;
  std::string codex_home;
  std::string home_directory;
  std::string state_directory;
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
    const std::string& state_directory,
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
      std::vector<std::pair<pid_t, std::uint64_t>> baseline_children,
      int previous_subreaper_state,
      std::string workspace_directory,
      std::string temporary_directory,
      std::string state_directory);

  int DuplicateSocket(std::string* error);
  void ReapOwnedChildren(bool wait_for_exit);
  void SignalOwnedChildren(int signal_number);
  bool OwnedChildrenRemain();
  void ReleaseSupervisor();

  std::mutex state_mutex_;
  std::mutex read_mutex_;
  std::mutex write_mutex_;
  pid_t pid_;
  pid_t process_group_id_;
  std::atomic<int> socket_fd_;
  int exit_code_;
  std::vector<std::pair<pid_t, std::uint64_t>> baseline_children_;
  std::vector<std::pair<pid_t, std::uint64_t>> term_signaled_children_;
  int previous_subreaper_state_;
  bool owns_supervisor_;
  const std::string workspace_directory_;
  const std::string temporary_directory_;
  const std::string state_directory_;
  std::string read_buffer_;
};

}  // namespace agentcodi

#endif
