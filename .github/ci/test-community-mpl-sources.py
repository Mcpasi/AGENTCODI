#!/usr/bin/env python3
"""Protect source availability, exact versions, original terms and APK source delivery."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mpl", REPO / ".github/ci/community-mpl-sources.py")
mpl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mpl)


class MplSourceTest(unittest.TestCase):
    def setUp(self):
        self.archive, index, self.notice = [(mpl.BASE / name).read_bytes() for name in mpl.FILENAMES]
        self.index = json.loads(index)
        self.dependencies = json.loads((mpl.BASE / "DEPENDENCY-LICENSE-INDEX.json").read_bytes())
        self.lock = (mpl.BASE / "Cargo.lock").read_bytes()

    def verify(self):
        return mpl.evidence(self.archive, json.dumps(self.index).encode(), self.notice,
                            self.dependencies, self.lock)

    def rewrite_archive(self, update):
        with zipfile.ZipFile(io.BytesIO(self.archive)) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        update(contents)
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, data in contents.items():
                archive.writestr(name, data)
        self.archive = stream.getvalue()
        self.index["archive_sha256"] = mpl.digest(self.archive)

    def test_all_mpl_components_have_complete_original_source_archives(self):
        result = self.verify()
        self.assertEqual(12, result["components"])
        self.assertEqual(11, result["source_archives"])

    def test_removing_an_indexed_component_is_rejected(self):
        self.index["components"].pop()
        with self.assertRaisesRegex(ValueError, "component coverage"):
            self.verify()

    def test_replacing_a_source_with_another_version_is_rejected(self):
        self.index["components"][0]["version"] = "999"
        with self.assertRaisesRegex(ValueError, "component coverage"):
            self.verify()

    def test_upstream_urls_cannot_drift_from_locked_revisions(self):
        self.index["components"][0]["url"] = "https://github.com/helix-editor/nucleo/archive/main.tar.gz"
        with self.assertRaisesRegex(ValueError, "package/revision binding"):
            self.verify()

    def test_changed_crate_is_rejected_even_if_delivery_hashes_are_updated(self):
        record = next(c for c in self.index["components"] if c["checksum"])
        self.rewrite_archive(lambda contents: contents.update({record["path"]: b"substituted sources"}))
        record["size"] = len(b"substituted sources")
        record["sha256"] = mpl.digest(b"substituted sources")
        with self.assertRaisesRegex(ValueError, "published crate checksum"):
            self.verify()

    def test_source_notice_must_explain_direct_offline_access(self):
        self.notice = b"MPL-2.0 license texts are available."
        self.index["notice_sha256"] = mpl.digest(self.notice)
        with self.assertRaisesRegex(ValueError, "availability notice"):
            self.verify()

    def test_original_mpl_license_cannot_be_removed_from_git_sources(self):
        record = next(c for c in self.index["components"] if not c["checksum"])
        with zipfile.ZipFile(io.BytesIO(self.archive)) as archive:
            source = archive.read(record["path"])
        stream = io.BytesIO()
        with tarfile.open(fileobj=io.BytesIO(source), mode="r:gz") as original:
            with tarfile.open(fileobj=stream, mode="w:gz") as changed:
                for member in original:
                    if Path(member.name).name == "LICENSE":
                        continue
                    changed.addfile(member, original.extractfile(member) if member.isfile() else None)
        data = stream.getvalue()
        self.rewrite_archive(lambda contents: contents.update({record["path"]: data}))
        for component in self.index["components"]:
            if component["path"] == record["path"]:
                component.update(size=len(data), sha256=mpl.digest(data))
        with self.assertRaisesRegex(ValueError, "Original MPL license missing"):
            self.verify()

    def test_notice_inside_source_zip_is_preserved(self):
        self.rewrite_archive(lambda contents: contents.update({"MPL-SOURCE-OFFER.txt": b"removed instructions"}))
        with self.assertRaisesRegex(ValueError, "bundled notice"):
            self.verify()

    def test_future_mpl_dependency_without_sources_fails(self):
        component = copy.deepcopy(self.dependencies["components"][0])
        component.update(name="new-mpl-component", version="1.0", license="MPL-2.0")
        self.dependencies["components"].append(component)
        with self.assertRaisesRegex(ValueError, "availability notice"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
