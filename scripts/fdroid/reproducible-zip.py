#!/usr/bin/env python3
"""Create a canonical, unsigned ZIP before running zipalign/signing."""
import argparse
import datetime
import os
from pathlib import Path, PurePosixPath
import stat
import zipfile


def safe_name(name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or "\\" in name or "\x00" in name
            or any(part in (".", "..", "") for part in name.split("/"))):
        raise ValueError("Unsafe ZIP entry: " + repr(name))
    return name


def canonical_zip(source, destination, additions, epoch):
    date = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc)
    if not 1980 <= date.year <= 2107:
        raise ValueError("SOURCE_DATE_EPOCH must be in the ZIP date range (1980..2107)")
    timestamp = (date.year, date.month, date.day, date.hour, date.minute, date.second // 2 * 2)
    entries = {}
    with zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            name = safe_name(entry.filename)
            if name in entries:
                raise ValueError("Duplicate ZIP entry: " + name)
            if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA", ".DSA", ".EC", ".SF")):
                raise ValueError("Input ZIP must be unsigned")
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("ZIP input contains a symbolic link: " + name)
            entries[name] = (archive.read(entry), entry.compress_type)
    if additions is not None:
        if additions.is_symlink():
            raise ValueError("Additions root must be a regular directory")
        for path in sorted(additions.rglob("*")):
            if path.is_symlink():
                raise ValueError("ZIP additions contain a symbolic link: " + str(path))
            if path.is_dir():
                continue
            if not path.is_file():
                raise ValueError("ZIP additions contain a special file: " + str(path))
            name = safe_name(path.relative_to(additions).as_posix())
            if name in entries:
                raise ValueError("Duplicate ZIP addition: " + name)
            entries[name] = (path.read_bytes(), zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, (data, compression) in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, timestamp)
            entry.create_system = 3
            mode = 0o755 if name.startswith("lib/") else 0o644
            entry.external_attr = (stat.S_IFREG | mode) << 16
            entry.compress_type = compression
            archive.writestr(entry, data, compresslevel=9)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--additions", type=Path)
    parser.add_argument("--epoch", type=int, default=os.environ.get("SOURCE_DATE_EPOCH"))
    args = parser.parse_args()
    if args.epoch is None:
        parser.error("SOURCE_DATE_EPOCH or --epoch is required")
    if args.source.resolve() == args.destination.resolve():
        parser.error("Input and output must be different files")
    canonical_zip(args.source, args.destination, args.additions, args.epoch)
