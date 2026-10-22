import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("python_payload", ROOT / "scripts/fdroid/verify-python-payload.py")
payload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class PythonPayloadTest(unittest.TestCase):
    def fixture(self, path, *, excluded=None, library=None, changed=False, missing=False, extra=False, duplicate=False):
        records = ["AGENTCODI_TOOL_RUNTIME_V1"]
        with zipfile.ZipFile(path, "w") as archive:
            for index in range(72):
                module = {15: "_decimal", 18: "_heapq", 65: "syslog"}.get(index, f"_fixture_{index}")
                if index == 7 and excluded:
                    module = excluded
                alias = f"libpython_ext_{index:03d}.so"
                data = module.encode()
                digest = hashlib.sha256(data).hexdigest()
                target = f"python/lib/python3.14/lib-dynload/{module}.cpython-314-aarch64-linux-android.so"
                record = f"L\t{digest}\t{alias}\t{target}"
                records.append(record)
                if index == 7 and duplicate:
                    records.append(record)
                if index != 7 or not missing:
                    archive.writestr("lib/arm64-v8a/" + alias, b"changed" if index == 7 and changed else data)
            if library:
                archive.writestr("lib/arm64-v8a/" + library, b"excluded runtime")
            if extra:
                archive.writestr("lib/arm64-v8a/libpython_ext_999.so", b"unexpected module")
            archive.writestr("assets/third-party/toolchain/RUNTIME-MANIFEST", "\n".join(records) + "\n")

    def test_source_build_aliases_keep_decimal_heapq_and_syslog(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "runtime.apk"
            self.fixture(apk)
            payload.verify(apk, "arm64-v8a", 72)

    def test_excluded_modules_are_rejected_by_identity_at_a_different_alias(self):
        for module in ("_dbm", "_gdbm", "readline"):
            with self.subTest(module=module), tempfile.TemporaryDirectory() as directory:
                apk = Path(directory) / "runtime.apk"
                self.fixture(apk, excluded=module)
                with self.assertRaisesRegex(ValueError, "excluded Python extension"):
                    payload.verify(apk, "arm64-v8a", 72)

    def test_excluded_gnu_libraries_remain_rejected(self):
        for library in ("libgdbm.so", "libgdbm_compat.so", "libgdbm.so.6", "libreadline_8.so"):
            with self.subTest(library=library), tempfile.TemporaryDirectory() as directory:
                apk = Path(directory) / "runtime.apk"
                self.fixture(apk, library=library)
                with self.assertRaisesRegex(ValueError, "excluded GNU runtime"):
                    payload.verify(apk, "arm64-v8a", 72)

    def test_missing_extra_changed_and_duplicate_modules_are_rejected(self):
        cases = (
            ({"missing": True}, "extension set differs"),
            ({"extra": True}, "extension set differs"),
            ({"missing": True, "extra": True}, "extension set differs"),
            ({"changed": True}, "extension differs"),
            ({"duplicate": True}, "duplicate Python extension mapping"),
        )
        for changes, message in cases:
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory:
                apk = Path(directory) / "runtime.apk"
                self.fixture(apk, **changes)
                with self.assertRaisesRegex(ValueError, message):
                    payload.verify(apk, "arm64-v8a", 72)


if __name__ == "__main__":
    unittest.main()
