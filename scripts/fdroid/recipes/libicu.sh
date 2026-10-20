# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/libicu/build.sh"
# Preserve the archive license instead of the upstream unverified branch curl.
termux_step_post_get_source() {
  TERMUX_PKG_SRCDIR+="/source"
}
