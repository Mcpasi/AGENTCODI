#!/usr/bin/env python3
"""Verify the one-token allowlist, byte preservation and fail-before-write checks."""
from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import unittest
from nacl.public import SealedBox


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


transfer = module("input_transfer", "transfer-package-build-input-token.py")
fixtures = module("fixtures", "transfer-package-signing-tests.py")


class BuildInputTokenTest(unittest.TestCase):
    def test_only_input_token_is_copied_without_leaking_values(self):
        api = fixtures.FakeAPI()
        environment = fixtures.environment()
        value = "synthetic mirror token $ ` ' ü\n"
        environment[transfer.NAME] = value
        checked = []
        log = io.StringIO()
        with redirect_stdout(log):
            transfer.transfer(environment, lambda _: api, checked.append)
        self.assertEqual(checked, [value])
        self.assertEqual(set(api.stored), {transfer.NAME})
        import base64
        self.assertEqual(SealedBox(api.private).decrypt(
            base64.b64decode(api.stored[transfer.NAME]["encrypted_value"])), value.encode("utf-8"))
        self.assertNotIn(value, log.getvalue())

    def test_missing_token_causes_no_api_calls(self):
        api = fixtures.FakeAPI()
        with self.assertRaises(transfer.shared.TransferError):
            transfer.transfer(fixtures.environment(), lambda _: api, lambda _: None)
        self.assertEqual(api.calls, [])

    def test_unusable_mirror_token_causes_no_api_writes(self):
        api = fixtures.FakeAPI()
        environment = fixtures.environment()
        environment[transfer.NAME] = "synthetic token"
        def reject(_):
            raise transfer.shared.TransferError("Private build-mirror read access failed.")
        with self.assertRaises(transfer.shared.TransferError):
            transfer.transfer(environment, lambda _: api, reject)
        self.assertEqual(api.calls, [])


if __name__ == "__main__":
    unittest.main()
