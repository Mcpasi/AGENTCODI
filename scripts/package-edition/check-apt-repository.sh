#!/usr/bin/env bash
# Authenticate the generated indexes with host APT; never install ARM64 DEBs here.
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo 'Usage: check-apt-repository.sh SNAPSHOT' >&2
  exit 64
fi
repository_root="$(realpath "$1")"
script_root="$(cd -- "$(dirname -- "$0")" && pwd)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
python3 "$script_root/apt-repository.py" verify --root "$repository_root" \
  --keyring "$repository_root/keys/agentcodi-package.gpg"
python3 - "$repository_root" "$temporary" <<'PY'
from pathlib import Path
import sys
root, work = map(Path, sys.argv[1:])
for name in ("lists/partial", "archives/partial", "empty"):
    (work / name).mkdir(parents=True, exist_ok=True)
(work / "status").touch()
(work / "sources.list").write_text(
    "deb [arch=aarch64 signed-by=" + str(root / "keys/agentcodi-package.gpg")
    + "] " + root.as_uri() + " stable main\n")
values = {
    "Dir::Etc::main": "/dev/null", "Dir::Etc::parts": str(work / "empty"),
    "Dir::Etc::sourcelist": str(work / "sources.list"),
    "Dir::Etc::sourceparts": str(work / "empty"),
    "Dir::State::status": str(work / "status"), "Dir::State::lists": str(work / "lists"),
    "Dir::Cache::archives": str(work / "archives"),
    "Dir::Cache::pkgcache": "", "Dir::Cache::srcpkgcache": "",
    "APT::Architecture": "aarch64", "APT::Sandbox::User": "",
    "Acquire::Languages": "none", "Acquire::AllowInsecureRepositories": "false",
    "Acquire::AllowDowngradeToInsecureRepositories": "false",
    "APT::Get::AllowUnauthenticated": "false", "APT::Update::Error-Mode": "any",
}
text = "#clear APT::Architectures;\nAPT::Architectures { \"aarch64\"; };\n"
for key, value in values.items():
    if any(c in value for c in '"\\\n'):
        raise SystemExit("Unsupported path in APT fixture")
    text += key + ' "' + value + '";\n'
(work / "apt.conf").write_text(text)
PY
export APT_CONFIG="$temporary/apt.conf"
apt-get update
apt-cache policy python nodejs-lts npm git ripgrep agentcodi-package-keyring
apt-get --download-only --no-install-recommends -y install \
  dash bash apt dpkg ca-certificates python nodejs-lts npm git ripgrep agentcodi-package-keyring
# A valid key remains required even when a repository was accepted previously.
cp "$repository_root/keys/agentcodi-package.gpg" "$temporary/trusted.gpg"
: > "$temporary/untrusted.gpg"
sed -i "s|$repository_root/keys/agentcodi-package.gpg|$temporary/untrusted.gpg|" "$temporary/sources.list"
rm -rf "$temporary/lists"
mkdir -p "$temporary/lists/partial"
if apt-get update > "$temporary/untrusted.log" 2>&1; then
  echo 'APT accepted the repository without its scoped trust key' >&2
  exit 1
fi
echo 'Signed metadata, dependency downloads and untrusted-key rejection passed.'
