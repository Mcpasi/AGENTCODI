#!/usr/bin/env python3
"""Check the transfer contract with synthetic secrets and local sealed boxes."""
import base64
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

from nacl.public import PrivateKey, SealedBox

spec = importlib.util.spec_from_file_location(
    "transfer", Path(__file__).with_name("transfer-package-signing-secrets.py"))
transfer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transfer)


class FakeAPI:
    def __init__(self):
        self.private = PrivateKey.generate()
        self.metadata = {"full_name": transfer.TARGET, "id": transfer.TARGET_ID, "archived": False}
        self.calls = []
        self.stored = {}
        self.fail_at = None

    def request(self, method, suffix="", payload=None):
        self.calls.append((method, suffix, payload))
        if not suffix:
            return self.metadata
        if suffix == "/actions/secrets/public-key":
            return {"key_id": "fixture-key-id", "key": base64.b64encode(bytes(self.private.public_key)).decode()}
        name = suffix.rsplit("/", 1)[1]
        if method == "PUT":
            if name == self.fail_at:
                raise transfer.TransferError("GitHub API request failed (HTTP 403).")
            self.stored[name] = payload
            return None
        return {"name": name} if name in self.stored else None


def environment():
    return {
        "GITHUB_REPOSITORY": transfer.SOURCE,
        "GITHUB_REF": transfer.SOURCE_REF,
        "GH_TOKEN": "synthetic-transfer-token",
        **{name: "synthetic value for " + name for name in transfer.REQUIRED},
        "AGENTCODI_APT_SIGNING_KEY": "synthetic armor\nline with ü, $, ` and \\\n\n",
        "AGENTCODI_RELEASE_STORE_PASSWORD": "synthetic 'quote' $value `command` \\",
    }


class SigningSecretTransferTest(unittest.TestCase):
    def setUp(self):
        self.api = FakeAPI()
        self.environment = environment()
        self.log = io.StringIO()

    def run_transfer(self):
        with redirect_stdout(self.log):
            return transfer.transfer(self.environment, lambda _: self.api)

    def test_values_are_encrypted_and_preserved_byte_for_byte(self):
        self.environment[transfer.OPTIONAL[0]] = "synthetic optional passphrase"
        self.environment["UNRELATED_SECRET"] = "do-not-copy"
        with tempfile.TemporaryDirectory() as folder:
            summary = Path(folder) / "summary"
            self.environment["GITHUB_STEP_SUMMARY"] = str(summary)
            names = self.run_transfer()
            self.assertEqual(set(names), set(transfer.REQUIRED + transfer.OPTIONAL))
            self.assertNotIn("UNRELATED_SECRET", self.api.stored)
            self.assertNotIn("AGENTCODI_SECRET_TRANSFER_TOKEN", self.api.stored)
            box = SealedBox(self.api.private)
            for name, payload in self.api.stored.items():
                self.assertEqual(payload["key_id"], "fixture-key-id")
                plaintext = box.decrypt(base64.b64decode(payload["encrypted_value"]))
                self.assertEqual(plaintext, self.environment[name].encode("utf-8"))
                self.assertNotIn(self.environment[name], self.log.getvalue())
                self.assertNotIn(self.environment[name], summary.read_text())

    def test_missing_required_secret_prevents_all_api_calls(self):
        for name in transfer.REQUIRED:
            with self.subTest(name=name):
                candidate = dict(self.environment)
                del candidate[name]
                self.api.calls.clear()
                with self.assertRaisesRegex(transfer.TransferError, name):
                    transfer.transfer(candidate, lambda _: self.api)
                self.assertEqual(self.api.calls, [])

    def test_missing_token_and_wrong_source_branch_cannot_start(self):
        for change in ({"GH_TOKEN": ""}, {"GITHUB_REPOSITORY": "other/repo"},
                       {"GITHUB_REF": "refs/heads/main"},
                       {"GITHUB_REF": "refs/heads/Mcpasi/package-edition"}):
            with self.subTest(change=change), self.assertRaises(transfer.TransferError):
                transfer.transfer({**self.environment, **change}, lambda _: self.api)
        self.assertEqual(self.api.calls, [])

    def test_wrong_destination_id_name_or_archive_status_prevents_writes(self):
        for change in ({"id": 1}, {"full_name": "other/repo"}, {"archived": True}):
            self.api = FakeAPI()
            self.api.metadata.update(change)
            with self.subTest(change=change), self.assertRaises(transfer.TransferError):
                self.run_transfer()
            self.assertFalse(self.api.stored)

    def test_empty_optional_passphrase_is_skipped(self):
        self.environment[transfer.OPTIONAL[0]] = ""
        names = self.run_transfer()
        self.assertEqual(names, list(transfer.REQUIRED))
        self.assertNotIn(transfer.OPTIONAL[0], self.api.stored)

    def test_partial_api_failure_can_be_retried_without_changing_values(self):
        self.api.fail_at = transfer.REQUIRED[2]
        with self.assertRaises(transfer.TransferError) as caught:
            self.run_transfer()
        self.assertEqual(set(self.api.stored), set(transfer.REQUIRED[:2]))
        self.assertIn("rerunning is safe", str(caught.exception))
        for name in transfer.REQUIRED:
            self.assertNotIn(self.environment[name], str(caught.exception))
        self.api.fail_at = None
        self.run_transfer()
        box = SealedBox(self.api.private)
        for name in transfer.REQUIRED:
            self.assertEqual(box.decrypt(base64.b64decode(self.api.stored[name]["encrypted_value"])),
                             self.environment[name].encode())

    def test_redirects_are_rejected_before_credentials_can_be_forwarded(self):
        self.assertIsNone(transfer.NoRedirect().redirect_request(None, None, 302, "redirect", {},
                                                                "https://unrelated.example"))

    def test_http_error_details_and_token_are_not_exposed(self):
        api = transfer.GitHubAPI(self.environment["GH_TOKEN"])
        error = urllib.error.HTTPError("https://api.github.com/fixture", 403,
                                       self.environment["AGENTCODI_APT_SIGNING_KEY"], {}, None)
        with patch.object(api.opener, "open", side_effect=error):
            with self.assertRaises(transfer.TransferError) as caught:
                api.request("GET")
        self.assertEqual(str(caught.exception), "GitHub API request failed (HTTP 403).")
        self.assertNotIn(self.environment["GH_TOKEN"], str(caught.exception))

    def test_unexpected_exception_does_not_print_secret_or_traceback(self):
        private = self.environment["AGENTCODI_APT_SIGNING_KEY"]
        error_log = io.StringIO()
        with patch.object(transfer.sys, "argv", ["transfer", "--transfer"]), \
                patch.object(transfer, "transfer", side_effect=RuntimeError(private)), \
                redirect_stderr(error_log):
            self.assertEqual(transfer.main(), 1)
        self.assertNotIn(private, error_log.getvalue())
        self.assertNotIn("Traceback", error_log.getvalue())


if __name__ == "__main__":
    unittest.main()
