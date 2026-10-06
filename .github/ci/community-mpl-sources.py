#!/usr/bin/env python3
"""Collect and verify the complete, pinned MPL source delivery for Community Codex."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import tomllib
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[2]
BASE = REPO / "third_party/community-codex"
FILENAMES = ("MPL-SOURCES.zip", "MPL-SOURCE-INDEX.json", "MPL-SOURCE-OFFER.txt")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def covered(index):
    return sorted((c for c in index["components"] if "MPL-2.0" in c.get("license", "")),
                  key=lambda c: (c["name"], c["version"]))


def source_location(component):
    source = component["source"]
    if source.startswith("registry+"):
        name = component["name"] + "-" + component["version"] + ".crate"
        return "sources/" + name, "https://static.crates.io/crates/" + component["name"] + "/" + name
    match = re.fullmatch(r"git\+https://github.com/([\w.-]+/[\w.-]+)[.]git[?]rev=([0-9a-f]{40})#\2", source)
    require(match is not None, "Unreviewed MPL Git source: " + source)
    repository, revision = match.groups()
    name = repository.replace("/", "-") + "-" + revision + ".tar.gz"
    return "sources/" + name, "https://codeload.github.com/" + repository + "/tar.gz/" + revision


def notice(components):
    text = """MPL-2.0 SOURCE CODE — AGENTCODI PACKAGE EDITION

The Community Codex runtime includes the MPL-2.0 components listed below.
Their complete, unmodified Source Code Form is supplied free of charge with
this APK in MPL-SOURCES.zip. Original source files, copyright notices and
MPL-2.0 license texts are retained. The sources remain available under MPL-2.0;
AGENTCODI's Apache-2.0 license does not restrict your rights to these sources.
This source delivery fulfills the source-availability notice in MPL-2.0 §3.2(a).

How to obtain the sources:
1. In the app, open Settings > Licenses and notices > Codex dependency notices
   and choose Save MPL sources. Select a destination in Android's
   file picker. The app saves the complete source ZIP without changing its bytes.
2. Alternatively, open this APK as a ZIP and copy
   assets/third-party/codex/MPL-SOURCES.zip. No account or network access is needed.
