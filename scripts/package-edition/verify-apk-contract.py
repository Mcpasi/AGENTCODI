#!/usr/bin/env python3
"""Verify the final Package Edition APK against the actual staged native/legal bytes."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contract():
    return json.loads((HERE / "apk-contract.json").read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def legal_path(name):
    path = PurePosixPath(name)
    return (len(path.parts) >= 3 and path.parts[:2] in
            (("share", "doc"), ("share", "licenses")) and
            any(word in path.name.lower() for word in ("copyright", "license", "copying", "notice")))


def bootstrap_evidence(archive_data, manifest_data, report_data):
    report = json.loads(report_data)
    require(digest(archive_data) == report["archive_sha256"], "Bootstrap archive checksum")
    require(digest(manifest_data) == report["manifest_sha256"], "Bootstrap manifest checksum")
    source = report.get("corresponding_sources", {})
    require(re.fullmatch(r"[0-9a-f]{64}", source.get("sha256", "")) is not None
            and source.get("archive", "").endswith(".tar.xz"), "Bootstrap corresponding sources missing")
    records = manifest_data.decode().splitlines()
    require(records and records[0] == "AGENTCODI_BOOTSTRAP_V1", "Bootstrap manifest format")
    files = {}
    for record in records[1:]:
        fields = record.split("\t")
        require(fields[0] in ("F", "L"), "Unknown bootstrap manifest record")
        if fields[0] == "F":
            require(len(fields) == 5 and fields[1] in ("600", "700"), "Invalid bootstrap file record")
            require(fields[4] not in files, "Duplicate bootstrap manifest path")
            files[fields[4]] = {"size": int(fields[2]), "sha256": fields[3]}
    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate bootstrap ZIP entry")
        require(set(names) == set(files), "Bootstrap ZIP/manifest file set")
        # Validate every file, including legal material and dpkg ownership.
        for name, record in files.items():
            require(name == PurePosixPath(name).as_posix() and
                    not name.startswith("/") and ".." not in PurePosixPath(name).parts,
                    "Unsafe bootstrap path")
            data = archive.read(name)
            require(len(data) == record["size"] and digest(data) == record["sha256"],
                    "Bootstrap bytes differ: " + name)
        inventory = report.get("licenses")
        require(isinstance(inventory, dict) and set(inventory) == set(report["packages"]),
                "Bootstrap package/license index differs")
        covered = set()
        gaps = []
        for package, records in inventory.items():
            listing = archive.read("var/lib/dpkg/info/" + package + ".list").decode().splitlines()
            if not records and package not in report.get("license_exemptions", {}):
                gaps.append("No package-local license text: " + package)
            for record in records:
                name = record["path"]
                require(legal_path(name) and name in files, "Missing bootstrap legal file: " + name)
                require(files[name] == {"size": record["size"], "sha256": record["sha256"]},
                        "Bootstrap license checksum differs: " + name)
                prefix = report["lock"]["target"]["prefix"]
                require(prefix + "/" + name in listing, "Bootstrap legal file has wrong package owner")
                covered.add(name)
        require(covered == {name for name in files if legal_path(name)},
                "Unindexed bootstrap legal material")
    return {"packages": report["packages"], "licenses": inventory,
            "corresponding_sources": source, "release_blockers": gaps}


def verify(apk_path, staged, project=REPO, release=False):
    spec = contract()
    staged = Path(staged)
    project = Path(project)
    abi_root = "lib/" + spec["abi"] + "/"
    expected_native = {abi_root + name for name in spec["native_files"]}
    expected_assets = set(spec["third_party_assets"])
    resource_sources = {name: project / "app/src/main" / name for name in spec["resources"]}
    report = {"format_version": 1, "contract": spec, "files": {},
              "release_blockers": list(spec["release_blockers"])}
    with zipfile.ZipFile(apk_path) as apk:
        names = [entry.filename for entry in apk.infolist() if not entry.is_dir()]
        require(len(names) == len(set(names)), "Duplicate APK ZIP entry")
        require({name for name in names if name.startswith("lib/")} == expected_native,
                "APK native file/ABI set differs")
        require({name for name in names if name.startswith("assets/")} == expected_assets,
                "APK asset set differs (retired or unexpected payload)")
        for name in sorted(expected_native | expected_assets | set(resource_sources)):
            data = apk.read(name)
            source = resource_sources.get(name, staged / name)
            require(data == source.read_bytes(), "APK bytes differ from staged source: " + name)
            require(bool(data), "Empty payload/legal file: " + name)
            report["files"][name] = {"size": len(data), "sha256": digest(data)}
        bootstrap_root = "assets/third-party/package-bootstrap/"
        evidence = bootstrap_evidence(
            apk.read(bootstrap_root + "bootstrap-aarch64.zip"),
            apk.read(bootstrap_root + "BOOTSTRAP-MANIFEST"),
            apk.read(bootstrap_root + "bootstrap-report.json"))
        index = json.loads(apk.read(bootstrap_root + "BOOTSTRAP-LICENSE-INDEX.json"))
        require(index["format_version"] == 1, "Bootstrap legal index format")
        require(index["packages"] == [
            {"name": name, "version": metadata["Version"], "files": evidence["licenses"][name],
             "notice": json.loads(apk.read(bootstrap_root + "bootstrap-report.json"))
                 .get("license_exemptions", {}).get(name, "")}
            for name, metadata in sorted(evidence["packages"].items())],
            "Bootstrap UI license index differs")
        report["bootstrap"] = evidence
        report["release_blockers"].extend(evidence["release_blockers"])
        # The complete LLVM source texts supplement the verbatim distributor notice.
        require(apk.read("assets/third-party/libcxx/LLVM-LICENSES") ==
                (project / "third_party/libcxx/LLVM-LICENSES").read_bytes(),
                "LLVM legal source differs")
    report["apk_sha256"] = digest(Path(apk_path).read_bytes())
    report["final_release_ready"] = not report["release_blockers"]
    require(not release or report["final_release_ready"], "Final APK license gate: " +
            "; ".join(report["release_blockers"]))
    return report


def check_sources():
    spec = contract()
    builder = (REPO / "scripts/build-debug-apk.sh").read_text()
    require("verify-apk-contract.py" in builder and "--native-files" in builder,
            "APK build must consume the final contract")
    local = (REPO / "scripts/test.sh").read_text()
    host = (REPO / ".github/ci/run-cpp-tests.sh").read_text()
    suites = {path.name for path in (REPO / "tests/cpp").glob("*_test.cpp")}
    for source, label in ((local, "local"), (host, "host")):
        require(set(re.findall(r"\b[a-z_]+_test\.cpp", source)) == suites,
                label + " C++ suite set differs from active tests")
        require("package_shell_main.cpp" in source, label + " runner must use the Package shell")
    require(len(spec["native_files"]) == len(set(spec["native_files"])) == 6,
            "Minimal native contract must contain exactly six files")
    require("libcodex-codehost.so" in spec["native_files"], "Missing standalone code-mode host")
    for path in spec["resources"]:
        require((REPO / "app/src/main" / path).is_file(), "Missing legal resource: " + path)
    print("Final Package Edition source/test contract verified.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-files", action="store_true")
    parser.add_argument("--check-sources", action="store_true")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--staged", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--release", action="store_true")
    args = parser.parse_args()
    if args.native_files:
        print("\n".join(contract()["native_files"]))
    elif args.check_sources:
        check_sources()
    elif args.apk and args.staged and args.output:
        result = verify(args.apk, args.staged, release=args.release)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print("Final APK payload and legal bytes verified; release blockers:", len(result["release_blockers"]))
    else:
        parser.error("use --check-sources, --native-files or --apk/--staged/--output")
