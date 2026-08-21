#include "workspace_import_installer.h"

#include <atomic>
#include <cerrno>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {

constexpr const char* kToken = "0123456789abcdef0123456789abcdef";
constexpr const char* kPendingName =
    ".pending-0123456789abcdef0123456789abcdef";
constexpr const char* kFinalName =
    "0123456789abcdef0123456789abcdef.bin";

void expect(bool condition, const std::string& message) {
  if (!condition) {
    throw std::runtime_error(message);
  }
}

class TemporaryDirectory final {
 public:
  TemporaryDirectory() {
    std::vector<char> pattern;
    const std::string value = "/tmp/agentcodi-import-installer-XXXXXX";
    pattern.assign(value.begin(), value.end());
    pattern.push_back('\0');
    char* created = mkdtemp(pattern.data());
    if (created == nullptr) {
      throw std::runtime_error("could not create installer test directory");
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
    throw std::runtime_error("could not create installer test directory");
  }
}

bool write_exclusive(
    const std::filesystem::path& path,
    const std::string& contents) {
  int descriptor;
  do {
    descriptor = open(
        path.c_str(),
        O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
        0600);
  } while (descriptor < 0 && errno == EINTR);
  if (descriptor < 0) {
    if (errno == EEXIST) {
      return false;
    }
    throw std::runtime_error("could not create installer test file");
  }
  std::size_t offset = 0U;
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
      throw std::runtime_error("could not write installer test file");
    }
    offset += static_cast<std::size_t>(count);
  }
  if (close(descriptor) != 0) {
    throw std::runtime_error("could not close installer test file");
  }
  return true;
}

std::string read_file(const std::filesystem::path& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) {
    throw std::runtime_error("could not read installer test file");
  }
  return std::string(
      std::istreambuf_iterator<char>(input),
      std::istreambuf_iterator<char>());
}

bool exists_without_following(const std::filesystem::path& path) {
  struct stat attributes {};
  return lstat(path.c_str(), &attributes) == 0;
}

std::filesystem::path create_workspace(TemporaryDirectory* temporary) {
  const std::filesystem::path workspace = temporary->path() / "workspace";
  make_directory(workspace);
  make_directory(workspace / "imports");
  return workspace;
}

void installs_completed_pending_file() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = create_workspace(&temporary);
  const std::filesystem::path imports = workspace / "imports";
  write_exclusive(imports / kPendingName, "source-bytes");

  std::string error;
  expect(
      agentcodi::InstallWorkspaceImportNoReplace(
          workspace.string(),
          kPendingName,
          kFinalName,
          12,
          &error),
      error);
  expect(
      !exists_without_following(imports / kPendingName),
      "successful installation left the pending name visible");
  expect(
      read_file(imports / kFinalName) == "source-bytes",
      "successful installation changed imported bytes");
}

void preserves_existing_target_on_collision() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = create_workspace(&temporary);
  const std::filesystem::path imports = workspace / "imports";
  write_exclusive(imports / kPendingName, "source-bytes");
  write_exclusive(imports / kFinalName, "competing-bytes");

  std::string error;
  expect(
      !agentcodi::InstallWorkspaceImportNoReplace(
          workspace.string(),
          kPendingName,
          kFinalName,
          12,
          &error),
      "installer replaced a target that already existed");
  expect(
      error.find("already exists") != std::string::npos,
      "target collision did not report the bounded collision error");
  expect(
      read_file(imports / kPendingName) == "source-bytes",
      "target collision changed the pending import");
  expect(
      read_file(imports / kFinalName) == "competing-bytes",
      "target collision overwrote the competing entry");
}

void never_overwrites_a_parallel_creator() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = create_workspace(&temporary);
  const std::filesystem::path imports = workspace / "imports";

  for (int iteration = 0; iteration < 64; ++iteration) {
    write_exclusive(imports / kPendingName, "source-bytes");
    std::atomic<bool> start(false);
    bool installed = false;
    bool competing_created = false;
    std::string install_error;
    std::thread installer([&]() {
      while (!start.load(std::memory_order_acquire)) {
        std::this_thread::yield();
      }
      installed = agentcodi::InstallWorkspaceImportNoReplace(
          workspace.string(),
          kPendingName,
          kFinalName,
          12,
          &install_error);
    });
    std::thread competitor([&]() {
      while (!start.load(std::memory_order_acquire)) {
        std::this_thread::yield();
      }
      competing_created = write_exclusive(
          imports / kFinalName,
          "competing-bytes");
    });
    start.store(true, std::memory_order_release);
    installer.join();
    competitor.join();

    expect(
        installed != competing_created,
        "parallel install did not have exactly one atomic winner");
    if (installed) {
      expect(
          read_file(imports / kFinalName) == "source-bytes",
          "parallel creator changed a successfully installed import");
      expect(
          !exists_without_following(imports / kPendingName),
          "successful parallel install retained its pending name");
    } else {
      expect(
          install_error.find("already exists") != std::string::npos,
          "lost parallel install did not report a target collision");
      expect(
          read_file(imports / kFinalName) == "competing-bytes",
          "lost parallel install overwrote the winning competitor");
      expect(
          read_file(imports / kPendingName) == "source-bytes",
          "lost parallel install changed its pending source");
      expect(
          unlink((imports / kPendingName).c_str()) == 0,
          "could not clean losing pending race fixture");
    }
    expect(
        unlink((imports / kFinalName).c_str()) == 0,
        "could not clean final race fixture");
  }
}

void rejects_mismatched_random_names() {
  TemporaryDirectory temporary;
  const std::filesystem::path workspace = create_workspace(&temporary);
  const std::filesystem::path imports = workspace / "imports";
  write_exclusive(imports / kPendingName, "source-bytes");
  const std::string different_final =
      "fedcba9876543210fedcba9876543210.bin";

  std::string error;
  expect(
      !agentcodi::InstallWorkspaceImportNoReplace(
          workspace.string(),
          kPendingName,
          different_final,
          12,
          &error),
      "installer accepted different pending and final random tokens");
  expect(
      read_file(imports / kPendingName) == "source-bytes",
      "invalid installation request changed its pending source");
  expect(
      !exists_without_following(imports / different_final),
      "invalid installation request created a final entry");
}

}  // namespace

int main() {
  try {
    expect(std::string(kToken).size() == 32U, "test token has the wrong size");
    installs_completed_pending_file();
    preserves_existing_target_on_collision();
    never_overwrites_a_parallel_creator();
    rejects_mismatched_random_names();
    std::cout << "Workspace import installer tests passed." << std::endl;
    return 0;
  } catch (const std::exception& error) {
    std::cerr << "Workspace import installer test failed: "
              << error.what() << std::endl;
    return 1;
  }
}
