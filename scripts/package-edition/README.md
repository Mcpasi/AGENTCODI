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
closure, source pins, binary hashes and installation/repair. The initial source-built
catalog and its ARM64/Bionic checks are described below; expansion remains a
separate step.

## Dedicated repository configuration

Both `repo.json` and the APT recipe use the dedicated HTTPS endpoint:

```text
https://mcpasi.github.io/AGENTCODI/apt/package-edition
stable main
```

Only this edition source is enabled. APT uses `arch=aarch64` and
`signed-by=$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`; its upstream
Termux keyring dependency is removed. No upstream trusted key, insecure
APT flag or official Termux binary mirror is substituted. The public key is pinned below and installed by the bootstrap. Publication
and its CI/HTTPS evidence are recorded in ROADMAP-package-edition.md.

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
Build-only packages are omitted from the installed bootstrap. dpkg file lists
retain the DEBs' shared ancestor-directory records, so uninstalling a later
package does not try to remove protected app/system parent directories.

The artifact agentcodi-package-bootstrap contains the DEBs, bootstrap ZIP,
BOOTSTRAP-MANIFEST, complete package/ELF report, corresponding-source archive and SHA256SUMS.
The separate agentcodi-package-build-debs artifact retains all source-built DEBs
for diagnosing assembly failures; build-only DEBs are not installed in the app. ZIP order and
timestamps are fixed. This does not claim that every package's compiler output
is independently bit-for-bit reproducible; independent compiler rebuild comparison remains future hardening beyond
the source-build and runtime checks in the catalog CI.

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
explicit package update through the signed repository.

The hosted ARM64/Bionic smoke installs the real ZIP with the Java initializer,
configures it with the actual Android dpkg, checks shell/APT/gpgv/certificates and APT's registered version against dpkg,
and installs, executes and removes a local fixture package. Host regression
tests cover extraction failure, checksum/ZIP/link rejection, rename recovery,
configuration retry, existing-file preservation and APK-update persistence.
The pinned reference image uses Android 9 / API 28. A CI-only library supplies
API-29 reallocarray and getloadavg, adapting the overflow-checked AOSP implementation at
`290c0cb5044b643e5d6cbcb1a5b275541ca3a89e`; allocation, resize, preserved data
and overflow/ENOMEM behavior, plus getloadavg bounds and results, are tested on
ARM64/Bionic before package installation.
It is built with the pinned NDK for API 28, hashed in a separate CI artifact,
and preloaded only in the smoke container. It is not part of the bootstrap or APK;
real Android 10+ provides both functions itself. Package builds remain API 29.
Real Android hardware tests remain skipped.

The scoped trust key and signed repository jobs are described below. The pkg
frontend remains a separate roadmap step. No insecure repository fallback is configured.

## Source-built catalog CI

The branch-only Package catalog workflow builds Python, Node.js LTS plus npm,
Git and ripgrep in four fresh amd64 build containers. Every target dependency
is rebuilt from the locked sources; no official Termux DEBs, dependency caches
or binary cycle seeds are restored. Node.js LTS 24.18.0 is the initial Node
variant. Python 3.14.6, npm 11.20.0, Git 2.56.0 and ripgrep 15.2.0 come from
the same recipe tree. The Python group also ships python-ensurepip-wheels from the same Python source archive. The separate catalog.json overlay makes Python and Git
headless (no Tk, Git GUI or optional Perl/Python integrations), adds Android-compatible Node bin shebangs to npm revision 1, and pins npm's Git tag to
d12b9434dd010b5fb7044c3cc149cdda317813f8. Bootstrap recipes are unaffected.

Pushes can reuse a successful same-branch source job from the producer run pinned in the workflow if
its artifact is still available and its source-build inputs match. A change
limited to Git's or npm's recipe can be ignored for another group only when
that producer's audited package list proves the affected recipe was never built.
A changed root selection additionally requires matching built-DEB hashes and
retained parent-source recipes; missing requested DEBs cause a fresh source build. SHA256SUMS,
trusted producer identity and successful source-job status are checked before
current audits and source packaging run again. source-build.json records
producer and consumer commits. A missing artifact or changed relevant input
starts a fresh source build; manual dispatch with source_run_id=0 always
builds fresh. Builds for different commits can finish independently.

Each agentcodi-catalog-<group> artifact contains the runtime DEBs, dependency
versions, full prefix/ELF audit, hashes, preparation and corresponding sources
for runtime and build-only recipes. all-built-packages.json audits every built
DEB. The bootstrap-shaped ZIP in these artifacts is only a checked combined
layout fixture; it is never embedded in the APK. The APK continues to use
agentcodi-package-bootstrap.

