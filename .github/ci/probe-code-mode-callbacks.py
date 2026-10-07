#!/usr/bin/env python3
"""Isolate pinned code-mode host callbacks over its framed stdio protocol.

No model, MCP server, app-server, APK relocation or credentials are involved.
--diagnose attaches the runner's GDB to each container host before execution.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import struct
import subprocess
import threading
import time
import uuid

IMAGE = "termux/termux-docker:aarch64@sha256:e19ea56dd687563849826cbda57da714ae23277ee463e21f39917dbc0a59bab4"
TOOL = {"name": "mcp__approval_probe__echo",
        "tool_name": {"name": "echo", "namespace": "mcp__approval_probe"},
        "description": "Return a synthetic marker.", "kind": "function",
        "input_schema": {"type": "object"}, "output_schema": None}
CASES = (
    ("text-string", "text('host-control-ok');", False, 0),
    ("tools-installed", "text('host-control-ok');", True, 0),
    ("js-json", "text(JSON.stringify({message:'host-control-ok'}));", True, 0),
    ("text-object", "text({message:'host-control-ok'});", True, 0),
    ("callback-no-args", "text(await tools.mcp__approval_probe__echo());", True, 1),
    ("callback-object", "text(await tools.mcp__approval_probe__echo({message:'host-control-ok'}));", True, 1),
)


def probe(audit, output, case, diagnose):
    label, source, enabled, expected_calls = case
    name = "codehost-probe-" + uuid.uuid4().hex
    transcript = []
    errors = []
    messages = queue.Queue()
    calls = []
    debugger = None
    debugger_output = None
    process = subprocess.Popen([
        "docker", "run", "--rm", "-i", "--name", name, "--user", "1000:1000",
        "--network", "none", "--entrypoint", "/audit/payload/package/bin/codex-code-mode-host",
        "-v", str(audit) + ":/audit:ro", IMAGE, "--listen", "stdio"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def read_messages():
        try:
            while True:
                header = process.stdout.read(4)
                if not header:
                    messages.put(None)
                    return
                if len(header) != 4:
                    raise ValueError("Truncated host frame header")
                length = struct.unpack("<I", header)[0]
                if not 0 < length <= 64 * 1024 * 1024:
                    raise ValueError("Invalid host frame length")
                payload = process.stdout.read(length)
                messages.put(json.loads(payload))
        except Exception as error:
            messages.put(error)

    def read_errors():
        for line in process.stderr:
            errors.append(line.decode(errors="replace"))

    reader = threading.Thread(target=read_messages, daemon=True)
    error_reader = threading.Thread(target=read_errors, daemon=True)
    reader.start()
    error_reader.start()

    def send(message):
        transcript.append({"client": message})
        payload = json.dumps(message).encode()
        process.stdin.write(struct.pack("<I", len(payload)) + payload)
        process.stdin.flush()

    def receive():
        message = messages.get(timeout=30)
        if isinstance(message, Exception):
            raise message
        if message is None:
            raise AssertionError("Host closed stdout before returning a result")
        transcript.append({"host": message})
        return message

    result = {"case": label, "source": source, "expected_calls": expected_calls}
    try:
        send({"type": "connection/hello", "supportedVersions": [1],
              "requiredCapabilities": [], "optionalCapabilities": []})
        assert receive()["type"] == "connection/ready"
        send({"type": "operation/request", "id": 1,
              "request": {"method": "session/open", "sessionId": "probe"}})
        opened = receive()
        assert opened["result"]["status"] == "ok", opened
        if diagnose:
            pid = int(subprocess.check_output([
                "docker", "inspect", "--format", "{{.State.Pid}}", name], text=True))
            debugger_output = (output / (label + ".gdb.txt")).open("w")
            debugger = subprocess.Popen([
                "sudo", "gdb", "-q", "-batch", "-ex", "set pagination off",
                "-ex", "set sysroot /proc/" + str(pid) + "/root",
                "-ex", "attach " + str(pid), "-ex", "continue",
                "-ex", "thread apply all bt", "-ex", "info registers",
                "-ex", "info proc mappings", "-ex", "x/12i $pc-16"],
                stdout=debugger_output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                status = Path("/proc/" + str(pid) + "/status").read_text()
                if any(line.startswith("TracerPid:") and int(line.split()[1]) for line in status.splitlines()):
                    break
                assert debugger.poll() is None, "GDB could not attach"
                time.sleep(0.1)
            else:
                raise AssertionError("GDB attach timed out")
        send({"type": "operation/request", "id": 2,
              "request": {"method": "session/execute", "sessionId": "probe", "request": {
                  "tool_call_id": "probe-call", "enabled_tools": [TOOL] if enabled else [],
                  "source": source, "yield_time_ms": 10000, "max_output_tokens": 1000}}})
        while True:
            message = receive()
            if message["type"] == "delegate/request":
                invocation = message["request"]["invocation"]
                calls.append(invocation)
                send({"type": "delegate/response", "id": message["id"], "result": {
                    "status": "ok", "value": {"type": "tool/result", "result": "host-control-ok"}}})
            if message["type"] == "execute/initialResponse":
                assert message["result"]["status"] == "ok", message
                value = message["result"]["value"]
                assert "host-control-ok" in json.dumps(value), message
                assert len(calls) == expected_calls, calls
                result["status"] = "passed"
                break
    except Exception as error:
        result.update(status="failed", error=str(error))
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)
            process.wait(timeout=5)
        reader.join(timeout=2)
        error_reader.join(timeout=2)
        if debugger:
            try:
                debugger.wait(timeout=5)
            except subprocess.TimeoutExpired:
                debugger.terminate()
                debugger.wait(timeout=5)
            debugger_output.close()
        result.update(exit_code=process.returncode, calls=calls)
        if process.returncode != 0:
            result["status"] = "failed"
        (output / (label + ".json")).write_text(json.dumps(transcript, indent=2) + "\n")
        (output / (label + ".stderr.txt")).write_text("".join(errors))
    print(label + ": " + json.dumps(result), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audit", type=Path)
    parser.add_argument("--diagnose", action="store_true")
    args = parser.parse_args()
    audit = args.audit.resolve()
    pin = json.loads(Path(__file__).with_name("community-codex-release.json").read_text())
    host = audit / "payload/package/bin/codex-code-mode-host"
    digest = hashlib.sha256(host.read_bytes()).hexdigest()
    assert digest == pin["native_sha256"][host.name], "Unverified host binary"
    output = audit / "report/code-mode-callbacks"
    output.mkdir(parents=True, exist_ok=True)
    results = [probe(audit, output, case, args.diagnose) for case in CASES]
    report = {"host_sha256": digest, "image": IMAGE, "probes": results}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    raise SystemExit(0 if all(r["status"] == "passed" for r in results) else 1)


if __name__ == "__main__":
    main()
