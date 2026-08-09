#include "agentcodi_engine.h"
#include "app_server_process.h"

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <iostream>
#include <string>

#include <sys/stat.h>
#include <unistd.h>

namespace {

int failures = 0;
int assertions = 0;

void expect(bool condition, const char* message) {
  ++assertions;
  if (!condition) {
    ++failures;
    std::cerr << "FAILED: " << message << '\n';
  }
}

}  // namespace

int main() {
  const std::string version = agentcodi::engine_version();
  expect(version == "agentcodi-native/0.2.2", "engine version");
  expect(agentcodi::run_self_test() == 0, "native self-test");

  const std::string diagnostics = agentcodi::runtime_diagnostics();
  expect(diagnostics.find("abi=arm64-v8a") != std::string::npos, "ABI diagnostic");
  expect(diagnostics.find("language=cpp") != std::string::npos, "language diagnostic");
  expect(diagnostics.find("jni=ready") != std::string::npos, "JNI diagnostic");
  expect(
      diagnostics.find("appServerSupervisor=ready") != std::string::npos,
      "app-server supervisor diagnostic");
  expect(
      diagnostics.find("responsesTransport=https") != std::string::npos,
      "HTTPS Responses transport diagnostic");

  const std::vector<std::string> codex_arguments = agentcodi::CodexAppServerArguments();
  const auto contains_argument = [&codex_arguments](const std::string& value) {
    return std::find(codex_arguments.begin(), codex_arguments.end(), value)
        != codex_arguments.end();
  };
  std::string joined_arguments;
  for (const std::string& value : codex_arguments) {
    joined_arguments.append(value).push_back('\n');
  }
  expect(!codex_arguments.empty() && codex_arguments.front() == "app-server",
         "Codex app-server command");
  expect(contains_argument("--stdio"), "Codex app-server stdio transport");
  expect(contains_argument("--strict-config"), "Codex strict config validation");
  expect(joined_arguments.find("cli_auth_credentials_store=\"file\"")
             != std::string::npos,
         "Codex file credential store");
  expect(joined_arguments.find("default_permissions=\"agentcodi-workspace\"")
             != std::string::npos,
         "Codex private permission profile default");
  expect(joined_arguments.find("model_provider=\"agentcodi-openai-http\"")
             != std::string::npos,
         "Codex HTTPS model provider selected");
  expect(joined_arguments.find(
             "model_providers.agentcodi-openai-http.requires_openai_auth=true")
             != std::string::npos,
         "Codex HTTPS provider preserves OpenAI authentication");
  expect(joined_arguments.find(
             "model_providers.agentcodi-openai-http.supports_websockets=false")
             != std::string::npos,
         "Codex Responses WebSocket transport disabled");
  expect(joined_arguments.find(
             "model_providers.agentcodi-openai-http.supports_standalone_web_search=true")
             != std::string::npos,
         "Codex HTTPS provider preserves standalone web search");
  expect(joined_arguments.find(":workspace_roots") != std::string::npos,
         "Codex workspace-root filesystem permission");
  expect(joined_arguments.find("sandbox_mode") == std::string::npos,
         "legacy sandbox config excluded");

  char temporary_template[] = "/tmp/agentcodi-process-test-XXXXXX";
  char* temporary_root = mkdtemp(temporary_template);
  expect(temporary_root != nullptr, "temporary process-test root");
  if (temporary_root != nullptr) {
    const std::string root = temporary_root;
    const std::string workspace = root + "/workspace";
    const std::string codex_home = root + "/codex-home";
    const std::string home = root + "/home";
    const std::string temporary = root + "/temporary";
    expect(mkdir(workspace.c_str(), 0700) == 0, "process-test workspace");
    expect(mkdir(codex_home.c_str(), 0700) == 0, "process-test Codex home");
    expect(mkdir(home.c_str(), 0700) == 0, "process-test home");
    expect(mkdir(temporary.c_str(), 0700) == 0, "process-test temporary directory");

    agentcodi::ProcessConfig config;
    config.executable = "/system/bin/sh";
    config.working_directory = workspace;
    config.codex_home = codex_home;
    config.home_directory = home;
    config.temporary_directory = temporary;
    config.library_directory = "/system/lib64";
    config.arguments = {
        "-c",
        "IFS= read -r line; printf '%s\\n' \"$line\"",
    };
    std::string error;
    std::shared_ptr<agentcodi::AppServerProcess> process =
        agentcodi::AppServerProcess::Start(config, &error);
    expect(process != nullptr, "spawn supervised process");
    if (process != nullptr) {
      const std::string probe = "{\"probe\":\"ok\"}";
      expect(process->WriteLine(probe, 1024U, &error), "write framed process line");
      std::string response;
      expect(
          process->ReadLine(1024U, &response, &error)
              == agentcodi::LineReadStatus::kLine,
          "read framed process line");
      expect(response == probe, "process framing round trip");
      expect(process->Stop(500) != INT_MIN, "supervised process stop");
    }

    config.codex_home = workspace;
    error.clear();
    process = agentcodi::AppServerProcess::Start(config, &error);
    expect(process == nullptr, "reject Codex home inside workspace");
    expect(error.find("separate") != std::string::npos, "auth boundary error");

    rmdir(temporary.c_str());
    rmdir(home.c_str());
    rmdir(codex_home.c_str());
    rmdir(workspace.c_str());
    rmdir(root.c_str());
  }

  if (failures != 0) {
    std::cerr << "C++ tests failed: " << failures << " of " << assertions << '\n';
    return 1;
  }
  std::cout << "C++ tests passed: " << assertions << '\n';
  return 0;
}
