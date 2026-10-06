# Community Codex dependency license material

This supplement accompanies the unchanged original Community release LICENSE
and NOTICE. It is bound to DioNanos/codex-termux release v0.156.1-termux.1,
source commit ea762071ec4acbf1531fcc7daf47524836f70a09, and the native APK
hashes in [.github/ci/community-codex-release.json](../../.github/ci/community-codex-release.json).
The complete original source Cargo.lock is retained here; SHA-256:
d722f05fc760bcd1f5749ec452452d81058458b788df3b765b80500d757eba4a.

The inventory contains 1,033 Cargo components for the aarch64-linux-android
normal/build dependency closure of codex-cli and codex-code-mode-host, plus
Rust standard-library and rusty_v8/V8 source material. It has 669 unique
legal texts and no unresolved components. Build dependencies are included
because native-source build crates can contribute linked code. Build-only
components and additional V8 test-source notices are retained conservatively;
the list does not assert that every source file is retained in the native ELF.

## Files and provenance

- DEPENDENCY-LICENSE-INDEX.json maps component versions, source/checksum pins,
  supplied authors, original source paths and legal retrieval methods to text
  hashes. Its gaps list is empty.
- DEPENDENCY-LICENSES.zip stores original legal bytes under texts/<sha256>.txt.
  Identical texts are deduplicated. Original LICENSES-directory contents,
  NOTICE and COPYRIGHT files are retained as well as license files.
- DEPENDENCY-PROVENANCE.json binds the inventory and ZIP hashes, Cargo.lock,
  Community release/native hashes, producer evidence and Rust/V8 source pins.
- Cargo.lock is the original source snapshot used by locked Cargo resolution.

The APK includes the index, ZIP and provenance; its license screen provides
component/file selection. Rust's original COPYRIGHT-library.html is retained
in the archive and rendered as readable text in the screen. Text sizes are
checked against the screen's 512-KiB limit; the index has a separate 4-MiB limit.

## Source collection and omitted crate files

[Collection run 37452960760](https://github.com/Mcpasi/AGENTCODI/actions/runs/37452960760)
used Rust 1.95.0, cargo tree --locked --target aarch64-linux-android
--edges normal,build, and cargo metadata --locked. The checked-in lock
remained unchanged. Registry checksums and Git dependency revisions are taken
from that lock. Workspace packages retain the source-root LICENSE and NOTICE.

For 64 crates whose published archives omitted legal files, original
repository-root legal material was recovered at the exact revision recorded
in .cargo_vcs_info.json or the locked Git source. For 16 remaining cases,
the checksum-verified published package explicitly declares MIT or an
MIT/Apache alternative. The collector supplies the complete declared terms,
selecting Apache-2.0 where that alternative is offered. These supplements are
identified by legal_source.selected_spdx, ATTRIBUTION.txt and the selected
license file; they are not described as original publisher license files.
Original copyright statements, authors and available README attribution
are retained. Generic copyright placeholders are removed from the MIT terms;
no author or copyright date is invented. See the pinned
[template provenance](../../.github/ci/license-templates/README.md).
Unsupported declarations remain gaps and cause the license gate to fail.

The V8 static archive is the producer's ptrcomp_sandbox_release Android ARM64
build for rusty_v8 150.4.0. Its archive and bindings hashes are recorded in
DEPENDENCY-PROVENANCE.json and match the Community source build inputs.
[Producer run 33288446010](https://github.com/DioNanos/codex-vl/actions/runs/33288446010)
records rusty_v8 revision 5c15a6995c9bb4bacd3e341b59fff32c909c80bf
and V8 submodule ac1e23989121713ca642f6650b34deff7b686896.
All 20 recursive submodule revisions and their original legal files are
inventoried, including ICU/Unicode, Abseil, fdlibm and strongtalk material.
Rust 1.95.0's original COPYRIGHT-library.html supplies the standard-library
copyright/license material. This is source-bound licensing evidence for the
pinned release; it is not an independent reproduction of its native build.

## Regeneration and validation

The branch-only [collection workflow](../../.github/workflows/community-license-research.yml)
uses the committed, checksum-verified V8/standard-library seed and freshly
resolves the pinned Cargo source. It needs no expiring artifact as input.
It runs four license-selection/attribution regressions and produces an
inspectable artifact; it does not update the repository automatically.

For a fresh V8 collection, initialize the producer's rusty_v8 repository at
5c15a6995c9bb4bacd3e341b59fff32c909c80bf with all recursive submodules,
install Rust 1.95.0 including rust-docs, and run:

```sh
python3 .github/ci/collect-community-licenses.py \
  --codex legal-source/codex --v8 legal-source/rusty_v8 --output legal-evidence
```

The Codex checkout must be the pinned source revision above. GH_TOKEN with
public-repository read access permits exact Git-revision notice recovery.
After regeneration, review source lineage, update provenance and the native
release pins together when changing releases, and verify the actual final APK.
The APK contract checks every ZIP text/hash and lock/component/release binding.
Its format-2 license_release_ready flag concerns licensing prerequisites.
Physical Android tests remain a separate, unperformed release requirement.
