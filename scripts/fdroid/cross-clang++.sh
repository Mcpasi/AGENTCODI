#!/usr/bin/env bash
# Use Debian LLVM with the verified NDK sysroot/compiler-rt and source-built
# libc++. Host utilities (notably the ELF injector) stay native Linux programs.
set -Eeuo pipefail
cross=0
link=1
for argument in "$@"; do
  case "$argument" in
    --target=aarch64-linux-android*) cross=1 ;;
    -c|-E|-S|--version) link=0 ;;
  esac
done
if [ "$cross" -eq 0 ]; then
  exec /usr/bin/clang++-21 "$@"
fi
toolchain="${AGENTCODI_ASSEMBLY_NDK:?Source scripts/fdroid/host-env.sh first}/toolchains/llvm/prebuilt/linux-x86_64"
resources=("$toolchain"/lib/clang/*)
if [ ! -d "$toolchain/sysroot/usr/include" ] || [ "${#resources[@]}" -ne 1 ] \
    || [ ! -d "${resources[0]}/lib/linux" ]; then
  echo "Missing verified assembly NDK; run scripts/fdroid/prepare.sh assembly." >&2
  exit 1
fi
options=(--sysroot="$toolchain/sysroot" -resource-dir="${resources[0]}")
if [ "$link" -eq 1 ]; then
  options+=(--ld-path=/usr/bin/ld.lld-21 -nostdlib++ --rtlib=compiler-rt \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
  exec /usr/bin/clang++-21 "${options[@]}" "$@" -lc++_shared \
    -Wl,--exclude-libs,libunwind.a "${resources[0]}/lib/linux/aarch64/libunwind.a"
fi
exec /usr/bin/clang++-21 "${options[@]}" "$@"
