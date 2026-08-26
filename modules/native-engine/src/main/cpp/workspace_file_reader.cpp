#include "workspace_file_reader.h"

#include <cerrno>
#include <climits>
#include <cstdint>
#include <limits>
#include <utility>

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

  int release() {
    const int descriptor = descriptor_;
    descriptor_ = -1;
    return descriptor;
  }

  void reset(int descriptor = -1) {
    if (descriptor_ >= 0) {
      close(descriptor_);
    }
    descriptor_ = descriptor;
  }

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

bool parse_relative_path(
    const std::string& relative_path,
    std::vector<std::string>* components,
    std::string* error) {
  if (relative_path.empty() || relative_path.front() == '/'
      || relative_path.back() == '/') {
    return fail("Workspace relative path is unsafe", error);
  }
  std::size_t start = 0;
  while (start < relative_path.size()) {
    const std::size_t separator = relative_path.find('/', start);
    const std::size_t end = separator == std::string::npos
        ? relative_path.size()
        : separator;
    const std::string component = relative_path.substr(start, end - start);
    if (component.empty() || component == "." || component == ".."
        || component.size() > NAME_MAX) {
      return fail("Workspace relative path is unsafe", error);
    }
    for (unsigned char value : component) {
      if (value == 0U || value < 0x20U || value == 0x7fU
          || value == '\\' || value == ':') {
        return fail("Workspace relative path is unsafe", error);
      }
    }
    components->push_back(component);
    if (separator == std::string::npos) {
      break;
    }
    start = separator + 1U;
  }
  return !components->empty()
      || fail("Workspace relative path is unsafe", error);
}

int duplicate_descriptor(int descriptor) {
  int duplicate;
  do {
    duplicate = fcntl(descriptor, F_DUPFD_CLOEXEC, 3);
  } while (duplicate < 0 && errno == EINTR);
  return duplicate;
}

