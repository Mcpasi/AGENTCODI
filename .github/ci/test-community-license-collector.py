#!/usr/bin/env python3
"""Check license inheritance, declared alternatives and original attribution."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("collector", Path(__file__).with_name("collect-community-licenses.py"))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)

class CollectorTest(unittest.TestCase):
    def test_reuse_license_directory_keeps_named_terms(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "LICENSES").mkdir()
            (root / "LICENSES/MIT.txt").write_text("permission")
            (root / "unrelated.txt").write_text("data")
            self.assertEqual([root / "LICENSES/MIT.txt"], collector.files(root))

    def test_selected_mit_terms_retain_actual_notice_without_placeholder_dates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "source"
            root.mkdir()
            (root / "lib.rs").write_text("// Copyright 2017 Original Author\nfn main() {}\n")
            (root / "README.md").write_text("Original package documentation")
            package = {"name": "fixture", "version": "1.0", "license": "MIT", "authors": ["Original Author"],
                       "repository": "https://github.com/example/fixture"}
            output, files, evidence = collector.declared_terms(package, root, Path(tmp) / "output", root)
            text = (output / "LICENSE-MIT.txt").read_text()
            self.assertIn("Permission is hereby granted", text)
            self.assertNotIn("<year>", text)
            self.assertNotIn("<copyright holders>", text)
            self.assertEqual("MIT", evidence["selected_spdx"])
            self.assertIn("// Copyright 2017 Original Author",
                          (output / "ORIGINAL-COPYRIGHT-NOTICES.txt").read_text())
            self.assertIn("\nSelected distribution terms: MIT\n",
                          (output / "ATTRIBUTION.txt").read_text())

    def test_apache_alternative_is_selected_only_when_declared(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "LICENSE").write_text("Complete original Apache terms")
            package = {"name": "fixture", "version": "1.0", "license": "MIT OR Apache-2.0",
                       "authors": ["Author"]}
            output, _, evidence = collector.declared_terms(package, root, root / "out", root)
            self.assertEqual("Complete original Apache terms", (output / "LICENSE-Apache-2.0.txt").read_text())
            self.assertEqual("Apache-2.0", evidence["selected_spdx"])

    def test_unsupported_license_is_not_silently_replaced(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(ValueError, "No reviewed standard-license selection"):
                collector.declared_terms({"name": "fixture", "version": "1.0", "license": "BSD-2-Clause"},
                                         root, root / "out", root)

if __name__ == "__main__":
    unittest.main()
