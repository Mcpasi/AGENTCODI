# Hosted CI test drivers

These scripts exist so GitHub Actions can run the project's tests on a stock
Ubuntu runner. They are additional entry points only:

* The host-test drivers do not invoke `scripts/test.sh` or
  `scripts/build-debug-apk.sh`. These depend on the Termux Android toolchain
  (`/data/data/com.termux/files/usr/bin/clang++`, `ld.lld`, `llvm-objcopy`,
  `/system/bin/sh`) and remain the authoritative local runners. The APK job
  invokes the build script in the Android-enabled container described below.
* The test sources under `tests/java` and `tests/cpp` are used **unmodified**.
  Nothing here changes how the local suites behave.
* Nothing is written into the working tree. Build output goes to `$RUNNER_TEMP`
  (or `$AGENTCODI_CI_BUILD_DIR` when set).

## Jobs

| Job | Driver | What it runs |
| --- | --- | --- |
| Architecture contracts | `scripts/check-architecture.sh` | Architecture rules, shell syntax, and generated build-input manifest synchronization. |
| Java host tests | `run-java-tests.sh` | The complete Java suite — the same sources and the same `de.agentcodi.tests.TestMain` entry point that `scripts/test.sh` compiles. |
| C++ host tests | `run-cpp-tests.sh` | The portable 8 of the 9 C++ host suites. |
| Package recipe and toolchain contracts | `check-package-edition.sh` | Pinned recipe preparation, prefix and APT contracts, NDK ARM64 cross-compile and repeatable fixture DEB with metadata/ELF audit. |
| Community release inspection | `inspect-community-codex.py` | Verify release, tag, archive, native dependencies and notices. |
| Community ARM64 Bionic runtime | `community-runtime-pins.py`, `verify-community-protocol.py` | Verify binary relocation and generated schemas, validate actual Java fixture RPCs, and run the real Android app-server. |
| Android sources and resources | `compile-android-sources.sh` | Compile against API 35, check SDK pins and Package Edition identity, and resolve every manifest component against its compiled Java class. |

Package Edition builds use installation ID `de.agentcodi.pkg`, their own
`0.1.0-package.1` version line (Android versionCode starts at 1), and APK names
starting with `AGENTCODI-Package-`. The APK workflow on this branch
uploads `agentcodi-package-debug-apk`. Java classes and resources retain the
`de.agentcodi.app` namespace via AAPT2's `--custom-package`; manifest components
use their full Java class names. Host compilation does not replace an APK
installation and parallel-app test on Android hardware.

## Running them locally

```sh
.github/ci/run-java-tests.sh
.github/ci/setup-system-shim.sh   # once, see below
.github/ci/run-cpp-tests.sh
```

`run-cpp-tests.sh` honours `CXX`, `CXXFLAGS` and `LDFLAGS`, so a host whose
zlib headers are not in the default search path can still build the suite.

The Java package-diagnostics regressions require `sh` and `dpkg-query` on `PATH`,
in addition to JDK 17. Both are included in the Ubuntu runners and APK build
container. They execute the actual terminal report against temporary package
metadata, including update/removal, missing tools and invalid-database cases;
they do not read or modify the host's package database. The report uses the
current terminal environment and replaces the old fixed activation display.

On an Android device `setup-system-shim.sh` detects the real `/system/bin/sh`
and exits without touching anything.

## The `/system` shim

`tests/cpp/agentcodi_engine_test.cpp` drives the real app-server supervisor,
which spawns `/system/bin/sh` and validates the native payload read grant
against `/system/lib64`. A hosted runner has neither, which costs several of the engine
assertions, so the workflow creates three paths before the C++ job:

* `/system/bin/sh` — a **copy** of the runner's shell. It must not be a
  symlink: the supervisor resolves the executable with `realpath` and compares
  it against the configured path, so a symlink to `/usr/bin/dash` fails the
  `canonical code-mode host environment` assertion.
* `/system/lib64` — the library grant directory the rejection tests point at.
* `/system/xbin` — part of the reported tool search path.

