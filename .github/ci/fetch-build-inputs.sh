#!/usr/bin/env bash
# Restores the pinned build inputs into a cache directory.
#
#     .github/ci/fetch-build-inputs.sh [DIRECTORY]
#
# Without an argument it fills AGENTCODI_CACHE_DIR, falling back to
# .cache/android. Files already present with a matching pin are kept, so
# re-runs only fetch what is missing.
#
# Each missing file is taken from the mirror first and from its pinned upstream
# URL otherwise. Either way the SHA-256 from the manifest is verified before the
# file is moved into place, so the source never matters for trust.
#
# The mirror is a private backup, which is what keeps it complete and lawful:
# the Android SDK Licence Agreement permits copies for backup purposes but
# forbids redistribution (section 3.4), and several Termux packages carry
# GPL-2.0 or GPL-3.0 notices whose obligations only trigger on redistribution.
# Reading it therefore needs an authenticated gh — in CI a token secret with
# read access, because the default workflow token cannot reach another
# repository. The upstream fallback needs nothing.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
MANIFEST="$SCRIPT_DIR/build-inputs.tsv"
MIRROR_REPO="${AGENTCODI_INPUTS_REPO:-Mcpasi/agentcodi-build-inputs}"

if [ -z "${AGENTCODI_INPUTS_TAG:-}" ]; then
  # One release per pin set: the tag follows the APK version.
  AGENTCODI_INPUTS_TAG="$(sed -n 's/^APP_VERSION="\(.*\)"$/\1/p' \
    "$PROJECT_ROOT/scripts/build-debug-apk.sh" | head -1)"
fi
if [ -z "$AGENTCODI_INPUTS_TAG" ]; then
  echo "Cannot determine the mirror release tag; set AGENTCODI_INPUTS_TAG." >&2
  exit 1
fi

target="${1:-${AGENTCODI_CACHE_DIR:-$PROJECT_ROOT/.cache/android}}"
case "$target" in
  ''|/|"$PROJECT_ROOT"|"$PROJECT_ROOT"/|"${HOME:-/nonexistent}")
    echo "Refusing unsafe target directory: $target" >&2
    exit 1
    ;;
esac
if ! command -v curl >/dev/null 2>&1; then
  echo "curl is required." >&2
  exit 1
fi
mirror_available=1
if ! command -v gh >/dev/null 2>&1; then
  echo "Note: gh is unavailable; falling back to the pinned upstream URLs." >&2
  mirror_available=0
fi
if [ ! -r "$MANIFEST" ]; then
  echo "Missing manifest: $MANIFEST" >&2
  exit 1
fi

mkdir -p "$target"
staging="$(mktemp -d)"
trap 'rm -rf -- "$staging"' EXIT

echo "Mirror: $MIRROR_REPO @ $AGENTCODI_INPUTS_TAG"
echo "Target: $target"

kept=0
from_mirror=0
from_upstream=0
failed=0

# Confirms the pinned hash of a staged download.
staged_matches() {
  local staged="$1" expected="$2"
  [ -f "$staged" ] || return 1
  if [ "$(sha256sum "$staged" | cut -d' ' -f1)" != "$expected" ]; then
    rm -f -- "$staged"
    return 1
  fi
  return 0
}

try_mirror() {
  local asset="$1" expected="$2" staged="$3"
  [ "$mirror_available" -eq 1 ] || return 1
  rm -f -- "$staged"
  gh release download "$AGENTCODI_INPUTS_TAG" --repo "$MIRROR_REPO" \
    --pattern "$asset" --dir "$staging" >/dev/null 2>&1 || return 1
  staged_matches "$staged" "$expected"
}

try_upstream() {
  local source_url="$1" expected="$2" staged="$3"
  rm -f -- "$staged"
  curl --fail --location --silent --show-error \
    --retry 3 --retry-delay 2 --output "$staged" "$source_url" 2>/dev/null || return 1
  staged_matches "$staged" "$expected"
}

while IFS=$'\t' read -r path sha origin url; do
  case "$path" in ''|\#*) continue ;; esac
  destination="$target/$path"
  if [ -f "$destination" ] \
      && [ "$(sha256sum "$destination" | cut -d' ' -f1)" = "$sha" ]; then
    kept=$((kept + 1))
    continue
  fi

  asset="${path##*/}"
  staged="$staging/$asset"
  source_label=""
  if try_mirror "$asset" "$sha" "$staged"; then
    source_label="mirror"
    from_mirror=$((from_mirror + 1))
  elif [ "$origin" = "download" ] && [ "$url" != "-" ] \
      && try_upstream "$url" "$sha" "$staged"; then
    source_label="upstream"
    from_upstream=$((from_upstream + 1))
  else
    printf 'FAILED    %s (no source produced the pinned bytes)\n' "$path" >&2
    failed=$((failed + 1))
    continue
  fi

  mkdir -p "$(dirname -- "$destination")"
  mv -f -- "$staged" "$destination"
  printf '%-9s %s\n' "$source_label" "$path"
done < "$MANIFEST"

echo "-----"
printf 'kept %d, mirror %d, upstream %d, failed %d\n' \
  "$kept" "$from_mirror" "$from_upstream" "$failed"
if [ "$failed" -ne 0 ]; then
  exit 1
fi
echo "All pinned build inputs are in place."
