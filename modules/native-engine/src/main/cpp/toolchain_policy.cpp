#include "toolchain_policy.h"

#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "ripgrep_bridge_policy.h"

namespace agentcodi {
namespace {

constexpr const char* kPythonHomeRelativePath = "python";
constexpr const char* kPythonLandmarkRelativePath =
    "python/lib/python3.14/encodings/__init__.pyc";
constexpr off_t kMaximumRuntimeFileBytes = 16 * 1024 * 1024;

std::string errno_message(const char* operation, int error_number) {
  return std::string(operation) + ": " + std::strerror(error_number);
}

const char* required_environment(const char* name) {
  const char* value = std::getenv(name);
  if (value == nullptr || value[0] != '/') {
    return nullptr;
  }
  for (const char* cursor = value; *cursor != '\0'; ++cursor) {
    if (*cursor == '\n' || *cursor == '\r') {
      return nullptr;
    }
  }
  return value;
}

bool canonical_tool_runtime(std::string* runtime, std::string* error) {
  const char* supplied_runtime = required_environment("AGENTCODI_TOOL_RUNTIME");
  std::string workspace;
  std::string toolchain;
  if (supplied_runtime == nullptr) {
    *error = "Packaged tool runtime environment is missing";
    return false;
  }
  if (!CanonicalPrivateToolDirectory(supplied_runtime, runtime, error)
      || !ResolveToolchainDirectories(&workspace, &toolchain, error)) {
    return false;
  }
  if (ContainsPath(workspace, *runtime)
      || ContainsPath(*runtime, workspace)
      || ContainsPath(toolchain, *runtime)
      || ContainsPath(*runtime, toolchain)) {
    *error = "Packaged tool runtime must remain separate from the workspace";
    return false;
  }
  return true;
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
      || !ContainsPath(runtime, resolved)) {
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
      || !ContainsPath(runtime, resolved)
      || !CanonicalPrivateToolDirectory(resolved, home, error)) {
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

bool set_environment(
    const char* name,
    const std::string& value,
    std::string* error) {
  if (setenv(name, value.c_str(), 1) == 0) {
    return true;
  }
  *error = errno_message("Packaged tool environment", errno);
  return false;
}

const PackageSpec& guarded_package(GuardedTool tool) {
  switch (tool) {
    case GuardedTool::kNode:
      return kNodePackage;
    case GuardedTool::kPython:
      return kPythonPackage;
    case GuardedTool::kRipgrep:
      return kRipgrepPackage;
  }
  return kNodePackage;
}

}  // namespace

const PackageSpec kNodePackage {
    "node", "Node.js", "24.18.0", "node-24.18.0"};
const PackageSpec kNpmPackage {
    "npm", "npm", "11.19.0", "npm-11.19.0"};
const PackageSpec kPythonPackage {
    "python", "Python", "3.14.6", "python-3.14.6"};
const PackageSpec kRipgrepPackage {
    "ripgrep", "ripgrep", "15.2.0", "ripgrep-15.2.0"};

bool ContainsPath(const std::string& parent, const std::string& child) {
  return parent == child
      || (child.size() > parent.size()
          && child.compare(0U, parent.size(), parent) == 0
          && child[parent.size()] == '/');
}

bool CanonicalPrivateToolDirectory(
    const char* supplied,
    std::string* canonical,
    std::string* error) {
  if (supplied == nullptr || canonical == nullptr || error == nullptr) {
    return false;
  }
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

bool ResolveToolchainDirectories(
    std::string* workspace,
    std::string* toolchain,
    std::string* error) {
  if (workspace == nullptr || toolchain == nullptr || error == nullptr) {
    return false;
  }
  const char* supplied_workspace = required_environment("AGENTCODI_WORKSPACE");
  const char* supplied_toolchain = required_environment("AGENTCODI_TOOLCHAIN");
  if (supplied_workspace == nullptr || supplied_toolchain == nullptr) {
    *error = "Toolchain environment is missing";
    return false;
  }
  if (!CanonicalPrivateToolDirectory(supplied_workspace, workspace, error)
      || !CanonicalPrivateToolDirectory(supplied_toolchain, toolchain, error)) {
    return false;
  }
  if (*workspace == *toolchain || !ContainsPath(*workspace, *toolchain)) {
    *error = "Toolchain directory escaped the canonical workspace";
    return false;
  }
  return true;
}

int OpenToolActivationDirectory(bool create, std::string* error) {
  if (error == nullptr) {
    return -1;
  }
  std::string workspace;
  std::string toolchain;
  if (!ResolveToolchainDirectories(&workspace, &toolchain, error)) {
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
      error->clear();
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

bool ValidateToolActivationMarker(
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

bool IsToolPackageEnabled(
    const PackageSpec& package,
    std::string* error) {
  if (error == nullptr) {
    return false;
  }
  error->clear();
  const int directory = OpenToolActivationDirectory(false, error);
  if (directory < 0) {
    return false;
  }
  const bool enabled = ValidateToolActivationMarker(directory, package, error);
  close(directory);
  return enabled;
}

bool PrepareGuardedToolInvocation(
    GuardedTool tool,
    const std::vector<std::string>& arguments,
    std::string* error,
    int* exit_code) {
  if (error == nullptr || exit_code == nullptr) {
    return false;
  }
  error->clear();
  *exit_code = 126;
  const PackageSpec& package = guarded_package(tool);
  if (!IsToolPackageEnabled(package, error)) {
    if (error->empty()) {
      *error = std::string(package.display_name) + " " + package.version
          + " is available but not enabled. Ask the user for permission, then run: "
          + "agentcodi-toolchain install " + package.name;
      *exit_code = 127;
    }
    return false;
  }

  if (tool == GuardedTool::kPython) {
    std::string python_home;
    if (!canonical_python_home(&python_home, error)
        || !set_environment("PYTHONHOME", python_home, error)
        || !set_environment("PYTHONNOUSERSITE", "1", error)
        || !set_environment("PYTHONDONTWRITEBYTECODE", "1", error)
        || !set_environment("PYTHONSAFEPATH", "1", error)
        || !set_environment("PYTHON_HISTORY", "/dev/null", error)
        || !set_environment("PYTHONUTF8", "1", error)) {
      return false;
    }
  } else if (tool == GuardedTool::kRipgrep) {
    *exit_code = 2;
    if (!ValidateRipgrepArguments(arguments, error)
        || !PrepareRipgrepEnvironment(error)) {
      return false;
    }
  }

  *exit_code = 0;
  return true;
}

}  // namespace agentcodi
