#!/usr/bin/env python3
"""Verify deployed HTTPS metadata with the committed key and expected run/commit."""
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import time
import urllib.request
import urllib.error
import zipfile

HERE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("repository", HERE / "scripts/package-edition/apt-repository.py")
repository = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repository)
settings, lock = repository.config()
base = lock["repository"]["url"]


last_request = 0.0


def request(path, method="GET", fresh=False):
    global last_request
    repository.relative(path)
    suffix = "?ci_run=" + os.environ["GITHUB_RUN_ID"] if fresh else ""
    headers = {"Cache-Control": "no-cache"} if fresh else {}
    for attempt in range(6):
        time.sleep(max(0, last_request + 0.5 - time.monotonic()))
        last_request = time.monotonic()
        query = urllib.request.Request(base + "/" + path + suffix, method=method, headers=headers)
        try:
            response = urllib.request.urlopen(query, timeout=60)
            if not response.geturl().startswith(base + "/"):
                response.close()
                raise ValueError("Published repository redirects outside its HTTPS endpoint")
            return response
        except urllib.error.HTTPError as error:
            if error.code not in (429, 502, 503, 504) or attempt == 5:
                raise
            retry = error.headers.get("Retry-After", "")
            delay = min(180, max(10 * 2 ** attempt, int(retry) if retry.isdigit() else 0))
            error.close()
            print("Transient Pages response", error.code, "for", path,
                  "- retrying in", delay, "seconds", flush=True)
            time.sleep(delay)


def fetch(path):
    fresh = path.startswith("dists/") and "/by-hash/" not in path
    with request(path, fresh=fresh) as response:
        return response.read()


def check_pack(data, names, files):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        members = archive.infolist()
        if len(members) != len(names) or {item.filename for item in members} != set(names):
            raise ValueError("Source pack contains missing, duplicate or undeclared objects")
        for item in members:
            record = files[item.filename]
            if item.file_size != record["size"]:
                raise ValueError("Packed source size differs: " + item.filename)
            if hashlib.sha256(archive.read(item)).hexdigest() != record["sha256"]:
                raise ValueError("Packed source checksum differs: " + item.filename)


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

        remaining = set(source_paths)
        pack_count = 0
        for pack, names in manifest.get("source_packs", {}).items():
            if not remaining.intersection(names):
                continue
            data = fetch(pack)
            record = manifest["files"][pack]
            if len(data) != record["size"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
                raise ValueError("Published source pack differs: " + pack)
            check_pack(data, names, manifest["files"])
            remaining.difference_update(names)
            pack_count += 1
        print("Source objects verified through", pack_count, "signed packs;",
              len(remaining), "large payloads remain", flush=True)
        for path in sorted(remaining):
            record = manifest["files"][path]
            with request(path, method="HEAD") as response:
                if int(response.headers["Content-Length"]) != record["size"]:
                    raise ValueError("Published source payload differs: " + path)
        print("Public HTTPS, pinned signatures, freshness, indexes, catalog DEBs and complete source availability verified.", flush=True)


for attempt in range(1, 21):
    try:
        check()
        break
    except Exception as error:
        if attempt == 20:
            raise
        print("Waiting for the deployed snapshot:", type(error).__name__, str(error), flush=True)
        time.sleep(10)
