#!/usr/bin/env bash
# CI-only driver for the C++ host tests that can run on a hosted Linux runner.
#
# scripts/test.sh stays the authoritative local runner. It builds every C++
# target with the Termux Android toolchain, which a hosted runner does not
# have, so this script rebuilds the portable subset with a stock system
# compiler (g++ by default) and glibc. The test sources themselves are used
# unmodified. Nothing is written into the working tree.
#
# Not covered here, on purpose:
#   * the toolchain ELF guard/attestor chain (toolchain_elf_guard_test.cpp).
#     Its attestor payload is hand-written aarch64 syscall assembly that needs
#     __attribute__((naked)), clang, ld.lld and llvm-objcopy, and the injected
#     entry segment only loads on an aarch64 target.
#   * tests/cpp/android_app_server_bootstrap_smoke.cpp, which scripts/test.sh
#     does not run either: scripts/build-debug-apk.sh drives it against the
#     packaged Codex runtime binaries, which are not part of the repository.
# See .github/ci/README.md.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
NATIVE="$PROJECT_ROOT/modules/native-engine/src/main/cpp"
TESTS="$PROJECT_ROOT/tests/cpp"
BUILD_DIR="${AGENTCODI_CI_BUILD_DIR:-${RUNNER_TEMP:-/tmp}/agentcodi-ci-cpp}"
CXX="${CXX:-g++}"

if ! command -v "$CXX" >/dev/null 2>&1; then
  echo "A C++17 compiler is required (set CXX)." >&2
  exit 1
fi
if [ ! -e /system/bin/sh ] || [ ! -d /system/lib64 ]; then
  echo "The supervisor tests need /system/bin/sh and /system/lib64." >&2
  echo "Run .github/ci/setup-system-shim.sh first." >&2
  exit 1
fi

# The supervisor is handed a host library directory to use as the native
# payload read grant, and the engine test reads the first LD_LIBRARY_PATH entry.
HOST_LIB_DIR="/usr/lib/$("$CXX" -dumpmachine)"
[ -d "$HOST_LIB_DIR" ] || HOST_LIB_DIR="/usr/lib"

echo "Compiler: $("$CXX" --version | head -1)"
echo "Host library directory: $HOST_LIB_DIR"

