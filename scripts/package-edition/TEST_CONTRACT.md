# Final Package Edition test contract

Only Mcpasi/package-edition is changed. This contract does not authorize a PR,
merge, APK release or APT publication. Minimum API is 29, target SDK is 28,
installation ID is de.agentcodi.pkg, and Full access is the sole runtime mode.

The current release is **Package Edition 0.1.1, Android versionCode 4,
published** as an experimental prerelease. Physical Android device tests
passed on 2026-10-07. CI artifacts are test builds; hardware test results are
recorded separately.

The existing Package Edition prerelease `v0.1.0-package.3` was published on
2026-10-06. Its [release APK](https://github.com/Mcpasi/AGENTCODI/releases/tag/v0.1.0-package.3)
has SHA-256 `028679df0ebeb2f1a5f9d8b373320122cba1778e67e0f198ac877c2771bc25ed`.
Publication does not change the required evidence or turn hosted CI into
hardware validation; this contract itself authorizes no publication action.

apk-contract.json is the shared native/legal payload definition.
verify-apk-contract.py is called by architecture checks and the complete APK
build. The APK contains exactly six ARM64 native files: original engine and
shell bridge, Community Codex app-server and matching code-mode host, libc++,
and zlib. Only the declared third-party assets are accepted; extra ABIs,
retired tools, unexpected assets, duplicate ZIP entries and changed staged
bytes fail verification. The existing ELF dependency, signature, alignment,
identity and native-runtime checks remain required.

| Layer | Required evidence |
| --- | --- |
| Architecture / host contract regressions | Same active C++ suite set in local and hosted drivers; Package shell fixture; exact APK native/asset and legal-byte contract. Twenty-two mutation/acceptance regressions exercise assembled fixture ZIPs; nine MPL-source regressions check complete source coverage and notice preservation. |
| Java host suite | Full-access identity/protocol, bootstrap interruption/recovery and update preservation; managed and user prefixes; all four user tool names, executable permissions, dpkg state and caches; alias retirement; file-browser/import/export checks, including replaced package roots and same-size ZIP member changes with restored mtime; MCP tool approval allow/decline/cancel, nullable turn IDs, stale/overloaded rejection, invalid elicitation, server resolution and timeout. |
| Seven portable C++ suites | Supervisor environment, process lifecycle, framing, PNG and file operations; actual Package shell; prefix precedence; inherited retired activation variables stripped. |
| Android compilation | All sources/resources against API 35, manifest target 28/minimum 29, separate installation identity and compiled component classes; German/English legal UI. |
| Community ARM64/Bionic runtime | Pinned archive/relocation/schemas and actual controller RPCs; real app-server, code-mode JavaScript without external Node/npm, restart and persistent user program; actual Java MCP approval records validated against generated schemas and a real MCP prompt gate with invocation counts for accept/decline/cancel. |
| Bootstrap ARM64/Bionic smoke | Real ZIP installed through Java; dpkg configuration, shell/APT/gpgv, certificates and local package install/remove. |
| Complete APK ARM64/Bionic smoke | Full-access sibling access, Codex commands, PTY operations, import context, managed/legacy precedence and identical stdio-MCP environment; no retired activation/version variables. |
| Package catalog (separate workflow) | Source-built npm/npx, pip/user/venv, Git/ripgrep and install/remove/reinstall/upgrade tests; signed APT and authenticated sources. These tools are not required in the minimal APK smoke. |
| Physical Android devices, skipped here | Android 10 and current Android: installation/parallel apps/update/low-target warning, service/notifications, login, picker/backups, writable-prefix ELF/shebang/library execution, npm/pip/PTY/MCP and hardware linker behavior. |

## License evidence and publication prerequisite

Assembly indexes the original legal files in each selected bootstrap DEB.
bootstrap-report.json includes package versions, ownership, size/SHA-256,
and corresponding-source archive evidence. BOOTSTRAP-LICENSE-INDEX.json exposes
the same files in the app. APK verification checks the entire bootstrap ZIP
against its manifest and the legal index against those bytes and dpkg lists.
Original distributor Codex, libc++ and zlib texts are copied unchanged.
Checked-in LLVM texts supplement the libc++ distributor's generic NCSA
template. Historical sandbox/tool notices stay in repository NOTICE.md.

The APK workflow uploads agentcodi-package-apk-contract, containing exact file
hashes, package legal/source evidence and explicit release blockers.
The original Community archive omits dependency terms; the committed supplement
records its target-specific normal/build Cargo closure, Rust standard-library
notices and exact V8 source/submodules. ZIP/index/provenance and Cargo.lock
are tied to the release/source/native hashes, with original notices preserved
and declared standard terms identified explicitly where publishers omit files.
All twelve MPL components receive complete, unchanged offline sources in the
APK: ten Cargo.lock-bound crate archives and one Git snapshot for both nucleo
components. The source offer, manifests, license files and all delivered source
bytes are verified; saving through the Android document picker remains a
separate hardware check.
The bzip2/gpgv/xz-utils DEBs retain their parent source's legal files under their
own package ownership. The format-2 report's license_release_ready describes
only legal prerequisites, and device_tests records the unperformed hardware
validation. Release builds fail if any component or package has a legal gap.
Debug CI success cannot close device tests or authorize final publication.

Bootstrap copyright links are resolved solely through the audited manifest.
The index records both the package-owned installed path and the actual shared
license file under share/LICENSES (or share/licenses). Shared texts are indexed
for their owning package as well. Dangling, escaping or cyclic legal links fail;
reading arbitrary host paths is not part of this process.

## MCP tool approval scope

Managed MCP server mutations continue to enforce `prompt` and clear per-tool
approval overrides. Message-only `mcp_tool_call` form elicitations use a
dedicated dialog with server/message/redacted parameters. Answers allow once,
decline or cancel, with no persisted approval metadata. Structured forms needing
additional input and URL elicitations are rejected safely. This is distinct
from optional command/file approvals and does not change Full access.

Package installation/use passed on a physical device on 2026-10-06.
At that milestone the Android-version/device matrix and the MCP hardware
retest were still outstanding. Subsequent release APK tests from APT through
MCP passed. The complete version/device/update matrix and new
MPL source-saving feature are not documented as hardware-validated; hosted CI
continues to skip device tests.

The nested code-mode SIGSEGV in runtime run `37493948718` was reproduced
and resolved as an Android 9 CI Bionic / native ELF TLS mismatch on 2026-10-07.
The Community runtime CI image now supplies Android 10 / API 29 Bionic,
matching the app's minimum API. The actual JavaScript result, six isolated
host probes and native/nested MCP accept/decline/cancel checks are required.
All pass in run `37676838798`; binary/schema pins and APK bytes are unchanged.
See [the diagnosis](../../.github/ci/CODE_MODE_SIGSEGV.md). This hosted evidence
does not replace the separate physical device matrix.
