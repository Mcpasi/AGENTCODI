#!/usr/bin/env python3
"""Verify packaged Python modules by manifest identity, independent of alias numbers."""
import hashlib
from pathlib import PurePosixPath
import re
import sys
import zipfile


def verify(apk, abi, expected_count):
    with zipfile.ZipFile(apk) as archive:
        entries = archive.namelist()
        if len(entries) != len(set(entries)):
            raise ValueError("APK contains duplicate entries")
        prefix = f"lib/{abi}/"
        for entry in entries:
            name = entry.removeprefix(prefix)
            if entry.startswith(prefix) and re.fullmatch(r"lib(?:gdbm.*|readline.*)\.so(?:\..*)?", name):
                raise ValueError(f"APK contains an excluded GNU runtime library: {entry}")
        manifest = archive.read("assets/third-party/toolchain/RUNTIME-MANIFEST").decode("utf-8").splitlines()
        if not manifest or manifest[0] != "AGENTCODI_TOOL_RUNTIME_V1":
            raise ValueError("APK tool-runtime manifest has an invalid header")
        modules, targets = {}, set()
        for line in manifest[1:]:
            fields = line.split("\t")
            if fields[0] != "L":
                continue
            if len(fields) != 4:
                raise ValueError("APK tool-runtime manifest has an invalid native link")
            _, digest, alias, target = fields
            module = PurePosixPath(target).name.split(".", 1)[0]
            if module in {"_dbm", "_gdbm", "readline"}:
                raise ValueError(f"APK references an excluded Python extension: {target}")
            if not re.fullmatch(r"[a-f0-9]{64}", digest) or not re.fullmatch(r"libpython_ext_[0-9]{3}\.so", alias):
                raise ValueError(f"APK has an invalid Python extension record: {alias}")
            if not re.fullmatch(r"python/lib/python[0-9.]+/lib-dynload/[A-Za-z0-9_]+\.[^/]+\.so", target):
                raise ValueError(f"APK has an invalid Python extension target: {target}")
            if alias in modules or target in targets:
                raise ValueError(f"APK has a duplicate Python extension mapping: {alias}")
            modules[alias] = digest
            targets.add(target)
        actual = {entry.removeprefix(prefix) for entry in entries
                  if entry.startswith(prefix + "libpython_ext_")}
        if len(modules) != expected_count or actual != set(modules):
            raise ValueError("APK Python extension set differs from its runtime manifest")
        for alias, expected_digest in modules.items():
            with archive.open(prefix + alias) as stream:
                actual_digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual_digest != expected_digest:
                raise ValueError(f"APK Python extension differs from its runtime manifest: {alias}")


if __name__ == "__main__":
    verify(sys.argv[1], sys.argv[2], int(sys.argv[3]))
