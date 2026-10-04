# Package Edition recipe build contract

This directory pins and builds the minimal ARM64/Bionic package bootstrap for
AGENTCODI. The APK includes its audited ZIP, manifest and build report, and
initializes it before starting the app-server.

## Locked inputs

- Recipes: termux/termux-packages commit
  `b6af76b353140fe17f299248fca1ac13ea91c5c5`
  (2026-10-04); the full Git tree and source timestamp are in `lock.json`.
- Host builder: `ghcr.io/termux/package-builder@sha256:1db92723f6a82fd3ba45288d68ff99dbdeb08a3e9f3f0c4750115178cc7a6879`,
  Linux amd64. The digest was published by the
  [upstream build](https://github.com/termux/termux-packages/actions/runs/35882400849/job/107253886726)
  for `c3326ca2d01cbc89bd3d48fd26f407b9fed1c69c`.
  Its immutable layers pin the complete host toolchain, including LLVM 21,
  JDK 17, build tools and their dependencies. Do not update packages in it.
- Android NDK r30 and command-line SDK 9123335: upstream download URLs and
  SHA-256 values are recorded separately. SDK build-tools version is 37.0.0.
  Preparation checks their hashes against the pinned upstream setup script;
  the container check records the installed NDK and compiler versions.
- Target: aarch64, Bionic, Android API 29, Debian packages. The APK continues
  to target API 28; the compiler's minimum API is a separate setting.
- TLS: all package C/C++ builds use `-femulated-tls`. The pinned hosted Bionic
  image uses Android 9 (API 28), which lacks native ELF TLS; Clang switches to
  native TLS at our API 29 compile baseline. Emutls keeps thread-local storage
  working in this runtime and on API 29+ without lowering the compile baseline.
  The audit rejects native AArch64 TLS relocations in every package ELF.
- App ID: `de.agentcodi.pkg`; rootfs:
  `/data/data/de.agentcodi.pkg/files`; prefix: `files/usr`;
  home: `files/agentcodi/home`. These match WorkspaceLayout. The
  Android primary-user path is the build-time identity; equivalent canonical
  runtime paths remain handled by the app.

## Preparing recipes

Use a **new, clean checkout** at the locked commit and a separate output:

```sh
git clone https://github.com/termux/termux-packages.git /tmp/agentcodi-recipes-source
git -C /tmp/agentcodi-recipes-source checkout b6af76b353140fe17f299248fca1ac13ea91c5c5
python3 scripts/package-edition/prepare.py \
  --source /tmp/agentcodi-recipes-source --output /tmp/agentcodi-recipes
```

Preparation verifies the commit, tree, clean worktree (including ignored
caches), each edited upstream blob, and each exact edit context. It copies
only tracked files and leaves the source unchanged. An existing output is
rejected; prepare another directory instead of reusing a cached build.

`overlay.json` is the versioned, small set of edits. Identity is set in
upstream properties **before** prefix/home derivation. The native toolchain
and patch substitution use those values; upstream script massage emits
prefix shebangs. Debian data, conffiles and bootstrap templates use the
same prefix. The preparation report records lock/overlay and output SHA-256.

In the locked builder, mount the prepared tree at
`/home/builder/termux-packages`, provide writable build storage and run
`./agentcodi-build-package.sh <recipe-name>...`. The launcher and recursive
builds source `agentcodi.env`, including the fixed `SOURCE_DATE_EPOCH`.
Build dependencies from source. `-i`/`-I`, other ABIs/libraries and automatic
binary-repository cycle seeds are rejected. Cycles need explicit, audited
edition seed recipes in the later package-build workflow.

The generated bootstrap plan starts with **dash, APT, dpkg and CA
certificates**, plus their dependency closure. The stock Termux bootstrap
package list and second-stage installer are not the AGENTCODI initializer.
Termux core/exec/tools/API/keyring and root/X11 repositories are unsupported
until separately adapted. Ordinary upstream package names and license
recipes remain unchanged. The minimal bootstrap below now builds and records its runtime dependency
closure, source pins, binary hashes and installation/repair. The broader
package catalog and its independent rebuild checks remain upcoming steps.

## Dedicated repository configuration

Both `repo.json` and the APT recipe use the planned HTTPS endpoint:

```text
https://mcpasi.github.io/AGENTCODI/apt/package-edition
stable main
```

Only this edition source is enabled. APT uses `arch=aarch64` and
`signed-by=$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`; its upstream
Termux keyring dependency is removed. No upstream trusted key, insecure
APT flag or official Termux binary mirror is substituted. The endpoint and
public key are **not published yet**. Until the signed-repository roadmap
step provisions them, updates cannot authenticate this source.

## Verification and updates

The Tests workflow's Package recipe and toolchain contracts job runs
`.github/ci/check-package-edition.sh`. It checks preparation repeatability,
real upstream property derivation, bootstrap template rendering, patch and
shebang substitution, APT configuration and rejected download modes.
Inside the locked builder it cross-compiles an ARM64 ELF executable and
shared library with a thread-local variable, the real upstream linker flags and NDK, creates a DEB
using the real Debian metadata hook twice and compares its SHA-256.

This fixture checks the exported toolchain flags using an already-prepared
toolchain marker; it does not assemble the FUSE-mounted, patched sysroot.
That operation is validated when the full bootstrap is built.

`verify-prefix.py` audits extracted DEB/bootstrap roots: payload paths,
symlinks, embedded foreign prefixes/sources, shebangs, ARM64/ELF64, the
Bionic interpreter, RUNPATH, emulated TLS, conffiles, control scripts and architecture.
It reports per-file SHA-256 and ELF details and never executes payload files.
The CI evidence artifact includes the actual compiler/host inventory,
preparation report, fixture DEB, metadata and SHA-256. This is a build
contract fixture, not a distributable bootstrap. Device tests are skipped.

Update the commit/tree/timestamp, builder digest, toolchain pins and exact
overlay contexts together. Use fresh build storage, rerun CI, inspect
artifact reports and review all affected dependency recipes. Do not infer
bit-for-bit reproducibility of the eventual package catalog from this
fixture: those real package outputs still need their own rebuild checks.


## Minimal bootstrap and recovery

The APK workflow calls the branch-scoped reusable bootstrap workflow. It may reuse
source build 37215181811 while its artifact remains available and every package
source/build input matches that trusted branch push. README, assembly, source-archive
and audit code are excluded from the binary build key; they are rerun on the
verified source-built DEBs. The source archive retains the original package
sources and includes the current assembly/audit scripts.
The source-build job must have passed; all input SHA-256, DEB hashes and the complete lock
are rechecked, and source-build.json records provenance. Changed inputs or expired
artifacts cause a full rebuild. Java, ARM64/Bionic and APK checks still run.
It builds
dash, bash (required by maintainer scripts), APT, dpkg, CA certificates and their
runtime dependencies in fresh storage with the pinned builder. Its FUSE capability
is needed only by the cross-build sysroot; it is unrelated to app permissions.
No official Termux binary dependencies, PRoot or Termux app components are used.

The overlay disables APT manpage/HTML and apt-ftparchive builds, avoiding the
DocBook/Python/X11 build dependency tree. It removes unused dpkg Perl/development subpackages and Java certificate
generation, explicitly builds and installs only GnuPG's gpgv target (including
libksba headers as a build-only dependency) and disables GnuTLS's optional Unbound
integration. libandroid-selinux preserves the edition CFLAGS in its custom
Makefile and validates its Git source commit
`1cbcdf624c248c66cd6153311d3e681ba1f9ff2a`; its clean Git snapshot is retained
in the corresponding-source archive. dpkg's Git tag must resolve to
b2f9600ead232a2dd3c27f8b52807a9ca5854d17. p11-kit's host ASN.1 generator is rebuilt from the checksum-pinned libtasn1
source instead of using a floating Ubuntu package index. Source archive hashes and package recipe
patches remain pinned by the recipe tree. Assembly selects only the runtime
Depends/Pre-Depends closure, validates dependency versions and DT_NEEDED libraries,
and rejects foreign prefixes, architectures, shebangs and escaping links.
Build-only packages are omitted from the installed bootstrap.

