#!/usr/bin/env python3
"""Map selected catalog DEBs to their pinned source recipes before building."""
import argparse
import json
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent


def resolve(recipes, selected):
    packages = Path(recipes) / "packages"
    roots = []
    for name in selected:
        if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) or ".." in name:
            raise ValueError("Invalid selected package name: " + name)
        if (packages / name / "build.sh").is_file():
            parent = name
        else:
            candidates = {path.parent.name for path in packages.glob("*/" + name + ".subpackage.sh")
                          if (path.parent / "build.sh").is_file()}
            if name.endswith("-static") and (packages / name.removesuffix("-static") / "build.sh").is_file():
                candidates.add(name.removesuffix("-static"))
            if len(candidates) != 1:
                raise ValueError(("No source recipe" if not candidates else "Ambiguous source recipe")
                                 + " for selected package: " + name)
            parent = candidates.pop()
        if parent not in roots:
            roots.append(parent)
    if not roots:
        raise ValueError("Empty catalog source recipe selection")
    return roots


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipes", type=Path, required=True)
    parser.add_argument("--group", required=True)
    args = parser.parse_args()
    catalog = json.loads((HERE / "catalog.json").read_text())
    # Resolve completely before printing; no partial successful build on failure.
    print("\n".join(resolve(args.recipes, catalog["groups"][args.group])))
