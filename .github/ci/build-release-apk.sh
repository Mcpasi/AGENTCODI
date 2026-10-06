#!/usr/bin/env bash
# GitHub runner entry point; the production builder still owns APK signing/verification.
set +x
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"

if [ "$#" -ne 0 ]; then
  echo "CI build-release-apk.sh does not accept positional arguments." >&2
  exit 1
fi
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"
: "${IMAGE:?IMAGE must name the prepared APK build container}"
: "${AGENTCODI_CI_SIGNING_DIR:?AGENTCODI_CI_SIGNING_DIR is required}"
case "$AGENTCODI_CI_SIGNING_DIR" in
  "$RUNNER_TEMP"/agentcodi-release-*) ;;
  *) echo "Signing directory must use the runner temporary release path." >&2; exit 1 ;;
esac

python3 "$SCRIPT_DIR/prepare-release-signing.py" --check
umask 077
mkdir --mode=700 -- "$AGENTCODI_CI_SIGNING_DIR"
cleanup() {
  rm -rf -- "$AGENTCODI_CI_SIGNING_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
python3 "$SCRIPT_DIR/prepare-release-signing.py" --directory "$AGENTCODI_CI_SIGNING_DIR"
AGENTCODI_RELEASE_KEY_ALIAS="$(cat "$AGENTCODI_CI_SIGNING_DIR/key-alias")"
AGENTCODI_RELEASE_CERT_SHA256="$(cat "$AGENTCODI_CI_SIGNING_DIR/certificate-sha256")"
export AGENTCODI_RELEASE_KEY_ALIAS AGENTCODI_RELEASE_CERT_SHA256
# Raw secret values are no longer needed and are never passed to Docker.
unset AGENTCODI_RELEASE_KEYSTORE_BASE64 AGENTCODI_RELEASE_STORE_PASSWORD AGENTCODI_RELEASE_KEY_PASSWORD

docker run --rm \
  --mount "type=bind,source=$PROJECT_ROOT,target=/workspace" -w /workspace \
  --mount "type=bind,source=$AGENTCODI_CI_SIGNING_DIR,target=/run/agentcodi-release,readonly" \
  -e AGENTCODI_CACHE_DIR=/workspace/.cache/android \
  -e AGENTCODI_OUTPUT_DIR=/workspace/output/release-apk \
  -e AGENTCODI_RELEASE_KEYSTORE=/run/agentcodi-release/release.keystore \
  -e AGENTCODI_RELEASE_PASSWORD_MODE=file \
  -e AGENTCODI_RELEASE_STORE_PASSWORD_FILE=/run/agentcodi-release/store-password \
  -e AGENTCODI_RELEASE_KEY_PASSWORD_FILE=/run/agentcodi-release/key-password \
  -e AGENTCODI_RELEASE_KEY_ALIAS -e AGENTCODI_RELEASE_CERT_SHA256 \
  "$IMAGE" ./scripts/build-release-apk.sh
