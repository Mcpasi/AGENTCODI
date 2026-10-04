#!/usr/bin/env python3
"""Record Community ELF relocation and schemas generated on ARM64/Bionic."""
import argparse
import hashlib
import json
import re
from pathlib import Path

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("audit", type=Path)
    args = parser.parse_args()
    root = args.audit
    lock = json.loads(Path(__file__).with_name("community-codex-release.json").read_text())
    binary = root / "payload/package/bin/codex.bin"
    data = binary.read_bytes()
    context = b"codex-code-mode-hostzshbincodex-resourcescodex-path"
    assert data.count(context) == 1, "Host context must be unique in this artifact"
    offset = data.index(context)
    assert offset == lock["apk_relocation"]["offset"], "Host offset differs from reviewed artifact"
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
    for name, expected in lock["native_sha256"].items():
        assert sha(root / "payload/package/bin" / name) == expected, name
    assert sha(target) == lock["apk_relocation"]["sha256"], "APK relocation digest"
    schema = root / "report/schema"
    if schema.exists():
        for key, name in [
            ("CODEX_SCHEMA_BUNDLE_SHA256", "codex_app_server_protocol.schemas.json"),
            ("CODEX_V2_SCHEMA_BUNDLE_SHA256", "codex_app_server_protocol.v2.schemas.json"),
        ]:
            pins[key] = sha(schema / name)
            assert pins[key] == lock["schema_sha256"][name], "Generated schema changed: " + name
        obj = json.loads((schema / "codex_app_server_protocol.schemas.json").read_text())
        for key in ("ClientRequest", "ServerRequest"):
            methods = [v["properties"]["method"]["enum"][0] for v in obj["definitions"][key]["oneOf"]]
            print(key + " methods=" + json.dumps(methods))
    build = Path("scripts/build-debug-apk.sh").read_text()
    for key, value in pins.items():
        actual = re.search(r'^' + key + r'="([^"]+)".write_text(json.dumps(pins, indent=2) + "\n")
    for key, value in pins.items():
        print(key + '="' + value + '"')

if __name__ == "__main__":
    main()
, build, re.M)
        assert actual and actual.group(1) == value, "Build pin differs: " + key
    (root / "report/runtime-pins.json").write_text(json.dumps(pins, indent=2) + "\n")
    for key, value in pins.items():
        print(key + '="' + value + '"')

if __name__ == "__main__":
    main()
