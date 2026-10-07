# Code-mode SIGSEGV investigation — 2026-10-07

The failure in [run 37493948718](https://github.com/Mcpasi/AGENTCODI/actions/runs/37493948718)
was caused by the CI container's Android 9 Bionic runtime. V8 in the pinned
Community host uses native ELF thread-local storage, supported from Android 10
/ API 29. AGENTCODI already requires API 29. The unchanged host works with
API 29 Bionic, including the originally failing nested MCP callback.

## Signal and failing memory access

The original app-server log reports `signal: 11 (SIGSEGV) (core dumped)` for
its child code-mode host. The app-server remains alive. This is a native
invalid memory access on the ARM64 Linux runner; containers still use the
runner's kernel. It is not a GitHub timeout, cancellation, OOM kill, Java
exception or MCP approval rejection.

The pinned Termux image contains `aosp-libs 9.0.0-r76-4`. Its pre-API-29
loader/libc cannot initialize the host's ELF TLS segment. Relaxed ARM64 TLS
instructions still execute and access unrelated thread storage. The native
GDB capture in [diagnostic run 37675519430](https://github.com/Mcpasi/AGENTCODI/actions/runs/37675519430)
shows the following in `v8::internal::Isolate::Enter()`:

```text
ELF-relative PC: 0x2abbd80
mrs x22, tpidr_el0
... TLS offset 0x88 ...
ldr x24, [x22, x0]      # g_current_per_isolate_thread_data_
...
ldr x25, [x24]          # fault; x24 is 0x1, not a thread-data pointer
```

The function and TLS symbol were identified by matching the instructions and
relocations in `isolate.o` from the release's pinned V8 archive, SHA-256
`6fb02f1669dbb85f7265a1cba05ac31b683295be7db9651f1db2b7b082794a09`.
See Android's [ELF TLS availability](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md#elf-tls-available-for-api-level-29)
and [old-platform memory corruption explanation](https://android.googlesource.com/platform/bionic/+/master/docs/elf-tls.md#graceful-failure-on-old-platforms).

Six direct host probes fail with exit 139 on the old native ARM64 container:
string output, installed tool definitions, JavaScript JSON serialization,
object output, callbacks without arguments and callbacks with an object.
[Run 37675977978](https://github.com/Mcpasi/AGENTCODI/actions/runs/37675977978)
repeated these failures both without and with GDB. The failure therefore
requires neither a model fixture nor MCP, APK host-name relocation or GDB.

## Why the earlier smoke appeared to pass

The smoke searched the entire follow-up model input for
`community-code-host-ok`. That history includes the submitted
`text('community-code-host-ok');` call even when its execution failed.
A deterministic model also completes its turn after a failed tool call.
These checks did not prove that JavaScript ran successfully.

`community_host_output.py` now checks the matching `custom_tool_call_output`
and requires the marker as an actual output line. Four regressions reject
crash output, a marker only in source/error text, and missing/wrong call IDs,
and accept real string/content-block results.

## Correction and verification

`community-runtime.Dockerfile` replaces only the test container's linker,
libc, libm and libdl with the Android 10 Bionic files from an immutable ARM64
Redroid image. It keeps the pinned Termux tools. The two base image digests
are in the Dockerfile; no Redroid services or Android system are booted.
The runtime workflow builds this image and uses it for schema generation,
isolated host probes and both app-server MCP dispatch paths.

The Community archive, host/app-server hashes, relocation, schemas, app code,
APK contents and runtime version pins are unchanged. This fixes the test
environment; a patched Community runtime is not needed for this failure.

[Fix run 37676838798](https://github.com/Mcpasi/AGENTCODI/actions/runs/37676838798)
at `35211867a10aef2dc85a3208dcb54d18fcbf7275` passed all seven jobs.
Its [runtime artifact](https://github.com/Mcpasi/AGENTCODI/actions/runs/37676838798/artifacts/11507383509)
was downloaded and inspected. It records:

- All six isolated probes pass with exit 0 and the expected delegate counts.
- The actual relocated-host output contains `community-code-host-ok`.
- Native model function calls and the original nested code-mode MCP calls
  both reach the real approval gate: zero calls before a decision, one after
  accept, and no additional calls after decline/cancel.
- Both protocol reports pass; each validates 417 actual Java RPC records and
  15 MCP approval records. The Java suite has 325 passing tests.
- Binary and generated schema pins still match the checked-in release.

The same six probes also passed locally under QEMU with Android 10 Bionic.
QEMU with the old Bionic failed even on the string control; those emulated
failures alone were not used to infer the cause.
Physical device/version/update tests remain separate. These results close
the recorded CI crash and do not replace Android hardware validation.

## Repeating the investigation

Run on a native ARM64 Linux machine with Docker, from the repository root:

```sh
python3 .github/ci/inspect-community-codex.py --output /tmp/community-audit
chmod -R a+rX /tmp/community-audit
chmod 755 /tmp/community-audit/payload/package/bin/codex-code-mode-host
docker build -f .github/ci/community-runtime.Dockerfile -t agentcodi-community-api29 .github/ci
python3 .github/ci/probe-code-mode-callbacks.py /tmp/community-audit
# Expected nonzero exit and host failures on the old Bionic:
python3 .github/ci/probe-code-mode-callbacks.py /tmp/community-audit --legacy-bionic
```

Add `--diagnose` with GDB and sudo installed to capture the failing process
before execution. Reports, stderr and framed messages are retained under
`report/code-mode-callbacks/` or `report/code-mode-callbacks-legacy/`.
The workflow additionally uses `verify-community-protocol.py --nested-mcp`
to check the original full app-server/MCP sequence and real Java decisions.

All investigation and fix commits are on `Mcpasi/package-edition`.
`main` remains `ff27ec7c30d373a864e845e9a7ceeae3380dd103`; no PR or merge.
