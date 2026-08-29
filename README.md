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
| Review mode | Native custom workspace review on the current Codex thread |
| Graphical file browser | Native paged workspace navigation, bounded previews, individual-file export and folder ZIP export |
| File import | Android document picker into a bounded private workspace copy |
| Codex home | Private and separated from the workspace |
| Account quotas | Read-only primary and secondary ChatGPT quota windows from Codex |
| Terminal | Interactive workspace terminal through the Codex runtime |
| Node.js | Packaged runtime |
| npm | Packaged runtime |
| Python | Packaged runtime |
| ripgrep | Packaged no-PCRE2 runtime exposed as `rg` |
| MCP management | Native Android interface backed by Codex configuration RPCs |
| Gmail and GitHub | One-tap browser sign-in, automatic selection and direct use in the next Codex message |

The current AGENTCODI 0.6.7 runtime uses **Codex app-server 0.148.1** together with the matching code-mode host from the same pinned artifact.

---

## Codex workflow

AGENTCODI exposes the parts of the Codex app-server workflow that matter during real development.

### Conversations

- Start new Codex threads
- Resume existing threads
- Recover thread history
- Switch between bounded active and archived thread lists
- Archive and restore threads through the Codex app-server
- Permanently delete a thread after an explicit confirmation
- Stream responses live
- Import external documents directly from the chat composer
- Connect Gmail and GitHub and use them in the next Codex message
- Correct or add guidance to an active turn without starting a new turn
- Start a custom workspace review on the current thread
- Interrupt active turns
- Restart the local runtime explicitly

While a turn is running, the composer switches to **Add guidance** and sends a correlated
`turn/steer` request to that same turn. The separate **Stop** action remains available.

Thread actions are disabled while a turn or native approval/input request is active. Archiving is reversible through the archived view; permanent deletion is a distinct Codex RPC and always requires confirmation. If the currently open thread is archived or deleted, AGENTCODI clears only its in-memory conversation projection. Private workspace files, including imported copies, are not deleted as a side effect.

The chat surface uses compact, accessible icon actions with 48 dp touch targets, localized screen-reader labels and long-press tooltips. Workspace files, terminal and settings live in the header; file import and Gmail/GitHub connection sit directly to the left of the composer. Review and Stop share a contextual slot so Stop remains available during an active turn without crowding the input. These local vector resources come from the pinned Apache-2.0 Material Icons source documented in `NOTICE.md`.

### Review mode

The native **Review** action starts the app-server's `review/start` flow on the currently open thread. AGENTCODI exposes only a bounded `custom` target with `inline` delivery: the user enters review instructions, while Git-derived targets such as uncommitted changes, base branches and commits remain unavailable because AGENTCODI does not expose a Git contract.

The request contains only the current thread, the custom instructions and inline delivery. The pinned app-server may report one turn in the `review/start` response and a distinct live turn in `turn/started`; AGENTCODI binds only those two bounded IDs to the exact thread, uses the live ID for Stop, accepts either correlated ID for lifecycle events, rejects a third ID and cannot revive a completed review. Stop uses a dedicated serial control path so it remains available while `review/start` is awaiting its response. The selection and instructions remain transient in memory. Review mode does not add another process, listener, workspace root, permission expansion or prompt-based execution policy.

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
- Review-mode entry and exit

### Models and reasoning

Available models and reasoning levels are read from the active Codex runtime.

You can change:

- Model
- Reasoning effort

Selections are validated against the options reported by the app-server.

### Execution modes

AGENTCODI 0.6.7 offers two explicitly separated execution modes:

| Mode | App-server profile | Filesystem behavior |
|---|---|---|
| Protected – default | `agentcodi-workspace` | Workspace only; changes are grouped into one patch with a preview where possible |
| Compatibility – experimental | `:danger-full-access` | Direct file editing is possible, but there is no effective filesystem isolation |