## What CI does not cover

Two C++ suites cannot run on a hosted x86-64 runner. Both stay local-only:

1. **Toolchain ELF guard/attestor chain** (`tests/cpp/toolchain_elf_guard_test.cpp`
   plus the guard fixtures built in `scripts/test.sh`).
   `modules/native-engine/src/main/cpp/toolchain_elf_attestor_payload.cpp` is a
   freestanding payload written in hand-rolled aarch64 syscall assembly
   (`svc 0`, `x0`/`x8`) whose entry point uses `__attribute__((naked))`. GCC
   rejects that attribute on aarch64, so the payload needs clang, and the
   linked entry segment is then injected into an aarch64 ELF, which an x86-64
   runner cannot execute. Reproducing it would need an `ubuntu-24.04-arm`
   runner with `clang`, `lld` and `llvm` installed; that has not been verified
   and arm64 runners are only free for public repositories.
2. **`tests/cpp/android_app_server_bootstrap_smoke.cpp`.** `scripts/test.sh`
   does not run this either — `scripts/build-debug-apk.sh` drives it against
   the packaged Codex runtime (`libcodex.so`, the packaged host and the
   node/python/ripgrep payload libraries), which are downloaded build products
   rather than repository content.

## Keeping the Java source list in sync

`java-sources.txt` mirrors the `find` block in `scripts/test.sh`. The source list is duplicated, so
`run-java-tests.sh` re-extracts the paths from `scripts/test.sh` on every run
and fails with a diff if the two drift apart. When a module or app file is
added to `scripts/test.sh`, add it to `java-sources.txt` as well.
Set `AGENTCODI_CI_SKIP_SOURCE_SYNC=1` to skip that comparison.

## Pinned build inputs

`scripts/build-debug-apk.sh` verifies every third-party artifact against a
SHA-256 pin before using it. Those artifacts are not in the repository: they
live in the cache directory (`AGENTCODI_CACHE_DIR`, by default
`.cache/android`). Any build host must be handed the identical bytes.

| File | Purpose |
| --- | --- |
| `build-inputs.tsv` | The 33 pinned inputs: path, SHA-256, origin, source URL. |
| `generate-build-inputs.sh` | Regenerates the manifest from the build script. |
| `verify-build-inputs.sh` | Checks a directory against the manifest. |
| `fetch-build-inputs.sh` | Restores the inputs from the mirror, or upstream. |
| `container-preflight.sh` | Checks an environment against the build's requirements. |

```sh
.github/ci/verify-build-inputs.sh                 # checks your cache
.github/ci/verify-build-inputs.sh --check-urls    # also probes upstream
.github/ci/fetch-build-inputs.sh                  # restores what is missing
.github/ci/generate-build-inputs.sh > .github/ci/build-inputs.tsv
```

The manifest is derived from all 33 `download_verified` calls in the build
script, including the content-addressed Community release archive. The explicit
configuration marker includes every destination assignment. Regenerate it when
pins change; architecture CI and `verify-build-inputs.sh` reject drift.
Schemas are generated from the verified binary rather than restored as old
cached inputs.

The build script also pins the LLVM toolchain through `CLANG_TOOLCHAIN_VERSION`
and refuses to build when clang, lld, llvm-objcopy or llvm-strip report a
different version. That toolchain compiles the guard libraries and the ELF
attestor payload, so its generated code is covered by the derived
`*_RUNTIME_SHA256` pins; without the check a silent `pkg upgrade` would surface
much later as an unexplained hash mismatch.

### Why this matters beyond CI

The Community Codex archive is downloaded from the pinned DioNanos GitHub
release, with an optional local override for the same verified bytes. The
Termux package pool removes superseded revisions; some pinned tool URLs may
therefore require the existing private build-input mirror. Run
`--check-urls` for current availability. The Package Edition APK job reads
the existing `0.7.6-preview.1` mirror for unchanged tools and fetches the new
Community archive from its release when absent from that mirror.

### The mirror

