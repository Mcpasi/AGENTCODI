#include "app_server_process.h"

#include <chrono>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <future>
#include <iostream>
#include <memory>
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

// Every fixture child publishes its whole script with a single write, so the
// supervisor observes it as one transport chunk. Frame boundaries must come
// from the newlines inside that chunk, never from the chunk itself.
int publish_once(const std::string& script) {
  if (write(STDOUT_FILENO, script.data(), script.size())
      != static_cast<ssize_t>(script.size())) {
    return 2;
  }
  while (true) {
    pause();
  }
}

int run_nul_frame_child() {
  std::string script = "{\"first\":1}\n{\"second\":2}\n{\"third\":";
  script.push_back('\0');
  script += "3}\n{\"fourth\":4}\n";
  return publish_once(script);
}

int run_image_lifecycle_child() {
  // The app-server announces an image item before any bytes exist. Such an
  // item reports no result, and may omit its status as well.
  const std::string script =
      "{\"method\":\"item/started\",\"params\":{\"item\":"
      "{\"id\":\"item_0\",\"type\":\"imageGeneration\"}}}\n"
      "{\"method\":\"item/updated\",\"params\":{\"item\":"
      "{\"id\":\"item_0\",\"type\":\"imageGeneration\","
      "\"status\":\"in_progress\",\"result\":null}}}\n"
      "{\"method\":\"item/completed\",\"params\":{\"item\":"
      "{\"id\":\"item_0\",\"type\":\"imageGeneration\","
      "\"status\":\"completed\",\"result\":\"\"}}}\n";
  return publish_once(script);
}

struct ReadOutcome {
  agentcodi::LineReadStatus status;
  std::string line;
  std::string error;
};

// Deadlines here bound the test harness, never the production runtime.
ReadOutcome read_next(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    const char* description) {
  auto pending = std::async(std::launch::async, [&process]() {
    ReadOutcome outcome {};
    outcome.status = process->ReadLine(4096U, &outcome.line, &outcome.error);
    return outcome;
  });
  if (pending.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
    process->Stop(0);
    require(false, description);
  }
  return pending.get();
}

std::shared_ptr<agentcodi::AppServerProcess> start_fixture(
    const agentcodi::ProcessConfig& fixture,
    const char* mode) {
  agentcodi::ProcessConfig config = fixture;
  config.arguments = {mode};
  std::string error;
  auto process = agentcodi::AppServerProcess::Start(config, &error);
  if (process == nullptr) {
    std::cerr << error << '\n';
  }
  require(process != nullptr, "framing fixture starts");
  return process;
}

// A NUL byte corrupts the frame that carries it and nothing else. Frames that
// already arrived complete in the same transport chunk must still be
// delivered, and the reader must resynchronize on the following newline.
void nul_byte_corrupts_only_its_own_frame(
    const agentcodi::ProcessConfig& fixture) {
  auto process = start_fixture(fixture, "--nul-frame-child");

  const ReadOutcome first = read_next(process, "first frame arrives");
  require(first.status == agentcodi::LineReadStatus::kLine
              && first.line == "{\"first\":1}",
          "deliver a complete frame that precedes a NUL byte");

  const ReadOutcome second = read_next(process, "second frame arrives");
  require(second.status == agentcodi::LineReadStatus::kLine
              && second.line == "{\"second\":2}",
          "deliver every complete frame that precedes a NUL byte");

  const ReadOutcome corrupted = read_next(process, "corrupt frame is reported");
  require(corrupted.status == agentcodi::LineReadStatus::kError
              && corrupted.line.empty()
              && corrupted.error.find("NUL byte") != std::string::npos,
          "reject only the frame that carries the NUL byte");

  const ReadOutcome resumed = read_next(process, "reader resynchronizes");
  require(resumed.status == agentcodi::LineReadStatus::kLine
              && resumed.line == "{\"fourth\":4}",
          "resynchronize on the frame after a rejected NUL frame");

  require(process->Stop(0) != INT_MIN, "framing fixture stops");
}

// An image item that reports no result yet carries no image bytes. It must
// reach the caller unchanged instead of invalidating the line that carries it.
void in_progress_image_items_pass_through(
    const agentcodi::ProcessConfig& fixture) {
  auto process = start_fixture(fixture, "--image-lifecycle-child");

  const ReadOutcome started = read_next(process, "started image item arrives");
  require(started.status == agentcodi::LineReadStatus::kLine
              && started.line.find("\"method\":\"item/started\"")
                  != std::string::npos
              && started.line.find("\"type\":\"imageGeneration\"")
                  != std::string::npos
              && started.line.find("\"result\"") == std::string::npos,
          "forward an image item that reports neither status nor result");

  const ReadOutcome updated = read_next(process, "updated image item arrives");
  require(updated.status == agentcodi::LineReadStatus::kLine
              && updated.line.find("\"status\":\"in_progress\"")
                  != std::string::npos
              && updated.line.find("\"result\":null") != std::string::npos,
          "forward an in-progress image item whose result is still null");

  const ReadOutcome completed = read_next(process, "completed image item arrives");
  require(completed.status == agentcodi::LineReadStatus::kLine
              && completed.line.find("\"status\":\"completed\"")
                  != std::string::npos,
          "forward a completed image item that carries no inline bytes");

  require(process->Stop(0) != INT_MIN, "image fixture stops");
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc == 2 && std::strcmp(argv[1], "--nul-frame-child") == 0) {
    return run_nul_frame_child();
  }
  if (argc == 2 && std::strcmp(argv[1], "--image-lifecycle-child") == 0) {
    return run_image_lifecycle_child();
  }
  require(argc == 2, "provide the host native library directory");
  char executable[PATH_MAX];
  require(realpath(argv[0], executable) != nullptr, "resolve test executable");
  char temporary[] = "/tmp/agentcodi-runtime-framing-XXXXXX";
  char* created = mkdtemp(temporary);
  require(created != nullptr, "create private runtime fixture");
  const std::string root = created;
  const std::vector<std::string> directories = {
      "/workspace", "/workspace/toolchain", "/tool-bin", "/tool-runtime",
      "/codex-home", "/home", "/state", "/temporary"};
  for (const auto& directory : directories) {
    require(mkdir((root + directory).c_str(), 0700) == 0,
            "create runtime directory");
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

  nul_byte_corrupts_only_its_own_frame(config);
  in_progress_image_items_pass_through(config);

  for (const auto& alias : aliases) {
    require(unlink((root + "/tool-bin/" + alias).c_str()) == 0,
            "remove fixture alias");
  }
  for (auto directory = directories.rbegin();
       directory != directories.rend();
       ++directory) {
    require(rmdir((root + *directory).c_str()) == 0,
            "remove runtime fixture directory");
  }
  require(rmdir(root.c_str()) == 0, "remove private fixture root");
  std::cout << "Runtime framing C++ tests passed: 2\n";
  return 0;
}
