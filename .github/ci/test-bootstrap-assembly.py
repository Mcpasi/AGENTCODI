#!/usr/bin/env python3
"""Exercise bootstrap dependency selection against real Debian version semantics."""
import contextlib
import hashlib
import importlib.util
import io
import json
import subprocess
import tempfile
import zipfile
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


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.debs = self.root / "debs"
        self.debs.mkdir()
        payloads = {
            "dash": {"bin/dash": b"shell fixture\n"},
            "bash": {"bin/bash": b"bash fixture\n"},
            "apt": {"bin/apt": b"apt fixture\n"},
            "dpkg": {"bin/dpkg": b"dpkg fixture\n"},
            "ca-certificates": {"etc/tls/cert.pem": b"certificate fixture\n"},
            "fixture-lib": {"lib/fixture.txt": b"runtime library fixture\n"},
            "compiler": {"bin/compiler": b"build-only fixture\n"},
        }
        for name, files in payloads.items():
            directory = self.root / name
            control = directory / "DEBIAN"
            control.mkdir(parents=True)
            version = "1:1.0" if name == "ca-certificates" else "1.0"
            depends = "Depends: fixture-lib (>= 1.0)\n" if name == "apt" else ""
            (control / "control").write_text(
                "Package: " + name + "\nVersion: " + version + "\nArchitecture: all\n"
                "Maintainer: AGENTCODI\nDescription: assembly regression fixture\n" + depends)
            prefix = directory / bootstrap.verify.PREFIX.lstrip("/")
            for path, data in files.items():
                target = prefix / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                target.chmod(0o700 if path.startswith("bin/") else 0o600)
            if name == "dash":
                (prefix / "bin/sh").symlink_to("dash")
            if name == "ca-certificates":
                (control / "conffiles").write_text(bootstrap.verify.PREFIX + "/etc/tls/cert.pem\n")
            self.build_deb(name)

    def build_deb(self, name):
        filename = name + ("_1:1.0_all.deb" if name == "ca-certificates" else ".deb")
        subprocess.run(["dpkg-deb", "--build", str(self.root / name),
                        str(self.debs / filename)], check=True,
                       stdout=subprocess.DEVNULL)

    def assemble(self, output):
        with contextlib.redirect_stdout(io.StringIO()):
            bootstrap.assemble(self.debs, output, "readelf")

    def test_real_debs_assemble_with_complete_database_and_verified_manifest(self):
        output = self.root / "bootstrap"
        self.assemble(output)
        report = json.loads((output / "bootstrap-report.json").read_text())
        self.assertEqual(set(report["packages"]),
                         {"dash", "bash", "apt", "dpkg", "ca-certificates", "fixture-lib", "agentcodi-package-keyring"})
        self.assertFalse(list(output.glob("compiler*.deb")))
        self.assertTrue((output / "ca-certificates_1_1.0_all.deb").is_file())
        self.assertEqual(report["packages"]["ca-certificates"]["Version"], "1:1.0")
        records = (output / "BOOTSTRAP-MANIFEST").read_text().splitlines()
        self.assertIn("L\tdash\tbin/sh", records)
        with zipfile.ZipFile(output / "bootstrap-aarch64.zip") as archive:
            for record in records[1:]:
                fields = record.split("\t")
                if fields[0] == "F":
                    data = archive.read(fields[4])
                    self.assertEqual(len(data), int(fields[2]))
                    self.assertEqual(hashlib.sha256(data).hexdigest(), fields[3])
            status = archive.read("var/lib/dpkg/status").decode()
            self.assertEqual(status.count("Status: install ok unpacked"), 7)
            self.assertTrue(archive.read("etc/apt/keyrings/agentcodi-package.gpg"))
            self.assertIn("var/lib/dpkg/info/agentcodi-package-keyring.list", archive.namelist())
            cert = archive.read("etc/tls/cert.pem")
            self.assertIn("Conffiles:\n " + bootstrap.verify.PREFIX + "/etc/tls/cert.pem " +
                          hashlib.md5(cert, usedforsecurity=False).hexdigest(), status)
            apt_sum = hashlib.md5(archive.read("bin/apt"), usedforsecurity=False).hexdigest()
            self.assertEqual(archive.read("var/lib/dpkg/info/apt.md5sums").decode(),
                             apt_sum + "  " + bootstrap.verify.PREFIX.lstrip("/") + "/bin/apt\n")
            for name in report["packages"]:
                self.assertIn("var/lib/dpkg/info/" + name + ".md5sums", archive.namelist())
                listing = archive.read("var/lib/dpkg/info/" + name + ".list").decode().splitlines()
                self.assertTrue(listing)
                ancestors = {str(path) for path in Path(bootstrap.verify.PREFIX).parents
                             if str(path) != "/"}
                self.assertTrue(ancestors.issubset(listing))
                self.assertTrue(all(path in ancestors or path == bootstrap.verify.PREFIX or
                                    path.startswith(bootstrap.verify.PREFIX + "/") for path in listing))
            self.assertEqual(archive.read("var/lib/dpkg/info/ca-certificates.conffiles").decode(),
                             bootstrap.verify.PREFIX + "/etc/tls/cert.pem\n")
        again = self.root / "again"
        self.assemble(again)
        self.assertEqual((again / "bootstrap-aarch64.zip").read_bytes(),
                         (output / "bootstrap-aarch64.zip").read_bytes())
        self.assertEqual((again / "BOOTSTRAP-MANIFEST").read_bytes(),
                         (output / "BOOTSTRAP-MANIFEST").read_bytes())

    def test_package_file_collision_fails(self):
        path = self.root / "bash" / bootstrap.verify.PREFIX.lstrip("/") / "bin/apt"
        path.write_text("conflicting file")
        self.build_deb("bash")
        with self.assertRaisesRegex(ValueError, "Package file collision"):
            self.assemble(self.root / "bootstrap")

    def test_missing_script_interpreter_fails(self):
        path = self.root / "dpkg" / bootstrap.verify.PREFIX.lstrip("/") / "bin/perl-tool"
        path.write_text("#!" + bootstrap.verify.PREFIX + "/bin/perl\n")
        path.chmod(0o700)
        self.build_deb("dpkg")
        with self.assertRaisesRegex(ValueError, "Missing bootstrap script interpreter"):
            self.assemble(self.root / "bootstrap")

    def test_payload_outside_managed_prefix_fails(self):
        path = self.root / "dpkg" / "etc/foreign"
        path.parent.mkdir(parents=True)
        path.write_text("invalid package payload")
        self.build_deb("dpkg")
        with self.assertRaisesRegex(ValueError, "Payload outside managed prefix"):
            self.assemble(self.root / "bootstrap")


if __name__ == "__main__":
    unittest.main()
