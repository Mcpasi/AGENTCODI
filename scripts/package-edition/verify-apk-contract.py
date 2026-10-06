#!/usr/bin/env python3
"""Verify the final Package Edition APK against the actual staged native/legal bytes."""
import argparse
import hashlib
import io
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import tomllib
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
legal_spec = importlib.util.spec_from_file_location("legal_files", HERE / "legal-files.py")
legal_files = importlib.util.module_from_spec(legal_spec)
legal_spec.loader.exec_module(legal_files)
mpl_spec = importlib.util.spec_from_file_location("mpl_sources", REPO / ".github/ci/community-mpl-sources.py")
mpl_sources = importlib.util.module_from_spec(mpl_spec)
mpl_spec.loader.exec_module(mpl_sources)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contract():
    return json.loads((HERE / "apk-contract.json").read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


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
    links = {}
    for record in records[1:]:
        fields = record.split("\t")
        require(fields[0] in ("F", "L"), "Unknown bootstrap manifest record")
        if fields[0] == "L":
            require(len(fields) == 3 and fields[2] not in links, "Invalid bootstrap link record")
            links[fields[2]] = fields[1]
        if fields[0] == "F":
            require(len(fields) == 5 and fields[1] in ("600", "700"), "Invalid bootstrap file record")
            require(fields[4] not in files, "Duplicate bootstrap manifest path")
            files[fields[4]] = {"size": int(fields[2]), "sha256": fields[3]}
    require(not set(files).intersection(links), "Conflicting bootstrap file/link record")
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
                installed = record["installed_path"]
                require(legal_files.is_legal(installed) and legal_files.is_legal(name) and name in files,
                        "Missing bootstrap legal file: " + name)
                require(legal_files.resolve(installed, files, links) == name,
                        "Bootstrap legal link differs")
                require(files[name] == {"size": record["size"], "sha256": record["sha256"]},
                        "Bootstrap license checksum differs: " + name)
                prefix = report["lock"]["target"]["prefix"]
                require(prefix + "/" + installed in listing, "Bootstrap legal file has wrong package owner")
                covered.add(name)
        require(covered == {name for name in files if legal_files.is_legal(name)},
                "Unindexed bootstrap legal material")
    return {"packages": report["packages"], "licenses": inventory,
            "corresponding_sources": source, "release_blockers": gaps}


def community_evidence(archive_data, index_data, provenance_data, project):
    base = project / "third_party/community-codex"
    provenance = json.loads(provenance_data)
    index = json.loads(index_data)
    release = json.loads((project / ".github/ci/community-codex-release.json").read_text())
    require(provenance["format_version"] == index["format_version"] == 1, "Community legal format")
    require(provenance["community_release"] == release, "Community legal release binding")
    require(index["codex_source_commit"] == release["source_commit"], "Community legal source revision")
    lock_data = (base / "Cargo.lock").read_bytes()
    require(digest(lock_data) == index["cargo_lock_sha256"] == provenance["cargo_lock_sha256"],
            "Community legal Cargo.lock binding")
    require(digest(index_data) == provenance["index_sha256"] and
            digest(archive_data) == provenance["archive_sha256"], "Community legal artifact checksum")
    require(index["target"] == "aarch64-linux-android" and
            index["roots"] == ["codex-cli", "codex-code-mode-host"], "Community legal target/roots")
    locked = {(p["name"], p["version"]): p for p in tomllib.loads(lock_data.decode())["package"]}
    cargo = [c for c in index["components"] if c["kind"] == "cargo-normal-and-build-closure"]
    keys = {(c["name"], c["version"]) for c in cargo}
    require(len(keys) == len(cargo) == provenance["cargo_component_count"] and
            {"codex-cli", "codex-code-mode-host"}.issubset({name for name, _ in keys}),
            "Community legal component set")
    for component in cargo:
        package = locked.get((component["name"], component["version"]))
        require(package is not None and component["source"] == package.get("source") and
                component["checksum"] == package.get("checksum"), "Community legal package/source checksum")
    v8 = [c for c in index["components"] if c["kind"] == "v8-source-material"]
    std = [c for c in index["components"] if c["kind"] == "rust-standard-library"]
    require(len(v8) == len(std) == 1 and v8[0]["version"] == "150.4.0" and
            v8[0]["source"] == provenance["v8"]["source_commit"] and
            v8[0]["submodules"] == provenance["v8"]["submodules"] and
            std[0]["version"] == provenance["rust_toolchain"]["version"], "V8/Rust legal source binding")
    require(len(index["components"]) == len(cargo) + 2, "Unexpected Community legal component")
    covered = {}
    missing = []
    for component in index["components"]:
        if not component["files"]:
            missing.append(component["name"] + " " + component["version"])
        for record in component["files"]:
            require(re.fullmatch(r"texts/[0-9a-f]{64}[.]txt", record["path"]) is not None,
                    "Unsafe Community legal path")
            require(0 < record["size"] <= 512 * 1024, "Community legal display limit")
            expected = {"size": record["size"], "sha256": record["sha256"]}
            require(record["path"] not in covered or covered[record["path"]] == expected,
                    "Conflicting Community legal record")
            covered[record["path"]] = expected
    require(sorted(missing) == sorted(gap["name"] + " " + gap["version"] for gap in index["gaps"]),
            "Community legal gaps differ from inventory")
    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == set(covered), "Community legal ZIP file set")
        for name, expected in covered.items():
            data = archive.read(name)
            require(len(data) == expected["size"] and digest(data) == expected["sha256"],
                    "Community legal text checksum")
    return {"cargo_components": len(cargo), "unique_texts": len(covered),
            "provenance": provenance, "release_blockers":
            ["Missing Community dependency legal text: " + value for value in missing]}


