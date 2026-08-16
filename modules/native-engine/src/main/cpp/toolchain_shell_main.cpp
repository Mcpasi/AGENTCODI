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

namespace {

constexpr const char* kSystemShell = "/system/bin/sh";
constexpr const char* kPackagedShellName = "libagentcodi-shell.so";
constexpr const char* kPackagedNodeName = "libnode.so";
constexpr const char* kPackagedPythonName = "libpython-bin.so";
constexpr const char* kNpmCliRelativePath =
    "npm/node_modules/npm/bin/npm-cli.js";
constexpr const char* kPythonHomeRelativePath = "python";
constexpr const char* kPythonLandmarkRelativePath =
    "python/lib/python3.14/encodings/__init__.pyc";
constexpr std::size_t kMaximumCommandCharacters = 256U * 1024U;
constexpr off_t kMaximumRuntimeFileBytes = 16 * 1024 * 1024;

struct PackageSpec {
  const char* name;
  const char* display_name;
  const char* version;
  const char* marker;
};

constexpr PackageSpec kNodePackage {
    "node", "Node.js", "24.18.0", "node-24.18.0"};
constexpr PackageSpec kNpmPackage {
    "npm", "npm", "11.19.0", "npm-11.19.0"};
constexpr PackageSpec kPythonPackage {
    "python", "Python", "3.14.6", "python-3.14.6"};
constexpr const PackageSpec* kPackages[] = {
    &kNodePackage,
    &kNpmPackage,
    &kPythonPackage,
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

bool contains_path(const std::string& parent, const std::string& child) {
  return parent == child
      || (child.size() > parent.size()
          && child.compare(0U, parent.size(), parent) == 0
          && child[parent.size()] == '/');
}

bool canonical_private_directory(
    const char* supplied,
    std::string* canonical,
    std::string* error) {
  char resolved[PATH_MAX];
  if (realpath(supplied, resolved) == nullptr) {
    *error = errno_message("Toolchain directory", errno);
    return false;
  }
  struct stat metadata {};
  if (lstat(resolved, &metadata) != 0
      || !S_ISDIR(metadata.st_mode)
      || metadata.st_uid != geteuid()
      || (metadata.st_mode & 077) != 0) {
    *error = "Toolchain directory is not a private canonical directory";
    return false;
  }
  *canonical = resolved;
  return true;
}

bool toolchain_directories(
    std::string* workspace,
    std::string* toolchain,
    std::string* error) {
  const char* supplied_workspace = required_environment("AGENTCODI_WORKSPACE");
  const char* supplied_toolchain = required_environment("AGENTCODI_TOOLCHAIN");
  if (supplied_workspace == nullptr || supplied_toolchain == nullptr) {
    *error = "Toolchain environment is missing";
    return false;
  }
  if (!canonical_private_directory(supplied_workspace, workspace, error)
      || !canonical_private_directory(supplied_toolchain, toolchain, error)) {
    return false;
  }
  if (*workspace == *toolchain || !contains_path(*workspace, *toolchain)) {
    *error = "Toolchain directory escaped the canonical workspace";
    return false;
  }
  return true;
}

bool canonical_tool_runtime(std::string* runtime, std::string* error) {
  const char* supplied_runtime = required_environment("AGENTCODI_TOOL_RUNTIME");
  std::string workspace;
  std::string toolchain;
  if (supplied_runtime == nullptr
      || !canonical_private_directory(supplied_runtime, runtime, error)
      || !toolchain_directories(&workspace, &toolchain, error)) {
    return false;
  }
  if (contains_path(workspace, *runtime)
      || contains_path(*runtime, workspace)
      || contains_path(toolchain, *runtime)
      || contains_path(*runtime, toolchain)) {
    *error = "Packaged tool runtime must remain separate from the workspace";
    return false;
  }
  return true;
}

int open_installed_directory(bool create, std::string* error) {
  std::string workspace;
  std::string toolchain;
  if (!toolchain_directories(&workspace, &toolchain, error)) {
    return -1;
  }
  const int toolchain_descriptor = open(
      toolchain.c_str(),
      O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (toolchain_descriptor < 0) {
    *error = errno_message("Toolchain open", errno);
    return -1;
  }
  if (create && mkdirat(toolchain_descriptor, "installed", 0700) != 0
      && errno != EEXIST) {
    const int saved_errno = errno;
    close(toolchain_descriptor);
    *error = errno_message("Toolchain activation directory", saved_errno);
    return -1;
  }
  const int installed = openat(
      toolchain_descriptor,
      "installed",
      O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  const int saved_errno = errno;
  close(toolchain_descriptor);
  if (installed < 0) {
    if (!create && saved_errno == ENOENT) {
      return -1;
    }
    *error = errno_message("Toolchain activation directory", saved_errno);
    return -1;
  }
  struct stat metadata {};
  if (fstat(installed, &metadata) != 0
      || !S_ISDIR(metadata.st_mode)
      || metadata.st_uid != geteuid()
      || (metadata.st_mode & 077) != 0) {
    close(installed);
    *error = "Toolchain activation directory has unsafe metadata";
    return -1;
  }
  return installed;
}

bool valid_marker(
    int directory,
    const PackageSpec& package,
    std::string* error) {
  const int marker = openat(
      directory,
      package.marker,
      O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
  if (marker < 0) {
    if (errno != ENOENT && error != nullptr) {
      *error = errno_message(
          (std::string(package.display_name) + " activation marker").c_str(),
          errno);
    }
    return false;
  }
  struct stat metadata {};
  const std::string expected = std::string("enabled ") + package.version + "\n";
  std::string contents(expected.size(), '\0');
  std::size_t read_bytes = 0U;
  while (read_bytes < contents.size()) {
    const ssize_t count = read(
        marker,
        &contents[read_bytes],
        contents.size() - read_bytes);
    if (count > 0) {
      read_bytes += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      break;
    }
  }
  char trailing = '\0';
  const ssize_t trailing_bytes = read(marker, &trailing, 1U);
  const bool valid = fstat(marker, &metadata) == 0
      && S_ISREG(metadata.st_mode)
      && metadata.st_uid == geteuid()
      && metadata.st_nlink == 1
      && (metadata.st_mode & 0777) == 0600
      && metadata.st_size == static_cast<off_t>(expected.size())
      && read_bytes == expected.size()
      && trailing_bytes == 0
      && contents == expected;
  close(marker);
  if (!valid && error != nullptr) {
    *error = std::string(package.display_name)
        + " activation marker has unsafe metadata";
  }
  return valid;
}

std::string executable_name(const char* value) {
  if (value == nullptr) {
    return "";
  }
  const char* slash = std::strrchr(value, '/');
  return slash == nullptr ? std::string(value) : std::string(slash + 1);
}

bool canonical_packaged_bridge(std::string* bridge, std::string* error) {
  char resolved[PATH_MAX];
  struct stat metadata {};
  if (realpath("/proc/self/exe", resolved) == nullptr
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
      || !contains_path(runtime, resolved)) {
    *error = "Packaged tool runtime file failed canonical validation";
    return false;
  }
  *file = resolved;
  return true;
}

bool canonical_python_home(std::string* home, std::string* error) {
  std::string runtime;
  if (!canonical_tool_runtime(&runtime, error)) {
    return false;
  }
  const std::string candidate = runtime + "/" + kPythonHomeRelativePath;
  char resolved[PATH_MAX];
  if (realpath(candidate.c_str(), resolved) == nullptr
      || !contains_path(runtime, resolved)
      || !canonical_private_directory(resolved, home, error)) {
    *error = "Packaged Python home failed canonical validation";
    return false;
  }
  std::string landmark;
  if (!canonical_runtime_file(kPythonLandmarkRelativePath, &landmark, error)) {
    *error = "Packaged Python standard library failed canonical validation";
    return false;
  }
  return true;
}

std::string shell_quote(const std::string& value) {
  std::string quoted("'");
  for (char character : value) {
    if (character == '\'') {
      quoted.append("'\\''");
    } else {
      quoted.push_back(character);
    }
  }
  quoted.push_back('\'');
  return quoted;
}

bool package_enabled(const PackageSpec& package, std::string* error) {
  const int directory = open_installed_directory(false, error);
  if (directory < 0) {
    return false;
  }
  const bool enabled = valid_marker(directory, package, error);
  close(directory);
  return enabled;
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
  const int directory = open_installed_directory(true, &error);
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
    if (valid_marker(directory, package, &error)) {
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
  const int directory = open_installed_directory(false, &error);
  if (directory < 0) {
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    std::cout << package.display_name << " is not enabled.\n";
    return 0;
  }
  if (!valid_marker(directory, package, &error)) {
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
      << "[list|install <node|npm|python>|remove <node|npm|python>]\n";
  return 2;
}

std::string shell_functions(const std::string& bridge) {
  const std::string command = shell_quote(bridge);
  return
      "agentcodi-toolchain() { " + command + " --toolchain \"$@\"; }; "
      "node() { " + command + " --node \"$@\"; }; "
      "npm() { " + command + " --npm \"$@\"; }; "
      "python() { " + command + " --python \"$@\"; }; "
      "python3() { " + command + " --python \"$@\"; }; ";
}

int run_shell_command(const char* command) {
  if (command == nullptr || std::strlen(command) > kMaximumCommandCharacters) {
    std::cerr << "Shell command exceeds the AGENTCODI limit\n";
    return 2;
  }
  std::string bridge;
  std::string error;
  if (!canonical_packaged_bridge(&bridge, &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  const std::string prepared = shell_functions(bridge) + command;
  char* const arguments[] = {
      const_cast<char*>(kSystemShell),
      const_cast<char*>("-c"),
      const_cast<char*>(prepared.c_str()),
      nullptr,
  };
  execv(kSystemShell, arguments);
  std::cerr << errno_message("System shell", errno) << '\n';
  return 127;
}

int run_interactive_shell() {
  std::string bridge;
  std::string error;
  if (!canonical_packaged_bridge(&bridge, &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  int initialization[2] {-1, -1};
  if (pipe2(initialization, O_CLOEXEC) != 0) {
    std::cerr << errno_message("Terminal initialization", errno) << '\n';
    return 127;
  }
  const std::string functions = shell_functions(bridge) + "\n";
  if (!write_all(initialization[1], functions.data(), functions.size())) {
    const int saved_errno = errno;
    close(initialization[0]);
    close(initialization[1]);
    std::cerr << errno_message("Terminal initialization", saved_errno) << '\n';
    return 127;
  }
  close(initialization[1]);
  const int descriptor_flags = fcntl(initialization[0], F_GETFD);
  if (descriptor_flags < 0
      || fcntl(initialization[0], F_SETFD, descriptor_flags & ~FD_CLOEXEC) != 0) {
    const int saved_errno = errno;
    close(initialization[0]);
    std::cerr << errno_message(
        "Terminal initialization descriptor",
        saved_errno) << '\n';
    return 127;
  }
  const std::string environment_path =
      "/proc/self/fd/" + std::to_string(initialization[0]);
  if (setenv("ENV", environment_path.c_str(), 1) != 0) {
    const int saved_errno = errno;
    close(initialization[0]);
    std::cerr << errno_message("Terminal shell environment", saved_errno) << '\n';
    return 127;
  }
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

int run_node(int argc, char* argv[]) {
  std::string error;
  if (!require_enabled(kNodePackage, &error)) {
    return error.empty() ? 127 : 126;
  }
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
      || !toolchain_directories(&workspace, &toolchain, &error)) {
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
  std::string error;
  if (!require_enabled(kPythonPackage, &error)) {
    return error.empty() ? 127 : 126;
  }
  std::string python_path;
  std::string python_home;
  if (!canonical_packaged_python(&python_path, &error)
      || !canonical_python_home(&python_home, &error)) {
    std::cerr << error << '\n';
    return 126;
  }
  if (!set_environment("PYTHONHOME", python_home)
      || !set_environment("PYTHONNOUSERSITE", "1")
      || !set_environment("PYTHONDONTWRITEBYTECODE", "1")
      || !set_environment("PYTHONSAFEPATH", "1")
      || !set_environment("PYTHON_HISTORY", "/dev/null")
      || !set_environment("PYTHONUTF8", "1")) {
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
  if (argc == 3 && std::string(argv[1]) == "-c") {
    return run_shell_command(argv[2]);
  }
  std::cerr
      << "Usage: libagentcodi-shell.so "
      << "[--interactive|-c command|--toolchain ...|--node ...|--npm ...|--python ...]\n";
  return 2;
}
