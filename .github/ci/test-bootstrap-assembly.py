#!/usr/bin/env python3
"""Exercise bootstrap dependency selection against real Debian version semantics."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "bootstrap", Path(__file__).resolve().parents[2] / "scripts/package-edition/assemble-bootstrap.py")
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


def package(name, version="1.0", **fields):
    return Path(name + ".deb"), {"Package": name, "Version": version, **fields}


class ClosureTest(unittest.TestCase):
    def test_runtime_closure_excludes_build_only_packages(self):
        packages = {"apt": package("apt", Depends="dpkg (>= 1.2), certs"),
                    "dpkg": package("dpkg", "1.3", **{"Pre-Depends": "shell"}),
                    "shell": package("shell"), "certs": package("certs"),
                    "compiler": package("compiler")}
        self.assertEqual(set(bootstrap.select(packages, ["apt"])),
                         {"apt", "dpkg", "shell", "certs"})

    def test_missing_dependency_fails(self):
        with self.assertRaisesRegex(ValueError, "Unsatisfied"):
            bootstrap.select({"apt": package("apt", Depends="missing")}, ["apt"])

    def test_wrong_version_fails(self):
        with self.assertRaisesRegex(ValueError, "Unsatisfied"):
            bootstrap.select({"apt": package("apt", Depends="dpkg (>= 2.0)"),
                              "dpkg": package("dpkg", "1.0")}, ["apt"])

    def test_alternative_and_epoch_are_resolved(self):
        packages = {"apt": package("apt", Depends="missing | dep (>= 1:1.0)"),
                    "dep": package("dep", "1:1.1")}
        self.assertEqual(set(bootstrap.select(packages, ["apt"])), {"apt", "dep"})

    def test_runtime_cycle_is_closed_without_downloads(self):
        packages = {"a": package("a", Depends="b"), "b": package("b", Depends="a")}
        self.assertEqual(set(bootstrap.select(packages, ["a"])), {"a", "b"})


if __name__ == "__main__":
    unittest.main()
