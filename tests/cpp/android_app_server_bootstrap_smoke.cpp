#include "app_server_process.h"

#include <climits>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

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
    const std::string& forbidden_marker,
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
      if (!forbidden_marker.empty()
          && line.find(forbidden_marker) != std::string::npos) {
        std::cerr << "Bootstrap response retained a forbidden contract marker\n";
        return false;
      }
      return true;
    }
  }
  std::cerr << "Bootstrap response was displaced by too many notifications\n";
  return false;
}

bool read_response(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    const std::string& id_marker,
    const std::string& required_marker,
    std::string* error) {
  return read_response(process, id_marker, required_marker, "", error);
}

bool safe_json_path(const std::string& value) {
  return value.find('"') == std::string::npos
      && value.find('\\') == std::string::npos
      && value.find('\n') == std::string::npos
      && value.find('\r') == std::string::npos;
}

bool extract_json_string(
    const std::string& line,
    const std::string& field,
    std::string* value) {
  const std::string marker = "\"" + field + "\":\"";
  const std::size_t begin = line.find(marker);
  if (begin == std::string::npos) {
    return false;
  }
  const std::size_t value_begin = begin + marker.size();
  const std::size_t end = line.find('"', value_begin);
  if (end == std::string::npos) {
    return false;
  }
  *value = line.substr(value_begin, end - value_begin);
  return true;
}

int base64_value(char character) {
  if (character >= 'A' && character <= 'Z') {
    return character - 'A';
  }
  if (character >= 'a' && character <= 'z') {
    return character - 'a' + 26;
  }
  if (character >= '0' && character <= '9') {
    return character - '0' + 52;
  }
  if (character == '+') {
    return 62;
  }
  if (character == '/') {
    return 63;
  }
  return -1;
}

bool append_base64(const std::string& encoded, std::string* decoded) {
  if (encoded.size() % 4U != 0U || encoded.size() > 88U * 1024U) {
    return false;
  }
  for (std::size_t index = 0U; index < encoded.size(); index += 4U) {
    const int first = base64_value(encoded[index]);
    const int second = base64_value(encoded[index + 1U]);
    const bool third_padding = encoded[index + 2U] == '=';
    const bool fourth_padding = encoded[index + 3U] == '=';
    const int third = third_padding ? 0 : base64_value(encoded[index + 2U]);
    const int fourth = fourth_padding ? 0 : base64_value(encoded[index + 3U]);
    if (first < 0 || second < 0 || third < 0 || fourth < 0
        || (third_padding && !fourth_padding)
        || ((third_padding || fourth_padding) && index + 4U != encoded.size())) {
      return false;
    }
    decoded->push_back(static_cast<char>((first << 2) | (second >> 4)));
    if (!third_padding) {
      decoded->push_back(static_cast<char>((second << 4) | (third >> 2)));
    }
    if (!fourth_padding) {
      decoded->push_back(static_cast<char>((third << 6) | fourth));
    }
    if (decoded->size() > 128U * 1024U) {
      return false;
    }
  }
  return true;
}

bool read_interactive_terminal_completion(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    std::string* error) {
  bool write_acknowledged = false;
  bool command_completed = false;
  std::string output;
  for (int attempt = 0; attempt < 64; ++attempt) {
    std::string line;
    const agentcodi::LineReadStatus status = process->ReadLine(
        kMaximumLineBytes,
        &line,
        error);
    if (status != agentcodi::LineReadStatus::kLine) {
      std::cerr << "Terminal protocol response failed: " << *error << '\n';
      return false;
    }
    if (line.find("\"method\":\"command/exec/outputDelta\"")
            != std::string::npos
        && line.find("\"processId\":\"agentcodi-build-terminal\"")
            != std::string::npos) {
      std::string delta;
      if (!extract_json_string(line, "deltaBase64", &delta)
          || !append_base64(delta, &output)) {
        std::cerr << "Terminal output notification was not bounded Base64\n";
        return false;
      }
    } else if (line.find("\"id\":8") != std::string::npos) {
      if (line.find("\"result\":{}") == std::string::npos) {
        std::cerr << "Terminal input request failed\n";
        return false;
      }
      write_acknowledged = true;
    } else if (line.find("\"id\":6") != std::string::npos) {
      if (line.find("\"exitCode\":0") == std::string::npos
          || line.find("\"stdout\":\"\"") == std::string::npos
          || line.find("\"stderr\":\"\"") == std::string::npos) {
        std::cerr << "Terminal completion response was malformed\n";
        return false;
      }
      command_completed = true;
    }
    if (write_acknowledged && command_completed
        && output.find("terminal-protocol-smoke") != std::string::npos
        && output.find("Enabled packaged Node.js 24.18.0") != std::string::npos
        && output.find("v24.18.0") != std::string::npos) {
      return true;
    }
  }
  std::cerr << "Interactive terminal protocol did not complete all correlated events\n";
  return false;
}

