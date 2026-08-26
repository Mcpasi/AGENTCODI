#include "workspace_directory_reader.h"

#include <cerrno>
#include <climits>
#include <cstdint>
#include <limits>
#include <utility>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace agentcodi {
namespace {

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
  void reset(int descriptor = -1) {
    if (descriptor_ >= 0) {
      close(descriptor_);
    }
    descriptor_ = descriptor;
  }
  int release() {
    const int descriptor = descriptor_;
    descriptor_ = -1;
    return descriptor;
  }

 private:
  int descriptor_;
};

class ScopedDirectory final {
 public:
  explicit ScopedDirectory(DIR* directory) : directory_(directory) {}
  ~ScopedDirectory() {
    if (directory_ != nullptr) {
      closedir(directory_);
    }
  }
  ScopedDirectory(const ScopedDirectory&) = delete;
  ScopedDirectory& operator=(const ScopedDirectory&) = delete;
  DIR* get() const { return directory_; }

 private:
  DIR* directory_;
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

bool safe_component(const std::string& component) {
  if (component.empty() || component == "." || component == ".."
      || component.size() > NAME_MAX) {
    return false;
  }
  for (unsigned char value : component) {
    if (value == 0U || value < 0x20U || value == 0x7fU
        || value == '/' || value == '\\' || value == ':') {
      return false;
    }
  }
  return true;
}

bool parse_directory(
    const std::string& relative_directory,
    std::size_t maximum_relative_path_bytes,
    std::size_t maximum_depth,
    std::vector<std::string>* components,
    std::string* error) {
  if (relative_directory.size() > maximum_relative_path_bytes) {
    return fail("Workspace directory path exceeds the browser limit", error);
  }
  if (relative_directory.empty()) {
    return true;
  }
  if (relative_directory.front() == '/' || relative_directory.back() == '/') {
    return fail("Workspace directory path is unsafe", error);
  }
  std::size_t start = 0;
  while (start < relative_directory.size()) {
    const std::size_t separator = relative_directory.find('/', start);
    const std::size_t end = separator == std::string::npos
        ? relative_directory.size()
        : separator;
    const std::string component = relative_directory.substr(start, end - start);
    if (!safe_component(component)) {
      return fail("Workspace directory path is unsafe", error);
    }
    components->push_back(component);
    if (components->size() > maximum_depth) {
      return fail("Workspace directory depth exceeds the browser limit", error);
    }
    if (separator == std::string::npos) {
      break;
    }
    start = separator + 1U;
  }
  return true;
}

int duplicate_descriptor(int descriptor) {
  int duplicate;
  do {
    duplicate = fcntl(descriptor, F_DUPFD_CLOEXEC, 3);
  } while (duplicate < 0 && errno == EINTR);
  return duplicate;
}

std::int64_t modified_milliseconds(const struct stat& attributes) {
  if (attributes.st_mtim.tv_sec < 0 || attributes.st_mtim.tv_nsec < 0) {
    return 0;
  }
  constexpr std::int64_t kMillisecondsPerSecond = 1000;
  const std::int64_t seconds = static_cast<std::int64_t>(attributes.st_mtim.tv_sec);
  const std::int64_t fractional =
      static_cast<std::int64_t>(attributes.st_mtim.tv_nsec / 1000000L);
  if (seconds > (std::numeric_limits<std::int64_t>::max() - fractional)
          / kMillisecondsPerSecond) {
    return std::numeric_limits<std::int64_t>::max();
  }
  return seconds * kMillisecondsPerSecond + fractional;
}

std::string child_path(
    const std::string& relative_directory,
    const std::string& name) {
  return relative_directory.empty()
      ? name
      : relative_directory + "/" + name;
}

WorkspaceDirectoryEntry unavailable(
    std::string name,
    WorkspaceDirectoryEntryReason reason,
    const struct stat* attributes = nullptr) {
  WorkspaceDirectoryEntry entry;
  entry.name = std::move(name);
  entry.kind = WorkspaceDirectoryEntryKind::kUnavailable;
  entry.reason = reason;
  if (attributes != nullptr) {
    entry.modified_milliseconds = modified_milliseconds(*attributes);
  }
  return entry;
}

}  // namespace

