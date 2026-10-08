# AGENTCODI Package Edition

<div align="center">

### Codex workflows on Android with a real local toolchain

[Downloads](https://github.com/Mcpasi/AGENTCODI/releases/tag/v0.1.2-package.3) · [Website](https://devsblog.com/) · [Issues](../../issues) · [Roadmap](ROADMAP-package-edition.md)

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Version](https://img.shields.io/badge/Package%20Edition-0.1.2-blue)
![Status](https://img.shields.io/badge/Status-published%20experimental%20prerelease-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</div>

AGENTCODI Package Edition brings Codex workflows to Android with an interactive terminal, installable development tools, workspace management, MCP support, and a native local app-server.

The Package Edition has its own application ID, `de.agentcodi.pkg`, so it can be installed alongside the regular AGENTCODI app.

**[Package Edition 0.1.2](https://github.com/Mcpasi/AGENTCODI/releases/tag/v0.1.2-package.3) is published as an experimental prerelease.**
**Device tests for version 0.1.2: passed on physical Android hardware.**

No Termux installation, WebView shell, or separate gateway setup is required.

> [!IMPORTANT]
> **Package Edition is intended for experienced users.**
>
> It runs in **Full access** mode. Codex and programs you install can read, modify, or delete files that are reachable inside the app's private storage, including Codex account data. Android still isolates AGENTCODI from other apps, but there is no workspace sandbox inside Package Edition.

## What you get

- Native Codex chats on Android
- Local Codex app-server
- Interactive terminal
- Writable package environment with APT and dpkg
- Python, Node.js, npm, Git and ripgrep available through the signed package repository
- Workspace import, preview and export
- Read-only browsing and export of installed package files
- MCP server management with per-request tool approval
- German, English and Simplified Chinese interface with device-language detection
- Package diagnostics for paths, installed packages and command resolution
- Separate installation from the regular AGENTCODI edition

Model requests still require an internet connection and OpenAI authentication.

## Requirements

- Android 10 or newer
- ARM64-v8a device
- Internet connection for Codex and package downloads

Package Edition deliberately uses `targetSdk 28` so Android permits execution from the app's writable private package directory. The minimum Android version remains Android 10 / API 29.

Recent Android versions may display a warning during installation because of the lower target SDK.

## Install

Download the Package Edition 0.1.2 APK from [GitHub Releases](https://github.com/Mcpasi/AGENTCODI/releases/tag/v0.1.2-package.3) and install it on your Android device.

Release APKs use the package name:

```text
de.agentcodi.pkg
```

The Package Edition and regular AGENTCODI use separate private storage, settings and sign-ins.

APK filenames follow this format:

```text
AGENTCODI-Package-<Version>-arm64-v8a-release.apk
```

## Install development tools

Package Edition initializes a minimal APT/dpkg environment on first start. The package prefix is normally:

```text
/data/data/de.agentcodi.pkg/files/usr
```

The initial signed catalog includes Python, Node.js LTS, npm, Git and ripgrep.

Start with:

```sh
apt update
apt install python nodejs-lts npm git ripgrep
```

Examples:

```sh
npm install -g <npm-package>

python -m ensurepip --user
python -m pip install --user <python-package>

python -m venv .venv
.venv/bin/python -m pip install <python-package>
```

Installed APT packages live under `$PREFIX`. Global npm tools and Python user packages use `$HOME/.local`.

Useful package commands:

```sh
apt update
apt upgrade
apt install <package>
apt remove <package>
dpkg-query -W
```

The dedicated signed repository is:

```text
https://mcpasi.github.io/AGENTCODI/apt/package-edition
```

Signing fingerprint:

```text
2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89
```

The repository is scoped specifically to Package Edition and is verified using its pinned signing key.

### About Termux packages

Package Edition uses Android ARM64/Bionic packages rebuilt for its own installation prefix.

Existing Termux DEBs may contain hard-coded Termux paths such as `/data/data/com.termux/files/usr`. Those packages are not automatically compatible with AGENTCODI simply because both environments run on Android.

For build details, package provenance and source reconstruction, see the [Package Edition build contract](scripts/package-edition/README.md).

## Full access and approvals

Full access is the only runtime mode in Package Edition.

Optional Codex command and file approvals can still be enabled through the `untrusted` policy. These approvals control individual actions. They do not create filesystem isolation.

Managed MCP servers use per-request tool approval. The approval dialog shows the server, request and redacted tool parameters. **Allow** applies once. **Decline** and **Cancel** close the request without running it.

MCP forms that require additional fields or URL elicitation are currently unsupported and are rejected safely.

## Workspace and package files

The graphical file browser offers three explicit areas:

- **Workspace**
- **APT packages** from `$PREFIX`
- **User packages** from `$HOME/.local`

Package views are read-only.

Files can be imported into the workspace through Android's document picker. Individual files and folders can be exported again. Folder exports are ZIP archives.

Known credential locations such as `auth.json`, `.ssh`, `.npmrc`, `.pypirc`, `.netrc`, `.git-credentials` and APT authentication files are excluded from package exports.

ZIP exports are intended for file transfer and inspection. They do not preserve every package-management property such as executable permissions, links or empty directories.

## Package diagnostics

Open the terminal and use **Package diagnostics** to inspect the active environment.

The report shows information such as:

- `PREFIX`, `HOME`, `TMPDIR`
- command search paths
- library search paths
- npm prefix
- resolved locations of common tools
- installed APT package names, versions and dpkg status

The diagnostics view is read-only and does not install, update or remove packages.

## Runtime

Package Edition currently pins the Community Android ARM64 Codex runtime from [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux), release [v0.156.1-termux.1](https://github.com/DioNanos/codex-termux/releases/tag/v0.156.1-termux.1), based on OpenAI Codex `rust-v0.156.1`.

The runtime archive, source revision, native binaries, schema files and Android integration hashes are pinned and verified in CI.

AGENTCODI is an independent open-source project. It is not affiliated with or endorsed by OpenAI.

## Security model

Package Edition gives installed programs the same app-level filesystem permissions as the local Codex runtime.

Keep these points in mind:

- Programs installed through APT, npm, pip or another tool execute with AGENTCODI's app permissions.
- `HOME`, the workspace, `CODEX_HOME` and the managed package prefix are separate directories for organization.
- That directory separation is not a security boundary between programs running inside the app.
- Android's normal application sandbox still separates AGENTCODI from other apps.
- Review third-party packages and scripts before executing them.

Security issues can be reported through the repository's normal security/contact channels.

## Updates and existing installations

Package Edition preserves the managed package prefix across normal app restarts and APK updates.

Packages, caches and existing user-installed files are retained. APT remains responsible for package updates and removals.

Older development APKs may have used a different signing identity. Android cannot update an installation when the signing certificate changes. In that case, export any data you need before uninstalling the older APK, because uninstalling removes the app's private data.

## Build and verification

The public repository contains the Package Edition build scripts, package recipes, CI checks and verification contracts.

Useful entry points:

- [Package Edition roadmap](ROADMAP-package-edition.md)
- [Package build contract](scripts/package-edition/README.md)
- [Test contract](scripts/package-edition/TEST_CONTRACT.md)
- [CI documentation](.github/ci/README.md)
- [Release signing documentation](.github/ci/RELEASE_SIGNING.md)
- [Third-party notices](NOTICE.md)

Local project checks can be started with:

```sh
./scripts/test.sh
./scripts/build-debug-apk.sh
```

The CI verifies the application identity, APK payload, ARM64 ABI, alignment, signing requirements, package bootstrap, source availability and supplied license material.

## Source code and licenses

AGENTCODI's original Java/C++ application code, tests, resources, build automation and documentation are:

Copyright 2026 Pascal (Mc Pasi)

Licensed under the [Apache License 2.0](LICENSE).

Third-party components keep their own licenses and copyright notices. See [NOTICE.md](NOTICE.md) and the legal notices included in the app.

### Community Codex dependency sources

The original `LICENSE` and `NOTICE` files from the pinned Community Codex runtime are preserved.

Dependency license texts and provenance are documented under [third_party/community-codex](third_party/community-codex/README.md).

Complete original sources for the runtime's MPL-2.0 components are shipped with the APK. They can be exported directly from:

**Settings -> Licenses and notices -> Codex dependency notices -> Save MPL sources**

The export works without an account or network connection.

The exact source archive, component index, hashes and upstream locations are documented in the [MPL source offer](third_party/community-codex/MPL-SOURCE-OFFER.txt).

### Package sources

The APT bootstrap and catalog are built from pinned source inputs. Source provenance, source archives and reconstruction instructions are retained in the repository and build artifacts.

See:

- [Package source documentation](third_party/package-source-archives/README.md)
- [Package build contract](scripts/package-edition/README.md)
- [Third-party notices](NOTICE.md)

## Project branches

`Mcpasi/package-edition` contains the Package Edition development and release line.

`main` continues to contain the regular AGENTCODI edition.

The two editions have separate Android application IDs and can be installed side by side.
