#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
TERMUX_PREFIX="${AGENTCODI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
CACHE_ROOT="${AGENTCODI_RIPGREP_CACHE:-$PROJECT_ROOT/.cache/ripgrep-rebuild}"
BUILD_ROOT="$PROJECT_ROOT/.build/ripgrep-rebuild"
TRACKED_ARTIFACT="$PROJECT_ROOT/third_party/ripgrep/ripgrep-15.2.0-android-arm64.elf"

RIPGREP_SOURCE_URL="https://github.com/BurntSushi/ripgrep/archive/refs/tags/15.2.0.tar.gz"
RIPGREP_SOURCE_SHA256="7605249d3eb0d5f170e3414498e3344e26b1e7a147aec518b57090b80036a562"
RIPGREP_LOCK_SHA256="7a7d39cda8a03930e578f1dbb724e055771901842eca239e03b01e19da946a64"
RUST_URL="https://packages.termux.dev/apt/termux-main/pool/main/r/rust/rust_1.97.1_aarch64.deb"
RUST_SHA256="55c7eae124034fb8cf738f58031b4b7d0182e84325675d7e6da116cf3af0ca41"
RUST_STD_URL="https://packages.termux.dev/apt/termux-main/pool/main/r/rust-std-aarch64-linux-android/rust-std-aarch64-linux-android_1.97.1_aarch64.deb"
RUST_STD_SHA256="98eeb5a632468a9d067b4ff7b5a2b263ae1ba9b16dc14464c96396041b68033e"
EXECINFO_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-execinfo/libandroid-execinfo_0.1-3_aarch64.deb"
EXECINFO_SHA256="725dd2c6da7fc96e860fcc928e18aa9fb0e85e0bdcaf4f8ce0f0654ca6860fc2"
PATCHELF_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/patchelf/patchelf_0.19.1_aarch64.deb"
PATCHELF_SHA256="a08bea49b3c9c3bf449ee0c7b7ee9c97a9f3ab84ae06ace08a564d0903a23c3f"
CLANG_SHA256="3599a121ecc11b433d23bc43545bc0441f9a8ffcc587ea18e312d88188fcf282"
LLD_SHA256="7a0e3dad3eaeaf7ec0e85337ab1047470141e95aae11e4b51eb12c749c1a561a"
COMPILER_RT_SHA256="9aeed0613b933c2c79a7c366371b5910681b740b78093d33237fd31c67345cb2"
PRE_SANITIZE_SHA256="ad956fdd0b372556e4d97fcc74f56eb222882d29490bb4dbcedd4c7837a4b9c6"
RPATH_REMOVED_SHA256="8c357566aa70063c6e5a6b6ad34312304901150cf8fbde29055f10d02e1c3753"
FINAL_SHA256="4eb0d0c70d2e3c760cab4f478c7eb715082ae1d8b5f4a23bb14515154348b04d"
DEPENDENCIES_SHA256="373bca4f92736c1d185462b7d5722d9a98097ba4c696c4f62752d0ecd91ebcf6"
LICENSES_SHA256="43ba0c48735498436470bc5ceddbd1286b694b17235f6f571b14dc3bfc43d678"
PROVENANCE_SHA256="dda94ec73d14990f746373f3100d718a6dcb608c994c89a3c4081fb1535d80b0"

for command_name in awk cmp curl dd dpkg-deb file grep mv readelf rg sed sha256sum sort strings tar tr wc; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Missing ripgrep rebuild command: $command_name" >&2
    exit 1
  fi
done
if [ "$TERMUX_PREFIX" != "/data/data/com.termux/files/usr" ]; then
  echo "The pinned ripgrep build requires the reviewed Termux prefix." >&2
  exit 1
fi

verify_sha256() {
  local file="$1"
  local expected="$2"
  if ! printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status; then
    echo "ripgrep rebuild SHA-256 mismatch: $file" >&2
    exit 1
  fi
}

download_verified() {
  local url="$1"
  local expected="$2"
  local destination="$3"
  if [ -f "$destination" ] && printf '%s  %s\n' "$expected" "$destination" \
      | sha256sum --check --status; then
    return
  fi
  local partial="$destination.partial"
  curl --fail --location --retry 3 --output "$partial" "$url"
  verify_sha256 "$partial" "$expected"
  mv "$partial" "$destination"
}

mkdir -p "$CACHE_ROOT"
SOURCE_ARCHIVE="$CACHE_ROOT/ripgrep-15.2.0.tar.gz"
RUST_ARCHIVE="$CACHE_ROOT/rust-1.97.1-aarch64.deb"
RUST_STD_ARCHIVE="$CACHE_ROOT/rust-std-1.97.1-aarch64.deb"
EXECINFO_ARCHIVE="$CACHE_ROOT/libandroid-execinfo-0.1-3-aarch64.deb"
PATCHELF_ARCHIVE="$CACHE_ROOT/patchelf-0.19.1-aarch64.deb"
download_verified "$RIPGREP_SOURCE_URL" "$RIPGREP_SOURCE_SHA256" "$SOURCE_ARCHIVE"
download_verified "$RUST_URL" "$RUST_SHA256" "$RUST_ARCHIVE"
download_verified "$RUST_STD_URL" "$RUST_STD_SHA256" "$RUST_STD_ARCHIVE"
download_verified "$EXECINFO_URL" "$EXECINFO_SHA256" "$EXECINFO_ARCHIVE"
download_verified "$PATCHELF_URL" "$PATCHELF_SHA256" "$PATCHELF_ARCHIVE"

