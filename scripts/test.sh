#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
JAVA_HOME_17="${AGENTCODI_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
JAVAC="$JAVA_HOME_17/bin/javac"
JAVA="$JAVA_HOME_17/bin/java"
TERMUX_PREFIX="${AGENTCODI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
CLANGXX="${AGENTCODI_CLANGXX:-$TERMUX_PREFIX/bin/clang++}"
LD_LLD="${AGENTCODI_LD_LLD:-$TERMUX_PREFIX/bin/ld.lld}"
LLVM_OBJCOPY="${AGENTCODI_LLVM_OBJCOPY:-$TERMUX_PREFIX/bin/llvm-objcopy}"
TEST_BUILD="$PROJECT_ROOT/.build/tests"

if [ ! -x "$JAVAC" ] || [ ! -x "$JAVA" ] || [ ! -x "$CLANGXX" ] \
    || [ ! -x "$LD_LLD" ] || [ ! -x "$LLVM_OBJCOPY" ]; then
  echo "Required Java 17 or Android LLVM tool is missing." >&2
  exit 1
fi

case "$TEST_BUILD" in
  "$PROJECT_ROOT"/.build/tests) rm -rf -- "$TEST_BUILD" ;;
  *) echo "Refusing unsafe test cleanup." >&2; exit 1 ;;
esac
mkdir -p "$TEST_BUILD/java-classes" "$TEST_BUILD/cpp"

"$SCRIPT_DIR/check-architecture.sh"

find \
  "$PROJECT_ROOT/modules/core/src/main/java" \
  "$PROJECT_ROOT/modules/review-mode/src/main/java" \
  "$PROJECT_ROOT/modules/protected-mode/src/main/java" \
  "$PROJECT_ROOT/modules/compatibility-mode/src/main/java" \
  "$PROJECT_ROOT/modules/storage/src/main/java" \
  "$PROJECT_ROOT/modules/import-contracts/src/main/java" \
  "$PROJECT_ROOT/modules/import-client/src/main/java" \
  "$PROJECT_ROOT/modules/mcp-contracts/src/main/java" \
  "$PROJECT_ROOT/modules/mcp-client/src/main/java" \
  "$PROJECT_ROOT/tests/java" \
  -type f -name '*.java' -print | sort > "$TEST_BUILD/java-sources.txt"

"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$TEST_BUILD/java-classes" @"$TEST_BUILD/java-sources.txt"
"$JAVA" -cp "$TEST_BUILD/java-classes" de.agentcodi.tests.TestMain

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_shell_main.cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_policy.cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/ripgrep_bridge_policy.cpp" \
  -o "$TEST_BUILD/cpp/libagentcodi-shell.so"
cp /system/bin/sh "$TEST_BUILD/cpp/libnode.so"
cp /system/bin/sh "$TEST_BUILD/cpp/libpython-bin.so"
cp /system/bin/sh "$TEST_BUILD/cpp/libripgrep.so"

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/ripgrep_bridge_policy.cpp" \
  "$PROJECT_ROOT/tests/cpp/ripgrep_bridge_policy_test.cpp" \
  -o "$TEST_BUILD/cpp/ripgrep-bridge-policy-test"
"$TEST_BUILD/cpp/ripgrep-bridge-policy-test"

mkdir -p "$TEST_BUILD/cpp/guard-fixtures" "$TEST_BUILD/cpp/fake-guards"
"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector.cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector_main.cpp" \
  -o "$TEST_BUILD/cpp/toolchain-elf-attestor-injector"
