# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/npm/build.sh"
TERMUX_PKG_VERSION=11.19.0
TERMUX_PKG_REVISION=0
TERMUX_PKG_SRCURL=()
TERMUX_PKG_SHA256=()
termux_step_get_source() {
  cp -a "$AGENTCODI_RUNTIME_CACHE/git/npm/." "$TERMUX_PKG_SRCDIR/"
}
termux_step_make_install() {
  # Production JS dependencies are checked in; resetdeps runs online npm install.
  python3 "$AGENTCODI_PROJECT_ROOT/scripts/fdroid/stage-npm.py" \
    "$TERMUX_PKG_SRCDIR" "$TERMUX_PREFIX/lib/node_modules/npm"
}
termux_step_create_debscripts() { :; }
