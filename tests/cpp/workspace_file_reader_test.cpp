#include "workspace_file_reader.h"

#include <cerrno>
#include <atomic>
#include <cstdlib>
#include <csignal>
#include <filesystem>
#include <functional>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <unistd.h>

namespace {

void expect(bool condition, const std::string& message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

class TemporaryDirectory final {
 public:
  TemporaryDirectory() {
    std::vector<char> pattern;
    const std::string value = "/tmp/agentcodi-workspace-reader-XXXXXX";
    pattern.assign(value.begin(), value.end());
    pattern.push_back('\0');
    char* created = mkdtemp(pattern.data());
    if (created == nullptr) {
      throw std::runtime_error("could not create temporary test directory");
    }
    path_ = created;
  }

  ~TemporaryDirectory() {
    std::error_code ignored;
    std::filesystem::remove_all(path_, ignored);
  }

  const std::filesystem::path& path() const { return path_; }

 private:
  std::filesystem::path path_;
};

// A real write error that root cannot bypass. Both state changes are restored
// before subsequent tests, including the concurrent-creation regression.
class ScopedWriteFailure final {
 public:
  ScopedWriteFailure() {
    if (getrlimit(RLIMIT_FSIZE, &previous_limit_) != 0) {
      throw std::runtime_error("could not read file size limit");
    }
    struct sigaction ignored {};
    ignored.sa_handler = SIG_IGN;
    sigemptyset(&ignored.sa_mask);
    if (sigaction(SIGXFSZ, &ignored, &previous_signal_) != 0) {
      throw std::runtime_error("could not configure file size signal");
    }
    struct rlimit limited = previous_limit_;
    limited.rlim_cur = 0;
    if (setrlimit(RLIMIT_FSIZE, &limited) != 0) {
      sigaction(SIGXFSZ, &previous_signal_, nullptr);
      throw std::runtime_error("could not configure file size limit");
    }
  }
  ~ScopedWriteFailure() {
    setrlimit(RLIMIT_FSIZE, &previous_limit_);
    sigaction(SIGXFSZ, &previous_signal_, nullptr);
  }
  ScopedWriteFailure(const ScopedWriteFailure&) = delete;
  ScopedWriteFailure& operator=(const ScopedWriteFailure&) = delete;
 private:
  struct rlimit previous_limit_ {};
  struct sigaction previous_signal_ {};
};

void make_directory(const std::filesystem::path& path) {
  if (mkdir(path.c_str(), 0700) != 0) {
    throw std::runtime_error("could not create test directory");
  }
}

void write_file(const std::filesystem::path& path, const std::string& contents) {
  const int descriptor = open(
      path.c_str(),
      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
      0600);
  if (descriptor < 0) {
    throw std::runtime_error("could not create test file");
  }
  std::size_t offset = 0;
  while (offset < contents.size()) {
    const ssize_t count = write(
        descriptor,
        contents.data() + offset,
        contents.size() - offset);
    if (count < 0 && errno == EINTR) {
      continue;
    }
    if (count <= 0) {
      close(descriptor);
      throw std::runtime_error("could not write test file");
    }
    offset += static_cast<std::size_t>(count);
  }
  if (close(descriptor) != 0) {
    throw std::runtime_error("could not close test file");
  }
}

std::string read_all(agentcodi::WorkspaceFileReader* reader) {
  std::string result;
  unsigned char buffer[4];
  while (true) {
    std::string error;
    const ssize_t count = reader->Read(buffer, sizeof(buffer), &error);
    expect(count >= 0, error.empty() ? "reader failed" : error);
    if (count == 0) {
      break;
    }
    result.append(
        reinterpret_cast<const char*>(buffer),
        static_cast<std::size_t>(count));
  }
  return result;
}

void reads_nested_regular_file() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  make_directory(workspace);
  make_directory(workspace / "nested");
  write_file(workspace / "nested" / "payload.bin", "workspace-bytes");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "nested/payload.bin",
          1024,
          &error);
  expect(reader != nullptr, error);
  expect(reader->metadata().size == 15, "reader reported the wrong size");
  expect(reader->Position(10, &error), error);
  unsigned char suffix[5];
  expect(reader->Read(suffix, sizeof(suffix), &error) == 5, error);
  expect(
      std::string(reinterpret_cast<const char*>(suffix), sizeof(suffix)) == "bytes",
      "positioned reader returned the wrong suffix");
  expect(!reader->Position(16, &error), "reader accepted a position beyond EOF");
  expect(reader->Position(0, &error), error);
  expect(read_all(reader.get()) == "workspace-bytes", "reader changed file bytes");
  expect(reader->VerifyUnchanged(&error), error);
}

