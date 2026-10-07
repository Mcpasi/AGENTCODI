# Roadmap: AGENTCODI Package Edition

Status: 2026-10-07. Only branch `Mcpasi/package-edition`; no merge into `main`.

### Package Edition 0.1.1: package file export fixes - publish, 2026-10-07

Current development identity: `0.1.1`, Android `versionCode 4`, application ID
`de.agentcodi.pkg`. **Experimental prerelease. Device tests: passed.** .
Version 0.1.1 publish.

Selected roadmap area: [Workspace browser and package file import/export](#workspace-browser-und-paketdatei-importexport--2026-10-05).
Two suspected bugs were reproduced independently against the preceding
implementation, using only synthetic files in temporary directories:

1. Save the selected package root, rename it and replace it with a symlink to
   another private directory containing `report.bin`. Single-file inspection
   and export previously resolved the replacement root to its target. The
   shared boundary now rejects noncanonical roots and passes the original
   selected path unchanged to the descriptor-relative no-follow opener.
2. After ZIP inspection, change a member's bytes in place before it is copied,
   preserving its size/inode and restoring the exact original mtime. The ZIP
   previously succeeded. The source/export snapshots now retain and compare
   Unix `ctime`; the native metadata already supplied it but Java discarded it.
   Changes fail export through the existing destination-rollback transaction.

`WorkspaceFileScopeTest` covers both cases for managed APT and user package
roots. Each new regression failed separately before the fixes and passes
afterward. Reproduce through `.github/ci/run-java-tests.sh`: 327 Java tests pass
locally. All seven portable C++ suites pass in a Linux host container;
architecture, release-signing/build-input/APK/MPL-source checks also pass.
Android sources/resources compile against API 35; target 28, minimum 29,
edition identity and signing fixtures (`versionCode 4 → 5`) pass. Physical
Android APK tests subsequently passed according to the user's report above.
The [0.1.1 changelog](CHANGELOG.md#011--package-edition-android-versioncode-4--unreleased)
records these fixes under Package Edition. No tag, APK release, PR or merge
is created for 0.1.1.

<a id="architecture-ci-host-ripgrep--2026-10-07"></a>

### Architecture CI host ripgrep installation — 2026-10-07

[Tests run 37680369242](https://github.com/Mcpasi/AGENTCODI/actions/runs/37680369242)
at `40025bb` and
[Tests run 37683333419](https://github.com/Mcpasi/AGENTCODI/actions/runs/37683333419)
at `7452b24` exceeded the architecture job's ten-minute timeout during
`apt-get update`, before ripgrep installation or architecture checks ran.
Their logs show repeated failures at `http://azure.archive.ubuntu.com/ubuntu`;
some InRelease requests reached the official HTTPS fallback, but package-index
requests stalled on the Azure mirror. The other six test jobs passed in both
runs, including the API-29 isolated host probes and native/nested MCP checks.

The Bionic fix changed only the separate Community ARM64 runtime job and its
CI image. The Ubuntu architecture job's ripgrep installation was unchanged;
the original Bionic fix run `37676838798` also passed architecture checks.
API 29 remains the app's existing minimum and is required by the Community
host's native ELF TLS. This host package-download failure provides no reason
to revert that corrected Android test environment.

The architecture job now pins Ubuntu 24.04 amd64 and installs ripgrep through
`.github/ci/install-host-ripgrep.py`. It selects the official Ubuntu archive
and security sources over HTTPS for both APT commands, retains archive-key
authentication and uses fresh temporary lists. Existing runner sources are
not rewritten. Connection/retry and whole-command limits bound acquisition;
incomplete updates and installation errors fail the job. Eight regressions
cover source isolation, successful installation, existing/broken ripgrep,
update/install failures, cleanup, missing trust material and unsupported hosts. All
architecture, generated-input, signing, APK and MPL checks remain required.
The ten-minute job budget, Android runtime image, catalog recipes, bootstrap,
APK inputs and `0.1.1` / versionCode 4 identity are unchanged.

The first correction run `37686252962` successfully installed ripgrep in
17 seconds, then caught a cleanup permissions error: APT had created
root/_apt-owned `lists/partial` below an unprivileged temporary directory.
The corrected installer puts lists under traversable `/tmp`, always removes
only its own generated lists with sudo, and keeps cleanup failures fatal.

<a id="code-mode-sigsegv-ci-fix--2026-10-07"></a>

### Code-mode SIGSEGV: reproduced and resolved — 2026-10-07

The failure in runtime run `37493948718` was a CI runtime mismatch. The
pinned Termux container supplies Android 9 `aosp-libs 9.0.0-r76-4`, while
the Community V8 host uses native ELF thread-local storage supported from
Android 10 / API 29. Native ARM64 GDB captures identify the invalid pointer
read in `v8::internal::Isolate::Enter()`. Six isolated controls reproduce
SIGSEGV even without MCP, relocation or a model. This is an invalid memory
access signaled by the runner's Linux kernel, not a GitHub cancellation.

The earlier host smoke had a false positive: it searched the whole model
history, including the source containing its success marker. It now checks
the actual matching tool output, with four regressions for that error.
The CI-only runtime image uses pinned Android 10 Bionic libraries; the
Community host, app-server, schemas, APK bytes and application remain unchanged.
The original nested MCP path is now a required test alongside native dispatch.

[Fix run 37676838798](https://github.com/Mcpasi/AGENTCODI/actions/runs/37676838798)
at `35211867a10aef2dc85a3208dcb54d18fcbf7275` passed all seven jobs.
All six isolated probes return exit 0, the actual relocated-host marker is
verified, and native/nested MCP each verify accept/decline/cancel with the
real Java responses. The downloaded
[runtime artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37676838798/artifacts/11507383509)
confirms the results and unchanged binary/schema pins. The recorded CI crash
is closed. Physical Android device/version/update checks remain separate.
See the [complete diagnosis and reproduction commands](.github/ci/CODE_MODE_SIGSEGV.md).
All work stays on `Mcpasi/package-edition`; `main` remains
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`, with no PR or merge.

### Published Package Edition release — 2026-10-06

[AGENTCODI Package Edition V0.1.0](https://github.com/Mcpasi/AGENTCODI/releases/tag/v0.1.0-package.3)
was published on 2026-10-06 at 23:36:32 UTC as an experimental prerelease,
tagged `v0.1.0-package.3`, from this edition branch. The released asset is
`AGENTCODI-Package-0.1.0-package.3-arm64-v8a-release.apk`, with SHA-256
`028679df0ebeb2f1a5f9d8b373320122cba1778e67e0f198ac877c2771bc25ed`.
It matches the signed APK evidence recorded below. APT repository publication,
signed APK CI artifacts and the GitHub APK release are separate deliverables;
all three now exist. This documentation update creates no release, PR or merge.

<a id="mpl-20-quellenzugang--2026-10-06"></a>

### MPL-2.0 source access — 2026-10-06

The twelve MPL components of the Community runtime receive complete,
unchanged sources in the APK: ten crate archives bound to Cargo.lock checksums
and one Git snapshot for both nucleo components. The readable source offer
explains free offline delivery; the license view provides “Save MPL sources”
through the Android document picker. Original copyright notices and license
files remain included. The APK contract checks source/version coverage,
package manifests, license files and all delivered bytes. Regressions prevent
silent notice loss and new MPL dependencies without updated sources. APT
recipes, bootstrap build inputs and catalog packages are unchanged by this work.

For implementation commit `e6303b269c7229d439ae13ad0c073262ad343e31`, all
seven jobs of [Tests run 37533009175](https://github.com/Mcpasi/AGENTCODI/actions/runs/37533009175)
and all four jobs of [signed release APK run 37533013532](https://github.com/Mcpasi/AGENTCODI/actions/runs/37533013532)
passed. The nine MPL regressions, 22 APK contract tests and architecture check
passed locally. The release build uses the existing, verified bootstrap from
run `37493949315`; no APT catalog or packages such as Python, ripgrep, Git or
Node are rebuilt.

The [completed APK contract report](https://github.com/Mcpasi/AGENTCODI/actions/runs/37533013532/artifacts/11445870275)
confirms `license_release_ready: true`, no license blockers and complete
coverage of the twelve MPL components by eleven original source archives.
The three MPL assets in the APK match the commit byte for byte. The
[signed APK](https://github.com/Mcpasi/AGENTCODI/actions/runs/37533013532/artifacts/11444709902)
has SHA-256 `028679df0ebeb2f1a5f9d8b373320122cba1778e67e0f198ac877c2771bc25ed`;
`MPL-SOURCES.zip` has SHA-256
`cdcc940563e3e267fbca5343f08e118785b21018eda598a957382cb2a310a174`.
The report was also downloaded and its MPL asset checksums compared with the
local files. At this verification milestone, a direct complete APK download
into the working environment failed with HTTP 403; the actual APK contents
were verified in the successful release job.

The user reports that the release APK device tests, including APT through MCP,
passed, and confirmed successful hardware testing of the current APK on
2026-10-07. The experimental
code-mode callback failure described in the MCP approval section was reproduced
and resolved as a CI Bionic-version mismatch on 2026-10-07; see the [investigation above](#code-mode-sigsegv-ci-fix--2026-10-07).

The checklists show the current implementation status. Dated result and verification sections record earlier milestones; their test counts, artifacts and checksums belong to the specified commit. Statements about skipped device tests or absent APK publication in those sections describe that milestone, not the current release status above. Bootstrap, starter catalog and public signed APT repository are implemented. npm/Python paths and the shared process environment are implemented and checked in CI; the evidence is in “Shared package environment and npm/Python paths”. Package diagnostics and workspace browser/import/export extensions are implemented; user-installable transitional tools have been removed from the APK. Remaining legacy helpers and transport parameters have been cleaned up. The build script, Dockerfile, CI inputs, restore/preflight checks and cache keys are reduced to the active edition dependencies. The final test/payload contract and reconciliation of delivered legal materials are implemented; the evidence is in “Final test contract and license reconciliation”. Community Rust/V8 dependency texts and the three package-local bootstrap license assignments have been supplemented; source, version and artifact bindings are checked in the APK contract. “Supplementing the missing licenses” documents that status. The initial user report confirmed package installation/use; the later report confirms release APK tests through MCP. Remaining hardware checks and the resolved CI code-mode callback failure are recorded separately. The APK has been released as described above.

<a id="ziel-und-feste-entscheidungen"></a>

## Goal and fixed decisions

Users install their own packages for Codex and the terminal to use directly. This second development line uses `targetSdk 28`, offers only Full access and is intended for experienced users. Android's isolation between apps remains active; this edition provides no additional workspace sandbox.

A target SDK change alone provides neither a package manager nor a suitable package source. Programs need Android ARM64/Bionic and the correct installation prefix. The package architecture selected by the user on 2026-10-03 — a dedicated prefix, minimal bootstrap and signed repository built from Termux package recipes — is recorded in section 3.

<a id="1-grundlage"></a>

## 1. Foundation

- [x] Create a separate branch from main commit `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.
- [x] Set target SDK to 28 in the manifest, build script and BuildIdentity; retain minimum SDK 29.
- [x] Restrict the app mode to Full access, including service restart and old launch intents.
- [x] Remove Protected mode selection and the JIT switch from settings.
- [x] Update German and English texts, persistent chat labeling and the README warning.
- [x] Create a writable transitional prefix `$HOME/.local` with `bin/lib/include/share/etc/tmp`. The managed package base has since moved to `files/usr` as described in section 3; existing `$HOME/.local` files are preserved.
- [x] Put prefix binaries and libraries before transitional tools for the app-server and Codex commands.
- [x] Remove shell functions that override user-installed programs with the same name.
- [x] Add regressions for persistent installations, mode contract, SDK pins and actual execution of user programs.
- [x] Also compile Android sources/resources against API 35 in GitHub Actions; check manifest target 28 and minimum 29.
- [ ] Android device test: start a program from the writable prefix, execute the same command through Codex, restart the service/process and check again. The user reports successful release APK testing; this complete sequence is not separately documented.
- [x] Define and consistently implement a separate application ID, version line and APK names for parallel installation: `de.agentcodi.pkg`, its own `0.1.0-package.1` line starting at `versionCode 1`, APK files `AGENTCODI-Package-*` and CI artifact `agentcodi-package-debug-apk`. The display name is AGENTCODI Package; the Java namespace remains `de.agentcodi.app`.

The installation identity is independent of the Java namespace. All manifest components use fully qualified class names; AAPT2 still generates resources under `de.agentcodi.app`. Android CI compares installation/version information in the manifest, build script, BuildIdentity and linked resources and checks that each manifest component exists as a Java class. A physical installation/parallel-operation test remains part of the hardware matrix not yet documented as complete. Previous data from the shared ID is not transferred automatically; export/import is described in the README.

The edition uses only `:danger-full-access`; the Protected module, active Protected contracts and JIT selection have been removed. Remaining old boolean transfer parameters are always false or explicitly reject true. Old launch intents are migrated to Full access. The historical internal mode ID `compatibility` remains for compatibility with existing session data.

<a id="2-community-app-server-anbinden"></a>

## 2. Integrate the Community app-server

The active Community runtime comes directly from the pinned `DioNanos/codex-termux` release. Verified Community release dated 2026-09-24:

- Repository: https://github.com/DioNanos/codex-termux
- Release: `v0.156.1-termux.1`, upstream `rust-v0.156.1`
- Source commit of the release tag: `ea762071ec4acbf1531fcc7daf47524836f70a09`
- Archive: `mmmbuto-codex-cli-termux-0.156.1-termux.1.tgz`
- SHA-256 from the GitHub release asset digest: `44cee2f3a4a110fd79d4f7d61378d46fd72406f45cffb3163e809d63e86d946a`

These details identify the active, fully pinned Community runtime. Archive, source commit, ELF files, APK relocation and generated schemas are verified in GitHub Actions.

- [x] Download the release archive in CI, verify its checksum and inspect contents including the code-mode host, licenses and dependencies.
- [x] Record the source commit and full binary/schema checksums; do not reuse old hashes or binary offsets.
- [x] Switch `scripts/update-codex-runtime.sh`, CodexRuntimeUpdater/Metadata/LocalSource, BuildIdentity, build input list and notices to the Community channel.
- [x] Check the app-server JSON schema against all used RPCs: Initialize, Login, Models, Permission Profiles, Thread/Turn, Approvals, Terminal PTY, MCP and Connectors.
- [x] Adapt renamed fields, capabilities or methods in Client/SessionController and verify with the real app-server.
- [x] Check code-mode host resolution. If an APK library is still renamed, determine the new offset from the new artifact.
- [x] Reduce native startup arguments to Full access and remove the unused `agentcodi-workspace` profile.
- [x] Replace old Protected/JIT contracts, modules, resources and tests selectively; retain the other regressions.
- [x] Replace CI sandbox special options and seccomp/ptrace/Protected smokes in the edition build with Full-access smokes.
- [x] Ensure no runtime update selects the Mcpasi sandbox fork again.

<a id="ergebnis-der-community-archivprüfung--2026-10-03"></a>

### Community archive inspection result — 2026-10-03

Implemented with `.github/ci/community-codex-release.json`, `inspect-community-codex.py` and the additional Tests job `Community Codex release inspection`, only on this branch. Successful [CI run 37158009969](https://github.com/Mcpasi/AGENTCODI/actions/runs/37158009969) for commit `5a89b6a3a4e2871b3952d10b2201215527e4ea10` checks the release asset digest, resolved tag source commit and actual downloaded archive bytes. The [inspection artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37158009969/artifacts/11286760213) contains the full file inventory with SHA-256, ELF reports, metadata, launcher findings and LICENSE/NOTICE.

- The archive contains 12 regular files, including `codex.bin`, the separate `codex-code-mode-host` and `libc++_shared.so`. The hostname occurs in the Codex binary; this static check did not yet confirm actual resolution or APK relocation. Both were checked in the subsequent Community integration completion recorded below.
- Both executable ELF files are ARM64/ELF64 with interpreter `/system/bin/linker64` and RUNPATH `$ORIGIN:$ORIGIN`. The two identical entries resolve to the same library directory; foreign or empty search paths are rejected.
- `codex.bin` dynamically requires `libdl.so/libm.so/libc.so`, and the code-mode host additionally requires `liblog.so`. The supplied `libc++_shared.so` requires `libc.so/libm.so/libdl.so`; the two programs have no direct DT_NEEDED entry for libc++ in this release. All detected dynamic dependencies are Android system libraries.
- No npm package dependencies are declared. The JavaScript launchers declare Node.js `>=18.0.0`; the postinstall script adjusts shebangs using the running Node interpreter. Shell and JavaScript launchers still contain the Termux default prefix `/data/data/com.termux/files/usr`. The later integration uses the ELF files directly; these npm/JavaScript launchers are neither installed nor executed.
- The package license and LICENSE are Apache-2.0; NOTICE names OpenAI, Davide A. Guglielmi and Ratatui/MIT. Separate license texts for libc++ and statically linked Rust/V8 dependencies are absent from the archive. LICENSE/NOTICE are copied unchanged into the APK. The reconciliation in section 4 at that time recorded this supplied set and missing Rust/V8 dependency notices. The later “Supplementing the missing licenses” section documents the separate, pinned addition of those materials.
- The archived README contains the outdated `rust-v0.155.0`; the release and package description specify `rust-v0.156.1`. The check records this discrepancy and uses the pinned release/source information.

Newly determined ELF checksums, exclusively for this unchanged release archive:

| File | SHA-256 |
| --- | --- |
| `codex.bin` | `6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5` |
| `codex-code-mode-host` | `8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf` |
| `libc++_shared.so` | `430f7cde7c1a88042bb9e39ae1c1f4b9f5819f3bd95acd1453d2e02c63ba1870` |

Architecture checks, 309 Java tests, all 8 portable C++ suites, Android sources/resources against API 35 and the release inspection including 8 new archive/search-path tests passed. The initial CI issues (anonymous GitHub API rate limit and overly strict comparison of duplicate ORIGIN entries) were fixed.

This static inspection executed neither npm install/postinstall nor Community ELFs and changed no APK runtime. Schema generation/checksums, RPC/startup compatibility, host relocation and channel switching were implemented subsequently; the next section records completion. Device tests were still open at this milestone and were skipped at the user's request.

<a id="abschluss-der-community-anbindung--2026-10-04"></a>

### Community integration completion — 2026-10-04

The active build uses `0.156.1-termux.1`; the ELF reports upstream version
`codex-cli 0.156.1`. Version checking accounts for this distinction.
Updater, metadata and source checks accept the DioNanos channel, require the
matching release tag and reject `-agentcodi` and a foreign source remote. The
33 download entries in the build input manifest are generated from the build
script; obsolete local sandbox/schema inputs are removed. Community archive
LICENSE and NOTICE are copied unchanged into the APK; historical credits
remain clearly identified as historical information.

| Pin | Determined value |
| --- | --- |
| Community source commit | `ea762071ec4acbf1531fcc7daf47524836f70a09` |
| OpenAI upstream commit | `b412ff32c417f855c2b2d1581b77058eed87c84b` |
| Unchanged app-server ELF SHA-256 | `6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5` |
| Unchanged code-mode host SHA-256 | `8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf` |
| APK app-server ELF SHA-256 | `cf1b406252928b0d68cb0f8f81adde6a02bf357a7fffb762d10cb503a235be06` |
| Complete app-server schema SHA-256 | `eb1ba91bd0fab656523092f6ed7de3ea7aef278921a650f14dc871ae7dcfaf84` |
| v2 schema SHA-256 | `995fc3b8f8c469f6787e8fc5be4038c4f31359025edd8480b862e83355f3bf3b` |
| Host name field in the new ELF | Byte offset `10568364`; `codex-code-mode-host` → `libcodex-codehost.so` |

The new offset comes from the unique install-context data field of the
Community artifact. The change preserves binary length and changes exactly
this entry; other host name references remain unchanged. Full ELF checksums
verify the result.

ARM64/Bionic CI generates schemas with the actual ELF, validates 378 test
RPCs sent by the Java client and additionally checks login, approval and
user-input formats. Used methods for Initialize, Models, Permission Profiles,
Thread/Turn, Terminal, MCP and Connectors are present in the schema. The real
app-server executes Full-access commands, reads/writes synthetic files outside
the workspace and handles PTY operations and MCP/Connector queries. Import
context is verified in the actual model request using the canonical file path
rather than the visible label. The native bootstrap reads thread IDs specifically
from the thread object, regardless of the preceding `activePermissionProfile`.
A local Responses API dummy completes a turn through the renamed code-mode
host; its JavaScript result is checked in the follow-up request. After runtime
restart, the session resumes and a user-installed program runs again. No real
OpenAI credentials or external model requests are required.

Native startup arguments include neither JIT nor the old workspace profile.
Protected/JIT-specific modules, UI resources and tests were selectively
replaced with Full-access contract checks; other regressions remain. The
edition APK build requires no special seccomp/ptrace/AppArmor options and
checks Full access instead of the old workspace isolation.

The root README is entirely English. The subsequently implemented package
base, starter catalog and signed repository are documented in section 3.
The later package path/environment work is recorded there with CI evidence.
APK size reduction in section 4 has since been implemented. Physical Android
device tests were still open at this milestone and were not performed at the
user's explicit request.

<a id="3-paket-bootstrap-und-workspace-vervollständigen"></a>

## 3. Complete package bootstrap and workspace

<a id="beschlossene-paketarchitektur--2026-10-03"></a>

### Agreed package architecture — 2026-10-03

The user explicitly selected this solution. The architecture decision was first documented on 2026-10-03; bootstrap and package repository were implemented afterward as described below. The decision does not need to be requested again.

- **Reuse Termux package recipes:** Rebuild required packages and their dependencies from [termux/termux-packages](https://github.com/termux/termux-packages) for Android ARM64/Bionic, AGENTCODI's own application ID and installation prefix. The Termux app itself is neither included nor pinned as a complete app version.
- **Dedicated prefix outside the user home:** Use `files/usr` for the package base, for example `/data/data/de.agentcodi.pkg/files/usr`. The separate application ID is finalized as `de.agentcodi.pkg` and implemented in the manifest and build configuration. User home, workspace and `CODEX_HOME` remain separate directories. The former `$HOME/.local` prefix remains as a legacy fallback; the managed package base is now in `files/usr`.
- **Minimal bootstrap:** Provide only shell, APT, dpkg, certificates and their required dependencies as the initial base. AGENTCODI installs and initializes this bootstrap.
- **Dedicated signed package repository:** Its own CI builds offered packages from Termux recipes. The repository provides installation, updates and dependency resolution. The implemented interface uses APT, for example `apt install python nodejs-lts npm git ripgrep`; an additional `pkg` command remains optional.
- **Targeted reproducible pins:** Record package recipe revision, build toolchain, package versions and artifact checksums. Test and publish updates deliberately. A controlled set of build adjustments is sufficient; adopting the complete Termux app is unnecessary.
- **Consistent runtime:** Codex commands, terminal, app-server and local stdio-MCP processes use the same package installation and coordinated `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR` values.

Official Termux DEBs are often built for `/data/data/com.termux/files/usr`. Extracting them, changing `PATH` or setting `apt --root` does not generally make them compatible. This edition's regular package source therefore contains dedicated builds for its fixed prefix; official Termux binary repositories are not mixed in as interchangeable sources.

The offered package catalog includes only packages built and verified for this edition with their dependencies. The dedicated repository requires ongoing maintenance and security updates. Packages depending on Termux app components or Termux:API need separate adaptations before being offered.

PRoot with virtual Termux paths and general post-build relocation of finished DEBs are not the chosen approach. The edition is intended to execute packages directly and natively under its own prefix.

The Termux build system documents configurable app/prefix variables in [scripts/properties.sh](https://github.com/termux/termux-packages/blob/master/scripts/properties.sh) and supports custom bootstrap builds through [scripts/build-bootstraps.sh](https://github.com/termux/termux-packages/blob/master/scripts/build-bootstraps.sh). Implementation must set these values consistently in the build configuration; a runtime export alone does not replace rebuilding.

<a id="umsetzungsschritte"></a>

### Implementation steps

- [x] Document the user's architecture decision: dedicated prefix, minimal bootstrap and signed repository from rebuilt Termux package recipes.
- [x] Finalize and implement the separate application ID before building packages with absolute paths: `de.agentcodi.pkg`; managed prefix `/data/data/de.agentcodi.pkg/files/usr`.
- [x] Move the managed prefix to `files/usr` outside the user home. Preserve existing `$HOME/.local` files; document and test transition/migration and search order.
- [x] Pin a reproducible revision of `termux-packages` and the toolchain; version targeted build adjustments for app ID, prefix and repository URLs. Align bootstrap, package metadata, shebangs, RPATH/RUNPATH and configurations to the same final path.
- [x] Build a minimal ARM64 bootstrap with shell, APT, dpkg, certificates and dependencies; integrate initialization and repair after interrupted installation.
- [x] Set up dedicated CI for package/dependency builds; verify bootstrap and a small catalog such as Python, Node.js/npm, Git and ripgrep first, then expand.
- [x] Set up a signed APT repository with trust key, HTTPS, publication process and update strategy.
- [x] Provide documented APT usage for installation, updates and removal. An additional `pkg` wrapper is optional and not yet implemented.
- [x] Fully verify the shared environment definition for app-server, Codex commands, terminal and local stdio-MCP processes after npm/Python path adjustments. The real ARM64/Bionic app-server checks identical `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR` values in Codex shell, terminal and stdio-MCP, including private Codex session helpers in the shared `PATH`.
- [x] Make npm global prefix/cache and Python user/venv/pip paths usable. Normal user configuration and Python user site are active; local global/user/venv installations, executable scripts, npx and removal are verified under ARM64/Bionic.
- [x] Document installation, updates, removal and status in the terminal; retain packages across app restart and APK update. APT usage is documented in the README, preservation checked by Java regressions; the full device/update matrix remains separately undocumented as complete.
- [x] Display package paths and installed versions in diagnostics/terminal when needed; replace former activation displays. The terminal “Package diagnostics” button queries current environment values, command resolution and version/status data from the managed dpkg database.
- [x] Extend workspace browser and import/export for explicitly selected package areas. Account data remains outside accessible roots; known credential paths and links are blocked in package areas.
- [ ] Verify Android 10 and a current Android version on physical ARM64 hardware: ELF, script/shebang, dynamic library, npm/pip, PTY and stdio-MCP. The user reports release APK tests through MCP passed; completion of this full version/device matrix is not documented.

<a id="verwalteter-präfix--2026-10-04"></a>

### Managed prefix — 2026-10-04

The app now creates the package base as `usr` directly under its canonical
files directory and explicitly passes it through Java/JNI to the native
supervisor. `HOME`, workspace, `CODEX_HOME` and temporary files remain separate.
Codex adds private session helpers to the shared `PATH` on startup. Package
search order is then `files/usr/bin`, `$HOME/.local/bin`, APK aliases, Android
system paths; libraries are searched in `files/usr/lib`, `$HOME/.local/lib`
and native APK libraries. App-server and Codex shell configuration obtain
package values from the same native definition. Codex commands and terminal
inherit the server `PATH` augmented with session helpers at runtime; inherited
stdio-MCP processes use the same actual path. Final reconciliation is described
in the result section dated 2026-10-05.

Existing files under `$HOME/.local` are preserved unchanged. No automatic move
or relocation occurs; packages with embedded absolute paths must be reinstalled
specifically for the new prefix. README and regressions cover preservation,
search precedence, legacy fallback, process restart and rejection of unsuitable
prefix directories. The real ARM64/Bionic APK smoke checks the contract through
Codex and terminal shell. Bootstrap, starter catalog CI and the public signed
package repository are documented below. Device tests were open and skipped
at the user's request at this milestone.

<a id="reproduzierbarer-paket-buildvertrag--2026-10-04"></a>

### Reproducible package build contract — 2026-10-04

Implemented with `scripts/package-edition/lock.json`, the small versioned
`overlay.json`, `prepare.py` and `verify-prefix.py`. The recipe revision is
`termux/termux-packages@b6af76b353140fe17f299248fca1ac13ea91c5c5`
(Git tree `34c914ef107a5552e9c850299be67050cbabe4eb`). The amd64 build container
is pinned by digest `sha256:1db92723f6a82fd3ba45288d68ff99dbdeb08a3e9f3f0c4750115178cc7a6879`
from the successful [upstream build](https://github.com/termux/termux-packages/actions/runs/35882400849/job/107253886726).
NDK r30, SDK 9123335 with archive SHA-256, Build-Tools 37.0.0, host LLVM 21
and ARM64/Bionic/API 29 are fixed. Fixed container bytes also pin host
dependencies; no package upgrade runs there. API 29 is the native minimum;
the manifest remains at target SDK 28.

Preparation requires a clean checkout, checks commit, tree, individual blob
IDs and unique change contexts, and writes only a separate recipe copy.
App ID and home are set before upstream paths are derived: package base
`/data/data/de.agentcodi.pkg/files/usr`, home `files/agentcodi/home`.
Patch replacement, bootstrap path templates, shebang processing, linker
RUNPATH and Debian metadata use the same package base. Recursive builds
receive the same pins and `SOURCE_DATE_EPOCH=1791110575`. Foreign ABIs,
glibc and downloaded binary dependencies, including automatic cyclic seeds,
are rejected; such cycles need explicitly audited edition seeds later.

`repo.json` and the APT recipe use only the dedicated HTTPS source
`https://mcpasi.github.io/AGENTCODI/apt/package-edition` with `stable main`
and `signed-by=$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`. The upstream
Termux keyring is excluded. At this build-contract milestone the source had
not yet been published; trust key and public publication were subsequently
implemented in the signed repository step described below. The APT source
is now public.

The new Tests job “Package recipe and toolchain contracts” checks preparation
twice, actual upstream path derivations, bootstrap templates, patch/shebang
replacement, APT configuration and rejection cases. In the pinned container
with the actual NDK, it builds an ARM64 test ELF and a library, uses the real
Debian metadata/archive hook, compares two DEB assemblies and checks interpreter,
RUNPATH, payload, symlink, script and metadata paths. The artifact contains
compiler/host inventory, preparation, ELF reports, package metadata, test DEB
and SHA-256. FUSE/sysroot setup and real bootstrap packages were subsequently
verified during the full build below.

The initial packages of the subsequently implemented bootstrap are Dash,
APT, dpkg and certificates with dependencies. This build contract's test DEB
was not a distributable bootstrap; initialization/repair, real package builds
and signed publication are documented in the following result sections.
Termux app/API/Exec/Tools components need dedicated adaptations and remain
rejected. The [build documentation](scripts/package-edition/README.md)
describes pins, adjustments, checks and the update process. Device tests
were skipped at the user's request at this milestone.

<a id="minimaler-arm64-bootstrap-und-wiederherstellung--2026-10-04"></a>

### Minimal ARM64 bootstrap and recovery — 2026-10-04

The edition bootstrap is built entirely from pinned Termux recipes for
ARM64/Bionic/API 29 and `/data/data/de.agentcodi.pkg/files/usr`. Dash provides
`sh`; Bash is supplied for package configuration scripts. At that time the
bootstrap with APT, dpkg, CA certificates and runtime dependencies comprised
47 packages, before the later edition keyring package was added. Build
dependencies are also built from source and then excluded from the runtime
bootstrap. The libc++ compiler runtime comes from the fixed NDK as specified
in the pinned recipe. Termux app/API/Exec/Tools/Keyring components and foreign
binary repositories remain excluded.

`assemble-bootstrap.py` checks Depends/Pre-Depends with versions, script
interpreters and ELF dependencies. Package paths, shebangs, RUNPATH, symlinks,
configuration files and ARM64/Bionic are checked before packing. The dpkg
database contains complete file lists including jointly registered parent
directories, checksums, configuration-file MD5 and the “unpacked” state.
Shared directory entries prevent attempts to remove protected parents when
later packages are uninstalled; configuration changes can thus be detected
in later package updates. ZIP order and timestamps are fixed. The artifact
contains selected DEBs, ZIP, size/SHA-256 manifest, version/ELF report,
SHA256SUMS and the corresponding source archive with recipes, patches and
edition build scripts.

The APK contains ZIP, manifest and report as assets. `PackageBootstrap`
installs into private staging before app-server startup and checks every file
against size, SHA-256 and file mode. Existing noncolliding prefix files are
preserved; collisions are reported. Installation is committed through atomic
renames with backup. Interrupted extraction is retried on the next startup;
interruption between renames first restores the previous prefix. Native
initialization executes `dpkg --configure -a` with an explicit package
environment, timeout and private log. The ready marker is created only after
success. Failure/interruption allows configuration to resume on next startup.
Log: `files/agentcodi/logs/package-bootstrap.log`.

An initialized prefix is not extracted again on app restart or APK update.
Installed packages, APT/dpkg state and user changes remain intact, as do
HOME, CODEX_HOME and the old HOME/.local prefix. A bootstrap version is not
copied over an existing installation; package updates use the now-published
signed APT channel. Limited migration of a missing trust key is described
in the repository section.

CI issues were fixed: unnecessary APT documentation/archiver build dependencies,
the unsupported GnuPG gpgv-only switch, leftover Perl helpers, missing dpkg
checksums and the test-container runtime contract. libandroid-selinux retains
edition CFLAGS in its Makefile and validates source commit
`1cbcdf624c248c66cd6153311d3e681ba1f9ff2a`; the source archive contains its
clean Git snapshot. The package toolchain consistently uses `-femulated-tls`:
the pinned Bionic CI image is based on Android 9 and does not support native
TLS relocations generated by default from compile API 29 onward. API 29
remains the minimum. The CI harness additionally preloads a test-only
reallocarray library for the API-28 reference image. It matches the
overflow-checked API-29 implementation from AOSP commit
`290c0cb5044b643e5d6cbcb1a5b275541ca3a89e` and is tested on ARM64/Bionic for
allocation, growth, preservation and ENOMEM on overflow. Library and sources
are in a separate CI artifact and are not installed in bootstrap or APK;
Android 10+ supplies the function itself. A real NDK thread-local test and
rejection of native AArch64 TLS relocations verify the contract.

The successful [source build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37215181811/job/111474115967)
delivered source-built DEBs and the original source archive. The assembly
at that time delivered the [bootstrap/source artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636/artifacts/11309996366).
ZIP SHA-256: `d875abf8f90fe7e70494ce8b268da826e575031275f75ca611e89ae64512041a`.
Manifest SHA-256: `275ef6dad15d6cdcfa8a5e7765bc697b5923da1e7400f637bb022f44cd18eccd`.
Subsequent builds may reuse these DEBs only with identical package build
inputs and a verified artifact; current assembly/ELF checks run again.

Verification: [Tests run 37219054346](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054346)
and [APK run 37219054636](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636)
for implementation commit `070c37845e19931501d692ad0eb081da16296663` passed.
The ARM64/Bionic smoke at that milestone installed the real ZIP through the
Java installer, configured all 47 packages with Android dpkg, started
shell/APT/gpgv and checked the registered APT version through apt-cache policy,
certificates and configuration-file metadata. It also installed, started
and removed a local test package. The 313 Java tests cover recovery after
extraction/configuration interruption, rename interruption, integrity/path
errors and preservation across APK updates. The 15 prefix/TLS checks and
9 bootstrap assembly tests passed, as did the eight portable C++ suites,
Android compilation and Community runtime. The full debug APK build checks
embedded bootstrap assets, native compilation, Full-access/PTY/app-server
smokes and APK identity, signature and alignment. The [debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37219054636/artifacts/11309621629)
contains the verified Package Edition (
`AGENTCODI-Package-0.1.0-package.1-arm64-v8a-debug.apk`, 169 MiB;
SHA-256 `3f395698a6100003854b5884efcb7b58288e9a9216f31f8abc46b1078c9ea987`).
Device tests were skipped as instructed and remained open at this milestone.

Starter catalog, trust key and signed online repository were subsequently
implemented; the following sections record the evidence. The source is
authenticated exclusively with the pinned key; there is no insecure fallback.
Further catalog expansion remains optional. The APK size reduction then still
pending has since been implemented. A `pkg` wrapper remains optional.
No PR, merge or GitHub APK release was created during this milestone;
the APK was subsequently released on 2026-10-06 as recorded above. `main`
remains unchanged by edition work.

<a id="paket--und-abhängigkeits-ci-mit-startkatalog--2026-10-04"></a>

### Package/dependency CI with starter catalog — 2026-10-04

The branch-specific `.github/workflows/packages.yml` checks the normal app
bootstrap and four separate source-build groups: Python 3.14.6, Node.js LTS
24.18.0 with npm 11.20.0, Git 2.56.0 and ripgrep 15.2.0. `catalog.json`
extends the bootstrap overlay without changing its package recipes. Python
is configured without Tk; Git omits GUI and optional Perl/Python integrations.
npm uses a fixed Git commit. All offered packages and their target build
dependencies are built from source for ARM64/Bionic/API 29 under the edition
prefix.

Each catalog artifact contains runtime DEBs, package versions, hashes,
preparation and corresponding sources including build dependencies.
`all-built-packages.json` documents checks of all generated DEBs, including
build-only packages. Dependency versions, file collisions, executable script
interpreters, ELF libraries and prefix paths are checked. The ZIP in the
catalog artifact is exclusively a CI test image; the APK still contains the
normal minimal bootstrap.

Successful source-build artifacts from the same branch may be reused only
after checking provenance, successful source job, checksums and unchanged
relevant build inputs. A change exclusively to the Git recipe can leave another
group unaffected only if its package report proves that Git was not built.
Current validators and source archiving run again; provenance and consumer
commit are recorded in `source-build.json`. Changed build inputs or expired
artifacts trigger a fresh source build. A manual run with `source_run_id=0`
rebuilds everything.

ARM64/Bionic runtime jobs install the real app bootstrap through the Java
initializer. APT then installs local source-built catalog DEBs with dependency
resolution. Checks cover Python with native modules, Node with Crypto/ICU,
npm/npx with offline pack/run steps, Git with commit/fsck and ripgrep with
PCRE2. Each group is then removed and reinstalled through the same local DEBs;
package status and functional evidence are stored as artifacts. Online sources
and insecure APT exceptions are unnecessary.

Encountered CI issues were fixed: complete recipe graph despite subpackages
not offered, source mapping for synthetic `-static` packages, archiving DEB
names with colons, canonical RUNPATH subpaths inside the edition's own `lib`
and distinguishing executable scripts from nonexecutable library templates.
Git no longer supplies a hook template with a missing Perl interpreter or
Python-dependent `git-p4`. The API-28 test container additionally receives
`getloadavg` from the AOSP API-29 implementation; limits/results are checked
before the smokes. This compatibility library remains exclusively a CI artifact
and is installed in neither bootstrap nor APK. The native minimum remains
API 29.

Verification of CI implementation commit
`be4219642e31b1899191fea1bf9ae63bc4c30d2c`:
[Tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37241404970)
with all seven jobs and
[package catalog with ARM64/Bionic runtime checks](https://github.com/Mcpasi/AGENTCODI/actions/runs/37241405136)
with all ten jobs passed. The [full APK build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37238815700)
for `0309a3a32d858dd771f89bfed287833ba128befb` also passed; afterward only
the catalog workflow and test harness changed. The final runtime correction
maps the app cache directory for APT in the container and uses existing,
empty source configurations. CI does not claim independent bit-for-bit
reproducibility of all compiler outputs; source pins and artifact hashes make
inputs/results inspectable. Device tests were skipped at the user's request
and remained open at this milestone. The subsequently implemented signed
APT repository is documented in the next section. No PR, merge or GitHub
APK release was created during this milestone; the later APK release is
recorded above. `main` remains unchanged by edition work.

<a id="signiertes-repository-für-den-startkatalog--2026-10-05"></a>

### Signed repository for the starter catalog — 2026-10-05

The starter catalog with Python, Node.js LTS/npm, Git and ripgrep, together
with bootstrap and its runtime dependencies, has been built, signed, verified
and publicly published as a dedicated ARM64 APT repository at
`https://mcpasi.github.io/AGENTCODI/apt/package-edition`. `repository.json`
pins public primary key `2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89`, seven
days of validity and a 950-MB budget. Private signing uses only Actions secrets
in a temporary GnuPG directory.

The local `agentcodi-package-keyring` package is installed in the bootstrap
and owns the key scoped to this source at
`$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`. An initialized prefix without
that key receives only the manifest-verified public file during APK update;
package data and existing keys remain intact. Subsequent dpkg ownership is
established through `apt install agentcodi-package-keyring`. New Java regressions
check preservation and interrupted/corrupt key migration.

After its existing source/runtime checks, the branch-specific catalog workflow
builds a fully verified combined snapshot: content-addressed DEB pool,
Packages/Packages.gz, by-hash, signed payload/provenance manifest and Release,
InRelease and Release.gpg. Independent builds of shared dependencies may have
different Installed-Size values; all other runtime metadata must match.
The selected DEB retains its actual metadata. Prefix, ELF, interpreter,
dependency and collision checks also cover the entire combined package set.

Complete sources including build dependencies are provided through shared
compressed SHA-256 objects and group-specific source manifests. Files,
permissions, times and links remain intact; hashes of original source archives
are recorded in signed provenance. `download-sources.py` authenticates Release
and all required source objects and reconstructs a complete archive. Regressions
check archive round trips and rejection of corrupt sources. This avoids storing
large identical source archives multiple times, which had increased the first
snapshot to 1.36 GB.

Host APT verifies signatures and downloads runtime packages. An additional
ARM64/Bionic job uses real Android APT/dpkg through an HTTPS test server:
installation, removal and reinstallation of all catalog groups, version checks
and a signed test-package upgrade from 1.0 to 2.0. Invalid keys, signatures,
index/DEB checksums and expired metadata must be rejected. Only after successful
checks does GitHub Pages publish the complete snapshot. Public HTTPS delivery,
current run/commit, signatures, by-hash, catalog DEBs and all referenced source
objects are checked afterward.

Updates preserve the verified previous pool, sources and index hashes.
Downgrades and changes to published package bytes without a version bump
are rejected. The manual workflow with `repository_action=publish` refreshes
the snapshot before the seven days expire. For signature refresh alone,
`source_run_id` is set to the verified catalog run from the latest result
section so unchanged DEBs can be reused; `source_run_id=0` explicitly rebuilds.
`repository_previous_run_id=0` finds the last completed run with a successful
actual Pages deployment, even if subsequent public verification failed.
Key rotation uses overlapping public trust anchors and an increased keyring
version.

The README describes `apt update`, `apt install`, `apt upgrade`, `apt remove`
and `dpkg-query -W`. An additional `pkg` wrapper is optional and was not added
in this step.

Verification of implementation commit
`d9816fac268b1b113019a5cea1bfab5e46056c6a`:
[Tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548510)
passed all seven jobs including 315 Java tests. The
[package catalog](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937)
passed all 14 signing, source-build, bootstrap, runtime and publication jobs.
The signed snapshot comprises 67 packages and complete sources in
648,375,829 bytes; the
[repository artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937/artifacts/11357750277)
and [ARM64/Bionic evidence](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937/artifacts/11357495578)
are available. [APK build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548822)
and [debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548822/artifacts/11356239632)
for `d9816fac268b1b113019a5cea1bfab5e46056c6a` passed. Tests, package/repository
workflow and APK run check the same implementation commit; the final roadmap
change affects documentation only.

**Public publication completed:** The user allowed `Mcpasi/package-edition`
for the `github-pages` environment. The rerun publication job published the
snapshot; its original final verification triggered HTTP 503/429 from Pages
through thousands of parallel individual requests. The cause was fixed:
small sources are additionally offered in at most 16 signed ZIP files. CI
downloads these files, checks every contained source object against its signed
hash and requests only the remaining large sources individually. HTTP requests
are limited to two per second and transient responses handled with backoff.
Complete sources and individual URLs remain available.

An actually published snapshot whose final verification failed is also
recognized as a predecessor and preserved after renewed signature/hash checks.
Corrected catalog run 37336548937 passed completely. The public
[signed Release file](https://mcpasi.github.io/AGENTCODI/apt/package-edition/dists/stable/InRelease)
is accessible over HTTPS. CI checks both pinned Release signatures, validity,
current consumer commit/run, index/by-hash checksums, offered catalog DEBs
and complete sources: 16 signed ZIP files are verified including every
contained object; another 219 large source files are individually checked
for availability and signed size. Starter catalog and public signed repository
are implemented and checked off. Documented APT usage is also checked off;
the next result section records the now-implemented package path/environment
work.

Device tests were skipped as instructed and remained open at this milestone.
No PR, merge or GitHub APK release was created in this step; the later APK
release is recorded above. All access in this historical step used the GitHub
Connector; `main` remained at `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

<a id="gemeinsame-paketumgebung-und-npm-python-pfade--2026-10-05"></a>

### Shared package environment and npm/Python paths — 2026-10-05

Implementation and successful CI verification of this step took place only
on `Mcpasi/package-edition`. The shared process environment and its npm/Python
path prerequisite are implemented; both checklist items are checked off.

A shared native definition supplies the app-server environment and Codex shell
configuration. Codex adds its private session helper directory to `PATH` at
startup. Codex commands and terminal inherit this actually running server path
through `inherit="core"` and an explicit variable list; a static `PATH` override
would differ from stdio-MCP. Other values still come from the shared definition.
The runtime test directly compares actual Codex-shell, terminal and MCP paths
while checking the unchanged package search path. `PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR`
and npm prefix/cache home are additionally checked with a real stdio-MCP server
under ARM64/Bionic. `TERMUX_VERSION=agentcodi-package-edition` activates only
Android environment forwarding in the pinned Community release. No Termux app
is installed and no official Termux binary repository is used. Explicit MCP
server environment overrides remain user configuration.

npm global installations use `$HOME/.local` by default; npm reads normal user
configuration again and uses `$HOME/.npm` as its default cache. Python user site
remains enabled; user packages/scripts also reside under `$HOME/.local`. pip
uses `$HOME/.cache/pip`. venvs retain their own installation paths. The catalog
offers `python-ensurepip-wheels`, already generated from the pinned Python source
archive, for `ensurepip --user` and offline venv creation. At this milestone,
the bundled transitional Python supported user site; venv/pip used the edition
Python installed through APT. The transitional tool was later removed from
the APK as documented in section 4.

npm and the then-bundled transitional npm adjust the usual Node shebang
`/usr/bin/env node` to Android's `/system/bin/env node` when creating executable
links. Remaining script bytes are preserved. The edition npm recipe revision
was raised to `11.20.0-1` so already-published package bytes are not replaced
under the same version.

New runtime checks install and remove purely local npm/Python test packages
without network access. They check global prefix, cache, custom npm configuration,
npx, user site and separate venvs. Source-build artifacts may be reused after
root-selection changes only if unchanged relevant recipes and the complete
producer report prove all requested DEBs; current assembly/runtime checks
run again. npm recipe changes force a new source build for the Node group.

Verified implementation commit: `f1f1ee3ed7e8d918b40e844a4c1729b4a5502a9c`.
[Tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245260)
passed all seven jobs, including 315 Java tests and eight portable C++ suites.
The [APK build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245644)
passed all three jobs; the ARM64/Bionic smoke checks real Codex-shell,
terminal and stdio-MCP processes with identical environment values and the
transitional npm/Python paths. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245644/artifacts/11367960019)
is available.

The [package catalog](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245741)
passed all 14 jobs. These include renewed checks of the new Node/npm source
build, verified Python wheel assembly, real npm/pip installations/removal and
separate venvs under ARM64/Bionic, signed APT publication and its public HTTPS
final verification. The
[signed repository snapshot](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245741/artifacts/11369690652)
and its [ARM64/Bionic evidence](https://github.com/Mcpasi/AGENTCODI/actions/runs/37362245741/artifacts/11370025576)
are available as CI artifacts. The
[new Node/npm source build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37346622005/job/111886746281)
passed as a separate producer job. The current catalog checks its unchanged
relevant build inputs and package checksums, creates current assembly and
repeats all runtime checks. All three implementation records refer to the same
commit `f1f1ee3ed7e8d918b40e844a4c1729b4a5502a9c`. Subsequent roadmap/CI
configuration changes do not alter app code.

GitHub canceled later documentation checks before test startup:
“The job was not acquired by Runner of type hosted even after multiple
attempts”. An additional diagnostic step reads cancellation messages from
the previous commit into the CI log. The unchanged eight portable C++ suites
now use the available `ubuntu-24.04-arm` pool; their driver builds native
fixtures and determines the host library path through the system compiler.
The additional [Tests run](https://github.com/Mcpasi/AGENTCODI/actions/runs/37375227949)
for `da6dae58dcffc513fefcb221828ac2f03cf934ca` passed all seven jobs, including
all eight C++ suites on ARM64. The following completion commit adds only
this roadmap evidence.

Device tests were skipped at the user's request and remained open at this
milestone.
No PR or merge; `main` remains unchanged.

<a id="paketdiagnose-im-terminal--2026-10-05"></a>

### Package diagnostics in the terminal — 2026-10-05

Former activation buttons and fixed APK version displays are replaced by a
“Package diagnostics” button. Every invocation reads the current terminal
environment and queries only the managed `$PREFIX/var/lib/dpkg` database
with `$PREFIX/bin/dpkg-query`. Package name, actual version including
epoch/revision and dpkg status are displayed. Command resolution and package
status are separate: a legacy/APK command does not prove an APT installation;
`config-files` identifies removed packages with remaining configuration.
Missing paths, database or query tool and query failures are reported explicitly.

Diagnostics installs or activates nothing and needs no network access. It
shows only selected package environment variables; account data is not read.
Its output stays in memory like other terminal output. Controls/explanations
are German/English; technical diagnostic headings and dpkg status values
remain English. npm/pip/venv inventories must be queried separately as
documented in the README. At that time, transitional tools and their internal
activation process remained until APK size reduction; the then-current README
described the explicit legacy command. These tools/APIs have since been
removed as recorded in the following result sections.

Five new Java regressions execute the actual diagnostic command with real
`dpkg-query` against isolated test data. They check current paths, managed
versions/status, update/removal with legacy fallback, missing database/tools,
invalid metadata and a missing prefix. Architecture checks now require
diagnostics instead of the old activation display. The Java suite needs `sh`
and `dpkg-query` on the test host; both are present on Ubuntu runners and in
the APK build container and used only with isolated temporary metadata.

Verified implementation commit: `139fb464ee9a2760e0131b6973f6c3a680d591c9`.
[Tests run 37382642660](https://github.com/Mcpasi/AGENTCODI/actions/runs/37382642660)
passed all seven jobs: 320 Java tests, all eight portable C++ suites, Android
sources/resources against API 35, Community archive/ARM64-Bionic checks and
package/toolchain contracts. [APK run 37382642782](https://github.com/Mcpasi/AGENTCODI/actions/runs/37382642782)
passed all three jobs, including bootstrap build, bootstrap smoke and full
APK build with real app-server/PTY/MCP runtime checks. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37382642782/artifacts/11376077909)
contains the separate Package Edition; identity, signature, alignment, ABI
and payload are verified. APK SHA-256:
`13ccd2acd5468d69ec55e99aad507132cf95d9651181a5a482a29a42b06611d6`.

Implementation runs had no failures. Device-dependent linker checks were
skipped with `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1`; physical device,
installation and update tests remained open at this milestone. The completion
commit adds only this evidence and CI test prerequisites; app code remains
unchanged. No PR, merge or APK release was created in this step; the later
APK release is recorded above. `main` remained at
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

<a id="workspace-browser-und-paketdatei-importexport--2026-10-05"></a>

### Workspace browser and package file import/export — 2026-10-05

Implemented only on `Mcpasi/package-edition`. The browser offers separate
workspace, APT packages (`files/usr`) and user packages (`$HOME/.local`)
areas with independent navigation/preview. Package areas are read-only;
they expose neither the entire HOME nor CODEX_HOME. Normal workspace
exports still exclude package prefixes.

Individual files and explicitly selected package folders can be exported
through the Android document dialog. Existing size, file, scan and depth
limits, source-relative no-follow/hard-link/change checks and rollback remain
active. Native access receives the verified absolute root unchanged so a
replaced root symlink is not resolved before `O_NOFOLLOW`. Pending exports
retain their original area. Package ZIP names identify their source.

Known credential paths including `auth.json`, `codex-home`, `.codex`, `.ssh`,
`.npmrc`, `.pypirc`, `.netrc`, `.git-credentials` and APT `auth.conf`/`auth.conf.d`
are blocked for preview/export in package areas, including direct selection.
Links and these paths are omitted and counted during ZIP export. Filename
checks cannot detect secrets copied under arbitrary other names; README and
SECURITY explain this limit and the unchanged Full access.

“Import file” copies a single Android file of any type, including DEB/ZIP,
byte for byte into `workspace/imports`, with a unique random name and safe
extension. The browser displays the exact path. The existing import module
checks temporary read permission, the 512-MiB limit, account-data filenames
and atomic no-replace completion; it saves no URI and installs, extracts or
starts nothing. Terminal use is an explicit user action.

ZIPs are file copies: links, empty folders and executable permissions are
absent; they are not complete package/dpkg recovery backups. Managed packages
are reinstalled through APT. German/English controls, settings texts, README
and SECURITY are aligned with this contract.

Five new Java regressions check root selection, preview/single-file export
of both prefixes, account-data/link exclusions in the actual ZIP, direct/unsafe
paths and replaced root symlinks. An additional import regression checks
DEB/ZIP bytes and extensions without package installation. Verified
implementation revision: `4949dad012634ef403dae1f03fe72cddcb4ccc2e`.
[Tests run 37386821181](https://github.com/Mcpasi/AGENTCODI/actions/runs/37386821181)
passed all seven jobs: 326 Java tests including six new regressions, eight
portable C++ suites, Android sources/resources against API 35, Community
archive/ARM64-Bionic checks and package/toolchain contracts.
[APK run 37386821299](https://github.com/Mcpasi/AGENTCODI/actions/runs/37386821299)
passed all three jobs, including bootstrap build, ARM64/Bionic bootstrap smoke
and full debug APK build with app-server/PTY/MCP runtime checks, identity,
signature and alignment. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37386821299/artifacts/11380222721)
contains the verified separate Package Edition. APK SHA-256:
`4e71d975344a67d53fd073cf0228f5868d7e2e0a04296ad8ad61c6760c7cfb67`.

Final implementation runs had no test failures. Earlier active attempts were
canceled by the existing CI concurrency rule after subsequent branch commits.
Device-dependent linker checks were skipped with
`AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1`; physical device, installation and update
tests remained open at this milestone. The completion commit adds only
documentation and this evidence; verified app code/resources remain unchanged.
No PR, merge or APK release was created in this step; the later APK release
is recorded above. `main` remains unchanged by edition work.

<a id="4-build-verkleinern-und-veröffentlichbare-edition-erstellen"></a>

## 4. Reduce the build and produce a releasable edition

Bootstrap, starter catalog, signed package channel and shared package environment work in CI. npm/Python path prerequisites are complete. Previously bundled user-installable packages have been removed from the APK. The active startup path uses only the native Codex runtime and installed package base. Unused legacy sources, identity constants and activation/transport APIs have been removed. Old private tool directories are no longer created or required for startup; existing user data is preserved. Build dependencies, their restoration and cache selection are reduced. The final verification contract and reconciliation of delivered legal materials are implemented. Community dependency texts and bootstrap license assignments are supplemented and verified. The signed Package Edition APK was published on 2026-10-06; the user reports release APK tests from APT through MCP passed. On 2026-10-07 the user also confirmed successful hardware testing of the current 0.1.1 APK. Both versions remain experimental prereleases. The detailed installation/update/version matrix is tracked separately below. The CI code-mode callback failure was resolved on 2026-10-07; see the [investigation above](#code-mode-sigsegv-ci-fix--2026-10-07).

- [x] Remove bundled Node.js, npm, Python, ripgrep and libraries/archives/licenses needed only by them from the APK.
- [x] First check app-server/code-mode host dependencies on these tools; retain essential base tools in the bootstrap.
- [x] Adapt PackagedToolRuntime, tool alias/activation/ELF attestor code and runtime startup validation to package bootstrap. Retired sources/APIs and fixed tool pins removed; startup requires only the active package contract. Old user data is preserved.
- [x] Reduce build script, Dockerfile, CI input manifest, restore/preflight checks and cache keys to minimal edition dependencies.
- [x] Produce dedicated debug APK artifacts for this branch (`agentcodi-package-debug-apk`); do not overwrite regular main releases. The APK contains no transitional tools; cleanup is complete. The signed release APK has since been published separately.
- [x] Align architecture checks and Java/C++/Android smokes with the final package contract.
- [x] Reconcile notices, README, SECURITY and build documentation with the actual delivered package base. Reconciliation and subsequent license supplementation are implemented; Community Rust/V8 materials and package-local bootstrap assignments are source/artifact-bound. The release license gate rejects any new gap.
- [x] Supplement Community Rust/V8 dependency texts and missing bootstrap license assignments for pinned artifacts; check source/version/hash evidence, app license view and final APK license gate.
- [x] Test the current Package Edition 0.1.1 APK on physical Android hardware. Passed, as confirmed by the user on 2026-10-07; the version remains an experimental prerelease.
- [ ] Perform installation/update tests including low target SDK, foreground service, notifications, login, file selection and backups. The complete matrix is not documented as completed by the broader user report.
- [x] Test the release APK on a device and publish it as Package Edition. The user reports successful release APK tests; `v0.1.0-package.3` is published as an experimental prerelease. The detailed hardware/version/update matrix is tracked separately.

<a id="historische-verifikation-des-grundlagenabschnitts"></a>

## Historical verification of the foundation section

Successful [GitHub Actions run](https://github.com/Mcpasi/AGENTCODI/actions/runs/37154718553) for commit `801f44d82fe5aa4398d12395383a0806bb89ec41`:

- Architecture checks passed.
- 309 Java tests passed.
- All 8 portable C++ suites passed, including actual execution of a user-installed program through the supervisor.
- Android Java sources/resources compiled successfully against API 35; target SDK 28, minimum SDK 29 and edition display name verified.

A terminal shell test additionally covers precedence of user-installed programs over former fixed shell functions.

All repository access/changes in the historical implementation used only the GitHub Connector. Community integration in section 2, minimal package bootstrap and starter catalog CI in section 3 are implemented. The signed package repository is implemented with public HTTPS publication and ARM64/APT runtime tests. Legacy source/API cleanup and build/cache reduction are implemented. Further catalog expansion remains optional. The final architecture/smoke contract and delivered legal-material reconciliation, including subsequent license supplementation, are implemented. The original verification details above describe the preceding foundation milestone. Device tests and APK publication were open then; the later user report and published APK are recorded at the top, with remaining hardware checks in sections 3/4.

<a id="historische-verifikation-der-community-anbindung"></a>

## Historical verification of Community integration

Verified implementation commit: `1fed889377c980f66cdd6eabc0dba2dbcce0b9de`.

- [Tests run 37165001117](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117): all six jobs passed — architecture/manifest, 303 Java tests, eight portable C++ suites, Android compilation, Community archive inspection and real ARM64/Bionic app-server.
- [Runtime inspection artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001117/artifacts/11289216891): generated schemas, complete ELF/archive findings and protocol evidence.
- [APK run 37165001114](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114): full debug APK build including native Full-access, PTY, import-context and transitional-tool smokes passed.
- Device-dependent linker tests were skipped with `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1`; physical installation/hardware tests remained open at that milestone.
- `main` remained at `ff27ec7c30d373a864e845e9a7ceeae3380dd103`. No PR, merge or GitHub APK release was submitted during that step; the later release is recorded above.

The [debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37165001114/artifacts/11289032769) contains `AGENTCODI-Package-0.1.0-package.1-arm64-v8a-debug.apk` (149 MiB; SHA-256 `2ebf610159251aea8caa3766d19ded24399efcd05360f19160aab68833529247`). Signature, alignment, application ID, ABI and supplied runtime were verified successfully. This remains a CI artifact; at that milestone no public GitHub release of the final APK from section 4 had been created. The later signed APK release is recorded above.

<a id="entfernung-der-apk-übergangswerkzeuge--2026-10-05"></a>

## Removal of APK transitional tools — 2026-10-05

Implemented only on `Mcpasi/package-edition`. Node.js, npm, Python and ripgrep,
including libraries needed only by them, Python extensions, npm/Python archives
and license assets, are neither downloaded nor delivered in the APK. The build
input list now has 13 rather than 33 downloads at this milestone. AAPT2
dependencies are exclusively build tools. libc++ remains for native app code;
zlib remains for PNG verification and receives its own complete license asset.

Community ELFs dynamically require only Android system libraries; the
code-mode host contains its own JavaScript runtime. Shell, APT/dpkg,
certificates and dependencies remain in the unchanged edition bootstrap.
APK assembly checks the exact set of six native files, resolves every ELF
dependency against this set or Android system libraries and compares all
delivered native bytes with the verified assembly. Retired tool assets are
explicitly rejected.

The active shell passes commands directly to Android's shell. App-server,
Codex, terminal and stdio-MCP use `files/usr/bin`, then `$HOME/.local/bin`,
then Android system commands; `tool-bin` and former APK version/activation
variables are removed from the process environment. The supervisor starts
without Node/Python/ripgrep ELFs or tool aliases. Java startup no longer
extracts old tool-runtime assets and removes only recognized app-created
aliases, including after a changed APK installation path. Foreign links,
regular files and user packages remain intact. A new Java test checks this
idempotent migration.

Active architecture checks and ARM64/Bionic smokes check the minimal APK
contract, PTY, imports, prefix precedence, persistent user programs, shared
stdio-MCP environment and runtime restart. Real npm/Python package installation
remains covered by the already-documented separate package catalog CI.
README and German/English UI/license texts describe the new payload;
historical notices remain separately preserved as historical provenance.

This historical step removed the APK payload and adapted the necessary active
startup path. Unused PackagedToolRuntime/activation/ELF-attestor source, old
BuildIdentity constants and reserved transport parameters initially remained
for subsequent cleanup. That work is now implemented and described in the
next result section. Docker/preflight/cache reduction and license reconciliation
were separate open items then. Build reduction and delivered legal-material
reconciliation have since been implemented. Complete Community dependency
notices were still required for publication at that milestone; the missing
materials were later supplemented before the published release. Old extracted
runtime data is not automatically deleted.

Verified implementation commit: `fc0f62fc3c36408788fcd8a4403efe56153f13de`.
[Tests run 37389766041](https://github.com/Mcpasi/AGENTCODI/actions/runs/37389766041)
passed all seven jobs: 327 Java tests, eight portable C++ suites, Android
sources/resources against API 35, Community archive/ARM64-Bionic checks and
package/toolchain contracts. The real code-mode host executed JavaScript
with a restricted tool PATH without Node/npm; protocol checks also cover
runtime restart and persistent user programs.
[APK run 37389766895](https://github.com/Mcpasi/AGENTCODI/actions/runs/37389766895)
passed all three jobs: bootstrap build, bootstrap ARM64/Bionic smoke and
full debug APK build with app-server/PTY/import/MCP runtime checks. Identity,
signature, alignment, ARM64 ABI, 16-KiB segments, exact ELF set, dependencies
and delivered native bytes are verified. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37389766895/artifacts/11380802926)
contains the separate Package Edition. APK SHA-256:
`435d7295a53410cf8aafcbe2bc3c168c9fcdf482c8847bd18c448619eba25c30`.
Rounded `du -h` size drops from 169 MiB in the previously documented workspace
browser build (`4949dad012634ef403dae1f03fe72cddcb4ccc2e`) to 123 MiB. This
remains a CI artifact; APK publication and device/installation verification
were not performed in this step. The later signed release is recorded above.

The first implementation run found a missing `LinkOption` import in the new
Java migration test; the same compiler error also blocked the Community runtime
job. The cause was fixed. Final implementation runs have no test failures.
The completion commit adds only documentation; verified app code remains
unchanged. Device tests were skipped at the user's request and remained open
at this milestone. No PR, merge or APK release was created in this step;
the later release is recorded above. `main` remains unchanged by edition work.

<a id="bereinigung-der-alten-tool-runtime-und-start-apis--2026-10-06"></a>

## Cleanup of old tool runtime and startup APIs — 2026-10-06

Implemented and verified in CI only on `Mcpasi/package-edition`.

PackagedToolRuntime, alias creation, activation-marker queries, ToolchainCommand
with fixed APK versions and unused native toolchain/ripgrep-policy/ELF-guard/
attestor/injector code and retired tests are removed. Java, JNI and ProcessConfig
pass only the active native app-server, code-mode host, shell and required
package/private directories. The old JIT parameter is removed from this startup
API; existing launch intents are still migrated to Full access.

New installations no longer create `tool-bin`, `tool-runtime` or
`workspace/toolchain`. Existing archives, activation markers and user files
are preserved but neither interpreted nor checked as startup prerequisites.
The narrowly scoped migration of recognized old APK aliases remains idempotent;
linked or nondirectory legacy roots are ignored without following them.

The package base and its bootstrap/repair contract remain intact. Canonical
executable APK files, separate package/private roots, private image-state data
and Codex configurations are still validated. The managed prefix must also
be separate from the temporary directory. Local test driver and GitHub CI
now build the same active Package shell. Seven portable C++ suites retain
supervisor, PNG, framing, lifecycle and file-access checks. Two additional
Java regressions check preservation of old data and ignoring linked legacy
directories; layout/alias migration tests also check missing legacy directories
and preservation of foreign files. The ARM64/Bionic APK smoke uses the same
reduced startup contract without old tools or activation directories.

README, SECURITY and CI documentation are adapted. Full build/Docker/preflight/
cache reduction, which followed this step, is now implemented. Subsequently
completed delivered legal-material reconciliation and alignment of all
architecture/smoke contracts are described in the next result section.
Complete Community dependency notices were still a prerequisite for APK
publication at this milestone; they were later supplemented before release.
Device tests were skipped at the user's request and remained open then.
No PR, merge or APK release was created in this step; the later release is
recorded above. `main` remains unchanged by edition work.

Verified implementation commit: `28a79dff30d966a5056ac80f5e67476ed9acafd5`.
[Tests run 37392093277](https://github.com/Mcpasi/AGENTCODI/actions/runs/37392093277)
passed all seven jobs: 320 Java tests, all seven current portable C++ suites
(293 engine assertions), Android sources/resources against API 35, Community
archive/ARM64-Bionic checks and package/toolchain contracts.
[APK run 37392093667](https://github.com/Mcpasi/AGENTCODI/actions/runs/37392093667)
passed all three jobs: bootstrap build, bootstrap ARM64/Bionic smoke and full
debug APK build with app-server/PTY/import/MCP checks, runtime restart, prefix
precedence and persistent user programs. Identity, signature, alignment,
ARM64 ABI, 16-KiB segments, exact ELF set, dependencies and delivered native
bytes remain verified. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37392093667/artifacts/11381199454)
contains the separate Package Edition. APK SHA-256:
`0e161e1a9d9ae4b91123cbe6c690376405b61c349db3d8c68fd25e4c894e48b9`.
Rounded APK size remains 123 MiB.

Initial CI found a remaining reference to the removed activation variable in
the Android dialog, a browser test assumption about the previously auto-created
toolchain folder and an incorrectly escaped newline in the new C++ expected
value. All three causes were fixed. The Community runtime job was blocked
by the same Java test assumption. Final implementation runs have no test
failures; the earlier APK attempt was canceled by the existing CI concurrency
rule at the correction commit.

Removed device-dependent guard/attestor tests belong to retired code; the
active CI contract no longer needs `AGENTCODI_SKIP_DEVICE_LINKER_TESTS`.
Physical device/installation/update tests explicitly remained open at this
milestone. The following completion commit adds only this roadmap evidence
and marks the item implemented; verified app code, resources and build
configuration remain unchanged. No PR, merge or APK release was created in
this step; the later release is recorded above. `main` remained at
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

<a id="reduktion-der-apk-build-abhängigkeiten--2026-10-06"></a>

## Reduction of APK build dependencies — 2026-10-06

Implemented only on `Mcpasi/package-edition`. The APK build no longer downloads
or extracts `patchelf` or requires its version check. The unused `llvm-objcopy`
prerequisite, `script` command and unused general ELF relocation helper are
removed. Required targeted Codex-host/zlib relocation remains. Clang, lld
and llvm-strip remain pinned for engine, shell and Bionic smokes.

The generated CI input manifest contains 12 rather than 13 downloads:
Community Codex, Android platform, R8 and required AAPT2/libc++/zlib packages.
The source-built package bootstrap retains its separate verified restoration
path. The input restorer considers only the current manifest; verification
now also fails if manifest regeneration fails. Old/corrupt manifests and
missing/changed bytes are rejected. Five new host regressions check these
cases, restoration of listed inputs only, preservation of foreign cache
files and safe cache-path selection.

The APK workflow uses a dedicated edition cache with OS, architecture and
manifest/cache-selection hash in its key. It contains only the eleven current
non-SDK files, no entire old caches or build outputs; there are no fallback
keys. Bytes are checked again against SHA-256 before use and stored only
after successful manifest/input validation. The Android SDK file remains
exclusively in the existing private mirror or upstream. The Community archive
path remains SHA-256-addressed.

Docker no longer requires Ubuntu gcc/libc6-dev and bsdutils. The final image
copies only the required Termux prefix, without home/cache data, APT package
lists or the temporary reconstructed sysroot DEB. Pinned NDK-r29 headers/CRT
and their Termux patches remain required compile/link inputs and are still
reconstructed from verified sources. Preflight checks Java 17, ARM64,
canonical system shell, executable Android linker and active LLVM version.
A temporary API-29 C++/JNI/zlib probe checks compilation and actual Bionic
execution. Old manual linker/guard/ptrace/seccomp probes are removed.

README and CI build documentation describe the same delivery/build contract.
Historical verification remains tied to its earlier commits; its then-open
build cleanup is now implemented. The subsequently completed architecture/smoke
alignment and delivered legal-material reconciliation are described in the
next result section. Complete Community dependency notices were still a
publication prerequisite at this milestone; they were later supplemented
before release. Physical device tests were skipped at the user's request
and remained open then.

Verified implementation commit: `28c77f3444e1c54b741f3a55513fb2125641f10d`.
[Tests run 37442948418](https://github.com/Mcpasi/AGENTCODI/actions/runs/37442948418)
passed all seven jobs: 320 Java tests, seven portable C++ suites (293 engine
assertions), Android sources/resources against API 35, Community
archive/ARM64-Bionic checks and package/toolchain contracts, including five
new build-input regressions.

[APK run 37442949060](https://github.com/Mcpasi/AGENTCODI/actions/runs/37442949060)
passed all three jobs: bootstrap build, bootstrap ARM64/Bionic smoke and full
debug APK build. The new cache was saved with exactly the selected files;
all twelve build inputs are SHA-256-verified. Container preflight including
the new native probe, app-server/PTY/import/MCP smokes, runtime restart,
prefix precedence and persistent user programs passed. APK identity,
signature, alignment, ARM64 ABI, 16-KiB segments, exact ELF set, dependencies
and delivered native bytes remain verified. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37442949060/artifacts/11402117477)
contains the separate Package Edition. APK SHA-256:
`723f76120cc70f698b3b146f3f51d2dbf753d1a0be167fb6fe18b7a1d0c7a0dd`.
Rounded APK size remains 123 MiB; this step reduces build prerequisites
and container/input/cache data.

Implementation runs had no failures. The completion commit adds only this
roadmap evidence and implementation marker; verified app code, resources
and build configuration remain unchanged. No PR, merge or APK release was
created in this step; the later release is recorded above. `main` remained
at `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

<a id="finaler-testvertrag-und-lizenzabgleich--2026-10-06"></a>

## Final test contract and license reconciliation — 2026-10-06

Implemented only on `Mcpasi/package-edition`. The two non-hardware items in
section 4 are checked off. The
[final test contract](scripts/package-edition/TEST_CONTRACT.md) maps architecture,
Java, seven portable C++, Android compilation and real ARM64/Bionic checks
to the actually delivered package contract.

`apk-contract.json` is the shared definition of the six native ARM64 files
and allowed assets. Architecture checks and APK assembly use the same
contract. The new verifier compares all native files, license assets and
raw license resources byte for byte with verified staging/sources. Additional
ABIs, unknown/old assets, duplicate ZIP files, changed bytes, bootstrap
manifest/license-index/source-evidence errors and premature release builds
are rejected. Fourteen new host regressions check these cases. Existing
ELF, identity, signature, alignment and runtime checks remain intact.

Java checks preservation of the four user-installed command names `node`,
`npm`, `python` and `rg`, their executable permissions, managed dpkg database
and user caches. The native host regression injects old activation variables
into the parent process and requires their removal in the child. The real
APK smoke requires the same cleaned contract for Codex commands, Package
shell and stdio-MCP; existing Full-access/PTY/import/prefix/restart checks
remain active. The code-mode host still needs no external Node/npm.

Bootstrap assembly indexes original license files including package-owned
copyright links to shared texts under `share/LICENSES`. Resolution uses only
verified manifest files/links without reading foreign host paths. Index and
report identify the installed package-owned path, actual ZIP text, size and
SHA-256. Eleven bootstrap assembly regressions also check shared/missing
targets. The current bootstrap comprises 48 packages with 56 concrete license
texts, 106 package-related records and 31 packages with shared license references.

The license view offers the per-package bootstrap index and reads original
texts from the supplied ZIP. It describes the APK bootstrap; packages installed
or updated later retain their own current notices in the prefix. The libc++
build checks the copyright reference against unchanged Termux NCSA source
material recorded in the repository. Complete libc++/libc++abi/libunwind
license texts from a documented LLVM source pin supplement the generic
template. Codex and zlib distributor notices remain unchanged. Host Python
is only a build tool; twelve pinned downloads and minimal APK tool set remain.
Historical sandbox/tool provenance remains in `NOTICE.md`; it is not presented
as the current APK payload.

**Historical license reconciliation result for the commit specified below:**
The delivered set and then-supplied evidence are reconciled. The following
four findings were subsequently resolved in “Supplementing the missing
licenses”. The Community archive itself still lacks complete Rust/V8 dependency
notices for the exact statically linked release build. Also, `bzip2`, `gpgv`
and `xz-utils` then lacked their own package-local license file in the selected
DEB; these findings remain in the historical report. Shared texts and
corresponding sources were available; complete package-specific attribution
was not inferred from them. The public edition keyring metadata package
uses the AGENTCODI Apache-2.0 notice. The historical debug report contains
four license blockers and `final_release_ready=false`; release builds at
that revision failed the license gate. Remaining mappings/evidence were
subsequently supplemented and are checked in the current format-2 report.
The historical four blockers remain traceable in the original report.

Verified code/license-index commit:
`c04fdf3c93a5f0313c1df675416a73582df75e24`.
The subsequent commit `80fc2b0ed134a120a5d6edb5dc254566c8df08d8` corrects only
paths in delivered legal notices and repository documentation; the following
Tests/APK evidence refers to this actual payload revision.

[Tests run 37448382223](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382223)
passed all seven jobs: 320 Java tests, seven portable C++ suites (294 engine
assertions), Android sources/resources against API 35, Community
archive/ARM64-Bionic checks and package/toolchain contracts. Architecture
checks pass including 14 APK contract regressions; eleven bootstrap assembly
and five build-input regressions are also green.

[APK run 37448382664](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664)
passed all three jobs: bootstrap build, bootstrap ARM64/Bionic smoke and full
debug APK build. App-server, Codex/PTY/import/MCP smokes, runtime restart,
prefix precedence and persistent user programs passed. The final APK verifier
confirms exact payload and unchanged delivered license bytes; it reports
four open license blockers as expected for that revision. The
[debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664/artifacts/11404469000)
contains the separate Package Edition; the
[APK contract report](https://github.com/Mcpasi/AGENTCODI/actions/runs/37448382664/artifacts/11404995462)
contains file/license hashes, package/source evidence and publication blockers.
APK SHA-256:
`5f93a77255498029e44e23a29c54ba83938403163dd4f4b983dc55292ad1d2cf`.
Rounded APK size remains 123 MiB.

[Package catalog run 37447449356](https://github.com/Mcpasi/AGENTCODI/actions/runs/37447449356)
for the code/license-index commit passed all 14 jobs: source builds for Git,
Python, ripgrep and Node.js/npm, bootstrap/tool smokes, signed APT build,
ARM64 installation/update checks and public HTTPS verification including
complete source availability. Existing CI automatically publishes the
separate APT repository; that workflow does not create an APK release.
The later GitHub APK release is recorded above.

The first Tests run found an outdated fixture expectation: after adding the
license file, the dpkg MD5 list also contains its checksum. The expected value
was corrected. Shared Termux license collection and copyright links were
supplemented using real package reports; the libc++ license link is now also
resolved against recorded source bytes without an additional download.
Final implementation runs are fully green after these fixes.

Device-dependent installation, parallel operation, APK updates, foreground
service, notifications, login, file selection/backups and physical hardware
linker checks were not performed at the user's request and remained open
at this milestone. Hosted ARM64/Bionic containers do not replace these tests.
No PR, merge or final APK release was created in this step; the later release
and user device-test report are recorded above. `main` remained at
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

The completion commit adds only roadmap evidence/markers. Historical
verification remains bound to the respective specified commits.

<a id="ergänzung-der-fehlenden-lizenzen--2026-10-06"></a>

## Supplementing the missing licenses — 2026-10-06

Implemented only on `Mcpasi/package-edition`. The license item in section 4
is checked off separately from device verification and publication. At this
milestone all physical device/installation/update tests remained open; the
later release and device-test report are recorded above.

Unchanged LICENSE/NOTICE from the Community archive are supplemented by
[source-bound dependency texts](third_party/community-codex/README.md):
1,033 Cargo components in Android normal/build dependencies, Rust standard
library and exact rusty_v8/V8 sources with 20 recursive submodule pins.
The collection contains 669 distinct texts and no unresolved components.
Cargo.lock, target, source revision, original authors, archive/file hashes
and native release hashes are bound. 64 omitted crate files were recovered
from precisely evidenced Git revisions. Another 16 cases use fully written-out
MIT/Apache terms declared in the checksum-verified crate; existing author/
copyright notices remain intact and these supplements are explicitly marked.
Years or copyright holders are not invented. Conservative build/V8 test
sources are included; this does not prove an independently reproduced native
binary.

[License collection 37454140926](https://github.com/Mcpasi/AGENTCODI/actions/runs/37454140926)
passes including four selection/attribution regressions. Regeneration matches
the recorded index/ZIP byte for byte. Ongoing regeneration needs no CI artifact
that expires later. The app offers component/file selection, including readable
display of the unchanged Rust copyright HTML.

`bzip2`, `gpgv` and `xz-utils` receive complete legal files from their parent
packages under their own dpkg-managed paths. libbz2/bzip2 use revision 9,
GnuPG/gpgv and liblzma/xz-utils revision 2. Additional reconciliation found
the incomplete GPL-only declaration of `attr` and the explicit 0BSD assignment
omitted in the previous XZ recipe: attr now names GPL-2.0 and LGPL-2.1;
attr/libacl retain original `doc/COPYING` and `doc/COPYING.LGPL`, GnuPG/gpgv
original `COPYING`. liblzma/xz-utils also retain original `COPYING.0BSD`
alongside the summary and GNU license texts. attr/libacl use revision 1.
APT can thus deliver supplemented files through package updates too.

Savannah was inaccessible over HTTP and HTTPS; checked mirrors did not
provide the exact attr/acl versions. Complete original archives were
[restored](https://github.com/Mcpasi/AGENTCODI/actions/runs/37456334585)
from earlier successful checksum-verified source CI.
[Original sources and provenance](third_party/package-source-archives/README.md)
remain unchanged on this branch; immutable GitHub URLs replace unreliable
retrieval with identical original SHA-256 and versions.
[Archive verification 37456619360](https://github.com/Mcpasi/AGENTCODI/actions/runs/37456619360)
confirms both hashes and original GPL/LGPL files. No binary packages are
introduced as build seeds; twelve APK inputs remain unchanged.

A fresh Python source build also exposed a catalog recipe-selection bug:
`python-ensurepip-wheels` was invoked as an independent source recipe after
successful Python compilation. The build now resolves selected DEBs to
unambiguous parent recipes, builds Python once and retains both runtime
packages in the selection. Four catalog source regressions check source
coverage and parent resolution and reject unknown/ambiguous recipes without
binary-repository fallback.

Verified implementation commit: `7c4ef061ff96841621c30710267a6427435c1640`.
[Tests 37459913151](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913151)
passed all seven jobs: Java, seven portable C++ suites, Android compilation,
Community archive/ARM64-Bionic contract and package/toolchain checks. These
include 20 APK contract regressions, eleven bootstrap assembly and four
catalog source regressions.

[APK 37459913761](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761)
passed all three jobs: fresh bootstrap source build, ARM64/Bionic smoke and
full debug APK build. The new bootstrap comprises 48 packages, 113 package-related
license records and 66 concrete license files without a gap. The final verifier
confirms all native/asset/license bytes, Cargo/source/release bindings and
dpkg ownership. It reports **zero license blockers**, corresponding to
`license_release_ready=true` in the format-2 report. `device_tests` explicitly
remains unperformed in CI.

[Debug APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761/artifacts/11413840550)
and [APK contract report](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761/artifacts/11413935702)
are available. The report records APK, file and license SHA-256 and package/
source evidence. Rounded APK size is 124 MiB. Identity, signature, alignment,
ARM64 ABI, 16-KiB segments and existing runtime checks remain successful.
Six native files and twelve pinned APK inputs remain the active contract.

Additional [package catalog run 37459913693](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913693)
executes fresh source builds for Git, Python, ripgrep and Node.js/npm,
bootstrap/catalog ARM64-Bionic smokes, signed repository assembly and public
HTTPS/source verification for the same implementation commit. Existing
branch CI publishes the separate APT repository only after successful catalog
checks; this does not create an APK release. The later GitHub APK release is
recorded above. Job results and corresponding source/DEB artifacts remain
inspectable in that run.

The final documentation reconciliation for this milestone updated only
`ROADMAP-package-edition.md`, `.github/ci/README.md` and `NOTICE.md`.
The code/payload contract remains bound to the verified implementation commit
above. Physical Android hardware, installation, update and service tests
remained open at the user's request then. No PR, merge or final APK release
was created in this step; the later release and device-test report are recorded
above. `main` remained at `ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

<a id="mcp-tool-freigaben-und-versionsbump--2026-10-06"></a>

## MCP tool approvals and version bump — 2026-10-06

Only `Mcpasi/package-edition`; no PR or merge into `main`.
The user reported successful package installation/use on a device. MCP tool
use failed: `prompt` correctly requested approval, but the client did not
recognize `mcpServer/elicitation/request`. It answered with
`-32601 / Client request is not supported`; the runtime rejected the tool
without displaying a dialog.

The reproduction-only [commit](https://github.com/Mcpasi/AGENTCODI/commit/b3aa230e77e1ee7f00962bfc5ebc05a9d7cf15aa)
and [Tests run 37492914015](https://github.com/Mcpasi/AGENTCODI/actions/runs/37492914015)
demonstrate exactly this rejection in the Java job. The also-failing Community
runtime check uses the same Java suite.

The fix handles message-only form requests with
`codex_approval_kind=mcp_tool_call` as dedicated MCP tool approvals. Unlike
command/file approvals, these requests have no `itemId` or `startedAtMs`;
`turnId` may be null. Thread and any existing turn are checked. The dialog
shows server, request and bounded, redacted parameters in chat, settings and
MCP management. Allow applies once; decline/cancel do not execute the tool.
The response uses `action/content/_meta` with null content/metadata, not
command `decision`. Stale, full-queue or expired requests are closed with
MCP `cancel`. Server-resolved requests cannot be approved afterward. Forms
with additional input fields and URL elicitations explicitly remain unsupported
and are safely rejected.

Five Java regressions check this contract including all three decisions,
null turn, queue limit, invalid requests, server resolution and expiry.
Community CI validates actual generated Java MCP requests/responses against
schemas generated by the pinned ELF. Additionally, a synthetic local HTTP
MCP server with a deterministic model fixture checks the real ARM64/Bionic
`prompt` gate: no invocation before approval, exactly one after allow and
none after decline/cancel. Completed CI/APK evidence for this implementation
revision is recorded below.

Version at this milestone: `0.1.0-package.2`, Android `versionCode 2`.
Manifest, BuildIdentity, build script, identity tests, architecture contract
and then-active documentation use the same revision; earlier artifacts/version
details remain tied to historical commits. Package installation/use is recorded
as a user report without marking the full device/version matrix passed.
Further physical device checks and a hardware retest of the MCP fix were
skipped at the user's request and remained open then. The later user report
confirms release APK tests through MCP, as recorded above; it does not resolve
the separate experimental callback failure below.

The first extended [runtime run](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493948718)
passed Java, schema, commands, PTY and the then-existing code-mode host smoke
(later found to allow a false positive; see the 2026-10-07 diagnosis),
but failed on the additionally forced experimental code-mode callback:
the pinned host reported SIGSEGV before an MCP request was created.
MCP approval verification now uses the native model function call with MCP
namespace used in the pinned upstream test suite, in a separate runtime
process. It still checks the real `prompt` path and actual Java responses;
the existing code-mode host smoke is retained. This does not prove a repair
of the Community host for nested experimental code-mode callbacks. Such
callback/hardware evidence remains outside the MCP approval verification
confirmed here. **This failure remained open at that milestone. It was reproduced and
resolved as a CI Bionic-version mismatch on 2026-10-07, as [recorded above](#code-mode-sigsegv-ci-fix--2026-10-07).**

<a id="verifikation-des-mcp-fixes"></a>

### MCP fix verification

App/version/payload commit:
`0968f19930113e3f62090171e93faa278cf96366`.
The subsequent native MCP test fixture and documentation reconciliation are
on `6ff314b864ff7848d123165db927bccc6d72a4e9`; app, resources, version pins
and APK payload are unchanged by that work.

[Tests run 37494809156](https://github.com/Mcpasi/AGENTCODI/actions/runs/37494809156)
passed all seven jobs: 325 Java tests, all seven portable C++ suites (294
engine assertions), architecture/package/toolchain contracts, Android
sources/resources against API 35, Community archive and real ARM64/Bionic
runtime. Generated schemas validate 418 actual Java RPCs and 15 MCP approval
requests/responses. [Runtime evidence](https://github.com/Mcpasi/AGENTCODI/actions/runs/37494809156/artifacts/11427551406)
records the three real MCP `prompt` decisions: only allow increases the tool
invocation count to one; decline/cancel do not increase it. The separate
existing code-mode host, PTY, import, Full-access and restart contract also
passes.

[APK run 37493949315](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315)
completed successfully: new bootstrap from pinned sources, ARM64/Bionic
bootstrap smoke and debug APK build on the specified app/payload commit.
The build repeats 325 Java tests, C++ host checks including 674 terminal
bootstrap assertions and architecture contracts. The completed APK reports
`de.agentcodi.pkg`, `versionCode 2` and `versionName 0.1.0-package.2`;
signature, alignment, ARM64 ABI and payload/license bytes are verified.
The payload/license verifier reports zero blockers within this verification
contract.

- [APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315/artifacts/11427764122):
  `AGENTCODI-Package-0.1.0-package.2-arm64-v8a-debug.apk`
  (debug-signed, not debuggable).
- APK file SHA-256: `7e929e795da754b21b493c1066110def67a4e6901b0d66159d88dcf3e92269eb`.
- [Payload/license report](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315/artifacts/11428327399).
- [Bootstrap and corresponding sources](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315/artifacts/11428401113).

The [package catalog rebuild 37493949752](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949752),
automatically triggered by the package test contract, was not yet complete
at the time of that record (2026-10-06, 16:45 UTC): signature prerequisites,
bootstrap, its ARM64/Bionic smoke and ripgrep source build passed; Git,
Python and Node source builds were still running. No job had failed at that
time. This historical intermediate status does not prove a completed catalog/
APT publication run and does not change completed Tests/APK evidence above.
Catalog recipe inputs were not changed for the MCP fix.
That run has since completed with a failure; it is not evidence of a
successful catalog/APT publication. This does not change the successful
Tests/APK evidence for the MCP fix recorded above.

The final evidence commit changes only this roadmap and the Package Edition
changelog; app, version pins and build payload remain unchanged. Physical
device checks were skipped/open at this milestone. No PR, merge or final
APK release was created in this step; the later release and user device-test
report are recorded above. `main` remained at
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.

<a id="stabile-debug-signierung-und-versionsbump--2026-10-06"></a>

## Stable debug signing and version bump — 2026-10-06

The user reported an installation/update conflict despite a complete Android
version bump; the build in use at that time had `versionCode 2`. Debug
keystores were generated exclusively on CI runners.

Signature comparison confirms the cause:
[APK run 37459913761](https://github.com/Mcpasi/AGENTCODI/actions/runs/37459913761)
(`0.1.0-package.1` / code 1) reports certificate SHA-256
`3e15a999a522f3c7179ea99b89df80b4e08853819a9417f10d4efdac88bcdc55`;
[APK run 37493949315](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493949315)
(`0.1.0-package.2` / code 2) reports
`66eaf52ce0fe2ff694d15e22b9e22af0cae96c833c36ac28722073d3254a4399`.
`build-debug-apk.sh` generated a random key pair whenever the local keystore
was missing. The explicit CI input cache and uploaded artifacts do not contain
that keystore. Checking the certificate name did not detect the changing key.
The same app ID and a higher version code are insufficient for Android to
update an installation with an incompatible signature.

The [reproduction-only commit](https://github.com/Mcpasi/AGENTCODI/commit/87edf38c52c5ec0a1d62638ec14e02e780ae0e4d)
and [Tests run 37501539976](https://github.com/Mcpasi/AGENTCODI/actions/runs/37501539976)
execute the actual debug-signing block with two AAPT2 APKs in empty,
independent build caches. The Android job fails precisely on the differing
certificate fingerprint; the other six jobs pass.

The fix uses the versioned public AOSP test signer exclusively for development
APKs. [Provenance, license and SHA-256 pins](scripts/debug-signing/README.md)
are inspectable; `sign-debug-apk.py` checks key/certificate bytes and then
the actual signed APK. The stable certificate pin is
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.
Missing/changed material stops the build; no replacement key is generated.
An existing old cache keystore is ignored and preserved. The private test
key remains a public build fixture and is not delivered as an app asset.
It does not authenticate an official release. The release path retains its
external private-keystore configuration and explicitly rejects this public
test certificate.

Five signing regressions check cold builds/higher version code, old cache
keystores, missing material, tampered key/certificate and release rejection.
The new revision is `0.1.0-package.3` / `versionCode 3`. Java identity,
manifest, build script, architecture contract and the three native version
pins omitted in the previous bump are aligned. Historical build/CI records
above remain tied to their original commits/artifacts.

APK CI may reuse the bootstrap successfully built from pinned sources in
run `37493949315`. The existing reusable workflow checks branch, source/build
inputs, successful producer, unexpired artifact, SHA-256 sums and lock;
current APK assembly and its tests still run completely. Package recipes
were not changed for this signing fix.

**Transition for existing installations:** The original private CI key was
not retained and cannot be recovered from an APK or its public certificate.
This fix therefore cannot update an existing randomly signed installation
without that key. Before one-time removal/reinstallation, export all required
data and verify backups. Workspace ZIPs do not automatically contain private
chats, credentials or installed packages; uninstalling deletes private app
data. Later APKs with the stable certificate and higher version code satisfy
the signature prerequisite. Physical installation/update/data-retention checks
were skipped/open at the user's request at this milestone. Completed CI/APK
evidence for this fix is recorded below. Only `Mcpasi/package-edition`, no PR,
no merge into `main`.

The first [extended Tests run 37502585222](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502585222)
already confirms identical debug signers and all four other signing regressions,
but fails on the additionally checked fixture version difference: AAPT2
uses the version from an already-versioned manifest instead of replacing
it solely through CLI options. The fixture now creates its own manifest
with version code increased by one. The test reads both actual APK identities
with `aapt2 dump badging` and requires identical app ID and increasing code.
The correction affects only test/documentation files; app/signer/version/
payload files on `5f0632e4286823b6b52a73282c305545d589f6b5` remain unchanged.

<a id="verifikation-der-stabilen-debug-signierung"></a>

### Stable debug signing verification

App/signer/version/payload commit:
`5f0632e4286823b6b52a73282c305545d589f6b5`.
Corrected test fixture and its documentation are on
`d92f1ac6f09be5b1b6313d096563ecc48b4df69b`; comparing these commits shows
only test/documentation changes, no changed APK payload.

[Tests run 37503097714](https://github.com/Mcpasi/AGENTCODI/actions/runs/37503097714)
passes all seven jobs: 325 Java tests, seven portable C++ suites, architecture/
package/toolchain contracts, Android sources/resources against API 35,
Community release inspection and real ARM64/Bionic runtime. All five new
signing regressions pass. Actual fixture APKs have the same app ID and
`versionCode 3 → 4`; SDK apksigner verifies their signatures and both use
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.

[APK run 37502586391](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502586391)
passes completely: verified bootstrap reuse, ARM64/Bionic smoke and full APK
build. The build repeats all host tests (325 Java tests, including 294 engine
and 674 terminal bootstrap assertions) and architecture contracts. The
completed `de.agentcodi.pkg` APK reports `versionCode 3`,
`versionName 0.1.0-package.3` and actually uses the pinned AOSP test signer
`a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc`.
Signature, alignment, ABI, payload and delivered license bytes are verified;
the payload/license contract reports zero blockers within its scope.

- [APK artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502586391/artifacts/11430427890):
  `AGENTCODI-Package-0.1.0-package.3-arm64-v8a-debug.apk`.
- APK file SHA-256:
  `3816238666301182bae8d53d4534c67a19ee16442580f111ac971e76b2e6e430`.
- [Payload/license report](https://github.com/Mcpasi/AGENTCODI/actions/runs/37502586391/artifacts/11430582818).

The final documentation reconciliation for this milestone changes only
roadmap, changelog, Security Policy and NOTICE provenance; verified app and
payload remain unchanged. Signing fixture and this build do not replace
physical Android update/data-retention checks. Switching from an earlier
random CI identity without the original key remains a one-time transition
through backed-up reinstallation. No PR, merge or final APK release was
created in this step; the later privately signed release is recorded at the
top. `main` remains unchanged at
`ff27ec7c30d373a864e845e9a7ceeae3380dd103`.