Compatibility mode exists for Android environments where the pinned app-server cannot provide reliable access through its protected sandbox. Before it can be activated, AGENTCODI always shows a native warning requiring an explicit acknowledgement. The active risk remains visible, the choice is not persisted, and an unconfirmed system restart returns to protected mode. Full access remains subject to Android's app UID, but it can reach files outside the workspace that are available to that UID, including private sibling directories.

The selection is carried only in the app-server's native `permissions` and `permissionProfile` fields for threads, turns and the terminal. AGENTCODI does not inject a system prompt, developer prompt or base instructions to implement either mode.

### Authentication

AGENTCODI supports:

- ChatGPT account sign-in through the Codex account flow
- OpenAI API key authentication
- Read-only ChatGPT quota usage, window duration and reset time reported by Codex
- Logout and account, quota and model refresh

Authentication controls are kept in the settings surface.

Gmail and GitHub use a simple **Sign in → finish in the browser → return to AGENTCODI** flow. The secure sign-in action remains available independently of the **Use with Codex** action, including when Codex already reports the service as callable. On return, AGENTCODI performs at most two bounded checks and automatically prepares only the freshly confirmed service for the next message. Provider passwords and tokens are never collected or stored by AGENTCODI. The screen accepts only the validated HTTPS `installUrl` supplied by Codex below `openai.com` or `chatgpt.com`.

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

### Graphical file browser

The native **Files** surface navigates the private workspace without exposing it through an Android provider. Breadcrumbs move directly through the current path, directory pages keep folders before files, and previous/next controls cover both long directory listings and long file contents. Refresh never changes the selected execution mode or Codex session.

Regular UTF-8 text is shown as selectable, bounded content pages. A text candidate is validated incrementally through the safely opened file before it is projected as text, so a later NUL or malformed UTF-8 sequence classifies the file as binary instead of aborting its preview. Other regular files receive a bounded hexadecimal preview, while validated PNG, JPEG, GIF and WebP files receive a native image preview. Preview decoding remains off the main thread. A malformed image is rejected as an image instead of being decoded from unchecked bytes.

Directory enumeration and file reading remain descriptor-relative to the canonical workspace and never follow symbolic links. A symbolic link, hard link, special entry or unreadable child is represented as an unavailable row with its reason; it does not abort navigation to safe sibling folders and files. These entry-local safety decisions are independent of the active Codex execution mode. The same surface exports a selected regular file byte for byte or the currently open folder as a ZIP through Android's document picker. Opening the workspace root and choosing the folder action replaces the former separate whole-workspace ZIP button.

### Import

The chat composer can import files selected through Android's system document picker. AGENTCODI requires the returned result intent itself to carry `FLAG_GRANT_READ_URI_PERMISSION`; requesting that flag when opening the picker or having some other way to read a `content:` URI is not accepted as proof. The UI rejects a result without the transient read grant before collecting its URIs, and the Runtime import facade verifies the same immutable, URI-free grant projection again before reading provider metadata or opening a stream. AGENTCODI then copies each selected document byte-for-byte into the private `workspace/imports` directory and attaches only that verified workspace copy. Single and multiple selection remain supported.

For both a new `turn/start` and attachment-only or text-backed `turn/steer`, the pinned app-server's native `mention` input preserves the visible attachment in user history while its native `additionalContext` field gives Codex the exact verified workspace path and requires it to read the actual file bytes with the existing workspace tools. No file is represented to the model by its display name alone, and the application creates no upload protocol, second process, listener, JNI gateway or filesystem root.

