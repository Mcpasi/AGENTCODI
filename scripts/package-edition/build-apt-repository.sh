#!/usr/bin/env bash
# Run only on a trusted edition job. Signing material must be Actions secrets.
set -euo pipefail
set +x
script_root="$(cd -- "$(dirname -- "$0")" && pwd)"
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo 'Usage: build-apt-repository.sh ARTIFACTS NEW_OUTPUT [PREVIOUS_SNAPSHOT]' >&2
  exit 64
fi
test -n "${AGENTCODI_APT_SIGNING_KEY:-}" || {
  echo 'Missing Actions secret AGENTCODI_APT_SIGNING_KEY' >&2
  exit 78
}
signing_home="$(mktemp -d)"
chmod 700 "$signing_home"
export GNUPGHOME="$signing_home"
trap 'gpgconf --kill gpg-agent >/dev/null 2>&1 || true; rm -rf -- "$signing_home"' EXIT
# Import via stdin; neither private bytes nor passphrases are written to artifacts.
python3 - "$script_root" <<'PY'
import importlib.util, os, subprocess, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location("repository", Path(sys.argv[1]) / "apt-repository.py")
repository = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repository)
settings, _ = repository.config()
subprocess.run(["gpg", "--batch", "--import"],
               input=os.environ["AGENTCODI_APT_SIGNING_KEY"].encode(), check=True,
               stdout=subprocess.DEVNULL)
records = subprocess.check_output(["gpg", "--batch", "--with-colons", "--list-secret-keys"], text=True)
if repository.fingerprints(records) != [settings["signing_fingerprint"]]:
    raise SystemExit("Secret must contain exactly the configured primary signing key")
PY
unset AGENTCODI_APT_SIGNING_KEY
arguments=(build --artifacts "$1" --output "$2")
if [ "$#" -eq 3 ]; then arguments+=(--previous "$3"); fi
python3 "$script_root/apt-repository.py" "${arguments[@]}"
