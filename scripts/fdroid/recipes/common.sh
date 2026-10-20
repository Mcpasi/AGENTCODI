# Applied after the package recipe has defined its Android-specific hooks.
if declare -F termux_step_pre_configure >/dev/null; then
  eval "$(declare -f termux_step_pre_configure | sed '1s/termux_step_pre_configure/agentcodi_pre_configure/')"
fi
termux_step_pre_configure() {
  CFLAGS+=" -fPIC -ffile-prefix-map=$TERMUX_TOPDIR=/usr/src/agentcodi-runtime -ffile-prefix-map=$AGENTCODI_RUNTIME_WORK=/usr/src/agentcodi-recipes"
  # Use the source-built libc++ headers once. A second NDK libc++ include
  # directory would intercept include_next before it reaches Bionic's headers.
  CXXFLAGS+=" -nostdinc++ -fPIC -ffile-prefix-map=$TERMUX_TOPDIR=/usr/src/agentcodi-runtime -ffile-prefix-map=$AGENTCODI_RUNTIME_WORK=/usr/src/agentcodi-recipes"
  LDFLAGS+=" -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
  if declare -F agentcodi_pre_configure >/dev/null; then
    agentcodi_pre_configure
  fi
}
