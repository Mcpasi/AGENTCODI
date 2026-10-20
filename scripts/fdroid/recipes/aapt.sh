# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/aapt/build.sh"
# Git sources and all submodule revisions come from runtime-lock.json.
TERMUX_PKG_SRCURL=()
TERMUX_PKG_SHA256=()
termux_step_get_source() {
  cp -a "$AGENTCODI_RUNTIME_CACHE/git/aapt/." "$TERMUX_PKG_SRCDIR/"
}
termux_step_make() {
  cmake --build . --target aapt2 --parallel "$TERMUX_PKG_MAKE_PROCESSES"
}
termux_step_make_install() {
  install -Dm755 vendor/aapt2 "$TERMUX_PREFIX/bin/aapt2"
}
