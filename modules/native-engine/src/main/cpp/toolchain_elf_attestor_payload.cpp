#include <cstddef>
#include <cstdint>

#include <asm/unistd.h>
#include <sys/stat.h>

#ifndef AGENTCODI_EXPECTED_EXECUTABLE
#error "AGENTCODI_EXPECTED_EXECUTABLE is required"
#endif

#ifndef AGENTCODI_EXPECTED_GUARD
#error "AGENTCODI_EXPECTED_GUARD is required"
#endif

namespace {

constexpr long kAtCurrentWorkingDirectory = -100;
constexpr long kOpenReadOnly = 0;
constexpr long kOpenCloseOnExec = 02000000;
constexpr long kOpenNoFollow = 0400000;
constexpr std::size_t kMaximumExecutablePathBytes = 4096U;
constexpr std::size_t kMaximumMapsLineBytes = 8192U;
constexpr std::size_t kMaximumMapsBytes = 1024U * 1024U;
constexpr char kExpectedExecutable[] = AGENTCODI_EXPECTED_EXECUTABLE;
constexpr char kExpectedGuard[] = AGENTCODI_EXPECTED_GUARD;
constexpr char kExecutableLink[] = "/proc/self/exe";
constexpr char kMapsPath[] = "/proc/self/maps";
constexpr char kFailureMessage[] =
    "Guarded tool rejected an untrusted policy library\n";

struct AttestorConfiguration {
  unsigned char magic[16];
  std::uint64_t original_entry;
  std::uint64_t injected_virtual_address;
};

static_assert(offsetof(AttestorConfiguration, original_entry) == 16U,
              "attestor original-entry offset");
static_assert(
    offsetof(AttestorConfiguration, injected_virtual_address) == 24U,
    "attestor virtual-address offset");

inline long raw_syscall1(long number, long first) {
  register long x0 __asm__("x0") = first;
  register long x8 __asm__("x8") = number;
  __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8) : "memory");
  return x0;
}

inline long raw_syscall2(long number, long first, long second) {
  register long x0 __asm__("x0") = first;
  register long x1 __asm__("x1") = second;
  register long x8 __asm__("x8") = number;
  __asm__ volatile(
      "svc 0"
      : "+r"(x0)
      : "r"(x1), "r"(x8)
      : "memory");
  return x0;
}

inline long raw_syscall3(long number, long first, long second, long third) {
  register long x0 __asm__("x0") = first;
  register long x1 __asm__("x1") = second;
  register long x2 __asm__("x2") = third;
  register long x8 __asm__("x8") = number;
  __asm__ volatile(
      "svc 0"
      : "+r"(x0)
      : "r"(x1), "r"(x2), "r"(x8)
      : "memory");
  return x0;
}

inline long raw_syscall4(
    long number,
    long first,
    long second,
    long third,
    long fourth) {
  register long x0 __asm__("x0") = first;
  register long x1 __asm__("x1") = second;
  register long x2 __asm__("x2") = third;
  register long x3 __asm__("x3") = fourth;
  register long x8 __asm__("x8") = number;
  __asm__ volatile(
      "svc 0"
      : "+r"(x0)
      : "r"(x1), "r"(x2), "r"(x3), "r"(x8)
      : "memory");
  return x0;
}

bool equal_bytes(
    const char* left,
    const char* right,
    std::size_t length) {
  for (std::size_t index = 0U; index < length; ++index) {
    if (left[index] != right[index]) {
      return false;
    }
  }
  return true;
}

void write_failure() {
  std::size_t offset = 0U;
  constexpr std::size_t length = sizeof(kFailureMessage) - 1U;
  while (offset < length) {
    const long count = raw_syscall3(
        __NR_write,
        2,
        reinterpret_cast<long>(kFailureMessage + offset),
        static_cast<long>(length - offset));
    if (count <= 0) {
      return;
    }
    offset += static_cast<std::size_t>(count);
  }
}

bool prepare_expected_guard_path(
    char* expected_path,
    std::size_t* expected_length) {
  const long path_length = raw_syscall4(
      __NR_readlinkat,
      kAtCurrentWorkingDirectory,
      reinterpret_cast<long>(kExecutableLink),
      reinterpret_cast<long>(expected_path),
      static_cast<long>(kMaximumExecutablePathBytes - 1U));
  if (path_length <= 0
      || static_cast<std::size_t>(path_length)
          >= kMaximumExecutablePathBytes) {
    return false;
  }
  const std::size_t length = static_cast<std::size_t>(path_length);
  std::size_t separator = length;
  while (separator > 0U && expected_path[separator - 1U] != '/') {
    --separator;
  }
  constexpr std::size_t executable_length = sizeof(kExpectedExecutable) - 1U;
  constexpr std::size_t guard_length = sizeof(kExpectedGuard) - 1U;
  if (separator == 0U
      || length - separator != executable_length
      || !equal_bytes(
          expected_path + separator,
          kExpectedExecutable,
          executable_length)
      || separator + guard_length >= kMaximumExecutablePathBytes) {
    return false;
  }
  for (std::size_t index = 0U; index < guard_length; ++index) {
    expected_path[separator + index] = kExpectedGuard[index];
  }
  expected_path[separator + guard_length] = '\0';
  *expected_length = separator + guard_length;
  return true;
}

