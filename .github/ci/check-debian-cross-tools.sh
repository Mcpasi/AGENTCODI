#!/usr/bin/env bash
# Check that Debian LLVM can link Android ELFs, patch their dependencies and
# inject the real ARM64 attestor using a native Linux utility.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
source "$SCRIPT_DIR/../../scripts/fdroid/host-env.sh"
toolchain="$AGENTCODI_ASSEMBLY_NDK/toolchains/llvm/prebuilt/linux-x86_64"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
cat > "$temporary/main.cpp" <<'CPP'
#include <string>
int main(int argc, char** argv) { return std::string(argv[0]).size() < unsigned(argc); }
CPP
# The SDK libc++ here is only a compiler test fixture. The APK assembler links
# against the separately verified, source-built runtime instead.
"$AGENTCODI_CLANGXX" --target=aarch64-linux-android29 \
  -nostdinc++ -isystem"$toolchain/sysroot/usr/include/c++/v1" \
  -L"$toolchain/sysroot/usr/lib/aarch64-linux-android" \
  -std=c++17 -O2 -Wall -Wextra -Werror "$temporary/main.cpp" -o "$temporary/target"
"$AGENTCODI_LLVM_STRIP" --strip-unneeded "$temporary/target"
patchelf --page-size 16384 --add-needed libagentcodi-node-guard.so "$temporary/target"
"$AGENTCODI_CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$AGENTCODI_PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$AGENTCODI_PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector.cpp" \
  "$AGENTCODI_PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector_main.cpp" \
  -o "$temporary/injector"
"$AGENTCODI_CLANGXX" --target=aarch64-linux-android29 \
  -std=c++17 -Os -Wall -Wextra -Werror -ffreestanding -fno-builtin \
  -fno-exceptions -fno-rtti -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -fno-stack-protector -fvisibility=hidden -fPIE \
  '-DAGENTCODI_EXPECTED_EXECUTABLE="libnode.so"' \
  '-DAGENTCODI_EXPECTED_GUARD="libagentcodi-node-guard.so"' \
  -c "$AGENTCODI_PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_payload.cpp" \
  -o "$temporary/attestor.o"
"$AGENTCODI_LD_LLD" -m aarch64elf -nostdlib -static \
  -T "$AGENTCODI_PROJECT_ROOT/scripts/toolchain_elf_attestor_payload.ld" \
  -o "$temporary/attestor.elf" "$temporary/attestor.o"
"$AGENTCODI_LLVM_OBJCOPY" -O binary "$temporary/attestor.elf" "$temporary/attestor.bin"
"$temporary/injector" "$temporary/target" "$temporary/attestor.bin" "$temporary/attested"
readelf -h "$temporary/attested" | grep -F 'AArch64'
readelf -l "$temporary/attested" | grep -F '/system/bin/linker64'
readelf -d "$temporary/attested" | grep -F 'Shared library: [libc++_shared.so]'
readelf -d "$temporary/attested" | grep -F 'Shared library: [libagentcodi-node-guard.so]'
readelf -lW "$temporary/attested" | awk \
  '$1 == "LOAD" { seen = 1; if ($NF != "0x4000") bad = 1 } END { exit (!seen || bad) }'
echo "Debian host utilities and Android cross-linking passed."
