#!/usr/bin/env python3
"""Copy only the existing private build-mirror read token to the edition repo."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location(
    "signing_transfer", Path(__file__).with_name("transfer-package-signing-secrets.py"))
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
NAME = "AGENTCODI_INPUTS_TOKEN"
MIRROR = "Mcpasi/agentcodi-build-inputs"
TAG = "0.7.6-preview.1"
REQUIRED_MIRROR_ASSETS = (
    "aapt2-16.0.0.4-1-aarch64.deb", "fmt-11.2.0-aarch64.deb",
    "libcxx-29-aarch64.deb", "libexpat-2.8.2-aarch64.deb", "libpng-1.6.58-aarch64.deb",
)


def check_mirror(token):
    request = urllib.request.Request(
        "https://api.github.com/repos/" + MIRROR + "/releases/tags/" + TAG,
        headers={"Authorization": "Bearer " + token,
                 "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28",
                 "User-Agent": "AGENTCODI-build-input-token-transfer"})
    try:
        with urllib.request.build_opener(shared.NoRedirect()).open(request, timeout=30) as response:
            release = json.load(response)
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        raise shared.TransferError("Private build-mirror read access failed (HTTP " + str(status) + ").") from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        raise shared.TransferError("Cannot verify private build-mirror access.") from None
    pins = {}
    for line in Path(__file__).with_name("build-inputs.tsv").read_text().splitlines():
        if line and not line.startswith("#"):
            path, digest, _, _ = line.split("\t")
            pins[Path(path).name] = "sha256:" + digest
    if release.get("tag_name") != TAG:
        raise shared.TransferError("Unexpected private mirror release.")
    assets = {asset["name"]: asset.get("digest") for asset in release.get("assets", [])}
    if any(assets.get(name) != pins.get(name) for name in REQUIRED_MIRROR_ASSETS):
        raise shared.TransferError("Private mirror does not contain the required pinned build inputs.")


def transfer(environment, api_factory=shared.GitHubAPI, mirror_check=check_mirror):
    api = shared.context(environment, api_factory)
    value = environment.get(NAME, "")
    if not value:
        raise shared.TransferError("Missing source repository secret: " + NAME + ".")
    mirror_check(value)
    key, key_id = shared.destination(api)
    from nacl.public import PublicKey, SealedBox
    encrypted = base64.b64encode(SealedBox(PublicKey(key)).encrypt(value.encode("utf-8"))).decode("ascii")
    api.request("PUT", "/actions/secrets/" + NAME,
                {"encrypted_value": encrypted, "key_id": key_id})
    record = api.request("GET", "/actions/secrets/" + NAME)
    if not isinstance(record, dict) or record.get("name") != NAME:
        raise shared.TransferError("Cannot verify destination build-input secret metadata.")
    print("Stored " + NAME, flush=True)
    if environment.get("GITHUB_STEP_SUMMARY"):
        Path(environment["GITHUB_STEP_SUMMARY"]).write_text(
            "Copied `" + NAME + "` to `" + shared.TARGET + "`.\n\n"
            "Private mirror read access and required asset digests verified. "
            "API write and destination secret name verified. No token values logged.\n")


def main():
    try:
        transfer(os.environ)
        print("Build-input token transfer complete.")
    except shared.TransferError as error:
        print("Build-input token transfer stopped: " + str(error), file=sys.stderr)
        return 1
    except Exception:
        print("Build-input token transfer stopped due to an unexpected error; no token values are logged.",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
