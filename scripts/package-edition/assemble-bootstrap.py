#!/usr/bin/env python3
"""Assemble only the runtime dependency closure of locally rebuilt edition DEBs."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("verify", HERE / "verify-prefix.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def command(*args):
    return subprocess.check_output(args, text=True)


def fields(text):
    result = {}
    for line in text.splitlines():
        if line and not line[0].isspace() and ":" in line:
            key, value = line.split(":", 1)
            result[key] = value.strip()
    return result


def select(packages, roots):
    selected = {}
    pending = list(roots)
    while pending:
        name = pending.pop()
        if name in selected:
            continue
        if name not in packages:
            raise ValueError("Missing locally built runtime dependency: " + name)
        deb, metadata = packages[name]
        selected[name] = (deb, metadata)
        for key in ("Pre-Depends", "Depends"):
            for dependency in metadata.get(key, "").split(","):
                if not dependency.strip():
                    continue
                alternatives = [x.strip() for x in dependency.split("|")]
                chosen = None
                for alternative in alternatives:
                    match = re.fullmatch(r"([a-z0-9][a-z0-9+.-]*)(?:\s+\((<<|<=|=|>=|>>)\s+([^)]+)\))?", alternative)
                    if not match:
                        raise ValueError("Unsupported dependency: " + alternative)
                    candidate, op, version = match.groups()
                    if candidate in packages and (not op or subprocess.run(
                            ["dpkg", "--compare-versions", packages[candidate][1]["Version"],
                             op, version]).returncode == 0):
                        chosen = candidate
                        break
                if chosen is None:
                    raise ValueError("Unsatisfied runtime dependency: " + dependency)
                pending.append(chosen)
    return selected


def assemble(debs, output, readelf):
    lock = json.loads((HERE / "lock.json").read_text())
    packages = {}
    for deb in sorted(debs.glob("*.deb")):
        metadata = fields(command("dpkg-deb", "-f", str(deb)))
        name = metadata["Package"]
        if metadata["Architecture"] not in ("aarch64", "all"):
            raise ValueError("Wrong bootstrap ABI")
        if name in packages:
            raise ValueError("Duplicate package: " + name)
        packages[name] = (deb, metadata)
    # bash is needed by upstream package maintainer scripts; dash provides bin/sh.
    selected = select(packages, [*lock["bootstrap"]["roots"], "bash"])
    unsupported = set(lock["bootstrap"]["unsupported_packages"])
    if unsupported.intersection(selected):
        raise ValueError("Unadapted Termux app dependency")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        root = work / "root"
        prefix = root / verify.PREFIX.lstrip("/")
        prefix.mkdir(parents=True)
        info = prefix / "var/lib/dpkg/info"
        info.mkdir(parents=True)
        status = []
        reports = {}
        for name, (deb, metadata) in sorted(selected.items()):
            payload, control = work / name / "payload", work / name / "control"
            subprocess.run(["dpkg-deb", "-x", str(deb), str(payload)], check=True)
            subprocess.run(["dpkg-deb", "-e", str(deb), str(control)], check=True)
            reports[name] = verify.audit(payload, readelf)
            verify.check_control(control)
            file_list = []
            for path in sorted(payload.rglob("*")):
                relative = path.relative_to(payload)
                dest = root / relative
                if path.is_relative_to(payload / verify.PREFIX.lstrip("/")):
                    file_list.append("/" + relative.as_posix())
                if path.is_dir() and not path.is_symlink():
                    if dest.is_symlink() or (dest.exists() and not dest.is_dir()):
                        raise ValueError("Directory collision: " + str(relative))
                    dest.mkdir(parents=True, exist_ok=True)
                else:
                    if dest.exists() or dest.is_symlink():
                        raise ValueError("Package file collision: " + str(relative))
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    if path.is_symlink():
                        dest.symlink_to(os.readlink(path))
                    else:
                        shutil.copy2(path, dest)
            (info / (name + ".list")).write_text("\n".join(file_list) + "\n")
            for path in control.iterdir():
                if path.name != "control":
                    shutil.copy2(path, info / (name + "." + path.name))
            control_text = (control / "control").read_text().strip()
            status.append(control_text + "\nStatus: install ok unpacked\n")
            shutil.copy2(deb, output / deb.name)
        (prefix / "var/lib/dpkg/status").write_text("\n".join(status) + "\n")
        (prefix / "var/lib/dpkg/available").touch()
        for directory in ("var/lib/dpkg/updates", "var/lib/dpkg/triggers",
                          "var/lib/dpkg/alternatives", "var/log/apt",
                          "var/cache/apt/archives/partial", "var/lib/apt/lists/partial",
                          "etc/apt/preferences.d", "tmp"):
            (prefix / directory).mkdir(parents=True, exist_ok=True)
        # No key is invented: the planned signed source remains unusable until provisioned.
        (prefix / "etc/apt/apt.conf.d").mkdir(parents=True, exist_ok=True)
        (prefix / "etc/apt/apt.conf.d/00agentcodi").write_text(
            'APT::Sandbox::User "";\nAcquire::AllowInsecureRepositories "false";\n')
        audit = verify.audit(root, readelf)
        system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
        for path, report in audit["elf"].items():
            for library in re.findall(r"\(NEEDED\).*?\[([^]]+)\]", report):
                if library not in system and not (prefix / "lib" / library).exists():
                    raise ValueError("Missing ELF dependency " + library + " in " + path)
        entries = ["AGENTCODI_BOOTSTRAP_V1"]
        archive = output / "bootstrap-aarch64.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zipout:
            for path in sorted(prefix.rglob("*")):
                name = path.relative_to(prefix).as_posix()
                if path.is_symlink():
                    target = os.readlink(path)
                    if target.startswith(verify.PREFIX + "/"):
                        target = os.path.relpath(target, verify.PREFIX + "/" + str(Path(name).parent))
                    entries.append("L\t" + target + "\t" + name)
                elif path.is_file():
                    data = path.read_bytes()
                    mode = "700" if path.stat().st_mode & 0o111 else "600"
                    entries.append("F\t" + mode + "\t" + str(len(data)) + "\t" +
                                   hashlib.sha256(data).hexdigest() + "\t" + name)
                    entry = zipfile.ZipInfo(name, (2026, 10, 4, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    zipout.writestr(entry, data)
        manifest = output / "BOOTSTRAP-MANIFEST"
        manifest.write_text("\n".join(entries) + "\n")
        evidence = {"lock": lock, "packages": {
            name: {**metadata, "deb_sha256": hashlib.sha256(deb.read_bytes()).hexdigest()}
            for name, (deb, metadata) in sorted(selected.items())},
            "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
            "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
            "audit": audit}
        (output / "bootstrap-report.json").write_text(json.dumps(evidence, indent=2) + "\n")
        (output / "SHA256SUMS").write_text("".join(
            hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n"
            for p in sorted(output.iterdir()) if p.is_file() and p.name != "SHA256SUMS"))
        print(json.dumps({k: v for k, v in evidence.items() if k != "audit"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    assemble(args.debs, args.output, args.readelf)