verify_sha256 "$TERMUX_PREFIX/bin/clang-21" "$CLANG_SHA256"
verify_sha256 "$TERMUX_PREFIX/bin/ld.lld" "$LLD_SHA256"
verify_sha256 \
  "$TERMUX_PREFIX/lib/clang/21/lib/linux/libclang_rt.builtins-aarch64-android.a" \
  "$COMPILER_RT_SHA256"
if ! "$TERMUX_PREFIX/bin/clang" --version | grep -Fq 'clang version 21.1.8' \
    || ! "$TERMUX_PREFIX/bin/ld.lld" --version | grep -Fq 'LLD 21.1.8'; then
  echo "The pinned clang/LLD version is unavailable." >&2
  exit 1
fi

case "$BUILD_ROOT" in
  "$PROJECT_ROOT"/.build/ripgrep-rebuild) rm -rf -- "$BUILD_ROOT" ;;
  *) echo "Refusing unsafe ripgrep rebuild cleanup." >&2; exit 1 ;;
esac
mkdir -p "$BUILD_ROOT/source" "$BUILD_ROOT/rust" "$BUILD_ROOT/cargo-home"
tar -xzf "$SOURCE_ARCHIVE" --strip-components=1 -C "$BUILD_ROOT/source"
dpkg-deb -x "$RUST_ARCHIVE" "$BUILD_ROOT/rust"
dpkg-deb -x "$RUST_STD_ARCHIVE" "$BUILD_ROOT/rust"
dpkg-deb -x "$EXECINFO_ARCHIVE" "$BUILD_ROOT/rust"
dpkg-deb -x "$PATCHELF_ARCHIVE" "$BUILD_ROOT/rust"
verify_sha256 "$BUILD_ROOT/source/Cargo.lock" "$RIPGREP_LOCK_SHA256"

RUST_PREFIX="$BUILD_ROOT/rust/data/data/com.termux/files/usr"
RUSTC="$RUST_PREFIX/bin/rustc"
CARGO="$RUST_PREFIX/bin/cargo"
PATCHELF="$RUST_PREFIX/bin/patchelf"
BUILD_LIBRARY_PATH="$RUST_PREFIX/lib:$TERMUX_PREFIX/lib"
if ! env LD_LIBRARY_PATH="$BUILD_LIBRARY_PATH" "$RUSTC" --version --verbose \
    | grep -Fq 'release: 1.97.1' \
    || ! env LD_LIBRARY_PATH="$TERMUX_PREFIX/lib" "$PATCHELF" --version \
      | grep -Fq 'patchelf 0.19.1'; then
  echo "The extracted Rust or patchelf build tool is invalid." >&2
  exit 1
fi

env \
  PATH="$RUST_PREFIX/bin:$TERMUX_PREFIX/bin:/system/bin" \
  LD_LIBRARY_PATH="$BUILD_LIBRARY_PATH" \
  CARGO_HOME="$BUILD_ROOT/cargo-home" \
  RUSTC="$RUSTC" \
  "$CARGO" fetch \
    --manifest-path "$BUILD_ROOT/source/Cargo.toml" \
    --locked \
    --target aarch64-linux-android

env \
  PATH="$RUST_PREFIX/bin:$TERMUX_PREFIX/bin:/system/bin" \
  LD_LIBRARY_PATH="$BUILD_LIBRARY_PATH" \
  CARGO_HOME="$BUILD_ROOT/cargo-home" \
  RUSTC="$RUSTC" \
  CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$SCRIPT_DIR/ripgrep-android-linker.sh" \
  AGENTCODI_RIPGREP_CLANG="$TERMUX_PREFIX/bin/clang" \
  CARGO_INCREMENTAL=0 \
  SOURCE_DATE_EPOCH=1784131200 \
  RUSTFLAGS="--remap-path-prefix=$BUILD_ROOT/source=/usr/src/ripgrep-15.2.0 --remap-path-prefix=$BUILD_ROOT/cargo-home=/usr/src/cargo -C link-arg=--target=aarch64-linux-android29 -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384" \
  "$CARGO" build \
    --manifest-path "$BUILD_ROOT/source/Cargo.toml" \
    --locked \
    --offline \
    --target aarch64-linux-android \
    --profile release-lto \
    --no-default-features

