#!/usr/bin/env python3
"""Compile runtime bytecode without CPython's deliberately invalid test fixtures."""
import compileall
from pathlib import Path
import py_compile
import re
import sys


def compile_stdlib(directory):
    directory = Path(directory).resolve()
    # Anchor the exclusion inside this stdlib. A build checkout itself may
    # live in a directory named "tests" without excluding runtime modules.
    tests = re.compile(re.escape(str(directory)) + r"/(?:.*/)?(?:test|tests|idle_test)/")
    if not compileall.compile_dir(str(directory), force=True, quiet=1, legacy=True,
            workers=1, optimize=0, rx=tests, stripdir=str(directory),
            prependdir="/usr/lib/python3.14",
            invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH):
        raise ValueError("Python runtime bytecode compilation failed")


if __name__ == "__main__":
    if sys.version_info[:3] != (3, 14, 6):
        raise SystemExit("Runtime bytecode requires the locked Python 3.14.6 interpreter")
    compile_stdlib(sys.argv[1])
