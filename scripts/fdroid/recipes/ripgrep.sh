TERMUX_PKG_HOMEPAGE=https://github.com/BurntSushi/ripgrep
TERMUX_PKG_DESCRIPTION="AGENTCODI source-built search tool without PCRE2"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_LICENSE_FILE="LICENSE-MIT"
TERMUX_PKG_MAINTAINER="AGENTCODI"
TERMUX_PKG_VERSION=15.2.0
TERMUX_PKG_SRCURL=https://github.com/BurntSushi/ripgrep/archive/refs/tags/15.2.0.tar.gz
TERMUX_PKG_SHA256=7605249d3eb0d5f170e3414498e3344e26b1e7a147aec518b57090b80036a562
termux_step_configure() { :; }
termux_step_make() {
  # A release archive has no .git directory. Do not discover AGENTCODI's
  # enclosing checkout and embed its unrelated, changing commit in rg.
  export GIT_CEILING_DIRECTORIES="$TERMUX_TOPDIR"
  printf '%s  %s\n' \
    7a7d39cda8a03930e578f1dbb724e055771901842eca239e03b01e19da946a64 \
    "$TERMUX_PKG_SRCDIR/Cargo.lock" | sha256sum --check
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="--remap-path-prefix=$TERMUX_TOPDIR=/usr/src/runtime --remap-path-prefix=$CARGO_HOME=/usr/src/cargo --remap-path-prefix=$AGENTCODI_RUNTIME_CACHE/vendor=/usr/src/cargo-vendor -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
  cargo +1.95.0 build --manifest-path "$TERMUX_PKG_SRCDIR/Cargo.toml" \
    --frozen --target aarch64-linux-android --profile release-lto --no-default-features
  cargo +1.95.0 tree --manifest-path "$TERMUX_PKG_SRCDIR/Cargo.toml" \
    --frozen --target aarch64-linux-android --no-default-features \
    --edges normal --prefix none --format '{p}' \
    | sed -E 's/ \(\*\)$//; s# \(/.*\)$##' | sort -u > "$TERMUX_PKG_TMPDIR/tree"
  sed -nE 's/^([A-Za-z0-9_-]+) ([0-9][^ ]*) \| static.*$/\1 v\2/p' \
    "$AGENTCODI_PROJECT_ROOT/third_party/ripgrep/DEPENDENCIES" | sort -u \
    | cmp - "$TERMUX_PKG_TMPDIR/tree"
}
termux_step_make_install() {
  install -Dm755 "$TERMUX_PKG_SRCDIR/target/aarch64-linux-android/release-lto/rg" "$TERMUX_PREFIX/bin/rg"
}