The artifact agentcodi-package-bootstrap contains the DEBs, bootstrap ZIP,
BOOTSTRAP-MANIFEST, complete package/ELF report, corresponding-source archive and SHA256SUMS.
The separate agentcodi-package-build-debs artifact retains all source-built DEBs
for diagnosing assembly failures; build-only DEBs are not installed in the app. ZIP order and
timestamps are fixed. This does not claim that every package's compiler output
is independently bit-for-bit reproducible; real rebuild comparison remains part
of the later package-catalog CI step.

PackageBootstrap checks every file's size, SHA-256 and mode while extracting into
a sibling staging directory. It preserves existing nonconflicting prefix files
and refuses collisions. It publishes using two same-filesystem atomic renames.
A crash between renames restores the original prefix before WorkspaceLayout
creates directories. An incomplete extraction is discarded and retried.
After publication, native dpkg --configure -a initializes package scripts and
alternatives. Failure or interruption retains the unpacked prefix and retries
configuration on the next app start; the log is
files/agentcodi/logs/package-bootstrap.log. The ready marker is written only
after successful configuration. Staging and backup directories are then removed.

A ready prefix is never re-extracted or reset on app restart or APK update:
package versions, APT/dpkg state and user modifications persist. HOME,
CODEX_HOME and the legacy HOME/.local prefix are preserved. Do not remove the
ready marker to upgrade a working installation. Bootstrap upgrades need an
explicit package update through the future signed repository.

The hosted ARM64/Bionic smoke installs the real ZIP with the Java initializer,
configures it with the actual Android dpkg, checks shell/APT/gpgv/certificates and APT's registered version against dpkg,
and installs, executes and removes a local fixture package. Host regression
tests cover extraction failure, checksum/ZIP/link rejection, rename recovery,
configuration retry, existing-file preservation and APK-update persistence.
Real Android hardware tests remain skipped.

The signed repository, trust key, pkg frontend and public package catalog are
separate roadmap steps. apt update cannot authenticate the planned source yet.
No insecure repository fallback is configured.
