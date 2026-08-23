#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

int assertions = 0;

void expect(bool condition, const char* message) {
  ++assertions;
  if (!condition) {
    std::cerr << "Assertion failed: " << message << '\n';
    std::exit(1);
  }
}

bool write_file(const std::string& path, const std::string& contents) {
  const int descriptor = open(
      path.c_str(),
      O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW,
      0600);
  if (descriptor < 0) {
    return false;
  }
  std::size_t written = 0U;
  while (written < contents.size()) {
    const ssize_t count = write(
        descriptor,
        contents.data() + written,
        contents.size() - written);
    if (count > 0) {
      written += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      close(descriptor);
      return false;
    }
  }
  return close(descriptor) == 0;
}

struct ChildResult {
  int exit_code = -1;
  std::string output;
};

ChildResult run_child(
    const std::string& executable,
    const std::vector<std::string>& supplied_arguments) {
  int output_pipe[2] {-1, -1};
  ChildResult result;
  if (pipe(output_pipe) != 0) {
    return result;
  }
  std::vector<std::string> argument_storage;
  argument_storage.reserve(supplied_arguments.size() + 1U);
  argument_storage.push_back(executable);
  argument_storage.insert(
      argument_storage.end(),
      supplied_arguments.begin(),
      supplied_arguments.end());
  std::vector<char*> arguments;
  for (std::string& value : argument_storage) {
    arguments.push_back(const_cast<char*>(value.c_str()));
  }
  arguments.push_back(nullptr);

  const pid_t child = fork();
  if (child == 0) {
    close(output_pipe[0]);
    if (dup2(output_pipe[1], STDOUT_FILENO) < 0
        || dup2(output_pipe[1], STDERR_FILENO) < 0) {
      _exit(125);
    }
    close(output_pipe[1]);
    execv(executable.c_str(), arguments.data());
    _exit(124);
  }
  close(output_pipe[1]);
  if (child < 0) {
    close(output_pipe[0]);
    return result;
  }
  char buffer[4096];
  for (;;) {
    const ssize_t count = read(output_pipe[0], buffer, sizeof(buffer));
    if (count > 0) {
      result.output.append(buffer, static_cast<std::size_t>(count));
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      break;
    }
  }
  close(output_pipe[0]);
  int status = 0;
  if (waitpid(child, &status, 0) == child) {
    if (WIFEXITED(status)) {
      result.exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
      result.exit_code = 128 + WTERMSIG(status);
    }
  }
  return result;
}

}  // namespace

