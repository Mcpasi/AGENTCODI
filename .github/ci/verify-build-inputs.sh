#!/usr/bin/env bash
# Verifies a directory against the pinned build-input manifest.
#
#     .github/ci/verify-build-inputs.sh [DIRECTORY]
#
# Without an argument it checks AGENTCODI_CACHE_DIR, falling back to
# .cache/android. Every entry must exist and match its pinned SHA-256; the
# script exits non-zero otherwise. It only reads files.
#
# Options:
#   --check-urls   additionally probe whether each downloadable artifact is
#                  still reachable upstream. The Termux package pool rolls and
#                  deletes superseded revisions, so pinned URLs die over time;
#                  this reports which artifacts exist only in your copy.
#   --no-sync      skip the check that the manifest still matches
#                  scripts/build-debug-apk.sh.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
MANIFEST="$SCRIPT_DIR/build-inputs.tsv"

check_urls=0
check_sync=1
target=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --check-urls) check_urls=1 ;;
    --no-sync) check_sync=0 ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) target="$1" ;;
  esac
  shift
done

if [ -z "$target" ]; then
  target="${AGENTCODI_CACHE_DIR:-$PROJECT_ROOT/.cache/android}"
fi
if [ ! -d "$target" ]; then
  echo "Not a directory: $target" >&2
  exit 1
fi
if [ ! -r "$MANIFEST" ]; then
  echo "Missing manifest: $MANIFEST" >&2
  exit 1
fi

# The manifest is generated from the build script; catch it going stale.
if [ "$check_sync" -eq 1 ] && [ -x "$SCRIPT_DIR/generate-build-inputs.sh" ]; then
  if regenerated="$("$SCRIPT_DIR/generate-build-inputs.sh" 2>/dev/null)"; then
    if ! printf '%s\n' "$regenerated" | diff -q - "$MANIFEST" >/dev/null 2>&1; then
      echo "The manifest no longer matches scripts/build-debug-apk.sh." >&2
      echo "Regenerate it:" >&2
      echo "  .github/ci/generate-build-inputs.sh > .github/ci/build-inputs.tsv" >&2
      printf '%s\n' "$regenerated" | diff -u "$MANIFEST" - \
        --label '.github/ci/build-inputs.tsv' --label 'regenerated' >&2 || true
      exit 1
    fi
    echo "Manifest is in sync with scripts/build-debug-apk.sh."
  fi
fi

echo "Verifying pinned build inputs in $target"
present=0
missing=0
mismatched=0
gone=0
missing_list=""

while IFS=$'\t' read -r path sha origin url; do
  case "$path" in ''|\#*) continue ;; esac
  file="$target/$path"
  if [ ! -f "$file" ]; then
    printf 'MISSING   %s\n' "$path"
    missing=$((missing + 1))
    missing_list="$missing_list$path"$'\n'
    continue
  fi
  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  if [ "$actual" != "$sha" ]; then
    printf 'MISMATCH  %s\n            expected %s\n            actual   %s\n' \
      "$path" "$sha" "$actual"
    mismatched=$((mismatched + 1))
    continue
  fi
  present=$((present + 1))
  if [ "$check_urls" -eq 1 ] && [ "$origin" = "download" ]; then
    code="$(curl -sS -o /dev/null -I -w '%{http_code}' --max-time 25 "$url" 2>/dev/null || true)"
    if [ "$code" != "200" ]; then
      printf 'UPSTREAM  %s (HTTP %s) — only your copy remains\n' "$path" "${code:-error}"
      gone=$((gone + 1))
    fi
  fi
done < "$MANIFEST"

total=$((present + missing + mismatched))
echo "-----"
printf 'verified %d of %d\n' "$present" "$total"
if [ "$check_urls" -eq 1 ]; then
  printf 'no longer downloadable upstream: %d\n' "$gone"
fi
if [ "$missing" -ne 0 ] || [ "$mismatched" -ne 0 ]; then
  printf 'missing %d, mismatched %d\n' "$missing" "$mismatched" >&2
  exit 1
fi
echo "All pinned build inputs are present and match their SHA-256."
