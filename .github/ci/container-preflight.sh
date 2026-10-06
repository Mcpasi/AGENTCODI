#!/usr/bin/env bash
# Checks whether the current environment satisfies what scripts/build-debug-apk.sh
# requires, without assembling an APK. A disposable native probe checks the
# compiler, link inputs and Bionic execution.
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
  javac_version="$("$java_home/bin/javac" -version 2>&1)"
  printf '  version %s\n' "$javac_version"
  if [[ "$javac_version" != javac\ 17.* ]]; then
    printf '  WRONG   need Java 17\n'
    missing=$((missing + 1))
  fi
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
  echo "  MISSING pinned toolchain version"
  missing=$((missing + 1))
else
  printf '  pinned  %s\n' "$expected"
  for tool_name in clang++ llvm-strip ld.lld; do
    tool_path="$prefix/bin/$tool_name"
    case "$tool_name" in
      clang++) tool_path="${AGENTCODI_CLANGXX:-$tool_path}" ;;
      llvm-strip) tool_path="${AGENTCODI_LLVM_STRIP:-$tool_path}" ;;
      ld.lld) tool_path="${AGENTCODI_LD_LLD:-$tool_path}" ;;
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
if [ "$(uname -m)" != aarch64 ]; then
  printf '  WRONG   architecture %s (need aarch64)\n' "$(uname -m)"
  missing=$((missing + 1))
else
  printf '  ok      architecture aarch64\n'
fi
# The supervisor canonicalizes the system shell with realpath and compares the
# result against the literal /system/bin/sh, so /system must be a real directory
# rather than a symlink into a prefix.
if [ ! -x /system/bin/sh ]; then
  printf '  MISSING /system/bin/sh\n'
  missing=$((missing + 1))
elif [ "$(readlink -f /system/bin/sh)" != "/system/bin/sh" ]; then
  printf '  WRONG   /system/bin/sh resolves to %s\n' "$(readlink -f /system/bin/sh)"
  printf '          it must resolve to itself; /system may not be a symlink\n'
  missing=$((missing + 1))
else
  printf '  ok      /system/bin/sh is canonical\n'
fi
if [ -x /system/bin/linker64 ]; then
  printf '  ok      executable Android linker64\n'
else
  printf '  MISSING executable /system/bin/linker64\n'
  missing=$((missing + 1))
fi
# Binaries built by the pinned toolchain carry a DT_RUNPATH into the Termux
# prefix and resolve libc++_shared.so there.
if [ -f "$prefix/lib/libc++_shared.so" ]; then
  printf '  libc++  present in the Termux prefix\n'
else
  printf '  MISSING %s/lib/libc++_shared.so\n' "$prefix"
  missing=$((missing + 1))
fi
# Bionic reads its library namespace configuration here.
if [ -f /linkerconfig/ld.config.txt ]; then
  printf '  linkercfg ld.config.txt present (%s bytes)\n' \
    "$(wc -c < /linkerconfig/ld.config.txt)"
else
  printf '  note    /linkerconfig/ld.config.txt absent — bionic uses its built-in\n'
  printf '          namespace configuration whose permitted paths may exclude the\n'
  printf '          native library directory\n'
fi


echo
echo "== Native engine/shell prerequisites =="
# Compile and execute a disposable Bionic probe against the same JNI headers,
# minimum API, C++ runtime and zlib used by the APK and its test driver.
clangxx="${AGENTCODI_CLANGXX:-$prefix/bin/clang++}"
min_sdk="$(sed -n 's/^MIN_SDK="\(.*\)"$/\1/p' "$BUILD_SCRIPT" | head -1)"
if [ "$missing" -eq 0 ]; then
  probe_dir="$(mktemp -d)"
  trap 'rm -rf -- "$probe_dir"' EXIT
  cat > "$probe_dir/probe.cpp" <<'CPP'
#include <jni.h>
#include <android/log.h>
#include <zlib.h>
#include <string>
int main() {
  std::string version = zlibVersion();
  return version.empty() || sizeof(jlong) != 8;
}
CPP
  if ! "$clangxx" --target=aarch64-linux-android"$min_sdk" -std=c++17 \
      -I"$java_home/include" -I"$java_home/include/linux" \
      "$probe_dir/probe.cpp" -lz -llog -o "$probe_dir/probe"; then
    printf '  MISSING usable Android headers/CRT, JNI, libc++ or zlib link inputs\n'
    missing=$((missing + 1))
  elif ! timeout 15 env LD_LIBRARY_PATH="$prefix/lib" "$probe_dir/probe"; then
    printf '  WRONG   Bionic probe cannot execute with the native build libraries\n'
    missing=$((missing + 1))
  else
    printf '  ok      API %s C++/JNI/zlib compilation and Bionic execution\n' "$min_sdk"
  fi
fi

echo
echo "-----"
if [ "$missing" -ne 0 ]; then
  echo "$missing requirement(s) not satisfied." >&2
  exit 1
fi
echo "The environment satisfies every build requirement."
