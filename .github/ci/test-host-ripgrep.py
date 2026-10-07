#!/usr/bin/env python3
"""Regressions for host APT source isolation and mandatory installer failures."""
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    "installer", Path(__file__).with_name("install-host-ripgrep.py")
)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class HostRipgrepTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        for name, owner, attribute, value in (
            ("which", installer.shutil, "which", None),
            ("release", installer.platform, "freedesktop_os_release",
             {"ID": "ubuntu", "VERSION_CODENAME": "noble"}),
            ("architecture", installer.subprocess, "check_output", "amd64\n"),
            ("key_file", installer.Path, "is_file", True),
        ):
            patch = mock.patch.object(owner, attribute, return_value=value)
            setattr(self, name, patch.start())
            self.addCleanup(patch.stop)
        patch = mock.patch.object(installer.subprocess, "run", side_effect=self.record)
        self.run_command = patch.start()
        self.addCleanup(patch.stop)

    def record(self, command, **kwargs):
        self.assertTrue(kwargs.get("check"), "each subprocess must fail the job on error")
        self.calls.append(command)
        if "apt-get" in command:
            options = dict(item.split("=", 1) for item in command if "=" in item)
            source = Path(options["Dir::Etc::sourcelist"])
            self.assertEqual(installer.SOURCES, source.read_text())
            self.assertTrue(Path(options["Dir::State::lists"]).is_dir())
        return subprocess.CompletedProcess(command, 0)

    def test_installs_from_fresh_signed_official_https_sources(self):
        installer.install()
        update, install, cleanup, version = self.calls
        self.assertEqual(["sudo", "timeout", "--kill-after=10s", "180s", "apt-get"], update[:5])
        self.assertEqual(["sudo", "timeout", "--kill-after=10s", "120s", "apt-get"], install[:5])
        self.assertEqual("update", update[-1])
        self.assertEqual(["install", "-y", "--no-install-recommends", "ripgrep"], install[-4:])
        self.assertEqual(["rg", "--version"], version)
        self.assertEqual(update[5:-1], install[5:-4], "update and install use the same sources")
        for option in (
            "Dir::Etc::sourceparts=-", "APT::Update::Error-Mode=any",
            "Acquire::http::Timeout=20", "Acquire::https::Timeout=20",
            "Acquire::Retries=2", "Acquire::Languages=none",
        ):
            self.assertIn(option, update)
        self.assertIn("URIs: https://archive.ubuntu.com/ubuntu\n", installer.SOURCES)
        self.assertIn("URIs: https://security.ubuntu.com/ubuntu\n", installer.SOURCES)
        self.assertEqual(
            2, installer.SOURCES.count("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
        )
        self.assertNotIn("mirror", installer.SOURCES)
        self.assertNotIn("trusted", installer.SOURCES)
        for command in (update, install):
            self.assertFalse(any("AllowUnauthenticated" in item or "AllowInsecure" in item
                                 for item in command))
        source = next(item.split("=", 1)[1] for item in update
                      if item.startswith("Dir::Etc::sourcelist="))
        self.assertEqual(Path("/tmp"), Path(source).parent.parent, "_apt can traverse parents")
        self.assertEqual(
            ["sudo", "rm", "-rf", "--", str(Path(source).parent / "lists")], cleanup,
            "privileged cleanup is limited to freshly created APT lists",
        )
        self.assertFalse(Path(source).exists(), "temporary source/lists are cleaned up")

    def test_existing_ripgrep_still_has_to_execute(self):
        self.which.return_value = "/usr/bin/rg"
        installer.install()
        self.assertEqual([["rg", "--version"]], self.calls)
        self.architecture.assert_not_called()

    def test_update_error_stops_before_install_and_verification(self):
        for exit_code in (100, 124):
            with self.subTest(exit_code=exit_code):
                self.calls.clear()
                self.run_command.reset_mock()
                def fail_update(command, **kwargs):
                    if command[-1] == "update":
                        self.calls.append(command)
                        raise subprocess.CalledProcessError(exit_code, command)
                    return self.record(command, **kwargs)
                self.run_command.side_effect = fail_update
                with self.assertRaises(subprocess.CalledProcessError) as error:
                    installer.install()
                self.assertEqual(exit_code, error.exception.returncode)
                self.assertEqual(2, self.run_command.call_count)
                self.assertEqual("update", self.calls[0][-1])
                self.assertEqual(["sudo", "rm"], self.calls[1][:2])

    def test_install_error_stops_before_verification(self):
        def fail_install(command, **kwargs):
            if command[-1] == "ripgrep":
                raise subprocess.CalledProcessError(100, command)
            return self.record(command, **kwargs)
        self.run_command.side_effect = fail_install
        with self.assertRaises(subprocess.CalledProcessError):
            installer.install()
        self.assertEqual(3, self.run_command.call_count)
        self.assertEqual(["sudo", "rm"], self.calls[-1][:2])

    def test_cleanup_error_is_fatal_before_verification(self):
        def fail_cleanup(command, **kwargs):
            if command[:2] == ["sudo", "rm"]:
                raise subprocess.CalledProcessError(1, command)
            return self.record(command, **kwargs)
        self.run_command.side_effect = fail_cleanup
        with self.assertRaises(subprocess.CalledProcessError):
            installer.install()
        self.assertEqual(3, self.run_command.call_count)

    def test_installed_but_broken_ripgrep_is_an_error(self):
        self.which.return_value = "/usr/bin/rg"
        self.run_command.side_effect = subprocess.CalledProcessError(127, "rg --version")
        with self.assertRaises(subprocess.CalledProcessError):
            installer.install()

    def test_missing_archive_key_is_an_error(self):
        self.key_file.return_value = False
        with self.assertRaisesRegex(RuntimeError, "signing keyring"):
            installer.install()
        self.run_command.assert_not_called()

    def test_foreign_distribution_or_architecture_is_rejected(self):
        for release, architecture in (
            ({"ID": "debian", "VERSION_CODENAME": "trixie"}, "amd64"),
            ({"ID": "ubuntu", "VERSION_CODENAME": "jammy"}, "amd64"),
            ({"ID": "ubuntu", "VERSION_CODENAME": "noble"}, "arm64"),
        ):
            with self.subTest(release=release, architecture=architecture):
                self.release.return_value = release
                self.architecture.return_value = architecture
                with self.assertRaisesRegex(RuntimeError, "Ubuntu 24.04 amd64"):
                    installer.install()
        self.run_command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
