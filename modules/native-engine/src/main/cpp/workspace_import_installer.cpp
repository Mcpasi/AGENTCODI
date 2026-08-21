#include "workspace_import_installer.h"

#include <cerrno>
#include <cstddef>
#include <cstdint>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace agentcodi {
namespace {

constexpr const char* kImportsDirectory = "imports";
constexpr const char* kPendingPrefix = ".pending-";
constexpr std::size_t kRandomTokenCharacters = 32U;
constexpr std::size_t kMaximumExtensionCharacters = 12U;
constexpr std::int64_t kMaximumImportBytes = 512LL * 1024LL * 1024LL;
constexpr unsigned int kRenameNoReplace = 1U;

class ScopedDescriptor final {
 public:
  explicit ScopedDescriptor(int descriptor = -1) : descriptor_(descriptor) {}

  ~ScopedDescriptor() {
    if (descriptor_ >= 0) {
      close(descriptor_);
    }
  }

  ScopedDescriptor(const ScopedDescriptor&) = delete;
  ScopedDescriptor& operator=(const ScopedDescriptor&) = delete;

  int get() const { return descriptor_; }

 private:
  int descriptor_;
};

bool fail(const char* message, std::string* error) {
  if (error != nullptr) {
    *error = message;
  }
  return false;
}

bool valid_workspace_path(const std::string& workspace) {
  if (workspace.empty() || workspace.front() != '/') {
    return false;
  }
  for (unsigned char value : workspace) {
    if (value == 0U || value < 0x20U || value == 0x7fU) {
      return false;
    }
  }
  return true;
}

bool is_lower_hex_token(const std::string& value, std::size_t offset) {
  if (value.size() < offset + kRandomTokenCharacters) {
    return false;
  }
  for (std::size_t index = offset;
       index < offset + kRandomTokenCharacters;
       ++index) {
    const char current = value[index];
    if (!((current >= '0' && current <= '9')
          || (current >= 'a' && current <= 'f'))) {
      return false;
    }
  }
  return true;
}

bool valid_names(
    const std::string& pending_name,
    const std::string& final_name) {
  const std::string pending_prefix(kPendingPrefix);
  if (pending_name.size() != pending_prefix.size() + kRandomTokenCharacters
      || pending_name.compare(0U, pending_prefix.size(), pending_prefix) != 0
      || !is_lower_hex_token(pending_name, pending_prefix.size())
      || final_name.size() < kRandomTokenCharacters
      || final_name.size()
          > kRandomTokenCharacters + 1U + kMaximumExtensionCharacters
      || !is_lower_hex_token(final_name, 0U)
      || pending_name.compare(
          pending_prefix.size(),
          kRandomTokenCharacters,
          final_name,
          0U,
          kRandomTokenCharacters) != 0) {
    return false;
  }
  if (final_name.size() == kRandomTokenCharacters) {
    return true;
  }
  if (final_name[kRandomTokenCharacters] != '.') {
    return false;
  }
  for (std::size_t index = kRandomTokenCharacters + 1U;
       index < final_name.size();
       ++index) {
    const char current = final_name[index];
    if (!((current >= 'a' && current <= 'z')
          || (current >= '0' && current <= '9'))) {
      return false;
    }
  }
  return final_name.size() > kRandomTokenCharacters + 1U;
}

int open_directory_at(int parent, const char* name) {
  int descriptor;
  do {
    descriptor = openat(
        parent,
        name,
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  } while (descriptor < 0 && errno == EINTR);
  return descriptor;
}

bool read_attributes(int descriptor, struct stat* attributes) {
  int result;
  do {
    result = fstat(descriptor, attributes);
  } while (result < 0 && errno == EINTR);
  return result == 0;
}

bool private_directory(int descriptor) {
  struct stat attributes {};
  return read_attributes(descriptor, &attributes)
      && S_ISDIR(attributes.st_mode)
      && attributes.st_uid == geteuid()
      && (attributes.st_mode & 0777) == 0700;
}

bool private_pending_file(
    int descriptor,
    std::int64_t expected_byte_count) {
  struct stat attributes {};
  return read_attributes(descriptor, &attributes)
      && S_ISREG(attributes.st_mode)
      && attributes.st_uid == geteuid()
      && attributes.st_nlink == 1
      && (attributes.st_mode & 0777) == 0600
      && attributes.st_size >= 0
      && static_cast<std::int64_t>(attributes.st_size) == expected_byte_count;
}

}  // namespace

bool InstallWorkspaceImportNoReplace(
    const std::string& workspace,
    const std::string& pending_name,
    const std::string& final_name,
    std::int64_t expected_byte_count,
    std::string* error) {
  if (error != nullptr) {
    error->clear();
  }
  if (!valid_workspace_path(workspace)
      || expected_byte_count < 0
      || expected_byte_count > kMaximumImportBytes
      || !valid_names(pending_name, final_name)) {
    return fail("Workspace import installation request is invalid", error);
  }

  int root_descriptor;
  do {
    root_descriptor = open(
        workspace.c_str(),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  } while (root_descriptor < 0 && errno == EINTR);
  ScopedDescriptor root(root_descriptor);
  if (root.get() < 0 || !private_directory(root.get())) {
    return fail("Workspace root cannot install an imported file safely", error);
  }

  ScopedDescriptor imports(open_directory_at(root.get(), kImportsDirectory));
  if (imports.get() < 0 || !private_directory(imports.get())) {
    return fail("Workspace imports directory cannot be opened safely", error);
  }

  int pending_descriptor;
  do {
    pending_descriptor = openat(
        imports.get(),
        pending_name.c_str(),
        O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
  } while (pending_descriptor < 0 && errno == EINTR);
  ScopedDescriptor pending(pending_descriptor);
  if (pending.get() < 0
      || !private_pending_file(pending.get(), expected_byte_count)) {
    return fail("Pending workspace import is not a private regular file", error);
  }

  int move_result;
  do {
    move_result = static_cast<int>(syscall(
        SYS_renameat2,
        imports.get(),
        pending_name.c_str(),
        imports.get(),
        final_name.c_str(),
        kRenameNoReplace));
  } while (move_result != 0 && errno == EINTR);
  if (move_result == 0) {
    return true;
  }
  if (errno == EEXIST) {
    return fail("Random imported workspace name already exists", error);
  }
  return fail(
      "Workspace import could not be installed atomically without replacement",
      error);
}

}  // namespace agentcodi
