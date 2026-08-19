<div align="center">

# AGENTCODI

### Codex workflows, native on Android.

**Run the Codex app-server, workspace, approvals, terminal and supported development toolchains directly on your Android device.**

<br>

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Codex](https://img.shields.io/badge/Codex%20app--server-0.147.2-111111)
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
| Codex home | Private and separated from the workspace |
| Terminal | Interactive workspace terminal through the Codex runtime |
| Node.js | Packaged runtime |
| npm | Packaged runtime |
| Python | Packaged runtime |
| MCP management | Native Android interface backed by Codex configuration RPCs |

The current AGENTCODI 0.5.0 runtime uses **Codex app-server 0.147.2**.

---

## Codex workflow

AGENTCODI exposes the parts of the Codex app-server workflow that matter during real development.

### Conversations

- Start new Codex threads
- Resume existing threads
- Recover thread history
- Stream responses live
- Interrupt active turns
- Restart the local runtime explicitly

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
- Logout and account refresh

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

The application also validates canonical paths and rejects unsafe symbolic-link boundaries.

### Export

Supported workspace exports include:

- Individual files
- Images
- Bounded ZIP archives

Export paths and archive contents pass through dedicated validation before leaving the private workspace.

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

The toolchains are prepared inside the private AGENTCODI environment and remain inactive until explicitly enabled.

AGENTCODI validates its packaged tool aliases and runtime files before making them available to the workspace.

Python includes SQLite-backed `dbm` and `shelve` support as well as PyREPL.

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

AGENTCODI 0.5.0 also includes a guarded Expert Mode for the supported part of the user's MCP configuration.

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
       +---- Node.js / npm / Python
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
- Bounded protocol messages
- Bounded terminal input and output
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

### AGENTCODI 0.5.0

| Requirement | Value |
|---|---|
| Minimum Android | Android 10 / API 29 |
| Target SDK | API 35 |
| Architecture | ARM64 |
| Application source | Java + C++ |
| Codex runtime | 0.147.2 |
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

Version 0.5.0 currently focuses heavily on:

- Native Codex runtime integration
- MCP visibility and guarded configuration
- Packaged development toolchains
- Android runtime stability
- Workspace boundaries
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