# Hosted CI test drivers

These scripts exist so GitHub Actions can run the project's tests on a stock
Ubuntu runner. They are additional entry points only:

* `scripts/test.sh`, `scripts/build-debug-apk.sh` and the rest of the build
  system are **not** used or modified here. They depend on the Termux Android
  toolchain (`/data/data/com.termux/files/usr/bin/clang++`, `ld.lld`,
  `llvm-objcopy`, `/system/bin/sh`) and remain the authoritative local runners.
* The test sources under `tests/java` and `tests/cpp` are used **unmodified**.
  Nothing here changes how the local suites behave.
* Nothing is written into the working tree. Build output goes to `$RUNNER_TEMP`
  (or `$AGENTCODI_CI_BUILD_DIR` when set).

## Jobs

| Job | Driver | What it runs |
| --- | --- | --- |
| Architecture contracts | `scripts/check-architecture.sh` | The existing script, unchanged. It is pure `rg`/`find`, so it is the one part of `scripts/` that is already portable. |
| Java host tests | `run-java-tests.sh` | The complete Java suite — the same 138 sources and the same `de.agentcodi.tests.TestMain` entry point that `scripts/test.sh` compiles. |
| C++ host tests | `run-cpp-tests.sh` | The portable 8 of the 9 C++ host suites. |

## Running them locally

```sh
.github/ci/run-java-tests.sh
.github/ci/setup-system-shim.sh   # once, see below
.github/ci/run-cpp-tests.sh
```

`run-cpp-tests.sh` honours `CXX`, `CXXFLAGS` and `LDFLAGS`, so a host whose
zlib headers are not in the default search path can still build the suite.

On an Android device `setup-system-shim.sh` detects the real `/system/bin/sh`
and exits without touching anything.

## The `/system` shim

`tests/cpp/agentcodi_engine_test.cpp` drives the real app-server supervisor,
which spawns `/system/bin/sh` and validates the native payload read grant
against `/system/lib64`. A hosted runner has neither, which costs 31 of the 329
engine assertions, so the workflow creates three paths before the C++ job:

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

`java-sources.txt` mirrors the `find` block in `scripts/test.sh`. Because that
script must stay untouched, the list is duplicated rather than shared, so
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
| `build-inputs.tsv` | The 35 pinned inputs: path, SHA-256, origin, source URL. |
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

The manifest is derived from the build script — the 32 `download_verified`
calls plus the three content-addressed Codex files — so it is regenerated,
never hand-edited. `verify-build-inputs.sh` regenerates it on every run and
fails with a diff when the two drift apart.

The build script also pins the LLVM toolchain through `CLANG_TOOLCHAIN_VERSION`
and refuses to build when clang, lld, llvm-objcopy or llvm-strip report a
different version. That toolchain compiles the guard libraries and the ELF
attestor payload, so its generated code is covered by the derived
`*_RUNTIME_SHA256` pins; without the check a silent `pkg upgrade` would surface
much later as an unexplained hash mismatch.

### Why this matters beyond CI

The Codex runtime (`codex/<sha>/package.tgz`, ~108 MB) is a locally built fork
with no registry fallback — the build script says so explicitly: *consume this
exact local artifact without registry fallback*. And the Termux package pool
deletes superseded revisions, so pinned URLs die: six already return 404
(`nodejs-lts`, `npm`, `aapt2`, `libexpat`, `libffi`, `liblzma`). Those bytes
exist only in the cache and its mirror. Run `--check-urls` for the current
picture.

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
`scripts/build-debug-apk.sh` is used unmodified; everything is steered through
the `AGENTCODI_*` variables it already supports.

The image reproduces the build host, which is a hybrid rather than a Termux
system. Resolving every command in the build script's own `require_command`
list back to its owning package on the host gives 25 Ubuntu packages and no
Termux ones — `zipalign`, `apksigner` and `java` all come from `/usr/bin`. So
the image is:

* **Ubuntu arm64** for the required commands. Termux does not package
  `zipalign` at all, so a pure Termux image cannot complete a build.
* **The Termux prefix** for the pinned LLVM toolchain, installed at the version
  the build script pins and checked again by the build itself.
* **The Android linker and bionic libraries**, copied from
  `termux/termux-docker:aarch64`, which ships them as aosp-libs. Without them
  the packaged `aapt2`, `patchelf`, Python and Codex app-server cannot run —
  they are bionic binaries. On an arm64 runner all of this runs natively,
  without qemu.

The workflow is manual and defaults to a preflight-only run.
`container-preflight.sh` reads the required command list and the pinned
toolchain version out of the build script — so they cannot drift — and reports
everything the environment is missing in one pass, instead of surfacing it one
failing build at a time. It is green on the build host, which makes it the
reference the container has to match.

It needs a repository secret `AGENTCODI_INPUTS_TOKEN` with read access to the
mirror.
