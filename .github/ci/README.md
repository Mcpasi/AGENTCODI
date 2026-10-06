# Hosted CI test drivers

These scripts exist so GitHub Actions can run the project's tests on a stock
Ubuntu runner. They are additional entry points only:

* The host-test drivers do not invoke `scripts/test.sh` or
  `scripts/build-debug-apk.sh`. These depend on the Termux Android toolchain
  (`/data/data/com.termux/files/usr/bin/clang++`, `/system/bin/sh`) and remain the authoritative local runners. The APK job
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
| C++ host tests | `run-cpp-tests.sh` | All seven current portable C++ host suites, using the actual Package Edition shell. |
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

The seven current portable C++ suites run on hosted Linux; the retired
ripgrep policy and toolchain activation/guard/attestor suites have been removed
with their unused implementation. The local runner builds the same package
shell and preserves the supervisor, framing, lifecycle, PNG and file tests.

`tests/cpp/android_app_server_bootstrap_smoke.cpp` requires the actual ARM64/Bionic
payload. `scripts/build-debug-apk.sh` runs it in the APK workflow against the
minimal native payload (Codex, matching code-mode host, shell bridge, libc++
and zlib). It receives only the active workspace, account, home, managed prefix,
state, temporary and library paths. It requires no APK tool aliases, extracted
tool runtime or activation markers.

Real Android installation, updates and hardware behavior remain device tests;
hosted CI does not claim to cover them.

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
| `build-inputs.tsv` | The 12 pinned inputs: path, SHA-256, origin, source URL. |
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

The manifest is derived from all 12 `download_verified` calls in the build
script, including the content-addressed Community release archive. The explicit
configuration marker includes every destination assignment. Regenerate it when
pins change; architecture CI and `verify-build-inputs.sh` reject drift.
Schemas are generated from the verified binary rather than restored as old
cached inputs. Build-only patchelf, llvm-objcopy checks and the unused bulk ELF
relocation helper are removed.

The build script also pins the LLVM toolchain through `CLANG_TOOLCHAIN_VERSION`
and refuses to build when clang, lld or llvm-strip report a
different version. That toolchain compiles the JNI engine and minimal shell
bridge. Historical guard/attestor sources and their obsolete regression
fixtures are removed.

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

The Android SDK platform archive remains a private backup under the Android
SDK Licence Agreement, section 3.4. It is restored from the existing private
mirror or its upstream URL and excluded from the GitHub Actions input cache.
The mirror also retains historical inputs for earlier builds; the Edition
restorer reads only the current manifest. The final delivered-payload licence
review remains a separate roadmap item.

Reading the mirror therefore needs an authenticated `gh` — in CI a token secret
with read access, because the default workflow token cannot reach another
repository. The upstream fallback needs no credentials.

When a pin changes, publish a new release for the new pin set instead of
editing the existing one, so old APKs stay reproducible.

### Edition input cache

The APK job caches only the 11 non-SDK paths selected from the current
12-row manifest by `build-input-cache.py`. Its key is
`agentcodi-package-inputs-v1-<OS>-<architecture>-<manifest/selector hash>`;
there are no fallback keys, whole-directory restores, native build outputs
or retired tool archives. Cached files are SHA-256-verified before use and
saved only after manifest synchronization and verification pass.
App-source changes do not change the download key. The Community archive
retains its existing SHA-256-addressed subdirectory. SDK bytes and the
source-built bootstrap use their existing separate restoration paths.

`test-build-inputs.py` covers missing/corrupt inputs, rejected generator
failures, stale manifests, restoration of only listed files, preservation
of unrelated files and cache path validation. Architecture CI runs it and
syntax-checks the active restore/verification/preflight scripts.

## Building the APK on a hosted runner

`.github/workflows/apk.yml` builds the debug APK on an `ubuntu-24.04-arm`
runner, inside the image defined by `.github/ci/Dockerfile`.
`scripts/build-debug-apk.sh` is used directly; everything is steered through
the `AGENTCODI_*` variables it already supports.

The image reproduces the build host, which is a hybrid rather than a Termux
system. Resolving every command in the build script's own `require_command`
list back to its owning package determines the Ubuntu package set;
`zipalign`, `apksigner` and `java` all come from `/usr/bin`. The unused
Ubuntu gcc/libc6-dev and bsdutils requirements are removed. So
the image is:

* **Ubuntu arm64** for the required commands. Termux does not package
  `zipalign` at all, so a pure Termux image cannot complete a build.
* **The Termux prefix** for the pinned LLVM toolchain, installed at the version
  the build script pins and checked again by the build itself. The Termux base
  image is pinned by digest; `ndk-sysroot` 29-3 and `libc++` 29 are pinned with
  LLVM's 21.1.8-3 packages for repeatable native builds. The legacy
  guard/attestor sources and obsolete fixtures are removed.
* **The Android linker and bionic libraries**, copied from
  `termux/termux-docker:aarch64`, which ships them as aosp-libs. Without them
  build-only `aapt2` and the Codex app-server cannot run —
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
reference the container has to match. Preflight now checks Java 17, ARM64,
the canonical shell and executable linker, then compiles and runs a disposable
API-29 C++/JNI/zlib probe. It has no retired guard/manual-linker or process-
confinement probes. The final image copies only the Termux prefix, without
home/cache data, package lists or the temporary reconstructed sysroot DEB.

