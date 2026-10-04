#!/usr/bin/env python3
"""Regress corresponding-source coverage for runtime and automatic static DEBs."""
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

HERE = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("sources", HERE / "scripts/package-edition/collect-bootstrap-sources.py")
sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sources)

class CatalogSourcesTest(unittest.TestCase):
    def test_static_build_dependency_retains_parent_recipe_and_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recipes, build, output = root / "recipes", root / "build", root / "output"
            output.mkdir()
            for name in ("root", "dependency"):
                package = recipes / "packages" / name
                package.mkdir(parents=True)
                (package / "build.sh").write_text("# source recipe fixture\n")
                cache = build / name / "cache"
                cache.mkdir(parents=True)
                (cache / "source.tar").write_bytes(name.encode())
            for name in ("scripts", "ndk-patches"):
                (recipes / name).mkdir()
            for name in ("build-package.sh", "repo.json", "agentcodi.env",
                         "agentcodi-build-package.sh", "agentcodi-preparation.json"):
                (recipes / name).write_text("fixture\n")
            (output / "bootstrap-report.json").write_text(json.dumps({
                "packages": {"root": {}}, "catalog": {"group": "fixture"}}))
            (output / "all-built-packages.json").write_text(json.dumps({
                "root": {}, "dependency": {}, "dependency-static": {}}))
            sources.collect(recipes, build, output)
            report = json.loads((output / "bootstrap-report.json").read_text())
            self.assertEqual(report["corresponding_sources"]["recipes"], ["dependency", "root"])
            self.assertEqual({item["package"] for item in report["corresponding_sources"]["downloads"]},
                             {"dependency", "root"})
            with tarfile.open(output / "bootstrap-corresponding-sources.tar.xz") as archive:
                self.assertIn("termux-packages/packages/dependency/build.sh", archive.getnames())
                self.assertIn("source-downloads/dependency/source.tar", archive.getnames())

if __name__ == "__main__":
    unittest.main()