for guard_spec in \
    '1:node:libagentcodi-node-guard.so:libnode.so' \
    '2:python:libagentcodi-python-guard.so:libpython-bin.so' \
    '3:ripgrep:libagentcodi-ripgrep-guard.so:libripgrep.so'; do
  guard_kind="${guard_spec%%:*}"
  guard_remainder="${guard_spec#*:}"
  guard_label="${guard_remainder%%:*}"
  guard_remainder="${guard_remainder#*:}"
  guard_library="${guard_remainder%%:*}"
  guard_executable="${guard_remainder#*:}"
  "$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
    -DAGENTCODI_GUARDED_TOOL="$guard_kind" \
    -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_guard.cpp" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_policy.cpp" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/ripgrep_bridge_policy.cpp" \
    -Wl,-soname,"$guard_library" \
    -o "$TEST_BUILD/cpp/guard-fixtures/$guard_library"
  "$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror \
    "$PROJECT_ROOT/tests/cpp/toolchain_guard_fixture_main.cpp" \
    -L"$TEST_BUILD/cpp/guard-fixtures" \
    -Wl,--no-as-needed -Wl,-l:"$guard_library" \
    -o "$TEST_BUILD/cpp/guard-fixtures/$guard_executable"
  "$CLANGXX" --target=aarch64-linux-android29 -std=c++17 -Os \
    -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-exceptions \
    -fno-rtti -fno-unwind-tables -fno-asynchronous-unwind-tables \
    -fno-stack-protector -fvisibility=hidden -fPIE \
    "-DAGENTCODI_EXPECTED_EXECUTABLE=\"$guard_executable\"" \
    "-DAGENTCODI_EXPECTED_GUARD=\"$guard_library\"" \
    -c "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_payload.cpp" \
    -o "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.o"
  "$LD_LLD" -m aarch64elf -nostdlib -static \
    -T "$PROJECT_ROOT/scripts/toolchain_elf_attestor_payload.ld" \
    -o "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.elf" \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.o"
  if [ "$(readelf -h "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.elf" \
      | awk '/Entry point address:/ {print $4}')" != "0x0" ] \
      || ! readelf -rW "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.elf" \
        | grep -Fq 'There are no relocations in this file.'; then
    echo "The $guard_label ELF attestor is not a relocation-free entry payload." >&2
    exit 1
  fi
  "$LLVM_OBJCOPY" -O binary \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.elf" \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.bin"
  "$TEST_BUILD/cpp/toolchain-elf-attestor-injector" \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_executable" \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_label-attestor.bin" \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_executable.attested"
  mv -f \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_executable.attested" \
    "$TEST_BUILD/cpp/guard-fixtures/$guard_executable"
  chmod 755 "$TEST_BUILD/cpp/guard-fixtures/$guard_executable"
  attested_entry="$(readelf -h \
      "$TEST_BUILD/cpp/guard-fixtures/$guard_executable" \
      | awk '/Entry point address:/ {print $4}')"
  if ! grep -aFq \
        'Guarded tool rejected an untrusted policy library' \
        "$TEST_BUILD/cpp/guard-fixtures/$guard_executable" \
      || ! grep -aFq \
        'AGENTCODI-ATTEST' \
        "$TEST_BUILD/cpp/guard-fixtures/$guard_executable" \
      || ! readelf -lW \
        "$TEST_BUILD/cpp/guard-fixtures/$guard_executable" \
        | awk -v entry="$attested_entry" \
          'function canonical_hex(value) { \
             sub(/^0x0+/, "0x", value); \
             return value == "0x" ? "0x0" : tolower(value); \
           } \
           $1 == "LOAD" \
              && canonical_hex($3) == canonical_hex(entry) \
              && $(NF - 2) == "R" \
              && $(NF - 1) == "E" && $NF == "0x4000" { found = 1 } \
           END { exit !found }'; then
    echo "The guarded $guard_label ELF fixture lacks its attestor entry segment." >&2
    exit 1
  fi
  "$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -fPIC -shared \
    "$PROJECT_ROOT/tests/cpp/toolchain_fake_guard.cpp" \
    -Wl,-soname,"$guard_library" \
    -o "$TEST_BUILD/cpp/fake-guards/$guard_library"
  if [ ! -x "$TEST_BUILD/cpp/guard-fixtures/$guard_executable" ]; then
    echo "Failed to build the guarded $guard_label ELF fixture." >&2
    exit 1
  fi
done

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror \
  "$PROJECT_ROOT/tests/cpp/toolchain_elf_guard_test.cpp" \
  -o "$TEST_BUILD/cpp/toolchain-elf-guard-test"
env LD_LIBRARY_PATH="$TEST_BUILD/cpp/guard-fixtures:$TERMUX_PREFIX/lib" \
  "$TEST_BUILD/cpp/toolchain-elf-guard-test" \
  "$TEST_BUILD/cpp/guard-fixtures/libnode.so" \
  "$TEST_BUILD/cpp/guard-fixtures/libpython-bin.so" \
  "$TEST_BUILD/cpp/guard-fixtures/libripgrep.so" \
  "$TEST_BUILD/cpp/fake-guards"

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -pthread -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/agentcodi_engine.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/png_validator.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/sha256.cpp" "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" -lz -o "$TEST_BUILD/cpp/agentcodi-engine-test"

env LD_LIBRARY_PATH="$TERMUX_PREFIX/lib" "$TEST_BUILD/cpp/agentcodi-engine-test" \
  "$TEST_BUILD/cpp/libagentcodi-shell.so"

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_file_reader.cpp" \
  "$PROJECT_ROOT/tests/cpp/workspace_file_reader_test.cpp" \
  -o "$TEST_BUILD/cpp/workspace-file-reader-test"
"$TEST_BUILD/cpp/workspace-file-reader-test"

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_import_installer.cpp" \
  "$PROJECT_ROOT/tests/cpp/workspace_import_installer_test.cpp" \
  -o "$TEST_BUILD/cpp/workspace-import-installer-test"
"$TEST_BUILD/cpp/workspace-import-installer-test"

echo "All host tests passed."
