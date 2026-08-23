#include "toolchain_elf_attestor_injector.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include <elf.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace agentcodi {
namespace {

constexpr std::uint64_t kPageAlignment = 16384U;
constexpr std::size_t kMaximumInputBytes = 512U * 1024U * 1024U;
constexpr std::size_t kMaximumPayloadBytes = 64U * 1024U;
constexpr unsigned char kConfigurationMagic[] = {
    'A', 'G', 'E', 'N', 'T', 'C', 'O', 'D',
    'I', '-', 'A', 'T', 'T', 'E', 'S', 'T',
};

std::string errno_message(const char* operation, int error_number) {
  return std::string(operation) + ": " + std::strerror(error_number);
}

bool checked_add(
    std::uint64_t left,
    std::uint64_t right,
    std::uint64_t* result) {
  if (result == nullptr
      || right > std::numeric_limits<std::uint64_t>::max() - left) {
    return false;
  }
  *result = left + right;
  return true;
}

bool align_up(
    std::uint64_t value,
    std::uint64_t alignment,
    std::uint64_t* result) {
  if (alignment == 0U || (alignment & (alignment - 1U)) != 0U) {
    return false;
  }
  const std::uint64_t remainder = value & (alignment - 1U);
  return remainder == 0U
      ? (*result = value, true)
      : checked_add(value, alignment - remainder, result);
}

bool read_bounded_file(
    const std::string& path,
    std::size_t maximum_bytes,
    std::vector<unsigned char>* bytes,
    std::string* error) {
  const int descriptor = open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
  if (descriptor < 0) {
    *error = errno_message("ELF attestor input open", errno);
    return false;
  }
  struct stat metadata {};
  if (fstat(descriptor, &metadata) != 0) {
    const int saved_errno = errno;
    close(descriptor);
    *error = errno_message("ELF attestor input metadata", saved_errno);
    return false;
  }
  if (!S_ISREG(metadata.st_mode)
      || metadata.st_size < 1
      || static_cast<std::uint64_t>(metadata.st_size) > maximum_bytes) {
    close(descriptor);
    *error = "ELF attestor input has invalid metadata";
    return false;
  }
  std::vector<unsigned char> value(
      static_cast<std::size_t>(metadata.st_size));
  std::size_t offset = 0U;
  while (offset < value.size()) {
    const ssize_t count = read(
        descriptor,
        value.data() + offset,
        value.size() - offset);
    if (count > 0) {
      offset += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      const int saved_errno = errno;
      close(descriptor);
      *error = errno_message("ELF attestor input read", saved_errno);
      return false;
    }
  }
  if (close(descriptor) != 0) {
    *error = errno_message("ELF attestor input close", errno);
    return false;
  }
  bytes->swap(value);
  return true;
}

bool range_inside(
    std::uint64_t offset,
    std::uint64_t length,
    std::uint64_t outer_offset,
    std::uint64_t outer_length) {
  std::uint64_t end = 0U;
  std::uint64_t outer_end = 0U;
  return checked_add(offset, length, &end)
      && checked_add(outer_offset, outer_length, &outer_end)
      && offset >= outer_offset
      && end <= outer_end;
}

bool patch_configuration(
    std::vector<unsigned char>* payload,
    std::uint64_t original_entry,
    std::uint64_t injected_virtual_address,
    std::string* error) {
  std::size_t match = payload->size();
  std::size_t matches = 0U;
  for (std::size_t index = 0U;
       index + sizeof(kConfigurationMagic) + 16U <= payload->size();
       ++index) {
    if (std::memcmp(
            payload->data() + index,
            kConfigurationMagic,
            sizeof(kConfigurationMagic)) == 0) {
      match = index;
      ++matches;
    }
  }
  if (matches != 1U) {
    *error = "ELF attestor payload configuration is missing or ambiguous";
    return false;
  }
  std::memcpy(
      payload->data() + match + sizeof(kConfigurationMagic),
      &original_entry,
      sizeof(original_entry));
  std::memcpy(
      payload->data() + match + sizeof(kConfigurationMagic)
          + sizeof(original_entry),
      &injected_virtual_address,
      sizeof(injected_virtual_address));
  return true;
}

bool write_exclusive_file(
    const std::string& path,
    const std::vector<unsigned char>& bytes,
    mode_t mode,
    std::string* error) {
  const int descriptor = open(
      path.c_str(),
      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW,
      mode & 0777);
  if (descriptor < 0) {
    *error = errno_message("ELF attestor output open", errno);
    return false;
  }
  std::size_t offset = 0U;
  while (offset < bytes.size()) {
    const ssize_t count = write(
        descriptor,
        bytes.data() + offset,
        bytes.size() - offset);
    if (count > 0) {
      offset += static_cast<std::size_t>(count);
    } else if (count == -1 && errno == EINTR) {
      continue;
    } else {
      const int saved_errno = errno;
      close(descriptor);
      unlink(path.c_str());
      *error = errno_message("ELF attestor output write", saved_errno);
      return false;
    }
  }
  int saved_errno = 0;
  if (fsync(descriptor) != 0) {
    saved_errno = errno;
  }
  if (close(descriptor) != 0 && saved_errno == 0) {
    saved_errno = errno;
  }
  if (saved_errno != 0) {
    unlink(path.c_str());
    *error = errno_message("ELF attestor output commit", saved_errno);
    return false;
  }
  return true;
}

}  // namespace

