# One-time Package Edition signing-secret transfer

Source: `Mcpasi/AGENTCODI`. Destination: `Mcpasi/AGENTCODI-Package-Edition`
(repository ID `1413186839`). The workflow is restricted to the temporary branch
`Mcpasi/transfer-package-signing-secrets` in the source repository.
Neither `main` nor `Mcpasi/package-edition` is changed or merged.

The source repository's existing Actions secrets are supplied directly to one
step on an ephemeral hosted runner. Values are encrypted in memory with the
destination's public encryption key using libsodium sealed boxes and sent to
GitHub's repository-secret API. The helper preserves the exact UTF-8 bytes,
including newlines, and writes no plaintext files or artifacts. Logs and the job
summary contain only allowlisted secret names, not their values.

## One credential to add

Create a short-lived **fine-grained personal access token**:

- Resource owner: `Mcpasi`.
- Repository access: **Only select repositories** → `AGENTCODI-Package-Edition`.
- Repository permissions: **Secrets → Read and write**. Metadata read access is
  automatic; Contents, Actions and Administration write permissions are not needed.

Add that token as the repository Actions secret **`AGENTCODI_SECRET_TRANSFER_TOKEN`**
in the **old repository `Mcpasi/AGENTCODI`**, under
Settings → Secrets and variables → Actions. Do not put it in a Git file or chat.
The default `GITHUB_TOKEN` is scoped to the old repository and cannot write
secrets in the new one.

No original signing-key files, passphrases, or passwords need to be handed over
or manually read back. Existing source values are used without generating
replacement keys.

## Start and retry

The push containing this workflow starts a run on the temporary branch.
Without the transfer token, the preflight fails with setup instructions, before
signing secrets are passed to any step. Once the token is added, open that run in
Actions → **Transfer Package Edition signing secrets** → **Re-run failed jobs**.
GitHub makes newly added secrets available to the rerun.

This deliberately uses a branch-scoped `push` event. A newly introduced
`workflow_dispatch` workflow would first need to exist on the default branch;
that would conflict with keeping the original `main` untouched.

The helper checks destination identity, public-key access, and the presence of
the complete required source bundle before the first write. Each encrypted write
must succeed and each destination secret name is checked afterward. GitHub does
not return stored secret plaintext, so this verifies accepted writes and metadata
without reading the destination's private values back.

The operation sets or replaces only these names in the destination:

- `AGENTCODI_APT_SIGNING_KEY`
- `AGENTCODI_RELEASE_KEYSTORE_BASE64`
- `AGENTCODI_RELEASE_STORE_PASSWORD`
- `AGENTCODI_RELEASE_KEY_PASSWORD`
- `AGENTCODI_RELEASE_KEY_ALIAS`
- `AGENTCODI_RELEASE_CERT_SHA256`
- `AGENTCODI_APT_SIGNING_PASSPHRASE`, only when its source value is nonempty.

Other target secrets, the transfer token, and `AGENTCODI_INPUTS_TOKEN` are not
copied. An absent/empty optional APT passphrase does not delete a target value.
Source secrets are never updated or deleted. If a write fails midway, earlier
names may already have been set; rerunning copies the same source values again.

The workflow uses repository-level secrets, matching the existing Package
Edition signing jobs. Environment-only secrets are not selected.

After a successful transfer, revoke the short-lived PAT and delete
`AGENTCODI_SECRET_TRANSFER_TOKEN` from the source repository. The temporary
branch can then be removed separately. Production workflow and Pages changes
remain part of migration step 2.

## Local checks with synthetic data

```sh
python3 -m venv /tmp/agentcodi-secret-transfer-test
/tmp/agentcodi-secret-transfer-test/bin/python -m pip install \
  --only-binary=:all: --require-hashes \
  -r .github/ci/transfer-package-signing-requirements.txt
/tmp/agentcodi-secret-transfer-test/bin/python -B \
  .github/ci/transfer-package-signing-tests.py
```
