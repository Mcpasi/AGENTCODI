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
prefix="${AGENTCODI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"

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
echo "== Text encoding =="
printf '  LANG=%s LC_ALL=%s\n' "${LANG:-<unset>}" "${LC_ALL:-<unset>}"
if [ -x "$java_home/bin/java" ]; then
  jnu="$("$java_home/bin/java" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/.*sun\.jnu\.encoding = //p' | head -1)"
  case "$jnu" in
    UTF-8|utf-8|UTF8)
      printf '  ok      sun.jnu.encoding %s\n' "$jnu"
      ;;
    *)
      # The workspace browser tests create a file whose name holds an emoji.
      # Without a UTF-8 locale the JVM cannot encode that path at all.
      printf '  WRONG   sun.jnu.encoding %s (need UTF-8; set LANG/LC_ALL to C.UTF-8)\n' \
        "${jnu:-unknown}"
      missing=$((missing + 1))
      ;;
  esac
fi

echo
echo "== Pinned LLVM toolchain =="
expected="$(sed -n 's/^CLANG_TOOLCHAIN_VERSION="\(.*\)"$/\1/p' "$BUILD_SCRIPT" | head -1)"
if [ -z "$expected" ]; then
  echo "  note    the build script does not pin a toolchain version"
else
  printf '  pinned  %s\n' "$expected"
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
echo "== Android linker =="
# tests/cpp/toolchain_elf_guard_test.cpp asserts that invoking a guarded tool
# manually through the dynamic linker cannot bypass the guard. That guard reads
# /proc/self/exe and compares its basename against the expected tool name, so
# the assertion only holds when a manual linker invocation leaves /proc/self/exe
# pointing at the linker. This is a property of the specific Android linker, so
# report what this environment actually does rather than assume it matches a
# device. Informational: it does not fail the preflight.
linker="/system/bin/linker64"
if [ ! -e "$linker" ]; then
  printf '  note    %s is absent\n' "$linker"
else
  printf '  path    %s -> %s\n' "$linker" "$(readlink -f "$linker" 2>/dev/null || echo '?')"
  probe=""
  for candidate in "$prefix/bin/readlink" /usr/bin/readlink; do
    if [ -x "$candidate" ]; then probe="$candidate"; break; fi
  done
  if [ -z "$probe" ]; then
    printf '  note    no readlink available to probe the linker\n'
  else
    observed="$("$linker" "$probe" /proc/self/exe 2>&1)"
    status=$?
    printf '  probe   %s %s /proc/self/exe -> exit %s\n' "$linker" "$probe" "$status"
    printf '  result  %s\n' "${observed:-<no output>}"
    case "$observed" in
      */linker64)
        printf '  ok      a manual invocation is visible as the linker; the guard can reject it\n'
        ;;
      *)
        printf '  DIFFERS this environment does not expose a manual invocation as the linker,\n'
        printf '          so the guard test expectation cannot hold here\n'
        ;;
    esac
  fi
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
