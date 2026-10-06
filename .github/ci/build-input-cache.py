#!/usr/bin/env python3
"""List the current edition's verified cache inputs; keep SDK bytes private."""
import os
from pathlib import Path, PurePosixPath
import re
import sys


def cache_paths(manifest):
    paths = []
    seen = set()
    for line in manifest.splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise ValueError("Malformed build-input row")
        path, sha, origin, url = fields
        parts = PurePosixPath(path)
        if parts.is_absolute() or ".." in parts.parts or str(parts) != path:
            raise ValueError("Unsafe build-input path: " + path)
        if path in seen or not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise ValueError("Duplicate path or invalid SHA-256: " + path)
        if origin != "download" or not url.startswith("https://"):
            raise ValueError("Unsupported build-input source: " + path)
        seen.add(path)
        # Android SDK backups stay in the existing private mirror or upstream.
        if path.startswith("platform-"):
            continue
        paths.append(".cache/android/" + path)
    if not paths:
        raise ValueError("No edition cache inputs")
    return paths


def main():
    manifest = Path(__file__).with_name("build-inputs.tsv")
    paths = cache_paths(manifest.read_text())
    print("\n".join(paths))
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write("paths<<AGENTCODI_CACHE_PATHS\n")
            output.write("\n".join(paths) + "\nAGENTCODI_CACHE_PATHS\n")


if __name__ == "__main__":
    main()