def verify(apk_path, staged, project=REPO, release=False):
    spec = contract()
    staged = Path(staged)
    project = Path(project)
    abi_root = "lib/" + spec["abi"] + "/"
    expected_native = {abi_root + name for name in spec["native_files"]}
    expected_assets = set(spec["third_party_assets"])
    resource_sources = {name: project / "app/src/main" / name for name in spec["resources"]}
    report = {"format_version": 2, "contract": spec, "files": {},
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
        community_root = "assets/third-party/codex/"
        for filename in ("DEPENDENCY-LICENSES.zip", "DEPENDENCY-LICENSE-INDEX.json", "DEPENDENCY-PROVENANCE.json",
                         *mpl_sources.FILENAMES):
            require(apk.read(community_root + filename) ==
                    (project / "third_party/community-codex" / filename).read_bytes(),
                    "Community legal checked-in source differs")
        community = community_evidence(
            apk.read(community_root + "DEPENDENCY-LICENSES.zip"),
            apk.read(community_root + "DEPENDENCY-LICENSE-INDEX.json"),
            apk.read(community_root + "DEPENDENCY-PROVENANCE.json"), project)
        runtime = community["provenance"]["community_release"]
        require(digest(apk.read(abi_root + "libcodex.so")) == runtime["apk_relocation"]["sha256"] and
                digest(apk.read(abi_root + "libcodex-codehost.so")) ==
                runtime["native_sha256"]["codex-code-mode-host"], "Community legal native artifact binding")
        report["community"] = community
        report["release_blockers"].extend(community["release_blockers"])
        report["community"]["mpl_sources"] = mpl_sources.evidence(
            *(apk.read(community_root + name) for name in mpl_sources.FILENAMES),
            json.loads(apk.read(community_root + "DEPENDENCY-LICENSE-INDEX.json")),
            (project / "third_party/community-codex/Cargo.lock").read_bytes())
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
        require(apk.read("assets/third-party/libcxx/DISTRIBUTOR-LICENSE") ==
                (project / "third_party/libcxx/DISTRIBUTOR-LICENSE").read_bytes(),
                "LLVM distributor legal source differs")
        require(apk.read("assets/third-party/libcxx/LLVM-LICENSES") ==
                (project / "third_party/libcxx/LLVM-LICENSES").read_bytes(),
                "LLVM legal source differs")
    report["apk_sha256"] = digest(Path(apk_path).read_bytes())
    report["license_release_ready"] = not report["release_blockers"]
    report["device_tests"] = "Not performed by hosted CI; physical Android validation remains separate"
    require(not release or report["license_release_ready"], "Final APK license gate: " +
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
    mpl_sources.evidence(
        *((REPO / "third_party/community-codex" / name).read_bytes() for name in mpl_sources.FILENAMES),
        json.loads((REPO / "third_party/community-codex/DEPENDENCY-LICENSE-INDEX.json").read_bytes()),
        (REPO / "third_party/community-codex/Cargo.lock").read_bytes())
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
