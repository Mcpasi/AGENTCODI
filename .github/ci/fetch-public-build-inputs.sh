#!/usr/bin/env bash
# Fetch downloadable inputs without the private backup or locally built Codex.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
target="${1:?Pass a cache directory}"
mkdir -p "$target"
failed=0
while IFS=$'\t' read -r path sha origin url; do
  case "$path" in ''|\#*) continue ;; esac
  [ "$origin" = download ] || continue
  destination="$target/$path"
  mkdir -p "$(dirname -- "$destination")"
  if [ -f "$destination" ] && printf '%s  %s\n' "$sha" "$destination" | sha256sum --check --status; then
    continue
  fi
  sources=("$url")
  case "$url" in
    https://grimler.se/termux/termux-main/*)
      relative="${url#https://grimler.se/termux/termux-main/}"
      sources+=("https://packages-cf.termux.dev/apt/termux-main/$relative"
        "https://packages.termux.dev/apt/termux-main/$relative")
      bucket="${relative#pool/main/}"
      bucket="${bucket%%/*}"
      package="${relative%/*}"
      package="${package##*/}"
      filename="${relative##*/}"
      sources+=("https://archive.org/download/termux_pkgs_archive_$bucket/$package/$filename")
      ;;
  esac
  accepted=0
  for candidate in "${sources[@]}"; do
    candidate="${candidate//+/%2B}"
    partial="$destination.partial"
    rm -f -- "$partial"
    if curl --fail --location --silent --show-error --retry 2 \
        --connect-timeout 20 --max-time 300 --output "$partial" "$candidate"; then
      if printf '%s  %s\n' "$sha" "$partial" | sha256sum --check --status; then
        mv -- "$partial" "$destination"
        echo "PUBLIC OK $path $sha $candidate"
        accepted=1
        break
      fi
      echo "PUBLIC HASH MISMATCH $path $candidate" >&2
    fi
    rm -f -- "$partial"
  done
  if [ "$accepted" = 0 ]; then
    echo "PUBLIC MISSING $path" >&2
    failed=$((failed + 1))
  fi
done < "$SCRIPT_DIR/build-inputs.tsv"
[ "$failed" = 0 ]