bool list_workspace_directory(
    const std::string& workspace,
    const std::string& relative_directory,
    std::size_t maximum_entries,
    std::size_t maximum_relative_path_bytes,
    std::size_t maximum_depth,
    WorkspaceDirectoryListing* listing,
    std::string* error) {
  if (error != nullptr) {
    error->clear();
  }
  if (listing == nullptr || maximum_entries == 0U
      || maximum_relative_path_bytes == 0U || maximum_depth == 0U) {
    return fail("Workspace directory catalog limits are invalid", error);
  }
  listing->entries.clear();
  listing->truncated = false;
  if (!valid_workspace_path(workspace)) {
    return fail("Workspace root path is invalid", error);
  }
  std::vector<std::string> components;
  if (!parse_directory(
          relative_directory,
          maximum_relative_path_bytes,
          maximum_depth,
          &components,
          error)) {
    return false;
  }

  int root;
  do {
    root = open(
        workspace.c_str(),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  } while (root < 0 && errno == EINTR);
  ScopedDescriptor directory(root);
  if (directory.get() < 0) {
    return fail("Workspace root could not be opened without following links", error);
  }
  struct stat root_attributes {};
  if (fstat(directory.get(), &root_attributes) != 0
      || !S_ISDIR(root_attributes.st_mode)) {
    return fail("Workspace root is not a directory", error);
  }
  for (const std::string& component : components) {
    int child;
    do {
      child = openat(
          directory.get(),
          component.c_str(),
          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    } while (child < 0 && errno == EINTR);
    if (child < 0) {
      return fail(
          "Workspace browser directory could not be opened without following links",
          error);
    }
    directory.reset(child);
  }

  ScopedDescriptor iteration_descriptor(duplicate_descriptor(directory.get()));
  if (iteration_descriptor.get() < 0) {
    return fail("Workspace browser directory could not be duplicated", error);
  }
  DIR* raw_directory = fdopendir(iteration_descriptor.release());
  if (raw_directory == nullptr) {
    return fail("Workspace browser directory stream could not be opened", error);
  }
  ScopedDirectory stream(raw_directory);
  while (true) {
    errno = 0;
    struct dirent* raw_entry = readdir(stream.get());
    if (raw_entry == nullptr) {
      if (errno != 0) {
        return fail("Workspace browser directory could not be read", error);
      }
      break;
    }
    const std::string name(raw_entry->d_name);
    if (name == "." || name == "..") {
      continue;
    }
    if (listing->entries.size() >= maximum_entries) {
      listing->truncated = true;
      break;
    }
    if (!safe_component(name)
        || child_path(relative_directory, name).size()
            > maximum_relative_path_bytes) {
      listing->entries.push_back(unavailable(
          name.empty() ? std::string("[entry]") : name,
          WorkspaceDirectoryEntryReason::kUnsafeName));
      continue;
    }
    struct stat attributes {};
    int status;
    do {
      status = fstatat(
          directory.get(),
          name.c_str(),
          &attributes,
          AT_SYMLINK_NOFOLLOW);
    } while (status < 0 && errno == EINTR);
    if (status < 0) {
      listing->entries.push_back(unavailable(
          name,
          WorkspaceDirectoryEntryReason::kUnreadable));
      continue;
    }
    if (S_ISLNK(attributes.st_mode)) {
      listing->entries.push_back(unavailable(
          name,
          WorkspaceDirectoryEntryReason::kSymbolicLink,
          &attributes));
      continue;
    }
    if (S_ISDIR(attributes.st_mode)) {
      WorkspaceDirectoryEntry entry;
      entry.name = name;
      entry.kind = WorkspaceDirectoryEntryKind::kDirectory;
      entry.reason = WorkspaceDirectoryEntryReason::kNone;
      entry.modified_milliseconds = modified_milliseconds(attributes);
      listing->entries.push_back(std::move(entry));
      continue;
    }
    if (S_ISREG(attributes.st_mode)) {
      if (attributes.st_nlink != 1) {
        listing->entries.push_back(unavailable(
            name,
            WorkspaceDirectoryEntryReason::kHardLink,
            &attributes));
        continue;
      }
      if (attributes.st_size < 0) {
        listing->entries.push_back(unavailable(
            name,
            WorkspaceDirectoryEntryReason::kUnreadable,
            &attributes));
        continue;
      }
      WorkspaceDirectoryEntry entry;
      entry.name = name;
      entry.kind = WorkspaceDirectoryEntryKind::kRegularFile;
      entry.reason = WorkspaceDirectoryEntryReason::kNone;
      entry.size = static_cast<std::int64_t>(attributes.st_size);
      entry.modified_milliseconds = modified_milliseconds(attributes);
      listing->entries.push_back(std::move(entry));
      continue;
    }
    listing->entries.push_back(unavailable(
        name,
        WorkspaceDirectoryEntryReason::kSpecialEntry,
        &attributes));
  }
  return true;
}

}  // namespace agentcodi
