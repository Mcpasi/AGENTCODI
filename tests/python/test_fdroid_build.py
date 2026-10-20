import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/fdroid" / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


builder = module("build-codex")
zipper = module("reproducible-zip")
comparison = module("compare-apks")


class ReproducibleBuildTest(unittest.TestCase):
    def test_zip_ignores_input_order_timestamps_permissions_and_extra_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index in range(2):
                with zipfile.ZipFile(root / f"input{index}.apk", "w") as archive:
                    names = ["resources.arsc", "AndroidManifest.xml"]
                    for name in names[::1 if index == 0 else -1]:
                        entry = zipfile.ZipInfo(name, (2020 + index, 1, 1, 0, 0, 0))
                        entry.external_attr = (0o100600 if index else 0o100755) << 16
                        entry.comment = b"host metadata"
                        entry.extra = b"\x01\xca\x00\x00"
                        archive.writestr(entry, name.encode())
                stage = root / f"stage{index}"
                stage.mkdir()
                (stage / "classes.dex").write_bytes(b"same dex bytes")
                os.utime(stage / "classes.dex", (100 + index, 200 + index))
                zipper.canonical_zip(root / f"input{index}.apk", root / f"output{index}.apk", stage, 1700000000)
            self.assertEqual((root / "output0.apk").read_bytes(), (root / "output1.apk").read_bytes())

    def test_zip_rejects_traversal_duplicates_signatures_and_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("../classes.dex", "/classes.dex", "META-INF/CERT.RSA", "a\\b"):
                with zipfile.ZipFile(root / "input.apk", "w") as archive:
                    archive.writestr(name, b"invalid")
                with self.assertRaises(ValueError):
                    zipper.canonical_zip(root / "input.apk", root / "output.apk", None, 1700000000)
            with zipfile.ZipFile(root / "input.apk", "w") as archive:
                archive.writestr("classes.dex", b"dex")
            stage = root / "stage"
            stage.mkdir()
            (stage / "classes.dex").write_bytes(b"replacement")
            with self.assertRaises(ValueError):
                zipper.canonical_zip(root / "input.apk", root / "output.apk", stage, 1700000000)
            (stage / "classes.dex").unlink()
            (stage / "link").symlink_to(root / "input.apk")
            with self.assertRaises(ValueError):
                zipper.canonical_zip(root / "input.apk", root / "output.apk", stage, 1700000000)

    def test_package_has_identical_bytes_across_paths_and_input_order(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = {name: name.encode() for name in builder.PAYLOAD}
            first, second = root / "one.tgz", root / "different-name.tgz"
            builder.deterministic_package(first, files, 1700000000)
            builder.deterministic_package(second, dict(reversed(list(files.items()))), 1700000000)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first.read_bytes()[4:8], bytes(4))

    def test_comparison_fails_when_only_zip_metadata_differs(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = [Path(temporary) / f"{index}.apk" for index in range(2)]
            for index, path in enumerate(paths):
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(zipfile.ZipInfo("classes.dex", (2020 + index, 1, 1, 0, 0, 0)), b"dex")
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(comparison.compare(*paths), 1)
                self.assertEqual(comparison.compare(paths[0], paths[0]), 0)


class SourceIntegrityTest(unittest.TestCase):
    def test_changed_build_tools_are_rejected_before_an_offline_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache = Path(temporary)
            tools = cache / "v8-tools"
            tools.mkdir()
            binary = tools / "gn"
            binary.write_bytes(b"locked tool")
            lock = {"v8": {"tools": {"gn": {"binary_sha256": builder.sha256(binary)}}}}
            builder.verify_v8_tools(cache, lock)
            binary.write_bytes(b"different tool")
            with self.assertRaises(ValueError):
                builder.verify_v8_tools(cache, lock)

    def test_submodule_source_changes_are_part_of_the_prepared_source_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dep, source = root / "dependency", root / "v8"
            def git(path, *args):
                return subprocess.run(["git", "-c", "user.name=Build test",
                    "-c", "user.email=build@example.invalid", *args], cwd=path,
                    check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            for path in (dep, source):
                path.mkdir()
                git(path, "init", "--quiet")
                (path / "code.cc").write_text("original")
                git(path, "add", ".")
                git(path, "commit", "--quiet", "-m", "fixture")
            git(source, "-c", "protocol.file.allow=always", "submodule", "add", "--quiet", str(dep), "build")
            git(source, "commit", "--quiet", "-am", "pin submodule")
            original = builder.v8_state(source)
            (source / "build/code.cc").write_text("first change")
            first = builder.v8_state(source)
            (source / "build/code.cc").write_text("second change")
            second = builder.v8_state(source)
            self.assertNotEqual(original, first)
            self.assertNotEqual(first, second)

    def test_recipe_extraction_rejects_ambiguous_or_unresolved_steps(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            recipe = "      - name: Build\n        shell: bash\n        run: |\n          set -e\n          echo source\n\n      - name: Publish\n        run: |\n          false\n"
            (root / "recipe.yml").write_text(recipe)
            script = builder.recipe_step(root, "recipe.yml", "Build")
            self.assertIn("echo source", script)
            self.assertNotIn("false", script)
            subprocess.run(["bash", "-n"], input=script, text=True, check=True)
            (root / "recipe.yml").write_text(recipe + recipe)
            with self.assertRaises(ValueError):
                builder.recipe_step(root, "recipe.yml", "Build")
            (root / "recipe.yml").write_text(recipe.replace("echo source", "echo ${{ inputs.code }}"))
            with self.assertRaises(ValueError):
                builder.recipe_step(root, "recipe.yml", "Build")

    def test_source_checkout_requires_exact_commit_and_clean_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            (root / "source.rs").write_text("original")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "-c", "user.name=Build test", "-c", "user.email=build@example.invalid",
                            "commit", "--quiet", "-m", "fixture"], cwd=root, check=True)
            commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            builder.verify_checkout(root, commit)
            with self.assertRaises(ValueError):
                builder.verify_checkout(root, "0" * 40)
            (root / "source.rs").write_text("local changes")
            with self.assertRaises(ValueError):
                builder.verify_checkout(root, commit)

    def test_source_manifest_rejects_changed_archives_and_different_source_locks(self):
        lock = json.loads(builder.LOCK_PATH.read_text())
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            files = {name: name.encode() for name in builder.PAYLOAD}
            builder.deterministic_package(output / "package.tgz", files, lock["source_date_epoch"])
            record = {"schema": 1, "commit": lock["commit"], "v8_commit": lock["v8"]["commit"],
                      "v8_from_source": True, "source_lock_sha256": builder.sha256(builder.LOCK_PATH),
                      "builder_sha256": builder.sha256(builder.__file__),
                      "archive_sha256": builder.sha256(output / "package.tgz"),
                      "payload_sha256": {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
            manifest = output / "source-build.json"
            manifest.write_text(json.dumps(record))
            builder.verify_output(output, lock)
            with (output / "package.tgz").open("ab") as archive:
                archive.write(b"tampering")
            with self.assertRaises(ValueError):
                builder.verify_output(output, lock)
            record["source_lock_sha256"] = "0" * 64
            manifest.write_text(json.dumps(record))
            with self.assertRaises(ValueError):
                builder.verify_output(output, lock)


if __name__ == "__main__":
    unittest.main()
