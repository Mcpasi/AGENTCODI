# Package Edition recipe build contract

This directory pins the inputs for packages rebuilt for AGENTCODI. It does not
install a package manager in the APK. The following roadmap step builds and
initializes the minimal bootstrap.

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
recipes remain unchanged. The full dependency closure, source archive/git
pins for that closure, its binary hashes, installation/repair and package
catalog are the next implementation steps.

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
shared library with the real upstream linker flags and NDK, creates a DEB
using the real Debian metadata hook twice and compares its SHA-256.

This fixture checks the exported toolchain flags using an already-prepared
toolchain marker; it does not assemble the FUSE-mounted, patched sysroot.
That operation is validated when the full bootstrap is built.

`verify-prefix.py` audits extracted DEB/bootstrap roots: payload paths,
symlinks, embedded foreign prefixes/sources, shebangs, ARM64/ELF64, the
Bionic interpreter, RUNPATH, conffiles, control scripts and architecture.
It reports per-file SHA-256 and ELF details and never executes payload files.
The CI evidence artifact includes the actual compiler/host inventory,
preparation report, fixture DEB, metadata and SHA-256. This is a build
contract fixture, not a distributable bootstrap. Device tests are skipped.

Update the commit/tree/timestamp, builder digest, toolchain pins and exact
overlay contexts together. Use fresh build storage, rerun CI, inspect
artifact reports and review all affected dependency recipes. Do not infer
bit-for-bit reproducibility of the eventual package catalog from this
fixture: those real package outputs still need their own rebuild checks.
