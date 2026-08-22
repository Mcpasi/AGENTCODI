#!/usr/bin/env bash
set -Eeuo pipefail

CLANG_BIN="${AGENTCODI_RIPGREP_CLANG:-}"
if [[ "$CLANG_BIN" != /* ]] || [[ ! -x "$CLANG_BIN" ]]; then
  echo "AGENTCODI_RIPGREP_CLANG must name an absolute executable." >&2
  exit 1
fi

declare -a FILTERED_ARGUMENTS=()
for argument in "$@"; do
  case "$argument" in
    -Wl,-rpath,*|-Wl,-rpath=*)
      ;;
    *)
      FILTERED_ARGUMENTS+=("$argument")
      ;;
  esac
done

exec "$CLANG_BIN" \
  --target=aarch64-linux-android29 \
  "${FILTERED_ARGUMENTS[@]}" \
  -Wl,-z,max-page-size=16384 \
  -Wl,-z,common-page-size=16384
