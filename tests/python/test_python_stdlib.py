import contextlib
import importlib.util
import io
import marshal
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("python_stdlib", ROOT / "scripts/fdroid/compile-python-stdlib.py")
stdlib = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stdlib)


class PythonStdlibTest(unittest.TestCase):
    def test_invalid_regression_fixtures_are_excluded_but_runtime_modules_compile(self):
        with tempfile.TemporaryDirectory() as temporary:
            # A parent called tests must not cause the whole stdlib to be skipped.
            root = Path(temporary) / "tests/stdlib"
            module = root / "encodings/__init__.py"
            module.parent.mkdir(parents=True)
            module.write_text("value = 42\n")
            for name in ("test/test_future_stmt/badsyntax_future.py", "test/tokenizedata/bad_coding.py",
                         "idlelib/idle_test/bad.py", "package/tests/bad.py"):
                fixture = root / name
                fixture.parent.mkdir(parents=True, exist_ok=True)
                fixture.write_bytes(b"# coding: uft-8\nthis is invalid syntax !\n")
            stdlib.compile_stdlib(root)
            self.assertTrue(module.with_suffix(".pyc").is_file())
            self.assertEqual(list(root.rglob("bad*.pyc")), [])
            bytecode = module.with_suffix(".pyc").read_bytes()
            self.assertEqual(marshal.loads(bytecode[16:]).co_filename, "/usr/lib/python3.14/encodings/__init__.py")

    def test_invalid_runtime_source_still_fails_the_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "runtime.py").write_text("invalid syntax !\n")
            with contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(ValueError, "bytecode compilation failed"):
                    stdlib.compile_stdlib(root)

    def test_bytecode_does_not_depend_on_build_path_or_source_timestamp(self):
        with tempfile.TemporaryDirectory() as temporary:
            outputs = []
            for index in range(2):
                root = Path(temporary) / str(index)
                root.mkdir()
                module = root / "runtime.py"
                module.write_text("value = 42\n")
                os.utime(module, (100 + index, 200 + index))
                stdlib.compile_stdlib(root)
                outputs.append(module.with_suffix(".pyc").read_bytes())
            self.assertEqual(outputs[0], outputs[1])
            self.assertEqual(int.from_bytes(outputs[0][4:8], "little"), 3)
