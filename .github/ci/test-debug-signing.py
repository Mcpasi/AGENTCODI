#!/usr/bin/env python3
"""Exercise the production debug signing branch with real SDK-built APKs."""
import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIRST_APK, UPGRADE_APK, APKSIGNER = map(Path, sys.argv[1:4])
spec = importlib.util.spec_from_file_location("debug_signer", ROOT / "scripts/sign-debug-apk.py")
signer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signer)
sys.argv = [sys.argv[0]] + sys.argv[4:]


def debug_signing_branch(root):
    source = (root / "scripts/build-debug-apk.sh").read_text()
    marker = '\nif [ "$BUILD_VARIANT" = "debug" ]; then\n'
    # Execute the production signing stage without rebuilding unrelated native payloads.
    return source.rsplit(marker, 1)[1].split("\nelse\n", 1)[0]


class DebugSigningTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)

    def sign(self, name, unsigned, root=ROOT, success=True):
        work = self.work / name
        work.mkdir(exist_ok=True)
        cache, output = work / "cache", work / "output"
        cache.mkdir(exist_ok=True)
        output.mkdir(exist_ok=True)
        environment = dict(
            os.environ,
            PATH=str(APKSIGNER.parent) + os.pathsep + os.environ["PATH"],
            PROJECT_ROOT=str(root), CACHE_DIR=str(cache), OUTPUT_DIR=str(output),
            KEYTOOL=shutil.which("keytool"), MIN_SDK="29",
            APP_ARTIFACT_NAME="signing-fixture", APP_VERSION=name,
            ABI="arm64-v8a", ALIGNED_APK=str(unsigned),
            AGENTCODI_CACHE_DIR=str(cache))
        result = subprocess.run(
            ["bash", "-Eeuo", "pipefail", "-c", debug_signing_branch(root)],
            env=environment, capture_output=True, text=True, timeout=45)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        apk = output / ("signing-fixture-" + name + "-arm64-v8a-debug.apk")
        return apk, result

    def certificate(self, apk):
        result = subprocess.run(
            [str(APKSIGNER), "verify", "--min-sdk-version", "29", "--print-certs", str(apk)],
            check=True, capture_output=True, text=True, timeout=20)
        certificates = re.findall(
            r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$",
            result.stdout, re.M)
        self.assertEqual(len(certificates), 1, result.stdout)
        return certificates[0]

    def test_clean_builds_keep_the_same_update_signer(self):
        first, _ = self.sign("first", FIRST_APK)
        upgrade, _ = self.sign("upgrade", UPGRADE_APK)
        self.assertEqual(
            self.certificate(first), self.certificate(upgrade),
            "Fresh CI runners changed the debug certificate; Android rejects the update")
        self.assertEqual(self.certificate(first), signer.CERTIFICATE_SHA256)
        self.assertNotEqual(FIRST_APK.read_bytes(), UPGRADE_APK.read_bytes())
        print("Two clean APK builds keep the same update signer: " + self.certificate(first))

    def copy_project(self, name):
        project = self.work / name / "project"
        shutil.copytree(ROOT / "scripts/debug-signing", project / "scripts/debug-signing")
        for filename in ("build-debug-apk.sh", "sign-debug-apk.py"):
            shutil.copy2(ROOT / "scripts" / filename, project / "scripts" / filename)
        return project

    def test_legacy_cached_keystore_is_ignored_and_preserved(self):
        cache = self.work / "stale" / "cache"
        cache.mkdir(parents=True)
        legacy = cache / "agentcodi-debug.keystore"
        subprocess.run([
            shutil.which("keytool"), "-genkeypair", "-noprompt",
            "-keystore", str(legacy), "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-dname", "CN=Legacy CI Debug",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000"],
            check=True, capture_output=True, text=True, timeout=30)
        previous = hashlib.sha256(legacy.read_bytes()).hexdigest()
        apk, _ = self.sign("stale", FIRST_APK)
        self.assertEqual(self.certificate(apk), signer.CERTIFICATE_SHA256)
        self.assertEqual(hashlib.sha256(legacy.read_bytes()).hexdigest(), previous)

    def test_missing_tracked_key_fails_without_generating_a_replacement(self):
        project = self.copy_project("missing")
        (project / "scripts/debug-signing/testkey.pk8.b64").unlink()
        apk, result = self.sign("missing", FIRST_APK, root=project, success=False)
        self.assertIn("Debug APK signing failed", result.stderr)
        self.assertFalse(apk.exists())
        self.assertFalse((self.work / "missing/cache/agentcodi-debug.keystore").exists())

    def test_modified_key_or_certificate_is_rejected(self):
        for filename in ("testkey.pk8.b64", "testkey.x509.pem"):
            with self.subTest(filename=filename):
                name = "modified-" + filename
                project = self.copy_project(name)
                target = project / "scripts/debug-signing" / filename
                if filename.endswith(".b64"):
                    key = bytearray(base64.b64decode(target.read_text()))
                    key[-1] ^= 1
                    target.write_bytes(base64.b64encode(key) + b"\n")
                else:
                    target.write_text(target.read_text().replace("MIIEq", "MIIEr", 1))
                apk, result = self.sign(name, FIRST_APK, root=project, success=False)
                self.assertIn("checksum mismatch", result.stderr)
                self.assertFalse(apk.exists())

    def test_release_verification_rejects_the_public_development_identity(self):
        apk, _ = self.sign("release-guard", FIRST_APK)
        report = subprocess.run(
            [str(APKSIGNER), "verify", "--min-sdk-version", "29", "--print-certs", str(apk)],
            check=True, capture_output=True, text=True, timeout=20).stdout
        source = (ROOT / "scripts/build-debug-apk.sh").read_text()
        verification = "signer_count=" + source.split("\nsigner_count=", 1)[1].split("\nbadging=", 1)[0]
        environment = dict(
            os.environ, certificate_report=report, BUILD_VARIANT="release",
            EXPECTED_RELEASE_CERT_SHA256=signer.CERTIFICATE_SHA256,
            DEBUG_CERT_SHA256=signer.CERTIFICATE_SHA256)
        result = subprocess.run(
            ["bash", "-Eeuo", "pipefail", "-c", verification],
            env=environment, capture_output=True, text=True, timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release APK must not use the public development test certificate.", result.stderr)


if __name__ == "__main__":
    unittest.main()