void rejects_final_symbolic_link() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(private_directory);
  write_file(private_directory / "outside.bin", "outside-bytes");
  expect(
      symlink(
          (private_directory / "outside.bin").c_str(),
          (workspace / "linked.bin").c_str()) == 0,
      "could not create final symbolic link");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "linked.bin",
          1024,
          &error);
  expect(reader == nullptr, "reader followed a final symbolic link");
}

void rejects_symbolic_parent() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(private_directory);
  write_file(private_directory / "outside.bin", "outside-bytes");
  expect(
      symlink(private_directory.c_str(), (workspace / "linked-parent").c_str()) == 0,
      "could not create parent symbolic link");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "linked-parent/outside.bin",
          1024,
          &error);
  expect(reader == nullptr, "reader followed a symbolic parent directory");
}

void rejects_hard_link() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(private_directory);
  const std::filesystem::path outside = private_directory / "outside.bin";
  write_file(outside, "outside-bytes");
  expect(
      link(outside.c_str(), (workspace / "linked.bin").c_str()) == 0,
      "could not create hard link");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "linked.bin",
          1024,
          &error);
  expect(reader == nullptr, "reader accepted a hard-linked file");
}

void keeps_open_descriptor_inside_original_workspace_tree() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = temporary.path() / "workspace";
  const std::filesystem::path private_directory = temporary.path() / "private";
  make_directory(workspace);
  make_directory(workspace / "nested");
  make_directory(private_directory);
  write_file(workspace / "nested" / "payload.bin", "safe");
  write_file(private_directory / "payload.bin", "outside");

  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> reader =
      agentcodi::WorkspaceFileReader::Open(
          workspace.string(),
          "nested/payload.bin",
          1024,
          &error);
  expect(reader != nullptr, error);
  expect(
      rename(
          (workspace / "nested").c_str(),
          (workspace / "nested-before-swap").c_str()) == 0,
      "could not move original parent directory");
  expect(
      symlink(private_directory.c_str(), (workspace / "nested").c_str()) == 0,
      "could not replace parent directory with a symbolic link");

  expect(read_all(reader.get()) == "safe", "reader escaped through replaced parent");
  expect(
      !reader->VerifyUnchanged(&error),
      "reader did not report the replaced workspace path");
  const std::string draft = "draft";
  expect(!reader->Replace(reinterpret_cast<const unsigned char*>(draft.data()),
      draft.size(), &error), "save followed a replaced symbolic parent");
  auto outside = agentcodi::WorkspaceFileReader::Open(
      private_directory.string(), "payload.bin", 1024, &error);
  expect(outside != nullptr && read_all(outside.get()) == "outside",
      "failed save changed the outside file");
}

void saves_complete_revisions_atomically_with_permissions() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  make_directory(workspace / "nested");
  const auto file = workspace / "nested" / "script.sh";
  write_file(file, "original");
  expect(chmod(file.c_str(), 0750) == 0, "could not set script permissions");
  std::string error;
  auto reader = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "nested/script.sh", 1024, &error);
  expect(reader != nullptr, error);
  const std::string replacement = "#!/system/bin/sh\necho changed\n";
  expect(reader->Replace(reinterpret_cast<const unsigned char*>(replacement.data()),
      replacement.size(), &error), error);
  expect(read_all(reader.get()) == "original", "save truncated the original inode in place");
  auto saved = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "nested/script.sh", 1024, &error);
  expect(saved != nullptr && read_all(saved.get()) == replacement,
      "save did not install the complete replacement");
  struct stat attributes {};
  expect(stat(file.c_str(), &attributes) == 0 && (attributes.st_mode & 0777) == 0750,
      "save did not preserve executable permissions");
  expect(saved->Replace(nullptr, 0, &error), error);
  expect(std::filesystem::file_size(file) == 0U, "save could not empty the file");
  for (const auto& entry : std::filesystem::directory_iterator(workspace / "nested")) {
    expect(entry.path().filename() == "script.sh", "save left a temporary file behind");
  }
}

void rejects_changed_revision_and_oversized_save() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  write_file(workspace / "code.txt", "original");
  std::string error;
  auto reader = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "code.txt", 8, &error);
  expect(reader != nullptr, error);
  const std::string too_large = "oversized";
  expect(!reader->Replace(reinterpret_cast<const unsigned char*>(too_large.data()),
      too_large.size(), &error), "save ignored its byte limit");
  expect(reader->VerifyUnchanged(&error), "rejected save changed the original");
  expect(rename((workspace / "code.txt").c_str(), (workspace / "old.txt").c_str()) == 0,
      "could not replace external revision");
  write_file(workspace / "code.txt", "external");
  const std::string draft = "draft";
  expect(!reader->Replace(reinterpret_cast<const unsigned char*>(draft.data()),
      draft.size(), &error), "save overwrote an external replacement");
  auto external = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "code.txt", 8, &error);
  expect(external != nullptr && read_all(external.get()) == "external",
      "failed save altered the external revision");
}

