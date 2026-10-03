#!/usr/bin/env python3
"""Inspect the pinned Community Codex package without installing or executing it."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import urllib.request

PIN_FILE = Path(__file__).with_name("community-codex-release.json")
MAX_UNPACKED = 768 * 1024 * 1024
REQUIRED = (
    "package/package.json", "package/LICENSE", "package/NOTICE", "package/README.md",
    "package/bin/codex", "package/bin/codex-exec",
    "package/bin/codex.js", "package/bin/codex-exec.js",
    "package/bin/codex.bin", "package/bin/codex-code-mode-host",
    "package/bin/libc++_shared.so", "package/scripts/postinstall_termux_launcher.js",
)
SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            sha.update(block)
    return sha.hexdigest()


def github_json(endpoint):
    request = urllib.request.Request(
        "https://api.github.com/repos/" + endpoint,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "AGENTCODI-package-audit"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download_release(pin, directory):
    release = github_json(pin["repository"] + "/releases/tags/" + pin["release"])
    require(release["tag_name"] == pin["release"], "Release tag changed")
    assets = [asset for asset in release["assets"] if asset["name"] == pin["asset_name"]]
    require(len(assets) == 1, "Expected exactly one pinned release asset")
    asset = assets[0]
    require(asset.get("digest") == "sha256:" + pin["archive_sha256"],
            "GitHub release digest differs from the checked-in pin")
    ref = github_json(pin["repository"] + "/git/ref/tags/" + pin["release"])
    obj = ref["object"]
    for _ in range(4):
        if obj["type"] != "tag":
            break
        obj = github_json(pin["repository"] + "/git/tags/" + obj["sha"])["object"]
    require(obj["type"] == "commit" and obj["sha"] == pin["source_commit"],
            "Release tag no longer resolves to the pinned source commit")
    url = ("https://github.com/" + pin["repository"] + "/releases/download/"
           + pin["release"] + "/" + pin["asset_name"])
    require(asset["browser_download_url"] == url, "Unexpected release download URL")
    archive = directory / pin["asset_name"]
    request = urllib.request.Request(url, headers={"User-Agent": "AGENTCODI-package-audit"})
    with urllib.request.urlopen(request, timeout=60) as response, archive.open("wb") as out:
        size = 0
        for block in iter(lambda: response.read(1024 * 1024), b""):
            size += len(block)
            require(size <= asset["size"], "Release asset exceeds its declared size")
            out.write(block)
    require(size == asset["size"], "Incomplete release download")
    return archive, {"asset_id": asset["id"], "size": size, "source_commit": obj["sha"], "url": url}


def unpack_verified(archive, destination, expected_hash):
    require(digest(archive) == expected_hash, "Release archive SHA-256 mismatch")
    # Inspect every entry before writing anything; never follow archive links.
    with tarfile.open(archive, "r:gz") as tar:
        members = tar.getmembers()
        require(len(members) <= 4096, "Unexpected archive entry count")
        names = set()
        for member in members:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and ".." not in path.parts
                    and path.parts and path.parts[0] == "package"
                    and str(path) == member.name.rstrip("/")
                    and "\\" not in member.name,
                    "Unsafe archive path: " + member.name)
            require(str(path) not in names, "Duplicate archive path: " + member.name)
            names.add(str(path))
            require(member.isfile() or member.isdir(), "Unsupported archive entry: " + member.name)
            require(not member.mode & 0o6000, "Unexpected set-id file: " + member.name)
        require(sum(member.size for member in members) <= MAX_UNPACKED,
                "Archive exceeds unpacked-size limit")
        require(set(REQUIRED) <= names, "Missing required files: " + str(sorted(set(REQUIRED) - names)))
        for member in members:
            target = destination / member.name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open("xb") as out:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    out.write(block)
            # Inspection needs only readable data, never executable permissions.
            target.chmod(0o600)
    return [{"path": member.name, "size": member.size,
             "archive_mode": oct(member.mode), "sha256": digest(destination / member.name)}
            for member in members if member.isfile()]


def elf_report(path, reports):
    result = subprocess.run(
        ["readelf", "--wide", "--file-header", "--program-headers", "--dynamic", str(path)],
        check=True, capture_output=True, text=True, env={**os.environ, "LC_ALL": "C"},
    ).stdout
    (reports / (path.name + ".readelf.txt")).write_text(result, encoding="utf-8")
    require(re.search(r"Class:\s+ELF64", result) and re.search(r"Machine:\s+AArch64", result),
            "Expected Android ARM64 ELF: " + path.name)
    return {
        "needed": re.findall(r"\(NEEDED\).*Shared library: \[([^]]+)\]", result),
        "runpath": re.findall(r"\((?:RUNPATH|RPATH)\).*Library (?:runpath|rpath): \[([^]]*)\]", result),
        "interpreter": re.findall(r"Requesting program interpreter: ([^]]+)", result),
    }


def inspect(archive, pin, directory, provenance):
    payload = directory / "payload"
    reports = directory / "report"
    reports.mkdir()
    inventory = unpack_verified(archive, payload, pin["archive_sha256"])
    (reports / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")
    package_root = payload / "package"
    package = json.loads((package_root / "package.json").read_text(encoding="utf-8"))
    require(package["name"] == pin["package_name"] and package["version"] == pin["package_version"],
            "Package identity differs from the pin")
    require(package.get("license") == "Apache-2.0", "Unexpected package license")
    require(package.get("os") == ["android"] and package.get("cpu") == ["arm64"],
            "Unexpected package platform")
    if package.get("gitHead"):
        require(package["gitHead"] == pin["source_commit"], "Package gitHead differs from release tag")
    dependencies = {key: package.get(key, {}) for key in (
        "dependencies", "optionalDependencies", "peerDependencies", "devDependencies", "bundledDependencies",
    )}
    native = {}
    for entry in inventory:
        path = payload / entry["path"]
        with path.open("rb") as stream:
            is_elf = stream.read(4) == b"\x7fELF"
        if is_elf:
            native[entry["path"]] = elf_report(path, reports)
    bundled_libraries = {Path(name).name for name in native if name.endswith(".so")}
    for name, metadata in native.items():
        metadata["bundled_dependencies"] = sorted(set(metadata["needed"]) & bundled_libraries)
        metadata["android_system_dependencies"] = sorted(set(metadata["needed"]) & SYSTEM_LIBRARIES)
        metadata["unresolved_dependencies"] = sorted(set(metadata["needed"]) - bundled_libraries - SYSTEM_LIBRARIES)
        require(not metadata["unresolved_dependencies"], "Unresolved native dependency: " + name)
    for name in ("codex.bin", "codex-code-mode-host"):
        relative = "package/bin/" + name
        require(relative in native, "Missing native executable: " + name)
        require(native[relative]["interpreter"] == ["/system/bin/linker64"],
                "Unexpected Android interpreter: " + name)
        require(native[relative]["runpath"] == ["$ORIGIN"], "Unexpected native search path: " + name)
        mode = next(entry["archive_mode"] for entry in inventory if entry["path"] == relative)
        require(int(mode, 8) & 0o111, "Native executable lacks execute permission: " + name)
    licenses = []
    for entry in inventory:
        path = Path(entry["path"])
        if re.search(r"license|notice|copying", path.name, re.IGNORECASE):
            content = (payload / entry["path"]).read_text(encoding="utf-8")
            (reports / path.name).write_text(content, encoding="utf-8")
            licenses.append({"path": entry["path"], "sha256": entry["sha256"], "text": content})
    require("Apache License" in (package_root / "LICENSE").read_text(encoding="utf-8"),
            "Packaged Apache license missing")
    launchers = []
    for entry in inventory:
        if entry["path"].endswith((".js", "/codex", "/codex-exec")):
            content = (payload / entry["path"]).read_text(encoding="utf-8")
            launchers.append({
                "path": entry["path"], "shebang": content.splitlines()[0],
                "environment_and_prefix_lines": [
                    line for line in content.splitlines()
                    if re.search(r"PREFIX|LD_LIBRARY_PATH|CODEX_|/data/data/|execPath|Interpreter", line)
                ],
            })
    # A string occurrence shows a host-name reference, not a validated relocation offset.
    host_reference = b"codex-code-mode-host" in (package_root / "bin/codex.bin").read_bytes()
    report = {
        "pin": pin, "release_provenance": provenance,
        "package": package, "npm_dependencies": dependencies,
        "native": native, "licenses": licenses, "launchers": launchers,
        "code_mode_host": {"path": "package/bin/codex-code-mode-host",
                           "name_referenced_in_codex_binary": host_reference},
        "scope": "Static archive audit; no npm install, postinstall, ELF execution, APK mutation or device test.",
        "follow_up": [
            "Generate and validate the app-server schema on a compatible ARM64/Bionic host.",
            "Determine host resolution and any relocation offset from this exact artifact before APK integration.",
            "Review notices for bundled libc++ and statically linked Rust/V8 dependencies before redistribution.",
            "Adapt Termux-default prefixes and launcher shebangs to the Package Edition runtime.",
        ],
    }
    (reports / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for name in ("package.json", "README.md"):
        (reports / name).write_bytes((package_root / name).read_bytes())
    summary = [
        "## Community Codex release inspection",
        "", "- Archive SHA-256 verified: `" + pin["archive_sha256"] + "`",
        "- Release source commit verified: `" + pin["source_commit"] + "`",
        "- Package: `" + package["name"] + "@" + package["version"] + "`",
        "- Archive files: " + str(len(inventory)),
        "- Code-mode host is packaged; binary name reference: " + str(host_reference),
        "- Declared npm dependencies: `" + json.dumps(dependencies, sort_keys=True) + "`",
        "- License/notice files: " + ", ".join(item["path"] for item in licenses),
        "", "| Native file | DT_NEEDED | RUNPATH | Interpreter |",
        "| --- | --- | --- | --- |",
    ]
    for name, metadata in native.items():
        summary.append("| " + name + " | " + ", ".join(metadata["needed"]) + " | "
                       + ", ".join(metadata["runpath"]) + " | " + ", ".join(metadata["interpreter"]) + " |")
    summary.extend(["", "### Remaining integration checks", ""] + [
        "- " + item for item in report["follow_up"]
    ])
    text = "\n".join(summary) + "\n"
    (reports / "summary.md").write_text(text, encoding="utf-8")
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
            out.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path,
                        help="New private audit directory outside the source tree")
    args = parser.parse_args()
    # Refuse existing directories so reports cannot silently reuse old bytes.
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    pin = json.loads(PIN_FILE.read_text(encoding="utf-8"))
    archive, provenance = download_release(pin, args.output)
    inspect(archive, pin, args.output, provenance)


if __name__ == "__main__":
    main()
