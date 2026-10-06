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

roots_spec = importlib.util.spec_from_file_location("roots", HERE / "scripts/package-edition/catalog-build-roots.py")
roots = importlib.util.module_from_spec(roots_spec)
roots_spec.loader.exec_module(roots)

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

    def test_reselection_requires_matching_built_deb_and_retained_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "source", root / "output"
            source.mkdir()
            output.mkdir()
            archive_name = "bootstrap-corresponding-sources.tar.xz"
            package = root / "packages" / "python"
            package.mkdir(parents=True)
            (package / "build.sh").write_text("# Python source recipe\n")
            (package / "python-ensurepip-wheels.subpackage.sh").write_text("# wheels subpackage\n")
            with tarfile.open(source / archive_name, "w:xz") as archive:
                archive.add(package, arcname="termux-packages/packages/python")
            previous = {"lock": {"commit": "pinned"}, "catalog": {"group": "python"},
                        "packages": {"python": {"deb_sha256": "python-hash"}},
                        "corresponding_sources": {"archive": archive_name, "recipes": ["python"]}}
            current = {**previous, "packages": {**previous["packages"],
                       "python-ensurepip-wheels": {"deb_sha256": "wheels-hash"}}}
            (source / "bootstrap-report.json").write_text(json.dumps(previous))
            (source / "all-built-packages.json").write_text(json.dumps(current["packages"]))
            (source / "agentcodi-preparation.json").write_text("{}")
            (output / "bootstrap-report.json").write_text(json.dumps(current))
            sources.reuse(source, output)
            with tarfile.open(output / archive_name) as archive:
                self.assertIn("termux-packages/packages/python/python-ensurepip-wheels.subpackage.sh",
                              archive.getnames())
            for bad_packages, message in (
                ({**current["packages"], "python-ensurepip-wheels": {"deb_sha256": "changed"}}, "checksum"),
                ({**current["packages"], "unknown": {"deb_sha256": "unknown"}}, "did not build"),
            ):
                (output / "bootstrap-report.json").write_text(json.dumps({**current, "packages": bad_packages}))
                with self.assertRaisesRegex(ValueError, message):
                    sources.reuse(source, output)
            (package / "python-ensurepip-wheels.subpackage.sh").unlink()
            with tarfile.open(source / archive_name, "w:xz") as archive:
                archive.add(package, arcname="termux-packages/packages/python")
            (output / "bootstrap-report.json").write_text(json.dumps(current))
            with self.assertRaisesRegex(ValueError, "retained source recipe"):
                sources.reuse(source, output)

    def test_selected_subpackage_builds_its_parent_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("python", "npm"):
                package = root / "packages" / name
                package.mkdir(parents=True)
                (package / "build.sh").write_text("# source recipe\n")
            (root / "packages/python/python-ensurepip-wheels.subpackage.sh").write_text("# wheels\n")
            selected = ["python", "python-ensurepip-wheels", "python-static", "npm"]
            self.assertEqual(["python", "npm"], roots.resolve(root, selected))
            self.assertEqual(["python", "python-ensurepip-wheels", "python-static", "npm"], selected)

    def test_unknown_and_ambiguous_packages_cannot_fall_back_to_binary_repositories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("one", "two"):
                package = root / "packages" / name
                package.mkdir(parents=True)
                (package / "build.sh").write_text("# source recipe\n")
                (package / "shared.subpackage.sh").write_text("# conflicting parent\n")
            for selected, message in ((["unknown"], "No source recipe"),
                                      (["shared"], "Ambiguous source recipe"),
                                      (["../one"], "Invalid selected package"),
                                      ([], "Empty catalog")):
                with self.assertRaisesRegex(ValueError, message):
                    roots.resolve(root, selected)

if __name__ == "__main__":
    unittest.main()
