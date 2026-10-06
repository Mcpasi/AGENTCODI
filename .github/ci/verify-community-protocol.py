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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
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
    used.update(("initialize", "initialized", "account/login/start", "command/exec/write",
                 "item/commandExecution/requestApproval", "item/fileChange/requestApproval",
                 "item/tool/requestUserInput", "mcpServer/elicitation/request"))
    assert used <= available, "Missing source RPCs: " + repr(sorted(used - available))
    print("Source RPC inventory verified:", ", ".join(sorted(used)))
    return used


class ModelFixture:
    """Serve deterministic Responses SSE without credentials or external inference."""
    def __init__(self):
        self.requests = []
        self.probe_code = "text('community-code-host-ok');"
        self.mcp_probe = False
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                body = self.rfile.read(int(self.headers["Content-Length"]))
                fixture.requests.append(json.loads(body))
                number = len(fixture.requests)
                events = [{"type": "response.created", "response": {"id": "ci-" + str(number)}}]
                if number % 2 == 1:
                    item = ({"type": "function_call", "call_id": "community-mcp-probe",
                             "namespace": "mcp__approval_probe", "name": "echo",
                             "arguments": json.dumps({"message": "mcp-approved-once-ok"})}
                            if fixture.mcp_probe else
                            {"type": "custom_tool_call", "call_id": "community-host-probe",
                             "name": "exec", "input": fixture.probe_code})
                    events.append({"type": "response.output_item.done", "item": item})
                else:
                    events.append({"type": "response.output_item.done", "item": {
                        "type": "message", "role": "assistant", "id": "ci-message",
                        "content": [{"type": "output_text", "text": "Community host verified."}]}})
                events.append({"type": "response.completed", "response": {
                    "id": "ci-" + str(number), "usage": {
                        "input_tokens": 0, "output_tokens": 0, "total_tokens": 0,
                        "input_tokens_details": None, "output_tokens_details": None}}})
                payload = "".join("event: " + e["type"] + "\ndata: " + json.dumps(e) + "\n\n"
                                  for e in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:" + str(self.server.server_port) + "/v1"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

class McpApprovalFixture:
    """Local HTTP MCP tool: invocation count proves the approval gate is effective."""
    def __init__(self):
        self.calls = []
        fixture = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.send_error(405)

            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                method = request["method"]
                if "id" not in request:
                    self.send_response(202)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if method == "initialize":
                    result = {"protocolVersion": request["params"]["protocolVersion"],
                              "capabilities": {"tools": {}},
                              "serverInfo": {"name": "approval-probe", "version": "1"}}
                elif method == "tools/list":
                    result = {"tools": [{"name": "echo", "description": "Return a synthetic marker.",
                              "inputSchema": {"type": "object",
                                  "properties": {"message": {"type": "string"}},
                                  "required": ["message"]},
                              "annotations": {"readOnlyHint": True}}]}
                elif method == "tools/call":
                    fixture.calls.append(request["params"])
                    result = {"content": [{"type": "text",
                                          "text": request["params"]["arguments"]["message"]}]}
                else:
                    raise AssertionError("Unexpected MCP method: " + method)
                payload = json.dumps({"jsonrpc": "2.0", "id": request["id"],
                                      "result": result}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:" + str(self.server.server_port) + "/mcp"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class Runtime:
    def __init__(self, audit, validate, mock_url, code_mode_only=True):
        self.name = "community-probe-" + uuid.uuid4().hex
        prefix = "/data/data/com.termux/files/usr"
        probe = "/probe"
        runtime_data = audit / "runtime-data"
        runtime_data.mkdir(exist_ok=True, mode=0o777)
        runtime_data.chmod(0o777)
        command = [
            "docker", "run", "--rm", "-i", "--name", self.name,
            "--network", "host", "--entrypoint", "/entrypoint.sh", "-v", str(audit) + ":/audit",
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
        command[-1] += (
            ' -c \'model_providers.agentcodi-openai-http.requires_openai_auth=false\''
            ' -c \'model_providers.agentcodi-openai-http.base_url="' + mock_url + '"\''
            ' -c \'features.enable_request_compression=false\''
            " -c 'features.code_mode_only=" + str(code_mode_only).lower() + "'"
        )
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

    def wait_message(self, method):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            for index, message in enumerate(self.notifications):
                if message.get("method") == method:
                    return self.notifications.pop(index)
            try:
                self.notifications.append(json.loads(self.lines.get(timeout=1)))
            except queue.Empty:
                assert self.process.poll() is None, "".join(self.errors)
        raise AssertionError("Notification timed out: " + method + "\n" + "".join(self.errors)
                             + "\nPending messages: " + json.dumps(self.notifications[-6:]))

    def wait_notification(self, method):
        return self.wait_message(method)["params"]

    def respond_to_server_request(self, request_id, result):
        self.process.stdin.write(json.dumps({"id": request_id, "result": result}) + "\n")
        self.process.stdin.flush()

    def initialize(self):
        result = self.request("initialize", {
            "clientInfo": {"name": "agentcodi_android", "title": "AGENTCODI Package", "version": "0.1.0-package.2"},
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
    parser.add_argument("--mcp-approvals", type=Path, required=True)
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
    server_request = validator(bundle, "ServerRequest")
    scope = {"threadId": "ci-thread", "turnId": "ci-turn", "itemId": "ci-item"}
    for method, extra in [
        ("item/commandExecution/requestApproval", {"startedAtMs": 1, "command": "pwd", "cwd": "/probe/workspace"}),
        ("item/fileChange/requestApproval", {"startedAtMs": 1, "reason": "synthetic fixture"}),
        ("item/tool/requestUserInput", {"isBlocking": True, "autoResolutionMs": None,
          "questions": [{"id": "choice", "header": "Choice", "question": "Continue?",
                         "options": [{"label": "Continue", "description": "Proceed"}]}]}),
    ]:
        server_request.validate({"id": 1, "method": method, "params": dict(scope, **extra)})
        observed.add(method)
    mcp_response = validator(bundle, "McpServerElicitationRequestResponse")
    mcp_records = [json.loads(line) for line in args.mcp_approvals.read_text().splitlines()]
    assert mcp_records, "Java MCP approval audit must not be empty"
    mcp_actions = {}
    for record in mcp_records:
        assert record["request"]["method"] == "mcpServer/elicitation/request"
        server_request.validate(record["request"])
        mcp_response.validate(record["response"])
        mcp_actions[record["response"]["action"]] = record["response"]
    assert set(mcp_actions) == {"accept", "decline", "cancel"}, mcp_actions
    observed.add("mcpServer/elicitation/request")
    print("Generated schemas accepted", len(mcp_records),
          "actual Java MCP approval requests/responses, including nullable turn IDs.")
    print("Approval and user-input request shapes match the generated Community schema.")
    print("Generated schemas accepted", count, "actual Java fixture RPCs:", ", ".join(sorted(observed)))

    model = ModelFixture()
    runtime = Runtime(audit, client, model.url)
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
        fixture = runtime.command("printf imported-ci-content > '" + runtime.cwd + "/imported-content.bin'")
        assert fixture["exitCode"] == 0
        runtime.request("turn/start", {"threadId": thread_id,
            "input": [{"type": "text", "text": "run the synthetic host probe"},
                      {"type": "mention", "name": "VISIBLE-LABEL-MUST-NOT-BE-MODEL-CONTEXT.bin",
                       "path": runtime.cwd + "/imported-content.bin"}],
            "additionalContext": {"agentcodi-import-1": {"kind": "application",
                "value": "Read the actual bytes at " + runtime.cwd + "/imported-content.bin before answering."}},
            "cwd": runtime.cwd, "runtimeWorkspaceRoots": [runtime.cwd],
            "approvalPolicy": "on-request", "permissions": PROFILE,
            "model": "gpt-5.1-codex", "effort": "medium", "summary": "auto"})
        completed = runtime.wait_notification("turn/completed")
        assert completed["turn"]["status"] == "completed", completed
        assert len(model.requests) == 2, "Expected host call and follow-up model request"
        first_input = json.dumps(model.requests[0].get("input", []))
        assert runtime.cwd + "/imported-content.bin" in first_input, first_input
        assert "Read the actual bytes" in first_input, first_input
        assert "VISIBLE-LABEL-MUST-NOT-BE-MODEL-CONTEXT.bin" not in first_input, first_input
        follow_up = json.dumps(model.requests[1].get("input", []))
        assert "community-code-host-ok" in follow_up, follow_up
        assert "failed to spawn" not in follow_up and "failed to initialize" not in follow_up
        print("Relocated sibling code-mode host executed JavaScript successfully.")
    finally:
        runtime.close()
    # MCP tools are normally dispatched as model function calls. Do not force
    # experimental code-mode callbacks to test the native MCP approval protocol.
    runtime = Runtime(audit, client, model.url, code_mode_only=False)
    mcp = McpApprovalFixture()
    try:
        runtime.initialize()
        runtime.request("config/batchWrite", {"edits": [{
            "keyPath": "mcp_servers.approval_probe",
            "value": {"url": mcp.url, "enabled": True, "required": False,
                      "default_tools_approval_mode": "prompt"},
            "mergeStrategy": "replace"}], "reloadUserConfig": False})
        runtime.request("config/mcpServer/reload")
        probe = runtime.request("thread/start", {
            "cwd": runtime.cwd, "runtimeWorkspaceRoots": [runtime.cwd],
            "model": "gpt-5.1-codex", "modelProvider": "agentcodi-openai-http",
            "approvalPolicy": "on-request", "permissions": PROFILE,
            "persistExtendedHistory": True})
        probe_id = probe["thread"]["id"]
        model.mcp_probe = True
        for action in ("accept", "decline", "cancel"):
            before = len(mcp.calls)
            runtime.request("turn/start", {"threadId": probe_id,
                "input": [{"type": "text", "text": "Run the synthetic MCP approval probe."}],
                "cwd": runtime.cwd, "runtimeWorkspaceRoots": [runtime.cwd],
                "approvalPolicy": "on-request", "permissions": PROFILE,
                "model": "gpt-5.1-codex", "effort": "medium", "summary": "auto"})
            approval = runtime.wait_message("mcpServer/elicitation/request")
            server_request.validate(approval)
            params = approval["params"]
            assert params["threadId"] == probe_id and params["serverName"] == "approval_probe", params
            assert params["_meta"]["codex_approval_kind"] == "mcp_tool_call", params
            assert params["requestedSchema"]["properties"] == {}, params
            assert len(mcp.calls) == before, "MCP tool ran before the user decision"
            # Send the exact action object emitted by the Java controller regression.
            runtime.respond_to_server_request(approval["id"], mcp_actions[action])
            finished = runtime.wait_notification("turn/completed")
            expected = before + (1 if action == "accept" else 0)
            assert len(mcp.calls) == expected, (action, mcp.calls, finished)
            if action == "accept":
                assert finished["turn"]["status"] == "completed", finished
                assert "mcp-approved-once-ok" in json.dumps(model.requests[-1]["input"])
            print("Real MCP prompt gate passed:", action, "tool invocations:", len(mcp.calls))
        assert len(mcp.calls) == 1, "Only the explicitly accepted call may run"
        runtime.request("config/batchWrite", {"edits": [{
            "keyPath": "mcp_servers.approval_probe", "value": None,
            "mergeStrategy": "replace"}], "reloadUserConfig": False})
        runtime.request("config/mcpServer/reload")
    finally:
        mcp.close()
        runtime.close()
    restarted = Runtime(audit, client, model.url)
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
        model.close()
    report = {"source_methods": sorted(used), "emitted_methods": sorted(observed),
              "java_messages_validated": count, "real_runtime": "passed",
              "mcp_approval_messages_validated": len(mcp_records),
              "real_mcp_prompt_actions": sorted(mcp_actions),
              "device_tests": "skipped"}
    (audit / "report/protocol-verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Community Full-access, PTY, MCP, connector and runtime-restart checks passed.")

if __name__ == "__main__":
    main()
