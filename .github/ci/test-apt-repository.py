#!/usr/bin/env python3
"""Regress deterministic package sizes and immutable same-version updates."""
import importlib.util
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[2] / "scripts/package-edition"


def module(name, filename, directory=SCRIPTS):
    spec = importlib.util.spec_from_file_location(name, directory / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


repository = module("repository", "apt-repository.py")
sizes = module("installed_size", "installed-size.py")


class InstalledSizeTest(unittest.TestCase):
    def test_logical_sizes_are_stable_across_archive_order_and_compression(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for compression, reverse in (("", False), ("gz", True), ("xz", False)):
                path = root / ("payload.tar." + compression)
                entries = []
                for name, size in (("empty", 0), ("one", 1), ("kib", 1024), ("over", 1025)):
                    entry = tarfile.TarInfo(name)
                    entry.size = size
                    entries.append((entry, b"x" * size))
                for name, kind in (("directory", tarfile.DIRTYPE),
                                   ("symlink", tarfile.SYMTYPE), ("hardlink", tarfile.LNKTYPE)):
                    entry = tarfile.TarInfo(name)
                    entry.type = kind
                    entry.linkname = "one" if kind != tarfile.DIRTYPE else ""
                    entries.append((entry, None))
                with tarfile.open(path, "w:" + compression) as archive:
                    for entry, content in reversed(entries) if reverse else entries:
                        archive.addfile(entry, io.BytesIO(content) if content is not None else None)
                self.assertEqual(sizes.installed_size(path), 7)


class PublishedPackageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.published = self.root / "published"
        self.published.mkdir()
        self.old_deb, self.old_metadata = self.package("old", installed_size=3508)
        self.old = {**self.old_metadata, "filename": self.old_deb.name,
                    "sha256": repository.digest(self.old_deb)}

    def package(self, label, installed_size=3512, version="1.0", payload=b"library bytes\n",
                mode=0o644, link="fixture", postinst=b"#!/bin/sh\nexit 0\n",
                conffiles=b"/usr/lib/fixture\n", description="update regression fixture",
                epoch="1791110575"):
        directory = self.root / label
        control = directory / "DEBIAN"
        control.mkdir(parents=True)
        control.chmod(0o755)
        (control / "control").write_text(
            "Package: fixture\nVersion: " + version + "\nArchitecture: all\n"
            "Installed-Size: " + str(installed_size) + "\n"
            "Maintainer: AGENTCODI\nDescription: " + description + "\n")
        (control / "postinst").write_bytes(postinst)
        (control / "postinst").chmod(0o755)
        (control / "conffiles").write_bytes(conffiles)
        target = directory / "usr/lib/fixture"
        target.parent.mkdir(parents=True)
        target.write_bytes(payload)
        target.chmod(mode)
        (target.parent / "alias").symlink_to(link)
        deb = self.published / "fixture.deb" if label == "old" else self.root / (label + ".deb")
        subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(directory), str(deb)],
                       check=True, stdout=subprocess.DEVNULL,
                       env={**os.environ, "SOURCE_DATE_EPOCH": epoch})
        metadata = repository.assembly.fields(repository.assembly.command("dpkg-deb", "-f", str(deb)))
        return deb, metadata

    def select(self, candidate):
        return repository.retain_published_package(*candidate, self.old, self.published)

    def test_size_only_change_retains_exact_published_bytes_and_metadata(self):
        candidate = self.package("candidate")
        self.assertNotEqual(repository.digest(candidate[0]), self.old["sha256"])
        selected, metadata = self.select(candidate)
        self.assertEqual(selected, self.old_deb)
        self.assertEqual(metadata, self.old_metadata)
        self.assertEqual(metadata["Installed-Size"], "3508")
        self.assertEqual(repository.digest(selected), self.old["sha256"])
        self.assertEqual(candidate[1]["Installed-Size"], "3512")

    def test_payload_permissions_links_scripts_and_metadata_changes_require_revision(self):
        mutations = ({"payload": b"changed library\n"}, {"mode": 0o755},
                     {"link": "other-target"}, {"postinst": b"#!/bin/sh\nexit 1\n"},
                     {"conffiles": b""}, {"description": "changed description"},
                     {"epoch": "1791110576"})
        for index, mutation in enumerate(mutations):
            with self.subTest(mutation=mutation):
                candidate = self.package("mutation" + str(index), **mutation)
                with self.assertRaisesRegex(ValueError, "require a version bump"):
                    self.select(candidate)

    def test_version_bump_accepts_changed_payload(self):
        candidate = self.package("upgrade", version="1.1", payload=b"new library\n")
        self.assertEqual(self.select(candidate), candidate)

    def test_downgrade_remains_rejected(self):
        with self.assertRaisesRegex(ValueError, "downgrade"):
            self.select(self.package("downgrade", version="0.9"))

    def test_identical_deb_does_not_need_replacement(self):
        self.assertEqual(self.select((self.old_deb, self.old_metadata)),
                         (self.old_deb, self.old_metadata))

    def test_corrupt_published_bytes_are_rejected(self):
        candidate = self.package("candidate")
        self.old_deb.write_bytes(b"corrupt archive")
        with self.assertRaisesRegex(ValueError, "Published DEB checksum mismatch"):
            self.select(candidate)

    def test_unsafe_published_path_is_rejected(self):
        self.old["filename"] = "../outside.deb"
        with self.assertRaisesRegex(ValueError, "Unsafe repository path"):
            self.select(self.package("candidate"))


