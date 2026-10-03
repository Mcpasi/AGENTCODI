#!/usr/bin/env python3
"""Archive rejection tests; the CI job also inspects the actual pinned release."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "inspection", Path(__file__).with_name("inspect-community-codex.py"))
inspection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inspection)


class ArchiveValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "package.tgz"
        self.destination = self.root / "payload"

    def create_archive(self, extra=None, omit=None):
        with tarfile.open(self.archive, "w:gz") as tar:
            for name in inspection.REQUIRED:
                if name == omit:
                    continue
                content = b"fixture"
                entry = tarfile.TarInfo(name)
                entry.size = len(content)
                entry.mode = 0o755
                tar.addfile(entry, io.BytesIO(content))
            if extra:
                tar.addfile(extra)
        return inspection.digest(self.archive)

    def test_valid_archive_records_bytes_and_never_installs_executables(self):
        sha = self.create_archive()
        inventory = inspection.unpack_verified(self.archive, self.destination, sha)
        self.assertEqual(len(inspection.REQUIRED), len(inventory))
        self.assertEqual(hashlib.sha256(b"fixture").hexdigest(), inventory[0]["sha256"])
        self.assertEqual(0o600, (self.destination / inspection.REQUIRED[0]).stat().st_mode & 0o777)

    def test_wrong_digest_rejected_before_extraction(self):
        self.create_archive()
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            inspection.unpack_verified(self.archive, self.destination, "0" * 64)
        self.assertFalse(self.destination.exists())

    def test_traversal_rejected_before_extraction(self):
        entry = tarfile.TarInfo("package/../../escape")
        sha = self.create_archive(extra=entry)
        with self.assertRaisesRegex(ValueError, "Unsafe archive path"):
            inspection.unpack_verified(self.archive, self.destination, sha)
        self.assertFalse(self.destination.exists())

    def test_symlink_rejected_before_extraction(self):
        entry = tarfile.TarInfo("package/link")
        entry.type = tarfile.SYMTYPE
        entry.linkname = "/tmp"
        sha = self.create_archive(extra=entry)
        with self.assertRaisesRegex(ValueError, "Unsupported archive entry"):
            inspection.unpack_verified(self.archive, self.destination, sha)
        self.assertFalse(self.destination.exists())

    def test_missing_host_rejected_before_extraction(self):
        sha = self.create_archive(omit="package/bin/codex-code-mode-host")
        with self.assertRaisesRegex(ValueError, "Missing required files"):
            inspection.unpack_verified(self.archive, self.destination, sha)
        self.assertFalse(self.destination.exists())

    def test_duplicate_member_rejected_before_extraction(self):
        entry = tarfile.TarInfo(inspection.REQUIRED[0])
        sha = self.create_archive(extra=entry)
        with self.assertRaisesRegex(ValueError, "Duplicate archive path"):
            inspection.unpack_verified(self.archive, self.destination, sha)
        self.assertFalse(self.destination.exists())


class ElfSearchPathTest(unittest.TestCase):
    def test_repeated_origin_entries_keep_the_same_lookup_directory(self):
        self.assertTrue(inspection.origin_only_search_path(["$ORIGIN"]))
        self.assertTrue(inspection.origin_only_search_path(["$ORIGIN:$ORIGIN"]))

    def test_missing_empty_or_external_entries_are_not_origin_only(self):
        for paths in ([], [""], ["$ORIGIN:"], [":$ORIGIN"],
                      ["$ORIGIN:/data/data/com.termux/files/usr/lib"], ["."]):
            with self.subTest(paths=paths):
                self.assertFalse(inspection.origin_only_search_path(paths))


if __name__ == "__main__":
    unittest.main()
