#include "agentcodi_engine.h"
#include "app_server_process.h"
#include "sha256.h"

#include <algorithm>
#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <zlib.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/wait.h>
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

std::string saved_path_from_event(const std::string& event) {
  const std::string field = "\"savedPath\":\"";
  const std::size_t begin = event.find(field);
  if (begin == std::string::npos) {
    return "";
  }
  const std::size_t value_begin = begin + field.size();
  const std::size_t end = event.find('"', value_begin);
  if (end == std::string::npos) {
    return "";
  }
  return event.substr(value_begin, end - value_begin);
}

bool run_toolchain_shell(
    const std::string& executable,
    const std::vector<std::string>& arguments,
    const std::string& workspace,
    const std::string& toolchain,
    std::string* output,
    int* exit_code) {
  int descriptors[2] {-1, -1};
  if (pipe(descriptors) != 0) {
    return false;
  }
  const pid_t child = fork();
  if (child < 0) {
    close(descriptors[0]);
    close(descriptors[1]);
    return false;
  }
  if (child == 0) {
    close(descriptors[0]);
    if (dup2(descriptors[1], STDOUT_FILENO) < 0
        || dup2(descriptors[1], STDERR_FILENO) < 0
        || chdir(workspace.c_str()) != 0
        || setenv("AGENTCODI_WORKSPACE", workspace.c_str(), 1) != 0
        || setenv("AGENTCODI_TOOLCHAIN", toolchain.c_str(), 1) != 0
        || setenv(
            "AGENTCODI_TOOL_RUNTIME",
            (workspace.substr(0U, workspace.rfind('/')) + "/tool-runtime").c_str(),
            1) != 0) {
      _exit(126);
    }
    close(descriptors[1]);
    std::vector<char*> mutable_arguments;
    mutable_arguments.reserve(arguments.size() + 2U);
    mutable_arguments.push_back(const_cast<char*>(executable.c_str()));
    for (const std::string& argument : arguments) {
      mutable_arguments.push_back(const_cast<char*>(argument.c_str()));
    }
    mutable_arguments.push_back(nullptr);
    execv(executable.c_str(), mutable_arguments.data());
    _exit(127);
  }
  close(descriptors[1]);
  output->clear();
  char buffer[4096];
  while (true) {
    const ssize_t count = read(descriptors[0], buffer, sizeof(buffer));
    if (count > 0) {
      output->append(buffer, static_cast<std::size_t>(count));
      if (output->size() > 128U * 1024U) {
        close(descriptors[0]);
        kill(child, SIGKILL);
        waitpid(child, nullptr, 0);
        return false;
      }
    } else if (count == 0) {
      break;
    } else if (errno != EINTR) {
      close(descriptors[0]);
      kill(child, SIGKILL);
      waitpid(child, nullptr, 0);
      return false;
    }
  }
  close(descriptors[0]);
  int status = 0;
  if (waitpid(child, &status, 0) != child) {
    return false;
  }
  *exit_code = WIFEXITED(status)
      ? WEXITSTATUS(status)
      : (WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1);
  return true;
}

bool write_fixture_file(const std::string& path, const std::string& contents) {
  const int descriptor = open(
      path.c_str(),
      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
      0600);
  if (descriptor < 0) {
    return false;
  }
  const ssize_t written = write(descriptor, contents.data(), contents.size());
  const bool result = written == static_cast<ssize_t>(contents.size())
      && fsync(descriptor) == 0;
  close(descriptor);
  return result;
}