3. Open the saved ZIP, then open its sources/*.crate or sources/*.tar.gz archives
   with a tar/gzip-capable archive tool. Rust .crate files are gzip-compressed tar
   archives. The original license files are inside each source archive.

This same ZIP, its source index and this notice are also retained under
third_party/community-codex in the AGENTCODI source repository. The upstream
download URLs below are an additional way to obtain the identical pinned sources.
The delivered ZIP remains usable if an upstream URL becomes unavailable.

The source selection follows the pinned Community Cargo.lock and conservative
normal/build dependency inventory. It does not claim an independent reproduction
of the Community binaries. A shared Git snapshot contains both nucleo components.

Components and exact upstream source locations:
"""
    for c in components:
        path, url = source_location(c)
        text += "\n" + c["name"] + " " + c["version"] + " — MPL-2.0\n" + path + "\n" + url + "\n"
    return text.encode("utf-8")


def source_members(data):
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = archive.getmembers()
        names = [m.name for m in members]
        require(len(names) == len(set(names)), "Duplicate MPL source archive member")
        for member in members:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and ".." not in path.parts,
                    "Unsafe MPL source archive path")
        return {m.name: archive.extractfile(m).read() for m in members if m.isfile()}


def evidence(archive_data, index_data, notice_data, dependencies, lock_data):
    index = json.loads(index_data)
    selected = covered(dependencies)
    require(index["format_version"] == 1 and
            index["codex_source_commit"] == dependencies["codex_source_commit"] and
            index["cargo_lock_sha256"] == digest(lock_data) == dependencies["cargo_lock_sha256"],
            "MPL source release/Cargo.lock binding")
    require(digest(archive_data) == index["archive_sha256"], "MPL source ZIP checksum")
    require(notice_data == notice(selected) and digest(notice_data) == index["notice_sha256"],
            "MPL source availability notice differs")
    records = index["components"]
    require([(c["name"], c["version"]) for c in records] ==
            [(c["name"], c["version"]) for c in selected], "MPL source component coverage differs")
    locked = {(p["name"], p["version"]): p for p in tomllib.loads(lock_data.decode())["package"]}
    expected = {"MPL-SOURCE-OFFER.txt"}
    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate MPL source ZIP entry")
        require(archive.read("MPL-SOURCE-OFFER.txt") == notice_data, "MPL bundled notice differs")
        cache = {}
        for record, component in zip(records, selected):
            package = locked.get((component["name"], component["version"]))
            path, url = source_location(component)
            require(package is not None and record["source"] == component["source"] == package.get("source")
                    and record["checksum"] == component["checksum"] == package.get("checksum")
                    and record["path"] == path and record["url"] == url,
                    "MPL source package/revision binding: " + component["name"])
            expected.add(path)
            data = archive.read(path)
            require(len(data) == record["size"] and digest(data) == record["sha256"],
                    "MPL source archive checksum: " + component["name"])
            if component["checksum"]:
                require(digest(data) == component["checksum"], "MPL published crate checksum")
            if path not in cache:
                cache[path] = source_members(data)
            members = cache[path]
            if component["source"].startswith("git+"):
                revision = component["source"].rsplit("#", 1)[1]
                repository_name = component["source"].split("?")[0].rsplit("/", 1)[1].removesuffix(".git")
                require(all(name.startswith(repository_name + "-" + revision + "/") for name in members),
                        "MPL Git source snapshot revision differs")
            manifests = [value for name, value in members.items() if name.endswith("/Cargo.toml")]
            require(any(tomllib.loads(value.decode()).get("package", {}).get("name") == component["name"]
                        and tomllib.loads(value.decode()).get("package", {}).get("version") == component["version"]
                        for value in manifests), "MPL source Cargo package/version missing")
            legal = [value for name, value in members.items()
                     if re.fullmatch(r"(?i)(LICENSE(?:[.]txt)?)", PurePosixPath(name).name)]
            require(any(b"Mozilla Public License Version 2.0" in value for value in legal),
                    "Original MPL license missing from source archive")
            require(any(digest(value) in {f["sha256"] for f in component["files"]}
                        for value in legal), "MPL original source/license notice binding")
        require(set(names) == expected, "MPL source ZIP file set differs")
    return {"components": len(records), "source_archives": len(expected) - 1,
            "archive_sha256": digest(archive_data), "notice_sha256": digest(notice_data),
            "delivery": "Complete original sources bundled in APK; legal screen offers notice and source export"}


def collect(output):
    dependencies = json.loads((BASE / "DEPENDENCY-LICENSE-INDEX.json").read_bytes())
    lock_data = (BASE / "Cargo.lock").read_bytes()
    components = covered(dependencies)
    contents = {"MPL-SOURCE-OFFER.txt": notice(components)}
    records = []
    for component in components:
        path, url = source_location(component)
        if path not in contents:
            with urllib.request.urlopen(url, timeout=60) as response:
                contents[path] = response.read()
        data = contents[path]
        require(not component["checksum"] or digest(data) == component["checksum"],
                "Downloaded MPL crate checksum: " + component["name"])
        records.append({key: component[key] for key in ("name", "version", "source", "checksum")} |
                       {"path": path, "url": url, "size": len(data), "sha256": digest(data)})
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(contents.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)
    data = stream.getvalue()
    index = {"format_version": 1, "codex_source_commit": dependencies["codex_source_commit"],
             "cargo_lock_sha256": digest(lock_data), "archive_sha256": digest(data),
             "notice_sha256": digest(contents["MPL-SOURCE-OFFER.txt"]), "components": records}
    index_data = (json.dumps(index, indent=2) + "\n").encode()
    result = evidence(data, index_data, contents["MPL-SOURCE-OFFER.txt"], dependencies, lock_data)
    output.mkdir(parents=True, exist_ok=True)
    for name, value in zip(FILENAMES, (data, index_data, contents["MPL-SOURCE-OFFER.txt"])):
        (output / name).write_bytes(value)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collect", action="store_true")
    parser.add_argument("--output", type=Path, default=BASE)
    args = parser.parse_args()
    if args.collect:
        collect(args.output)
    else:
        print(json.dumps(evidence(
            *((BASE / name).read_bytes() for name in FILENAMES),
            json.loads((BASE / "DEPENDENCY-LICENSE-INDEX.json").read_bytes()),
            (BASE / "Cargo.lock").read_bytes()), indent=2))
