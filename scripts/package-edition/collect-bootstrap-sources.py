#!/usr/bin/env python3
"""Retain recipe changes and corresponding source archives beside the bootstrap."""
import argparse
import hashlib
import json
import io
import shutil
import subprocess
from pathlib import Path
import tarfile

HERE = Path(__file__).resolve().parent


def collect(recipes, build, output):
    report = json.loads((output / "bootstrap-report.json").read_text())
    parents = {}
    for recipe in (recipes / "packages").iterdir():
        if not (recipe / "build.sh").exists():
            continue
        parents[recipe.name] = recipe
        for subpackage in recipe.glob("*.subpackage.sh"):
            parents[subpackage.name.removesuffix(".subpackage.sh")] = recipe
    names = set(report["packages"])
    if report.get("catalog"):
        names.update(json.loads((output / "all-built-packages.json").read_text()))
    selected = sorted({parents[name].name for name in names})
    materials = []
    git_sources = []
    with tarfile.open(output / "bootstrap-corresponding-sources.tar.xz", "w:xz") as archive:
        for path in sorted(HERE.iterdir()):
            if path.is_file():
                archive.add(path, arcname="agentcodi/" + path.name)
        for name in ("build-package.sh", "repo.json", "agentcodi.env",
                     "agentcodi-build-package.sh", "agentcodi-preparation.json"):
            archive.add(recipes / name, arcname="termux-packages/" + name)
        archive.add(recipes / "scripts", arcname="termux-packages/scripts")
        archive.add(recipes / "ndk-patches", arcname="termux-packages/ndk-patches")
        for name in selected:
            archive.add(recipes / "packages" / name, arcname="termux-packages/packages/" + name)
            source = build / name / "src"
            if (source / ".git").exists():
                # Custom Git-source recipes (notably libandroid-selinux) clone
                # directly into src. Retain clean pinned source, not build objects.
                commit = subprocess.check_output(
                    ["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
                data = subprocess.check_output(
                    ["git", "-C", str(source), "archive", "--format=tar", "HEAD"])
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as snapshot:
                    for member in snapshot:
                        member.name = "git-sources/" + name + "/" + member.name
                        archive.addfile(member, snapshot.extractfile(member) if member.isfile() else None)
                git_sources.append({"package": name, "commit": commit,
                                    "archive_sha256": hashlib.sha256(data).hexdigest()})
            cache = build / name / "cache"
            if cache.exists():
                for path in sorted(cache.iterdir()):
                    if path.is_file():
                        archive.add(path, arcname="source-downloads/" + name + "/" + path.name)
                        materials.append({"package": name, "file": path.name,
                                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
                    elif path.name == "tmp-checkout" and not (source / ".git").exists():
                        archive.add(path, arcname="git-sources/" + name,
                                    filter=lambda member: None if "/.git/" in member.name or member.name.endswith("/.git") else member)
    report["corresponding_sources"] = {
        "archive": "bootstrap-corresponding-sources.tar.xz",
        "sha256": hashlib.sha256((output / "bootstrap-corresponding-sources.tar.xz").read_bytes()).hexdigest(),
        "recipes": selected, "downloads": materials, "git_sources": git_sources}
    (output / "bootstrap-report.json").write_text(json.dumps(report, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
        for path in sorted(output.iterdir()) if path.is_file() and path.name != "SHA256SUMS"))


def reuse(source, output):
    """Repackage unchanged binary sources with the current assembly/audit scripts."""
    previous = json.loads((source / "bootstrap-report.json").read_text())
    current = json.loads((output / "bootstrap-report.json").read_text())
    if previous["lock"] != current["lock"] or set(previous["packages"]) != set(current["packages"]):
        raise ValueError("Reused package selection or lock differs; rebuild from source")
    for name in current["packages"]:
        if previous["packages"][name]["deb_sha256"] != current["packages"][name]["deb_sha256"]:
            raise ValueError("Reused DEB checksum differs: " + name)
    archive_name = previous["corresponding_sources"]["archive"]
    originals = {path.name: path for path in HERE.iterdir() if path.is_file()}
    retained = set()
    with tarfile.open(source / archive_name, "r:xz") as old, tarfile.open(output / archive_name, "w:xz") as new:
        for member in old:
            relative = member.name.removeprefix("agentcodi/")
            if member.name.startswith("agentcodi/") and relative in originals:
                data = originals[relative].read_bytes()
                member.size = len(data)
                new.addfile(member, io.BytesIO(data))
                retained.add(relative)
            else:
                new.addfile(member, old.extractfile(member) if member.isfile() else None)
        for name in sorted(set(originals) - retained):
            new.add(originals[name], arcname="agentcodi/" + name)
    shutil.copy2(source / "agentcodi-preparation.json", output / "agentcodi-preparation.json")
    current["corresponding_sources"] = {**previous["corresponding_sources"],
        "sha256": hashlib.sha256((output / archive_name).read_bytes()).hexdigest()}
    (output / "bootstrap-report.json").write_text(json.dumps(current, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
        for path in sorted(output.iterdir()) if path.is_file() and path.name != "SHA256SUMS"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipes", type=Path)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--reuse-from", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.reuse_from:
        reuse(args.reuse_from, args.output)
    elif args.recipes and args.build:
        collect(args.recipes, args.build, args.output)
    else:
        parser.error('--recipes and --build are required for a fresh source build')
