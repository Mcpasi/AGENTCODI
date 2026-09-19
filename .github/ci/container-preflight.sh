#!/usr/bin/env bash
# Checks whether the current environment satisfies what scripts/build-debug-apk.sh
# requires, without building anything.
#
# This exists because the Termux container is assembled on a hosted runner and
# the first attempts will be missing packages. Rather than discovering that
# through a failing build, this names every missing piece at once. The required
# command list and the pinned toolchain version are read out of the build
# script itself, so they cannot drift.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
BUILD_SCRIPT="$PROJECT_ROOT/scripts/build-debug-apk.sh"

if [ ! -r "$BUILD_SCRIPT" ]; then
  echo "Cannot read the build script: $BUILD_SCRIPT" >&2
  exit 1
fi

missing=0

echo "== Required commands =="
required="$(sed -n 's/^for command_name in \(.*\); do$/\1/p' "$BUILD_SCRIPT" | head -1)"
if [ -z "$required" ]; then
  echo "Could not read the required command list from the build script." >&2
  exit 1
fi
for command_name in $required; do
  if command -v "$command_name" >/dev/null 2>&1; then
    printf '  ok      %s\n' "$command_name"
  else
    printf '  MISSING %s\n' "$command_name"
    missing=$((missing + 1))
  fi
done

echo
echo "== Java 17 =="
java_home="${AGENTCODI_JAVA_HOME:-$(sed -n 's/^JAVA_HOME_17="${AGENTCODI_JAVA_HOME:-\(.*\)}"$/\1/p' "$BUILD_SCRIPT" | head -1)}"
for java_tool in java javac jar keytool; do
  if [ -x "$java_home/bin/$java_tool" ]; then
    printf '  ok      %s/bin/%s\n' "$java_home" "$java_tool"
  else
    printf '  MISSING %s/bin/%s\n' "$java_home" "$java_tool"
    missing=$((missing + 1))
  fi
done
if [ -x "$java_home/bin/javac" ]; then
  printf '  version %s\n' "$("$java_home/bin/javac" -version 2>&1)"
fi

echo
echo "== Pinned LLVM toolchain =="
expected="$(sed -n 's/^CLANG_TOOLCHAIN_VERSION="\(.*\)"$/\1/p' "$BUILD_SCRIPT" | head -1)"
if [ -z "$expected" ]; then
  echo "  note    the build script does not pin a toolchain version"
else
  printf '  pinned  %s\n' "$expected"
  prefix="${AGENTCODI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
  for tool_name in clang++ llvm-strip ld.lld llvm-objcopy; do
    tool_path="$prefix/bin/$tool_name"
    case "$tool_name" in
      clang++) tool_path="${AGENTCODI_CLANGXX:-$tool_path}" ;;
      llvm-strip) tool_path="${AGENTCODI_LLVM_STRIP:-$tool_path}" ;;
      ld.lld) tool_path="${AGENTCODI_LD_LLD:-$tool_path}" ;;
      llvm-objcopy) tool_path="${AGENTCODI_LLVM_OBJCOPY:-$tool_path}" ;;
    esac
    if [ ! -x "$tool_path" ]; then
      printf '  MISSING %s\n' "$tool_path"
      missing=$((missing + 1))
      continue
    fi
    found="$("$tool_path" --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
    if [ "$found" = "$expected" ]; then
      printf '  ok      %-14s %s\n' "$tool_name" "$found"
    else
      printf '  WRONG   %-14s %s (need %s)\n' "$tool_name" "${found:-none}" "$expected"
      missing=$((missing + 1))
    fi
  done
fi

echo
echo "== Environment =="
printf '  arch    %s\n' "$(uname -m)"
printf '  shell   %s\n' "$([ -x /system/bin/sh ] && echo '/system/bin/sh present' || echo '/system/bin/sh MISSING')"
printf '  linker  %s\n' "$([ -e /system/bin/linker64 ] && echo 'present' || echo 'MISSING')"

echo
echo "-----"
if [ "$missing" -ne 0 ]; then
  echo "$missing requirement(s) not satisfied." >&2
  exit 1
fi
echo "The environment satisfies every build requirement."