class SnapshotUpdateTest(unittest.TestCase):
    def test_signed_update_retains_published_deb_and_rejects_changed_payload(self):
        fixtures = module("assembly_fixtures", "test-bootstrap-assembly.py", Path(__file__).parent)
        fixture = fixtures.AssemblyTest()
        self.addCleanup(fixture.doCleanups)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            home = root / "gnupg"
            home.mkdir(mode=0o700)
            edition = root / "project/scripts/package-edition"
            edition.mkdir(parents=True)
            for name in ("apt-repository.py", "assemble-bootstrap.py", "verify-prefix.py",
                         "build-apt-repository.sh", "repository.json", "lock.json",
                         "source-store.py", "download-sources.py"):
                shutil.copy2(SCRIPTS / name, edition / name)
            (edition.parents[1] / "LICENSE").write_text("MIT regression fixture\n")
            (edition / "catalog.json").write_text('{"groups": {}}\n')
            with mock.patch.dict(os.environ, {"GNUPGHOME": str(home), "SOURCE_DATE_EPOCH": "1791110575"}):
                subprocess.run(["gpg", "--batch", "--passphrase", "", "--quick-generate-key",
                                "AGENTCODI CI fixture", "ed25519", "sign", "0"],
                               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                records = subprocess.check_output(["gpg", "--batch", "--with-colons", "--list-keys"],
                                                  text=True, stderr=subprocess.DEVNULL)
                fingerprint = repository.fingerprints(records)[0]
                public = edition / "keys/fixture.asc"
                public.parent.mkdir()
                public.write_bytes(subprocess.check_output(["gpg", "--batch", "--armor", "--export",
                                                            fingerprint]))
                settings = json.loads((edition / "repository.json").read_text())
                settings.update({"public_key": "keys/fixture.asc", "signing_fingerprint": fingerprint,
                                 "trusted_fingerprints": [fingerprint]})
                (edition / "repository.json").write_text(json.dumps(settings))
                fixture.setUp()
                control = fixture.root / "fixture-lib/DEBIAN/control"
                control.write_text(control.read_text() + "Installed-Size: 3508\n")
                fixture.build_deb("fixture-lib")

                def artifacts(label):
                    artifacts = root / label
                    output = artifacts / "agentcodi-package-bootstrap"
                    fixture.assemble(output)
                    source = output / "bootstrap-corresponding-sources.tar.xz"
                    with tarfile.open(source, "w:xz") as archive:
                        entry = tarfile.TarInfo("fixture-source.txt")
                        entry.size = 7
                        archive.addfile(entry, io.BytesIO(b"fixture"))
                    report = json.loads((output / "bootstrap-report.json").read_text())
                    report["corresponding_sources"] = {"archive": source.name,
                                                       "sha256": repository.digest(source)}
                    (output / "bootstrap-report.json").write_text(json.dumps(report))
                    (output / "SHA256SUMS").write_text("".join(
                        repository.digest(path) + "  " + path.name + "\n"
                        for path in sorted(output.iterdir()) if path.is_file() and path.name != "SHA256SUMS"))
                    return artifacts

                with mock.patch.object(repository, "HERE", edition), contextlib.redirect_stdout(io.StringIO()):
                    first = root / "first"
                    repository.build(artifacts("original"), first, None)
                    old = json.loads((first / "dists/stable/repository-manifest.json").read_text())
                    control.write_text(control.read_text().replace("Installed-Size: 3508", "Installed-Size: 3512"))
                    fixture.build_deb("fixture-lib")
                    second = root / "second"
                    repository.build(artifacts("rebuilt"), second, first)
                    current = repository.verify(second, second / "keys/agentcodi-package.gpg")
                    self.assertEqual(current["packages"]["fixture-lib"], old["packages"]["fixture-lib"])
                    retained = current["reused_packages"]["fixture-lib"]
                    self.assertEqual(retained["published_sha256"], old["packages"]["fixture-lib"]["sha256"])
                    self.assertNotEqual(retained["candidate_sha256"], retained["published_sha256"])
                    (fixture.root / "fixture-lib" / repository.assembly.verify.PREFIX.lstrip("/") /
                     "lib/fixture.txt").write_bytes(b"changed library\n")
                    fixture.build_deb("fixture-lib")
                    rejected = root / "rejected"
                    with self.assertRaisesRegex(ValueError, "require a version bump"):
                        repository.build(artifacts("changed"), rejected, second)
                    self.assertFalse((rejected / "dists/stable/InRelease").exists())


if __name__ == "__main__":
    unittest.main()
