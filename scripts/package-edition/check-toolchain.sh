#!/bin/bash
# Runs inside the locked amd64 builder. Cross-compiles but never runs Android ELF.
set -euo pipefail
recipe_dir="$(realpath "$1")"
report_dir="$(realpath "$2")"
cd "$recipe_dir"
export TERMUX_SCRIPTDIR="$recipe_dir"
. ./agentcodi.env
. ./scripts/properties.sh
test "$(uname -m)" = x86_64
test "$TERMUX_NDK_VERSION" = 30
test -f "$NDK/source.properties"
grep -q '^Pkg.Revision = 30\.' "$NDK/source.properties"
test -x "$ANDROID_HOME/build-tools/37.0.0/d8"
clang-21 --version | head -1 | grep -E 'clang version 21\.'
ndk_bin="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
target_clang="$ndk_bin/aarch64-linux-android29-clang"
{
    cat "$NDK/source.properties"
    "$target_clang" --version
    clang-21 --version
    java -version 2>&1
    dpkg-query -W
} > "$report_dir/builder-inventory.txt"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# Test the real export stage of the upstream toolchain hook. Its FUSE/sysroot
# assembly belongs to the subsequent full package build; this fixture uses the
# untouched, digest-pinned NDK directly and does not require /dev/fuse.
mkdir -p "$work/toolchain"
touch "$work/toolchain/.termux-standalone-toolchain"
mountpoint() { return 0; }
export TERMUX_ON_DEVICE_BUILD=false TERMUX_DEBUG_BUILD=false
export TERMUX_ARCH=aarch64 TERMUX_REAL_ARCH=aarch64 TERMUX_HOST_PLATFORM=aarch64-linux-android
export TERMUX_PKG_CONFIG_LIBDIR="$TERMUX_PREFIX/lib/pkgconfig"
export TERMUX_PKG_DEPENDS="" TERMUX_STANDALONE_TOOLCHAIN="$work/toolchain"
. ./scripts/build/toolchain/termux_setup_toolchain_30.sh
termux_setup_toolchain_30
unset -f mountpoint
test "$TERMUX_PKG_API_LEVEL" = 29
[[ "$CFLAGS" == *"-femulated-tls"* ]]
[[ "$CXXFLAGS" == *"-femulated-tls"* ]]
[[ "$LDFLAGS" == *"-Wl,-rpath=$TERMUX_PREFIX/lib"* ]]
[[ "$LDFLAGS" == *"-Wl,--enable-new-dtags"* ]]
root="$work/root"
mkdir -p "$root$TERMUX_PREFIX"/{bin,lib,etc} "$work/package" "$work/cache" "$work/output"
cat > "$work/library.c" <<'C'
#include <android/api-level.h>
#if __ANDROID_API__ != 29
#error wrong API baseline
#endif
_Thread_local int prefix_fixture_value = 29;
int prefix_fixture(void) { return prefix_fixture_value; }
C
cat > "$work/main.c" <<'C'
#include <stdio.h>
extern int prefix_fixture(void);
int main(void) { printf("%d\n", prefix_fixture()); return 0; }
C
# Word splitting is intentional for the upstream compiler/linker flag strings.
"$target_clang" $CPPFLAGS $CFLAGS -fPIC -shared "$work/library.c" $LDFLAGS \
    -Wl,-soname,libprefixfixture.so -o "$root$TERMUX_PREFIX/lib/libprefixfixture.so"
"$target_clang" $CPPFLAGS $CFLAGS "$work/main.c" $LDFLAGS \
    -L"$root$TERMUX_PREFIX/lib" -lprefixfixture -o "$root$TERMUX_PREFIX/bin/prefix-fixture"
printf '#!%s/bin/sh\nexit 0\n' "$TERMUX_PREFIX" > "$root$TERMUX_PREFIX/bin/prefix-script"
chmod 755 "$root$TERMUX_PREFIX/bin/prefix-script"
printf 'prefix=%s\n' "$TERMUX_PREFIX" > "$root$TERMUX_PREFIX/etc/prefix-fixture.conf"

# Exercise the actual upstream Debian archive, conffile and script metadata hook.
export TERMUX_PKG_NAME=prefix-fixture TERMUX_PKG_FULLVERSION=1.0
export TERMUX_PKG_MAINTAINER=AGENTCODI TERMUX_PKG_HOMEPAGE=https://github.com/Mcpasi/AGENTCODI
export TERMUX_PKG_DESCRIPTION="Prefix contract fixture"
export TERMUX_PKG_PACKAGEDIR="$work/package" TERMUX_COMMON_CACHEDIR="$work/cache"
export TERMUX_OUTPUT_DIR="$work/output" DEBUG="" AR=ar
export TERMUX_PKG_METAPACKAGE=false TERMUX_PKG_PLATFORM_INDEPENDENT=false
export TERMUX_GLOBAL_LIBRARY=false TERMUX_PKG_ESSENTIAL=false
export TERMUX_PKG_CONFFILES=etc/prefix-fixture.conf
for field in BREAKS PRE_DEPENDS CONFLICTS RECOMMENDS REPLACES PROVIDES SUGGESTS; do
    export "TERMUX_PKG_$field="
done
termux_step_create_debscripts() {
    printf '#!%s/bin/sh\nexit 0\n' "$TERMUX_PREFIX" > postinst
    chmod 755 postinst
}
termux_step_create_alternatives() { :; }
termux_step_create_python_debscripts() { :; }
. ./scripts/build/termux_step_create_debian_package.sh
(
    cd "$root"
    termux_step_create_debian_package
)
deb="$work/output/prefix-fixture_1.0_aarch64.deb"
first_digest="$(sha256sum "$deb" | cut -d' ' -f1)"
(
    cd "$root"
    termux_step_create_debian_package
)
test "$first_digest" = "$(sha256sum "$deb" | cut -d' ' -f1)"
dpkg-deb --extract "$deb" "$work/extracted"
dpkg-deb --control "$deb" "$work/control"
python3 -B /audit/scripts/package-edition/verify-prefix.py \
    --root "$work/extracted" --control "$work/control" \
    --readelf "$ndk_bin/llvm-readelf" --report "$report_dir/prefix-audit.json"
python3 - "$report_dir/prefix-audit.json" "$TERMUX_PREFIX" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
assert len(report["elf"]) == 2
for elf in report["elf"].values():
    assert "(RUNPATH)" in elf and sys.argv[2] + "/lib" in elf
executable = report["elf"][sys.argv[2].lstrip("/") + "/bin/prefix-fixture"]
assert "Requesting program interpreter: /system/bin/linker64" in executable
PY
cp "$deb" "$report_dir/"
cp "$recipe_dir/agentcodi-preparation.json" "$report_dir/"
cp "$work/control/control" "$report_dir/fixture-control.txt"
cp "$work/control/conffiles" "$report_dir/fixture-conffiles.txt"
printf '%s  prefix-fixture_1.0_aarch64.deb\n' "$first_digest" > "$report_dir/SHA256SUMS"
echo "Pinned NDK ARM64/Bionic compile, emulated TLS, RUNPATH, metadata and repeatable DEB assembly passed"