BUILT_ARTIFACT="$BUILD_ROOT/source/target/aarch64-linux-android/release-lto/rg"
verify_sha256 "$BUILT_ARTIFACT" "$PRE_SANITIZE_SHA256"
env LD_LIBRARY_PATH="$TERMUX_PREFIX/lib" "$PATCHELF" --remove-rpath "$BUILT_ARTIFACT"
verify_sha256 "$BUILT_ARTIFACT" "$RPATH_REMOVED_SHA256"
if readelf -dW "$BUILT_ARTIFACT" | grep -Eq '(RPATH|RUNPATH)'; then
  echo "The rebuilt ripgrep ELF retains an active build-host search path." >&2
  exit 1
fi

HOST_RUNPATH='/data/data/com.termux/files/usr/bin/../../usr/lib'
host_path_count="$(grep -aboF "$HOST_RUNPATH" "$BUILT_ARTIFACT" | wc -l)"
host_path_offset="$(grep -aboF "$HOST_RUNPATH" "$BUILT_ARTIFACT" | awk -F: 'NR == 1 {print $1}')"
if [ "$host_path_count" -ne 1 ] || [ -z "$host_path_offset" ]; then
  echo "The reviewed stale Termux dynstr value changed unexpectedly." >&2
  exit 1
fi
dd if=/dev/zero of="$BUILT_ARTIFACT" bs=1 seek="$host_path_offset" \
  count="${#HOST_RUNPATH}" conv=notrunc status=none
verify_sha256 "$BUILT_ARTIFACT" "$FINAL_SHA256"

if ! file "$BUILT_ARTIFACT" | grep -q 'ARM aarch64' \
    || ! readelf -lW "$BUILT_ARTIFACT" | grep -Fq '/system/bin/linker64'; then
  echo "The rebuilt ripgrep ELF failed ABI or Android-linker validation." >&2
  exit 1
fi
if ! readelf -lW "$BUILT_ARTIFACT" \
    | awk '$1 == "LOAD" && $NF != "0x4000" {bad=1} END {exit bad}'; then
  echo "The rebuilt ripgrep ELF failed 16 KiB alignment validation." >&2
  exit 1
fi
if [ "$(readelf -dW "$BUILT_ARTIFACT" | awk '/NEEDED/ {gsub(/[][]/, "", $NF); print $NF}' | sort | tr '\n' ' ')" != "libc.so libdl.so " ]; then
  echo "The rebuilt ripgrep ELF has an unexpected dependency." >&2
  exit 1
fi
if [ "$("$BUILT_ARTIFACT" --version | sed -n '1p')" != 'ripgrep 15.2.0' ] \
    || ! "$BUILT_ARTIFACT" --version | grep -Fq 'features:-pcre2' \
    || "$BUILT_ARTIFACT" --pcre2-version >/dev/null 2>&1 \
    || strings "$BUILT_ARTIFACT" | grep -Fq '/data/data/com.termux'; then
  echo "The rebuilt ripgrep feature or path audit failed." >&2
  exit 1
fi

NORMAL_TREE="$BUILD_ROOT/normal-tree.txt"
INVENTORIED_NORMAL_TREE="$BUILD_ROOT/inventoried-normal-tree.txt"
env \
  PATH="$RUST_PREFIX/bin:$TERMUX_PREFIX/bin:/system/bin" \
  LD_LIBRARY_PATH="$BUILD_LIBRARY_PATH" \
  CARGO_HOME="$BUILD_ROOT/cargo-home" \
  RUSTC="$RUSTC" \
  "$CARGO" tree \
    --manifest-path "$BUILD_ROOT/source/Cargo.toml" \
    --locked \
    --offline \
    --target aarch64-linux-android \
    --no-default-features \
    --edges normal \
    --prefix none \
    --format '{p}' \
  | sed -E 's/ \(\*\)$//; s# \(/.*\)$##' \
  | sort -u > "$NORMAL_TREE"
sed -nE \
  's/^([A-Za-z0-9_-]+) ([0-9][^ ]*) \| static.*$/\1 v\2/p' \
  "$PROJECT_ROOT/third_party/ripgrep/DEPENDENCIES" \
  | sort -u > "$INVENTORIED_NORMAL_TREE"
if [ "$(wc -l < "$NORMAL_TREE")" -ne 34 ] \
    || grep -Eiq '(^|[- ])pcre2([ -]|$)' "$NORMAL_TREE" \
    || ! cmp -s "$NORMAL_TREE" "$INVENTORIED_NORMAL_TREE"; then
  echo "The rebuilt Cargo dependency closure is incomplete or includes PCRE2." >&2
  exit 1
fi

verify_sha256 "$PROJECT_ROOT/third_party/ripgrep/DEPENDENCIES" "$DEPENDENCIES_SHA256"
verify_sha256 "$PROJECT_ROOT/third_party/ripgrep/LICENSES" "$LICENSES_SHA256"
verify_sha256 "$PROJECT_ROOT/third_party/ripgrep/PROVENANCE" "$PROVENANCE_SHA256"
if ! cmp -s "$BUILT_ARTIFACT" "$TRACKED_ARTIFACT"; then
  echo "The rebuilt ripgrep ELF differs from the tracked artifact." >&2
  exit 1
fi

echo "Rebuilt and verified ripgrep 15.2.0: $FINAL_SHA256"
