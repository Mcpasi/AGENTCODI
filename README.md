> **Package Edition — for power users and experienced users only.** This edition offers **Full access** exclusively. Codex and user-installed programs can read, change, or delete every file reachable by the app, including Codex account data. Android's isolation from other apps remains in place; there is no workspace sandbox within this app.
>
> **Development status:** The writable package prefix, Full access, and Community app-server integration are implemented. A package manager and a smaller APK remain on the [roadmap](ROADMAP-package-edition.md). This development branch is not merged into `main`.

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

Codex commands and the terminal use the same search order:

```text
PATH=$PREFIX/bin:$HOME/.local/bin:<existing APK tool aliases>:/system/bin:/system/xbin
LD_LIBRARY_PATH=$PREFIX/lib:$HOME/.local/lib:<native APK libraries>
```

The app-server, Codex commands, terminal, and inherited stdio-MCP environment use these paths. The managed prefix takes precedence over the old `$HOME/.local` prefix, followed by the bundled tools. `HOME`, `CODEX_HOME`, and the existing private temporary directory remain separate; `TMPDIR` does not move into the package prefix.

Upgrading an existing installation leaves all files in `$HOME/.local` in place with their contents and file permissions preserved. Programs and libraries there remain reachable as a fallback, including after app restart. No automatic copy, move, deletion, or symlink replacement occurs: binaries, scripts, and configuration may contain absolute paths. Rebuild or reinstall a package explicitly for the new prefix when migrating it. If both prefixes contain the same command or library, the managed version wins; removing it exposes the legacy version again. Workspace export does not include either prefix.

User-installed programs take precedence over the bundled tools. The terminal shell no longer defines fixed functions for `node`, `npm`, `python`, or `rg` that could override this order.

For an initial device test, install a small shell program:

```sh
printf '#!/system/bin/sh\nprintf "package-edition-ok\\n"\n' > "$PREFIX/bin/package-check"
chmod 700 "$PREFIX/bin/package-check"
package-check
```

Codex can then run `package-check` as a normal command. This checks the installation path; a `pkg`/APT package manager is not yet included.

Native packages must be built for Android ARM64/Bionic and support the actual prefix. Existing Termux DEBs often contain fixed paths such as `/data/data/com.termux/files/usr`. Extracting them into `$PREFIX` is insufficient. The chosen architecture uses Termux package recipes rebuilt for this edition, a minimal bootstrap, and a dedicated signed repository; implementation is tracked in the roadmap.

The bundled Node.js, npm, Python, and ripgrep runtimes remain during the transition. Their activation and runtime checks still apply; the existing npm/Python wrappers do not provide general package management.

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
