<div align="center">

# AGENTCODI

### Codex workflows, native on Android.

**Run the Codex app-server, workspace, approvals, terminal and supported development toolchains directly on your Android device.**

<br>

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Codex](https://img.shields.io/badge/Codex%20app--server-0.148.1-111111)
![Source](https://img.shields.io/badge/Source-Java%20%2B%20C%2B%2B-00599C)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

  [Releases](../../releases) . [Issues](../../issues)
<br>

**No Termux required to use AGENTCODI. No WebView shell. No separate gateway setup.**

</div>

---

## What is AGENTCODI?

AGENTCODI brings the Codex development workflow directly to Android.

The application starts a pinned Codex app-server on the Android device, supervises it through a native C++ runtime and connects it to a native Java interface for conversations, approvals, workspace operations, settings, MCP management and terminal access.

The workspace and development environment live inside the Android application environment.

AGENTCODI is built around one simple idea:

> **Your Android device can be the machine where the Codex workflow actually runs.**

The language model itself is not bundled with the APK. Codex requests still require an internet connection and valid OpenAI authentication.

---

## What runs on the device?

AGENTCODI does more than display a Codex conversation.

| Component | On-device implementation |
|---|---|
| Codex app-server | Pinned runtime started locally by AGENTCODI |
| Process supervision | Native C++ supervisor |
| App interface | Native Android Java |
| Runtime service | Local Android foreground service |
| Workspace | Private application storage |
| File import | Android document picker into a bounded private workspace copy |
| Codex home | Private and separated from the workspace |
| Account quotas | Read-only primary and secondary ChatGPT quota windows from Codex |
| Terminal | Interactive workspace terminal through the Codex runtime |
| Node.js | Packaged runtime |
| npm | Packaged runtime |
| Python | Packaged runtime |
| ripgrep | Packaged no-PCRE2 runtime exposed as `rg` |
| MCP management | Native Android interface backed by Codex configuration RPCs |

The current AGENTCODI 0.5.16 runtime uses **Codex app-server 0.148.1** together with the matching code-mode host from the same pinned artifact.

---

## Codex workflow

AGENTCODI exposes the parts of the Codex app-server workflow that matter during real development.

### Conversations

- Start new Codex threads
- Resume existing threads
- Recover thread history
- Stream responses live
- Import external documents directly from the chat composer
- Correct or add guidance to an active turn without starting a new turn
- Interrupt active turns
- Restart the local runtime explicitly

While a turn is running, the composer switches to **Add guidance** and sends a correlated
`turn/steer` request to that same turn. The separate **Stop** action remains available.

### Live activity

AGENTCODI renders dedicated native cards for:

- Assistant messages
- Reasoning summaries
- Reasoning activity
- Plans
- Command execution
- Terminal interaction
- File changes
- Tool activity
- MCP tool progress

### Models and reasoning

Available models and reasoning levels are read from the active Codex runtime.

You can change:

- Model
- Reasoning effort

Selections are validated against the options reported by the app-server.

### Authentication

AGENTCODI supports:

- ChatGPT account sign-in through the Codex account flow
- OpenAI API key authentication
- Read-only ChatGPT quota usage, window duration and reset time reported by Codex
- Logout and account, quota and model refresh

Authentication controls are kept in the settings surface.

---

## Native approvals and user input

Codex remains interactive on Android.

AGENTCODI handles native requests for:

- Command execution approval
- File change approval
- Tool input
- User questions
- Supported policy amendments

Requests are shown through native Android dialogs and have bounded lifetimes.

File changes, command execution and other interactive events stay visible as part of the active Codex session.

---

## Workspace and exports

AGENTCODI creates its own private workspace inside application storage.

The storage layer keeps separate areas for:

- Workspace
- Toolchain state
- Tool binaries
- Packaged tool runtime
- Application state
- Logs
- Home directory
- Codex home

Workspace and Codex home are deliberately separated.

The application also validates canonical paths and rejects unsafe symbolic-link and hard-link boundaries.

### Import

The chat composer can import files selected through Android's system document picker. AGENTCODI requires the returned result intent itself to carry `FLAG_GRANT_READ_URI_PERMISSION`; requesting that flag when opening the picker or having some other way to read a `content:` URI is not accepted as proof. The UI rejects a result without the transient read grant before collecting its URIs, and the Runtime import facade verifies the same immutable, URI-free grant projection again before reading provider metadata or opening a stream. AGENTCODI then copies each selected document byte-for-byte into the private `workspace/imports` directory and attaches only that verified workspace copy. Single and multiple selection remain supported.

For both a new `turn/start` and attachment-only or text-backed `turn/steer`, the pinned app-server's native `mention` input preserves the visible attachment in user history while its native `additionalContext` field gives Codex the exact verified workspace path and requires it to read the actual file bytes with the existing workspace tools. No file is represented to the model by its display name alone, and the application creates no upload protocol, second process, listener, JNI gateway or filesystem root.

One message can attach at most 16 imported files, with a 512 MiB per-file limit and a 1 GiB combined limit. Pending and final files use random tokens plus at most a short strictly alphanumeric extension, owner-only permissions, bounded copying, descriptor-relative no-follow operations, cleanup after failure, and the same stable regular-file/link checks used by workspace export. Since 0.5.10, the completed pending file is installed through the existing JNI gateway by a C++ `renameat2(RENAME_NOREPLACE)` operation; there is no separate missing-target check, and a parallel creator wins without its bytes being replaced. In 0.5.11, ownership of the transient provider stream moves into the pure import client: the stream and both secure directory handles are always asked to close, but a close error after the final file has already been installed and fully verified can no longer revoke the returned import and leave it invisible to the composer. Before that commit boundary, the original failure remains authoritative and close failures are retained as suppressed context. Runtime startup and every serialized materialization also remove only exact regular `.pending-` plus 32-lowercase-hex entries through the held no-follow imports handle; malformed, symbolic, committed, and unrelated names are never interpreted as abandoned imports. The user-controlled display-name stem is therefore not part of the model-readable path. A transient SHA-256 binding is calculated while copying. In 0.5.9 the UI began passing only a closeable one-shot transaction across its hand-off queues; the serial Core operation consumes it by opening and fully hashing the complete selected batch inside the same synchronous scope that constructs and sends the request. Every no-follow handle remains open, the whole batch is checked after the last hash, and a one-time guard checks it again inside the JSONL transport write lock immediately before the request bytes are written. Handles close only after the correlated request returns or fails. Equal-length replacement before this scope, while a later attachment is checked, or during request preparation therefore fails closed before transport. Obvious credential filenames are rejected before copying. Provider URIs, grants, display labels and pending digest state never enter the model context; external storage never becomes a runtime workspace root or sandbox exception.

Detaching a file removes it only from the pending message. The private copy remains a normal workspace file so a later Codex turn can work with it and the existing explicit workspace export can retrieve it.

### Export

Supported workspace exports include:

- Individual files
- Images
- Bounded ZIP archives

Export paths and archive contents pass through dedicated validation before leaving the private workspace. Since version 0.5.3, each exported file is read from a descriptor opened component by component relative to the private workspace without following links, then that same descriptor and its workspace name are verified again. A concurrent path, symlink, parent-directory or hard-link exchange therefore fails without redirecting reads outside the workspace. ZIP correlation compares Java and native modification times at their common microsecond precision, while the native descriptor still validates its full nanosecond `mtime`/`ctime` snapshot and the Java catalog remains exactly checked before and after the archive.

Version 0.5.16 keeps the ZIP limit at 2,048 regular files and gives catalog traversal its own finite 65,536-entry limit. Directories and omitted symbolic links therefore cannot consume the regular-file allowance, while symbolic links are still never followed or exported and hard links, special entries, unsafe paths, races, file sizes and total archive size remain fail-closed.

Version 0.5.4 introduced complete PNG validation before native materialization and again before workspace image export or resumed-history reuse. Validation covers the complete chunk boundaries and ordering, CRCs, `IHDR` dimensions and format fields, palette rules, the bounded zlib stream, the exact interlaced or non-interlaced scanline shape and filter bytes, and a final `IEND` with no trailing data. A PNG signature followed by arbitrary bytes is rejected rather than materialized or offered for export.

Version 0.5.5 additionally binds resumed generated images to an owner-only SHA-256 materialization proof stored outside the writable workspace and outside `CODEX_HOME`. A valid PNG that replaced the originally materialized bytes is rejected even when its filename and current metadata still look valid. If no proven materialization exists, the app-server's reported `savedPath` is removed before the event reaches Java, so it cannot become an export candidate. Existing 0.5.4 workspace images are not retroactively trusted without fresh inline bytes. JPEG, GIF and WebP export support is unchanged.

---

## Integrated terminal

AGENTCODI includes an interactive terminal for the active workspace.

The terminal is started through the Codex app-server command interface and uses AGENTCODI's workspace permission profile.

It supports:

- Interactive TTY sessions
- Keyboard input
- Terminal resizing
- stdout and stderr streaming
- Explicit termination
- Bounded output capture

Terminal input and output are subject to size and protocol limits.

---

## Packaged development toolchains

AGENTCODI currently packages:

| Tool | Version |
|---|---:|
| Node.js | 24.18.0 |
| npm | 11.19.0 |
| Python | 3.14.6 |
| ripgrep | 15.2.0, without PCRE2 |

The toolchains are prepared inside the private AGENTCODI environment and remain inactive until explicitly enabled.

AGENTCODI validates its packaged tool aliases and runtime files before making them available to the workspace.

Python includes SQLite-backed `dbm` and `shelve` support as well as PyREPL.

ripgrep is a SHA-256-pinned Android ARM64 ELF bundled in the APK. The private
`rg` bridge clears external ripgrep configuration and rejects `--pre`,
`--search-zip`, `--follow` and their short forms before execution; it is never
downloaded or installed at runtime.

---

## MCP and Codex capabilities

AGENTCODI can inspect the capability catalog exposed by the active Codex runtime.

The native MCP and capabilities screen can show:

- MCP servers
- MCP tools
- Runtime features
- Skills
- Installed apps
- Experimental plugin marketplace inventory

This catalog remains a read-only projection of what Codex reports.

---

## MCP Expert Mode

AGENTCODI also includes a guarded Expert Mode for the supported part of the user's MCP configuration.

Supported user-owned servers can be managed through the native interface.

### Supported configuration

- Local stdio servers
- Remote HTTPS servers
- Command arguments
- Startup timeout
- Tool timeout
- Enabled tool lists
- Disabled tool lists
- Enable and disable state
- Delete
- Explicit reload

New server definitions are created disabled and can be reviewed before activation.

When AGENTCODI enables a supported MCP server, its tools remain behind prompt approval.

### Deliberate boundaries

The MCP editor does not expose credential-bearing configuration.

The following remain outside the editable surface:

- Tokens
- Passwords
- Environment variables
- HTTP authentication headers
- OAuth credentials
- Direct `config.toml` access

Project, managed, session, mixed and unknown configuration layers remain view-only.

Remote MCP URLs accepted by the editor use HTTPS and pass through a dedicated validation boundary.

---

## Architecture

AGENTCODI keeps the Android UI, protocol handling and process ownership separated.

```text
Native Android UI
       |
       v
AgentRuntimeService
       |
       v
CodexSessionController
       |
       v
NativeAppServerTransport
       |
      JNI
       |
       v
C++ Process Supervisor
       |
       v
Pinned Codex app-server
       |
       +---- Private workspace
       +---- Codex home
       +---- Interactive terminal
       +---- Node.js / npm / Python / ripgrep
       +---- MCP / Skills / Tools
```

Java does not directly spawn the runtime processes.

Child-process ownership stays behind the native C++ supervisor.

The JNI boundary is concentrated in the runtime gateway instead of being spread throughout the application.

---

## Security boundaries

AGENTCODI treats runtime boundaries as part of the application architecture.

Current safeguards include:

- Non-debuggable APK configuration
- Application backups disabled
- Cleartext network traffic disabled
- Non-exported runtime service
- Non-exported settings, terminal and MCP activities
- No WebView dependency in the application runtime
- No local HTTP or WebSocket listener for the app-server connection
- Private owner-restricted application directories
- Separate workspace and Codex home
- Canonical path validation
- Symbolic-link boundary checks
- Descriptor-relative, no-follow workspace exports with opened-file identity and link-count checks
- Bounded, owner-only in-chat imports whose one-shot Core send scope fully SHA-256 checks a retained stable-handle batch and revalidates it inside the RPC write lock before native history/model context is sent
- Complete bounded PNG chunk, CRC, zlib and scanline-shape validation
- Private SHA-256 materialization proofs before resumed images regain an export path
- Bounded protocol messages
- Bounded terminal input and output
- A private `rg` bridge that blocks preprocessor, archive-search and symlink-follow modes
- Credential detection and redaction in sensitive paths
- Explicit approval handling
- C++ ownership of child processes
- External release-signing configuration
- Release certificate verification
- Rejection of Android debug certificates for release builds

Several sensitive byte and character buffers are explicitly cleared after use.

---

## Platform

Current release line:

### AGENTCODI 0.5.16

| Requirement | Value |
|---|---|
| Minimum Android | Android 10 / API 29 |
| Target SDK | API 35 |
| Architecture | ARM64 |
| Application source | Java + C++ |
| Codex runtime | 0.148.1 |
| Languages | English and German |
| Themes | Light and dark |
| License | Apache 2.0 |

---

## Build and tests

The repository includes dedicated architecture, Java and C++ test gates.

Run the host test suite:

```sh
./scripts/test.sh
```

Build the installable test APK:

```sh
./scripts/build-debug-apk.sh
```

The test APK is still explicitly non-debuggable and uses local test signing.

Production signing uses the separate release path:

```sh
./scripts/build-release-apk.sh
```

Release credentials and the expected signing certificate are supplied externally to the build process.

The architecture gate also checks important project invariants, including module boundaries, MCP access boundaries, native process ownership, signing requirements and supported source languages.

Physical Android hardware is used separately to validate installation, UI behavior, lifecycle behavior and long-running runtime sessions.

---

## Project status

AGENTCODI is under active development.

Version 0.5.16 currently focuses heavily on:

- Native Codex runtime integration
- Correlated in-flight turn steering without losing the separate stop action
- Read-only, app-server-owned ChatGPT quota visibility
- MCP visibility and guarded configuration
- Packaged development toolchains, including a pinned no-PCRE2 ripgrep bridge
- Android runtime stability
- Workspace boundaries
- Direct, bounded external-document import with a proven transient read grant, crash-recovery cleanup, commit-stable resource closing, atomic no-replace installation, retained verification handles across queue hand-offs, and final batch revalidation coupled to the app-server JSONL write
- Race-free individual-file, image and ZIP source opening
- Workspace catalogs and ZIP exports with separate finite scan and regular-file limits, omitting symbolic links without following them or blocking regular-file export
- Complete PNG validation before materialization, recovery and export
- SHA-256-bound image materialization proofs for resumed history
- Approval handling
- Build and release integrity

Bug reports with reproducible behavior are welcome through [GitHub Issues](../../issues).

---

## License

AGENTCODI's original Java and C++ application code, tests, resources, build automation and documentation are licensed under the [Apache License 2.0](LICENSE).

Licenses and notices for third-party components bundled with the application are documented in [NOTICE.md](NOTICE.md) and are also exposed through the application's legal notices screen.

---

<div align="center">

### Build with Codex directly from Android.

**AGENTCODI**

</div>

AGENTCODI is an independent open-source project and is not affiliated with or endorsed by OpenAI.
