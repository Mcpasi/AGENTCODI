#!/usr/bin/env python3
"""Host regressions for input restoration, verification and cache selection."""
import hashlib
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

CI = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("build_input_cache", CI / "build-input-cache.py")
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class BuildInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.ci = self.root / ".github/ci"
        self.ci.mkdir(parents=True)
        self.target = self.root / "cache"
        self.target.mkdir()
        self.scripts = self.root / "scripts"
        self.scripts.mkdir()
        self.payload = b"verified edition input\n"
        self.sha = hashlib.sha256(self.payload).hexdigest()
        self.row = "tool.deb\t" + self.sha + "\tdownload\thttps://example.invalid/tool.deb\n"
        (self.ci / "build-inputs.tsv").write_text(self.row)
        for name in ("fetch-build-inputs.sh", "verify-build-inputs.sh"):
            shutil.copy2(CI / name, self.ci / name)
        self.generator = self.ci / "generate-build-inputs.sh"
        self.generator.write_text("#!/bin/sh\ncat \"$(dirname \"$0\")/build-inputs.tsv\"\n")
        self.generator.chmod(0o755)

    def verify(self, success=True):
        result = subprocess.run(
            ["bash", str(self.ci / "verify-build-inputs.sh"), str(self.target)],
            capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def test_missing_and_corrupted_inputs_fail_but_extra_cache_files_are_ignored(self):
        self.verify(False)
        (self.target / "tool.deb").write_bytes(b"wrong")
        self.assertIn("MISMATCH", self.verify(False).stdout)
        (self.target / "tool.deb").write_bytes(self.payload)
        (self.target / "retired-tool.deb").write_bytes(b"unrelated existing data")
        self.verify()

    def test_generator_failure_cannot_silently_accept_cached_bytes(self):
        (self.target / "tool.deb").write_bytes(self.payload)
        self.generator.write_text("#!/bin/sh\nexit 7\n")
        self.assertIn("Cannot regenerate", self.verify(False).stderr)

    def test_manifest_drift_fails_before_verification(self):
        (self.target / "tool.deb").write_bytes(self.payload)
        self.generator.write_text("#!/bin/sh\nprintf 'different manifest\\n'\n")
        self.assertIn("no longer matches", self.verify(False).stderr)

    def test_restore_fetches_only_manifest_inputs_and_preserves_other_files(self):
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        payload = self.root / "payload"
        payload.write_bytes(self.payload)
        curl = fake_bin / "curl"
        curl.write_text("#!/bin/bash\nwhile [ \"$#\" -gt 0 ]; do\n"
                        "  if [ \"$1\" = --output ]; then cp -- \"$FIXTURE_PAYLOAD\" \"$2\"; exit; fi\n"
                        "  shift\ndone\nexit 1\n")
        curl.chmod(0o755)
        gh = fake_bin / "gh"
        gh.write_text("#!/bin/sh\nexit 1\n")
        gh.chmod(0o755)
        unrelated = self.target / "retired-tool.deb"
        unrelated.write_bytes(b"preserved")
        import os
        environment = dict(os.environ, PATH=str(fake_bin) + ":" + os.environ["PATH"],
                           FIXTURE_PAYLOAD=str(payload), AGENTCODI_INPUTS_TAG="fixture",
                           AGENTCODI_FETCH_ATTEMPTS="1")
        result = subprocess.run(
            ["bash", str(self.ci / "fetch-build-inputs.sh"), str(self.target)],
            capture_output=True, text=True, env=environment, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.target / "tool.deb").read_bytes(), self.payload)
        self.assertEqual(unrelated.read_bytes(), b"preserved")
        self.verify()

    def test_cache_selection_omits_sdk_and_never_selects_unlisted_inputs(self):
        sdk = "platform-35_r02.zip\t" + self.sha + "\tdownload\thttps://example.invalid/sdk.zip\n"
        self.assertEqual(cache.cache_paths(self.row + sdk), [".cache/android/tool.deb"])
        for path in ("../outside", "/absolute", "a/../../outside", "./tool.deb"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                cache.cache_paths(self.row.replace("tool.deb\t", path + "\t"))
        with self.assertRaises(ValueError):
            cache.cache_paths(self.row + self.row)
        with self.assertRaises(ValueError):
            cache.cache_paths(self.row.replace(self.sha, "invalid"))


if __name__ == "__main__":
    unittest.main()