bool read_model_shell_completion(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    const std::string& expected_node_alias,
    std::string* error) {
  bool command_completed = false;
  std::string output;
  for (int attempt = 0; attempt < 64; ++attempt) {
    std::string line;
    const agentcodi::LineReadStatus status = process->ReadLine(
        kMaximumLineBytes,
        &line,
        error);
    if (status != agentcodi::LineReadStatus::kLine) {
      std::cerr << "Model shell response failed: " << *error << '\n';
      return false;
    }
    if (line.find("\"method\":\"command/exec/outputDelta\"")
            != std::string::npos
        && line.find("\"processId\":\"agentcodi-build-model-shell\"")
            != std::string::npos) {
      std::string delta;
      if (!extract_json_string(line, "deltaBase64", &delta)
          || !append_base64(delta, &output)) {
        std::cerr << "Model shell output was not bounded Base64\n";
        return false;
      }
    } else if (line.find("\"id\":9") != std::string::npos) {
      if (line.find("\"exitCode\":0") == std::string::npos
          || line.find("\"stderr\":\"\"") == std::string::npos) {
        std::cerr << "Model shell completion response was malformed\n";
        return false;
      }
      if (line.find("\"stdout\":\"\"") == std::string::npos) {
        if (line.find(expected_node_alias) == std::string::npos
            || line.find("v24.18.0") == std::string::npos
            || line.find("node 24.18.0") == std::string::npos
            || line.find("enabled") == std::string::npos) {
          std::cerr << "Inline model shell output omitted the packaged Node contract\n";
          return false;
        }
        return true;
      }
      command_completed = true;
    }
    if (command_completed
        && output.find(expected_node_alias) != std::string::npos
        && output.find("v24.18.0") != std::string::npos
        && output.find("node 24.18.0") != std::string::npos
        && output.find("enabled") != std::string::npos) {
      return true;
    }
  }
  std::cerr << "Model shell could not resolve the activated packaged Node command\n";
  return false;
}