int open_relative_file(
    int root_descriptor,
    const std::vector<std::string>& components,
    std::string* error) {
  ScopedDescriptor parent(duplicate_descriptor(root_descriptor));
  if (parent.get() < 0) {
    fail("Workspace root descriptor could not be duplicated", error);
    return -1;
  }
  for (std::size_t index = 0; index + 1U < components.size(); ++index) {
    int child;
    do {
      child = openat(
          parent.get(),
          components[index].c_str(),
          O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    } while (child < 0 && errno == EINTR);
    if (child < 0) {
      fail("Workspace parent directory could not be opened without following links", error);
      return -1;
    }
    parent.reset(child);
  }
  int file;
  do {
    file = openat(
        parent.get(),
        components.back().c_str(),
        O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
  } while (file < 0 && errno == EINTR);
  if (file < 0) {
    fail("Workspace file could not be opened without following links", error);
  }
  return file;
}

WorkspaceFileMetadata metadata_from_stat(const struct stat& attributes) {
  WorkspaceFileMetadata metadata;
  metadata.size = static_cast<std::int64_t>(attributes.st_size);
  metadata.modified_seconds =
      static_cast<std::int64_t>(attributes.st_mtim.tv_sec);
  metadata.modified_nanoseconds =
      static_cast<std::int64_t>(attributes.st_mtim.tv_nsec);
  metadata.changed_seconds =
      static_cast<std::int64_t>(attributes.st_ctim.tv_sec);
  metadata.changed_nanoseconds =
      static_cast<std::int64_t>(attributes.st_ctim.tv_nsec);
  metadata.device = static_cast<std::uint64_t>(attributes.st_dev);
  metadata.inode = static_cast<std::uint64_t>(attributes.st_ino);
  return metadata;
}

bool validate_regular_file(
    const struct stat& attributes,
    std::int64_t maximum_bytes,
    std::string* error) {
  if (!S_ISREG(attributes.st_mode)) {
    return fail("Workspace source is not a regular file", error);
  }
  if (attributes.st_nlink != 1) {
    return fail("Hard-linked workspace files are not exportable", error);
  }
  if (attributes.st_size < 0
      || static_cast<std::uint64_t>(attributes.st_size)
          > static_cast<std::uint64_t>(maximum_bytes)) {
    return fail("Workspace file size is outside the export limit", error);
  }
  return true;
}

bool same_snapshot(
    const struct stat& attributes,
    const WorkspaceFileMetadata& expected) {
  return S_ISREG(attributes.st_mode)
      && attributes.st_nlink == 1
      && static_cast<std::int64_t>(attributes.st_size) == expected.size
      && static_cast<std::int64_t>(attributes.st_mtim.tv_sec)
          == expected.modified_seconds
      && static_cast<std::int64_t>(attributes.st_mtim.tv_nsec)
          == expected.modified_nanoseconds
      && static_cast<std::int64_t>(attributes.st_ctim.tv_sec)
          == expected.changed_seconds
      && static_cast<std::int64_t>(attributes.st_ctim.tv_nsec)
          == expected.changed_nanoseconds
      && static_cast<std::uint64_t>(attributes.st_dev) == expected.device
      && static_cast<std::uint64_t>(attributes.st_ino) == expected.inode;
}

bool read_attributes(int descriptor, struct stat* attributes, std::string* error) {
  int result;
  do {
    result = fstat(descriptor, attributes);
  } while (result < 0 && errno == EINTR);
  return result == 0
      || fail("Workspace file attributes could not be read", error);
}

}  // namespace

std::unique_ptr<WorkspaceFileReader> WorkspaceFileReader::Open(
    const std::string& workspace,
    const std::string& relative_path,
    std::int64_t maximum_bytes,
    std::string* error) {
  if (error != nullptr) {
    error->clear();
  }
  if (!valid_workspace_path(workspace)) {
    fail("Workspace root path is invalid", error);
    return nullptr;
  }
  if (maximum_bytes < 0) {
    fail("Workspace file byte limit is invalid", error);
    return nullptr;
  }
  std::vector<std::string> components;
  if (!parse_relative_path(relative_path, &components, error)) {
    return nullptr;
  }

  int root;
  do {
    root = open(
        workspace.c_str(),
        O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  } while (root < 0 && errno == EINTR);
  ScopedDescriptor root_guard(root);
  if (root_guard.get() < 0) {
    fail("Workspace root could not be opened without following links", error);
    return nullptr;
  }
  struct stat root_attributes {};
  if (!read_attributes(root_guard.get(), &root_attributes, error)
      || !S_ISDIR(root_attributes.st_mode)) {
    fail("Workspace root is not a directory", error);
    return nullptr;
  }

  ScopedDescriptor file(open_relative_file(root_guard.get(), components, error));
  if (file.get() < 0) {
    return nullptr;
  }
  struct stat attributes {};
  if (!read_attributes(file.get(), &attributes, error)
      || !validate_regular_file(attributes, maximum_bytes, error)) {
    return nullptr;
  }
  std::unique_ptr<WorkspaceFileReader> reader(new WorkspaceFileReader(
      root_guard.release(),
      file.release(),
      std::move(components),
      maximum_bytes,
      metadata_from_stat(attributes)));
  if (!reader->VerifyUnchanged(error)) {
    return nullptr;
  }
  return reader;
}

WorkspaceFileReader::WorkspaceFileReader(
    int root_descriptor,
    int file_descriptor,
    std::vector<std::string> components,
    std::int64_t maximum_bytes,
    WorkspaceFileMetadata metadata)
    : root_descriptor_(root_descriptor),
      file_descriptor_(file_descriptor),
      components_(std::move(components)),
      maximum_bytes_(maximum_bytes),
      metadata_(metadata) {}

WorkspaceFileReader::~WorkspaceFileReader() {
  if (file_descriptor_ >= 0) {
    close(file_descriptor_);
  }
  if (root_descriptor_ >= 0) {
    close(root_descriptor_);
  }
}

const WorkspaceFileMetadata& WorkspaceFileReader::metadata() const {
  return metadata_;
}

ssize_t WorkspaceFileReader::Read(
    unsigned char* buffer,
    std::size_t length,
    std::string* error) {
  if (error != nullptr) {
    error->clear();
  }
  if (buffer == nullptr || length == 0U) {
    fail("Workspace file read buffer is invalid", error);
    return -1;
  }
  ssize_t count;
  do {
    count = read(file_descriptor_, buffer, length);
  } while (count < 0 && errno == EINTR);
  if (count < 0) {
    fail("Workspace file read failed", error);
    return -1;
  }
  if (count == 0) {
    return 0;
  }
  const std::uint64_t increment = static_cast<std::uint64_t>(count);
  if (total_read_ > std::numeric_limits<std::uint64_t>::max() - increment
      || total_read_ + increment
          > static_cast<std::uint64_t>(maximum_bytes_)
      || total_read_ + increment
          > static_cast<std::uint64_t>(metadata_.size)) {
    fail("Workspace file changed during export", error);
    return -1;
  }
  total_read_ += increment;
  return count;
}

bool WorkspaceFileReader::Position(
    std::int64_t absolute_offset,
    std::string* error) {
  if (error != nullptr) {
    error->clear();
  }
  if (absolute_offset < 0 || absolute_offset > metadata_.size
      || absolute_offset > maximum_bytes_) {
    return fail("Workspace file preview position is invalid", error);
  }
  off_t positioned;
  do {
    positioned = lseek(
        file_descriptor_,
        static_cast<off_t>(absolute_offset),
        SEEK_SET);
  } while (positioned < 0 && errno == EINTR);
  if (positioned < 0
      || static_cast<std::int64_t>(positioned) != absolute_offset) {
    return fail("Workspace file preview position could not be selected", error);
  }
  total_read_ = static_cast<std::uint64_t>(absolute_offset);
  return true;
}

bool WorkspaceFileReader::VerifyUnchanged(std::string* error) const {
  if (error != nullptr) {
    error->clear();
  }
  struct stat descriptor_attributes {};
  if (!read_attributes(file_descriptor_, &descriptor_attributes, error)
      || !same_snapshot(descriptor_attributes, metadata_)) {
    return fail("Workspace file changed during export", error);
  }
  ScopedDescriptor current(open_relative_file(root_descriptor_, components_, error));
  if (current.get() < 0) {
    return false;
  }
  struct stat current_attributes {};
  if (!read_attributes(current.get(), &current_attributes, error)
      || !same_snapshot(current_attributes, metadata_)) {
    return fail("Workspace file changed during export", error);
  }
  return true;
}

}  // namespace agentcodi