One message can attach at most 16 imported files, with a 512 MiB per-file limit and a 1 GiB combined limit. Pending and final files use random tokens plus at most a short strictly alphanumeric extension, owner-only permissions, bounded copying, descriptor-relative no-follow operations, cleanup after failure, and the same stable regular-file/link checks used by workspace export. Since 0.5.10, the completed pending file is installed through the existing JNI gateway by a C++ `renameat2(RENAME_NOREPLACE)` operation; there is no separate missing-target check, and a parallel creator wins without its bytes being replaced. In 0.5.11, ownership of the transient provider stream moves into the pure import client: the stream and both secure directory handles are always asked to close, but a close error after the final file has already been installed and fully verified can no longer revoke the returned import and leave it invisible to the composer. Before that commit boundary, the original failure remains authoritative and close failures are retained as suppressed context. Runtime startup and every serialized materialization also remove only exact regular `.pending-` plus 32-lowercase-hex entries through the held no-follow imports handle; malformed, symbolic, committed, and unrelated names are never interpreted as abandoned imports. The user-controlled display-name stem is therefore not part of the model-readable path. A transient SHA-256 binding is calculated while copying. In 0.5.9 the UI began passing only a closeable one-shot transaction across its hand-off queues; the serial Core operation consumes it by opening and fully hashing the complete selected batch inside the same synchronous scope that constructs and sends the request. Every no-follow handle remains open, the whole batch is checked after the last hash, and a one-time guard checks it again inside the JSONL transport write lock immediately before the request bytes are written. Handles close only after the correlated request returns or fails. Equal-length replacement before this scope, while a later attachment is checked, or during request preparation therefore fails closed before transport. Obvious credential filenames are rejected before copying. Provider URIs, grants, display labels and pending digest state never enter the model context; external storage never becomes a runtime workspace root or sandbox exception.

Detaching a file removes it only from the pending message. The private copy remains a normal workspace file so a later Codex turn can work with it and the existing explicit workspace export can retrieve it.

### Export

Supported explicit workspace exports include:

- Individual regular files from the graphical browser, regardless of extension or MIME type
- Validated generated images from their visible result cards
- The currently open browser folder as a bounded ZIP, including the workspace root

Export paths and archive contents pass through dedicated validation before leaving the private workspace. Since version 0.5.3, each exported file is read from a descriptor opened component by component relative to the private workspace without following links, then that same descriptor and its workspace name are verified again. A concurrent path, symlink, parent-directory or hard-link exchange therefore fails without redirecting reads outside the workspace. In 0.6.0, folder ZIPs use the same native descriptor-relative directory catalog as the browser and the same native stable file opener as individual export. The selected folder is bound into the summary, ZIP paths are relative to it, and complete pre-/post-write catalogs plus opened-file identity, size and timestamps detect mutations.

In 0.6.3, individual files, generated images and folder ZIPs share one recoverable document-output transaction. The selected Android document is opened with explicit truncation, and any preparation, source-verification, write, flush or close failure first closes the stream and then deletes the failed document. If a provider cannot delete it, AGENTCODI reopens it with explicit truncation so partial or stale export bytes do not remain. Source races still fail closed; they no longer leave already written destination bytes behind.

Version 0.6.0 retains the ZIP limits of 2,048 regular files, 1 GiB of content, 65,536 scanned entries, 512 MiB per file, 2,048 path characters and 64 directory levels. Symbolic links, hard links, special or unreadable entries, unsafe names and non-portable path collisions are omitted locally and reported in the export summary, so they cannot suppress independent safe siblings. They are never followed or included. Exceeding a quantitative bound, selecting an unsafe folder, or detecting a mutation of an already bound archive member still fails the archive instead of weakening a boundary or claiming an incomplete bounded export.

Version 0.5.4 introduced complete PNG validation before native materialization and again before workspace image export or resumed-history reuse. Validation covers the complete chunk boundaries and ordering, CRCs, `IHDR` dimensions and format fields, palette rules, the bounded zlib stream, the exact interlaced or non-interlaced scanline shape and filter bytes, and a final `IEND` with no trailing data. A PNG signature followed by arbitrary bytes is rejected rather than materialized or offered for export.

