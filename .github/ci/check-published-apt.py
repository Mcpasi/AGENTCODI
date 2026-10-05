#!/usr/bin/env python3
"""Verify deployed HTTPS metadata with the committed key and expected run/commit."""
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
from email.utils import parsedate_to_datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import urllib.request

HERE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("repository", HERE / "scripts/package-edition/apt-repository.py")
repository = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repository)
settings, lock = repository.config()
base = lock["repository"]["url"]


def fetch(path):
    repository.relative(path)
    request = urllib.request.Request(base + "/" + path + "?ci_run=" + os.environ["GITHUB_RUN_ID"],
                                     headers={"Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=60) as response:
        if not response.geturl().startswith(base + "/"):
            raise ValueError("Published repository redirects outside its HTTPS endpoint")
        return response.read()


def check():
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        release = root / "dists/stable"
        release.mkdir(parents=True)
        ring = root / "configured-keyring.gpg"
        repository.export_keyring(settings, ring)
        for name in ("Release", "InRelease", "Release.gpg"):
            (release / name).write_bytes(fetch("dists/stable/" + name))
        repository.signature(root, ring)
        text = (release / "Release").read_text()
        expires = parsedate_to_datetime(next(line.split(": ", 1)[1] for line in text.splitlines()
                                             if line.startswith("Valid-Until: ")))
        if expires <= datetime.now(timezone.utc):
            raise ValueError("Published Release has expired")
        downloaded = {}
        for line in text.split("\nSHA256:\n", 1)[1].splitlines():
            expected, size, relative = line.split()
            data = fetch("dists/stable/" + relative)
            if len(data) != int(size) or hashlib.sha256(data).hexdigest() != expected:
                raise ValueError("Published metadata checksum differs: " + relative)
            downloaded[relative] = data
            if relative.startswith("main/binary-aarch64/"):
                if fetch("dists/stable/main/binary-aarch64/by-hash/SHA256/" + expected) != data:
                    raise ValueError("Published by-hash index differs")
        manifest = json.loads(downloaded["repository-manifest.json"])
        if (manifest["consumer_run"] != os.environ["GITHUB_RUN_ID"]
                or manifest["consumer_commit"] != os.environ["GITHUB_SHA"]
                or manifest["signing_fingerprint"] != settings["signing_fingerprint"]):
            raise ValueError("Pages is serving a different signed snapshot")
        for name in ("python", "nodejs-lts", "npm", "git", "ripgrep", "agentcodi-package-keyring"):
            metadata = manifest["packages"][name]
            if hashlib.sha256(fetch(metadata["filename"])).hexdigest() != metadata["sha256"]:
                raise ValueError("Published catalog DEB differs: " + name)
        source_paths = set()
        for item in manifest["provenance"].values():
            if "source_manifest" in item:
                path = item["source_manifest"]
                data = fetch(path)
                record = manifest["files"][path]
                if len(data) != record["size"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
                    raise ValueError("Published source manifest differs")
                description = json.loads(data)
                if description["original_archive_sha256"] != item["original_archive_sha256"]:
                    raise ValueError("Published source provenance differs")
                source_paths.update(member["object"] for member in description["members"]
                                    if "object" in member)
            else:
                source_paths.add(item["source_archive"])

        def check_source(path):
            repository.relative(path)
            record = manifest["files"][path]
            request = urllib.request.Request(base + "/" + path, method="HEAD")
            with urllib.request.urlopen(request, timeout=30) as response:
                if (not response.geturl().startswith(base + "/")
                        or int(response.headers["Content-Length"]) != record["size"]):
                    raise ValueError("Published source payload differs: " + path)

        with ThreadPoolExecutor(max_workers=16) as executor:
            list(executor.map(check_source, sorted(source_paths)))
        print("Public HTTPS, pinned signatures, freshness, indexes, catalog DEBs and source availability verified.")


for attempt in range(1, 21):
    try:
        check()
        break
    except Exception as error:
        if attempt == 20:
            raise
        print("Waiting for the deployed snapshot:", type(error).__name__, str(error))
        time.sleep(10)
