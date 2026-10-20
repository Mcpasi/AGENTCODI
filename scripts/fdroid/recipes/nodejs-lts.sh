# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/nodejs-lts/build.sh"
termux_step_host_build() {
  # libicu precedes Node in our recipe graph. Reuse its source-built host
  # libraries/code generators instead of compiling the same ICU a second time.
  make -C "$TERMUX_TOPDIR/libicu/host-build" -j "$TERMUX_PKG_MAKE_PROCESSES" \
    install prefix="$TERMUX_PKG_HOSTBUILD_DIR/icu-installed"
  local archive=clang-llvmorg-21-init-5118-g52cd27e6-5.tar.xz
  termux_download \
    "https://commondatastorage.googleapis.com/chromium-browser-clang/Linux_x64/$archive" \
    "$TERMUX_PKG_CACHEDIR/$archive" \
    790fcc5b04e96882e8227ba7994161ab945c0e096057fc165a0f71e32a7cb061
  mkdir -p "$TERMUX_PKG_HOSTBUILD_DIR/llvm-project-build"
  tar -xf "$TERMUX_PKG_CACHEDIR/$archive" -C "$TERMUX_PKG_HOSTBUILD_DIR/llvm-project-build"
}
termux_step_make() {
  local variant=Release
  if [ "$TERMUX_DEBUG_BUILD" = true ]; then variant=Debug; fi
  ninja -C "out/$variant" -j "$TERMUX_PKG_MAKE_PROCESSES" node
}
