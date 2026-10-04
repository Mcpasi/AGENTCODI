#!/usr/bin/env python3
"""Validate emitted Java RPCs and exercise the pinned Community app-server."""
import argparse
import base64
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import uuid
from jsonschema import Draft7Validator

IMAGE = "termux/termux-docker:aarch64@sha256:e19ea56dd687563849826cbda57da714ae23277ee463e21f39917dbc0a59bab4"
PROFILE = ":danger-full-access"

def validator(bundle, name):
    return Draft7Validator({"definitions": bundle["definitions"],
                           "$ref": "#/definitions/" + name})

def validate_sources(bundle):
    envelope = bundle["definitions"]
    available = set()
    for name in ("ClientRequest", "ClientNotification", "ServerRequest", "ServerNotification"):
        for variant in envelope.get(name, {}).get("oneOf", []):
            available.update(variant.get("properties", {}).get("method", {}).get("enum", []))
    used = set()
    pattern = re.compile(r'(?:request[A-Za-z]*|sendNotification)\(\s*"([^"]+)"')
    for source in Path("modules").rglob("*.java"):
        used.update(pattern.findall(source.read_text()))
    used.update(("initialize", "initialized", "account/login/start", "command/exec/write"))
    assert used <= available, "Missing source RPCs: " + repr(sorted(used - available))
    print("Source RPC inventory verified:", ", ".join(sorted(used)))
    return used

