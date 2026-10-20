# Build-phase replacement for the Termux downloader. Only prepared SHA-256
# addressed inputs can enter a build; no repository packages or URL fallback.
termux_download() {
  local destination="$2" checksum="${3:-}"
  if ! [[ "$checksum" =~ ^[a-f0-9]{64}$ ]]; then
    echo "A source recipe requested an input without a SHA-256: $1" >&2
    return 1
  fi
  local prepared="$AGENTCODI_RUNTIME_CACHE/blobs/$checksum"
  if [ ! -f "$prepared" ] || [ -L "$prepared" ] \
      || ! printf '%s  %s\n' "$checksum" "$prepared" | sha256sum --check --status; then
    echo "Missing or changed prepared source input: $1 ($checksum)" >&2
    return 1
  fi
  mkdir -p "$(dirname -- "$destination")"
  cp "$prepared" "$destination"
}
