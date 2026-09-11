#include "app_server_process.h"

#include <cerrno>
#include <chrono>
#include <climits>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <future>
#include <iostream>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

namespace {

void require(bool condition, const char* message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << '\n';
    std::exit(1);
  }
}

// Test child: stay silent indefinitely, even across SIGTERM, until the
// supervisor explicitly stops the process tree. There is no runtime deadline.
int run_child(bool partial_frame) {
  if (signal(SIGTERM, SIG_IGN) == SIG_ERR) {
    return 2;
  }
  const pid_t descendant = fork();
  if (descendant < 0) {
    return 3;
  }
  if (descendant == 0) {
    if (setsid() < 0) {
      _exit(4);
    }
    while (true) {
      pause();
    }
  }
  std::cout << getpid() << ' ' << descendant << '\n' << std::flush;
  if (partial_frame) {
    std::cout << "{\"method\":\"item/started\"" << std::flush;
  }
  while (true) {
    pause();
  }
}

void stop_silent_child(const agentcodi::ProcessConfig& fixture, bool partial_frame) {
  agentcodi::ProcessConfig config = fixture;
  config.arguments = {partial_frame ? "--partial-frame-child" : "--silent-child"};
  std::string error;
  auto process = agentcodi::AppServerProcess::Start(config, &error);
  if (process == nullptr) {
    std::cerr << error << '\n';
  }
  require(process != nullptr, "runtime child starts");
  auto ready = std::async(std::launch::async, [&process]() {
    std::string line;
    std::string read_error;
    const auto status = process->ReadLine(4096U, &line, &read_error);
    require(status == agentcodi::LineReadStatus::kLine, "runtime fixture announces children");
    return line;
  });
  // Deadlines here bound the test harness, never the production runtime.
  if (ready.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
    process->Stop(0);
    require(false, "runtime fixture becomes ready");
  }
  const std::string children = ready.get();
  const auto separator = children.find(' ');
  require(separator != std::string::npos, "runtime reports both child identifiers");
  const pid_t parent = static_cast<pid_t>(std::stol(children.substr(0, separator)));
  const pid_t descendant = static_cast<pid_t>(std::stol(children.substr(separator + 1)));
  require(parent > 0 && descendant > 0, "runtime child identifiers are positive");

  auto reader = std::async(std::launch::async, [&process]() {
    std::string line;
    std::string read_error;
    return process->ReadLine(4096U, &line, &read_error);
  });
  const bool stayed_open = reader.wait_for(std::chrono::milliseconds(250))
      == std::future_status::timeout;
  const bool stayed_running = process->PollExitCode() == INT_MIN;
  const int stopped = process->Stop(25);
  const bool reader_released = reader.wait_for(std::chrono::seconds(5))
      == std::future_status::ready;
  require(stayed_open, "silence must leave the runtime reader waiting for data");
  require(stayed_running, "idle runtime stays alive until explicit stop");
  require(stopped != INT_MIN, "explicit stop terminates the runtime");
  require(reader_released, "runtime stop releases the blocked JSONL reader");
  require(reader.get() != agentcodi::LineReadStatus::kLine,
          "stop cannot publish an incomplete frame as a result");
  require(process->Stop(0) == stopped, "repeated stop is idempotent");
  require(kill(parent, 0) == -1 && errno == ESRCH, "runtime child is reaped");
  require(kill(descendant, 0) == -1 && errno == ESRCH,
          "detached SIGTERM-ignoring work is killed and reaped");
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc == 2 && std::strcmp(argv[1], "--silent-child") == 0) {
    return run_child(false);
  }
  if (argc == 2 && std::strcmp(argv[1], "--partial-frame-child") == 0) {
    return run_child(true);
  }
  require(argc == 2, "provide the host native library directory");
  char executable[PATH_MAX];
  require(realpath(argv[0], executable) != nullptr, "resolve test executable");
  char temporary[] = "/tmp/agentcodi-runtime-stop-XXXXXX";
  char* created = mkdtemp(temporary);
  require(created != nullptr, "create private runtime fixture");
  const std::string root = created;
  const std::vector<std::string> directories = {
      "/workspace", "/workspace/toolchain", "/tool-bin", "/tool-runtime",
      "/codex-home", "/home", "/state", "/temporary"};
  for (const auto& directory : directories) {
    require(mkdir((root + directory).c_str(), 0700) == 0, "create runtime directory");
  }
  const std::vector<std::string> aliases = {
      "node", "npm", "python", "python3", "rg", "agentcodi-toolchain"};
  for (const auto& alias : aliases) {
    require(symlink(executable, (root + "/tool-bin/" + alias).c_str()) == 0,
            "create verified fixture alias");
  }
  agentcodi::ProcessConfig config;
  config.executable = executable;
  config.code_mode_host_executable = executable;
  config.shell_executable = executable;
  config.node_executable = executable;
  config.python_executable = executable;
  config.ripgrep_executable = executable;
  config.working_directory = root + "/workspace";
  config.toolchain_directory = root + "/workspace/toolchain";
  config.tool_binary_directory = root + "/tool-bin";
  config.tool_runtime_directory = root + "/tool-runtime";
  config.codex_home = root + "/codex-home";
  config.home_directory = root + "/home";
  config.state_directory = root + "/state";
  config.temporary_directory = root + "/temporary";
  config.library_directory = argv[1];

  stop_silent_child(config, false);
  // A fresh child after completed stop also verifies release of the single
  // process supervisor, including ownership of detached descendants.
  stop_silent_child(config, true);

  for (const auto& alias : aliases) {
    require(unlink((root + "/tool-bin/" + alias).c_str()) == 0, "remove fixture alias");
  }
  for (auto directory = directories.rbegin(); directory != directories.rend(); ++directory) {
    require(rmdir((root + *directory).c_str()) == 0, "remove runtime fixture directory");
  }
  require(rmdir(root.c_str()) == 0, "remove private fixture root");
  std::cout << "Runtime stop C++ tests passed: 2\n";
  return 0;
}
