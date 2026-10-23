#!/usr/bin/env bash
# All network access belongs here, before the offline source-build phases.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
source "$SCRIPT_DIR/host-env.sh"
cd "$AGENTCODI_PROJECT_ROOT"
phase="${1:-all}"
case "$phase" in all|codex|runtime|assembly) ;; *) echo "Unknown preparation phase: $phase" >&2; exit 1 ;; esac
if [ "$phase" != runtime ]; then
  sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$sdkmanager" ]; then
    sdkmanager="$(command -v sdkmanager)"
  fi
  "$sdkmanager" --sdk_root="$ANDROID_HOME" \
    'ndk;28.2.13676358' 'platforms;android-36' 'build-tools;36.0.0'
fi
if [ "$phase" != assembly ]; then
  rustup toolchain install 1.95.0 --profile minimal --target aarch64-linux-android
fi
if [ "$phase" = all ] || [ "$phase" = codex ]; then
  ./scripts/build-codex-from-source.sh prepare
fi
if [ "$phase" = all ] || [ "$phase" = runtime ]; then
  ./scripts/build-runtime-from-source.sh prepare
fi
if [ "$phase" = all ] || [ "$phase" = assembly ]; then
  ./scripts/build-runtime-from-source.sh prepare-toolchain
  AGENTCODI_INPUTS_USE_MIRROR=0 .github/ci/fetch-build-inputs.sh
  .github/ci/verify-build-inputs.sh
fi
