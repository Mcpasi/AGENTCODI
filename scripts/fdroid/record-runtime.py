#!/usr/bin/env python3
"""Bind the assembled native files and guard transformations to their source receipt."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def record(assets, transformations, native, compiler):
    hashes = {}
    for number, line in enumerate(transformations.read_text().splitlines(), 1):
        fields = line.split("\t")
        if len(fields) != 2:
            raise ValueError(f"Invalid derived native fingerprint record on line {number}")
        name, value = fields
        if not re.fullmatch(r"[A-Z_][A-Z0-9_]*_SHA256", name) or not re.fullmatch(r"[a-f0-9]{64}", value):
            raise ValueError(f"Invalid derived native fingerprint: {name}")
        if name in hashes:
            raise ValueError(f"Duplicate derived native fingerprint: {name}")
        hashes[name] = value
    payload = {}
    for file in sorted(native.glob("*.so")):
        if file.is_symlink() or not file.is_file():
            raise ValueError("Invalid native payload")
        payload[file.name] = digest(file)
    root = Path(__file__).resolve().parents[2]
    result = {"schema": 1, "source_build_sha256": digest(assets / "source-build.json"),
              "source_date_epoch": int(os.environ["SOURCE_DATE_EPOCH"]),
              "assembler_sha256": digest(root / "scripts/build-debug-apk.sh"),
              "recorder_sha256": digest(Path(__file__)),
              "compiler": subprocess.check_output([compiler, "--version"], text=True).splitlines()[0],
              "transformations_sha256": hashes, "payload_sha256": payload}
    (assets / "assembled-runtime.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    record(*map(Path, sys.argv[1:4]), sys.argv[4])