bool read_terminated_terminal_completion(
    const std::shared_ptr<agentcodi::AppServerProcess>& process,
    std::string* error) {
  bool terminate_acknowledged = false;
  bool command_completed = false;
  for (int attempt = 0; attempt < 64; ++attempt) {
    std::string line;
    const agentcodi::LineReadStatus status = process->ReadLine(
        kMaximumLineBytes,
        &line,
        error);
    if (status != agentcodi::LineReadStatus::kLine) {
      std::cerr << "Terminal termination response failed: " << *error << '\n';
      return false;
    }
    if (line.find("\"id\":12") != std::string::npos) {
      if (line.find("\"result\":{}") == std::string::npos) {
        std::cerr << "Terminal terminate request failed\n";
        return false;
      }
      terminate_acknowledged = true;
    } else if (line.find("\"id\":10") != std::string::npos) {
      if (line.find("\"result\":{") == std::string::npos
          || line.find("\"exitCode\":") == std::string::npos) {
        std::cerr << "Terminated terminal completion was malformed\n";
        return false;
      }
      command_completed = true;
    }
    if (terminate_acknowledged && command_completed) {
      return true;
    }
  }
  std::cerr << "Terminal termination did not produce both correlated responses\n";
  return false;
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc != 15) {
    std::cerr << "Expected app-server, host, shell, Node, Python, workspace, toolchain, tool-bin, tool-runtime, Codex home, home, state, temp and library paths\n";
    return 2;
  }
  const std::string workspace = argv[6];
  const std::string shell = argv[3];
  if (!safe_json_path(workspace) || !safe_json_path(shell)) {
    std::cerr << "Workspace or shell path is not safe for the bootstrap fixture\n";
    return 2;
  }

  agentcodi::ProcessConfig config;
  config.executable = argv[1];
  config.code_mode_host_executable = argv[2];
  config.shell_executable = argv[3];
  config.node_executable = argv[4];
  config.python_executable = argv[5];
  config.working_directory = workspace;
  config.toolchain_directory = argv[7];
  config.tool_binary_directory = argv[8];
  config.tool_runtime_directory = argv[9];
  config.codex_home = argv[10];
  config.home_directory = argv[11];
  config.state_directory = argv[12];
  config.temporary_directory = argv[13];
  config.library_directory = argv[14];

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
      "\"title\":\"AGENTCODI\",\"version\":\"0.5.5\"},"
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

  const std::string terminal_request =
      "{\"method\":\"command/exec\",\"id\":6,\"params\":{"
      "\"command\":[\"" + shell + "\",\"--interactive\"],"
      "\"cwd\":\"" + workspace + "\","
      "\"processId\":\"agentcodi-build-terminal\","
      "\"permissionProfile\":\"agentcodi-workspace\","
      "\"tty\":true,\"size\":{\"rows\":24,\"cols\":80},"
      "\"outputBytesCap\":65536,\"timeoutMs\":10000}}";
  if (!write_request(process, terminal_request, &error)
      || !write_request(
          process,
          "{\"method\":\"command/exec/resize\",\"id\":7,\"params\":{"
          "\"processId\":\"agentcodi-build-terminal\","
          "\"size\":{\"rows\":32,\"cols\":96}}}",
          &error)
      || !read_response(process, "\"id\":7", "\"result\":{}", &error)
      || !write_request(
          process,
          "{\"method\":\"command/exec/write\",\"id\":8,\"params\":{"
          "\"processId\":\"agentcodi-build-terminal\","
          "\"deltaBase64\":"
          "\"cHJpbnRmIHRlcm1pbmFsLXByb3RvY29sLXNtb2tlCmFnZW50Y29kaS10b29sY2hhaW4gaW5zdGFsbCBub2RlCm5vZGUgLS12ZXJzaW9uCmV4aXQK\"}}",
          &error)
      || !read_interactive_terminal_completion(process, &error)) {
    process->Stop(2'000);
    return 1;
  }

  const std::string model_shell_request =
      "{\"method\":\"command/exec\",\"id\":9,\"params\":{"
      "\"command\":[\"/system/bin/sh\",\"-c\","
      "\"command -v node && command -v agentcodi-toolchain && "
      "node --version && agentcodi-toolchain status\"],"
      "\"cwd\":\"" + workspace + "\","
      "\"processId\":\"agentcodi-build-model-shell\","
      "\"permissionProfile\":\"agentcodi-workspace\","
      "\"tty\":false,\"outputBytesCap\":65536,\"timeoutMs\":10000}}";
  if (!write_request(process, model_shell_request, &error)
      || !read_model_shell_completion(
          process,
          config.tool_binary_directory + "/node",
          &error)) {
    process->Stop(2'000);
    return 1;
  }

  const std::string terminated_terminal_request =
      "{\"method\":\"command/exec\",\"id\":10,\"params\":{"
      "\"command\":[\"" + shell + "\",\"--interactive\"],"
      "\"cwd\":\"" + workspace + "\","
      "\"processId\":\"agentcodi-build-terminal-stop\","
      "\"permissionProfile\":\"agentcodi-workspace\","
      "\"tty\":true,\"size\":{\"rows\":24,\"cols\":80},"
      "\"outputBytesCap\":65536,\"timeoutMs\":10000}}";
  if (!write_request(process, terminated_terminal_request, &error)
      || !write_request(
          process,
          "{\"method\":\"command/exec/resize\",\"id\":11,\"params\":{"
          "\"processId\":\"agentcodi-build-terminal-stop\","
          "\"size\":{\"rows\":24,\"cols\":80}}}",
          &error)
      || !read_response(process, "\"id\":11", "\"result\":{}", &error)
      || !write_request(
          process,
          "{\"method\":\"command/exec/terminate\",\"id\":12,\"params\":{"
          "\"processId\":\"agentcodi-build-terminal-stop\"}}",
          &error)
      || !read_terminated_terminal_completion(process, &error)) {
    process->Stop(2'000);
    return 1;
  }

  const std::string mcp_probe_name = "agentcodi_build_probe";
  const std::string config_read =
      "{\"method\":\"config/read\",\"id\":13,\"params\":{"
      "\"cwd\":\"" + workspace + "\",\"includeLayers\":false}}";
  const std::string config_add =
      "{\"method\":\"config/batchWrite\",\"id\":14,\"params\":{"
      "\"edits\":[{\"keyPath\":\"mcp_servers." + mcp_probe_name + "\","
      "\"value\":{\"url\":\"https://example.invalid/mcp\","
      "\"enabled\":false,\"required\":false,"
      "\"startup_timeout_sec\":10,\"tool_timeout_sec\":60,"
      "\"default_tools_approval_mode\":\"prompt\","
      "\"tools\":{\"unsafe_probe\":{\"approval_mode\":\"approve\"}}},"
      "\"mergeStrategy\":\"replace\"}],\"reloadUserConfig\":false}}";
  const std::string config_harden =
      "{\"method\":\"config/batchWrite\",\"id\":17,\"params\":{"
      "\"edits\":[{\"keyPath\":\"mcp_servers." + mcp_probe_name + ".tools\","
      "\"value\":null,\"mergeStrategy\":\"replace\"},{"
      "\"keyPath\":\"mcp_servers." + mcp_probe_name
      + ".default_tools_approval_mode\","
      "\"value\":\"prompt\",\"mergeStrategy\":\"replace\"}],"
      "\"reloadUserConfig\":false}}";
  const std::string config_delete =
      "{\"method\":\"config/batchWrite\",\"id\":20,\"params\":{"
      "\"edits\":[{\"keyPath\":\"mcp_servers." + mcp_probe_name + "\","
      "\"value\":null,\"mergeStrategy\":\"replace\"}],"
      "\"reloadUserConfig\":false}}";
  if (!write_request(process, config_read, &error)
      || !read_response(
          process,
          "\"id\":13",
          "\"mcp_servers\":{",
          mcp_probe_name,
          &error)
      || !write_request(process, config_add, &error)
      || !read_response(process, "\"id\":14", "\"status\":\"ok\"", &error)
      || !write_request(
          process,
          "{\"method\":\"config/mcpServer/reload\",\"id\":15}",
          &error)
      || !read_response(process, "\"id\":15", "\"result\":{}", &error)
      || !write_request(
          process,
          "{\"method\":\"config/read\",\"id\":16,\"params\":{"
          "\"cwd\":\"" + workspace + "\",\"includeLayers\":false}}",
          &error)
      || !read_response(
          process,
          "\"id\":16",
          "\"approval_mode\":\"approve\"",
          &error)
      || !write_request(process, config_harden, &error)
      || !read_response(process, "\"id\":17", "\"status\":\"ok\"", &error)
      || !write_request(
          process,
          "{\"method\":\"config/mcpServer/reload\",\"id\":18}",
          &error)
      || !read_response(process, "\"id\":18", "\"result\":{}", &error)
      || !write_request(
          process,
          "{\"method\":\"config/read\",\"id\":19,\"params\":{"
          "\"cwd\":\"" + workspace + "\",\"includeLayers\":false}}",
          &error)
      || !read_response(
          process,
          "\"id\":19",
          mcp_probe_name,
          "\"approval_mode\":\"approve\"",
          &error)
      || !write_request(process, config_delete, &error)
      || !read_response(process, "\"id\":20", "\"status\":\"ok\"", &error)
      || !write_request(
          process,
          "{\"method\":\"config/mcpServer/reload\",\"id\":21}",
          &error)
      || !read_response(process, "\"id\":21", "\"result\":{}", &error)
      || !write_request(
          process,
          "{\"method\":\"config/read\",\"id\":22,\"params\":{"
          "\"cwd\":\"" + workspace + "\",\"includeLayers\":false}}",
          &error)
      || !read_response(
          process,
          "\"id\":22",
          "\"mcp_servers\":{",
          mcp_probe_name,
          &error)) {
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