bool skip_token(
    const char* line,
    std::size_t line_length,
    std::size_t* position) {
  while (*position < line_length && line[*position] == ' ') {
    ++*position;
  }
  const std::size_t begin = *position;
  while (*position < line_length && line[*position] != ' ') {
    ++*position;
  }
  return *position > begin;
}

bool parse_number(
    const char* line,
    std::size_t line_length,
    std::size_t* position,
    unsigned base,
    char delimiter,
    std::uint64_t* result) {
  std::uint64_t value = 0U;
  std::size_t digits = 0U;
  while (*position < line_length && line[*position] != delimiter) {
    const char character = line[*position];
    unsigned digit = 0U;
    if (character >= '0' && character <= '9') {
      digit = static_cast<unsigned>(character - '0');
    } else if (base == 16U && character >= 'a' && character <= 'f') {
      digit = static_cast<unsigned>(character - 'a') + 10U;
    } else if (base == 16U && character >= 'A' && character <= 'F') {
      digit = static_cast<unsigned>(character - 'A') + 10U;
    } else {
      return false;
    }
    if (digit >= base
        || value > (UINT64_MAX - digit) / base) {
      return false;
    }
    value = value * base + digit;
    ++*position;
    ++digits;
  }
  if (digits == 0U || *position >= line_length
      || line[*position] != delimiter) {
    return false;
  }
  ++*position;
  *result = value;
  return true;
}

std::uint64_t device_major(std::uint64_t device) {
  return ((device >> 8U) & UINT64_C(0xfff))
      | ((device >> 32U) & ~UINT64_C(0xfff));
}

std::uint64_t device_minor(std::uint64_t device) {
  return (device & UINT64_C(0xff))
      | ((device >> 12U) & ~UINT64_C(0xff));
}

bool line_maps_expected_guard(
    const char* line,
    std::size_t line_length,
    const char* expected_path,
    std::size_t expected_length,
    std::uint64_t expected_device,
    std::uint64_t expected_inode) {
  std::size_t position = 0U;
  if (!skip_token(line, line_length, &position)
      || !skip_token(line, line_length, &position)) {
    return false;
  }
  const std::size_t permissions_end = position;
  std::size_t permissions_begin = permissions_end;
  while (permissions_begin > 0U && line[permissions_begin - 1U] != ' ') {
    --permissions_begin;
  }
  if (permissions_end - permissions_begin < 3U
      || line[permissions_begin + 2U] != 'x'
      || !skip_token(line, line_length, &position)) {
    return false;
  }
  while (position < line_length && line[position] == ' ') {
    ++position;
  }
  std::uint64_t mapped_major = 0U;
  std::uint64_t mapped_minor = 0U;
  std::uint64_t mapped_inode = 0U;
  if (!parse_number(
          line,
          line_length,
          &position,
          16U,
          ':',
          &mapped_major)
      || !parse_number(
          line,
          line_length,
          &position,
          16U,
          ' ',
          &mapped_minor)) {
    return false;
  }
  while (position < line_length && line[position] == ' ') {
    ++position;
  }
  if (!parse_number(
          line,
          line_length,
          &position,
          10U,
          ' ',
          &mapped_inode)) {
    return false;
  }
  while (position < line_length && line[position] == ' ') {
    ++position;
  }
  if (mapped_major != device_major(expected_device)
      || mapped_minor != device_minor(expected_device)
      || mapped_inode != expected_inode
      || line_length - position < expected_length) {
    return false;
  }
  return equal_bytes(
      line + line_length - expected_length,
      expected_path,
      expected_length);
}

bool genuine_guard_is_mapped(
    const char* expected_path,
    std::size_t expected_length,
    std::uint64_t expected_device,
    std::uint64_t expected_inode) {
  const long descriptor = raw_syscall4(
      __NR_openat,
      kAtCurrentWorkingDirectory,
      reinterpret_cast<long>(kMapsPath),
      kOpenReadOnly | kOpenCloseOnExec,
      0);
  if (descriptor < 0) {
    return false;
  }
  char input[4096];
  char line[kMaximumMapsLineBytes];
  std::size_t line_length = 0U;
  std::size_t total = 0U;
  bool matched = false;
  bool valid = true;
  for (;;) {
    const long count = raw_syscall3(
        __NR_read,
        descriptor,
        reinterpret_cast<long>(input),
        static_cast<long>(sizeof(input)));
    if (count < 0) {
      valid = false;
      break;
    }
    if (count == 0) {
      if (line_length != 0U) {
        matched = line_maps_expected_guard(
            line,
            line_length,
            expected_path,
            expected_length,
            expected_device,
            expected_inode);
      }
      break;
    }
    total += static_cast<std::size_t>(count);
    if (total > kMaximumMapsBytes) {
      valid = false;
      break;
    }
    for (long index = 0; index < count; ++index) {
      const char value = input[index];
      if (value == '\n') {
        if (line_maps_expected_guard(
                line,
                line_length,
                expected_path,
                expected_length,
                expected_device,
                expected_inode)) {
          matched = true;
          break;
        }
        line_length = 0U;
      } else if (line_length < sizeof(line)) {
        line[line_length++] = value;
      } else {
        valid = false;
        break;
      }
    }
    if (matched || !valid) {
      break;
    }
  }
  raw_syscall1(__NR_close, descriptor);
  return valid && matched;
}

}  // namespace

