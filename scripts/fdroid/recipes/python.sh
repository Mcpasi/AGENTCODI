# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/python/build.sh"
unset TERMUX_PKG_VERSION TERMUX_PKG_SRCURL TERMUX_PKG_SHA256
TERMUX_PKG_VERSION=3.14.6
TERMUX_PKG_REVISION=1
TERMUX_PKG_SRCURL=https://www.python.org/ftp/python/3.14.6/Python-3.14.6.tar.xz
TERMUX_PKG_SHA256=143b1dddefaec3bd2e21e3b839b34a2b7fb9842272883c576420d605e9f30c63
TERMUX_PKG_DEPENDS="libandroid-posix-semaphore, libandroid-support, libbz2, libexpat, libffi, liblzma, libsqlite, ncurses, ncurses-ui-libs, openssl, zlib, zstd"
TERMUX_PKG_BUILD_DEPENDS=""
TERMUX_PKG_RECOMMENDS=""
termux_step_post_get_source() {
  sed "s/@TERMUX_PKG_API_LEVEL@/$TERMUX_PKG_API_LEVEL/g" \
    "$TERMUX_PKG_BUILDER_DIR/hardcode-android-api-level.diff" | patch --batch -p1
}
termux_step_pre_configure() {
  termux_setup_build_python
  CFLAGS="${CFLAGS/-Oz/-O3}"
  CPPFLAGS+=" -I$TERMUX_STANDALONE_TOOLCHAIN/sysroot/usr/include"
  LDFLAGS="${LDFLAGS/-Wl,--as-needed/} -landroid-posix-semaphore"
  TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" ac_cv_func_fexecve=no ac_cv_func_getlogin_r=no ac_cv_func_getloadavg=no ac_cv_func_sem_clockwait=no ac_cv_func_preadv2=no ac_cv_func_pwritev2=no ac_cv_func_close_range=no ac_cv_func_copy_file_range=no"
  autoreconf -fi
  cat >> "$TERMUX_PKG_SRCDIR/Modules/Setup.local" <<'MODULES'
*disabled*
_dbm
_gdbm
readline
_tkinter
MODULES
}
termux_step_post_make_install() { :; }
termux_step_post_massage() {
  for name in _bz2 _ctypes _curses _curses_panel _lzma _multiprocessing _sqlite3 _ssl _zstd zlib; do
    compgen -G "$TERMUX_PREFIX/lib/python3.14/lib-dynload/$name.*.so" >/dev/null \
      || termux_error_exit "Missing Python runtime module: $name"
  done
}
termux_step_create_debscripts() { :; }
