#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
exec python3 -B "$SCRIPT_DIR/fdroid/build-runtime.py" "$@"
