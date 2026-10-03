#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "toolchain_policy.h"

namespace {

constexpr const char* kSystemShell = "/system/bin/sh";
constexpr const char* kPackagedShellName = "libagentcodi-shell.so";
constexpr const char* kPackagedNodeName = "libnode.so";
constexpr const char* kPackagedPythonName = "libpython-bin.so";
constexpr const char* kPackagedRipgrepName = "libripgrep.so";
constexpr const char* kNpmCliRelativePath =
    "npm/node_modules/npm/bin/npm-cli.js";
constexpr std::size_t kMaximumCommandCharacters = 256U * 1024U;
constexpr off_t kMaximumRuntimeFileBytes = 16 * 1024 * 1024;

using agentcodi::PackageSpec;
using agentcodi::kNodePackage;
using agentcodi::kNpmPackage;
using agentcodi::kPythonPackage;
using agentcodi::kRipgrepPackage;

const PackageSpec* const kPackages[] = {
    &kNodePackage,
    &kNpmPackage,
    &kPythonPackage,
    &kRipgrepPackage,
};

std::string errno_message(const char* operation, int error_number) {
  return std::string(operation) + ": " + std::strerror(error_number);
}

const char* required_environment(const char* name) {
  const char* value = std::getenv(name);
  if (value == nullptr || value[0] != '/') {
    std::cerr << "AGENTCODI shell configuration is incomplete\n";
    return nullptr;
  }
  for (const char* cursor = value; *cursor != '\0'; ++cursor) {
    if (*cursor == '\n' || *cursor == '\r') {
      std::cerr << "AGENTCODI shell configuration is invalid\n";
      return nullptr;
    }
  }
  return value;
}

bool canonical_tool_runtime(std::string* runtime, std::string* error) {
  const char* supplied_runtime = required_environment("AGENTCODI_TOOL_RUNTIME");
  std::string workspace;
  std::string toolchain;
  if (supplied_runtime == nullptr
      || !agentcodi::CanonicalPrivateToolDirectory(
          supplied_runtime,
          runtime,
          error)
      || !agentcodi::ResolveToolchainDirectories(
          &workspace,
          &toolchain,
          error)) {
    return false;
  }
  if (agentcodi::ContainsPath(workspace, *runtime)
      || agentcodi::ContainsPath(*runtime, workspace)
      || agentcodi::ContainsPath(toolchain, *runtime)
      || agentcodi::ContainsPath(*runtime, toolchain)) {
    *error = "Packaged tool runtime must remain separate from the workspace";
    return false;
  }
  return true;
}

std::string executable_name(const char* value) {
  if (value == nullptr) {
    return "";
  }
  const char* slash = std::strrchr(value, '/');
  return slash == nullptr ? std::string(value) : std::string(slash + 1);
}

bool canonical_packaged_bridge(std::string* bridge, std::string* error) {
  // Read the kernel's executable link before canonicalizing its target.
  // Bionic realpath("/proc/self/exe") also probes /proc's metadata, which
  // a read-narrowed sandbox intentionally does not grant to the command.
  char executable[PATH_MAX];
  const ssize_t length = readlink(
      "/proc/self/exe", executable, sizeof(executable) - 1U);
  if (length <= 0
      || static_cast<std::size_t>(length) >= sizeof(executable) - 1U) {
    *error = "Packaged shell bridge failed canonical self-validation";
    return false;
  }
  executable[length] = '\0';
  char resolved[PATH_MAX];
  struct stat metadata {};
  if (realpath(executable, resolved) == nullptr
      || lstat(resolved, &metadata) != 0
      || !S_ISREG(metadata.st_mode)
      || metadata.st_nlink != 1
      || access(resolved, X_OK) != 0
      || executable_name(resolved) != kPackagedShellName) {
    *error = "Packaged shell bridge failed canonical self-validation";
    return false;
  }
  *bridge = resolved;
  return true;
}

bool canonical_packaged_sibling(
    const char* packaged_name,
    const char* label,
    std::string* executable,
    std::string* error) {
  std::string bridge;
  if (!canonical_packaged_bridge(&bridge, error)) {
    return false;
  }
  const std::size_t separator = bridge.rfind('/');
  if (separator == std::string::npos) {
    *error = "Packaged shell bridge has no canonical directory";
    return false;
  }
  const std::string candidate = bridge.substr(0U, separator + 1U) + packaged_name;
  char resolved[PATH_MAX];
  struct stat metadata {};
  if (realpath(candidate.c_str(), resolved) == nullptr
      || lstat(resolved, &metadata) != 0
      || !S_ISREG(metadata.st_mode)
      || metadata.st_nlink != 1
      || access(resolved, X_OK) != 0
      || executable_name(resolved) != packaged_name) {
    *error = std::string("Packaged ") + label
        + " sibling failed canonical validation";
    return false;
  }
  *executable = resolved;
  return true;
}

bool canonical_packaged_node(std::string* node, std::string* error) {
  return canonical_packaged_sibling(
      kPackagedNodeName,
      "Node.js",
      node,
      error);
}

bool canonical_packaged_python(std::string* python, std::string* error) {
  return canonical_packaged_sibling(
      kPackagedPythonName,
      "Python",
      python,
      error);
}

bool canonical_packaged_ripgrep(std::string* ripgrep, std::string* error) {
  return canonical_packaged_sibling(
      kPackagedRipgrepName,
      "ripgrep",
      ripgrep,
      error);
}

bool canonical_runtime_file(
    const std::string& relative_path,
    std::string* file,
    std::string* error) {
  std::string runtime;
  if (!canonical_tool_runtime(&runtime, error)) {
    return false;
  }
  const std::string candidate = runtime + "/" + relative_path;
  char resolved[PATH_MAX];
  struct stat metadata {};
  if (realpath(candidate.c_str(), resolved) == nullptr
      || lstat(resolved, &metadata) != 0
      || !S_ISREG(metadata.st_mode)
      || metadata.st_uid != geteuid()
      || metadata.st_nlink != 1
      || (metadata.st_mode & 077) != 0
      || metadata.st_size < 1
      || metadata.st_size > kMaximumRuntimeFileBytes
      || !agentcodi::ContainsPath(runtime, resolved)) {
    *error = "Packaged tool runtime file failed canonical validation";
    return false;
  }
  *file = resolved;
  return true;
}

bool package_enabled(const PackageSpec& package, std::string* error) {
  return agentcodi::IsToolPackageEnabled(package, error);
}

bool write_all(int descriptor, const char* data, std::size_t length) {
  std::size_t written = 0U;
  while (written < length) {
    const ssize_t count = write(descriptor, data + written, length - written);
    if (count > 0) {
      written += static_cast<std::size_t>(count);
    } else if (count == 0) {
      errno = EIO;
      return false;
    } else if (errno != EINTR) {
      return false;
    }
  }
  return true;
}

bool remove_replaceable_marker(
    int directory,
    const PackageSpec& package,
    std::string* error) {
  struct stat metadata {};
  if (fstatat(directory, package.marker, &metadata, AT_SYMLINK_NOFOLLOW) != 0) {
    if (errno == ENOENT) {
      error->clear();
      return true;
    }
    *error = errno_message(
        (std::string(package.display_name)
            + " activation marker inspection").c_str(),
        errno);
    return false;
  }
  if (S_ISDIR(metadata.st_mode)) {
    *error = std::string(package.display_name)
        + " activation marker is an unexpected directory";
    return false;
  }
  if (unlinkat(directory, package.marker, 0) != 0) {
    *error = errno_message(
        (std::string("Invalid ") + package.display_name
            + " activation marker removal").c_str(),
        errno);
    return false;
  }
  error->clear();
  return true;
}

int install_single_package(const PackageSpec& package) {
  std::string error;
  const int directory = agentcodi::OpenToolActivationDirectory(true, &error);
  if (directory < 0) {
    std::cerr << error << '\n';
    return 1;
  }
  int marker = openat(
      directory,
      package.marker,
      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
      0600);
  if (marker < 0 && errno == EEXIST) {
    if (agentcodi::ValidateToolActivationMarker(directory, package, &error)) {
      close(directory);
      std::cout << package.display_name << ' ' << package.version
                << " is already enabled.\n";
      return 0;
    }
    if (!remove_replaceable_marker(directory, package, &error)) {
      close(directory);
      std::cerr << error << '\n';
      return 1;
    }
    marker = openat(
        directory,
        package.marker,
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        0600);
  }
  if (marker < 0) {
    const int saved_errno = errno;
    close(directory);
    std::cerr << errno_message(
        (std::string(package.display_name) + " activation").c_str(),
        saved_errno) << '\n';
    return 1;
  }
  const std::string contents = std::string("enabled ") + package.version + "\n";
  bool written = write_all(marker, contents.data(), contents.size())
      && fsync(marker) == 0;
  int saved_errno = written ? 0 : errno;
  close(marker);
  if (written && fsync(directory) != 0) {
    written = false;
    saved_errno = errno;
  }
  if (!written) {
    unlinkat(directory, package.marker, 0);
    fsync(directory);
    close(directory);
    std::cerr << errno_message(
        (std::string(package.display_name) + " activation").c_str(),
        saved_errno) << '\n';
    return 1;
  }
  close(directory);
  std::cout << "Enabled packaged " << package.display_name << ' '
            << package.version << ".\n";
  return 0;
}

int install_package(const PackageSpec& package) {
  if (&package == &kNpmPackage) {
    const int node_result = install_single_package(kNodePackage);
    if (node_result != 0) {
      return node_result;
    }
  }
  return install_single_package(package);
}

int remove_single_package(const PackageSpec& package) {
  std::string error;
  const int directory = agentcodi::OpenToolActivationDirectory(false, &error);
  if (directory < 0) {
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    std::cout << package.display_name << " is not enabled.\n";
    return 0;
  }
  if (!agentcodi::ValidateToolActivationMarker(directory, package, &error)) {
    close(directory);
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    std::cout << package.display_name << " is not enabled.\n";
    return 0;
  }
  if (unlinkat(directory, package.marker, 0) != 0) {
    const int saved_errno = errno;
    close(directory);
    std::cerr << errno_message(
        (std::string(package.display_name) + " deactivation").c_str(),
        saved_errno) << '\n';
    return 1;
  }
  if (fsync(directory) != 0) {
    const int saved_errno = errno;
    close(directory);
    std::cerr << errno_message(
        (std::string(package.display_name) + " deactivation").c_str(),
        saved_errno) << '\n';
    return 1;
  }
  close(directory);
  std::cout << "Disabled " << package.display_name << ' '
            << package.version << ".\n";
  return 0;
}

int remove_package(const PackageSpec& package) {
  if (&package == &kNodePackage) {
    std::string error;
    const bool npm_enabled = package_enabled(kNpmPackage, &error);
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    if (npm_enabled) {
      const int npm_result = remove_single_package(kNpmPackage);
      if (npm_result != 0) {
        return npm_result;
      }
    }
  }
  return remove_single_package(package);
}

const PackageSpec* find_package(const std::string& name) {
  for (const PackageSpec* package : kPackages) {
    if (name == package->name) {
      return package;
    }
  }
  return nullptr;
}

int toolchain_command(int argc, char* argv[]) {
  if (argc == 0 || std::string(argv[0]) == "list"
      || std::string(argv[0]) == "status") {
    for (const PackageSpec* package : kPackages) {
      std::string error;
      const bool enabled = package_enabled(*package, &error);
      if (!error.empty()) {
        std::cerr << error << '\n';
        return 1;
      }
      std::cout << package->name << ' ' << package->version << " — "
                << (enabled ? "enabled" : "available, not enabled") << '\n';
    }
    return 0;
  }
  if (argc == 2) {
    const PackageSpec* package = find_package(argv[1]);
    if (package != nullptr && std::string(argv[0]) == "install") {
      return install_package(*package);
    }
    if (package != nullptr && (std::string(argv[0]) == "remove"
                               || std::string(argv[0]) == "uninstall")) {
      return remove_package(*package);
    }
  }
  std::cerr
      << "Usage: agentcodi-toolchain "
      << "[list|install <node|npm|python|ripgrep>|"
      << "remove <node|npm|python|ripgrep>]\n";
  return 2;
}

int run_shell_command(const char* command) {
  if (command == nullptr || std::strlen(command) > kMaximumCommandCharacters) {
    std::cerr << "Shell command exceeds the AGENTCODI limit\n";
    return 2;
  }
  // Resolve commands through PATH so installations in PREFIX/bin take priority.
  char* const arguments[] = {
      const_cast<char*>(kSystemShell),
      const_cast<char*>("-c"),
      const_cast<char*>(command),
      nullptr,
  };
  execv(kSystemShell, arguments);
  std::cerr << errno_message("System shell", errno) << '\n';
  return 127;
}

int run_interactive_shell() {
  char* const arguments[] = {
      const_cast<char*>(kSystemShell),
      const_cast<char*>("-i"),
      nullptr,
  };
  execv(kSystemShell, arguments);
  std::cerr << errno_message("System shell", errno) << '\n';
  return 127;
}

bool require_enabled(const PackageSpec& package, std::string* error) {
  if (package_enabled(package, error)) {
    return true;
  }
  if (error->empty()) {
    std::cerr
        << package.display_name << ' ' << package.version
        << " is available but not enabled. Ask the user for permission, then run: "
        << "agentcodi-toolchain install " << package.name << '\n';
  } else {
    std::cerr << *error << '\n';
  }
  return false;
}

bool prepare_guarded_invocation(
    agentcodi::GuardedTool tool,
    int argc,
    char* argv[],
    int* exit_code) {
  std::vector<std::string> supplied_arguments;
  supplied_arguments.reserve(static_cast<std::size_t>(argc));
  for (int index = 0; index < argc; ++index) {
    supplied_arguments.emplace_back(argv[index]);
  }
  std::string error;
  if (agentcodi::PrepareGuardedToolInvocation(
          tool,
          supplied_arguments,
          &error,
          exit_code)) {
    return true;
  }
  if (!error.empty()) {
    std::cerr << error << '\n';
  }
  return false;
}

int run_node(int argc, char* argv[]) {
  int exit_code = 126;
  if (!prepare_guarded_invocation(
          agentcodi::GuardedTool::kNode,
          argc,
          argv,
          &exit_code)) {
    return exit_code;
  }
  std::string error;
  std::string node_path;
  if (!canonical_packaged_node(&node_path, &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  std::vector<char*> arguments;
  arguments.reserve(static_cast<std::size_t>(argc) + 2U);
  arguments.push_back(const_cast<char*>("node"));
  for (int index = 0; index < argc; ++index) {
    arguments.push_back(argv[index]);
  }
  arguments.push_back(nullptr);
  execv(node_path.c_str(), arguments.data());
  std::cerr << errno_message("Packaged Node.js", errno) << '\n';
  return 126;
}

bool set_environment(const char* name, const std::string& value) {
  if (setenv(name, value.c_str(), 1) == 0) {
    return true;
  }
  std::cerr << errno_message("Packaged tool environment", errno) << '\n';
  return false;
}

int run_npm(int argc, char* argv[]) {
  std::string error;
  if (!require_enabled(kNpmPackage, &error)) {
    return error.empty() ? 127 : 126;
  }
  if (!require_enabled(kNodePackage, &error)) {
    return error.empty() ? 127 : 126;
  }
  std::string node_path;
  std::string npm_cli;
  std::string workspace;
  std::string toolchain;
  if (!canonical_packaged_node(&node_path, &error)
      || !canonical_runtime_file(kNpmCliRelativePath, &npm_cli, &error)
      || !agentcodi::ResolveToolchainDirectories(
          &workspace,
          &toolchain,
          &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  if (!set_environment("NPM_CONFIG_CACHE", toolchain + "/npm-cache")
      || !set_environment("NPM_CONFIG_PREFIX", toolchain + "/npm-prefix")
      || !set_environment("NPM_CONFIG_USERCONFIG", "/dev/null")
      || !set_environment("NPM_CONFIG_GLOBALCONFIG", "/system/etc/npmrc")
      || !set_environment("NPM_CONFIG_UPDATE_NOTIFIER", "false")
      || !set_environment("NPM_CONFIG_FUND", "false")
      || !set_environment("NPM_CONFIG_AUDIT", "false")
      || !set_environment("NPM_CONFIG_LOGS_MAX", "0")) {
    return 126;
  }
  std::vector<char*> arguments;
  arguments.reserve(static_cast<std::size_t>(argc) + 3U);
  arguments.push_back(const_cast<char*>("node"));
  arguments.push_back(const_cast<char*>(npm_cli.c_str()));
  for (int index = 0; index < argc; ++index) {
    arguments.push_back(argv[index]);
  }
  arguments.push_back(nullptr);
  execv(node_path.c_str(), arguments.data());
  std::cerr << errno_message("Packaged npm", errno) << '\n';
  return 126;
}

int run_python(int argc, char* argv[]) {
  int exit_code = 126;
  if (!prepare_guarded_invocation(
          agentcodi::GuardedTool::kPython,
          argc,
          argv,
          &exit_code)) {
    return exit_code;
  }
  std::string error;
  std::string python_path;
  if (!canonical_packaged_python(&python_path, &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  std::vector<char*> arguments;
  arguments.reserve(static_cast<std::size_t>(argc) + 2U);
  arguments.push_back(const_cast<char*>("python"));
  for (int index = 0; index < argc; ++index) {
    arguments.push_back(argv[index]);
  }
  arguments.push_back(nullptr);
  execv(python_path.c_str(), arguments.data());
  std::cerr << errno_message("Packaged Python", errno) << '\n';
  return 126;
}

int run_ripgrep(int argc, char* argv[]) {
  int exit_code = 126;
  if (!prepare_guarded_invocation(
          agentcodi::GuardedTool::kRipgrep,
          argc,
          argv,
          &exit_code)) {
    return exit_code;
  }
  std::string error;
  std::string ripgrep_path;
  if (!canonical_packaged_ripgrep(&ripgrep_path, &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  std::vector<char*> arguments;
  arguments.reserve(static_cast<std::size_t>(argc) + 2U);
  arguments.push_back(const_cast<char*>("rg"));
  for (int index = 0; index < argc; ++index) {
    arguments.push_back(argv[index]);
  }
  arguments.push_back(nullptr);
  execv(ripgrep_path.c_str(), arguments.data());
  std::cerr << errno_message("Packaged ripgrep", errno) << '\n';
  return 126;
}

}  // namespace

int main(int argc, char* argv[]) {
  umask(0077);
  const std::string invoked_as = argc > 0 ? executable_name(argv[0]) : "";
  if (invoked_as == "node") {
    return run_node(argc - 1, argv + 1);
  }
  if (invoked_as == "npm") {
    return run_npm(argc - 1, argv + 1);
  }
  if (invoked_as == "python" || invoked_as == "python3") {
    return run_python(argc - 1, argv + 1);
  }
  if (invoked_as == "rg") {
    return run_ripgrep(argc - 1, argv + 1);
  }
  if (invoked_as == "agentcodi-toolchain") {
    return toolchain_command(argc - 1, argv + 1);
  }
  if (argc >= 2 && std::string(argv[1]) == "--interactive") {
    return run_interactive_shell();
  }
  if (argc >= 2 && std::string(argv[1]) == "--toolchain") {
    return toolchain_command(argc - 2, argv + 2);
  }
  if (argc >= 2 && std::string(argv[1]) == "--node") {
    return run_node(argc - 2, argv + 2);
  }
  if (argc >= 2 && std::string(argv[1]) == "--npm") {
    return run_npm(argc - 2, argv + 2);
  }
  if (argc >= 2 && std::string(argv[1]) == "--python") {
    return run_python(argc - 2, argv + 2);
  }
  if (argc >= 2 && std::string(argv[1]) == "--ripgrep") {
    return run_ripgrep(argc - 2, argv + 2);
  }
  if (argc == 3 && std::string(argv[1]) == "-c") {
    return run_shell_command(argv[2]);
  }
  std::cerr
      << "Usage: libagentcodi-shell.so "
      << "[--interactive|-c command|--toolchain ...|--node ...|--npm ...|"
      << "--python ...|--ripgrep ...]\n";
  return 2;
}
