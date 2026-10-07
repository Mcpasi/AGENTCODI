#!/usr/bin/env python3
"""Install the Ubuntu architecture-check tool from signed official HTTPS sources."""
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile


SOURCES = """Types: deb
URIs: https://archive.ubuntu.com/ubuntu
Suites: noble noble-updates
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu
Suites: noble-security
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
"""


def install():
    if shutil.which("rg"):
        subprocess.run(["rg", "--version"], check=True)
        return

    release = platform.freedesktop_os_release()
    architecture = subprocess.check_output(
        ["dpkg", "--print-architecture"], text=True
    ).strip()
    if (release.get("ID"), release.get("VERSION_CODENAME"), architecture) != (
        "ubuntu", "noble", "amd64"
    ):
        raise RuntimeError("Host ripgrep installation requires Ubuntu 24.04 amd64.")
    if not Path("/usr/share/keyrings/ubuntu-archive-keyring.gpg").is_file():
        raise RuntimeError("The Ubuntu archive signing keyring is missing.")

    # Scope both commands to these sources. Leave the runner's mirror lists,
    # third-party sources and shared APT lists unchanged.
    # /tmp is traversable by _apt; RUNNER_TEMP can have private parent directories.
    with tempfile.TemporaryDirectory(prefix="agentcodi-host-apt-", dir="/tmp") as temporary:
        directory = Path(temporary)
        directory.chmod(0o755)  # APT's _apt download user needs traversal access.
        sources = directory / "ubuntu.sources"
        sources.write_text(SOURCES, encoding="utf-8")
        lists = directory / "lists"
        lists.mkdir()
        lists.chmod(0o755)
        options = [
            "-o", "Dir::Etc::sourcelist=" + str(sources),
            "-o", "Dir::Etc::sourceparts=-",
            "-o", "Dir::State::lists=" + str(lists),
            "-o", "Dir::Cache::pkgcache=",
            "-o", "Dir::Cache::srcpkgcache=",
            "-o", "Acquire::Languages=none",
            "-o", "Acquire::Retries=2",
            "-o", "Acquire::http::Timeout=20",
            "-o", "Acquire::https::Timeout=20",
            "-o", "APT::Update::Error-Mode=any",
        ]
        try:
            subprocess.run(
                ["sudo", "timeout", "--kill-after=10s", "180s", "apt-get"]
                + options + ["update"],
                check=True,
            )
            subprocess.run(
                ["sudo", "timeout", "--kill-after=10s", "120s", "apt-get"]
                + options + ["install", "-y", "--no-install-recommends", "ripgrep"],
                check=True,
            )
        finally:
            # APT creates root/_apt-owned partial directories. Only remove the
            # lists under this freshly created temporary directory with sudo.
            subprocess.run(["sudo", "rm", "-rf", "--", str(lists)], check=True)

    subprocess.run(["rg", "--version"], check=True)


if __name__ == "__main__":
    install()
