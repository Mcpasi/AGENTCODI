#!/usr/bin/env python3
"""Create separately signed lifecycle and rejection fixtures; never publish them."""
import argparse
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import gzip
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("repository", HERE / "scripts/package-edition/apt-repository.py")
repository = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repository)


def create(output):
    settings, lock = repository.config()
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        for version in ("1.0", "2.0"):
            root = output / ("v" + version[0])
            pool = root / "pool"
            pool.mkdir(parents=True)
            payload = work / version
            (payload / "DEBIAN").mkdir(parents=True)
            control = ("Package: repository-fixture\nVersion: " + version + "\nArchitecture: all\n"
                       "Maintainer: AGENTCODI CI\nDepends: dash\nDescription: Signed repository lifecycle fixture\n")
            (payload / "DEBIAN/control").write_text(control)
            program = payload / lock["target"]["prefix"].lstrip("/") / "bin/repository-fixture"
            program.parent.mkdir(parents=True)
            program.write_text("#!" + lock["target"]["prefix"] + "/bin/sh\nprintf '"
                               + version + "\\n'\n")
            program.chmod(0o755)
            deb = pool / "repository-fixture.deb"
            subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(payload), str(deb)], check=True)
            binary = root / "dists/stable/main/binary-aarch64"
            binary.mkdir(parents=True)
            text = control + "Filename: pool/repository-fixture.deb\nSize: " + str(deb.stat().st_size)
            text += "\nSHA256: " + repository.digest(deb) + "\n\n"
            (binary / "Packages").write_text(text)
            (binary / "Packages.gz").write_bytes(gzip.compress(text.encode(), mtime=0))
            for name in ("Packages", "Packages.gz"):
                path = binary / name
                by_hash = binary / "by-hash/SHA256" / repository.digest(path)
                by_hash.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, by_hash)
            release_dir = root / "dists/stable"
            manifest = {"format_version": 1, "repository": lock["repository"], "packages": {},
                        "files": {p.relative_to(root).as_posix():
                                  {"size": p.stat().st_size, "sha256": repository.digest(p)}
                                  for p in sorted(root.rglob("*")) if p.is_file()}}
            (release_dir / "repository-manifest.json").write_text(json.dumps(manifest) + "\n")
            write_release(release_dir, settings, expired=False)
    for name in ("bad-signature", "bad-index", "bad-deb", "expired"):
        shutil.copytree(output / "v2", output / name)
    signed = output / "bad-signature/dists/stable"
    for name in ("Release", "InRelease"):
        path = signed / name
        path.write_text(path.read_text().replace("AGENTCODI CI fixture", "AGENTCODI CI altered"))
    binary = output / "bad-index/dists/stable/main/binary-aarch64"
    for path in (binary / "Packages", binary / "Packages.gz",
                 *sorted((binary / "by-hash/SHA256").iterdir())):
        with path.open("ab") as stream:
            stream.write(b"corrupted-index")
    with (output / "bad-deb/pool/repository-fixture.deb").open("ab") as stream:
        stream.write(b"corrupted-deb")
    write_release(output / "expired/dists/stable", settings, expired=True)


def write_release(directory, settings, expired):
    now = datetime.now(timezone.utc).replace(microsecond=0)
    date = now - timedelta(days=10) if expired else now
    text = ("Origin: AGENTCODI CI fixture\nLabel: AGENTCODI CI fixture\nSuite: stable\nCodename: stable\n"
            "Architectures: aarch64\nComponents: main\nAcquire-By-Hash: yes\n"
            "Date: " + format_datetime(date, usegmt=True) + "\nValid-Until: "
            + format_datetime(date + timedelta(days=1), usegmt=True) + "\nSHA256:\n")
    for relative in ("main/binary-aarch64/Packages", "main/binary-aarch64/Packages.gz", "repository-manifest.json"):
        path = directory / relative
        text += " " + repository.digest(path) + " " + str(path.stat().st_size) + " " + relative + "\n"
    (directory / "Release").write_text(text)
    for name in ("InRelease", "Release.gpg"):
        (directory / name).unlink(missing_ok=True)
    repository.sign_release(directory, settings["signing_fingerprint"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    create(parser.parse_args().output)
