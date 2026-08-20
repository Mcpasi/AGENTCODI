#include "workspace_file_reader.h"

#include <cerrno>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
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
}

}  // namespace

int main() {
  try {
    reads_nested_regular_file();
    rejects_final_symbolic_link();
    rejects_symbolic_parent();
    rejects_hard_link();
    keeps_open_descriptor_inside_original_workspace_tree();
    std::cout << "Workspace file reader tests passed." << std::endl;
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "Workspace file reader test failed: " << error.what() << std::endl;
    return 1;
  }
}
