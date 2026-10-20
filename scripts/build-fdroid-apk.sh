#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
if [ "$#" -ne 0 ]; then
  echo "build-fdroid-apk.sh does not accept positional arguments." >&2
  exit 1
fi
# All downloads belong to preparation. F-Droid supplies its own signing key.
export AGENTCODI_BUILD_VARIANT=unsigned
export AGENTCODI_OFFLINE=1
exec "$SCRIPT_DIR/build-debug-apk.sh"
