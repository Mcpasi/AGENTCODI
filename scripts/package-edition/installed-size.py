#!/usr/bin/env python3
"""Estimate installed KiB from package entries without filesystem allocation."""
import argparse
from pathlib import Path
import tarfile


def installed_size(archive):
    # Regular files use their logical size rounded to KiB. Directories, links
    # and special files each contribute one KiB, independent of the build host.
    size = 0
    with tarfile.open(archive, "r|*") as payload:
        for entry in payload:
            size += (entry.size + 1023) // 1024 if entry.isfile() else 1
    return size


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    print(installed_size(parser.parse_args().archive))
