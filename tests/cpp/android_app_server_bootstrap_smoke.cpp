#include "app_server_process.h"

#include <climits>
#include <iostream>
#include <memory>
#include <string>

namespace {

constexpr std::size_t kMaximumLineBytes = 1024U * 1024U;

bool write_request(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    const std::string& request,
    std::string* error) {
  if (!process->WriteLine(request, 256U * 1024U, error)) {
    std::cerr << "Bootstrap request failed: " << *error << '\n';
    return false;
  }
  return true;
}

bool read_response(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    const std::string& id_marker,
    const std::string& required_marker,
    std::string* error) {
  for (int attempt = 0; attempt < 16; ++attempt) {
    std::string line;
    const agentcodi::LineReadStatus status = process->ReadLine(
        kMaximumLineBytes,
        &line,
        error);
    if (status != agentcodi::LineReadStatus::kLine) {
      std::cerr << "Bootstrap response failed: " << *error << '\n';
      return false;
    }
    if (line.find(id_marker) != std::string::npos) {
      if (line.find(required_marker) == std::string::npos) {
        std::cerr << "Bootstrap response omitted its required contract marker\n";
        return false;
      }
      return true;
    }
  }
  std::cerr << "Bootstrap response was displaced by too many notifications\n";
  return false;
}

bool safe_json_path(const std::string& value) {
  return value.find('"') == std::string::npos
      && value.find('\\') == std::string::npos
      && value.find('\n') == std::string::npos
      && value.find('\r') == std::string::npos;
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc != 8) {
    std::cerr << "Expected executable, host, workspace, Codex home, home, temp and library paths\n";
    return 2;
  }
  const std::string workspace = argv[3];
  if (!safe_json_path(workspace)) {
    std::cerr << "Workspace path is not safe for the bootstrap fixture\n";
    return 2;
  }

  agentcodi::ProcessConfig config;
  config.executable = argv[1];
  config.code_mode_host_executable = argv[2];
  config.working_directory = workspace;
  config.codex_home = argv[4];
  config.home_directory = argv[5];
  config.temporary_directory = argv[6];
  config.library_directory = argv[7];

  std::string error;
  std::shared_ptr<agentcodi::AppServerProcess> process =
      agentcodi::AppServerProcess::Start(config, &error);
  if (process == nullptr) {
    std::cerr << "Bootstrap supervisor start failed: " << error << '\n';
    return 1;
  }

  const std::string initialize =
      "{\"method\":\"initialize\",\"id\":1,\"params\":{"
      "\"clientInfo\":{\"name\":\"agentcodi_android\","
      "\"title\":\"AGENTCODI\",\"version\":\"0.4.5\"},"
      "\"capabilities\":{\"experimentalApi\":true,"
      "\"optOutNotificationMethods\":[\"rawResponseItem/completed\","
      "\"rawResponse/completed\"]}}}";
  if (!write_request(process, initialize, &error)
      || !read_response(process, "\"id\":1", "\"codexHome\":", &error)
      || !write_request(process, "{\"method\":\"initialized\",\"params\":{}}", &error)) {
    process->Stop(2'000);
    return 1;
  }

  const std::string permission_request =
      "{\"method\":\"permissionProfile/list\",\"id\":2,\"params\":{"
      "\"cwd\":\"" + workspace + "\",\"limit\":50}}";
  if (!write_request(process, permission_request, &error)
      || !read_response(process, "\"id\":2", "\"id\":\"agentcodi-workspace\"", &error)
      || !write_request(
          process,
          "{\"method\":\"model/list\",\"id\":3,\"params\":{"
          "\"limit\":50,\"includeHidden\":false}}",
          &error)
      || !read_response(process, "\"id\":3", "\"data\":[", &error)
      || !write_request(
          process,
          "{\"method\":\"account/read\",\"id\":4,\"params\":{"
          "\"refreshToken\":false}}",
          &error)
      || !read_response(process, "\"id\":4", "\"requiresOpenaiAuth\":", &error)
      || !write_request(
          process,
          "{\"method\":\"thread/list\",\"id\":5,\"params\":{"
          "\"limit\":50,\"sortKey\":\"updated_at\","
          "\"sourceKinds\":[\"cli\",\"vscode\",\"exec\",\"appServer\"]}}",
          &error)
      || !read_response(process, "\"id\":5", "\"data\":[", &error)) {
    process->Stop(2'000);
    return 1;
  }

  const int exit_code = process->Stop(2'000);
  if (exit_code == INT_MIN) {
    std::cerr << "Bootstrap supervisor did not stop its child\n";
    return 1;
  }
  std::cout << "Android app-server supervisor bootstrap passed.\n";
  return 0;
}