extern "C" __attribute__((used, visibility("hidden"),
                           section(".agentcodi_config")))
const AttestorConfiguration AgentCodiElfAttestorConfig = {
    {'A', 'G', 'E', 'N', 'T', 'C', 'O', 'D',
     'I', '-', 'A', 'T', 'T', 'E', 'S', 'T'},
    UINT64_C(0x1122334455667788),
    UINT64_C(0x8877665544332211),
};

extern "C" __attribute__((used, visibility("hidden")))
int AgentCodiElfAttestorCheck() {
  char expected_path[kMaximumExecutablePathBytes];
  std::size_t expected_length = 0U;
  if (!prepare_expected_guard_path(expected_path, &expected_length)) {
    write_failure();
    return 1;
  }
  const long guard = raw_syscall4(
      __NR_openat,
      kAtCurrentWorkingDirectory,
      reinterpret_cast<long>(expected_path),
      kOpenReadOnly | kOpenCloseOnExec | kOpenNoFollow,
      0);
  struct stat metadata {};
  const bool safe_guard = guard >= 0
      && raw_syscall2(
          __NR_fstat,
          guard,
          reinterpret_cast<long>(&metadata)) == 0
      && (metadata.st_mode & 0170000) == 0100000
      && metadata.st_nlink == 1;
  if (guard >= 0) {
    raw_syscall1(__NR_close, guard);
  }
  if (safe_guard
      && genuine_guard_is_mapped(
          expected_path,
          expected_length,
          static_cast<std::uint64_t>(metadata.st_dev),
          static_cast<std::uint64_t>(metadata.st_ino))) {
    return 0;
  }
  write_failure();
  return 1;
}

extern "C" __attribute__((naked, used, visibility("hidden"),
                           section(".text.agentcodi_entry")))
void AgentCodiElfAttestorEntry() {
  __asm__ volatile(
      "sub sp, sp, #256\n"
      "stp x0, x1, [sp, #0]\n"
      "stp x2, x3, [sp, #16]\n"
      "stp x4, x5, [sp, #32]\n"
      "stp x6, x7, [sp, #48]\n"
      "stp x8, x9, [sp, #64]\n"
      "stp x10, x11, [sp, #80]\n"
      "stp x12, x13, [sp, #96]\n"
      "stp x14, x15, [sp, #112]\n"
      "stp x16, x17, [sp, #128]\n"
      "stp x18, x19, [sp, #144]\n"
      "stp x20, x21, [sp, #160]\n"
      "stp x22, x23, [sp, #176]\n"
      "stp x24, x25, [sp, #192]\n"
      "stp x26, x27, [sp, #208]\n"
      "stp x28, x29, [sp, #224]\n"
      "str x30, [sp, #240]\n"
      "bl AgentCodiElfAttestorCheck\n"
      "cbz w0, 1f\n"
      "mov x0, #126\n"
      "mov x8, #94\n"
      "svc #0\n"
      "brk #0\n"
      "1:\n"
      "adr x16, AgentCodiElfAttestorEntry\n"
      "adr x17, AgentCodiElfAttestorConfig\n"
      "ldr x9, [x17, #16]\n"
      "ldr x10, [x17, #24]\n"
      "sub x16, x16, x10\n"
      "add x16, x16, x9\n"
      "str x16, [sp, #248]\n"
      "ldp x0, x1, [sp, #0]\n"
      "ldp x2, x3, [sp, #16]\n"
      "ldp x4, x5, [sp, #32]\n"
      "ldp x6, x7, [sp, #48]\n"
      "ldp x8, x9, [sp, #64]\n"
      "ldp x10, x11, [sp, #80]\n"
      "ldp x12, x13, [sp, #96]\n"
      "ldp x14, x15, [sp, #112]\n"
      "ldr x17, [sp, #136]\n"
      "ldp x18, x19, [sp, #144]\n"
      "ldp x20, x21, [sp, #160]\n"
      "ldp x22, x23, [sp, #176]\n"
      "ldp x24, x25, [sp, #192]\n"
      "ldp x26, x27, [sp, #208]\n"
      "ldp x28, x29, [sp, #224]\n"
      "ldr x30, [sp, #240]\n"
      "ldr x16, [sp, #248]\n"
      "add sp, sp, #256\n"
      "br x16\n");
}
