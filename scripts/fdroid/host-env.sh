#!/usr/bin/env bash
# Source this file from the Debian preparation and assembly entry points.
AGENTCODI_PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
export LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
export RUSTUP_HOME="${RUSTUP_HOME:-$AGENTCODI_PROJECT_ROOT/.cache/runtime-source/rustup}"
export CARGO_HOME="${CARGO_HOME:-$AGENTCODI_PROJECT_ROOT/.cache/cargo}"
export V8_SYSTEM_LIBCLANG_DIR="${V8_SYSTEM_LIBCLANG_DIR:-/usr/lib/llvm-21/lib}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}/ndk/28.2.13676358}"
export AGENTCODI_ASSEMBLY_NDK="${AGENTCODI_ASSEMBLY_NDK:-$AGENTCODI_PROJECT_ROOT/.build/runtime-source/tools/android-ndk-r29}"
export AGENTCODI_APK_HOST=linux-x86_64
export AGENTCODI_CLANGXX="$AGENTCODI_PROJECT_ROOT/scripts/fdroid/cross-clang++.sh"
export AGENTCODI_LLVM_STRIP=/usr/bin/llvm-strip-21
export AGENTCODI_LD_LLD=/usr/bin/ld.lld-21
export AGENTCODI_LLVM_OBJCOPY=/usr/bin/llvm-objcopy-21
export AGENTCODI_JAVA_HOME="$(dirname -- "$(dirname -- "$(readlink -f -- "$(command -v javac)")")")"
