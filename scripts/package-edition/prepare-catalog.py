#!/usr/bin/env python3
"""Apply the separately pinned headless catalog adaptations to a fresh recipe tree."""
import argparse
import importlib.util
import json
from pathlib import Path
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("prepare", HERE / "prepare.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    overlay = json.loads((HERE / "overlay.json").read_text())
    catalog = json.loads((HERE / "catalog.json").read_text())
    if catalog["format_version"] != 1:
        raise ValueError("Unsupported catalog format")
    overlay["edits"] += catalog["edits"]
    overlay["removals"] += catalog["removals"]
    with tempfile.TemporaryDirectory() as directory:
        prepare.OVERLAY = Path(directory) / "overlay.json"
        prepare.OVERLAY.write_text(json.dumps(overlay, indent=2) + "\n")
        prepare.prepare(args.source, args.output)

if __name__ == "__main__":
    main()