bool InjectToolchainElfAttestor(
    const std::string& input_path,
    const std::string& payload_path,
    const std::string& output_path,
    std::string* error) {
  if (input_path.empty() || payload_path.empty() || output_path.empty()
      || input_path == output_path || error == nullptr) {
    return false;
  }
  error->clear();
  std::vector<unsigned char> elf;
  std::vector<unsigned char> payload;
  if (!read_bounded_file(
          input_path,
          kMaximumInputBytes,
          &elf,
          error)
      || !read_bounded_file(
          payload_path,
          kMaximumPayloadBytes,
          &payload,
          error)) {
    return false;
  }
  if (elf.size() < sizeof(Elf64_Ehdr)) {
    *error = "ELF attestor input header is truncated";
    return false;
  }
  auto* header = reinterpret_cast<Elf64_Ehdr*>(elf.data());
  if (std::memcmp(header->e_ident, ELFMAG, SELFMAG) != 0
      || header->e_ident[EI_CLASS] != ELFCLASS64
      || header->e_ident[EI_DATA] != ELFDATA2LSB
      || header->e_ident[EI_VERSION] != EV_CURRENT
      || header->e_machine != EM_AARCH64
      || header->e_type != ET_DYN
      || header->e_version != EV_CURRENT
      || header->e_ehsize != sizeof(Elf64_Ehdr)
      || header->e_phentsize != sizeof(Elf64_Phdr)
      || header->e_phnum < 1U) {
    *error = "ELF attestor requires a little-endian Android ARM64 PIE";
    return false;
  }
  std::uint64_t program_headers_end = 0U;
  if (!checked_add(
          header->e_phoff,
          static_cast<std::uint64_t>(header->e_phnum)
              * sizeof(Elf64_Phdr),
          &program_headers_end)
      || program_headers_end > elf.size()) {
    *error = "ELF attestor program-header table is truncated";
    return false;
  }
  auto* program_headers = reinterpret_cast<Elf64_Phdr*>(
      elf.data() + header->e_phoff);
  Elf64_Phdr* reusable_note = nullptr;
  std::uint64_t maximum_load_end = 0U;
  bool entry_is_executable = false;
  for (std::size_t index = 0U; index < header->e_phnum; ++index) {
    Elf64_Phdr& program = program_headers[index];
    if (program.p_type == PT_LOAD) {
      std::uint64_t load_end = 0U;
      if (program.p_align != kPageAlignment
          || !checked_add(program.p_vaddr, program.p_memsz, &load_end)) {
        *error = "ELF attestor input has an invalid LOAD contract";
        return false;
      }
      if (load_end > maximum_load_end) {
        maximum_load_end = load_end;
      }
      if ((program.p_flags & PF_X) != 0
          && header->e_entry >= program.p_vaddr
          && header->e_entry < load_end) {
        entry_is_executable = true;
      }
    }
  }
  if (!entry_is_executable) {
    *error = "ELF attestor input entry point is not executable";
    return false;
  }
  for (std::size_t index = 0U; index < header->e_phnum; ++index) {
    Elf64_Phdr& candidate = program_headers[index];
    if (candidate.p_type != PT_NOTE || candidate.p_filesz == 0U) {
      continue;
    }
    bool covered = false;
    for (std::size_t load_index = 0U;
         load_index < header->e_phnum;
         ++load_index) {
      const Elf64_Phdr& load = program_headers[load_index];
      if (load.p_type == PT_LOAD
          && range_inside(
              candidate.p_offset,
              candidate.p_filesz,
              load.p_offset,
              load.p_filesz)) {
        covered = true;
        break;
      }
    }
    if (covered) {
      reusable_note = &candidate;
      break;
    }
  }
  if (reusable_note == nullptr) {
    *error = "ELF attestor input has no safely reusable Android note slot";
    return false;
  }
  const std::size_t reusable_index = static_cast<std::size_t>(
      reusable_note - program_headers);
  std::uint64_t payload_offset = 0U;
  std::uint64_t payload_virtual_address = 0U;
  if (!align_up(elf.size(), kPageAlignment, &payload_offset)
      || !align_up(maximum_load_end, kPageAlignment, &payload_virtual_address)
      || payload_offset > kMaximumInputBytes
      || payload_virtual_address == 0U
      || payload_virtual_address
          > std::numeric_limits<std::uint64_t>::max() - payload.size()) {
    *error = "ELF attestor segment layout overflowed";
    return false;
  }
  const std::uint64_t original_entry = header->e_entry;
  if (!patch_configuration(
          &payload,
          original_entry,
          payload_virtual_address,
          error)) {
    return false;
  }
  if (payload_offset > std::numeric_limits<std::size_t>::max()
      || payload_offset + payload.size() > kMaximumInputBytes) {
    *error = "ELF attestor output exceeds its size bound";
    return false;
  }
  elf.resize(static_cast<std::size_t>(payload_offset), 0U);
  elf.insert(elf.end(), payload.begin(), payload.end());

  // resize() may move the vector, so resolve the header table again.
  header = reinterpret_cast<Elf64_Ehdr*>(elf.data());
  program_headers = reinterpret_cast<Elf64_Phdr*>(elf.data() + header->e_phoff);
  if (reusable_index >= header->e_phnum) {
    *error = "ELF attestor note index changed unexpectedly";
    return false;
  }
  Elf64_Phdr& attestor_load = program_headers[reusable_index];
  attestor_load.p_type = PT_LOAD;
  attestor_load.p_flags = PF_R | PF_X;
  attestor_load.p_offset = payload_offset;
  attestor_load.p_vaddr = payload_virtual_address;
  attestor_load.p_paddr = payload_virtual_address;
  attestor_load.p_filesz = payload.size();
  attestor_load.p_memsz = payload.size();
  attestor_load.p_align = kPageAlignment;
  header->e_entry = payload_virtual_address;

  struct stat input_metadata {};
  if (stat(input_path.c_str(), &input_metadata) != 0
      || !S_ISREG(input_metadata.st_mode)) {
    *error = errno_message("ELF attestor source metadata", errno);
    return false;
  }
  return write_exclusive_file(
      output_path,
      elf,
      input_metadata.st_mode,
      error);
}

}  // namespace agentcodi