void failed_write_keeps_original_and_cleans_temporary_file() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  write_file(workspace / "code.txt", "original");
  std::string error;
  auto reader = agentcodi::WorkspaceFileReader::Open(
      workspace.string(), "code.txt", 1024, &error);
  expect(reader != nullptr, error);
  const std::string draft = "draft";
  bool saved;
  {
    ScopedWriteFailure failure;
    saved = reader->Replace(reinterpret_cast<const unsigned char*>(draft.data()),
        draft.size(), &error);
  }
  expect(!saved, "save ignored a real write failure");
  expect(reader->VerifyUnchanged(&error), "failed save changed the original revision");
  for (const auto& entry : std::filesystem::directory_iterator(workspace)) {
    expect(entry.path().filename() == "code.txt", "failed save left a temporary file");
  }
}

void expect_no_pending_files(const std::filesystem::path& directory) {
  for (const auto& entry : std::filesystem::directory_iterator(directory)) {
    expect(entry.path().filename().string().rfind(".agentcodi-edit-", 0) != 0,
        "creation left a pending file behind");
  }
}

void creates_valid_filename_matching_first_temporary_candidate() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  const std::string name = ".agentcodi-edit-" + std::to_string(getpid()) + "-0";
  std::string error;
  expect(agentcodi::WorkspaceFileReader::Create(workspace.string(), name, nullptr, 0, 1024, &error)
      == agentcodi::WorkspaceFileCreationResult::Created,
      "valid filename collided with the creator's own temporary file");
  expect(std::filesystem::exists(workspace / name), "valid hidden file disappeared after creation");
  expect(std::distance(std::filesystem::directory_iterator(workspace),
      std::filesystem::directory_iterator()) == 1, "creation left its own temporary file behind");
}

void creates_complete_unicode_and_empty_files_in_selected_directory() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  make_directory(workspace / "project");
  const std::string text = "print('Grüße 世界')\n";
  std::string error;
  expect(agentcodi::WorkspaceFileReader::Create(workspace.string(), "project/新文件 🚀.py",
      reinterpret_cast<const unsigned char*>(text.data()), text.size(), 1024, &error)
      == agentcodi::WorkspaceFileCreationResult::Created, error);
  auto created = agentcodi::WorkspaceFileReader::Open(workspace.string(), "project/新文件 🚀.py", 1024, &error);
  expect(created != nullptr && read_all(created.get()) == text, "new Unicode file was not saved completely");
  struct stat attributes {};
  expect(stat((workspace / "project" / "新文件 🚀.py").c_str(), &attributes) == 0
      && (attributes.st_mode & 0777) == 0600 && attributes.st_nlink == 1,
      "new file permissions or link count are wrong");
  expect(agentcodi::WorkspaceFileReader::Create(workspace.string(), "empty.txt", nullptr, 0, 1024, &error)
      == agentcodi::WorkspaceFileCreationResult::Created, error);
  expect(std::filesystem::file_size(workspace / "empty.txt") == 0, "empty new file was not created");
  expect_no_pending_files(workspace);
  expect_no_pending_files(workspace / "project");
}

void creation_never_replaces_existing_entries_or_follows_links() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  const auto outside = temporary.path() / "outside";
  make_directory(workspace);
  make_directory(outside);
  write_file(workspace / "existing.txt", "original");
  write_file(outside / "original.txt", "outside");
  make_directory(workspace / "folder");
  expect(symlink((outside / "original.txt").c_str(), (workspace / "link.txt").c_str()) == 0,
      "could not create existing symlink");
  expect(symlink((outside / "missing.txt").c_str(), (workspace / "dangling.txt").c_str()) == 0,
      "could not create dangling symlink");
  expect(link((outside / "original.txt").c_str(), (workspace / "hard.txt").c_str()) == 0,
      "could not create existing hard link");
  expect(symlink(outside.c_str(), (workspace / "linked-parent").c_str()) == 0,
      "could not create linked parent");
  const std::string draft = "draft";
  std::string error;
  for (const char* raw_path : {"existing.txt", "folder", "link.txt", "dangling.txt", "hard.txt"}) {
    const std::string path(raw_path);
    expect(agentcodi::WorkspaceFileReader::Create(workspace.string(), path,
        reinterpret_cast<const unsigned char*>(draft.data()), draft.size(), 1024, &error)
        == agentcodi::WorkspaceFileCreationResult::AlreadyExists,
        "creation replaced an existing destination: " + path);
  }
  for (const char* raw_path : {"../escape.txt", "/absolute.txt", "./new.txt", "a/../new.txt",
      "linked-parent/escape.txt", "missing/new.txt"}) {
    const std::string path(raw_path);
    expect(agentcodi::WorkspaceFileReader::Create(workspace.string(), path,
        reinterpret_cast<const unsigned char*>(draft.data()), draft.size(), 1024, &error)
        == agentcodi::WorkspaceFileCreationResult::Failed,
        "creation accepted an unsafe destination: " + path);
  }
  auto original = agentcodi::WorkspaceFileReader::Open(workspace.string(), "existing.txt", 1024, &error);
  expect(original != nullptr && read_all(original.get()) == "original", "creation changed the existing file");
  expect(!std::filesystem::exists(outside / "escape.txt")
      && !std::filesystem::exists(outside / "missing.txt"), "creation escaped the workspace");
  expect_no_pending_files(workspace);
}