bool read_fixture_file(const std::string& path, std::string* contents) {
  const int descriptor = open(
      path.c_str(),
      O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  if (descriptor < 0) {
    return false;
  }
  struct stat metadata {};
  if (fstat(descriptor, &metadata) != 0 || !S_ISREG(metadata.st_mode)
      || metadata.st_size < 0 || metadata.st_size > 1024) {
    close(descriptor);
    return false;
  }
  contents->assign(static_cast<std::size_t>(metadata.st_size), '\0');
  std::size_t received = 0U;
  while (received < contents->size()) {
    const ssize_t count = read(
        descriptor,
        &(*contents)[received],
        contents->size() - received);
    if (count > 0) {
      received += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      close(descriptor);
      contents->clear();
      return false;
    }
  }
  const bool closed = close(descriptor) == 0;
  return closed;
}

std::string proof_path_for_id(
    const std::string& state_directory,
    const std::string& image_id) {
  return state_directory + "/image-materialization-proofs/"
      + agentcodi::Sha256Hex(
          reinterpret_cast<const unsigned char*>(image_id.data()),
          image_id.size()) + ".proof";
}

std::string encode_fixture_base64(const std::vector<unsigned char>& bytes) {
  static const char alphabet[] =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string encoded;
  encoded.reserve(((bytes.size() + 2U) / 3U) * 4U);
  for (std::size_t offset = 0U; offset < bytes.size(); offset += 3U) {
    const std::size_t remaining = bytes.size() - offset;
    const std::uint32_t first = bytes[offset];
    const std::uint32_t second = remaining > 1U ? bytes[offset + 1U] : 0U;
    const std::uint32_t third = remaining > 2U ? bytes[offset + 2U] : 0U;
    const std::uint32_t combined = (first << 16U) | (second << 8U) | third;
    encoded.push_back(alphabet[(combined >> 18U) & 0x3fU]);
    encoded.push_back(alphabet[(combined >> 12U) & 0x3fU]);
    encoded.push_back(remaining > 1U ? alphabet[(combined >> 6U) & 0x3fU] : '=');
    encoded.push_back(remaining > 2U ? alphabet[combined & 0x3fU] : '=');
  }
  return encoded;
}

std::uint32_t read_fixture_u32(
    const std::vector<unsigned char>& bytes,
    std::size_t offset) {
  return (static_cast<std::uint32_t>(bytes[offset]) << 24U)
      | (static_cast<std::uint32_t>(bytes[offset + 1U]) << 16U)
      | (static_cast<std::uint32_t>(bytes[offset + 2U]) << 8U)
      | static_cast<std::uint32_t>(bytes[offset + 3U]);
}

void write_fixture_u32(
    std::vector<unsigned char>* bytes,
    std::size_t offset,
    std::uint32_t value) {
  (*bytes)[offset] = static_cast<unsigned char>(value >> 24U);
  (*bytes)[offset + 1U] = static_cast<unsigned char>(value >> 16U);
  (*bytes)[offset + 2U] = static_cast<unsigned char>(value >> 8U);
  (*bytes)[offset + 3U] = static_cast<unsigned char>(value);
}

std::size_t find_fixture_chunk(
    const std::vector<unsigned char>& png,
    const char* type) {
  std::size_t offset = 8U;
  while (offset <= png.size() && png.size() - offset >= 12U) {
    const std::uint32_t length = read_fixture_u32(png, offset);
    if (static_cast<std::uint64_t>(length) + 12U > png.size() - offset) {
      return std::string::npos;
    }
    if (std::memcmp(png.data() + offset + 4U, type, 4U) == 0) {
      return offset;
    }
    offset += static_cast<std::size_t>(length) + 12U;
  }
  return std::string::npos;
}

void rewrite_fixture_chunk_crc(
    std::vector<unsigned char>* png,
    std::size_t chunk_offset) {
  const std::uint32_t length = read_fixture_u32(*png, chunk_offset);
  uLong crc = crc32(0L, Z_NULL, 0U);
  crc = crc32(
      crc,
      reinterpret_cast<const Bytef*>(png->data() + chunk_offset + 4U),
      length + 4U);
  write_fixture_u32(
      png,
      chunk_offset + 8U + length,
      static_cast<std::uint32_t>(crc));
}

void append_fixture_chunk(
    std::vector<unsigned char>* png,
    const char* type,
    const std::vector<unsigned char>& data) {
  const std::size_t offset = png->size();
  png->resize(offset + data.size() + 12U, 0U);
  write_fixture_u32(
      png,
      offset,
      static_cast<std::uint32_t>(data.size()));
  std::memcpy(png->data() + offset + 4U, type, 4U);
  std::copy(data.begin(), data.end(), png->begin() + offset + 8U);
  rewrite_fixture_chunk_crc(png, offset);
}

std::vector<unsigned char> make_valid_fixture_png(
    unsigned char filter = 0U,
    unsigned char interlace = 0U) {
  const std::vector<unsigned char> pixels = {
      filter, 0x11U, 0x22U, 0x33U, 0xffU,
  };
  uLongf compressed_size = compressBound(pixels.size());
  std::vector<unsigned char> compressed(compressed_size);
  if (compress2(
          compressed.data(),
          &compressed_size,
          pixels.data(),
          pixels.size(),
          Z_BEST_COMPRESSION) != Z_OK) {
    return {};
  }
  compressed.resize(compressed_size);
  std::vector<unsigned char> png = {
      0x89U, 'P', 'N', 'G', 0x0dU, 0x0aU, 0x1aU, 0x0aU,
  };
  std::vector<unsigned char> ihdr(13U, 0U);
  write_fixture_u32(&ihdr, 0U, 1U);
  write_fixture_u32(&ihdr, 4U, 1U);
  ihdr[8U] = 8U;
  ihdr[9U] = 6U;
  ihdr[12U] = interlace;
  append_fixture_chunk(&png, "IHDR", ihdr);
  append_fixture_chunk(&png, "IDAT", compressed);
  append_fixture_chunk(&png, "IEND", {});
  return png;
}

std::vector<unsigned char> png_with_ancillary_chunk(
    const std::vector<unsigned char>& original,
    const char* type,
    const std::vector<unsigned char>& data) {
  const std::size_t iend = find_fixture_chunk(original, "IEND");
  if (iend == std::string::npos) {
    return {};
  }
  std::vector<unsigned char> chunk;
  append_fixture_chunk(&chunk, type, data);
  std::vector<unsigned char> result;
  result.reserve(original.size() + chunk.size());
  result.insert(result.end(), original.begin(), original.begin() + iend);
  result.insert(result.end(), chunk.begin(), chunk.end());
  result.insert(result.end(), original.begin() + iend, original.end());
  return result;
}

std::vector<unsigned char> png_with_private_chunk(
    const std::vector<unsigned char>& original) {
  const std::string data = "fixture=value";
  return png_with_ancillary_chunk(
      original,
      "aaAa",
      std::vector<unsigned char>(data.begin(), data.end()));
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc == 2 && std::string(argv[1]) == "--emit-oversized-image") {
    const std::vector<unsigned char> oversized_png = png_with_ancillary_chunk(
        make_valid_fixture_png(),
        "aaAa",
        std::vector<unsigned char>(1024U * 1024U, 'A'));
    const std::string payload = encode_fixture_base64(oversized_png);
    std::cout
        << "{\"method\":\"item/completed\",\"params\":{\"item\":{"
        << "\"type\":\"imageGeneration\",\"id\":\"child_image\","
        << "\"status\":\"completed\",\"result\":\""
        << payload
        << "\",\"savedPath\":\"/private/codex-home/generated_images/"
        << "child-thread/child_image.png\"}}}\n";
    std::cout
        << "{\"method\":\"turn/completed\",\"params\":{"
        << "\"threadId\":\"child_thread\",\"turn\":{"
        << "\"id\":\"child_turn\",\"status\":\"completed\"}}}\n";
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--emit-resumed-image") {
    std::cout
        << "{\"method\":\"item/completed\",\"params\":{\"item\":{"
        << "\"type\":\"imageGeneration\",\"id\":\"child_image\","
        << "\"status\":\"completed\",\"result\":\"\","
        << "\"savedPath\":\"/private/codex-home/generated_images/"
        << "child-thread/child_image.png\"}}}\n";
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--exit-with-code") {
    return 23;
  }
  if (argc == 2 && std::string(argv[1]) == "--emit-mcp-catalog") {
    std::cout
        << "{\"id\":41,\"result\":{\"data\":[{"
        << "\"name\":\"fixture-mcp\",\"authStatus\":\"unsupported\","
        << "\"tools\":{\"search\":{\"name\":\"search\","
        << "\"inputSchema\":{\"type\":\"object\"}}},"
        << "\"resources\":[],\"resourceTemplates\":[]}]}}\n";
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--rate-limits-roundtrip") {
    std::string request;
    if (!std::getline(std::cin, request)
        || request.find("\"method\":\"account/rateLimits/read\"")
            == std::string::npos
        || request.find("\"params\"") != std::string::npos) {
      return 34;
    }
    std::cout
        << "{\"id\":64,\"result\":{\"rateLimits\":{"
        << "\"limitId\":\"codex\","
        << "\"primary\":{\"usedPercent\":25,"
        << "\"windowDurationMins\":300,\"resetsAt\":1800000000},"
        << "\"secondary\":{\"usedPercent\":40,"
        << "\"windowDurationMins\":10080,\"resetsAt\":1800600000}}}}"
        << std::endl;
    std::cout
        << "{\"method\":\"account/rateLimits/updated\",\"params\":{"
        << "\"rateLimits\":{\"limitId\":\"codex\","
        << "\"primary\":{\"usedPercent\":31}}}}"
        << std::endl;
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--turn-steer-roundtrip") {
    std::string request;
    if (!std::getline(std::cin, request)
        || request.find("\"method\":\"turn/steer\"") == std::string::npos
        || request.find("\"threadId\":\"thr_fixture\"") == std::string::npos
        || request.find("\"expectedTurnId\":\"turn_fixture\"")
            == std::string::npos
        || request.find("\"type\":\"text\"") == std::string::npos
        || request.find("\"text\":\"Focus on tests first.\"")
            == std::string::npos
        || request.find("\"model\"") != std::string::npos
        || request.find("\"cwd\"") != std::string::npos
        || request.find("\"sandboxPolicy\"") != std::string::npos
        || request.find("\"approvalPolicy\"") != std::string::npos) {
      return 35;
    }
    std::cout
        << "{\"id\":65,\"result\":{\"turnId\":\"turn_fixture\"}}"
        << std::endl;
    std::cout
        << "{\"method\":\"item/completed\",\"params\":{"
        << "\"threadId\":\"thr_fixture\",\"turnId\":\"turn_fixture\","
        << "\"item\":{\"id\":\"steer_user_fixture\","
        << "\"type\":\"userMessage\",\"content\":[{"
        << "\"type\":\"text\",\"text\":\"Focus on tests first.\"}]}}}"
        << std::endl;
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--turn-import-roundtrip") {
    std::string request;
    if (!std::getline(std::cin, request)
        || request.find("\"method\":\"turn/start\"") == std::string::npos
        || request.find("\"threadId\":\"thr_import\"") == std::string::npos
        || request.find("\"type\":\"text\",\"text\":\"Analyze it.\"")
            == std::string::npos
        || request.find("\"type\":\"mention\"") == std::string::npos
        || request.find("\"name\":\"outside-data.csv\"") == std::string::npos
        || request.find(
            "\"path\":\"/private/workspace/imports/0123456789abcdef-outside-data.csv\"")
            == std::string::npos
        || request.find("\"additionalContext\":{") == std::string::npos
        || request.find("\"agentcodi-import-1\":{") == std::string::npos
        || request.find("\"kind\":\"application\"") == std::string::npos
        || request.find("Read the file's actual bytes with the workspace tools")
            == std::string::npos
        || request.find("\"cwd\":\"/private/workspace\"") == std::string::npos
        || request.find("\"runtimeWorkspaceRoots\":[\"/private/workspace\"]")
            == std::string::npos
        || request.find("\"approvalPolicy\":\"on-request\"")
            == std::string::npos
        || request.find("\"id\":\"agentcodi-workspace\"")
            == std::string::npos
        || request.find("content://") != std::string::npos
        || request.find("/external/") != std::string::npos
        || request.find("\"sandboxPolicy\"") != std::string::npos) {
      return 36;
    }
    std::cout
        << "{\"id\":66,\"result\":{\"turn\":{"
        << "\"id\":\"turn_import\",\"status\":\"inProgress\","
        << "\"items\":[],\"error\":null}}}" << std::endl;
    std::cout
        << "{\"method\":\"item/completed\",\"params\":{"
        << "\"threadId\":\"thr_import\",\"turnId\":\"turn_import\","
        << "\"item\":{\"id\":\"import_user_fixture\","
        << "\"type\":\"userMessage\",\"content\":[{"
        << "\"type\":\"mention\",\"name\":\"outside-data.csv\","
        << "\"path\":\"/private/workspace/imports/"
        << "0123456789abcdef-outside-data.csv\"}]}}}" << std::endl;
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--mcp-config-roundtrip") {
    std::string request;
    if (!std::getline(std::cin, request)
        || request.find("\"method\":\"config/read\"") == std::string::npos
        || request.find("\"includeLayers\":false") == std::string::npos) {
      return 31;
    }
    std::cout
        << "{\"id\":61,\"result\":{\"config\":{\"mcp_servers\":{}},"
        << "\"origins\":{},\"layers\":null}}" << std::endl;

    if (!std::getline(std::cin, request)
        || request.find("\"method\":\"config/batchWrite\"")
            == std::string::npos
        || request.find("\"keyPath\":\"mcp_servers.fixture\"")
            == std::string::npos
        || request.find("\"reloadUserConfig\":false") == std::string::npos
        || request.find("\"filePath\"") != std::string::npos) {
      return 32;
    }
    std::cout
        << "{\"id\":62,\"result\":{\"status\":\"ok\","
        << "\"version\":\"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
        << "\"filePath\":\"/private/codex-home/config.toml\","
        << "\"overriddenMetadata\":null}}" << std::endl;

    if (!std::getline(std::cin, request)
        || request.find("\"method\":\"config/mcpServer/reload\"")
            == std::string::npos
        || request.find("\"params\"") != std::string::npos) {
      return 33;
    }
    std::cout << "{\"id\":63,\"result\":{}}" << std::endl;
    return 0;
  }

  const std::string version = agentcodi::engine_version();
  expect(version == "agentcodi-native/0.5.8", "engine version");
  expect(agentcodi::run_self_test() == 0, "native self-test");
  const std::string abc = "abc";
  expect(
      agentcodi::Sha256Hex(
          reinterpret_cast<const unsigned char*>(abc.data()),
          abc.size())
          == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      "SHA-256 known vector");
  const std::string multi_block_sha256_fixture =
      "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
  expect(
      agentcodi::Sha256Hex(
          reinterpret_cast<const unsigned char*>(
              multi_block_sha256_fixture.data()),
          multi_block_sha256_fixture.size())
          == "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
      "SHA-256 multi-block known vector");

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
  expect(
      diagnostics.find("codeModeHost=android-sibling") != std::string::npos,
      "Android sibling code-mode host diagnostic");

  agentcodi::ProcessConfig argument_config;
  argument_config.shell_executable = "/private/native/libagentcodi-shell.so";
  argument_config.node_executable = "/private/native/libnode.so";
  argument_config.python_executable = "/private/native/libpython-bin.so";
  argument_config.working_directory = "/private/workspace";
  argument_config.toolchain_directory = "/private/workspace/toolchain";
  argument_config.tool_binary_directory = "/private/tool-bin";
  argument_config.tool_runtime_directory = "/private/tool-runtime";
  argument_config.home_directory = "/private/home";
  argument_config.state_directory = "/private/state";
  argument_config.temporary_directory = "/private/temporary";
  argument_config.library_directory = "/private/native";
  const std::vector<std::string> codex_arguments =
      agentcodi::CodexAppServerArguments(argument_config);
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
  expect(joined_arguments.find("approval_policy=\"on-request\"")
             != std::string::npos,
         "Codex native approval policy");
  expect(joined_arguments.find("approval_policy=\"never\"") == std::string::npos,
         "obsolete no-prompt policy removed");
  expect(joined_arguments.find("shell_environment_policy={inherit=\"none\"")
             != std::string::npos,
         "Codex tool environment starts empty");
  expect(joined_arguments.find("ignore_default_excludes=false")
             != std::string::npos,
         "Codex environment deny patterns remain enabled");
  expect(joined_arguments.find("CODEX_HOME=") == std::string::npos,
         "Codex home excluded from tool environment");
  expect(joined_arguments.find("/private/state") == std::string::npos,
         "materialization proof state excluded from tool arguments");
  expect(joined_arguments.find("SHELL=\"/system/bin/sh\"")
             != std::string::npos,
         "Codex reports the actual Android system shell");
  expect(joined_arguments.find(
             "PATH=\"/private/tool-bin:/private/native:/system/bin:/system/xbin\"")
             != std::string::npos,
         "Codex tools resolve packaged command aliases before system commands");
  expect(joined_arguments.find("AGENTCODI_TOOLCHAIN=\"/private/workspace/toolchain\"")
             != std::string::npos,
         "Codex tools receive the bounded workspace toolchain path");
  expect(joined_arguments.find("AGENTCODI_TOOLCHAIN_COMMAND=\"agentcodi-toolchain\"")
             != std::string::npos,
         "Codex tools receive the installation interface name");
  expect(joined_arguments.find("AGENTCODI_TOOL_BIN=\"/private/tool-bin\"")
             != std::string::npos,
         "Codex tools receive the dedicated packaged tool path");
  expect(joined_arguments.find("AGENTCODI_TOOL_RUNTIME=\"/private/tool-runtime\"")
             != std::string::npos,
         "Codex tools receive the verified packaged runtime path");
  expect(joined_arguments.find("AGENTCODI_TOOLCHAIN_PACKAGES=\"node,npm,python\"")
             != std::string::npos,
         "Codex tools receive all packaged tool names");
  expect(joined_arguments.find("AGENTCODI_SHELL_PATH") == std::string::npos
             && joined_arguments.find("AGENTCODI_NODE_PATH") == std::string::npos,
         "mutable executable path overrides are excluded from tool commands");
  expect(joined_arguments.find("\"/private/tool-bin\"=\"read\"")
             != std::string::npos,
         "packaged tool aliases are read-only in the permission profile");
  expect(joined_arguments.find("\"/private/tool-runtime\"=\"read\"")
             != std::string::npos,
         "packaged runtime is read-only in the permission profile");
  expect(joined_arguments.find("NODE_REPL_HISTORY=\"/dev/null\"")
             != std::string::npos,
         "Node REPL history persistence disabled");
  expect(joined_arguments.find("projects.") == std::string::npos,
         "unsupported project trust override omitted under strict config");
  expect(joined_arguments.find("analytics.enabled=false") != std::string::npos,
         "Codex analytics disabled");
  expect(joined_arguments.find("otel.exporter=\"none\"") != std::string::npos,
         "Codex telemetry exporter disabled");
  expect(joined_arguments.find("otel.log_user_prompt=false") != std::string::npos,
         "Codex prompt telemetry disabled");
  expect(joined_arguments.find("feedback.enabled=false") != std::string::npos,
         "Codex feedback upload disabled");
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

  const std::size_t java_frame_limit = 1024U * 1024U;
  const std::string image_payload(java_frame_limit + 128U * 1024U, 'A');
  const std::string image_notification =
      "{\"method\":\"item/completed\",\"params\":{\"item\":{"
      "\"id\":\"image_fixture\",\"result\":\"" + image_payload
      + "\",\"revisedPrompt\":\"\\uD83D\\uDE80\","
      "\"savedPath\":\"/private/workspace/image.png\","
      "\"type\":\"imageGeneration\",\"status\":\"completed\"}}}";
  std::string compacted_image;
  expect(
      agentcodi::CompactInboundImagePayloads(
          image_notification,
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kCompacted,
      "compact oversized image-generation result");
  expect(compacted_image.size() <= java_frame_limit,
         "compacted image fits Java frame");
  expect(compacted_image.find("image_fixture") != std::string::npos
             && compacted_image.find("/private/workspace/image.png")
                 != std::string::npos,
         "compacted image preserves metadata");
  expect(compacted_image.find("<generated-image-data-omitted>")
             != std::string::npos
             && compacted_image.find(std::string(64U, 'A')) == std::string::npos,
         "compacted image omits binary payload");

  const std::string medium_image_payload(600U * 1024U, 'B');
  const std::string medium_image =
      "{\"id\":\"medium_image\",\"type\":\"imageGeneration\","
      "\"result\":\"" + medium_image_payload
      + "\",\"status\":\"completed\"}";
  expect(medium_image.size() < java_frame_limit,
         "medium image fits wire frame before JSON parsing");
  expect(
      agentcodi::CompactInboundImagePayloads(
          medium_image,
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kCompacted,
      "compact image before stricter JSON string limit");

  const std::string raw_image =
      "{\"item\":{\"type\":\"image_generation_call\",\"result\":\""
      + image_payload + "\",\"status\":\"completed\"}}";
  expect(
      agentcodi::CompactInboundImagePayloads(
          raw_image,
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kCompacted,
      "compact raw image-generation response item");
  const std::string oversized_non_image =
      "{\"type\":\"agentMessage\",\"result\":\"" + image_payload + "\"}";
  expect(
      agentcodi::CompactInboundImagePayloads(
          oversized_non_image,
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kNotApplicable,
      "do not compact unrelated oversized protocol data");
  expect(
      agentcodi::CompactInboundImagePayloads(
          "{\"type\":\"imageGeneration\",\"result\":\"unterminated}",
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kInvalid,
      "reject malformed oversized image event");
  std::string invalid_utf8_payload(40U * 1024U, 'C');
  invalid_utf8_payload.push_back(static_cast<char>(0xc3U));
  invalid_utf8_payload.push_back('(');
  const std::string invalid_utf8_image =
      "{\"type\":\"imageGeneration\",\"result\":\""
      + invalid_utf8_payload + "\"}";
  expect(
      agentcodi::CompactInboundImagePayloads(
          invalid_utf8_image,
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kInvalid,
      "reject invalid UTF-8 hidden inside compactable image result");
  const std::string invalid_surrogate_image =
      "{\"type\":\"imageGeneration\",\"result\":\""
      + std::string(40U * 1024U, 'D') + "\\uD800\"}";
  expect(
      agentcodi::CompactInboundImagePayloads(
          invalid_surrogate_image,
          java_frame_limit,
          &compacted_image)
          == agentcodi::InboundLineCompactionStatus::kInvalid,
      "reject unpaired surrogate hidden inside compactable image result");

  char temporary_template[] = "/tmp/agentcodi-process-test-XXXXXX";
  char* temporary_root = mkdtemp(temporary_template);
  expect(temporary_root != nullptr, "temporary process-test root");
  if (temporary_root != nullptr) {
    const std::string root = temporary_root;
    const std::string workspace = root + "/workspace";
    const std::string toolchain = workspace + "/toolchain";
    const std::string tool_binary = root + "/tool-bin";
    const std::string tool_runtime = root + "/tool-runtime";
    const std::string codex_home = root + "/codex-home";
    const std::string home = root + "/home";
    const std::string state = root + "/state";
    const std::string temporary = root + "/temporary";
    expect(mkdir(workspace.c_str(), 0700) == 0, "process-test workspace");
    expect(mkdir(toolchain.c_str(), 0700) == 0, "process-test toolchain");
    expect(mkdir(tool_binary.c_str(), 0700) == 0, "process-test tool binary directory");
    expect(mkdir(tool_runtime.c_str(), 0700) == 0,
           "process-test packaged runtime directory");
    expect(mkdir((tool_runtime + "/npm").c_str(), 0700) == 0,
           "process-test npm runtime directory");
    expect(mkdir((tool_runtime + "/npm/node_modules").c_str(), 0700) == 0,
           "process-test npm modules directory");
    expect(mkdir((tool_runtime + "/npm/node_modules/npm").c_str(), 0700) == 0,
           "process-test npm package directory");
    expect(mkdir((tool_runtime + "/npm/node_modules/npm/bin").c_str(), 0700) == 0,
           "process-test npm binary directory");
    expect(write_fixture_file(
               tool_runtime + "/npm/node_modules/npm/bin/npm-cli.js",
               "printf 'npm-fixture\\n'\n"),
           "process-test npm CLI fixture");
    expect(mkdir((tool_runtime + "/python").c_str(), 0700) == 0,
           "process-test Python home");
    expect(mkdir((tool_runtime + "/python/lib").c_str(), 0700) == 0,
           "process-test Python library directory");
    expect(mkdir((tool_runtime + "/python/lib/python3.14").c_str(), 0700) == 0,
           "process-test Python standard library directory");
    expect(mkdir(
               (tool_runtime + "/python/lib/python3.14/encodings").c_str(),
               0700) == 0,
           "process-test Python encodings directory");
    expect(write_fixture_file(
               tool_runtime
                   + "/python/lib/python3.14/encodings/__init__.pyc",
               "python-fixture\n"),
           "process-test Python standard-library fixture");
    const std::string supervised_node_alias = tool_binary + "/node";
    const std::string supervised_npm_alias = tool_binary + "/npm";
    const std::string supervised_python_alias = tool_binary + "/python";
    const std::string supervised_python3_alias = tool_binary + "/python3";
    const std::string supervised_toolchain_alias = tool_binary + "/agentcodi-toolchain";
    expect(symlink("/system/bin/sh", supervised_node_alias.c_str()) == 0,
           "process-test Node alias");
    expect(symlink("/system/bin/sh", supervised_npm_alias.c_str()) == 0,
           "process-test npm alias");
    expect(symlink("/system/bin/sh", supervised_python_alias.c_str()) == 0,
           "process-test Python alias");
    expect(symlink("/system/bin/sh", supervised_python3_alias.c_str()) == 0,
           "process-test Python 3 alias");
    expect(symlink("/system/bin/sh", supervised_toolchain_alias.c_str()) == 0,
           "process-test toolchain alias");
    expect(mkdir(codex_home.c_str(), 0700) == 0, "process-test Codex home");
    expect(mkdir(home.c_str(), 0700) == 0, "process-test home");
    expect(mkdir(state.c_str(), 0700) == 0, "process-test private state");
    expect(mkdir(temporary.c_str(), 0700) == 0, "process-test temporary directory");

    const std::vector<unsigned char> valid_png = make_valid_fixture_png();
    expect(!valid_png.empty(), "construct valid native PNG fixture");
    const std::string materialized_payload = encode_fixture_base64(valid_png);
    const std::string materialized_event =
        "{\"method\":\"item/completed\",\"params\":{\"item\":{"
        "\"id\":\"materialize/fixture\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\""
        + materialized_payload
        + "\",\"savedPath\":\"/private/codex-home/generated_images/"
          "thread/materialize_fixture.png\"}}}";
    std::string prepared_event;
    std::string error;
    const agentcodi::InboundLineCompactionStatus materialized_status =
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            materialized_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_event,
            &error);
    if (materialized_status != agentcodi::InboundLineCompactionStatus::kCompacted) {
      std::cerr << "Materialization error: " << error << '\n';
    }
    expect(
        materialized_status == agentcodi::InboundLineCompactionStatus::kCompacted,
        "materialize generated image into workspace");
    const std::string materialized_path = saved_path_from_event(prepared_event);
    expect(
        !materialized_path.empty()
            && materialized_path.find(workspace + "/generated_images/") == 0U
            && prepared_event.find("/private/codex-home") == std::string::npos,
        "rewrite generated image path to canonical workspace path");
    expect(
        prepared_event.find("<generated-image-data-omitted>")
            != std::string::npos
            && prepared_event.find(std::string(64U, 'A')) == std::string::npos,
        "compact image only after workspace materialization");
    struct stat materialized_metadata {};
    expect(
        !materialized_path.empty()
            && lstat(materialized_path.c_str(), &materialized_metadata) == 0
            && S_ISREG(materialized_metadata.st_mode)
            && materialized_metadata.st_nlink == 1
            && (materialized_metadata.st_mode & (S_IRWXG | S_IRWXO)) == 0,
        "materialized image is a private regular file");
    unsigned char materialized_signature[8] {};
    const int materialized_descriptor = materialized_path.empty()
        ? -1
        : open(materialized_path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    const ssize_t materialized_signature_size = materialized_descriptor < 0
        ? -1
        : read(
            materialized_descriptor,
            materialized_signature,
            sizeof(materialized_signature));
    if (materialized_descriptor >= 0) {
      close(materialized_descriptor);
    }
    expect(
        materialized_signature_size == static_cast<ssize_t>(
            sizeof(materialized_signature))
            && materialized_signature[0] == 0x89U
            && materialized_signature[1] == 'P'
            && materialized_signature[2] == 'N'
            && materialized_signature[3] == 'G',
        "materialized image preserves decoded PNG bytes");
    const std::string materialized_proof_path = proof_path_for_id(
        state,
        "materialize/fixture");
    struct stat proof_directory_metadata {};
    struct stat proof_metadata {};
    std::string proof_contents;
    const std::string materialized_id = "materialize/fixture";
    const std::string expected_proof =
        "agentcodi-image-proof-v1\n"
        "id-sha256="
        + agentcodi::Sha256Hex(
            reinterpret_cast<const unsigned char*>(materialized_id.data()),
            materialized_id.size())
        + "\nimage-sha256="
        + agentcodi::Sha256Hex(valid_png.data(), valid_png.size())
        + "\nsize=" + std::to_string(valid_png.size()) + "\n";
    expect(
        lstat(
            (state + "/image-materialization-proofs").c_str(),
            &proof_directory_metadata) == 0
            && S_ISDIR(proof_directory_metadata.st_mode)
            && (proof_directory_metadata.st_mode & 0777) == 0700
            && materialized_proof_path.find(workspace) != 0U,
        "materialization proof directory remains private and outside workspace");
    expect(
        lstat(materialized_proof_path.c_str(), &proof_metadata) == 0
            && S_ISREG(proof_metadata.st_mode)
            && proof_metadata.st_nlink == 1
            && (proof_metadata.st_mode & 0777) == 0600
            && read_fixture_file(materialized_proof_path, &proof_contents)
            && proof_contents == expected_proof,
        "persist exact owner-only SHA-256 materialization proof");

    const std::string missing_path_event =
        "{\"id\":\"missing_path_fixture\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\""
        + materialized_payload + "\"}";
    std::string prepared_missing_path;
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            missing_path_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_missing_path,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted,
        "materialize image when app-server omits savedPath");
    const std::string inserted_materialized_path =
        saved_path_from_event(prepared_missing_path);
    expect(
        !inserted_materialized_path.empty()
            && inserted_materialized_path.find(
                workspace + "/generated_images/") == 0U,
        "insert canonical workspace savedPath into image event");

    const std::string resumed_image_event =
        "{\"id\":\"materialize/fixture\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\"\","
        "\"savedPath\":\"/private/codex-home/generated_images/thread/"
        "materialize_fixture.png\"}";
    std::string prepared_resume;
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            resumed_image_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_resume,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted
            && saved_path_from_event(prepared_resume) == materialized_path,
        "reuse materialized workspace image when history omits inline bytes");

    const std::vector<unsigned char> valid_replacement_png =
        make_valid_fixture_png(1U, 0U);
    expect(
        !materialized_path.empty() && valid_replacement_png != valid_png
            && unlink(materialized_path.c_str()) == 0
            && write_fixture_file(
                materialized_path,
                std::string(
                    valid_replacement_png.begin(),
                    valid_replacement_png.end())),
        "replace materialized image with a different valid PNG");
    prepared_resume.clear();
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            resumed_image_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_resume,
            &error) == agentcodi::InboundLineCompactionStatus::kInvalid
            && prepared_resume.empty()
            && error.find("SHA-256 proof") != std::string::npos,
        "reject a valid replacement PNG whose digest lacks prior proof");
    expect(
        unlink(materialized_path.c_str()) == 0
            && write_fixture_file(
                materialized_path,
                std::string(valid_png.begin(), valid_png.end())),
        "restore the originally proven materialized PNG bytes");
    prepared_resume.clear();
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            resumed_image_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_resume,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted
            && saved_path_from_event(prepared_resume) == materialized_path,
        "reuse restored bytes only when they match the stored digest");

    const std::string absent_resume_event =
        "{\"id\":\"never_materialized\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\"\",\"savedPath\":\""
        + materialized_path + "\"}";
    std::string prepared_absent_resume;
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            absent_resume_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_absent_resume,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted
            && prepared_absent_resume.find("\"savedPath\":null")
                != std::string::npos
            && prepared_absent_resume.find(materialized_path)
                == std::string::npos
            && saved_path_from_event(prepared_absent_resume).empty(),
        "clear app-server savedPath when no proven materialization exists");

    const std::string legacy_id = "legacy_unproven_fixture";
    const std::string legacy_inline_event =
        "{\"id\":\"" + legacy_id + "\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\""
        + materialized_payload + "\",\"savedPath\":null}";
    std::string prepared_legacy_inline;
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            legacy_inline_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_legacy_inline,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted,
        "materialize legacy-proof regression fixture from inline bytes");
    const std::string legacy_materialized_path =
        saved_path_from_event(prepared_legacy_inline);
    const std::string legacy_proof_path = proof_path_for_id(state, legacy_id);
    expect(
        !legacy_materialized_path.empty()
            && unlink(legacy_proof_path.c_str()) == 0,
        "remove only the private proof to model an unproven legacy image");
    const std::string legacy_resume_event =
        "{\"id\":\"" + legacy_id + "\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\"\",\"savedPath\":\""
        + legacy_materialized_path + "\"}";
    std::string prepared_legacy_resume;
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            legacy_resume_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_legacy_resume,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted
            && prepared_legacy_resume.find("\"savedPath\":null")
                != std::string::npos
            && prepared_legacy_resume.find(legacy_materialized_path)
                == std::string::npos,
        "never infer a resume proof from a valid workspace PNG alone");

    const std::string conflicting_image_event =
        "{\"id\":\"materialize/fixture\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\""
        + encode_fixture_base64(png_with_private_chunk(
            make_valid_fixture_png(0U, 1U)))
        + "\",\"savedPath\":null}";
    error.clear();
    struct stat preserved_metadata {};
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            conflicting_image_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_event,
            &error) == agentcodi::InboundLineCompactionStatus::kInvalid
            && error.find("conflicts") != std::string::npos
            && lstat(materialized_path.c_str(), &preserved_metadata) == 0
            && preserved_metadata.st_size == materialized_metadata.st_size,
        "validate Adam7 PNG and never overwrite when an item id conflicts");

    const std::string invalid_image_event =
        "{\"id\":\"invalid_image\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\"QUFBQQ==\","
        "\"savedPath\":null}";
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            invalid_image_event,
            1024U * 1024U,
            workspace,
            temporary,
            state,
            &prepared_event,
            &error) == agentcodi::InboundLineCompactionStatus::kInvalid
            && error.find("PNG") != std::string::npos,
        "reject non-PNG image payload before writing it");

    const auto expect_invalid_png = [
        &workspace,
        &temporary,
        &state,
        &prepared_event,
        &error](
        const std::vector<unsigned char>& bytes,
        const std::string& id,
        const char* message) {
      const std::string event =
          "{\"id\":\"" + id + "\",\"type\":\"imageGeneration\","
          "\"status\":\"completed\",\"result\":\""
          + encode_fixture_base64(bytes) + "\",\"savedPath\":null}";
      error.clear();
      expect(
          agentcodi::MaterializeAndCompactInboundImagePayloads(
              event,
              1024U * 1024U,
              workspace,
              temporary,
              state,
              &prepared_event,
              &error) == agentcodi::InboundLineCompactionStatus::kInvalid
              && error.find("PNG") != std::string::npos,
          message);
    };

    std::vector<unsigned char> signature_and_garbage = {
        0x89U, 'P', 'N', 'G', 0x0dU, 0x0aU, 0x1aU, 0x0aU,
        0x00U, 0x00U, 0x00U, 0x00U, 'D', 'A', 'T', 'A',
    };
    expect_invalid_png(
        signature_and_garbage,
        "signature_garbage",
        "reject PNG signature followed by arbitrary bytes before writing");

    std::vector<unsigned char> zero_width = valid_png;
    std::fill(zero_width.begin() + 16U, zero_width.begin() + 20U, 0U);
    rewrite_fixture_chunk_crc(&zero_width, 8U);
    expect_invalid_png(
        zero_width,
        "zero_width",
        "reject CRC-correct PNG with invalid IHDR dimensions");

    std::vector<unsigned char> crossing_chunk = valid_png;
    write_fixture_u32(&crossing_chunk, 8U, 0x7fffffffU);
    expect_invalid_png(
        crossing_chunk,
        "crossing_chunk",
        "reject PNG chunk length outside decoded payload");

    std::vector<unsigned char> bad_crc = valid_png;
    bad_crc[29U] ^= 0x01U;
    expect_invalid_png(
        bad_crc,
        "bad_crc",
        "reject PNG with invalid chunk CRC");

    std::vector<unsigned char> corrupt_idat = valid_png;
    const std::size_t idat = find_fixture_chunk(corrupt_idat, "IDAT");
    expect(idat != std::string::npos, "valid native PNG fixture contains IDAT");
    if (idat != std::string::npos) {
      corrupt_idat[idat + 8U] ^= 0x01U;
      rewrite_fixture_chunk_crc(&corrupt_idat, idat);
      expect_invalid_png(
          corrupt_idat,
          "corrupt_idat",
          "reject CRC-correct PNG with invalid compressed image data");
    }

    std::vector<unsigned char> incomplete_shape = valid_png;
    incomplete_shape[19U] = 0x02U;
    rewrite_fixture_chunk_crc(&incomplete_shape, 8U);
    expect_invalid_png(
        incomplete_shape,
        "incomplete_shape",
        "reject PNG whose IDAT scanline is shorter than its IHDR shape");

    expect_invalid_png(
        make_valid_fixture_png(5U, 0U),
        "invalid_filter",
        "reject PNG with invalid decompressed scanline filter");

    std::vector<unsigned char> missing_iend = valid_png;
    missing_iend.resize(missing_iend.size() - 12U);
    expect_invalid_png(
        missing_iend,
        "missing_iend",
        "reject PNG without IEND");

    std::vector<unsigned char> trailing_png = valid_png;
    trailing_png.push_back('X');
    expect_invalid_png(
        trailing_png,
        "trailing_png",
        "reject bytes after PNG IEND");

    if (!inserted_materialized_path.empty()) {
      expect(unlink(inserted_materialized_path.c_str()) == 0,
             "remove valid resumed-image fixture before corruption test");
      const std::string corrupt_file(
          signature_and_garbage.begin(),
          signature_and_garbage.end());
      expect(write_fixture_file(inserted_materialized_path, corrupt_file),
             "replace resumed-image fixture with malformed PNG");
      const std::string corrupt_resume_event =
          "{\"id\":\"missing_path_fixture\",\"type\":\"imageGeneration\","
          "\"status\":\"completed\",\"result\":\"\",\"savedPath\":null}";
      error.clear();
      expect(
          agentcodi::MaterializeAndCompactInboundImagePayloads(
              corrupt_resume_event,
              1024U * 1024U,
              workspace,
              temporary,
              state,
              &prepared_event,
              &error) == agentcodi::InboundLineCompactionStatus::kInvalid
              && error.find("PNG") != std::string::npos,
          "fully revalidate a materialized PNG before resumed-history reuse");
    }

    agentcodi::ProcessConfig config;
    config.executable = "/system/bin/sh";
    config.code_mode_host_executable = "/system/bin/sh";
    config.shell_executable = "/system/bin/sh";
    config.node_executable = "/system/bin/sh";
    config.python_executable = "/system/bin/sh";
    config.working_directory = workspace;
    config.toolchain_directory = toolchain;
    config.tool_binary_directory = tool_binary;
    config.tool_runtime_directory = tool_runtime;
    config.codex_home = codex_home;
    config.home_directory = home;
    config.state_directory = state;
    config.temporary_directory = temporary;
    config.library_directory = "/system/lib64";
    config.arguments = {
        "-c",
        "printf '%s|%s|%s|%s\\n' \"${AGENTCODI_PARENT_SECRET-unset}\" "
        "\"$CODEX_HOME\" \"$HOME\" \"$(umask)\"; "
        "printf '%s\\n' \"$CODEX_CODE_MODE_HOST_PATH\"; "
        "IFS= read -r line; printf '%s\\n' \"$line\"",
    };
    expect(setenv("AGENTCODI_PARENT_SECRET", "must-not-leak", 1) == 0,
           "set inherited-secret fixture");
    std::shared_ptr<agentcodi::AppServerProcess> process =
        agentcodi::AppServerProcess::Start(config, &error);
    unsetenv("AGENTCODI_PARENT_SECRET");
    expect(process != nullptr, "spawn supervised process");
    if (process != nullptr) {
      std::string child_security_state;
      expect(
          process->ReadLine(1024U, &child_security_state, &error)
              == agentcodi::LineReadStatus::kLine,
          "read child environment security state");
      expect(
          child_security_state.find("unset|" + codex_home + "|" + home + "|") == 0U
              && child_security_state.find(state) == std::string::npos,
          "child inherits only explicit private environment");
      expect(
          child_security_state.find("|0077") != std::string::npos
              || child_security_state.find("|077") != std::string::npos,
          "child process owner-only umask");
      std::string host_path;
      expect(
          process->ReadLine(1024U, &host_path, &error)
              == agentcodi::LineReadStatus::kLine,
          "read code-mode host environment");
      expect(host_path == "/system/bin/sh", "canonical code-mode host environment");
      const std::string probe = "{\"probe\":\"ok\"}";
      std::vector<unsigned char> mutable_probe(probe.begin(), probe.end());
      expect(
          process->WriteBytes(&mutable_probe, mutable_probe.size(), 1024U, &error),
          "write mutable framed process line");
      expect(
          std::find(mutable_probe.begin(), mutable_probe.end(), 0U)
              == mutable_probe.begin()
              && std::find_if(
                     mutable_probe.begin(),
                     mutable_probe.end(),
                     [](unsigned char value) { return value != 0U; })
                  == mutable_probe.end(),
          "mutable process write buffer wiped");
      std::string response;
      expect(
          process->ReadLine(1024U, &response, &error)
              == agentcodi::LineReadStatus::kLine,
          "read framed process line");
      expect(response == probe, "process framing round trip");
      expect(process->Stop(500) != INT_MIN, "supervised process stop");
    }

    char self_executable[PATH_MAX];
    const char* current_library_path = std::getenv("LD_LIBRARY_PATH");
    std::string test_library_directory = current_library_path == nullptr
        ? ""
        : current_library_path;
    const std::size_t library_separator = test_library_directory.find(':');
    if (library_separator != std::string::npos) {
      test_library_directory.resize(library_separator);
    }
    const bool self_resolved = argc > 0
        && realpath(argv[0], self_executable) != nullptr;
    expect(self_resolved && !test_library_directory.empty(),
           "resolve framing fixture executable");
    const bool shell_fixture_available = argc >= 2 && access(argv[1], X_OK) == 0;
    expect(shell_fixture_available, "resolve packaged shell fixture executable");
    if (shell_fixture_available) {
      std::string shell_output;
      int shell_exit = -1;
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "list"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("available, not enabled") != std::string::npos,
          "toolchain reports packaged Node before activation");
      expect(
          run_toolchain_shell(
              argv[1], {"--node", "--version"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 127
              && shell_output.find("Ask the user for permission") != std::string::npos,
          "missing Node directs Codex to the approval-backed installer");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "install", "node"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("Enabled packaged Node.js 24.18.0")
                  != std::string::npos,
          "activate packaged Node through shared toolchain interface");
      const std::string node_marker = toolchain + "/installed/node-24.18.0";
      struct stat marker_metadata {};
      expect(
          lstat(node_marker.c_str(), &marker_metadata) == 0
              && S_ISREG(marker_metadata.st_mode)
              && marker_metadata.st_nlink == 1
              && (marker_metadata.st_mode & 0777) == 0600,
          "Node activation marker is private and simply linked");
      const int corrupt_marker = open(
          node_marker.c_str(),
          O_WRONLY | O_TRUNC | O_CLOEXEC | O_NOFOLLOW);
      const char invalid_marker[] = "invalid\n";
      expect(
          corrupt_marker >= 0
              && write(
                  corrupt_marker,
                  invalid_marker,
                  sizeof(invalid_marker) - 1U)
                  == static_cast<ssize_t>(sizeof(invalid_marker) - 1U),
          "corrupt Node marker fixture");
      if (corrupt_marker >= 0) {
        close(corrupt_marker);
      }
      expect(
          run_toolchain_shell(
              argv[1], {"--node", "-c", "printf unexpected"},
              workspace, toolchain, &shell_output, &shell_exit)
              && shell_exit == 126
              && shell_output.find("unsafe metadata") != std::string::npos,
          "reject a private marker with forged contents");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "install", "node"}, workspace,
              toolchain, &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("Enabled packaged Node.js 24.18.0")
                  != std::string::npos,
          "repair an interrupted or corrupted Node activation marker");
      expect(
          run_toolchain_shell(
              argv[1], {"--node", "-c", "printf 'node-%s' ready"},
              workspace, toolchain, &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("node-ready") != std::string::npos,
          "activated Node command routes through packaged executable");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "install", "npm"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("Enabled packaged npm 11.19.0")
                  != std::string::npos,
          "activate packaged npm and its Node dependency");
      const std::string npm_marker = toolchain + "/installed/npm-11.19.0";
      expect(
          run_toolchain_shell(
              argv[1], {"--npm", "--version"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("npm-fixture") != std::string::npos,
          "activated npm routes its verified CLI through packaged Node");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "remove", "node"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("Disabled Node.js 24.18.0") != std::string::npos,
          "deactivate packaged Node through shared toolchain interface");
      expect(access(node_marker.c_str(), F_OK) != 0, "remove Node activation marker");
      expect(access(npm_marker.c_str(), F_OK) != 0,
             "removing Node also disables dependent npm");
      expect(mkfifo(node_marker.c_str(), 0600) == 0,
             "create non-blocking special marker fixture");
      expect(
          run_toolchain_shell(
              argv[1], {"--node", "--version"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 126
              && shell_output.find("unsafe metadata") != std::string::npos,
          "reject special activation marker without blocking");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "install", "node"}, workspace,
              toolchain, &shell_output, &shell_exit)
              && shell_exit == 0,
          "replace a non-directory special marker during explicit activation");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "remove", "node"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0,
          "remove repaired special-marker activation");
      expect(access(node_marker.c_str(), F_OK) != 0,
             "remove repaired Node activation marker");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "install", "python"}, workspace,
              toolchain, &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("Enabled packaged Python 3.14.6")
                  != std::string::npos,
          "activate packaged Python through shared toolchain interface");
      expect(
          run_toolchain_shell(
              argv[1], {"--python", "-c", "printf 'python-ready'"},
              workspace, toolchain, &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("python-ready") != std::string::npos,
          "activated Python command routes through packaged executable");
      expect(
          run_toolchain_shell(
              argv[1], {"--toolchain", "remove", "python"}, workspace,
              toolchain, &shell_output, &shell_exit)
              && shell_exit == 0,
          "deactivate packaged Python through shared toolchain interface");
      expect(rmdir((toolchain + "/installed").c_str()) == 0,
             "remove toolchain activation directory");

      const std::string bridge_tool_binary = root + "/bridge-tool-bin";
      const std::string bridge_node_alias = bridge_tool_binary + "/node";
      const std::string bridge_npm_alias = bridge_tool_binary + "/npm";
      const std::string bridge_python_alias = bridge_tool_binary + "/python";
      const std::string bridge_python3_alias = bridge_tool_binary + "/python3";
      const std::string bridge_toolchain_alias =
          bridge_tool_binary + "/agentcodi-toolchain";
      expect(mkdir(bridge_tool_binary.c_str(), 0700) == 0,
             "create bridge alias fixture directory");
      expect(symlink(argv[1], bridge_node_alias.c_str()) == 0,
             "create packaged Node command alias");
      expect(symlink(argv[1], bridge_npm_alias.c_str()) == 0,
             "create packaged npm command alias");
      expect(symlink(argv[1], bridge_python_alias.c_str()) == 0,
             "create packaged Python command alias");
      expect(symlink(argv[1], bridge_python3_alias.c_str()) == 0,
             "create packaged Python 3 command alias");
      expect(symlink(argv[1], bridge_toolchain_alias.c_str()) == 0,
             "create packaged toolchain command alias");
      expect(
          run_toolchain_shell(
              bridge_toolchain_alias, {"install", "node"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0,
          "PATH-style toolchain alias dispatches by invocation name");
      expect(
          run_toolchain_shell(
              bridge_node_alias, {"-c", "printf 'alias-%s' ready"},
              workspace, toolchain, &shell_output, &shell_exit)
              && shell_exit == 0
              && shell_output.find("alias-ready") != std::string::npos,
          "PATH-style Node alias reaches the packaged runtime");
      expect(
          run_toolchain_shell(
              bridge_toolchain_alias, {"remove", "node"}, workspace, toolchain,
              &shell_output, &shell_exit)
              && shell_exit == 0,
          "PATH-style toolchain alias removes activation");
      expect(unlink(bridge_node_alias.c_str()) == 0, "remove Node alias fixture");
      expect(unlink(bridge_npm_alias.c_str()) == 0, "remove npm alias fixture");
      expect(unlink(bridge_python_alias.c_str()) == 0,
             "remove Python alias fixture");
      expect(unlink(bridge_python3_alias.c_str()) == 0,
             "remove Python 3 alias fixture");
      expect(unlink(bridge_toolchain_alias.c_str()) == 0,
             "remove toolchain alias fixture");
      expect(rmdir(bridge_tool_binary.c_str()) == 0,
             "remove bridge alias fixture directory");
    }
    if (self_resolved && !test_library_directory.empty()) {
      config.executable = self_executable;
      config.library_directory = test_library_directory;
      config.arguments = {"--emit-oversized-image"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn oversized image framing fixture");
      std::string child_materialized_path;
      if (process != nullptr) {
        std::string compacted_child_image;
        const agentcodi::LineReadStatus child_read_status = process->ReadLine(
            1024U * 1024U,
            &compacted_child_image,
            &error);
        if (child_read_status != agentcodi::LineReadStatus::kLine) {
          std::cerr << "Child image framing error: " << error << '\n';
        }
        expect(
            child_read_status == agentcodi::LineReadStatus::kLine,
            "read compacted oversized image line");
        expect(
            compacted_child_image.find("child_image") != std::string::npos
                && compacted_child_image.find("<generated-image-data-omitted>")
                    != std::string::npos
                && compacted_child_image.size() <= 1024U * 1024U,
            "supervisor preserves bounded image metadata");
        child_materialized_path = saved_path_from_event(compacted_child_image);
        struct stat child_image_metadata {};
        expect(
            !child_materialized_path.empty()
                && child_materialized_path.find(
                    workspace + "/generated_images/") == 0U
                && lstat(
                    child_materialized_path.c_str(),
                    &child_image_metadata) == 0
                && S_ISREG(child_image_metadata.st_mode),
            "supervisor materializes generated image before forwarding event");
        std::string following_notification;
        expect(
            process->ReadLine(
                1024U * 1024U,
                &following_notification,
                &error) == agentcodi::LineReadStatus::kLine,
            "continue framing after compacted image line");
        expect(
            following_notification.find("turn/completed") != std::string::npos
                && following_notification.find("child_turn") != std::string::npos,
            "preserve notification following compacted image line");
        expect(process->Stop(500) != INT_MIN,
               "stop oversized image framing fixture");
      }
      if (!child_materialized_path.empty()) {
        config.arguments = {"--emit-resumed-image"};
        error.clear();
        process = agentcodi::AppServerProcess::Start(config, &error);
        expect(process != nullptr,
               "restart supervisor for persisted image-proof resume fixture");
        if (process != nullptr) {
          std::string resumed_child_image;
          expect(
              process->ReadLine(
                  1024U * 1024U,
                  &resumed_child_image,
                  &error) == agentcodi::LineReadStatus::kLine
                  && saved_path_from_event(resumed_child_image)
                      == child_materialized_path
                  && resumed_child_image.find("/private/codex-home")
                      == std::string::npos,
              "reuse image only through persisted proof after supervisor restart");
          expect(process->Stop(500) != INT_MIN,
                 "stop persisted image-proof resume fixture");
        }
        expect(unlink(child_materialized_path.c_str()) == 0,
               "remove child materialization fixture");
      }

      config.arguments = {"--emit-mcp-catalog"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn MCP catalog framing fixture");
      if (process != nullptr) {
        std::string catalog_response;
        expect(
            process->ReadLine(16U * 1024U, &catalog_response, &error)
                == agentcodi::LineReadStatus::kLine,
            "read bounded MCP catalog response");
        expect(
            catalog_response.find("\"name\":\"fixture-mcp\"")
                    != std::string::npos
                && catalog_response.find("\"inputSchema\":{\"type\":\"object\"}")
                    != std::string::npos,
            "supervisor preserves unrelated MCP inventory data for Java validation");
        expect(process->Stop(500) != INT_MIN,
               "stop MCP catalog framing fixture");
      }

      config.arguments = {"--mcp-config-roundtrip"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn MCP configuration framing fixture");
      if (process != nullptr) {
        const std::string config_read =
            "{\"id\":61,\"method\":\"config/read\",\"params\":{"
            "\"cwd\":\"/private/workspace\",\"includeLayers\":false}}";
        expect(
            process->WriteLine(config_read, 16U * 1024U, &error),
            "write bounded MCP configuration read request");
        std::string config_response;
        expect(
            process->ReadLine(16U * 1024U, &config_response, &error)
                == agentcodi::LineReadStatus::kLine
                && config_response.find("\"mcp_servers\":{}")
                    != std::string::npos,
            "read bounded MCP configuration response");

        const std::string config_write =
            "{\"id\":62,\"method\":\"config/batchWrite\",\"params\":{"
            "\"edits\":[{\"keyPath\":\"mcp_servers.fixture\","
            "\"value\":{\"url\":\"https://example.com/mcp\","
            "\"enabled\":false,\"required\":false},"
            "\"mergeStrategy\":\"replace\"}],"
            "\"reloadUserConfig\":false}}";
        expect(
            process->WriteLine(config_write, 16U * 1024U, &error),
            "write bounded MCP batch request without a path");
        std::string write_response;
        expect(
            process->ReadLine(16U * 1024U, &write_response, &error)
                == agentcodi::LineReadStatus::kLine
                && write_response.find("\"status\":\"ok\"")
                    != std::string::npos
                && write_response.find("/private/codex-home/config.toml")
                    != std::string::npos,
            "preserve write response for path-free Java projection");

        const std::string reload =
            "{\"id\":63,\"method\":\"config/mcpServer/reload\"}";
        expect(
            process->WriteLine(reload, 16U * 1024U, &error),
            "write parameter-free MCP reload request");
        std::string reload_response;
        expect(
            process->ReadLine(16U * 1024U, &reload_response, &error)
                == agentcodi::LineReadStatus::kLine
                && reload_response == "{\"id\":63,\"result\":{}}",
            "read MCP reload response");
        expect(process->Stop(500) == 0,
               "stop MCP configuration framing fixture");
      }

      config.arguments = {"--rate-limits-roundtrip"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn rate-limit framing fixture");
      if (process != nullptr) {
        const std::string rate_limits_read =
            "{\"id\":64,\"method\":\"account/rateLimits/read\"}";
        expect(
            process->WriteLine(rate_limits_read, 16U * 1024U, &error),
            "write parameter-free rate-limit read request");
        std::string rate_limits_response;
        expect(
            process->ReadLine(16U * 1024U, &rate_limits_response, &error)
                    == agentcodi::LineReadStatus::kLine
                && rate_limits_response.find("\"usedPercent\":25")
                    != std::string::npos
                && rate_limits_response.find("\"windowDurationMins\":10080")
                    != std::string::npos,
            "preserve bounded rate-limit response for Java validation");
        std::string rate_limits_update;
        expect(
            process->ReadLine(16U * 1024U, &rate_limits_update, &error)
                    == agentcodi::LineReadStatus::kLine
                && rate_limits_update.find("account/rateLimits/updated")
                    != std::string::npos
                && rate_limits_update.find("\"usedPercent\":31")
                    != std::string::npos,
            "preserve sparse rate-limit update notification");
        expect(process->Stop(500) == 0,
               "stop rate-limit framing fixture");
      }

      config.arguments = {"--turn-steer-roundtrip"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn turn-steer framing fixture");
      if (process != nullptr) {
        const std::string steer_request =
            "{\"id\":65,\"method\":\"turn/steer\",\"params\":{"
            "\"threadId\":\"thr_fixture\",\"input\":[{"
            "\"type\":\"text\",\"text\":\"Focus on tests first.\"}],"
            "\"expectedTurnId\":\"turn_fixture\"}}";
        expect(
            process->WriteLine(steer_request, 16U * 1024U, &error),
            "write bounded correlated turn-steer request");
        std::string steer_response;
        expect(
            process->ReadLine(16U * 1024U, &steer_response, &error)
                    == agentcodi::LineReadStatus::kLine
                && steer_response
                    == "{\"id\":65,\"result\":{\"turnId\":\"turn_fixture\"}}",
            "preserve correlated turn-steer response");
        std::string steer_item;
        expect(
            process->ReadLine(16U * 1024U, &steer_item, &error)
                    == agentcodi::LineReadStatus::kLine
                && steer_item.find("\"method\":\"item/completed\"")
                    != std::string::npos
                && steer_item.find("\"id\":\"steer_user_fixture\"")
                    != std::string::npos
                && steer_item.find("\"turnId\":\"turn_fixture\"")
                    != std::string::npos
                && steer_item.find("turn/started") == std::string::npos,
            "preserve steering user item without inventing a new turn");
        expect(process->Stop(500) == 0,
               "stop turn-steer framing fixture");
      }

      config.arguments = {"--turn-import-roundtrip"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn turn-import framing fixture");
      if (process != nullptr) {
        const std::string import_request =
            "{\"id\":66,\"method\":\"turn/start\",\"params\":{"
            "\"threadId\":\"thr_import\",\"input\":[{"
            "\"type\":\"text\",\"text\":\"Analyze it.\"},{"
            "\"type\":\"mention\",\"name\":\"outside-data.csv\","
            "\"path\":\"/private/workspace/imports/"
            "0123456789abcdef-outside-data.csv\"}],"
            "\"additionalContext\":{\"agentcodi-import-1\":{"
            "\"kind\":\"application\",\"value\":\"The current user turn "
            "includes an imported regular file at this canonical private workspace "
            "path:\\n/private/workspace/imports/"
            "0123456789abcdef-outside-data.csv\\nRead the file's actual bytes "
            "with the workspace tools before answering. Do not infer its contents "
            "from the visible attachment label.\"}},"
            "\"cwd\":\"/private/workspace\","
            "\"runtimeWorkspaceRoots\":[\"/private/workspace\"],"
            "\"approvalPolicy\":\"on-request\","
            "\"permissions\":{\"id\":\"agentcodi-workspace\"},"
            "\"model\":\"gpt-5.6-sol\",\"effort\":\"high\","
            "\"summary\":\"auto\"}}";
        expect(
            process->WriteLine(import_request, 16U * 1024U, &error),
            "write bounded turn/start request with mention and native file context");
        std::string import_response;
        expect(
            process->ReadLine(16U * 1024U, &import_response, &error)
                    == agentcodi::LineReadStatus::kLine
                && import_response.find("\"id\":66") != std::string::npos
                && import_response.find("\"id\":\"turn_import\"")
                    != std::string::npos,
            "preserve imported-file turn/start response");
        std::string import_item;
        expect(
            process->ReadLine(16U * 1024U, &import_item, &error)
                    == agentcodi::LineReadStatus::kLine
                && import_item.find("\"type\":\"mention\"")
                    != std::string::npos
                && import_item.find("outside-data.csv") != std::string::npos
                && import_item.find("/private/workspace/imports/")
                    != std::string::npos
                && import_item.find("content://") == std::string::npos,
            "preserve authoritative native mention without exposing provider URI");
        expect(process->Stop(500) == 0,
               "stop turn-import framing fixture");
      }

      config.arguments = {"--exit-with-code"};
      error.clear();
      process = agentcodi::AppServerProcess::Start(config, &error);
      expect(process != nullptr, "spawn early-exit diagnostic fixture");
      if (process != nullptr) {
        std::string unexpected_output;
        expect(
            process->ReadLine(1024U, &unexpected_output, &error)
                == agentcodi::LineReadStatus::kError
                && error.find("exited with code 23") != std::string::npos,
            "surface bounded app-server exit status instead of generic EOF");
        expect(process->Stop(500) == 23,
               "retain early app-server exit status during cleanup");
      }
    }

    config.executable = "/system/bin/sh";
    config.library_directory = "/system/lib64";

    config.code_mode_host_executable = root + "/missing-code-mode-host";
    error.clear();
    process = agentcodi::AppServerProcess::Start(config, &error);
    expect(process == nullptr, "reject missing code-mode host");
    expect(
        error.find("Code-mode host executable") != std::string::npos,
        "missing code-mode host error");

    config.code_mode_host_executable = "/system/bin/sh";
    config.codex_home = workspace;
    error.clear();
    process = agentcodi::AppServerProcess::Start(config, &error);
    expect(process == nullptr, "reject Codex home inside workspace");
    expect(error.find("separate") != std::string::npos, "auth boundary error");

    config.codex_home = codex_home;
    config.state_directory = workspace;
    error.clear();
    process = agentcodi::AppServerProcess::Start(config, &error);
    expect(process == nullptr, "reject image proof state inside workspace");
    expect(
        error.find("state must be private and separate") != std::string::npos,
        "materialization proof state boundary error");

    config.state_directory = state;
    config.arguments = {"-c", "exit 0"};
    const std::string configuration_path = codex_home + "/config.toml";
    const int configuration_descriptor = open(
        configuration_path.c_str(),
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC,
        0600);
    expect(configuration_descriptor >= 0, "create Codex configuration fixture");
    if (configuration_descriptor >= 0) {
      expect(fchmod(configuration_descriptor, 0644) == 0,
             "make Codex configuration fixture non-private");
      close(configuration_descriptor);
    }
    error.clear();
    process = agentcodi::AppServerProcess::Start(config, &error);
    expect(process == nullptr, "reject non-private Codex configuration file");
    expect(error.find("private regular file") != std::string::npos,
           "non-private Codex configuration error");
    expect(chmod(configuration_path.c_str(), 0600) == 0,
           "make Codex configuration fixture private");
    error.clear();
    process = agentcodi::AppServerProcess::Start(config, &error);
    expect(process != nullptr, "accept private Codex configuration file");
    if (process != nullptr) {
      expect(process->Stop(500) != INT_MIN,
             "clean up with private Codex configuration file present");
    }
    expect(unlink(configuration_path.c_str()) == 0,
           "remove Codex configuration fixture");

    if (!materialized_path.empty()) {
      expect(unlink(materialized_path.c_str()) == 0,
             "remove direct materialization fixture");
    }
    if (!inserted_materialized_path.empty()) {
      expect(unlink(inserted_materialized_path.c_str()) == 0,
             "remove inserted-path materialization fixture");
    }
    if (!legacy_materialized_path.empty()) {
      expect(unlink(legacy_materialized_path.c_str()) == 0,
             "remove unproven legacy materialization fixture");
    }
    expect(rmdir((workspace + "/generated_images").c_str()) == 0,
           "remove generated-image fixture directory");
    expect(
        unlink(proof_path_for_id(state, "materialize/fixture").c_str()) == 0,
        "remove materialized-image proof fixture");
    expect(
        unlink(proof_path_for_id(state, "missing_path_fixture").c_str()) == 0,
        "remove inserted-path proof fixture");
    const std::string child_proof_path = proof_path_for_id(state, "child_image");
    if (unlink(child_proof_path.c_str()) != 0) {
      expect(errno == ENOENT, "remove child image proof fixture when present");
    }
    expect(rmdir((state + "/image-materialization-proofs").c_str()) == 0,
           "remove image materialization proof directory");
    expect(rmdir(temporary.c_str()) == 0,
           "image materialization leaves private temporary directory empty");
    expect(rmdir(state.c_str()) == 0,
           "image materialization leaves only explicit private proof state");
    rmdir(home.c_str());
    rmdir(codex_home.c_str());
    unlink(supervised_node_alias.c_str());
    unlink(supervised_npm_alias.c_str());
    unlink(supervised_python_alias.c_str());
    unlink(supervised_python3_alias.c_str());
    unlink(supervised_toolchain_alias.c_str());
    rmdir(tool_binary.c_str());
    unlink((tool_runtime + "/npm/node_modules/npm/bin/npm-cli.js").c_str());
    rmdir((tool_runtime + "/npm/node_modules/npm/bin").c_str());
    rmdir((tool_runtime + "/npm/node_modules/npm").c_str());
    rmdir((tool_runtime + "/npm/node_modules").c_str());
    rmdir((tool_runtime + "/npm").c_str());
    unlink((tool_runtime
        + "/python/lib/python3.14/encodings/__init__.pyc").c_str());
    rmdir((tool_runtime + "/python/lib/python3.14/encodings").c_str());
    rmdir((tool_runtime + "/python/lib/python3.14").c_str());
    rmdir((tool_runtime + "/python/lib").c_str());
    rmdir((tool_runtime + "/python").c_str());
    rmdir(tool_runtime.c_str());
    rmdir(toolchain.c_str());
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
