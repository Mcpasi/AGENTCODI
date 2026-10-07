<a id="signierte-release-apk-der-package-edition"></a>

# Signed Package Edition release APK

The [APK Release](../workflows/apk-release.yml) workflow runs exclusively
on `Mcpasi/package-edition`. It uses the same container, pinned build inputs
and verified bootstrap as the debug build and invokes
`scripts/build-release-apk.sh`. This requires no changes to `main`.

The first public Package Edition APK was published on 2026-10-06 as
[AGENTCODI Package Edition V0.1.0](https://github.com/Mcpasi/AGENTCODI/releases/tag/v0.1.0-package.3),
an early-version prerelease tagged `v0.1.0-package.3`. The released asset is
`AGENTCODI-Package-0.1.0-package.3-arm64-v8a-release.apk`; its SHA-256 is
`028679df0ebeb2f1a5f9d8b373320122cba1778e67e0f198ac877c2771bc25ed`.
The workflow builds and uploads signed artifacts; GitHub release publication
is a separate step and has already taken place for this version.

<a id="einmalige-einrichtung-in-github"></a>

## One-time GitHub setup

Under **Settings → Secrets and variables → Actions → New repository secret**,
configure these five repository secrets:

| Secret | Contents |
| --- | --- |
| `AGENTCODI_RELEASE_KEYSTORE_BASE64` | Base64 contents of your own private JKS or PKCS12 keystore. |
| `AGENTCODI_RELEASE_STORE_PASSWORD` | Keystore password, unchanged and without a newline. |
| `AGENTCODI_RELEASE_KEY_PASSWORD` | Private-key password; for PKCS12, usually identical to the keystore password. |
| `AGENTCODI_RELEASE_KEY_ALIAS` | Private-key alias; 1–128 characters consisting of letters, digits, dot, underscore and hyphen. |
| `AGENTCODI_RELEASE_CERT_SHA256` | SHA-256 fingerprint of this key's certificate; 64 hexadecimal characters, also accepted with colons. |

Keep keystore and passwords outside the repository. Retain an existing
release keystore if it has already been used for this app. If none exists,
one can be created locally; `keytool` prompts for passwords interactively:

```sh
keytool -genkeypair -keystore agentcodi-release.jks -storetype JKS \
  -alias agentcodi-release -keyalg RSA -keysize 4096 -validity 10000
```

Generate Base64 on Linux and enter the entire file contents as the secret:

```sh
base64 -w 0 agentcodi-release.jks > agentcodi-release.jks.base64
```

Copy the fingerprint from the **SHA256** line in `keytool -list -v`.
Alternatively, hash the certificate bytes:

```sh
keytool -exportcert -keystore agentcodi-release.jks \
  -alias agentcodi-release -file agentcodi-release.der
openssl dgst -sha256 agentcodi-release.der
```

Store the keystore, Base64 file and passwords securely; they must not be
committed. The workflow generates no replacement key. The public AOSP test
key and Android debug certificates are rejected by the release build.

As in the existing APK workflow, `AGENTCODI_INPUTS_TOKEN` is used only for
the private build-input mirror. If pinned inputs are available upstream,
they can also be retrieved without this token.

<a id="build-starten-und-apk-herunterladen"></a>

## Start the build and download the APK

Relevant pushes to `Mcpasi/package-edition` start **APK Release** automatically.
The original setup commit already started a run. While secrets are missing,
the run stops with their names before bootstrap/container build. After adding
the secrets, restart that run through **Actions → APK Release → Re-run all
jobs**; no code change is needed.

Later changes to app, modules, scripts or release-build inputs start a new
run. The workflow also includes `workflow_dispatch`; GitHub exposes its
manual start only once the workflow file is known on the default branch.
For this workflow configured solely on the edition branch, push and rerunning
an existing run are the available entry points. Do not change `main` for this.

After a successful build, these artifacts are available:

- `agentcodi-package-release-apk`: versioned and unversioned signed release
  APKs with portable `.sha256` files.
- `agentcodi-package-release-apk-contract`: payload, bootstrap and license
  evidence for that APK.

The signature must contain exactly one signer with the configured SHA-256
fingerprint. The production builder also checks app ID, version, ABI,
alignment, a non-debuggable manifest and complete license evidence.
The workflow itself does not publish a GitHub release; the already-published
release linked above was distributed separately from its successful build.

<a id="temporäre-signing-dateien-und-updates"></a>

## Temporary signing files and updates

The CI entry point checks secrets and creates files with mode `0600` in a
`0700` directory under `RUNNER_TEMP`. Docker receives this directory read-only
outside `/workspace`; passwords are passed to `apksigner` through files.
Raw values do not enter Docker arguments, build cache, APK or artifacts.
Exit/signal traps and an `always()` step remove the signing directory even
when builds are canceled or fail.

For later Android updates, retain the same private signer and increase
`versionCode` with `scripts/bump-version.sh`. An app installed with the public
debug key cannot be updated directly using a different release key. Physical
Android installation, update and data retention remain separate device tests.
Release APK tests through MCP passed; the full
installation/update/version matrix and new MPL source-saving feature are not
documented as hardware-validated.