int main(int argc, char* argv[]) {
  if (argc != 5) {
    std::cerr << "Expected guarded fixtures and a fake-guard directory\n";
    return 2;
  }
  char root_template[] = "/tmp/agentcodi-tool-guard-test-XXXXXX";
  char* created_root = mkdtemp(root_template);
  expect(created_root != nullptr, "create guard-test root");
  const std::string root = created_root;
  const std::string workspace = root + "/workspace";
  const std::string toolchain = workspace + "/toolchain";
  const std::string installed = toolchain + "/installed";
  const std::string runtime = root + "/tool-runtime";
  const std::string python = runtime + "/python";
  const std::string python_lib = python + "/lib";
  const std::string python_version = python_lib + "/python3.14";
  const std::string encodings = python_version + "/encodings";
  expect(mkdir(workspace.c_str(), 0700) == 0, "create guard workspace");
  expect(mkdir(toolchain.c_str(), 0700) == 0, "create guard toolchain");
  expect(mkdir(runtime.c_str(), 0700) == 0, "create guard runtime");
  expect(mkdir(python.c_str(), 0700) == 0, "create Python home");
  expect(mkdir(python_lib.c_str(), 0700) == 0, "create Python lib");
  expect(mkdir(python_version.c_str(), 0700) == 0, "create Python version");
  expect(mkdir(encodings.c_str(), 0700) == 0, "create Python encodings");
  expect(write_file(encodings + "/__init__.pyc", "fixture"),
         "create Python runtime landmark");
  expect(setenv("AGENTCODI_WORKSPACE", workspace.c_str(), 1) == 0,
         "set guard workspace");
  expect(setenv("AGENTCODI_TOOLCHAIN", toolchain.c_str(), 1) == 0,
         "set guard toolchain");
  expect(setenv("AGENTCODI_TOOL_RUNTIME", runtime.c_str(), 1) == 0,
         "set guard runtime");

  ChildResult result = run_child(argv[1], {});
  expect(result.exit_code == 127
             && result.output.find("not enabled") != std::string::npos,
         "direct Node ELF requires activation");
  result = run_child(argv[2], {});
  expect(result.exit_code == 127
             && result.output.find("not enabled") != std::string::npos,
         "direct Python ELF requires activation");
  result = run_child(argv[3], {"--version"});
  expect(result.exit_code == 127
             && result.output.find("not enabled") != std::string::npos,
         "direct ripgrep ELF requires activation");

  expect(mkdir(installed.c_str(), 0700) == 0, "create activation directory");
  expect(write_file(installed + "/node-24.18.0", "enabled 24.18.0\n"),
         "activate direct Node fixture");
  expect(write_file(installed + "/python-3.14.6", "enabled 3.14.6\n"),
         "activate direct Python fixture");
  expect(write_file(installed + "/ripgrep-15.2.0", "enabled 15.2.0\n"),
         "activate direct ripgrep fixture");

  result = run_child(argv[1], {});
  expect(result.exit_code == 0
             && result.output.find("guarded-tool-main-ran") != std::string::npos,
         "activated direct Node remains functional");
  result = run_child(argv[2], {"--verify-python-environment"});
  expect(result.exit_code == 0
             && result.output.find("python-environment-guarded")
                 != std::string::npos,
         "direct Python receives the shared hardened environment");
  expect(setenv("RIPGREP_CONFIG_PATH", "/private/untrusted", 1) == 0,
         "set direct ripgrep configuration fixture");
  result = run_child(argv[3], {"--verify-ripgrep-environment"});
  unsetenv("RIPGREP_CONFIG_PATH");
  expect(result.exit_code == 0
             && result.output.find("ripgrep-environment-guarded")
                 != std::string::npos,
         "direct ripgrep clears external configuration");
  result = run_child(argv[3], {"--pre=/system/bin/sh", "needle", "."});
  expect(result.exit_code == 2
             && result.output.find("--pre, --search-zip and --follow")
                 != std::string::npos
             && result.output.find("guarded-tool-main-ran") == std::string::npos,
         "direct ripgrep blocks subprocess-capable options before main");

  const char* inherited_library_path = std::getenv("LD_LIBRARY_PATH");
  const std::string trusted_library_path = inherited_library_path == nullptr
      ? ""
      : inherited_library_path;
  const std::string substituted_library_path = std::string(argv[4]) + ":"
      + trusted_library_path;
  expect(setenv(
             "LD_LIBRARY_PATH",
             substituted_library_path.c_str(),
             1) == 0,
         "substitute direct guard lookup path");
  result = run_child(argv[1], {});
  expect(result.exit_code == 126
             && result.output.find("untrusted policy library")
                 != std::string::npos
             && result.output.find("guarded-tool-main-ran")
                 == std::string::npos,
         "direct Node rejects a substituted policy library");
  result = run_child(argv[2], {"--verify-python-environment"});
  expect(result.exit_code == 126
             && result.output.find("untrusted policy library")
                 != std::string::npos,
         "direct Python rejects a substituted policy library");
  result = run_child(argv[3], {"--version"});
  expect(result.exit_code == 126
             && result.output.find("untrusted policy library")
                 != std::string::npos,
         "direct ripgrep rejects a substituted policy library");
  expect(setenv("LD_LIBRARY_PATH", trusted_library_path.c_str(), 1) == 0,
         "restore trusted direct guard lookup path");

  result = run_child(
      "/system/bin/linker64",
      {argv[3], "--pre=/system/bin/sh", "needle", "."});
  expect(result.exit_code == 126
             && result.output.find("non-canonical executable entry point")
                 != std::string::npos,
         "manual dynamic-linker invocation cannot bypass the ELF guard");

  expect(write_file(installed + "/node-24.18.0", "forged\n"),
         "corrupt direct Node marker");
  result = run_child(argv[1], {});
  expect(result.exit_code == 126
             && result.output.find("unsafe metadata") != std::string::npos,
         "direct Node rejects a forged activation marker");

  expect(unlink((installed + "/node-24.18.0").c_str()) == 0,
         "remove Node marker");
  expect(unlink((installed + "/python-3.14.6").c_str()) == 0,
         "remove Python marker");
  expect(unlink((installed + "/ripgrep-15.2.0").c_str()) == 0,
         "remove ripgrep marker");
  expect(unlink((encodings + "/__init__.pyc").c_str()) == 0,
         "remove Python landmark");
  expect(rmdir(installed.c_str()) == 0, "remove activation directory");
  expect(rmdir(encodings.c_str()) == 0, "remove encodings directory");
  expect(rmdir(python_version.c_str()) == 0, "remove Python version directory");
  expect(rmdir(python_lib.c_str()) == 0, "remove Python lib directory");
  expect(rmdir(python.c_str()) == 0, "remove Python home");
  expect(rmdir(runtime.c_str()) == 0, "remove tool runtime");
  expect(rmdir(toolchain.c_str()) == 0, "remove toolchain");
  expect(rmdir(workspace.c_str()) == 0, "remove workspace");
  expect(rmdir(root.c_str()) == 0, "remove guard-test root");

  std::cout << "toolchain ELF guard tests passed: " << assertions
            << " assertions\n";
  return 0;
}
