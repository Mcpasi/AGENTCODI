> **Package Edition — for power users and experienced users only.** This edition offers **Full access** exclusively. Codex and user-installed programs can read, change, or delete every file reachable by the app, including Codex account data. Android's isolation from other apps remains in place; there is no workspace sandbox within this app.
>
> **Development status:** The writable package prefix, Full access, and Community app-server integration are implemented. The minimal APT/dpkg bootstrap, signed APT repository and initial Python, Node.js/npm, Git and ripgrep catalog are integrated and CI-verified. The public HTTPS repository passes pinned-signature, package/index checksum and complete source-availability checks. The `pkg` frontend, package diagnostics and a smaller APK remain on the [roadmap](ROADMAP-package-edition.md). This development branch is not merged into `main`.

<div align="center">

# AGENTCODI Package Edition

### Codex workflows, native on Android — with user-installed tools.

[Website](https://devsblog.com/) · [Issues](../../issues) · [Roadmap](ROADMAP-package-edition.md)

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Target SDK](https://img.shields.io/badge/targetSdk-28-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</div>

## This development branch

All changes for this second edition are made exclusively on `Mcpasi/package-edition`. The regular AGENTCODI version continues to be maintained on `main`.

AGENTCODI provides native Codex chats, workspace import and export, file previews, an interactive terminal, and MCP management. The app-server runs locally; model requests still require internet access and OpenAI authentication. The interface is available in German and English.

The Package Edition deliberately uses `targetSdk 28` to avoid Android's restriction on executing files from writable app storage for apps targeting SDK 29 or later. The minimum remains Android 10 / API 29. A lower target SDK does not replace a compatible ARM64/Bionic runtime or automatically make Termux packages compatible with another installation prefix.

## Full access

Full access is the only app mode, including after a runtime-service restart. Protected mode and its just-in-time permission setting are unavailable in this edition. The chat always displays Full access.

Commands and file changes can optionally require approval using Codex's `untrusted` policy. These approvals do not provide filesystem isolation. An installed program runs with the app's permissions.

The workspace, user home, and `CODEX_HOME` remain separate directories. This organizational separation does not protect account data from programs running with the same app permissions. Import, preview, and export continue to apply their own file checks.

## User-installed programs

The managed writable installation prefix is `$PREFIX = <app files>/usr`, normally `/data/data/de.agentcodi.pkg/files/usr` (Android may resolve its equivalent `/data/user/0/...` path). It is separate from the user home, workspace, and `CODEX_HOME`. At startup, AGENTCODI creates `bin`, `lib`, `include`, `share`, `etc`, and `tmp`; existing installations are preserved.

The app-server, Codex commands, terminal and local stdio MCP servers share the running server's search path:

```text
PATH=<Codex session helpers>:$PREFIX/bin:$HOME/.local/bin:<existing APK tool aliases>:/system/bin:/system/xbin
LD_LIBRARY_PATH=$PREFIX/lib:$HOME/.local/lib:<native APK libraries>
```

Codex creates its private session helpers when it starts. Commands and the terminal inherit that resulting `PATH`, including the helpers, rather than replacing it with an earlier snapshot; local stdio MCP servers inherit the same value. The other values come from the shared native environment, and the explicit variable allowlist keeps `CODEX_HOME` out of the tool environment. The managed prefix takes precedence over the old `$HOME/.local` prefix, followed by the bundled tools. `HOME`, `CODEX_HOME`, and the existing private temporary directory remain separate; `TMPDIR` does not move into the package prefix.

Upgrading an existing installation leaves all files in `$HOME/.local` in place with their contents and file permissions preserved. Programs and libraries there remain reachable as a fallback, including after app restart. No automatic copy, move, deletion, or symlink replacement occurs: binaries, scripts, and configuration may contain absolute paths. Rebuild or reinstall a package explicitly for the new prefix when migrating it. If both prefixes contain the same command or library, the managed version wins; removing it exposes the legacy version again. Workspace export does not include either prefix.

User-installed programs take precedence over the bundled tools. The terminal shell no longer defines fixed functions for `node`, `npm`, `python`, or `rg` that could override this order.

For an initial device test, install a small shell program:

```sh
printf '#!/system/bin/sh\nprintf "package-edition-ok\\n"\n' > "$PREFIX/bin/package-check"
chmod 700 "$PREFIX/bin/package-check"
package-check
```

Codex can then run `package-check` as a normal command. This checks the installation path. APT and dpkg are initialized from the edition bootstrap on first start. The signed online catalog uses the edition's scoped trust key; the `pkg` frontend remains an upcoming step.

Native packages must be built for Android ARM64/Bionic and support the actual prefix. Existing Termux DEBs often contain fixed paths such as `/data/data/com.termux/files/usr`. Extracting them into `$PREFIX` is insufficient. The chosen architecture uses Termux package recipes rebuilt for this edition, a minimal bootstrap, and a dedicated signed repository; implementation is tracked in the roadmap.

The recipe source, build container, NDK/SDK, and edition prefix configuration are pinned and checked in CI. See the [package build contract](scripts/package-edition/README.md) for recipe preparation, source-only dependency builds, and the signed dedicated repository. The APK now installs an audited minimal bootstrap with dash/bash, APT, dpkg, CA certificates and their dependencies before starting Codex. Interrupted extraction is retried, interrupted package configuration resumes with `dpkg --configure -a`, and a ready installation is preserved across app restarts and APK updates. Conflicting pre-existing prefix files are kept and reported instead of overwritten. Configuration errors are logged to `<app files>/agentcodi/logs/package-bootstrap.log`. The bootstrap includes the dpkg-owned edition keyring for `https://mcpasi.github.io/AGENTCODI/apt/package-edition`. A previous ready installation missing that key receives only the manifest-verified public file on APK update; existing trust and package data are preserved. Use `apt install agentcodi-package-keyring` to register ownership in such an earlier installation.

The initial catalog contains Python, Node.js LTS/npm, Git and ripgrep with their source-built dependency closure. The signed repository is published at `https://mcpasi.github.io/AGENTCODI/apt/package-edition`. The [roadmap](ROADMAP-package-edition.md#signiertes-repository-für-den-startkatalog--2026-10-05) links the verified snapshot and test evidence. Use `apt update` before `apt install python nodejs-lts npm git ripgrep`; `apt upgrade`, `apt remove <package>` and `dpkg-query -W` provide updates, removal and status. APT rejects missing keys, invalid signatures/checksums and expired Release metadata. The signing fingerprint is `2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89`; sources and build evidence are linked from the signed repository manifest. The [source download instructions](scripts/package-edition/README.md#signed-apt-repository-and-trust) explain how to reconstruct a complete authenticated source archive for each group.

Global npm tools and Python user packages use `$HOME/.local`, whose `bin` directory is already on the shared `PATH`. npm keeps its normal `$HOME/.npm` cache and reads user configuration from `$HOME/.npmrc`; the app sets `NPM_CONFIG_PREFIX=$HOME/.local` as the default. An explicit environment value or `npm --prefix <directory>` can select another prefix. Python uses its normal user-site directory under `$HOME/.local/lib/python<version>/site-packages`; pip caches under `$HOME/.cache/pip`. These directories are outside workspace exports and are preserved on restart and APK update.

```sh
apt update
apt install nodejs-lts npm python python-ensurepip-wheels
npm install -g <npm-package>
python -m ensurepip --user
python -m pip install --user <python-package>
python -m venv .venv
.venv/bin/python -m pip install <python-package>
```

The separately packaged ensurepip wheels come from the pinned Python source archive. A venv keeps its packages and scripts inside that environment; use its executable directly or activate it in the terminal. Native extensions still need Android ARM64/Bionic compatibility and appropriate build dependencies.

Both edition npm and the transitional bundled npm adapt npm-managed `#!/usr/bin/env node` scripts to `#!/system/bin/env node` when creating executable links, including local `node_modules/.bin` scripts. Other interpreter requirements remain the package author's responsibility. The shared environment sets `TERMUX_VERSION=agentcodi-package-edition` solely to enable the pinned Community runtime's Android stdio environment allowlist. It forwards `PREFIX`, library paths, npm prefix and cache-home values alongside the normal `HOME/PATH/TMPDIR`; no Termux app or official binary repository is installed. Explicit per-server MCP environment overrides remain user configuration.

The bundled Node.js, npm, Python, and ripgrep runtimes remain during the transition. Their activation and runtime checks still apply. Their npm wrapper preserves user configuration, and bundled Python permits its normal user-site imports; venv/pip package management uses the edition Python installed through APT.

## Runtime and build

The active runtime is the pinned [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux) Community release [v0.156.1-termux.1](https://github.com/DioNanos/codex-termux/releases/tag/v0.156.1-termux.1), based on OpenAI Codex `rust-v0.156.1`. The executable reports `codex-cli 0.156.1`. Release, source, archive, binary, relocation, and generated-schema pins are verified in CI. The updater rejects the former `-agentcodi` sandbox channel and requires the DioNanos source remote and matching release tag.

GitHub tests run on every branch push. The Package Edition APK workflow also runs on pushes to `Mcpasi/package-edition`; manual runs can select preflight only. ARM64/Bionic runtime checks run on hosted GitHub runners. Device-specific linker and installation tests remain separate.

```sh
./scripts/test.sh
./scripts/build-debug-apk.sh
```

The build environment is documented in [.github/ci/README.md](.github/ci/README.md). The Community ELF files run directly; npm launchers and their Termux-specific shebangs are not installed or executed. The code-mode host is packaged as `libcodex-codehost.so`; its matching name substitution is verified against the new binary. Full-access runtime checks replace the old seccomp/ptrace and workspace-sandbox probes.

The Package Edition uses the separate application ID `de.agentcodi.pkg` and starts at `0.1.0-package.1` with its own Android `versionCode 1`. It can be installed alongside the regular version (`de.agentcodi.app`). The two apps have separate private files, settings, and sign-ins. Java classes and generated resources remain under `de.agentcodi.app`, independently of installation identity.

APK files are named `AGENTCODI-Package-<Version>-arm64-v8a-debug.apk` or `AGENTCODI-Package-<Version>-arm64-v8a-release.apk`. Unversioned copies are `AGENTCODI-Package-debug.apk` and `AGENTCODI-Package-release.apk`; the APK workflow uses the artifact `agentcodi-package-debug-apk`. `scripts/bump-version.sh` increments this edition's version line and version code.

Earlier Package Edition builds with the shared ID remain in the previous installation. Their files are not automatically transferred to the new app; export required workspace files before switching, then import them into the new app. The managed package prefix is `/data/data/de.agentcodi.pkg/files/usr`; earlier `$HOME/.local` installations in the same app are retained as described above. Newer Android versions may show a warning about the low target SDK during installation.

## License

Original Java/C++ app code, tests, resources, build automation, and documentation:

Copyright 2026 Pascal (Mc Pasi). [Apache License 2.0](LICENSE).

Licenses and notices for included third-party components are available in [NOTICE.md](NOTICE.md) and in the app.

AGENTCODI is an independent open-source project and is neither affiliated with nor endorsed by OpenAI.
