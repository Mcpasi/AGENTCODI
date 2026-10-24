import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("runtime_sources", ROOT / "scripts/fdroid/build-runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeSourcesTest(unittest.TestCase):
    def test_python_post_massage_compiles_bytecode_into_the_transported_deb(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base, project = root / "base", root / "project"
            (base / "packages/python").mkdir(parents=True)
            (base / "packages/python/build.sh").write_text(":\n")
            installed = root / "installed"
            stdlib = installed / "lib/python3.14"
            (stdlib / "lib-dynload").mkdir(parents=True)
            for name in ("encodings/__init__.py", "importlib/__init__.py", "os.py"):
                file = stdlib / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("value = 42\n")
            for name in ("_bz2", "_ctypes", "_curses", "_curses_panel", "_lzma", "_multiprocessing",
                         "_sqlite3", "_ssl", "_zstd", "zlib"):
                (stdlib / "lib-dynload" / (name + ".fixture.so")).touch()
            massage = root / "massage"
            package_prefix = massage / str(runtime.PREFIX).lstrip("/")
            shutil.copytree(installed, package_prefix)
            control = massage / "DEBIAN/control"
            control.parent.mkdir()
            control.parent.chmod(0o755)
            control.write_text("Package: python\nVersion: 3.14.6-1\nArchitecture: aarch64\n"
                               "Maintainer: Test <test@example.invalid>\nDescription: Python packaging fixture\n")
            control.chmod(0o644)
            archive = root / "python.deb"
            def package():
                subprocess.run(["dpkg-deb", "--build", "--root-owner-group", str(massage), str(archive)],
                               check=True, stdout=subprocess.DEVNULL)
            package()
            with self.assertRaisesRegex(ValueError, "missing packaged bytecode"):
                runtime.verify_python_bytecode(archive)
            # Run the real recipe hook and bytecode compiler with the host test
            # interpreter. Python 3.14.6's CLI version check is tested separately.
            compiler = project / "scripts/fdroid/compile-python-stdlib.py"
            compiler.parent.mkdir(parents=True)
            compiler.write_text("import importlib.util, sys\n"
                "spec = importlib.util.spec_from_file_location('stdlib', "
                + repr(str(ROOT / "scripts/fdroid/compile-python-stdlib.py")) + ")\n"
                "module = importlib.util.module_from_spec(spec)\nspec.loader.exec_module(module)\n"
                "module.compile_stdlib(sys.argv[1])\n")
            host_python = root / "host-python/host-build-prefix/bin/python3.14"
            host_python.parent.mkdir(parents=True)
            host_python.symlink_to(sys.executable)
            env = dict(os.environ, AGENTCODI_BASE_RECIPES=str(base), AGENTCODI_PROJECT_ROOT=str(project),
                       TERMUX_PREFIX=str(installed), TERMUX_PREFIX_CLASSICAL=str(runtime.PREFIX),
                       TERMUX_PKG_MASSAGEDIR=str(massage), TERMUX_BUILD_PYTHON_DIR=str(root / "host-python"),
                       PYTHONDONTWRITEBYTECODE="1")
            subprocess.run(["bash", "-Eeuo", "pipefail", "-c",
                'source "$1"; termux_step_post_massage', "recipe",
                str(ROOT / "scripts/fdroid/recipes/python.sh")], cwd=package_prefix, env=env, check=True)
            self.assertFalse((stdlib / "encodings/__init__.pyc").exists())
            self.assertTrue((package_prefix / "lib/python3.14/encodings/__init__.pyc").is_file())
            package()
            runtime.verify_python_bytecode(archive)

    def test_assembly_toolchain_is_idempotent_and_never_downloads_during_build(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache, work = root / "cache", root / "work"
            (cache / "blobs").mkdir(parents=True)
            archive = root / "ndk.zip"
            with zipfile.ZipFile(archive, "w") as stream:
                stream.writestr("android-ndk-r29/source.properties", "Pkg.Revision = 29\n")
            digest = runtime.sha256(archive)
            archive.rename(cache / "blobs" / digest)
            lock = {"host_inputs": [{"extract": "ndk", "sha256": digest,
                                     "url": "https://example.invalid/ndk.zip"}]}
            with patch.object(runtime, "download", side_effect=AssertionError("network during build")):
                runtime.prepare_toolchain(cache, work, lock, fetch=False)
                runtime.prepare_toolchain(cache, work, lock, fetch=False)
                self.assertEqual((work / "tools/ndk.sha256").read_text(), digest + "\n")
                (cache / "blobs" / digest).write_bytes(b"changed archive")
                with self.assertRaisesRegex(ValueError, "changed prepared assembly NDK"):
                    runtime.prepare_toolchain(cache, work, lock, fetch=False)

    def test_assembly_toolchain_rejects_incomplete_extractions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache, work = root / "cache", root / "work"
            (cache / "blobs").mkdir(parents=True)
            blob = cache / "blobs/input"
            blob.write_bytes(b"archive")
            digest = runtime.sha256(blob)
            blob.rename(cache / "blobs" / digest)
            (work / "tools").mkdir(parents=True)
            lock = {"host_inputs": [{"extract": "ndk", "sha256": digest}]}
            with self.assertRaisesRegex(ValueError, "Incomplete assembly NDK"):
                runtime.prepare_toolchain(cache, work, lock, fetch=False)

    def fixture(self, output):
        record = runtime.identity()
        paths = {}
        for name, package in runtime.ARCHIVES.items():
            path = "debs/" + package + "_1_aarch64.deb"
            file = output / path
            file.parent.mkdir(exist_ok=True)
            file.write_bytes(package.encode())
            paths[name] = path
        record.update(from_source=True, archives=paths,
                      files_sha256={path: runtime.sha256(output / path) for path in paths.values()})
        runtime.write_json(output / "source-build.json", record)
        return record

    def test_complete_source_receipt_rejects_missing_changed_and_linked_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            record = self.fixture(output)
            runtime.verify_output(output)
            file = output / record["archives"]["NODE"]
            original = file.read_bytes()
            file.write_bytes(b"different node")
            with self.assertRaisesRegex(ValueError, "package changed"):
                runtime.verify_output(output)
            file.write_bytes(original)
            other = file.with_suffix(".original")
            file.rename(other)
            file.symlink_to(other)
            with self.assertRaises(ValueError):
                runtime.verify_output(output)
            file.unlink()
            with self.assertRaises(ValueError):
                runtime.verify_output(output)

    def test_receipt_rejects_incomplete_recipe_changes_and_archive_path_escape(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            record = self.fixture(output)
            with patch.object(runtime, "recipe_hashes", return_value={"changed-recipe.sh": "0" * 64}):
                with self.assertRaisesRegex(ValueError, "different recipes"):
                    runtime.verify_output(output)
            original = json.loads(json.dumps(record))
            for change in ("incomplete", "escape", "not-source"):
                record = json.loads(json.dumps(original))
                if change == "incomplete":
                    del record["archives"]["PYTHON"]
                elif change == "escape":
                    old = record["archives"]["NODE"]
                    record["archives"]["NODE"] = "../" + old
                    record["files_sha256"]["../" + old] = record["files_sha256"].pop(old)
                else:
                    record["from_source"] = False
                runtime.write_json(output / "source-build.json", record)
                with self.assertRaises(ValueError):
                    runtime.verify_output(output)

    def test_preparation_refuses_prebuilt_android_packages_and_unhashed_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            for url, digest in (
                ("https://packages.termux.dev/a/node.deb", "0" * 64),
                ("https://example.invalid/package.deb", "0" * 64),
                ("https://example.invalid/source.tar.gz", "SKIP_CHECKSUM"),
            ):
                with self.assertRaises(ValueError):
                    runtime.download(Path(directory), url, digest)

    def test_build_downloader_uses_verified_local_bytes_and_has_no_network_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            blobs = cache / "blobs"
            blobs.mkdir()
            source = cache / "source"
            source.write_bytes(b"source archive")
            digest = runtime.sha256(source)
            source.rename(blobs / digest)
            script = 'source "$1"; termux_download https://example.invalid/source.tar.gz "$2" "$3"'
            args = ["bash", "-eu", "-c", script, "downloader",
                    str(ROOT / "scripts/fdroid/recipes/download.sh"), str(cache / "output"), digest]
            env = dict(os.environ, AGENTCODI_RUNTIME_CACHE=str(cache))
            subprocess.run(args, check=True, env=env)
            self.assertEqual((cache / "output").read_bytes(), b"source archive")
            (blobs / digest).write_bytes(b"tampered source")
            result = subprocess.run(args, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"Missing or changed", result.stderr)


if __name__ == "__main__":
    unittest.main()
