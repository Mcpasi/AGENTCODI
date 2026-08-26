#include "workspace_directory_reader.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <map>
#include <memory>
#include <string>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

int assertions = 0;

void expect(bool condition, const char* message) {
  ++assertions;
  if (!condition) {
    std::cerr << "FAILED: " << message << '\n';
    std::exit(1);
  }
}

std::string join(const std::string& parent, const std::string& child) {
  return parent + "/" + child;
}

void write_file(const std::string& path, const std::string& contents) {
  int descriptor = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
  expect(descriptor >= 0, "fixture file opens");
  std::size_t offset = 0;
  while (offset < contents.size()) {
    const ssize_t count = write(
        descriptor,
        contents.data() + offset,
        contents.size() - offset);
    expect(count > 0, "fixture file writes");
    offset += static_cast<std::size_t>(count);
  }
  expect(close(descriptor) == 0, "fixture file closes");
}

class Fixture final {
 public:
  Fixture() {
    char pattern[] = "/tmp/agentcodi-directory-reader-XXXXXX";
    char* created = mkdtemp(pattern);
    expect(created != nullptr, "fixture directory creates");
    root = created;
  }

  ~Fixture() {
    unlink(join(root, "regular.txt").c_str());
    unlink(join(root, "hard-source.bin").c_str());
    unlink(join(root, "hard-alias.bin").c_str());
    unlink(join(root, "linked.txt").c_str());
    unlink(join(root, "pipe").c_str());
    unlink(join(root, "third.txt").c_str());
    unlink(join(root, "nested/inside.txt").c_str());
    unlink(join(root, "nested/second.txt").c_str());
    rmdir(join(root, "nested").c_str());
    rmdir(root.c_str());
  }

  std::string root;
};

std::map<std::string, agentcodi::WorkspaceDirectoryEntry> by_name(
    const agentcodi::WorkspaceDirectoryListing& listing) {
  std::map<std::string, agentcodi::WorkspaceDirectoryEntry> entries;
  for (const auto& entry : listing.entries) {
    entries.emplace(entry.name, entry);
  }
  return entries;
}

void catalogs_supported_and_unavailable_entries() {
  Fixture fixture;
  expect(mkdir(join(fixture.root, "nested").c_str(), 0700) == 0, "nested creates");
  write_file(join(fixture.root, "regular.txt"), "workspace");
  write_file(join(fixture.root, "hard-source.bin"), "hard");
  expect(link(
      join(fixture.root, "hard-source.bin").c_str(),
      join(fixture.root, "hard-alias.bin").c_str()) == 0,
      "hard link creates");
  expect(symlink(
      "/does/not/matter",
      join(fixture.root, "linked.txt").c_str()) == 0,
      "symbolic link creates");
  expect(mkfifo(join(fixture.root, "pipe").c_str(), 0600) == 0, "fifo creates");

  agentcodi::WorkspaceDirectoryListing listing;
  std::string error;
  expect(agentcodi::list_workspace_directory(
      fixture.root, "", 32, 2048, 64, &listing, &error),
      "root directory lists");
  expect(error.empty(), "root listing has no error");
  expect(!listing.truncated, "root listing is complete");
  expect(listing.entries.size() == 6U, "all direct entries remain visible");
  const auto entries = by_name(listing);
  expect(entries.at("nested").kind
      == agentcodi::WorkspaceDirectoryEntryKind::kDirectory,
      "directory is navigable");
  expect(entries.at("regular.txt").kind
      == agentcodi::WorkspaceDirectoryEntryKind::kRegularFile,
      "regular file is previewable");
  expect(entries.at("regular.txt").size == 9, "regular size is reported");
  expect(entries.at("linked.txt").reason
      == agentcodi::WorkspaceDirectoryEntryReason::kSymbolicLink,
      "symbolic link is visible but unavailable");
  expect(entries.at("hard-source.bin").reason
      == agentcodi::WorkspaceDirectoryEntryReason::kHardLink,
      "hard-link source is unavailable");
  expect(entries.at("hard-alias.bin").reason
      == agentcodi::WorkspaceDirectoryEntryReason::kHardLink,
      "hard-link alias is unavailable");
  expect(entries.at("pipe").reason
      == agentcodi::WorkspaceDirectoryEntryReason::kSpecialEntry,
      "special entry is visible but unavailable");
}

void navigates_nested_without_following_links() {
  Fixture fixture;
  expect(mkdir(join(fixture.root, "nested").c_str(), 0700) == 0, "nested creates");
  write_file(join(fixture.root, "nested/inside.txt"), "inside");
  agentcodi::WorkspaceDirectoryListing listing;
  std::string error;
  expect(agentcodi::list_workspace_directory(
      fixture.root, "nested", 8, 2048, 64, &listing, &error),
      "nested directory lists");
  expect(listing.entries.size() == 1U, "nested listing stays direct");
  expect(listing.entries[0].name == "inside.txt", "nested file name returns");

  expect(symlink(
      "/tmp",
      join(fixture.root, "linked.txt").c_str()) == 0,
      "directory symlink creates");
  expect(!agentcodi::list_workspace_directory(
      fixture.root, "linked.txt", 8, 2048, 64, &listing, &error),
      "symbolic directory is never traversed");
  expect(!error.empty(), "symbolic traversal reports bounded error");
}

void truncates_without_discarding_the_safe_prefix() {
  Fixture fixture;
  write_file(join(fixture.root, "regular.txt"), "one");
  write_file(join(fixture.root, "hard-source.bin"), "two");
  write_file(join(fixture.root, "third.txt"), "three");
  agentcodi::WorkspaceDirectoryListing listing;
  std::string error;
  expect(agentcodi::list_workspace_directory(
      fixture.root, "", 2, 2048, 64, &listing, &error),
      "bounded listing succeeds");
  expect(listing.truncated, "bounded listing declares truncation");
  expect(listing.entries.size() == 2U, "bounded listing preserves its entries");
}

void rejects_unsafe_requests() {
  Fixture fixture;
  agentcodi::WorkspaceDirectoryListing listing;
  std::string error;
  expect(!agentcodi::list_workspace_directory(
      fixture.root, "../outside", 8, 2048, 64, &listing, &error),
      "parent traversal is rejected");
  expect(!agentcodi::list_workspace_directory(
      fixture.root, "/absolute", 8, 2048, 64, &listing, &error),
      "absolute directory is rejected");
  expect(!agentcodi::list_workspace_directory(
      fixture.root, "", 0, 2048, 64, &listing, &error),
      "zero entry limit is rejected");
  expect(!agentcodi::list_workspace_directory(
      fixture.root, "nested", 8, 3, 64, &listing, &error),
      "path byte limit is enforced");
}

}  // namespace

int main() {
  catalogs_supported_and_unavailable_entries();
  navigates_nested_without_following_links();
  truncates_without_discarding_the_safe_prefix();
  rejects_unsafe_requests();
  std::cout << "Workspace directory reader assertions passed: "
            << assertions << '\n';
  return 0;
}
