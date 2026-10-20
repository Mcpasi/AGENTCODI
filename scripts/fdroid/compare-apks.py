#!/usr/bin/env python3
"""Require byte-identical independently built APKs and identify differing entries."""
import hashlib
from pathlib import Path
import sys
import zipfile


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def compare(first, second):
    left, right = digest(first), digest(second)
    print(f"{left}  {first}")
    print(f"{right}  {second}")
    if left == right:
        print("APKs are byte-identical.")
        return 0
    with zipfile.ZipFile(first) as a, zipfile.ZipFile(second) as b:
        a_names, b_names = set(a.namelist()), set(b.namelist())
        for name in sorted(a_names ^ b_names):
            print("Entry set differs: " + name)
        changed = False
        for name in sorted(a_names & b_names):
            if hashlib.sha256(a.read(name)).digest() != hashlib.sha256(b.read(name)).digest():
                print("Payload differs: " + name)
                changed = True
        if not changed and a_names == b_names:
            print("Payloads match; ZIP metadata, ordering, compression or signing data differs.")
    print("Reproducibility check failed.")
    return 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: compare-apks.py FIRST-unsigned.apk SECOND-unsigned.apk")
    sys.exit(compare(Path(sys.argv[1]), Path(sys.argv[2])))
