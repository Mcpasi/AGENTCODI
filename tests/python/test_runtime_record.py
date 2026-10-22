import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime_record", ROOT / "scripts/fdroid/record-runtime.py")
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)


class RuntimeRecordTest(unittest.TestCase):
    def fixture(self, directory):
        root = Path(directory)
        assets, native = root / "assets", root / "native"
        assets.mkdir()
        native.mkdir()
        (assets / "source-build.json").write_text('{"from_source":true}\n')
        (native / "libnode.so").write_bytes(b"assembled native payload")
        return assets, root / "transformations.tsv", native

    def record(self, assets, transformations, native):
        with patch.dict(os.environ, SOURCE_DATE_EPOCH="1700000000"), patch.object(
                recorder.subprocess, "check_output", return_value="clang version 21.1.8\n"):
            recorder.record(assets, transformations, native, "test-clang")

    def test_actual_assembler_fingerprints_are_recorded_with_their_payload_hashes(self):
        text = (ROOT / "scripts/build-debug-apk.sh").read_text()
        begin = text.index("record_derived_hash() {")
        end = text.index("\n}\n", begin) + 3
        function = text[begin:end]
        # Exercise the real TSV producer and its literal/attestor key names.
        names = re.findall(r"^record_derived_hash ([A-Z0-9_]+_SHA256) ", text, re.M)
        names += re.findall(r'^    "[a-z]+:\$[A-Z_]+:\$[A-Z_]+:([A-Z0-9_]+_SHA256)"', text, re.M)
        self.assertIn("ICUI18N_RUNTIME_SHA256", names)
        self.assertIn("NODE_ATTESTOR_SHA256", names)
        self.assertEqual(len(names), len(set(names)))
        with tempfile.TemporaryDirectory() as directory:
            assets, transformations, native = self.fixture(directory)
            payload = native / "libnode.so"
            script = 'set -Eeuo pipefail\n' + function + '\nfor name in "$@"; do record_derived_hash "$name" "$PAYLOAD"; done\n'
            subprocess.run(["bash", "-c", script, "fingerprints", *names], check=True,
                           env=dict(os.environ, DERIVED_HASHES=str(transformations), PAYLOAD=str(payload)))
            self.record(assets, transformations, native)
            result = json.loads((assets / "assembled-runtime.json").read_text())
            expected = hashlib.sha256(payload.read_bytes()).hexdigest()
            self.assertEqual(result["transformations_sha256"], dict.fromkeys(names, expected))
            self.assertEqual(result["payload_sha256"], {"libnode.so": expected})
            self.assertEqual(result["source_build_sha256"], recorder.digest(assets / "source-build.json"))
            self.assertEqual(result["assembler_sha256"], recorder.digest(ROOT / "scripts/build-debug-apk.sh"))
            self.assertEqual(result["source_date_epoch"], 1700000000)

    def test_invalid_records_and_duplicates_remain_rejected(self):
        valid = "ICUI18N_RUNTIME_SHA256\t" + "a" * 64 + "\n"
        cases = (
            (valid + valid, "Duplicate.*ICUI18N_RUNTIME_SHA256"),
            ("1RUNTIME_SHA256\t" + "a" * 64, "Invalid.*1RUNTIME_SHA256"),
            ("runtime_SHA256\t" + "a" * 64, "Invalid.*runtime_SHA256"),
            ("ICUI18N_RUNTIME_SHA256\t" + "A" * 64, "Invalid.*ICUI18N_RUNTIME_SHA256"),
            ("ICUI18N_RUNTIME_SHA256\tshort", "Invalid.*ICUI18N_RUNTIME_SHA256"),
            (valid + "missing-value\n", "record on line 2"),
            (valid + "extra\tfield\tvalue\n", "record on line 2"),
        )
        for contents, message in cases:
            with self.subTest(contents=contents), tempfile.TemporaryDirectory() as directory:
                assets, transformations, native = self.fixture(directory)
                transformations.write_text(contents)
                with self.assertRaisesRegex(ValueError, message):
                    self.record(assets, transformations, native)
                self.assertFalse((assets / "assembled-runtime.json").exists())

    def test_linked_native_payload_remains_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            assets, transformations, native = self.fixture(directory)
            transformations.write_text("ICUI18N_RUNTIME_SHA256\t" + "a" * 64 + "\n")
            (native / "libalias.so").symlink_to(native / "libnode.so")
            with self.assertRaisesRegex(ValueError, "Invalid native payload"):
                self.record(assets, transformations, native)


if __name__ == "__main__":
    unittest.main()
