#!/usr/bin/env python3
"""Sign development APKs with a pinned, public AOSP test identity."""
import argparse
import base64
import binascii
import hashlib
from pathlib import Path
import re
import ssl
import subprocess
import sys
import tempfile

CERTIFICATE_SHA256 = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
PRIVATE_KEY_SHA256 = "495675d32e89a149d5abe191f4e9c0e218b9068714e9b53a7c91e164a0741a23"
ASSETS = Path(__file__).resolve().parent / "debug-signing"


def load_material():
    certificate = (ASSETS / "testkey.x509.pem").read_bytes()
    certificate_der = ssl.PEM_cert_to_DER_cert(certificate.decode("ascii"))
    key = base64.b64decode(
        b"".join((ASSETS / "testkey.pk8.b64").read_bytes().split()), validate=True)
    if hashlib.sha256(certificate_der).hexdigest() != CERTIFICATE_SHA256:
        raise ValueError("Pinned debug signing certificate checksum mismatch")
    if hashlib.sha256(key).hexdigest() != PRIVATE_KEY_SHA256:
        raise ValueError("Pinned debug signing key checksum mismatch")
    return certificate, key


def sign(unsigned, output, min_sdk, apksigner):
    certificate, key = load_material()
    output.parent.mkdir(parents=True, exist_ok=True)
    # Build-local temporary files contain only the public development test key.
    # The signed APK is published only after its real certificate was checked.
    with tempfile.TemporaryDirectory(prefix=".debug-signing-", dir=output.parent) as work:
        work = Path(work)
        cert_file, key_file, apk = work / "testkey.x509.pem", work / "testkey.pk8", work / "signed.apk"
        cert_file.write_bytes(certificate)
        key_file.write_bytes(key)
        key_file.chmod(0o600)
        subprocess.run([
            apksigner, "sign", "--min-sdk-version", str(min_sdk),
            "--key", str(key_file), "--cert", str(cert_file),
            "--out", str(apk), str(unsigned)], check=True)
        verified = subprocess.run([
            apksigner, "verify", "--min-sdk-version", str(min_sdk), "--print-certs", str(apk)],
            check=True, capture_output=True, text=True)
        fingerprints = re.findall(
            r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
            verified.stdout, re.M)
        if [fingerprint.lower() for fingerprint in fingerprints] != [CERTIFICATE_SHA256]:
            raise ValueError("Signed debug APK does not have the pinned certificate")
        apk.replace(output)
    print("Stable debug certificate SHA-256: " + CERTIFICATE_SHA256)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate-sha256", action="store_true")
    parser.add_argument("--unsigned", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--min-sdk", type=int, default=29)
    parser.add_argument("--apksigner", default="apksigner")
    args = parser.parse_args()
    if args.certificate_sha256:
        if args.unsigned or args.output:
            parser.error("--certificate-sha256 cannot sign an APK")
        print(CERTIFICATE_SHA256)
        return
    if not args.unsigned or not args.output:
        parser.error("--unsigned and --output are required")
    if args.min_sdk < 29:
        parser.error("Package Edition signing requires API 29 or higher")
    try:
        sign(args.unsigned, args.output, args.min_sdk, args.apksigner)
    except (OSError, ValueError, UnicodeError, binascii.Error, subprocess.CalledProcessError) as error:
        print("Debug APK signing failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