The inputs are mirrored to the **private** repository
`Mcpasi/agentcodi-build-inputs`, one release per pin set, tagged with the APK
version from the build script. Assets use their cache basenames, which are
unique across the manifest; the layout and hashes come from `build-inputs.tsv`.

`fetch-build-inputs.sh` takes each missing file from the mirror first and from
its pinned upstream URL otherwise, verifying the manifest hash either way, so
the source never matters for trust. Files already present with a matching hash
are kept.

#### Why the mirror is private

Keeping it private is what allows it to be complete. Two inputs cannot lawfully
be redistributed:

* `platform-35_r02.zip` — Android SDK Licence Agreement, section 3.4: *"you may
  not copy (except for backup purposes), modify, adapt, redistribute,
  decompile, reverse engineer, disassemble, or create derivative works of the
  SDK or any part of the SDK."* A private backup falls under the stated backup
  exception; publishing it would not.
* `patchelf-0.19.1` — the package links its licence to `GPL-3.0.txt`, so
  redistribution would add a corresponding-source obligation.

Further Termux packages carry copyleft notices (`zstd` links `GPL-2.0.txt`,
`termux-licenses` links `GPL-3.0.txt`, `liblzma` ships `COPYING.GPLv2`), and
`aapt2`, `libexpat` and `libffi` ship no licence file at all, so their terms
cannot be established from the artifact. None of that matters for a private
backup; all of it would need clearing before publishing.

Reading the mirror therefore needs an authenticated `gh` — in CI a token secret
with read access, because the default workflow token cannot reach another
repository. The upstream fallback needs no credentials.

When a pin changes, publish a new release for the new pin set instead of
editing the existing one, so old APKs stay reproducible.

## Building the APK on a hosted runner

`.github/workflows/apk.yml` builds the debug APK on an `ubuntu-24.04-arm`
runner, inside the image defined by `.github/ci/Dockerfile`.
`scripts/build-debug-apk.sh` is used directly; everything is steered through
the `AGENTCODI_*` variables it already supports.

The image reproduces the build host, which is a hybrid rather than a Termux
system. Resolving every command in the build script's own `require_command`
list back to its owning package on the host gives 25 Ubuntu packages and no
Termux ones — `zipalign`, `apksigner` and `java` all come from `/usr/bin`. So
the image is:

* **Ubuntu arm64** for the required commands. Termux does not package
  `zipalign` at all, so a pure Termux image cannot complete a build.
* **The Termux prefix** for the pinned LLVM toolchain, installed at the version
  the build script pins and checked again by the build itself. The Termux base
  image is pinned by digest; `ndk-sysroot` 29-3 and `libc++` 29 are pinned with
  LLVM's 21.1.8-3 packages, because upgrading the headers/CRT also changes the
  derived guard hashes even when the Clang version is unchanged.
* **The Android linker and bionic libraries**, copied from
  `termux/termux-docker:aarch64`, which ships them as aosp-libs. Without them
  the packaged `aapt2`, `patchelf`, Python and Codex app-server cannot run —
  they are bionic binaries. On an arm64 runner all of this runs natively,
  without qemu.

The APK workflow builds only on `Mcpasi/package-edition` pushes that change
app, module, script, test or build-input paths. Documentation and Community
audit-driver changes do not restart an unchanged APK build.
Manual runs default to a preflight-only run.
`container-preflight.sh` reads the required command list and the pinned
toolchain version out of the build script — so they cannot drift — and reports
everything the environment is missing in one pass, instead of surfacing it one
failing build at a time. It is green on the build host, which makes it the
reference the container has to match.

It needs a repository secret `AGENTCODI_INPUTS_TOKEN` with read access to the
mirror.

The rolling Termux pool no longer supplies `ndk-sysroot` 29-3.
`restore-ndk-sysroot.sh` reconstructs its headers and link inputs from the
SHA-256-pinned Android NDK r29 archive and the matching upstream Termux recipe
at `e23be59f0cdcb00674821347881182e68a548135`. `ndk-29-inputs.tsv` pins the
20 patches and compatibility headers separately. The reconstructed package is
installed in the pinned Termux stage before compilation. The existing derived
guard/runtime hash checks remain authoritative and reject differing output.

