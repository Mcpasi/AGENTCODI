#!/usr/bin/env bash
# Reconstruct Termux ndk-sysroot 29-3 after its package leaves the rolling pool.
# Follow the pinned upstream recipe, using verified NDK and patch bytes.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
OUTPUT_DIR="${1:?Pass the directory for the reconstructed sysroot package}"
NDK_SHA256="4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf"
NDK_URL="https://dl.google.com/android/repository/android-ndk-r29-linux.zip"
PREFIX="/data/data/com.termux/files/usr"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf -- "$WORK_DIR"' EXIT

download_verified() {
  local url="$1" sha="$2" destination="$3"
  mkdir -p "$(dirname -- "$destination")"
  # Large Google/Termux downloads occasionally reset HTTP/2 streams on hosted
  # runners. Prefer HTTP/1.1 and resume the partial file across retries while
  # keeping the pinned SHA-256 as the final authority over accepted bytes.
  curl --fail --location --http1.1 \
    --retry 8 --retry-all-errors --retry-delay 3 \
    --connect-timeout 30 --max-time 1800 --continue-at - \
    --output "$destination" "$url"
  printf '%s  %s\n' "$sha" "$destination" | sha256sum --check
}

download_verified "$NDK_URL" "$NDK_SHA256" "$WORK_DIR/ndk.zip"
while IFS=$'\t' read -r sha path url; do
  case "$sha" in ''|\#*) continue ;; esac
  download_verified "$url" "$sha" "$WORK_DIR/patches/$path"
done < "$SCRIPT_DIR/ndk-29-inputs.tsv"

NDK_ROOT="$WORK_DIR/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64"
unzip -q "$WORK_DIR/ndk.zip" \
  'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/*' \
  'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/24/*.o' \
  'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libcompiler_rt-extras.a' \
  'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++experimental.a' \
  'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux/aarch64/libatomic.a' \
  'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux/aarch64/libunwind.a' \
  -d "$WORK_DIR"

# Match termux/termux-packages e23be59f's ndk-sysroot recipe and substitutions.
(
  cd "$NDK_ROOT/sysroot"
  for patch_file in "$WORK_DIR"/patches/ndk-patches/29/*.patch; do
    sed -e 's%@TERMUX_APP_PACKAGE@%com.termux%g' \
      -e 's%@TERMUX_BASE_DIR@%/data%g' \
      -e 's%@TERMUX_CACHE_DIR@%/data/data/com.termux/cache%g' \
      -e 's%@TERMUX_HOME@%/data/data/com.termux/files/home%g' \
      -e "s%@TERMUX_PREFIX@%$PREFIX%g" "$patch_file" \
      | patch --batch -p1
  done
  grep -lrw usr/include/c++/v1 -e 'include <version>' \
    | xargs -r sed -i 's/include <version>/include "version"/g'
)

PACKAGE_ROOT="$WORK_DIR/package"
PACKAGE_PREFIX="$PACKAGE_ROOT$PREFIX"
mkdir -p "$PACKAGE_PREFIX/include" "$PACKAGE_PREFIX/lib" "$PACKAGE_ROOT/DEBIAN"
cp -a "$NDK_ROOT/sysroot/usr/include/." "$PACKAGE_PREFIX/include/"
find "$PACKAGE_PREFIX/include" -name '*.orig' -delete
cp "$WORK_DIR/patches/ndk-patches/langinfo.h" \
  "$WORK_DIR/patches/ndk-patches/libintl.h" "$PACKAGE_PREFIX/include/"
for excluded_header in EGL GLES GLES2 GLES3 KHR/khrplatform.h execinfo.h \
    glob.h iconv.h spawn.h sys/capability.h sys/sem.h sys/shm.h unicode \
    vk_video vulkan zconf.h zlib.h; do
  rm -rf -- "$PACKAGE_PREFIX/include/$excluded_header"
done
cp "$NDK_ROOT"/sysroot/usr/lib/aarch64-linux-android/24/*.o "$PACKAGE_PREFIX/lib/"
cp "$NDK_ROOT/sysroot/usr/lib/aarch64-linux-android/libcompiler_rt-extras.a" \
  "$NDK_ROOT/sysroot/usr/lib/aarch64-linux-android/libc++experimental.a" \
  "$NDK_ROOT/lib/clang/21/lib/linux/aarch64/libatomic.a" \
  "$NDK_ROOT/lib/clang/21/lib/linux/aarch64/libunwind.a" "$PACKAGE_PREFIX/lib/"
for library in librt.so libpthread.so libutil.so; do
  printf '%s\n' 'INPUT(-lc)' > "$PACKAGE_PREFIX/lib/$library"
done
cat > "$PACKAGE_ROOT/DEBIAN/control" <<'CONTROL'
Package: ndk-sysroot
Version: 29-3
Architecture: aarch64
Maintainer: AGENTCODI contributors
Conflicts: libutil-dev, libgcc, libandroid-support-dev
Replaces: libutil-dev, libgcc, libandroid-support-dev, ndk-stl
Description: Pinned Termux NDK r29 sysroot reconstructed for AGENTCODI CI
CONTROL
mkdir -p "$OUTPUT_DIR"
dpkg-deb --build --root-owner-group -Zxz "$PACKAGE_ROOT" \
  "$OUTPUT_DIR/ndk-sysroot_29-3_aarch64.deb"