Version 0.5.5 additionally binds resumed generated images to an owner-only SHA-256 materialization proof stored outside the writable workspace and outside `CODEX_HOME`. A valid PNG that replaced the originally materialized bytes is rejected even when its filename and current metadata still look valid. If no proven materialization exists, the app-server's reported `savedPath` is removed before the event reaches Java, so it cannot become an export candidate. Existing 0.5.4 workspace images are not retroactively trusted without fresh inline bytes. JPEG, GIF and WebP export support is unchanged.

---

## Integrated terminal

AGENTCODI includes an interactive terminal for the active workspace.

The terminal is started through the Codex app-server command interface and uses the currently selected, verified execution-mode profile.

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
downloaded or installed at runtime. The native-library directory is excluded
from command `PATH`. Mandatory C++ guards on the actual Node.js, Python and
ripgrep ELFs enforce the same activation and environment policy even when an
absolute ELF path is invoked. A relocation-free in-binary entry attestor also
binds each process to the genuine no-follow sibling guard by device and inode,
so an `LD_LIBRARY_PATH` replacement cannot suppress the policy before normal
activated functionality starts.

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

## Gmail and GitHub

The Gmail/GitHub icon in the chat composer opens a native connection screen with a three-step guide:

1. Tap **Sign in to Gmail** or **Sign in to GitHub**.
2. Complete the secure account flow in the system browser.
3. Return to AGENTCODI. The app checks the connection and selects the service automatically for the next Codex message.

Already connected services can be selected or removed manually, while their separate **Manage sign-in** action stays available. Both services can still be attached to the same message. Changing chats, losing the runtime connection or sending successfully clears the transient per-message selection exactly as before.

AGENTCODI reads the active app-server's bounded `app/list`, `app/installed` and `app/read` projections; it does not implement Gmail or GitHub APIs, download connector code or call connector tools itself. Public directory data is published as soon as it is safe, so a trusted sign-in link is not hidden while runtime availability is still being checked. Essential directory and runtime checks run concurrently when fresh accessibility is required and settle within a shared finite budget; later checks use only `app/installed` once a successful directory phase is still current. If `app/list` fails, retained public metadata remains display-only: a runtime-only refresh cannot turn that failed snapshot into `READY`, and recovery requires another successful directory check. A request that was already running before the browser return is never accepted as the sign-in confirmation. Optional `app/read` display metadata runs separately and cannot block sign-in, callability or a subsequent refresh.

The app-server protocol can publish the complete merged catalog in an unpaginated `app/list/updated` notification. AGENTCODI does not consume that stream and opts out during `initialize`; connector discovery continues through the bounded paginated requests. The existing 1 MiB Java/native framing limit remains unchanged and unrelated oversized protocol data still fails closed.

Only an app that is accessible, enabled, present in the committed runtime snapshot and reported as callable can be selected—even after the browser returns. The selection is bounded to Gmail and GitHub, tied to the active thread and kept only in memory for the next message. Immediately before `turn/start` or `turn/steer`, the current snapshot is checked again. Codex receives its supported `$app-id` directive together with the native `{type: "mention", name, path: "app://id"}` input. No connector URL, token, schema, response or extra permission root is added to the turn.

