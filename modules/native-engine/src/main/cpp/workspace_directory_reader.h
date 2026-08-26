#ifndef AGENTCODI_WORKSPACE_DIRECTORY_READER_H
#define AGENTCODI_WORKSPACE_DIRECTORY_READER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace agentcodi {

enum class WorkspaceDirectoryEntryKind : std::uint8_t {
  kDirectory = 1,
  kRegularFile = 2,
  kUnavailable = 3,
};

enum class WorkspaceDirectoryEntryReason : std::uint8_t {
  kNone = 0,
  kSymbolicLink = 1,
  kHardLink = 2,
  kSpecialEntry = 3,
  kUnsafeName = 4,
  kUnreadable = 5,
};

struct WorkspaceDirectoryEntry {
  std::string name;
  WorkspaceDirectoryEntryKind kind = WorkspaceDirectoryEntryKind::kUnavailable;
  WorkspaceDirectoryEntryReason reason = WorkspaceDirectoryEntryReason::kUnreadable;
  std::int64_t size = -1;
  std::int64_t modified_milliseconds = 0;
};

struct WorkspaceDirectoryListing {
  std::vector<WorkspaceDirectoryEntry> entries;
  bool truncated = false;
};

bool list_workspace_directory(
    const std::string& workspace,
    const std::string& relative_directory,
    std::size_t maximum_entries,
    std::size_t maximum_relative_path_bytes,
    std::size_t maximum_depth,
    WorkspaceDirectoryListing* listing,
    std::string* error);

}  // namespace agentcodi

#endif