Hosted ARM64/Bionic jobs install the normal app bootstrap through its Java
initializer, configure dpkg, then use APT's local-DEB dependency resolver to
install the catalog without online repositories or authentication exceptions.
They execute Python native modules, offline ensurepip/user/venv installs, Node crypto/ICU, offline npm pack/run/global-bin/npx checks,
Git commit/fsck and ripgrep PCRE2, remove the catalog roots and reinstall them.
Installed versions and command results are uploaded as runtime evidence.
Physical-device tests remain skipped. The full APK smoke separately exercises the
same PATH/PREFIX/LD_LIBRARY_PATH/HOME/TMPDIR contract through the real
Community app-server, Codex and terminal shells, and a local stdio MCP server.
The signed publication workflow below adds repository authentication and
lifecycle checks.

Pushes affecting package sources or CI start the workflow automatically on
Mcpasi/package-edition; workflow_dispatch is also available on that branch.
Build failures retain produced DEBs and preparation for diagnosis. New catalog
groups must have pinned recipes, a closed dependency graph, prefix/ELF evidence
and a Bionic lifecycle smoke before publication. These jobs do not assert
bit-for-bit identity between independent compiler builds; the fixed build
contract and artifact hashes make inputs and results inspectable.

## Signed APT repository and trust

Initial commissioning status (2026-10-05): the [catalog run](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937)
passes all fourteen signing/build/runtime/publication jobs, including real ARM64/Bionic
HTTPS installation, removal, reinstallation, upgrade and rejection tests.
Its [signed site artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548937/artifacts/11357750277)
contains 67 packages and complete sources in 648,375,829 bytes.
The [commissioning host/source tests](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548510)
and [commissioning APK build](https://github.com/Mcpasi/AGENTCODI/actions/runs/37336548822)
also pass.

Publication succeeds after the owner allowed
`Mcpasi/package-edition` in `github-pages` and the HTTPS checker was adjusted
to avoid flooding Pages with individual source requests. The
[public signed Release](https://mcpasi.github.io/AGENTCODI/apt/package-edition/dists/stable/InRelease)
and the pinned HTTPS checks confirm the deployed consumer commit/run,
Release signatures and expiry, index/by-hash checksums, offered root DEBs
and all referenced source objects. The repository roadmap item is complete.

The pinned `lock.json` retains the original producer's source-build contract,
including its historical `publication_status=planned` value, so audited
producer inputs stay comparable. Current publication is tracked by successful
repository workflow runs and the roadmap.

The edition's committed public key is `keys/agentcodi-package.asc`, with
primary fingerprint `2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89`.
`repository.json` pins exact public trust anchors, the active signer,
keyring version, seven-day Release validity and a 950 MB site budget.
The bootstrap includes the locally generated `agentcodi-package-keyring`
DEB, whose scoped binary key is owned by dpkg at
`$PREFIX/etc/apt/keyrings/agentcodi-package.gpg`.
No global trust anchor or insecure APT option is added.

An older configured prefix missing this key receives only that public file
from the APK ZIP, checked against its packaged size and SHA-256 manifest.
Existing keys and package/user data are preserved. An interrupted or corrupt
key extraction publishes no partial key and can be retried. For earlier
installations, `apt install agentcodi-package-keyring` registers ownership
with dpkg; fresh installations already own it.

Repository Actions secrets `AGENTCODI_APT_SIGNING_KEY` and
`AGENTCODI_APT_SIGNING_PASSPHRASE` hold private material. Jobs import
through stdin into a temporary private GnuPG home and remove it on exit.
They verify the configured primary fingerprint and signing capability before
dependent builds. Neither secret is included in artifacts.

The branch-only `Package catalog` workflow builds and runs all bootstrap
and catalog checks before calling `apt-repository.yml`. Edition pushes
affecting package scripts, repository CI or its workflow build and publish
a signed snapshot. A manual `repository_action=none` keeps the
catalog-only path; `build` retains a signed candidate and tests its runtime;
`publish` also deploys it. No branch, PR, merge or release is created.

Each artifact's lock, catalog configuration, metadata, DEB hashes and source
archive are verified. Shared dependencies must have identical runtime metadata (Installed-Size can differ);
independent rebuild hashes are recorded, and bootstrap bytes take precedence
followed by sorted catalog groups. The combined runtime closure is rechecked
for dependency versions, prefix/ELF/interpreter contracts and file collisions.
This does not assert bitwise identity between independent compiler builds.

Snapshots contain an immutable, content-addressed `pool/main`,
complete corresponding sources, a signed payload manifest, `Packages`,
`Packages.gz`, index `by-hash`, `Release`, `InRelease` and
`Release.gpg`. Both signatures and all signed payload hashes are verified
before upload. Source manifests preserve every original tar member, file mode,
timestamp, link and recipe; identical file contents across groups share compressed
SHA-256 objects. This keeps complete runtime and build-dependency sources inside
the Pages budget. The original input archive hashes remain in signed provenance.
Source storage is also preserved across publications. Small objects have
deterministic, signed ZIP packs in at most sixteen groups, alongside their
individual URLs. The downloader verifies each pack and the compressed object
before checking its uncompressed content. The public check reads and hashes
every packed object, then checks the remaining large payloads' availability and signed sizes individually.
Requests are serial and limited to two per second; transient Pages responses
use backoff and respect numeric Retry-After values. Integrity checks remain
complete without requesting thousands of small files separately.

Use a checkout of these scripts and the committed public key to reconstruct
a group's complete authenticated source archive:

```sh
python3 scripts/package-edition/download-sources.py \
  --group python --output python-corresponding-sources.tar.xz
```

Groups are `bootstrap`, `python`, `node`, `git`, `ripgrep` and `keyring`.
The downloader verifies both Release signatures with the pinned public key,
then the signed manifest and each compressed and uncompressed source hash.
It writes an archive without extracting its contents or overwriting an output.
Round-trip tests check data, rights and links and reject corrupted objects.
The reconstructed logical archive is complete; its compressed bytes need not
equal the original input archive.

Host APT authenticates and downloads the runtime closure
without executing ARM64 binaries and rejects a missing trust key.

The separate ARM64/Bionic repository job installs the real bootstrap through
the Java initializer. It authenticates a local HTTPS test server with an
explicit fixture CA and the pinned APT signing key, installs/removes/reinstalls
Python, Node/npm, Git and ripgrep from signed indexes, and checks versions.
Separate unpublished signed fixtures test installation, upgrade and removal
and rejection of invalid signatures, index hashes, DEB hashes, missing trust
and expired metadata. This uses actual Android APT/dpkg and native package
execution; physical-device tests remain skipped.

Pages deployment follows these checks and a successful current Tests workflow.
The branch-scoped `APT publication diagnostics` workflow reads failed
publication check annotations when GitHub rejects the job before allocating
a runner, so no job log is available. It has read-only permissions and
accepts the affected catalog run ID.

GitHub Pages must use **GitHub Actions** and the `github-pages` environment
must allow this branch. The deploy job has `pages: write` and
`id-token: write`. Its complete site artifact contains `apt/package-edition`
and replaces the existing AGENTCODI Pages site; incorporate any other site
content before enabling publication. No `gh-pages` branch is used.

After deployment, the public HTTPS endpoint is checked against the committed
key, Release expiry, current run/commit, metadata/by-hash checksums, offered
root DEBs and corresponding-source availability. The
`agentcodi-apt-repository` artifact retains the exact site for 90 days.

For updates, `repository_previous_run_id=0` discovers the last completed
edition run whose actual Pages deployment step succeeded; a positive value selects that published run explicitly.
Branch/repository identity and the successful Pages deployment step are checked.
A failure in the subsequent public check does not undo that deployment, so
such a snapshot is also preserved by the next update. The
previous artifact's signatures and signed payload hashes are verified before
preserving old pool, sources and index hashes. Downgrades and changing published
bytes without a version bump are rejected. If the artifact expires, restore a
verified complete previous snapshot before proceeding.

Refresh and publish before the seven-day Release expiry even when package
versions are unchanged. The manual workflow is the renewal mechanism on this
branch. Select `repository_action=publish` and set `source_run_id` to the
previous successful catalog run linked from `ROADMAP-package-edition.md` to reuse its
verified source-built DEBs when inputs are unchanged. Keep
`repository_previous_run_id=0` for automatic snapshot selection.
`source_run_id=0` deliberately performs fresh compiler builds; changing
published binary bytes requires new package versions. Signing-key transitions require overlapping old/new public anchors,
a bumped `keyring_version` distributed under the still-trusted signer,
and only then switching the active signing key. Old installed keyrings remain
valid during that transition. Growth beyond the 950 MB budget fails the build
rather than deleting required sources or client-visible packages; review
retention and hosting explicitly when that limit is reached.

Trusted runner entrypoints:

```sh
bash scripts/package-edition/build-apt-repository.sh \
  repository-input site/apt/package-edition
bash scripts/package-edition/check-apt-repository.sh site/apt/package-edition
python3 scripts/package-edition/apt-repository.py verify \
  --root site/apt/package-edition \
  --keyring site/apt/package-edition/keys/agentcodi-package.gpg
```

`repository-input` contains `agentcodi-package-bootstrap` and the four
`agentcodi-catalog-<group>` artifact directories. Add a third
previous-snapshot argument for an update. Host jobs require Python 3.11+,
GnuPG/gpgv, dpkg/dpkg-deb, readelf and APT.
