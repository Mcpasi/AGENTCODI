#!/usr/bin/env python3
"""Record Community ELF relocation and schemas generated on ARM64/Bionic."""
import argparse
import hashlib
import json
from pathlib import Path

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audit", type=Path)
    args = parser.parse_args()
    root = args.audit
    binary = root / "payload/package/bin/codex.bin"
    data = binary.read_bytes()
    context = b"codex-code-mode-hostzshbincodex-resourcescodex-path"
    assert data.count(context) == 1, "Host context must be unique in this artifact"
    offset = data.index(context)
    patched = data[:offset] + b"libcodex-codehost.so" + data[offset + len(b"codex-code-mode-host"):]
    assert len(data) == len(patched)
    target = root / "payload/package/bin/libcodex.so"
    target.write_bytes(patched)
    target.chmod(0o755)
    (root / "payload/package/bin/libcodex-codehost.so").write_bytes(
        (root / "payload/package/bin/codex-code-mode-host").read_bytes())
    (root / "payload/package/bin/libcodex-codehost.so").chmod(0o755)
    pins = {
        "CODEX_DEFAULT_HOST_OFFSET": str(offset),
        "CODEX_APP_SERVER_SOURCE_SHA256": sha(binary),
        "CODEX_APP_SERVER_ANDROID_SHA256": sha(target),
        "CODEX_CODE_MODE_HOST_SHA256": sha(root / "payload/package/bin/codex-code-mode-host"),
        "CODEX_LICENSE_SHA256": sha(root / "payload/package/LICENSE"),
        "CODEX_NOTICE_SHA256": sha(root / "payload/package/NOTICE"),
    }
    schema = root / "report/schema"
    if schema.exists():
        for key, name in [
            ("CODEX_SCHEMA_BUNDLE_SHA256", "codex_app_server_protocol.schemas.json"),
            ("CODEX_V2_SCHEMA_BUNDLE_SHA256", "codex_app_server_protocol.v2.schemas.json"),
        ]:
            pins[key] = sha(schema / name)
        for name in sorted(schema.glob("*.json")):
            # Compact RPC/shape inventory makes CI findings inspectable via job logs.
            obj = json.loads(name.read_text())
            if name.name == "codex_app_server_protocol.schemas.json":
                for key in ("ClientRequest", "ServerRequest"):
                    print(key + "=" + json.dumps(obj.get("definitions", {}).get(key), separators=(",", ":")))
    (root / "report/runtime-pins.json").write_text(json.dumps(pins, indent=2) + "\n")
    for key, value in pins.items():
        print(key + '="' + value + '"')

if __name__ == "__main__":
    main()
