#!/usr/bin/env bash
# Cross-compile CI-only support; package API 29 and APK contents are unchanged.
set -euo pipefail
output="$(realpath "$1")"
ndk_dir="/home/builder/lib/android-ndk-r30"
test "$(uname -m)" = x86_64
grep -q '^Pkg.Revision = 30\.' "$ndk_dir/source.properties"
cc="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"
"$cc" -Oz -fPIC -shared -Wl,--no-undefined \
    -Wl,-soname,libagentcodi-ci-api29.so \
    -Wl,--version-script,/audit/.github/ci/bootstrap-api29-compat.map \
    /audit/.github/ci/bootstrap-api29-compat.c -o "$output/libagentcodi-ci-api29.so"
"$cc" -Oz -fno-builtin-reallocarray \
    /audit/.github/ci/bootstrap-api29-compat-test.c \
    -L"$output" -lagentcodi-ci-api29 -Wl,-rpath,/audit/ci-compat \
    -o "$output/bootstrap-api29-compat-test"
cp /audit/.github/ci/bootstrap-api29-compat.c \
    /audit/.github/ci/bootstrap-api29-compat.map \
    /audit/.github/ci/bootstrap-api29-compat-test.c "$output/"
cp "$ndk_dir/source.properties" "$output/ndk-source.properties"
cd "$output"
sha256sum *.c *.map *.so bootstrap-api29-compat-test ndk-source.properties > SHA256SUMS
