#!/usr/bin/env python3
"""Regression tests for actual APK bytes, bootstrap legal ownership and release gates."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("contract", REPO / "scripts/package-edition/verify-apk-contract.py")
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class ApkContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.staged = self.root / "staged"
        self.project = self.root / "project"
        self.apk = self.root / "fixture.apk"
        spec = contract.contract()
        self.entries = {"lib/" + spec["abi"] + "/" + name: name.encode()
                        for name in spec["native_files"]}
        self.entries.update({name: ("Legal fixture: " + name).encode()
                             for name in spec["third_party_assets"]})
        for name in spec["resources"]:
            self.entries[name] = (REPO / "app/src/main" / name).read_bytes()
            path = self.project / "app/src/main" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.entries[name])
        distributor = (REPO / "third_party/libcxx/DISTRIBUTOR-LICENSE").read_bytes()
        self.entries["assets/third-party/libcxx/DISTRIBUTOR-LICENSE"] = distributor
        path = self.project / "third_party/libcxx/DISTRIBUTOR-LICENSE"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(distributor)
        llvm = (REPO / "third_party/libcxx/LLVM-LICENSES").read_bytes()
        self.entries["assets/third-party/libcxx/LLVM-LICENSES"] = llvm
        path = self.project / "third_party/libcxx/LLVM-LICENSES"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(llvm)
        prefix = "/data/data/de.agentcodi.pkg/files/usr"
        self.legal = "share/doc/fixture/copyright"
        files = {self.legal: b"Copyright fixture\nMIT License\n",
                 "var/lib/dpkg/info/fixture.list": (prefix + "/" + self.legal + "\n").encode()}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, data in files.items():
                archive.writestr(name, data)
        manifest = ("AGENTCODI_BOOTSTRAP_V1\n" + "".join(
            "F\t600\t" + str(len(data)) + "\t" + contract.digest(data) + "\t" + name + "\n"
            for name, data in files.items())).encode()
        record = {"path": self.legal, "installed_path": self.legal, "size": len(files[self.legal]),
                  "sha256": contract.digest(files[self.legal])}
        self.report = {"archive_sha256": contract.digest(stream.getvalue()),
                       "manifest_sha256": contract.digest(manifest),
                       "lock": {"target": {"prefix": prefix}},
                       "packages": {"fixture": {"Version": "1.0"}},
                       "licenses": {"fixture": [record]},
                       "corresponding_sources": {"archive": "bootstrap-corresponding-sources.tar.xz",
                                                 "sha256": "1" * 64}}
        self.index = {"format_version": 1, "packages": [
            {"name": "fixture", "version": "1.0", "files": [record], "notice": ""}]}
        self.bootstrap = "assets/third-party/package-bootstrap/"
        self.entries[self.bootstrap + "bootstrap-aarch64.zip"] = stream.getvalue()
        self.entries[self.bootstrap + "BOOTSTRAP-MANIFEST"] = manifest
        self.update_report()
        self.write()

    def update_report(self):
        self.entries[self.bootstrap + "bootstrap-report.json"] = json.dumps(self.report).encode()
        self.entries[self.bootstrap + "BOOTSTRAP-LICENSE-INDEX.json"] = json.dumps(self.index).encode()

    def write(self, staged=True):
        if staged:
            for name, data in self.entries.items():
                if name.startswith(("lib/", "assets/")):
                    path = self.staged / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)

    def verify(self, release=False):
        return contract.verify(self.apk, self.staged, self.project, release=release)

    def test_exact_payload_and_legal_bytes_pass_with_explicit_release_gap(self):
        report = self.verify()
        self.assertFalse(report["final_release_ready"])
        self.assertEqual(1, len(report["release_blockers"]))
        self.assertEqual([self.legal], [x["path"] for x in report["bootstrap"]["licenses"]["fixture"]])

    def shared_license(self, target="../../LICENSES/MIT.txt"):
        shared = "share/LICENSES/MIT.txt"
        prefix = self.report["lock"]["target"]["prefix"]
        data = b"Copyright fixture\nMIT License\n"
        files = {shared: data,
                 "var/lib/dpkg/info/fixture.list": (prefix + "/" + self.legal + "\n").encode(),
                 "var/lib/dpkg/info/termux-licenses.list": (prefix + "/" + shared + "\n").encode()}
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            for name, value in files.items():
                archive.writestr(name, value)
        manifest = ("AGENTCODI_BOOTSTRAP_V1\nL\t" + target + "\t" + self.legal + "\n" + "".join(
            "F\t600\t" + str(len(value)) + "\t" + contract.digest(value) + "\t" + name + "\n"
            for name, value in files.items())).encode()
        linked = {"path": shared, "installed_path": self.legal,
                  "size": len(data), "sha256": contract.digest(data)}
        direct = {**linked, "installed_path": shared}
        self.report["archive_sha256"] = contract.digest(stream.getvalue())
        self.report["manifest_sha256"] = contract.digest(manifest)
        self.report["packages"]["termux-licenses"] = {"Version": "2.2"}
        self.report["licenses"] = {"fixture": [linked], "termux-licenses": [direct]}
        self.index["packages"] = [
            {"name": name, "version": value["Version"],
             "files": self.report["licenses"][name], "notice": ""}
            for name, value in sorted(self.report["packages"].items())]
        self.entries[self.bootstrap + "bootstrap-aarch64.zip"] = stream.getvalue()
        self.entries[self.bootstrap + "BOOTSTRAP-MANIFEST"] = manifest
        self.update_report()
        self.write()

    def test_manifest_owned_shared_license_links_are_resolved(self):
        self.shared_license()
        report = self.verify()
        self.assertEqual("share/LICENSES/MIT.txt",
                         report["bootstrap"]["licenses"]["fixture"][0]["path"])
        self.assertEqual(1, len(report["release_blockers"]))

    def test_escaping_legal_link_is_rejected_without_host_access(self):
        self.shared_license("../../../../../../outside")
        with self.assertRaisesRegex(ValueError, "leaves bootstrap prefix"):
            self.verify()

    def test_foreign_abi_is_rejected(self):
        self.entries["lib/x86_64/libcodex.so"] = b"foreign"
        self.write()
        with self.assertRaisesRegex(ValueError, "native file/ABI"):
            self.verify()

    def test_retired_and_unknown_assets_are_rejected(self):
        for name in ("assets/third-party/node/LICENSE", "assets/unreviewed.bin"):
            self.entries[name] = b"unexpected"
            self.write()
            with self.assertRaisesRegex(ValueError, "asset set"):
                self.verify()
            del self.entries[name]

    def test_native_mutation_after_staging_is_rejected(self):
        self.entries["lib/arm64-v8a/libcodex.so"] = b"mutated"
        self.write(staged=False)
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.verify()

    def test_legal_mutation_after_staging_is_rejected(self):
        self.entries["assets/third-party/codex/NOTICE"] = b"mutated"
        self.write(staged=False)
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            self.verify()

    def test_bootstrap_manifest_mutation_is_rejected(self):
        self.entries[self.bootstrap + "BOOTSTRAP-MANIFEST"] += b"invalid\n"
        self.write()
        with self.assertRaisesRegex(ValueError, "manifest checksum"):
            self.verify()

    def test_unindexed_bootstrap_license_is_rejected(self):
        self.report["licenses"]["fixture"] = []
        self.update_report()
        self.write()
        with self.assertRaisesRegex(ValueError, "Unindexed"):
            self.verify()

    def test_wrong_bootstrap_license_digest_is_rejected(self):
        self.report["licenses"]["fixture"][0]["sha256"] = "2" * 64
        self.update_report()
        self.write()
        with self.assertRaisesRegex(ValueError, "license checksum"):
            self.verify()

    def test_ui_index_must_match_real_package_legal_files(self):
        self.index["packages"][0]["version"] = "0.9"
        self.update_report()
        self.write()
        with self.assertRaisesRegex(ValueError, "UI license index"):
            self.verify()

    def test_missing_corresponding_source_evidence_is_rejected(self):
        del self.report["corresponding_sources"]
        self.update_report()
        self.write()
        with self.assertRaisesRegex(ValueError, "corresponding sources"):
            self.verify()

    def test_final_release_is_blocked_until_dependency_notices_are_complete(self):
        with self.assertRaisesRegex(ValueError, "Final APK license gate"):
            self.verify(release=True)

    def test_duplicate_apk_entry_is_rejected(self):
        with zipfile.ZipFile(self.apk, "a") as archive:
            archive.writestr("assets/third-party/codex/NOTICE", b"duplicate")
        with self.assertRaisesRegex(ValueError, "Duplicate APK"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
