#!/usr/bin/env bash
# Prove the provisioned private key can sign for the pinned public trust anchor.
set -euo pipefail
set +x
test -n "${AGENTCODI_APT_SIGNING_KEY:-}" || {
  echo 'Missing Actions secret AGENTCODI_APT_SIGNING_KEY; add it under repository Actions secrets.' >&2
  exit 78
}
script_root="$(cd -- "$(dirname -- "$0")" && pwd)"
signing_home="$(mktemp -d)"
chmod 700 "$signing_home"
export GNUPGHOME="$signing_home"
trap 'gpgconf --kill gpg-agent >/dev/null 2>&1 || true; rm -rf -- "$signing_home"' EXIT
python3 - "$script_root" <<'PY'
import importlib.util, os, subprocess, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location("repository", Path(sys.argv[1]) / "apt-repository.py")
repository = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repository)
settings, _ = repository.config()
work = Path(os.environ["GNUPGHOME"])
ring = work / "public.gpg"
repository.export_keyring(settings, ring)
subprocess.run(["gpg", "--batch", "--import"],
               input=os.environ["AGENTCODI_APT_SIGNING_KEY"].encode(),
               stdout=subprocess.DEVNULL, check=True)
records = subprocess.check_output(["gpg", "--batch", "--with-colons", "--list-secret-keys"], text=True)
if repository.fingerprints(records) != [settings["signing_fingerprint"]]:
    raise SystemExit("Secret must contain exactly the configured primary private key")
message = work / "signing-proof"
message.write_text("AGENTCODI Package Edition signing prerequisite check\n")
signed = work / "signing-proof.asc"
subprocess.run(["gpg", "--batch", "--no-tty", "--pinentry-mode", "loopback",
                "--passphrase-fd", "0", "--digest-algo", "SHA256",
                "--local-user", settings["signing_fingerprint"], "--armor",
                "--output", str(signed), "--detach-sign", str(message)], check=True,
               input=(os.environ.get("AGENTCODI_APT_SIGNING_PASSPHRASE", "") + "\n").encode())
subprocess.run(["gpgv", "--keyring", str(ring), str(signed), str(message)], check=True)
print("Public fingerprint, private signing capability and passphrase verified.")
PY
