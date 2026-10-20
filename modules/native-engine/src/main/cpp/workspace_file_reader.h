#ifndef AGENTCODI_WORKSPACE_FILE_READER_H
#define AGENTCODI_WORKSPACE_FILE_READER_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <sys/types.h>

namespace agentcodi {

struct WorkspaceFileMetadata {
  std::int64_t size = 0;
  std::int64_t modified_seconds = 0;
  std::int64_t modified_nanoseconds = 0;
  std::int64_t changed_seconds = 0;
  std::int64_t changed_nanoseconds = 0;
  std::uint64_t device = 0;
  std::uint64_t inode = 0;
};

enum class WorkspaceFileCreationResult { Created, AlreadyExists, Failed };

class WorkspaceFileReader final {
 public:
  static std::unique_ptr<WorkspaceFileReader> Open(
      const std::string& workspace,
      const std::string& relative_path,
      std::int64_t maximum_bytes,
      std::string* error);

  static WorkspaceFileCreationResult Create(
      const std::string& workspace, const std::string& relative_path,
      const unsigned char* bytes, std::size_t length,
      std::int64_t maximum_bytes, std::string* error);

  ~WorkspaceFileReader();

  WorkspaceFileReader(const WorkspaceFileReader&) = delete;
  WorkspaceFileReader& operator=(const WorkspaceFileReader&) = delete;

  const WorkspaceFileMetadata& metadata() const;

  // Returns a positive byte count, zero at EOF, or -1 after setting error.
  ssize_t Read(unsigned char* buffer, std::size_t length, std::string* error);

  bool Position(std::int64_t absolute_offset, std::string* error);

  bool VerifyUnchanged(std::string* error) const;

  // Save a complete revision through a synced sibling and atomic rename.
  // The reader's original snapshot is consumed by a successful replacement.
  bool Replace(const unsigned char* bytes, std::size_t length, std::string* error);

 private:
  WorkspaceFileReader(
      int root_descriptor,
      int file_descriptor,
      std::vector<std::string> components,
      std::int64_t maximum_bytes,
      WorkspaceFileMetadata metadata);

  int root_descriptor_;
  int file_descriptor_;
  std::vector<std::string> components_;
  std::int64_t maximum_bytes_;
  WorkspaceFileMetadata metadata_;
  std::uint64_t total_read_ = 0;
};

}  // namespace agentcodi

#endif
