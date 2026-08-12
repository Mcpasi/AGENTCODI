#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"

if [ "$#" -ne 0 ]; then
  echo "build-release-apk.sh does not accept positional arguments." >&2
  exit 1
fi

AGENTCODI_BUILD_VARIANT=release exec "$SCRIPT_DIR/build-debug-apk.sh"
