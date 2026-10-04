#!/usr/bin/env python3
"""Audit an extracted DEB or bootstrap staging root without executing its files."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

PREFIX = "/data/data/de.agentcodi.pkg/files/usr"
FOREIGN = (b"/data/data/com.termux/", b"/data/user/0/com.termux/",
           b"packages.termux.dev", b"packages-cf.termux.dev")


def check_runpath(value):
    for entry in value.split(":"):
        if entry == PREFIX + "/lib":
            continue
        if entry.startswith(PREFIX + "/lib/"):
            tail = PurePosixPath(entry[len(PREFIX + "/lib/"):])
            if tail.parts and ".." not in tail.parts and os.path.normpath(entry) == entry:
                continue
        if entry == "$ORIGIN":
            continue
        if entry.startswith("$ORIGIN/"):
            tail = PurePosixPath(entry[len("$ORIGIN/"):])
            if tail.parts and ".." not in tail.parts and not tail.is_absolute():
                continue
        raise ValueError("Foreign or empty ELF search path: " + value)


def check_elf(path, readelf):
    report = subprocess.check_output([readelf, "-W", "-h", "-l", "-d", "-r", str(path)], text=True,
                                     env={**os.environ, "LC_ALL": "C"})
    if not re.search(r"Class:\s+ELF64", report) or not re.search(r"Machine:\s+AArch64", report):
        raise ValueError("Not an ARM64 ELF: " + str(path))
    if re.search(r"R_AARCH64_TLS", report):
        raise ValueError("Native ELF TLS violates the bootstrap emutls ABI: " + str(path))
    if re.search(r"\(RPATH\)", report):
        raise ValueError("DT_RPATH is not supported by the Android linker")
    for runpath in re.findall(r"\(RUNPATH\).*?\[([^\]]*)\]", report):
        check_runpath(runpath)
    for interpreter in re.findall(r"Requesting program interpreter:\s*([^\]]+)", report):
        if interpreter != "/system/bin/linker64":
            raise ValueError("Not a Bionic interpreter: " + interpreter)
    return report


def check_script(data, label):
    if not data.startswith(b"#!"):
        return
    line = data.split(b"\n", 1)[0]
    if len(line) >= 127:
        raise ValueError("Shebang exceeds the Android 10 kernel baseline: " + label)
    parts = line[2:].strip().split()
    if not parts:
        raise ValueError("Empty shebang: " + label)
    interpreter = parts[0].decode()
    if interpreter != os.path.normpath(interpreter):
        raise ValueError("Noncanonical shebang: " + label)
    if interpreter != "/system/bin/sh" and not interpreter.startswith(PREFIX + "/bin/"):
        raise ValueError("Foreign shebang: " + label)


def audit(root, readelf="readelf"):
    root = root.resolve()
    prefix_root = root / PREFIX.lstrip("/")
    if not prefix_root.is_dir() or prefix_root.is_symlink():
        raise ValueError("Staging root must contain the final prefix")
    inventory, elf_reports = {}, {}
    # Do not follow links. Only parent directories of PREFIX may be outside it.
    for path in sorted(root.rglob("*")):
        name = path.relative_to(root).as_posix()
        inside = path.is_relative_to(prefix_root)
        if not inside:
            if path.is_symlink() or not path.is_dir() or not prefix_root.is_relative_to(path):
                raise ValueError("Payload outside managed prefix: " + name)
            continue
        if path.is_symlink():
            target = os.readlink(path)
            logical = PurePosixPath("/" + name)
            resolved = os.path.normpath(target if target.startswith("/") else
                                       str(logical.parent / target))
            if resolved != PREFIX and not resolved.startswith(PREFIX + "/"):
                raise ValueError("Symlink leaves managed prefix: " + name)
            inventory[name] = {"symlink": target}
        elif path.is_file():
            data = path.read_bytes()
            if any(marker in data for marker in FOREIGN):
                raise ValueError("Foreign Termux path or package source: " + name)
            if path.stat().st_mode & 0o111:
                check_script(data, name)
            inventory[name] = {"sha256": hashlib.sha256(data).hexdigest(),
                               "mode": oct(path.stat().st_mode & 0o777)}
            if data.startswith(b"\x7fELF"):
                elf_reports[name] = check_elf(path, readelf)
        elif not path.is_dir():
            raise ValueError("Unsupported payload file: " + name)
    return {"prefix": PREFIX, "files": inventory, "elf": elf_reports}


def check_control(control):
    fields = (control / "control").read_text()
    if not re.search(r"^Architecture: (aarch64|all)$", fields, re.M):
        raise ValueError("Foreign package architecture")
    for path in control.iterdir():
        if not path.is_file() or path.is_symlink():
            raise ValueError("Unexpected control entry: " + path.name)
        data = path.read_bytes()
        if any(marker in data for marker in FOREIGN):
            raise ValueError("Foreign prefix in package metadata")
        check_script(data, path.name)
    conffiles = control / "conffiles"
    if conffiles.exists():
        for path in conffiles.read_text().splitlines():
            if not path.startswith(PREFIX + "/") or ".." in PurePosixPath(path).parts:
                raise ValueError("Conffile outside managed prefix")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--control", type=Path)
    parser.add_argument("--readelf", default="readelf")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = audit(args.root, args.readelf)
    if args.control:
        check_control(args.control)
    text = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.write_text(text)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
