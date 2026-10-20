#!/usr/bin/env python3
"""Stage npm and its checked-in JS dependency sources without npm install."""
import json
from pathlib import Path
import shutil
import sys


def stage(source, destination):
    package = json.loads((source / "package.json").read_text())
    if package["version"] != "11.19.0":
        raise ValueError("Unexpected npm source version")
    destination.mkdir(parents=True)
    for name in ("bin", "lib", "node_modules", "package.json", "LICENSE"):
        item = source / name
        if item.is_dir():
            shutil.copytree(item, destination / name)
        else:
            shutil.copy2(item, destination / name)
    for workspace in sorted((source / "workspaces").glob("*/package.json")):
        metadata = json.loads(workspace.read_text())
        if metadata.get("private"):
            continue
        target = destination / "node_modules" / metadata["name"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(workspace.parent, target, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("test", "tests", "tap-snapshots"))
    for name in ("npm", "npx", "npm.cmd", "npx.cmd", "npm.ps1", "npx.ps1"):
        (destination / "bin" / name).unlink(missing_ok=True)
    for name in package["dependencies"]:
        if not (destination / "node_modules" / name / "package.json").is_file():
            raise ValueError("npm dependency is missing: " + name)
    if any(path.is_symlink() for path in destination.rglob("*")):
        raise ValueError("npm staging left a symlink")


if __name__ == "__main__":
    stage(*map(Path, sys.argv[1:]))