# The build directory is removed below, so refuse obviously unsafe values.
case "$BUILD_DIR" in
  ''|/|/*/..|"$PROJECT_ROOT"|"$PROJECT_ROOT"/|"${HOME:-/nonexistent}"|"${HOME:-/nonexistent}"/)
    echo "Refusing unsafe build directory: $BUILD_DIR" >&2
    exit 1
    ;;
  /*) ;;
  *)
    echo "The build directory must be an absolute path: $BUILD_DIR" >&2
    exit 1
    ;;
esac

rm -rf -- "$BUILD_DIR"
mkdir -p "$BUILD_DIR/fixtures"

# Standard CXXFLAGS/LDFLAGS are honoured so a runner whose zlib headers or
# libraries sit outside the default search paths can still build the suite.
read -ra EXTRA_CXXFLAGS <<< "${CXXFLAGS:-}"
read -ra EXTRA_LDFLAGS <<< "${LDFLAGS:-}"
CXXFLAGS_MIN=(-std=c++17 -O2 -Wall -Wextra -Werror
  ${EXTRA_CXXFLAGS[@]+"${EXTRA_CXXFLAGS[@]}"}
  ${EXTRA_LDFLAGS[@]+"${EXTRA_LDFLAGS[@]}"})
CXXFLAGS_BASE=("${CXXFLAGS_MIN[@]}" -pthread -I"$NATIVE")

# The engine test routes toolchain commands through a real shell fixture and
# expects the packaged tool binaries next to it, mirroring scripts/test.sh.
prepare_fixtures() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/toolchain_shell_main.cpp" \
    "$NATIVE/toolchain_policy.cpp" \
    "$NATIVE/ripgrep_bridge_policy.cpp" \
    -o "$BUILD_DIR/fixtures/libagentcodi-shell.so"
  local tool
  for tool in libnode.so libpython-bin.so libripgrep.so; do
    cp -- /system/bin/sh "$BUILD_DIR/fixtures/$tool"
    chmod 755 "$BUILD_DIR/fixtures/$tool"
  done
}

suite_bootstrap_terminal() {
  "$CXX" "${CXXFLAGS_MIN[@]}" \
    "$TESTS/bootstrap_terminal_test.cpp" -o "$BUILD_DIR/bootstrap-terminal-test" \
    && "$BUILD_DIR/bootstrap-terminal-test"
}

suite_ripgrep_bridge_policy() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/ripgrep_bridge_policy.cpp" \
    "$TESTS/ripgrep_bridge_policy_test.cpp" \
    -o "$BUILD_DIR/ripgrep-bridge-policy-test" \
    && "$BUILD_DIR/ripgrep-bridge-policy-test"
}

suite_workspace_file_reader() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/workspace_file_reader.cpp" \
    "$TESTS/workspace_file_reader_test.cpp" \
    -o "$BUILD_DIR/workspace-file-reader-test" \
    && "$BUILD_DIR/workspace-file-reader-test"
}

suite_workspace_directory_reader() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/workspace_directory_reader.cpp" \
    "$TESTS/workspace_directory_reader_test.cpp" \
    -o "$BUILD_DIR/workspace-directory-reader-test" \
    && "$BUILD_DIR/workspace-directory-reader-test"
}

suite_workspace_import_installer() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/workspace_import_installer.cpp" \
    "$TESTS/workspace_import_installer_test.cpp" \
    -o "$BUILD_DIR/workspace-import-installer-test" \
    && "$BUILD_DIR/workspace-import-installer-test"
}

suite_runtime_stop() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/app_server_process.cpp" \
    "$NATIVE/png_validator.cpp" \
    "$NATIVE/sha256.cpp" \
    "$TESTS/runtime_stop_test.cpp" \
    -lz -o "$BUILD_DIR/runtime-stop-test" \
    && env LD_LIBRARY_PATH="$HOST_LIB_DIR" \
      "$BUILD_DIR/runtime-stop-test" "$HOST_LIB_DIR"
}

suite_runtime_framing() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/app_server_process.cpp" \
    "$NATIVE/png_validator.cpp" \
    "$NATIVE/sha256.cpp" \
    "$TESTS/runtime_framing_test.cpp" \
    -lz -o "$BUILD_DIR/runtime-framing-test" \
    && env LD_LIBRARY_PATH="$HOST_LIB_DIR" \
      "$BUILD_DIR/runtime-framing-test" "$HOST_LIB_DIR"
}

suite_agentcodi_engine() {
  "$CXX" "${CXXFLAGS_BASE[@]}" \
    "$NATIVE/agentcodi_engine.cpp" \
    "$NATIVE/app_server_process.cpp" \
    "$NATIVE/png_validator.cpp" \
    "$NATIVE/sha256.cpp" \
    "$TESTS/agentcodi_engine_test.cpp" \
    -lz -o "$BUILD_DIR/agentcodi-engine-test" \
    && env LD_LIBRARY_PATH="$HOST_LIB_DIR" \
      "$BUILD_DIR/agentcodi-engine-test" \
      "$BUILD_DIR/fixtures/libagentcodi-shell.so"
}

SUITES=(
  bootstrap_terminal
  ripgrep_bridge_policy
  workspace_file_reader
  workspace_directory_reader
  workspace_import_installer
  runtime_stop
  runtime_framing
  agentcodi_engine
)

echo "Preparing toolchain fixtures."
prepare_fixtures

passed=()
failed=()
for suite in "${SUITES[@]}"; do
  echo
  echo "==> $suite"
  if "suite_$suite"; then
    passed+=("$suite")
  else
    failed+=("$suite")
    echo "!!! $suite FAILED" >&2
  fi
done

echo
echo "Passed ${#passed[@]} of ${#SUITES[@]} C++ suites."
if [ "${#failed[@]}" -ne 0 ]; then
  printf 'Failed: %s\n' "${failed[*]}" >&2
  exit 1
fi
echo "All portable C++ host tests passed."
