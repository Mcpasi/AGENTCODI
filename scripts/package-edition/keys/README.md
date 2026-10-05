# Public APT trust anchors

Commit the ASCII-armored **public** key as `agentcodi-package.asc` in this
directory, exclusively on `Mcpasi/package-edition`. Never commit a private
key or passphrase.

Record its full uppercase primary-key fingerprint in `../repository.json`
as both `signing_fingerprint` and a member of `trusted_fingerprints`.
The file must contain exactly those public keys. Multiple public keys support
a planned transition; the signing secret contains only the active private key.

Set the repository Actions secret `AGENTCODI_APT_SIGNING_KEY` to the
ASCII-armored private signing key. For an encrypted key, also set
`AGENTCODI_APT_SIGNING_PASSPHRASE`. Use repository Secrets, or ensure any
environment-scoped secrets are available to the signing job.

The build exports a binary, scoped keyring and creates the
`agentcodi-package-keyring` DEB. Including this trust package in a fresh APK
bootstrap remains part of the repository integration; its availability in the
online repository alone cannot establish first-install trust.
