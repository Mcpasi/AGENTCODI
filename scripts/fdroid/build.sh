#!/usr/bin/env bash
# Native F-Droid entry point: no Docker, Ubuntu, ARM emulation or root needed.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
source "$SCRIPT_DIR/host-env.sh"
cd "$AGENTCODI_PROJECT_ROOT"
export AGENTCODI_OFFLINE=1 CARGO_NET_OFFLINE=true
./scripts/build-runtime-from-source.sh build
./scripts/build-codex-from-source.sh build
exec ./scripts/build-fdroid-apk.sh