It needs a repository secret `AGENTCODI_INPUTS_TOKEN` with read access to the
mirror.

The rolling Termux pool no longer supplies `ndk-sysroot` 29-3.
`restore-ndk-sysroot.sh` reconstructs its headers and link inputs from the
SHA-256-pinned Android NDK r29 archive and the matching upstream Termux recipe
at `e23be59f0cdcb00674821347881182e68a548135`. `ndk-29-inputs.tsv` pins the
20 patches and compatibility headers separately. The reconstructed package is
installed in the pinned Termux stage before compilation. The Codex and zlib artifact pins, exact native payload set, ELF dependency
closure and shipped-byte comparisons remain authoritative.

### Device coverage

The old source-only toolchain guard/attestor and packaged-ripgrep linker tests
are removed with the retired implementation; the APK workflow no longer needs
`AGENTCODI_SKIP_DEVICE_LINKER_TESTS`. The disposable native probe in
`container-preflight.sh` checks build prerequisites only. Installation,
updates and runtime checks on actual Android hardware remain outside hosted CI.

### Full-access bootstrap

The Edition build uses ordinary Docker process confinement. It adds no
seccomp profile, ptrace capability or AppArmor override. The bootstrap fixture
uses Full access and verifies successful reads and writes of synthetic files
outside the workspace, plus terminal sessions, imported files, user programs from both writable
prefixes and the shared stdio-MCP environment. No Node/npm/Python/ripgrep
payload is copied into or required to start the APK. Real package-manager
installations remain covered by the separate Package catalog workflow. The former protected-only probe is removed.

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


## Final payload and supplied-license contract

The authoritative [test matrix](../../scripts/package-edition/TEST_CONTRACT.md)
maps all current checks to the actual minimal Package Edition.
Architecture now also requires host Python 3 and invokes
verify-apk-contract.py --check-sources; the hosted and local C++ drivers must
enumerate the same seven active test sources. The twenty APK-contract
regressions use actual fixture ZIP bytes and reject extra ABIs/assets, staged
mutations, bootstrap legal-index/manifest/source drift and premature releases.

The complete APK build uses the same apk-contract.json for its six native
files. Python 3 is a host build tool in Docker, not a bundled Python runtime.
After assembly, the verifier compares all native/assets/legal resources with
their staged or checked-in source bytes, validates every bootstrap file, and
checks its legal file ownership and corresponding-source metadata. It uploads
package-apk-contract.json as agentcodi-package-apk-contract, separate from the
debug APK artifact. Its hashes identify this exact APK and legal delivery.

Original Codex LICENSE/NOTICE, libc++ distributor copyright and zlib copyright
remain unchanged; complete checked-in LLVM notices supplement libc++.
The generated bootstrap index lets the Android legal UI read each original
license file directly from the immutable bundled ZIP. It does not describe
subsequent user installations or package upgrades.

The committed Community archive/index/provenance supplement its original
LICENSE/NOTICE with target-specific Rust normal/build dependency notices,
Rust standard-library and V8 source/submodule texts. The checker validates
Cargo.lock/source/package checksums, legal ZIP entries and the actual native
release hashes. The source research workflow collects this material with read
permissions; it neither auto-commits nor publishes it. Four collector regressions
check license directories, declared alternative selection and retained notices.

The format-2 report uses license_release_ready for this license gate and
explicitly records unperformed device tests. Release builds fail while any
legal gaps exist. Hosted CI does not perform or certify hardware
installation/update/service/picker tests; the separate device prerequisites
remain necessary before publishing a final APK.

Bootstrap copyright links are resolved solely through the audited manifest.
The index records both the package-owned installed path and the actual shared
license file under share/LICENSES (or share/licenses). Shared texts are indexed
for their owning package as well. Dangling, escaping or cyclic legal links fail;
reading arbitrary host paths is not part of this process.

The Savannah attr/acl source archives are retained unchanged at
[third_party/package-source-archives](../../third_party/package-source-archives/README.md).
The edition overlay uses immutable GitHub URLs with the original recipe
SHA-256 values. The branch-only Package source archive checks workflow verifies
both complete archives and their original COPYING material. This changes source
retrieval for the separate package builds; the 12 APK inputs remain unchanged.

The attr recipe records both GPL-2.0 and LGPL-2.1 and retains the original
doc/COPYING and doc/COPYING.LGPL files. libacl retains those original files
as well, and GnuPG/gpgv retain the original COPYING. Affected package revisions
are raised so APT installs the supplemented legal material on updates.

The XZ source summary explicitly identifies liblzma as 0BSD and describes
the additional GPL/LGPL portions of the toolset. Its original COPYING.0BSD is
retained alongside COPYING and the GNU GPL/LGPL texts in liblzma/xz-utils. The liblzma and GnuPG revisions
are 2, distinguishing these original-text payloads from earlier CI builds.

The catalog source driver resolves selected DEBs to unique parent recipes before
building. Python and python-ensurepip-wheels remain selected together; only the
Python source recipe is invoked. Two additional catalog regressions cover parent
deduplication and reject missing/ambiguous recipes without a binary fallback.
