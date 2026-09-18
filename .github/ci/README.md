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
