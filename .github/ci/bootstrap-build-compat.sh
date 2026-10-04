#!/usr/bin/env bash
# Cross-compile CI-only support; package API 29 and APK contents are unchanged.
set -euo pipefail
output="$(realpath "$1")"
ndk_dir="/home/builder/lib/android-ndk-r30"
test "$(uname -m)" = x86_64
grep -q '^Pkg.Revision = 30\.' "$ndk_dir/source.properties"
ndk_bin="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
cc="$ndk_bin/aarch64-linux-android28-clang"
# Match the exact symbol version imported by packages compiled for API 29.
"$ndk_bin/llvm-readelf" --dyn-syms -W "$ndk_bin/../sysroot/usr/lib/aarch64-linux-android/29/libc.so" > "$output/api29-libc-symbols.txt"
shim_version="$(sed -n 's/.*reallocarray@\{1,2\}\(LIBC[A-Z_]*\).*/\1/p' "$output/api29-libc-symbols.txt")"
case "$shim_version" in LIBC|LIBC_Q) ;; *) echo "Unexpected reallocarray ABI: $shim_version" >&2; exit 1;; esac
echo "API 29 reallocarray symbol version: $shim_version"
sed "s/CI_LIBC_VERSION/$shim_version/" /audit/.github/ci/bootstrap-api29-compat.map > "$output/bootstrap-api29-compat.map"
"$cc" -Oz -fno-builtin-reallocarray -fPIC -shared -Wl,--no-undefined \
    -Wl,-soname,libagentcodi-ci-api29.so \
    -Wl,--version-script,"$output/bootstrap-api29-compat.map" \
    /audit/.github/ci/bootstrap-api29-compat.c -o "$output/libagentcodi-ci-api29.so"
"$cc" -Oz -fno-builtin-reallocarray \
    /audit/.github/ci/bootstrap-api29-compat-test.c \
    -L"$output" -lagentcodi-ci-api29 -Wl,-rpath,/audit/ci-compat \
    -o "$output/bootstrap-api29-compat-test"
cp /audit/.github/ci/bootstrap-api29-compat.c \
    /audit/.github/ci/bootstrap-api29-compat-test.c "$output/"
cp "$ndk_dir/source.properties" "$output/ndk-source.properties"
cd "$output"
sha256sum *.c *.map *.so *.txt bootstrap-api29-compat-test ndk-source.properties > SHA256SUMS
