#!/usr/bin/env python3
"""Download a complete source archive authenticated by the pinned APT signing key."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import urllib.request


HERE = Path(__file__).resolve().parent


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def download(group, output):
    repository = module("repository", "apt-repository.py")
    sources = module("sources", "source-store.py")
    settings, lock = repository.config()
    base = lock["repository"]["url"]
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        release = root / "dists/stable"
        release.mkdir(parents=True)
        ring = root / "keyring.gpg"
        repository.export_keyring(settings, ring)

        def fetch(name):
            repository.relative(name)
            target = root / name
            if not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                request = urllib.request.Request(base + "/" + name)
                with urllib.request.urlopen(request, timeout=60) as response:
                    if not response.geturl().startswith(base + "/"):
                        raise ValueError("Source download redirects outside the repository")
                    with target.open("wb") as stream:
                        while chunk := response.read(1024 * 1024):
                            stream.write(chunk)
            return target

        for name in ("Release", "InRelease", "Release.gpg"):
            fetch("dists/stable/" + name)
        repository.signature(root, ring)
        entries = (release / "Release").read_text().split("\nSHA256:\n", 1)[1].splitlines()
        expected = next(line.split() for line in entries
                        if line.split()[2] == "repository-manifest.json")
        path = fetch("dists/stable/repository-manifest.json")
        if repository.digest(path) != expected[0] or path.stat().st_size != int(expected[1]):
            raise ValueError("Signed repository manifest differs")
        manifest = json.loads(path.read_text())

        def payload(name):
            record = manifest["files"][name]
            path = fetch(name)
            if repository.digest(path) != record["sha256"] or path.stat().st_size != record["size"]:
                raise ValueError("Signed source payload differs: " + name)
            return path

        source = manifest["provenance"][group]
        if "source_manifest" in source:
            description = json.loads(payload(source["source_manifest"]).read_text())
            sources.reconstruct(description, payload, output)
        else:
            # Previously published snapshots may contain whole source archives.
            if output.exists():
                raise ValueError("Source output already exists")
            import shutil
            shutil.copyfile(payload(source["source_archive"]), output)
    print("Complete authenticated sources written to", output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group", required=True,
                        choices=("bootstrap", "python", "node", "git", "ripgrep", "keyring"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    download(args.group, args.output)
