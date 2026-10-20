# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/ncurses/build.sh"
# Python needs libraries, not the terminal emulator source/terminfo additions.
unset TERMUX_PKG_VERSION TERMUX_PKG_SRCURL TERMUX_PKG_SHA256
TERMUX_PKG_VERSION=6.6.20260307+really6.5.20250830
TERMUX_PKG_SRCURL=https://github.com/ThomasDickey/ncurses-snapshots/archive/5f58399b2de47ed14bdfe3a0cb149293b27893d5.tar.gz
TERMUX_PKG_SHA256=28cd102efe6a2610e830cc79cf270da6ff0427b2022900a9a36d2761522f9576
termux_step_post_make_install() {
  for name in ncurses panel; do
    ln -sf "lib${name}w.so.6.5" "$TERMUX_PREFIX/lib/lib${name}w.so.6"
    ln -sf "lib${name}w.so.6" "$TERMUX_PREFIX/lib/lib${name}w.so"
  done
}
