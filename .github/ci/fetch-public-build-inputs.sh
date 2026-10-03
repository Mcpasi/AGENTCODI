#!/usr/bin/env bash
# Fetch downloadable inputs without the private backup or locally built Codex.
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
target="${1:?Pass a cache directory}"
mkdir -p "$target"

# The rolling pool and public archive do not retain every revision. These
# original Termux CI artifacts were verified against the manifest pins. The
# workflow's read-only GitHub token suffices; no private input repository is used.
try_termux_artifact() {
  local path="$1" sha="$2" destination="$3" artifact name entry
  case "$path" in
    nodejs-lts-24.18.0-aarch64.deb)
      artifact=8567576638; name=nodejs-lts_24.18.0_aarch64.deb ;;
    npm-11.19.0-all.deb)
      artifact=8746673348; name=npm_11.19.0_all.deb ;;
    patchelf-0.19.1-aarch64.deb)
      artifact=8126700666; name=patchelf_0.19.1_aarch64.deb ;;
    *) return 1 ;;
  esac
  if [ -z "${GH_TOKEN:-}" ]; then
    echo "A read-only GH_TOKEN is required for the public Termux artifact: $path" >&2
    return 1
  fi
  local staging
  staging="$(mktemp -d)"
  if curl --fail --location --silent --show-error --retry 3 \
      --connect-timeout 20 --max-time 300 \
      -H "Authorization: Bearer $GH_TOKEN" \
      --output "$staging/artifact.zip" \
      "https://api.github.com/repos/termux/termux-packages/actions/artifacts/$artifact/zip" \
      && unzip -p "$staging/artifact.zip" '*.tar' > "$staging/packages.tar"; then
    entry="$(tar -tf "$staging/packages.tar" | awk -F/ -v name="$name" '$NF == name {print}')"
    if [ -n "$entry" ] \
        && tar -xOf "$staging/packages.tar" -- "$entry" > "$staging/package.deb" \
        && printf '%s  %s\n' "$sha" "$staging/package.deb" | sha256sum --check --status; then
      mv -- "$staging/package.deb" "$destination"
      rm -rf -- "$staging"
      echo "PUBLIC OK $path $sha Termux artifact $artifact"
      return 0
    fi
    echo "Public Termux artifact has no matching pinned package: $path" >&2
  fi
  rm -rf -- "$staging"
  return 1
}

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
    if curl --fail --location --http1.1 --silent --show-error --retry 3 --retry-all-errors \
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
  if [ "$accepted" = 0 ] && try_termux_artifact "$path" "$sha" "$destination"; then
    accepted=1
  fi
  if [ "$accepted" = 0 ]; then
    echo "PUBLIC MISSING $path" >&2
    failed=$((failed + 1))
  fi
done < "$SCRIPT_DIR/build-inputs.tsv"
[ "$failed" = 0 ]
