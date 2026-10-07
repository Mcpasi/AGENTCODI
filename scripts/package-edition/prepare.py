#!/usr/bin/env python3
"""Prepare a separate, pinned Termux recipe tree for AGENTCODI Package Edition."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
LOCK = HERE / "lock.json"
OVERLAY = HERE / "overlay.json"


def read_lock():
    lock = json.loads(LOCK.read_text())
    target, repo = lock["target"], lock["repository"]
    if lock["format_version"] != 1:
        raise ValueError("Unsupported lock format")
    if (target["application_id"] != "de.agentcodi.pkg"
            or target["prefix"] != target["rootfs"] + "/usr"
            or target["rootfs"] != "/data/data/de.agentcodi.pkg/files"
            or target["home"] != target["rootfs"] + "/agentcodi/home"
            or target["architecture"] != "aarch64" or target["library"] != "bionic"
            or target["api_level"] != 29 or target["package_format"] != "debian"):
        raise ValueError("Lock does not match the Package Edition runtime")
    if not re.fullmatch(r"ghcr.io/termux/package-builder@sha256:[0-9a-f]{64}", lock["builder"]["image"]):
        raise ValueError("Builder must be pinned by digest")
    for key in ("commit", "tree"):
        if not re.fullmatch(r"[0-9a-f]{40}", lock["source"][key]):
            raise ValueError("Recipe source must be pinned by full Git object IDs")
    if (not repo["url"].startswith("https://") or "termux." in repo["url"]
            or repo["signed_by"] != target["prefix"] + "/etc/apt/keyrings/agentcodi-package.gpg"):
        raise ValueError("Dedicated HTTPS repository and scoped key are required")
    return lock


def git(source, *args):
    return subprocess.check_output(["git", "-C", str(source), *args])


def replace_exact(text, old, new):
    if text.count(old) != 1:
        raise ValueError("Overlay context missing or ambiguous: " + old[:100])
    return text.replace(old, new, 1)


def prepare(source, output):
    lock = read_lock()
    source = source.resolve()
    if output.exists() or output.is_symlink():
        raise ValueError("Output must be a new directory")
    output = output.resolve()
    if output.is_relative_to(source):
        raise ValueError("Output must be outside the upstream checkout")
    if git(source, "rev-parse", "HEAD").decode().strip() != lock["source"]["commit"]:
        raise ValueError("Wrong termux-packages commit")
    if git(source, "rev-parse", "HEAD^{tree}").decode().strip() != lock["source"]["tree"]:
        raise ValueError("Wrong termux-packages tree")
    if git(source, "status", "--porcelain", "--untracked-files=all", "--ignored"):
        raise ValueError("Upstream checkout must be clean, including ignored build caches")
    overlay = json.loads(OVERLAY.read_text())
    if overlay["format_version"] != 1:
        raise ValueError("Unsupported overlay format")
    # Validate all edits before creating the output. The source is never edited.
    edited = {}
    for edit in overlay["edits"]:
        path = edit["path"]
        actual = git(source, "rev-parse", "HEAD:" + path).decode().strip()
        if actual != edit["upstream_blob"]:
            raise ValueError("Unexpected upstream blob: " + path)
        text = (source / path).read_text()
        for replacement in edit["replacements"]:
            text = replace_exact(text, replacement["old"], replacement["new"])
        edited[path] = text

    for removal in overlay.get("removals", []):
        if git(source, "rev-parse", "HEAD:" + removal["path"]).decode().strip() != removal["upstream_blob"]:
            raise ValueError("Unexpected removal blob: " + removal["path"])

    output.mkdir(parents=True)
    try:
        for item in git(source, "ls-files", "-z").decode().split("\0"):
            if not item:
                continue
            src, dest = source / item, output / item
            dest.parent.mkdir(parents=True, exist_ok=True)
            if src.is_symlink():
                dest.symlink_to(os.readlink(src))
            else:
                shutil.copy2(src, dest)
        for removal in overlay.get("removals", []):
            (output / removal["path"]).unlink()
        for path, text in edited.items():
            (output / path).write_text(text)
        size_script = "scripts/agentcodi-installed-size.py"
        shutil.copy2(HERE / "installed-size.py", output / size_script)
        repo = lock["repository"]
        (output / "repo.json").write_text(json.dumps({
            "pkg_format": "debian",
            "packages": {key: repo[value] for key, value in
                         (("name", "name"), ("distribution", "suite"),
                          ("component", "component"), ("url", "url"))}
        }, indent=2) + "\n")
        target = lock["target"]
        environment = {
            "SOURCE_DATE_EPOCH": str(lock["source"]["source_date_epoch"]),
            "TERMUX_ARCH": target["architecture"],
            "TERMUX_PACKAGE_FORMAT": target["package_format"],
            "TERMUX_PACKAGE_LIBRARY": target["library"],
            "TERMUX_PKG_API_LEVEL": str(target["api_level"]),
            "TERMUX_NDK_VERSION_NUM": lock["toolchain"]["ndk"]["version"],
            "TERMUX_NDK_REVISION": "",
            "TERMUX_HOST_LLVM_MAJOR_VERSION": lock["toolchain"]["host_llvm_major"],
            "TERMUX_INSTALL_DEPS": "false",
            "TERMUX_SKIP_DEPCHECK": "false",
        }
        # Values come from a validated lock, quoted without shell interpolation.
        import shlex
        (output / "agentcodi.env").write_text(
            "# Source before any build, including bootstrap assembly.\n" +
            "".join("export " + key + "=" + shlex.quote(value) + "\n"
                    for key, value in environment.items()))
        (output / "agentcodi-build-package.sh").write_text("""#!/bin/bash
set -euo pipefail
cd "$(dirname "$(realpath "$0")")"
. ./agentcodi.env
(( $# > 0 )) || { echo "Pass source recipe names" >&2; exit 64; }
for package in "$@"; do
    [[ "$package" =~ ^[a-z0-9][a-z0-9+.-]*$ && "$package" != *..* ]] || {
        echo "Only recipe names are accepted, no build options or paths" >&2; exit 64;
    }
done
exec ./build-package.sh --format debian --library bionic -a aarch64 "$@"
""")
        (output / "agentcodi-build-package.sh").chmod(0o755)
        (output / "agentcodi-bootstrap-plan.json").write_text(
            json.dumps(lock["bootstrap"], indent=2) + "\n")
        paths = sorted([*edited, size_script, "repo.json", "agentcodi.env",
                        "agentcodi-build-package.sh", "agentcodi-bootstrap-plan.json"])
        report = {
            "source": lock["source"], "builder": lock["builder"],
            "target": target, "repository": repo,
            "lock_sha256": hashlib.sha256(LOCK.read_bytes()).hexdigest(),
            "overlay_sha256": hashlib.sha256(OVERLAY.read_bytes()).hexdigest(),
            "prepared_files": {path: hashlib.sha256((output / path).read_bytes()).hexdigest()
                               for path in paths},
        }
        (output / "agentcodi-preparation.json").write_text(json.dumps(report, indent=2) + "\n")
        return report
    except BaseException:
        shutil.rmtree(output)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = prepare(args.source, args.output)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
