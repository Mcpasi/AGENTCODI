#!/usr/bin/env python3
"""Retain recipe changes and corresponding source archives beside the bootstrap."""
import argparse
import hashlib
import json
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
    selected = sorted({parents[name].name for name in report["packages"]})
    materials = []
    with tarfile.open(output / "bootstrap-corresponding-sources.tar.xz", "w:xz") as archive:
        for path in sorted(HERE.iterdir()):
            if path.is_file():
                archive.add(path, arcname="agentcodi/" + path.name)
        archive.add(recipes / "scripts", arcname="termux-packages/scripts")
        archive.add(recipes / "ndk-patches", arcname="termux-packages/ndk-patches")
        for name in selected:
            archive.add(recipes / "packages" / name, arcname="termux-packages/packages/" + name)
            cache = build / name / "cache"
            if cache.exists():
                for path in sorted(cache.iterdir()):
                    if path.is_file():
                        archive.add(path, arcname="source-downloads/" + name + "/" + path.name)
                        materials.append({"package": name, "file": path.name,
                                          "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
                    elif path.name == "tmp-checkout":
                        archive.add(path, arcname="git-sources/" + name,
                                    filter=lambda member: None if "/.git" in member.name else member)
    report["corresponding_sources"] = {
        "archive": "bootstrap-corresponding-sources.tar.xz",
        "sha256": hashlib.sha256((output / "bootstrap-corresponding-sources.tar.xz").read_bytes()).hexdigest(),
        "recipes": selected, "downloads": materials}
    (output / "bootstrap-report.json").write_text(json.dumps(report, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
        for path in sorted(output.iterdir()) if path.is_file() and path.name != "SHA256SUMS"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipes", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    collect(args.recipes, args.build, args.output)
