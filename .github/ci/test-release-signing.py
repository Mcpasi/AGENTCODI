#!/usr/bin/env python3
"""Check secret validation, private staging and cleanup through the CI entry point."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("release_signing", ROOT / ".github/ci/prepare-release-signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def fixture_secrets():
    return {
        "AGENTCODI_RELEASE_KEYSTORE_BASE64": base64.b64encode(b"private-keystore-fixture").decode(),
        "AGENTCODI_RELEASE_STORE_PASSWORD": "store '$ fixture",
        "AGENTCODI_RELEASE_KEY_PASSWORD": "key ` fixture",
        "AGENTCODI_RELEASE_KEY_ALIAS": "agentcodi-release",
        "AGENTCODI_RELEASE_CERT_SHA256": ":".join(["AB"] * 32),
    }


class ReleaseSigningTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        self.environment = fixture_secrets()

    def test_wrapped_base64_and_keytool_fingerprint_are_normalized(self):
        self.environment["AGENTCODI_RELEASE_KEYSTORE_BASE64"] += "\n"
        keystore, alias, fingerprint = signing.validate(self.environment)
        self.assertEqual(keystore, b"private-keystore-fixture")
        self.assertEqual(alias, "agentcodi-release")
        self.assertEqual(fingerprint, "ab" * 32)

    def test_missing_and_invalid_secrets_fail_without_disclosing_values(self):
        for name in signing.REQUIRED:
            environment = dict(self.environment)
            environment.pop(name)
            with self.subTest(missing=name), self.assertRaisesRegex(ValueError, name):
                signing.validate(environment)
        for name, value in (
            ("AGENTCODI_RELEASE_KEYSTORE_BASE64", "invalid-base64-fixture!"),
            ("AGENTCODI_RELEASE_KEYSTORE_BASE64", " \n"),
            ("AGENTCODI_RELEASE_STORE_PASSWORD", "password-with\nnewline"),
            ("AGENTCODI_RELEASE_KEY_PASSWORD", "password-with\0nul"),
            ("AGENTCODI_RELEASE_KEY_ALIAS", "alias with spaces"),
            ("AGENTCODI_RELEASE_CERT_SHA256", "invalid-fingerprint-fixture"),
        ):
            environment = {**self.environment, name: value}
            with self.subTest(invalid=name):
                with self.assertRaises(ValueError) as error:
                    signing.validate(environment)
                self.assertIn(name, str(error.exception))
                self.assertNotIn(value, str(error.exception))

    def test_public_debug_certificate_is_rejected(self):
        source = (ROOT / "scripts/sign-debug-apk.py").read_text()
        fingerprint = signing.re.search(r'^CERTIFICATE_SHA256 = "([0-9a-f]{64})"$', source, signing.re.M).group(1)
        with self.assertRaisesRegex(ValueError, "public development certificate"):
            signing.validate({**self.environment, "AGENTCODI_RELEASE_CERT_SHA256": fingerprint})

    def test_staged_files_are_private_and_preserve_password_characters(self):
        directory = self.work / "signing"
        directory.mkdir(mode=0o700)
        signing.prepare(directory, self.environment)
        self.assertEqual((directory / "release.keystore").read_bytes(), b"private-keystore-fixture")
        for filename, secret in (("store-password", "AGENTCODI_RELEASE_STORE_PASSWORD"),
                                 ("key-password", "AGENTCODI_RELEASE_KEY_PASSWORD")):
            self.assertEqual((directory / filename).read_text(), self.environment[secret] + "\n")
        for path in directory.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)

    def test_checkout_and_linked_or_shared_directories_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "outside the checkout"):
            signing.prepare(ROOT, self.environment)
        directory = self.work / "shared"
        directory.mkdir(mode=0o755)
        with self.assertRaisesRegex(ValueError, "group or other"):
            signing.prepare(directory, self.environment)
        link = self.work / "linked"
        link.symlink_to(directory, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "real directory"):
            signing.prepare(link, self.environment)

    def test_existing_signing_files_are_never_overwritten_or_followed(self):
        directory = self.work / "signing"
        directory.mkdir(mode=0o700)
        outside = self.work / "outside"
        outside.write_bytes(b"unchanged")
        (directory / "release.keystore").symlink_to(outside)
        with self.assertRaises(FileExistsError):
            signing.prepare(directory, self.environment)
        self.assertEqual(outside.read_bytes(), b"unchanged")

    def test_ci_wrapper_passes_only_file_paths_and_cleans_up_on_success_and_failure(self):
        fake_bin = self.work / "bin"
        fake_bin.mkdir()
        docker = fake_bin / "docker"
        docker.write_text(
            "#!" + shutil.which("python3") + "\n"
            "import json, os, sys\n"
            "from pathlib import Path\n"
            "directory = Path(os.environ['AGENTCODI_CI_SIGNING_DIR'])\n"
            "raw_names = ['AGENTCODI_RELEASE_KEYSTORE_BASE64', 'AGENTCODI_RELEASE_STORE_PASSWORD', 'AGENTCODI_RELEASE_KEY_PASSWORD']\n"
            "report = {'args': sys.argv[1:], 'raw_secrets_present': any(name in os.environ for name in raw_names),\n"
            "          'files_present': all((directory / name).is_file() for name in ['release.keystore', 'store-password', 'key-password']),\n"
            "          'fingerprint': os.environ['AGENTCODI_RELEASE_CERT_SHA256']}\n"
            "Path(os.environ['DOCKER_REPORT']).write_text(json.dumps(report))\n"
            "sys.exit(int(os.environ['DOCKER_STATUS']))\n")
        docker.chmod(0o755)
        for status in (0, 17):
            with self.subTest(status=status):
                directory = self.work / ("agentcodi-release-" + str(status))
                report_path = self.work / ("docker-" + str(status) + ".json")
                environment = {
                    **os.environ, **self.environment,
                    "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
                    "RUNNER_TEMP": str(self.work),
                    "IMAGE": "release-fixture",
                    "AGENTCODI_CI_SIGNING_DIR": str(directory),
                    "DOCKER_REPORT": str(report_path),
                    "DOCKER_STATUS": str(status),
                }
                result = subprocess.run(
                    ["bash", str(ROOT / ".github/ci/build-release-apk.sh")],
                    env=environment, capture_output=True, text=True, timeout=20)
                self.assertEqual(result.returncode, status, result.stdout + result.stderr)
                self.assertFalse(directory.exists(), "Temporary signing material was retained")
                report = json.loads(report_path.read_text())
                self.assertFalse(report["raw_secrets_present"])
                self.assertTrue(report["files_present"])
                self.assertEqual(report["fingerprint"], "ab" * 32)
                self.assertIn("type=bind,source=" + str(directory) + ",target=/run/agentcodi-release,readonly", report["args"])
                self.assertEqual(report["args"][-2:], ["release-fixture", "./scripts/build-release-apk.sh"])
                for name in signing.REQUIRED[:3]:
                    self.assertNotIn(self.environment[name], result.stdout + result.stderr + json.dumps(report))

    def test_ci_wrapper_fails_before_creating_files_or_calling_docker_when_secrets_are_missing(self):
        environment = {name: value for name, value in os.environ.items() if name not in signing.REQUIRED}
        directory = self.work / "agentcodi-release-missing"
        environment.update(RUNNER_TEMP=str(self.work), IMAGE="fixture", AGENTCODI_CI_SIGNING_DIR=str(directory))
        result = subprocess.run(
            ["bash", str(ROOT / ".github/ci/build-release-apk.sh")],
            env=environment, capture_output=True, text=True, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing repository secrets", result.stderr)
        self.assertFalse(directory.exists())


if __name__ == "__main__":
    unittest.main()
