#!/usr/bin/env python3
"""Collect pinned source license files without authorizing publication."""
import argparse, base64, hashlib, json, re, subprocess, tomllib, zipfile
from pathlib import Path

def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def files(root):
    return [p for p in sorted(root.rglob("*")) if p.is_file() and not p.is_symlink()
            and ".git" not in p.parts and "target" not in p.relative_to(root).parts
            and re.match(r"^(license|licence|copying|copyright|notice)(?:$|[._-])", p.name, re.I)]

def main():
    parser = argparse.ArgumentParser()
    for option in ("codex", "v8", "output"):
        parser.add_argument("--" + option, type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    workspace = args.codex / "codex-rs"
    lock_data = (workspace / "Cargo.lock").read_bytes()
    locked = {(p["name"], p["version"]): p for p in tomllib.loads(lock_data.decode())["package"]}
    tree = run("cargo", "+1.95.0", "tree", "--locked", "--target", "aarch64-linux-android",
               "--edges", "normal", "--prefix", "none", "--format", "{p}",
               "-p", "codex-cli", "-p", "codex-code-mode-host", cwd=workspace)
    keys = set()
    for line in tree.splitlines():
        match = re.match(r"^(\\S+) v(\\S+)", line)
        if match:
            keys.add(match.groups())
    metadata = json.loads(run("cargo", "+1.95.0", "metadata", "--locked",
        "--format-version", "1", "--filter-platform", "aarch64-linux-android", cwd=workspace))
    if (workspace / "Cargo.lock").read_bytes() != lock_data:
        raise ValueError("Cargo.lock changed")
    components, payload, gaps = [], {}, []
    def add(component, root, candidates):
        records = []
        for p in sorted(set(candidates)):
            data = p.read_bytes()
            if not data:
                continue
            name = "texts/" + sha(data) + ".txt"
            payload[name] = data
            records.append({"source_path": p.relative_to(root).as_posix(), "path": name,
                            "size": len(data), "sha256": sha(data)})
        component["files"] = records
        if not records:
            gaps.append({k: component.get(k) for k in ("name", "version", "license", "repository", "source")})
        components.append(component)
    for p in metadata["packages"]:
        if (p["name"], p["version"]) not in keys:
            continue
        root = Path(p["manifest_path"]).parent
        candidates = files(root)
        if p.get("license_file"):
            candidates.append(root / p["license_file"])
        if p.get("source") is None:
            root = args.codex
            candidates.extend([root / "LICENSE", root / "NOTICE"])
        package = locked[(p["name"], p["version"])]
        add({"kind": "cargo-normal-closure", "name": p["name"], "version": p["version"],
             "license": p.get("license"), "repository": p.get("repository"),
             "source": p.get("source"), "checksum": package.get("checksum")}, root, candidates)
    if {(p["name"], p["version"]) for p in components} != keys:
        raise ValueError("Cargo inventory differs from requested closure")
    v8 = args.v8.resolve()
    submodules = []
    for line in run("git", "submodule", "status", "--recursive", cwd=v8).splitlines():
        if not line.startswith(" "):
            raise ValueError("Uninitialized or changed V8 submodule: " + line)
        commit, path = line.strip().split()[:2]
        submodules.append({"path": path, "commit": commit})
    add({"kind": "v8-source-material", "name": "rusty-v8-and-submodules", "version": "150.4.0",
         "source": run("git", "rev-parse", "HEAD", cwd=v8).strip(),
         "submodules": submodules}, v8, files(v8))
    toolchain = Path(run("rustc", "+1.95.0", "--print", "sysroot").strip())
    notice = toolchain / "share/doc/rust/COPYRIGHT-library.html"
    if not notice.is_file():
        gaps.append({"name": "rust-standard-library", "reason": "COPYRIGHT-library.html unavailable"})
    else:
        add({"kind": "rust-standard-library", "name": "rust-standard-library", "version": "1.95.0"},
            toolchain, [notice])
    index = {"format_version": 1,
        "codex_source_commit": run("git", "rev-parse", "HEAD", cwd=args.codex).strip(),
        "cargo_lock_sha256": sha(lock_data), "target": "aarch64-linux-android",
        "roots": ["codex-cli", "codex-code-mode-host"], "components": components, "gaps": gaps}
    (args.output / "DEPENDENCY-LICENSE-INDEX.json").write_text(json.dumps(index, indent=2) + "\n")
    with zipfile.ZipFile(args.output / "DEPENDENCY-LICENSES.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(payload.items()):
            item = zipfile.ZipInfo(name, (2026, 10, 6, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, data)
    summary = {"components": len(components), "unique_texts": len(payload), "gaps": gaps,
               "cargo_lock_sha256": index["cargo_lock_sha256"]}
    (args.output / "collection-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("LICENSE_RESEARCH_SUMMARY=" + json.dumps(summary))
    for name in ("DEPENDENCY-LICENSE-INDEX.json", "DEPENDENCY-LICENSES.zip"):
        encoded = base64.b64encode((args.output / name).read_bytes()).decode()
        for offset in range(0, len(encoded), 60000):
            print("LICENSE_EXPORT " + name + " " + str(offset) + " " + encoded[offset:offset+60000])

if __name__ == "__main__":
    main()
