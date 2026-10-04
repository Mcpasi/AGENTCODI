#!/usr/bin/env python3
"""Validate all source-built DEBs and the catalog's complete runtime closure."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("assembly", HERE / "assemble-bootstrap.py")
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)

def audit(debs, output, group):
    catalog = json.loads((HERE / "catalog.json").read_text())
    roots = catalog["groups"][group]
    output.mkdir(parents=True, exist_ok=True)
    built = {}
    # Audit build-only DEBs too, without installing them into the runtime.
    for deb in sorted(debs.glob("*.deb")):
        metadata = assembly.fields(assembly.command("dpkg-deb", "-f", str(deb)))
        name = metadata["Package"]
        if name in built:
            raise ValueError("Duplicate source-built package: " + name)
        with tempfile.TemporaryDirectory() as directory:
            payload, control = Path(directory) / "payload", Path(directory) / "control"
            payload.mkdir()
            control.mkdir()
            subprocess.run(["dpkg-deb", "-x", str(deb), str(payload)], check=True)
            subprocess.run(["dpkg-deb", "-e", str(deb), str(control)], check=True)
            assembly.verify.check_control(control)
            evidence = assembly.verify.audit(payload)
            built[name] = {**metadata, "deb_sha256": hashlib.sha256(deb.read_bytes()).hexdigest(),
                           "audit": evidence}
    # Use the same dependency/version, collision, interpreter and ELF checks as
    # the bootstrap. This ZIP is a CI layout fixture, never an APK input.
    original_select = assembly.select
    assembly.select = lambda packages, baseline: original_select(packages, [*baseline, *roots])
    assembly.assemble(debs, output, "readelf")
    report_path = output / "bootstrap-report.json"
    report = json.loads(report_path.read_text())
    report["catalog"] = {"group": group, "roots": roots,
                         "configuration_sha256": hashlib.sha256((HERE / "catalog.json").read_bytes()).hexdigest()}
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    (output / "all-built-packages.json").write_text(json.dumps(built, indent=2) + "\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--debs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--group", required=True)
    args = parser.parse_args()
    audit(args.debs, args.output, args.group)
