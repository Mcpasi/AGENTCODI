# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/libprotobuf/build.sh"
# aapt2 links libprotobuf. Code generation uses the separately locked host
# protoc, so Android compiler binaries and the unrelated upb library are unused.
TERMUX_PKG_EXTRA_CONFIGURE_ARGS+=" -Dprotobuf_BUILD_PROTOC_BINARIES=OFF -Dprotobuf_BUILD_LIBPROTOC=OFF -Dprotobuf_BUILD_LIBUPB=OFF"
