#!/usr/bin/env bash
# The complete local recipe graph also builds ripgrep and verifies its Cargo
# dependency inventory. No tracked ELF or rolling Termux Rust package is used.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
exec "$SCRIPT_DIR/build-runtime-from-source.sh" build "$@"
