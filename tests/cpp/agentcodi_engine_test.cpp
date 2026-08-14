#include "agentcodi_engine.h"
#include "app_server_process.h"

#include <algorithm>
#include <climits>
#include <cstdlib>
#include <iostream>
#include <string>

#include <fcntl.h>
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

}  // namespace

int main(int argc, char* argv[]) {
  if (argc == 2 && std::string(argv[1]) == "--emit-oversized-image") {
    const std::string payload =
        "iVBORw0KGgoA" + std::string(1152U * 1024U, 'A');
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
  if (argc == 2 && std::string(argv[1]) == "--exit-with-code") {
    return 23;
  }

  const std::string version = agentcodi::engine_version();
  expect(version == "agentcodi-native/0.4.5", "engine version");
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
  expect(
      diagnostics.find("codeModeHost=android-sibling") != std::string::npos,
      "Android sibling code-mode host diagnostic");

  agentcodi::ProcessConfig argument_config;
  argument_config.working_directory = "/private/workspace";
  argument_config.home_directory = "/private/home";
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
    const std::string codex_home = root + "/codex-home";
    const std::string home = root + "/home";
    const std::string temporary = root + "/temporary";
    expect(mkdir(workspace.c_str(), 0700) == 0, "process-test workspace");
    expect(mkdir(codex_home.c_str(), 0700) == 0, "process-test Codex home");
    expect(mkdir(home.c_str(), 0700) == 0, "process-test home");
    expect(mkdir(temporary.c_str(), 0700) == 0, "process-test temporary directory");

    const std::string materialized_payload =
        "iVBORw0KGgoA" + std::string(40U * 1024U, 'A');
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

    const std::string missing_path_event =
        "{\"id\":\"missing_path_fixture\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\""
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/"
        "x8AAusB9WlK3SAAAAAASUVORK5CYII=\"}";
    std::string prepared_missing_path;
    error.clear();
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            missing_path_event,
            1024U * 1024U,
            workspace,
            temporary,
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
            &prepared_resume,
            &error) == agentcodi::InboundLineCompactionStatus::kCompacted
            && saved_path_from_event(prepared_resume) == materialized_path,
        "reuse materialized workspace image when history omits inline bytes");

    const std::string conflicting_image_event =
        "{\"id\":\"materialize/fixture\",\"type\":\"imageGeneration\","
        "\"status\":\"completed\",\"result\":\"iVBORw0KGgoA\","
        "\"savedPath\":null}";
    error.clear();
    struct stat preserved_metadata {};
    expect(
        agentcodi::MaterializeAndCompactInboundImagePayloads(
            conflicting_image_event,
            1024U * 1024U,
            workspace,
            temporary,
            &prepared_event,
            &error) == agentcodi::InboundLineCompactionStatus::kInvalid
            && error.find("conflicts") != std::string::npos
            && lstat(materialized_path.c_str(), &preserved_metadata) == 0
            && preserved_metadata.st_size == materialized_metadata.st_size,
        "never overwrite a materialized image when an item id conflicts");

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
            &prepared_event,
            &error) == agentcodi::InboundLineCompactionStatus::kInvalid
            && error.find("PNG signature") != std::string::npos,
        "reject non-PNG image payload before writing it");

    agentcodi::ProcessConfig config;
    config.executable = "/system/bin/sh";
    config.code_mode_host_executable = "/system/bin/sh";
    config.working_directory = workspace;
    config.codex_home = codex_home;
    config.home_directory = home;
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
          child_security_state.find("unset|" + codex_home + "|" + home + "|") == 0U,
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
        expect(unlink(child_materialized_path.c_str()) == 0,
               "remove child materialization fixture");
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
    expect(rmdir((workspace + "/generated_images").c_str()) == 0,
           "remove generated-image fixture directory");
    expect(rmdir(temporary.c_str()) == 0,
           "image materialization leaves private temporary directory empty");
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
