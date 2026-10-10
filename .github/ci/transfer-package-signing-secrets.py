#!/usr/bin/env python3
"""Copy an explicit signing-secret set using GitHub sealed-box encryption."""
import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request

SOURCE = "Mcpasi/AGENTCODI"
SOURCE_REF = "refs/heads/Mcpasi/transfer-package-signing-secrets"
TARGET = "Mcpasi/AGENTCODI-Package-Edition"
TARGET_ID = 1413186839
REQUIRED = (
    "AGENTCODI_APT_SIGNING_KEY",
    "AGENTCODI_RELEASE_KEYSTORE_BASE64",
    "AGENTCODI_RELEASE_STORE_PASSWORD",
    "AGENTCODI_RELEASE_KEY_PASSWORD",
    "AGENTCODI_RELEASE_KEY_ALIAS",
    "AGENTCODI_RELEASE_CERT_SHA256",
)
OPTIONAL = ("AGENTCODI_APT_SIGNING_PASSPHRASE",)


class TransferError(Exception):
    """Only fixed, non-secret messages may be passed to this exception."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHubAPI:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, method, suffix="", payload=None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            "https://api.github.com/repos/" + TARGET + suffix,
            data=body, method=method,
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "AGENTCODI-signing-secret-transfer",
            })
        try:
            with self.opener.open(request, timeout=30) as response:
                data = response.read()
            return json.loads(data) if data else None
        except urllib.error.HTTPError as error:
            status = error.code
            # Method and allowlisted endpoint are public. Never print a token,
            # arbitrary response bodies, request data, or upstream exceptions.
            endpoint = "/repos/" + TARGET + suffix
            error.close()
            detail = f"GitHub API {method} {endpoint} failed (HTTP {status})."
            if status == 403 and suffix.startswith("/actions/secrets"):
                detail += (" Check the fine-grained PAT: resource owner Mcpasi, selected repository "
                           + TARGET + ", repository permission Secrets: Read and write.")
            raise TransferError(detail) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise TransferError("Cannot reach the GitHub API.") from None
        except (ValueError, UnicodeError):
            raise TransferError("Unexpected GitHub API response.") from None


def context(environment, api_factory=GitHubAPI):
    if (environment.get("GITHUB_REPOSITORY") != SOURCE
            or environment.get("GITHUB_REF") != SOURCE_REF):
        raise TransferError("Transfer is restricted to the temporary branch in " + SOURCE + ".")
    token = environment.get("GH_TOKEN", "")
    if not token:
        raise TransferError(
            "Add AGENTCODI_SECRET_TRANSFER_TOKEN as a repository Actions secret in "
            + SOURCE + "; use a short-lived fine-grained token for " + TARGET
            + " with Secrets: Read and write. Then rerun this job.")
    return api_factory(token)


def destination(api):
    metadata = api.request("GET")
    if (not isinstance(metadata, dict) or metadata.get("full_name") != TARGET
            or metadata.get("id") != TARGET_ID or metadata.get("archived")):
        raise TransferError("Destination repository identity does not match the reviewed target.")
    settings = api.request("GET", "/actions/secrets/public-key")
    try:
        key = base64.b64decode(settings["key"], validate=True)
        key_id = settings["key_id"]
        if len(key) != 32 or not isinstance(key_id, str) or not key_id:
            raise ValueError
    except (KeyError, TypeError, ValueError, binascii.Error):
        raise TransferError("Invalid destination encryption key.") from None
    return key, key_id


def transfer(environment, api_factory=GitHubAPI):
    api = context(environment, api_factory)
    # Check the entire required bundle before the first remote mutation.
    missing = [name for name in REQUIRED if not environment.get(name)]
    if missing:
        raise TransferError("Missing source repository secrets: " + ", ".join(missing))
    key, key_id = destination(api)
    from nacl.public import PublicKey, SealedBox
    box = SealedBox(PublicKey(key))
    names = [name for name in REQUIRED + OPTIONAL if environment.get(name)]
    encrypted = [(name, base64.b64encode(box.encrypt(environment[name].encode("utf-8"))).decode("ascii"))
                 for name in names]
    for name, ciphertext in encrypted:
        try:
            api.request("PUT", "/actions/secrets/" + name,
                        {"encrypted_value": ciphertext, "key_id": key_id})
        except TransferError as error:
            raise TransferError("Transfer stopped at " + name + ". " + str(error)
                                + " Earlier names may already be copied; rerunning is safe.") from None
        print("Stored " + name, flush=True)
    for name in names:
        record = api.request("GET", "/actions/secrets/" + name)
        if not isinstance(record, dict) or record.get("name") != name:
            raise TransferError("Cannot verify destination secret metadata for " + name + ".")
    summary = environment.get("GITHUB_STEP_SUMMARY")
    if summary:
        # Only allowlisted names are written to the log and summary.
        Path(summary).write_text(
            "Signing secrets copied from `" + SOURCE + "` to `" + TARGET + "`.\n\n"
            + "\n".join("- `" + name + "`" for name in names)
            + "\n\nAPI writes and destination secret names verified. "
            "Secret plaintext is not returned by GitHub. "
            "No signing keys were created or rotated.\n")
    return names


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--transfer", action="store_true")
    arguments = parser.parse_args()
    try:
        if arguments.preflight:
            destination(context(os.environ))
            print("Destination identity and public-key access verified: " + TARGET)
        else:
            names = transfer(os.environ)
            print("Transfer complete: " + str(len(names)) + " signing secrets.")
    except TransferError as error:
        print("Signing-secret transfer stopped: " + str(error), file=sys.stderr)
        return 1
    except Exception:
        # Never render unexpected exception details, request bodies, or traces.
        print("Signing-secret transfer stopped due to an unexpected error; no secret values are logged.",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