void failed_creation_keeps_folder_clean_and_enforces_limits() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  std::string error;
  const std::string text = "oversized";
  expect(agentcodi::WorkspaceFileReader::Create(workspace.string(), "new.txt",
      reinterpret_cast<const unsigned char*>(text.data()), text.size(), 8, &error)
      == agentcodi::WorkspaceFileCreationResult::Failed, "creation ignored its byte limit");
  agentcodi::WorkspaceFileCreationResult result;
  {
    ScopedWriteFailure failure;
    result = agentcodi::WorkspaceFileReader::Create(workspace.string(), "new.txt",
        reinterpret_cast<const unsigned char*>(text.data()), text.size(), 1024, &error);
  }
  expect(result == agentcodi::WorkspaceFileCreationResult::Failed, "creation ignored a real write failure");
  expect(!std::filesystem::exists(workspace / "new.txt"), "failed creation left a partial destination");
  expect_no_pending_files(workspace);
}

void concurrent_creations_have_one_winner_without_overwrite() {
  TemporaryDirectory temporary;
  const auto workspace = temporary.path() / "workspace";
  make_directory(workspace);
  const std::string first(64 * 1024, 'a');
  const std::string second(64 * 1024, 'b');
  for (int attempt = 0; attempt < 8; ++attempt) {
    const std::string name = "race-" + std::to_string(attempt) + ".txt";
    std::atomic<int> ready{0};
    std::atomic<bool> start{false};
    agentcodi::WorkspaceFileCreationResult results[2] = {
        agentcodi::WorkspaceFileCreationResult::Failed, agentcodi::WorkspaceFileCreationResult::Failed};
    std::string errors[2];
    auto create = [&](int index, const std::string& contents) {
      ready.fetch_add(1);
      while (!start.load()) { std::this_thread::yield(); }
      results[index] = agentcodi::WorkspaceFileReader::Create(workspace.string(), name,
          reinterpret_cast<const unsigned char*>(contents.data()), contents.size(), 1024 * 1024, &errors[index]);
    };
    std::thread a(create, 0, std::cref(first));
    std::thread b(create, 1, std::cref(second));
    while (ready.load() != 2) { std::this_thread::yield(); }
    start.store(true);
    a.join();
    b.join();
    const int winner = results[0] == agentcodi::WorkspaceFileCreationResult::Created ? 0 : 1;
    expect(results[winner] == agentcodi::WorkspaceFileCreationResult::Created, errors[winner]);
    expect(results[1 - winner] == agentcodi::WorkspaceFileCreationResult::AlreadyExists,
        "concurrent creation did not protect the first saved file");
    std::string error;
    auto saved = agentcodi::WorkspaceFileReader::Open(workspace.string(), name, 1024 * 1024, &error);
    expect(saved != nullptr && read_all(saved.get()) == (winner == 0 ? first : second),
        "losing creator changed the winner's contents");
  }
  expect_no_pending_files(workspace);
}

}  // namespace

int main() {
  try {
    creates_valid_filename_matching_first_temporary_candidate();
    reads_nested_regular_file();
    rejects_final_symbolic_link();
    rejects_symbolic_parent();
    rejects_hard_link();
    keeps_open_descriptor_inside_original_workspace_tree();
    saves_complete_revisions_atomically_with_permissions();
    rejects_changed_revision_and_oversized_save();
    failed_write_keeps_original_and_cleans_temporary_file();
    creates_complete_unicode_and_empty_files_in_selected_directory();
    creation_never_replaces_existing_entries_or_follows_links();
    failed_creation_keeps_folder_clean_and_enforces_limits();
    concurrent_creations_have_one_winner_without_overwrite();
    std::cout << "Workspace file reader tests passed." << std::endl;
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "Workspace file reader test failed: " << error.what() << std::endl;
    return 1;
  }
}
