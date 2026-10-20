# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/libsqlite/build.sh"
TERMUX_PKG_BUILD_DEPENDS=""
TERMUX_PKG_EXTRA_CONFIGURE_ARGS="--enable-fts3 --enable-fts4 --enable-fts5 --enable-rtree --enable-session --disable-readline --disable-tcl --with-tclsh=/usr/bin/tclsh"
TERMUX_PKG_EXTRA_MAKE_ARGS="TCLSH_CMD=/usr/bin/tclsh"
# Tcl remains a host code generator; no target Tcl extension is shipped.
termux_step_make_install() {
  make install INSTALL.strip=/usr/bin/install TCLSH_CMD=/usr/bin/tclsh
}
