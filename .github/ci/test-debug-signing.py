#!/usr/bin/env python3
"""Exercise the production debug signing branch with real SDK-built APKs."""
import hashlib
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
        print("Two clean APK builds keep the same update signer: " + self.certificate(first))


if __name__ == "__main__":
    unittest.main()
