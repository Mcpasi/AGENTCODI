#!/usr/bin/env python3
"""Prefix rejection tests and integration checks against the pinned recipe tree."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts/package-edition"


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


prepare = module("prepare", SCRIPTS / "prepare.py")
verify = module("verify", SCRIPTS / "verify-prefix.py")


class PrefixTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.prefix = self.root / verify.PREFIX.lstrip("/")
        (self.prefix / "bin").mkdir(parents=True)

    def test_lock_matches_installation_identity(self):
        import xml.etree.ElementTree as ET
        lock = prepare.read_lock()
        manifest = ET.parse(REPO / "app/src/main/AndroidManifest.xml").getroot()
        self.assertEqual(manifest.attrib["package"], lock["target"]["application_id"])
        self.assertEqual(29, lock["target"]["api_level"])

    def test_exact_overlay_rejects_drift(self):
        for text in ("missing", "old old"):
            with self.assertRaises(ValueError):
                prepare.replace_exact(text, "old", "new")

    def test_correct_script_and_internal_link_are_accepted(self):
        (self.prefix / "bin/hello").write_text("#!" + verify.PREFIX + "/bin/sh\nexit 0\n")
        (self.prefix / "bin/sh").symlink_to("hello")
        report = verify.audit(self.root)
        self.assertIn(verify.PREFIX.lstrip("/") + "/bin/hello", report["files"])

    def test_native_tls_is_rejected(self):
        report = "Class: ELF64\nMachine: AArch64\n0000 R_AARCH64_TLSDESC\n"
        with mock.patch.object(verify.subprocess, "check_output", return_value=report):
            with self.assertRaisesRegex(ValueError, "Native ELF TLS"):
                verify.check_elf(self.prefix / "bin/fixture", "readelf")

    def test_elf_without_native_tls_is_accepted(self):
        report = "Class: ELF64\nMachine: AArch64\n0000 R_AARCH64_RELATIVE\n"
        with mock.patch.object(verify.subprocess, "check_output", return_value=report):
            self.assertEqual(report, verify.check_elf(self.prefix / "bin/fixture", "readelf"))

    def test_foreign_shebang_is_rejected(self):
        (self.prefix / "bin/hello").write_text("#!/usr/bin/python3\n")
        with self.assertRaisesRegex(ValueError, "Foreign shebang"):
            verify.audit(self.root)

    def test_shebang_path_traversal_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Noncanonical shebang"):
            verify.check_script(("#!" + verify.PREFIX + "/bin/../../foreign\n").encode(), "escape")

    def test_long_shebang_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Shebang exceeds"):
            verify.check_script(b"#!" + verify.PREFIX.encode() + b"/bin/" + b"x" * 100, "long")

    def test_foreign_binary_strings_are_rejected(self):
        (self.prefix / "bin/hello").write_bytes(b"fixture\0/data/data/com.termux/files/usr/lib\0")
        with self.assertRaisesRegex(ValueError, "Foreign Termux"):
            verify.audit(self.root)

    def test_foreign_repository_is_rejected(self):
        (self.prefix / "sources.list").write_text("deb https://packages.termux.dev/apt stable main\n")
        with self.assertRaisesRegex(ValueError, "Foreign Termux"):
            verify.audit(self.root)

    def test_payload_outside_prefix_is_rejected(self):
        (self.root / "foreign").write_text("outside")
        with self.assertRaisesRegex(ValueError, "outside managed prefix"):
            verify.audit(self.root)

    def test_escaping_symlinks_are_rejected(self):
        for target in ("/data/data/com.termux/files/usr/bin/sh", "../../../../../../etc/sh"):
            link = self.prefix / "bin/link"
            link.symlink_to(target)
            with self.assertRaisesRegex(ValueError, "Symlink leaves"):
                verify.audit(self.root)
            link.unlink()

    def test_runpath_accepts_final_prefix_and_local_origin(self):
        for path in (verify.PREFIX + "/lib", "$ORIGIN", "$ORIGIN/lib"):
            verify.check_runpath(path)

    def test_runpath_rejects_empty_foreign_and_parent_entries(self):
        for path in ("", "/usr/lib", "$ORIGIN/../lib", "$ORIGIN:", ":" + verify.PREFIX + "/lib"):
            with self.assertRaisesRegex(ValueError, "ELF search path"):
                verify.check_runpath(path)

    def test_metadata_requires_target_architecture_and_prefix(self):
        control = self.root / "metadata"
        control.mkdir()
        (control / "control").write_text("Package: fixture\nArchitecture: aarch64\n")
        (control / "conffiles").write_text(verify.PREFIX + "/etc/test.conf\n")
        verify.check_control(control)
        (control / "control").write_text("Architecture: amd64\n")
        with self.assertRaisesRegex(ValueError, "architecture"):
            verify.check_control(control)
        (control / "control").write_text("Architecture: all\n")
        (control / "conffiles").write_text("/etc/test.conf\n")
        with self.assertRaisesRegex(ValueError, "Conffile"):
            verify.check_control(control)


def bash(script, cwd, env=None, check=True):
    return subprocess.run(["bash", "-e", "-c", script], cwd=cwd,
                          env={**os.environ, **(env or {})},
                          check=check, text=True, capture_output=True)


def check_prepared(source, prepared):
    lock = prepare.read_lock()
    with tempfile.TemporaryDirectory() as temporary:
        temp = Path(temporary)
        second = temp / "second"
        report = prepare.prepare(source, second)
        assert report == json.loads((prepared / "agentcodi-preparation.json").read_text())
        assert not prepare.git(source, "status", "--porcelain", "--untracked-files=all", "--ignored")
        for candidate in (prepared, source / "output"):
            try:
                prepare.prepare(source, candidate)
            except ValueError:
                pass
            else:
                raise AssertionError("Unsafe output accepted")
        # Test the real upstream property derivations, including legacy aliases.
        names = ("TERMUX_APP_PACKAGE", "TERMUX_PREFIX", "TERMUX_PREFIX_CLASSICAL",
                 "TERMUX_ANDROID_HOME", "TERMUX__ROOTFS", "TERMUX__PREFIX__LIB_DIR",
                 "TERMUX_BOOTSTRAP__BOOTSTRAP_SECOND_STAGE_DIR", "TERMUX_PKG_API_LEVEL",
                 "TERMUX_NDK_VERSION", "TERMUX_SDK_REVISION", "TERMUX_ANDROID_BUILD_TOOLS_VERSION")
        script = '. ./agentcodi.env\n. ./scripts/properties.sh\n'
        script += 'printf "%s\\n" ' + " ".join('"$' + name + '"' for name in names)
        values = dict(zip(names, bash(script, prepared).stdout.splitlines()))
        assert values["TERMUX_APP_PACKAGE"] == lock["target"]["application_id"]
        for name in ("TERMUX_PREFIX", "TERMUX_PREFIX_CLASSICAL"):
            assert values[name] == lock["target"]["prefix"]
        assert values["TERMUX_ANDROID_HOME"] == lock["target"]["home"]
        assert values["TERMUX__ROOTFS"] == lock["target"]["rootfs"]
        assert values["TERMUX__PREFIX__LIB_DIR"] == lock["target"]["prefix"] + "/lib"
        assert values["TERMUX_BOOTSTRAP__BOOTSTRAP_SECOND_STAGE_DIR"].startswith(lock["target"]["prefix"] + "/etc/")
        assert values["TERMUX_PKG_API_LEVEL"] == "29"
        assert values["TERMUX_NDK_VERSION"] == lock["toolchain"]["ndk"]["version"]
        assert values["TERMUX_SDK_REVISION"] == lock["toolchain"]["sdk"]["revision"]
        assert values["TERMUX_ANDROID_BUILD_TOOLS_VERSION"] == lock["toolchain"]["sdk"]["build_tools"]
        assert json.loads((prepared / "repo.json").read_text())["packages"]["url"] == lock["repository"]["url"]
        sdk_setup = (prepared / "scripts/setup-android-sdk.sh").read_text()
        for name in ("ndk", "sdk"):
            assert lock["toolchain"][name]["sha256"] in sdk_setup

        # Exercise upstream's actual shebang massage block in isolation, retaining
        # its local variables and loops; the remaining massage stages build packages.
        massage = (prepared / "scripts/build/termux_step_massage.sh").read_text()
        start = massage.index('\tif [ "$TERMUX_PKG_NO_SHEBANG_FIX" != "true" ]; then')
        end = massage.index("\n\t# Delete the info directory file.", start)
        scripts = temp / "scripts"
        scripts.mkdir()
        for name, shebang in (("env", "#!/usr/bin/env python3"),
                              ("system", "#!/system/bin/sh"),
                              ("legacy", "#!/data/data/com.termux/files/usr/bin/bash")):
            (scripts / name).write_text(shebang + "\nexit 0\n")
        env = {"FIXTURE": str(scripts), "TERMUX_PKG_NO_SHEBANG_FIX": "false",
               "TERMUX_PKG_NO_SHEBANG_FIX_FILES": ""}
        bash('. ./scripts/properties.sh\nmassage_fixture() {\n' + massage[start:end] +
             '\n}\ncd "$FIXTURE"\nmassage_fixture', prepared, env)
        assert (scripts / "env").read_text().splitlines()[0] == "#!" + verify.PREFIX + "/bin/env python3"
        assert (scripts / "system").read_text().splitlines()[0] == "#!/system/bin/sh"
        assert (scripts / "legacy").read_text().splitlines()[0] == "#!" + verify.PREFIX + "/bin/bash"

        # APT emits only the edition source, with the final-prefix scoped key path.
        apt_prefix = temp / "apt"
        (apt_prefix / "lib/apt/methods").mkdir(parents=True)
        for name in ("http", "https"):
            (apt_prefix / "lib/apt/methods" / name).touch()
        bash('. ./packages/apt/build.sh\ntermux_step_post_make_install', prepared,
             {"TERMUX_PREFIX": str(apt_prefix), "TERMUX_CACHE_DIR": str(temp),
              "TERMUX_ARCH": "aarch64"})
        line = (apt_prefix / "etc/apt/sources.list").read_text()
        assert line == "deb [arch=aarch64 signed-by=" + lock["repository"]["signed_by"] + "] " + lock["repository"]["url"] + " stable main\n"
        assert "termux-keyring" not in (prepared / "packages/apt/build.sh").read_text()

        # Render the upstream bootstrap path templates without building or
        # executing its Termux-specific second stage.
        bootstrap = (prepared / "scripts/build-bootstraps.sh").read_text()
        start = bootstrap.index("add_termux_bootstrap_second_stage_files() {")
        end = bootstrap.index("\n# Final stage:", start)
        bootstrap_root = temp / "bootstrap"
        (bootstrap_root / verify.PREFIX.lstrip("/") / "etc/profile.d").mkdir(parents=True)
        bash('. ./agentcodi.env\n. ./scripts/properties.sh\nTERMUX_SCRIPTDIR="$PWD"\n' +
             bootstrap[start:end] + '\nadd_termux_bootstrap_second_stage_files aarch64',
             prepared, {"BOOTSTRAP_ROOTFS": str(bootstrap_root), "TERMUX_PACKAGE_MANAGER": "apt"})
        for path in bootstrap_root.rglob("*.sh"):
            data = path.read_bytes()
            verify.check_script(data, path.name)
            assert verify.PREFIX.encode() in data and b"/data/data/com.termux/" not in data
        assert len(list(bootstrap_root.rglob("*.sh"))) == 2

        # Real upstream patch substitution must use the same prefix and home.
        patch_dir, source_dir = temp / "patches", temp / "src"
        patch_dir.mkdir()
        source_dir.mkdir()
        (source_dir / "paths.txt").write_text("old\n")
        (patch_dir / "paths.patch").write_text(
            "--- a/paths.txt\n+++ b/paths.txt\n@@ -1 +1 @@\n-old\n+@TERMUX_PREFIX@ @TERMUX_HOME@\n")
        bash('. ./scripts/properties.sh\n. ./scripts/build/termux_step_patch_package.sh\n'
             'termux_step_patch_package', prepared,
             {"TERMUX_PKG_SRCDIR": str(source_dir), "TERMUX_PKG_BUILDER_DIR": str(patch_dir),
              "TERMUX_PKG_METAPACKAGE": "false", "TERMUX_ARCH_BITS": "64",
              "TERMUX_DEBUG_BUILD": "false", "TERMUX_ON_DEVICE_BUILD": "false"})
        assert (source_dir / "paths.txt").read_text() == verify.PREFIX + " " + lock["target"]["home"] + "\n"
        # Binary-repository seeding and alternate ABIs fail before package build.
        for option in ("-i", "-I", "-a x86_64", "--library glibc"):
            result = bash("./build-package.sh " + option + " dash", prepared, check=False)
            assert result.returncode != 0 and "AGENTCODI requires source-built" in result.stdout + result.stderr, result.stdout + result.stderr
        result = bash("./agentcodi-build-package.sh -i", prepared, check=False)
        assert result.returncode == 64
        print("Pinned source preparation, repeatability, properties, APT, patch substitutions, shebangs and source-only guards passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path)
    parser.add_argument("--prepared", type=Path)
    args = parser.parse_args()
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PrefixTest))
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.source and args.prepared:
        check_prepared(args.source, args.prepared)
