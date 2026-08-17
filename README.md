# AGENTCODI

AGENTCODI is a native Android client that runs a pinned Codex app-server locally and presents its conversations, tools, approvals, and workspace through a focused mobile interface. The application and its tests are written in Java and C++, with a deliberately small Android/JNI boundary.

## Current features

- Streaming chat with bounded message, reasoning, plan, command, file-change, and tool cards
- Live model and reasoning-effort selection, including the options reported by the runtime
- Native approval and user-input dialogs with explicit consent and finite timeouts
- ChatGPT and API-key sign-in through the Codex account protocol
- Thread listing, creation, resume, history recovery, interruption, and explicit runtime restart
- Private app workspace with validated image, individual-file, and bounded ZIP export
- Sandboxed interactive terminal backed by the same app-server session
- Explicitly activated, packaged Node.js 24.18.0, npm 11.19.0, and Python 3.14.6 toolchains; Python retains SQLite-backed `dbm`/`shelve` and PyREPL without GNU dbm or Readline
- English and German interfaces, device-language fallback, light and dark themes
- Local, bounded, credential-redacted diagnostics

## Platform and status

The current release line is **0.4.11** for **Android 10+** on **ARM64**. It adds a bounded, read-only MCP and Codex capability catalog sourced exclusively from the active app-server, including MCP tools, skills, installed connectors, runtime features, and experimental plugin marketplaces without reading or constructing Codex configuration paths. Host tests and APK integrity gates cover the protocol, storage, native supervisor, toolchains, signing, and architecture boundaries. Installation and device-specific UI, lifecycle, and long-running behavior are validated separately on a physical test device.

## Build

Run the host test suite before building the installable test APK:

```sh
./scripts/test.sh
./scripts/build-debug-apk.sh
```

The generated test APK is non-debuggable and locally test-signed; it is not a production release. Release signing uses the separate externally configured build path.

## License

AGENTCODI's original Java/C++ application code, tests, resources, build automation, and documentation are licensed under the [Apache License 2.0](LICENSE). Licenses and notices for components bundled with the APK are documented in [NOTICE.md](NOTICE.md) and included in the application's legal-notices screen.
