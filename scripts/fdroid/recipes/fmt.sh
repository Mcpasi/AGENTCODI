# AGENTCODI source recipe; upstream Android patches are locked in runtime-lock.json.
source "$AGENTCODI_BASE_RECIPES/packages/fmt/build.sh"
termux_step_pre_configure() {
  # fmt 11.2 removes transitive libc++ includes but uses malloc/free itself.
  # Declare that dependency explicitly with the source-built libc++ headers.
  sed -i '/^#  include <cstdint>/a #  include <cstdlib>  // malloc, free' \
    "$TERMUX_PKG_SRCDIR/include/fmt/format.h"
}
