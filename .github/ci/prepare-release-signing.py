#!/usr/bin/env python3
"""Validate GitHub signing secrets and stage private files outside the checkout."""
import argparse
import base64
import binascii
import os
from pathlib import Path
import re
import stat
import sys

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = (
    "AGENTCODI_RELEASE_KEYSTORE_BASE64",
    "AGENTCODI_RELEASE_STORE_PASSWORD",
    "AGENTCODI_RELEASE_KEY_PASSWORD",
    "AGENTCODI_RELEASE_KEY_ALIAS",
    "AGENTCODI_RELEASE_CERT_SHA256",
)


def validate(environment):
    missing = [name for name in REQUIRED if not environment.get(name)]
    if missing:
        raise ValueError("Missing repository secrets: " + ", ".join(missing))
    try:
        keystore = base64.b64decode(
            "".join(environment[REQUIRED[0]].split()), validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("AGENTCODI_RELEASE_KEYSTORE_BASE64 must be valid Base64") from None
    if not keystore:
        raise ValueError("AGENTCODI_RELEASE_KEYSTORE_BASE64 must decode to a non-empty keystore")
    for name in REQUIRED[1:3]:
        if any(character in environment[name] for character in ("\r", "\n", "\0")):
            raise ValueError(name + " must be a single line without NUL characters")
    alias = environment["AGENTCODI_RELEASE_KEY_ALIAS"]
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", alias):
        raise ValueError("AGENTCODI_RELEASE_KEY_ALIAS must contain 1-128 safe alias characters")
    fingerprint = "".join(environment["AGENTCODI_RELEASE_CERT_SHA256"].split()).replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("AGENTCODI_RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint")
    # Read the production pin; keep the release rejection in sync with the debug signer.
    source = (ROOT / "scripts/sign-debug-apk.py").read_text()
    debug_fingerprint = re.search(r'^CERTIFICATE_SHA256 = "([0-9a-f]{64})"$', source, re.M)
    if debug_fingerprint is None:
        raise ValueError("Cannot read the pinned public development certificate")
    if fingerprint == debug_fingerprint.group(1):
        raise ValueError("Release signing must not use the public development certificate")
    return keystore, alias, fingerprint


def prepare(directory, environment):
    keystore, alias, fingerprint = validate(environment)
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Signing directory must be a real directory")
    directory = directory.resolve()
    if directory == ROOT or ROOT in directory.parents:
        raise ValueError("Signing files must remain outside the checkout")
    if stat.S_IMODE(directory.stat().st_mode) & 0o077:
        raise ValueError("Signing directory must not be accessible by group or other users")
    payloads = {
        "release.keystore": keystore,
        "store-password": environment["AGENTCODI_RELEASE_STORE_PASSWORD"].encode("utf-8") + b"\n",
        "key-password": environment["AGENTCODI_RELEASE_KEY_PASSWORD"].encode("utf-8") + b"\n",
        "key-alias": alias.encode("ascii") + b"\n",
        "certificate-sha256": fingerprint.encode("ascii") + b"\n",
    }
    for name, content in payloads.items():
        # Exclusive creation prevents overwriting or following an existing symlink.
        descriptor = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--directory", type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.check:
            validate(os.environ)
        else:
            prepare(arguments.directory, os.environ)
    except ValueError as error:
        # Validation messages contain only configuration names, never secret values.
        print("Release signing setup failed: " + str(error), file=sys.stderr)
        return 1
    except OSError:
        print("Release signing setup failed: cannot read or create signing files", file=sys.stderr)
        return 1
    print("Release signing configuration validated.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