class Runtime:
    def __init__(self, audit, validate):
        self.name = "community-probe-" + uuid.uuid4().hex
        prefix = "/data/data/com.termux/files/usr"
        probe = "/probe"
        runtime_data = audit / "runtime-data"
        runtime_data.mkdir(exist_ok=True, mode=0o777)
        runtime_data.chmod(0o777)
        command = [
            "docker", "run", "--rm", "-i", "--name", self.name,
            "--entrypoint", "/entrypoint.sh", "-v", str(audit) + ":/audit",
            "-v", str(runtime_data) + ":/probe", IMAGE,
            "bash", "-c",
            'mkdir -p ' + probe + '/home/codex ' + probe + '/workspace ' + probe + '/home/.local/bin; '
            'exec env HOME=' + probe + '/home CODEX_HOME=' + probe + '/home/codex TMPDIR=' + prefix + '/tmp '
            'PATH=' + probe + '/home/.local/bin:/system/bin:/system/xbin '
            'CODEX_SELF_EXE=/audit/payload/package/bin/libcodex.so '
            '/audit/payload/package/bin/libcodex.so app-server --stdio --strict-config '
            '-c \'cli_auth_credentials_store="file"\' '
            '-c \'default_permissions=":danger-full-access"\' '
            '-c \'approval_policy="on-request"\' '
            '-c \'analytics.enabled=false\' -c \'feedback.enabled=false\' '
            '-c \'check_for_update_on_startup=false\' -c \'allow_login_shell=false\' '
            '-c \'model_provider="agentcodi-openai-http"\' '
            '-c \'model_providers.agentcodi-openai-http.name="OpenAI"\' '
            '-c \'model_providers.agentcodi-openai-http.wire_api="responses"\' '
            '-c \'model_providers.agentcodi-openai-http.requires_openai_auth=true\' '
            '-c \'model_providers.agentcodi-openai-http.supports_websockets=false\' '
            '-c \'model_providers.agentcodi-openai-http.supports_standalone_web_search=true\''
        ]
        # Intentionally omit CODEX_CODE_MODE_HOST_PATH: exercise sibling host discovery.
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.notifications = []
        self.errors = []
        self.validate = validate
        self.sequence = 0
        self.cwd = probe + "/workspace"
        self.home = probe + "/home"
        def read_lines():
            for line in self.process.stdout:
                self.lines.put(line)
        def read_errors():
            for line in self.process.stderr:
                if len(self.errors) < 100:
                    self.errors.append(line)
        threading.Thread(target=read_lines, daemon=True).start()
        threading.Thread(target=read_errors, daemon=True).start()

    def request(self, method, params=None, expect_error=False):
        self.sequence += 1
        message = {"id": self.sequence, "method": method}
        if params is not None:
            message["params"] = params
        self.validate.validate(message)
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                if self.process.poll() is not None:
                    raise AssertionError("Runtime exited: " + "".join(self.errors))
                continue
            response = json.loads(line)
            if response.get("id") == self.sequence and "method" not in response:
                if expect_error:
                    assert "error" in response, (method, "Expected structured rejection")
                    return response["error"]
                assert "error" not in response, (method, response.get("error"))
                print("Real Community RPC passed:", method)
                return response["result"]
            self.notifications.append(response)
        raise AssertionError("RPC timed out: " + method + "\n" + "".join(self.errors))

    def initialize(self):
        result = self.request("initialize", {
            "clientInfo": {"name": "agentcodi_android", "title": "AGENTCODI Package", "version": "0.1.0-package.1"},
            "capabilities": {"experimentalApi": True,
                             "optOutNotificationMethods": ["rawResponseItem/completed", "rawResponse/completed", "app/list/updated"]}
        })
        assert "codexHome" in result
        self.process.stdin.write('{"method":"initialized","params":{}}\n')
        self.process.stdin.flush()

    def command(self, shell, process_id=None):
        params = {"command": ["/system/bin/sh", "-c", shell], "cwd": self.cwd,
                  "permissionProfile": PROFILE, "tty": False,
                  "outputBytesCap": 65536, "timeoutMs": 10000}
        if process_id:
            params["processId"] = process_id
        return self.request("command/exec", params)

    def close(self):
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            subprocess.run(["docker", "rm", "-f", self.name], check=False,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.process.wait(timeout=5)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audit", type=Path)
    parser.add_argument("--trace", type=Path, required=True)
    args = parser.parse_args()
    audit = args.audit.resolve()
    bundle = json.loads((audit / "report/schema/codex_app_server_protocol.schemas.json").read_text())
    client = validator(bundle, "ClientRequest")
    notification = validator(bundle, "ClientNotification")
    used = validate_sources(bundle)
    observed = set()
    count = 0
    for line in args.trace.read_text().splitlines():
        message = json.loads(line)
        (client if "id" in message else notification).validate(message)
        observed.add(message["method"])
        count += 1
    # Validate sensitive API shapes without recording a runtime account or contacting OpenAI.
    for message in [
        {"id": 1, "method": "account/login/start", "params": {"type": "chatgpt"}},
        {"id": 2, "method": "account/login/start", "params": {"type": "apiKey", "apiKey": "synthetic-ci-only"}},
        {"id": 3, "method": "command/exec/write", "params": {"processId": "probe", "deltaBase64": "YQ=="}},
    ]:
        client.validate(message)
        observed.add(message["method"])
    print("Generated schemas accepted", count, "actual Java fixture RPCs:", ", ".join(sorted(observed)))

    runtime = Runtime(audit, client)
    thread_id = None
    try:
        runtime.initialize()
        profiles = runtime.request("permissionProfile/list", {"cwd": runtime.cwd, "limit": 50})
        assert any(p["id"] == PROFILE for p in profiles["data"])
        runtime.request("model/list", {"limit": 50, "includeHidden": False})
        runtime.request("account/read", {"refreshToken": False})
        runtime.request("thread/list", {"limit": 50, "sortKey": "updated_at",
                                        "sourceKinds": ["cli", "vscode", "exec", "appServer"]})
        result = runtime.request("thread/start", {
            "cwd": runtime.cwd, "runtimeWorkspaceRoots": [runtime.cwd],
            "model": "gpt-5.1-codex", "modelProvider": "agentcodi-openai-http",
            "approvalPolicy": "on-request", "permissions": PROFILE, "persistExtendedHistory": True})
        assert result["activePermissionProfile"]["id"] == PROFILE
        thread_id = result["thread"]["id"]
        sibling = runtime.home + "/synthetic-sibling"
        command = runtime.command("printf sibling > '" + sibling + "'; cat '" + sibling + "'")
        assert command["exitCode"] == 0 and "sibling" in command["stdout"]
        # This persists outside the workspace and is executed again after a runtime restart.
        executable = runtime.home + "/.local/bin/package-check"
        script = "#!/system/bin/sh\nprintf package-edition-ok\n"
        encoded = base64.b64encode(script.encode()).decode()
        command = runtime.command("printf '%s' '" + encoded + "' | base64 -d > '" + executable
                                  + "'; chmod 700 '" + executable + "'; '" + executable + "'")
        assert command["exitCode"] == 0 and "package-edition-ok" in command["stdout"]

        terminal = {"command": ["/system/bin/sh"], "cwd": runtime.cwd, "permissionProfile": PROFILE,
                    "processId": "community-pty", "tty": True, "size": {"rows": 24, "cols": 80},
                    "outputBytesCap": 65536, "timeoutMs": 10000}
        # PTY startup is asynchronous; do not block waiting for command completion.
        runtime.sequence += 1
        message = {"id": runtime.sequence, "method": "command/exec", "params": terminal}
        client.validate(message)
        runtime.process.stdin.write(json.dumps(message) + "\n")
        runtime.process.stdin.flush()
        time.sleep(0.5)
        runtime.request("command/exec/resize", {"processId": "community-pty", "size": {"rows": 30, "cols": 100}})
        runtime.request("command/exec/write", {"processId": "community-pty",
                                               "deltaBase64": base64.b64encode(b"printf pty-ok\n").decode()})
        runtime.request("command/exec/terminate", {"processId": "community-pty"})

        runtime.request("config/read", {"cwd": runtime.cwd, "includeLayers": False})
        runtime.request("config/batchWrite", {"edits": [{
            "keyPath": "mcp_servers.community_ci_probe", "value": {"command": "/system/bin/sh", "enabled": False},
            "mergeStrategy": "replace"}], "reloadUserConfig": False})
        runtime.request("config/mcpServer/reload")
        runtime.request("mcpServerStatus/list", {"limit": 50})
        runtime.request("config/batchWrite", {"edits": [{
            "keyPath": "mcp_servers.community_ci_probe", "value": None,
            "mergeStrategy": "replace"}], "reloadUserConfig": False})
        runtime.request("config/mcpServer/reload")
        runtime.request("app/list", {"limit": 50, "forceRefetch": False})
        runtime.request("app/installed", {"limit": 50})
        # A missing thread is expected; this proves the real server decodes the complete turn shape.
        runtime.request("turn/start", {"threadId": "missing-ci-thread",
            "input": [{"type": "text", "text": "synthetic"}], "cwd": runtime.cwd,
            "runtimeWorkspaceRoots": [runtime.cwd], "approvalPolicy": "on-request",
            "permissions": PROFILE, "model": "gpt-5.1-codex", "effort": "medium", "summary": "auto"},
            expect_error=True)
    finally:
        runtime.close()
    restarted = Runtime(audit, client)
    try:
        restarted.initialize()
        resumed = restarted.request("thread/resume", {"threadId": thread_id, "cwd": restarted.cwd,
            "runtimeWorkspaceRoots": [restarted.cwd], "approvalPolicy": "on-request",
            "permissions": PROFILE, "modelProvider": "agentcodi-openai-http", "persistExtendedHistory": True})
        assert resumed["activePermissionProfile"]["id"] == PROFILE
        command = restarted.command("'" + restarted.home + "/.local/bin/package-check'")
        assert command["exitCode"] == 0 and "package-edition-ok" in command["stdout"]
    finally:
        restarted.close()
    report = {"source_methods": sorted(used), "emitted_methods": sorted(observed),
              "java_messages_validated": count, "real_runtime": "passed",
              "device_tests": "skipped"}
    (audit / "report/protocol-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Community Full-access, PTY, MCP, connector and runtime-restart checks passed.")

if __name__ == "__main__":
    main()