### The device linker checks

Two checks assert that invoking a guarded tool manually through the Android
dynamic linker cannot bypass its ELF guard — one in `scripts/test.sh` (the
whole `toolchain_elf_guard_test` suite) and one in `scripts/build-debug-apk.sh`
(the packaged ripgrep). Both rest on a property of the device's linker: under a
manual invocation `/proc/self/exe` resolves to the linker itself, so the guard
sees a non-canonical entry point and refuses.

A container ships a different AOSP linker and cannot be relied on to reproduce
that. `AGENTCODI_SKIP_DEVICE_LINKER_TESTS=1` therefore opts out of both, and the
APK workflow sets it. Unset — on a device — nothing changes, so the local runs
keep the full contract.

`container-preflight.sh` probes and reports the property, so the container's
actual behaviour is visible rather than assumed.

### Full-access bootstrap

The Edition build uses ordinary Docker process confinement. It adds no
seccomp profile, ptrace capability or AppArmor override. The bootstrap fixture
uses Full access and verifies successful reads and writes of synthetic files
outside the workspace, plus terminal sessions and the packaged
Node/npm/Python/ripgrep tools. The former protected-only probe is removed.

## Community Codex runtime verification (Package Edition)

`community-codex-release.json` fixes `DioNanos/codex-termux` release
`v0.156.1-termux.1`, source commit, archive digest, native ELF hashes,
the APK host-name relocation and both generated schema hashes. These are the
active runtime pins. Both Community jobs run only on
`Mcpasi/package-edition`.

`inspect-community-codex.py` verifies the GitHub asset digest, the resolved
tag and downloaded archive bytes before reading entries. It rejects missing
required files, duplicate paths, traversal, links and special files. It records
each file's size, mode and digest, and checks ARM64 ELF headers, interpreter,
RUNPATH and DT_NEEDED with `readelf`. Dependencies must resolve to bundled
files or Android system libraries. Both executables must use
`/system/bin/linker64` and only `$ORIGIN` search paths. The eight archive
and search-path tests run before the real release inspection.

The original package metadata, LICENSE and NOTICE are included in
`community-codex-release-inspection`. Its README still mentions
`rust-v0.155.0`, while the verified tag and description identify
`rust-v0.156.1`; the discrepancy is reported, not used as a runtime pin.
No npm lifecycle script or Termux-default launcher is executed.

The ARM64 job runs the downloaded native executables in the digest-pinned
Termux/Bionic image. `community-runtime-pins.py` checks the unique
install-context field at byte offset `10568364`, substitutes the equal-length
APK host name `libcodex-codehost.so`, and checks the complete resulting
binary digest. Additional host-name references remain untouched. The job then
generates both schemas with the real ELF and verifies their hashes against
the lock and build script.

`verify-community-protocol.py` validates the actual outbound RPCs emitted
by the Java controller fixtures against those schemas, inventories source
methods, and validates synthetic login and PTY-write requests. The real
app-server checks initialization, permissions, models, account reads,
threads, Full-access commands, PTY operations, MCP configuration/reload and
connector listings. A deterministic local Responses API fixture completes a
turn through the relocated sibling code-mode host. Its JavaScript output must
appear in the follow-up request. A fresh runtime then resumes the persisted
thread and executes the program installed outside the workspace. No OpenAI
credentials or external inference are used.

Reports and generated schemas are uploaded as `community-codex-runtime`;
synthetic runtime state and the downloaded binaries are excluded. Real-device
linker, app installation and Android service lifecycle tests remain separate.

To repeat the static audit:

```sh
python3 .github/ci/test-community-codex-inspection.py
python3 .github/ci/inspect-community-codex.py --output /tmp/agentcodi-community-audit
```

Use a fresh output directory for each audit. The ARM64 workflow defines the
complete runnable runtime/schema sequence. Publishing a final APK still
requires the later roadmap work and its device validation.
