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
constexpr const char* kNodeVersion = "24.18.0";
constexpr const char* kNodeMarker = "node-24.18.0";
constexpr std::size_t kMaximumCommandCharacters = 256U * 1024U;

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

bool valid_marker(int directory, std::string* error) {
  const int marker = openat(
      directory,
      kNodeMarker,
      O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
  if (marker < 0) {
    if (errno != ENOENT && error != nullptr) {
      *error = errno_message("Node.js activation marker", errno);
    }
    return false;
  }
  struct stat metadata {};
  const std::string expected = std::string("enabled ") + kNodeVersion + "\n";
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
    *error = "Node.js activation marker has unsafe metadata";
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

bool canonical_packaged_node(std::string* node, std::string* error) {
  std::string bridge;
  if (!canonical_packaged_bridge(&bridge, error)) {
    return false;
  }
  const std::size_t separator = bridge.rfind('/');
  if (separator == std::string::npos) {
    *error = "Packaged shell bridge has no canonical directory";
    return false;
  }
  const std::string candidate =
      bridge.substr(0U, separator + 1U) + kPackagedNodeName;
  char resolved[PATH_MAX];
  struct stat metadata {};
  if (realpath(candidate.c_str(), resolved) == nullptr
      || lstat(resolved, &metadata) != 0
      || !S_ISREG(metadata.st_mode)
      || metadata.st_nlink != 1
      || access(resolved, X_OK) != 0) {
    *error = "Packaged Node.js sibling failed canonical validation";
    return false;
  }
  *node = resolved;
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

bool node_enabled(std::string* error) {
  const int directory = open_installed_directory(false, error);
  if (directory < 0) {
    return false;
  }
  const bool enabled = valid_marker(directory, error);
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

bool remove_replaceable_marker(int directory, std::string* error) {
  struct stat metadata {};
  if (fstatat(
          directory,
          kNodeMarker,
          &metadata,
          AT_SYMLINK_NOFOLLOW) != 0) {
    if (errno == ENOENT) {
      error->clear();
      return true;
    }
    *error = errno_message("Node.js activation marker inspection", errno);
    return false;
  }
  if (S_ISDIR(metadata.st_mode)) {
    *error = "Node.js activation marker is an unexpected directory";
    return false;
  }
  if (unlinkat(directory, kNodeMarker, 0) != 0) {
    *error = errno_message("Invalid Node.js activation marker removal", errno);
    return false;
  }
  error->clear();
  return true;
}

int install_node() {
  std::string error;
  const int directory = open_installed_directory(true, &error);
  if (directory < 0) {
    std::cerr << error << '\n';
    return 1;
  }
  int marker = openat(
      directory,
      kNodeMarker,
      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
      0600);
  if (marker < 0 && errno == EEXIST) {
    if (valid_marker(directory, &error)) {
      close(directory);
      std::cout << "Node.js " << kNodeVersion << " is already enabled.\n";
      return 0;
    }
    if (!remove_replaceable_marker(directory, &error)) {
      close(directory);
      std::cerr << error << '\n';
      return 1;
    }
    marker = openat(
        directory,
        kNodeMarker,
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        0600);
  }
  if (marker < 0) {
    const int saved_errno = errno;
    close(directory);
    std::cerr << errno_message("Node.js activation", saved_errno) << '\n';
    return 1;
  }
  const std::string contents = std::string("enabled ") + kNodeVersion + "\n";
  bool written = write_all(marker, contents.data(), contents.size())
      && fsync(marker) == 0;
  int saved_errno = written ? 0 : errno;
  close(marker);
  if (written && fsync(directory) != 0) {
    written = false;
    saved_errno = errno;
  }
  if (!written) {
    unlinkat(directory, kNodeMarker, 0);
    fsync(directory);
    close(directory);
    std::cerr << errno_message("Node.js activation", saved_errno) << '\n';
    return 1;
  }
  close(directory);
  std::cout << "Enabled packaged Node.js " << kNodeVersion << ".\n";
  return 0;
}

int remove_node() {
  std::string error;
  const int directory = open_installed_directory(false, &error);
  if (directory < 0) {
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    std::cout << "Node.js is not enabled.\n";
    return 0;
  }
  if (!valid_marker(directory, &error)) {
    close(directory);
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    std::cout << "Node.js is not enabled.\n";
    return 0;
  }
  if (unlinkat(directory, kNodeMarker, 0) != 0) {
    const int saved_errno = errno;
    close(directory);
    std::cerr << errno_message("Node.js deactivation", saved_errno) << '\n';
    return 1;
  }
  if (fsync(directory) != 0) {
    const int saved_errno = errno;
    close(directory);
    std::cerr << errno_message("Node.js deactivation", saved_errno) << '\n';
    return 1;
  }
  close(directory);
  std::cout << "Disabled Node.js " << kNodeVersion << ".\n";
  return 0;
}

int toolchain_command(int argc, char* argv[]) {
  if (argc == 0 || std::string(argv[0]) == "list"
      || std::string(argv[0]) == "status") {
    std::string error;
    const bool enabled = node_enabled(&error);
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 1;
    }
    std::cout << "node " << kNodeVersion << " — "
              << (enabled ? "enabled" : "available, not enabled") << '\n';
    return 0;
  }
  if (argc == 2 && std::string(argv[0]) == "install"
      && std::string(argv[1]) == "node") {
    return install_node();
  }
  if (argc == 2 && (std::string(argv[0]) == "remove"
                    || std::string(argv[0]) == "uninstall")
      && std::string(argv[1]) == "node") {
    return remove_node();
  }
  std::cerr << "Usage: agentcodi-toolchain [list|install node|remove node]\n";
  return 2;
}

std::string shell_functions(const std::string& bridge) {
  const std::string command = shell_quote(bridge);
  return
      "agentcodi-toolchain() { " + command + " --toolchain \"$@\"; }; "
      "node() { " + command + " --node \"$@\"; }; ";
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
    std::cerr << errno_message("Terminal initialization descriptor", saved_errno) << '\n';
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

int run_node(int argc, char* argv[]) {
  std::string error;
  if (!node_enabled(&error)) {
    if (!error.empty()) {
      std::cerr << error << '\n';
      return 126;
    }
    std::cerr
        << "Node.js " << kNodeVersion
        << " is available but not enabled. Ask the user for permission, then run: "
        << "agentcodi-toolchain install node\n";
    return 127;
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

}  // namespace

int main(int argc, char* argv[]) {
  umask(0077);
  const std::string invoked_as = argc > 0 ? executable_name(argv[0]) : "";
  if (invoked_as == "node") {
    return run_node(argc - 1, argv + 1);
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
  if (argc == 3
      && (std::string(argv[1]) == "-c" || std::string(argv[1]) == "-lc")) {
    return run_shell_command(argv[2]);
  }
  std::cerr << "Unsupported AGENTCODI shell invocation\n";
  return 2;
}