Installation and reauthentication remain owned by ChatGPT/Codex. The native screen accepts only app-server-provided HTTPS pages below `openai.com` or `chatgpt.com` and opens them in the external browser; AGENTCODI has no connector WebView, OAuth callback, client secret or token store.

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
       +---- protected-mode (`agentcodi-workspace`)
       +---- compatibility-mode (`:danger-full-access`)
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
       +---- Codex Gmail / GitHub Apps
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
- Non-exported settings, terminal, MCP and connector activities
- No WebView dependency in the application runtime
- No local HTTP or WebSocket listener for the app-server connection
- Private owner-restricted application directories
- Separate workspace and Codex home
- Canonical path validation
- Symbolic-link boundary checks
- Descriptor-relative, no-follow workspace exports with opened-file identity and link-count checks
- Descriptor-relative selected-folder ZIP catalogs that isolate and report unavailable children while preserving safe siblings
- Bounded, owner-only in-chat imports whose one-shot Core send scope fully SHA-256 checks a retained stable-handle batch and revalidates it inside the RPC write lock before native history/model context is sent
- Complete bounded PNG chunk, CRC, zlib and scanline-shape validation
- Private SHA-256 materialization proofs before resumed images regain an export path
- Bounded protocol messages
- Bounded terminal input and output
- Bounded, thread-scoped, transient Gmail/GitHub selections with callable-state revalidation before send
- Gmail/GitHub sign-in restricted to validated OpenAI/ChatGPT HTTPS pages, with no local credentials or OAuth callback
- A private alias-only tool `PATH`, mandatory Node.js/Python/ripgrep ELF guards, and in-binary guard attestation that enforce activation and block ripgrep preprocessor, archive-search and symlink-follow modes even for absolute invocations or substituted policy libraries
- Credential detection and redaction in sensitive paths
- Explicit approval handling
- Protected execution by default and an unpersisted, explicitly warned compatibility mode
- Permission-profile-only mode transport with no injected system or developer prompts
- C++ ownership of child processes
- External release-signing configuration
- Release certificate verification
- Rejection of Android debug certificates for release builds

Several sensitive byte and character buffers are explicitly cleared after use.

---

## Platform

Current release line:

### AGENTCODI 0.6.7

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

The architecture gate also checks important project invariants, including module boundaries, MCP and Gmail/GitHub trust boundaries, native process ownership, signing requirements and supported source languages.

Physical Android hardware is used separately to validate installation, UI behavior, lifecycle behavior and long-running runtime sessions.

---

## Project status

AGENTCODI is under active development.

Version 0.6.7 currently focuses heavily on:

- Native Codex runtime integration
- A fail-closed app-server `fork()`/`execve()` boundary that prevents unrelated parent file descriptors from entering the Codex child and combines an isolated child process group with parent-side subreaper ownership
- Graceful TERM and synchronous forced KILL/reap cleanup for the complete app-server tree, including terminal or tool descendants that create their own process group or session
- Separate protected and experimental compatibility-mode modules, mandatory danger warning and native profile propagation without prompt injection
- Bounded active/archive thread views with app-server-backed archive, restore and confirmed permanent deletion
- A compact accessible icon-based chat header, thread list and composer without removing any chat action
- Correlated in-flight turn steering without losing the separate stop action
- A native custom-only inline review flow with bounded instructions, bounded split-ID correlation, reliable completion and a stop path that remains available during a pending review start
- Read-only, app-server-owned ChatGPT quota visibility
- MCP visibility and guarded configuration
- Beginner-friendly Gmail/GitHub connection with an always-available secure sign-in/manage action, staged and bounded availability checks, failed-directory provenance preserved across runtime-only refreshes, at most two post-return checks, automatic selection only after fresh confirmation and unchanged pre-send callability checks
- Packaged development toolchains with activation-bound and in-binary-attested native ELF guards plus a pinned no-PCRE2 ripgrep bridge
- Android runtime stability
- Workspace boundaries
- A native graphical workspace browser with breadcrumbs, directory and content paging, complete incremental UTF-8 candidate validation, bounded text/image/binary previews, isolated unavailable-entry reporting, byte-exact file export and selected-folder ZIP export
- Direct, bounded external-document import with a proven transient read grant, crash-recovery cleanup, commit-stable resource closing, atomic no-replace installation, retained verification handles across queue hand-offs, and final batch revalidation coupled to the app-server JSONL write
- Race-free individual-file, image and ZIP source opening
- Recoverable Android document transactions that delete or explicitly clear failed individual-file, image and ZIP exports
- Workspace catalogs and ZIP exports with separate finite scan and regular-file limits, omitting unsafe or non-portable entries without following them or blocking independent safe files
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
