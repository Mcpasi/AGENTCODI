#!/usr/bin/env python3
"""Build and verify a scoped, signed APT snapshot from audited edition artifacts."""
import argparse
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("assembly", HERE / "assemble-bootstrap.py")
assembly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assembly)
source_spec = importlib.util.spec_from_file_location("sources", HERE / "source-store.py")
sources = importlib.util.module_from_spec(source_spec)
source_spec.loader.exec_module(sources)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def relative(value):
    path = PurePosixPath(value)
    if (path.is_absolute() or not path.parts or ".." in path.parts
            or path.as_posix() != value or any(c.isspace() for c in value)):
        raise ValueError("Unsafe repository path: " + value)
    return path


def config():
    settings = json.loads((HERE / "repository.json").read_text())
    lock = json.loads((HERE / "lock.json").read_text())
    fingerprint = settings["signing_fingerprint"]
    trusted = settings["trusted_fingerprints"]
    if settings["format_version"] != 1:
        raise ValueError("Unsupported repository configuration")
    if not isinstance(fingerprint, str) or not re.fullmatch(r"[A-F0-9]{40}", fingerprint):
        raise ValueError("Configure the full uppercase signing_fingerprint in repository.json")
    if (not trusted or len(trusted) != len(set(trusted)) or fingerprint not in trusted
            or any(not re.fullmatch(r"[A-F0-9]{40}", value) for value in trusted)):
        raise ValueError("Configure the exact trusted primary-key fingerprints")
    if not 86400 <= settings["valid_for_seconds"] <= 1209600:
        raise ValueError("Release validity must be between one and fourteen days")
    if not 0 < settings["max_site_bytes"] <= 1000000000:
        raise ValueError("Pages site size limit must not exceed one GB")
    if not re.fullmatch(r"[0-9]+(?:[.+~-][0-9A-Za-z]+)*", settings["keyring_version"]):
        raise ValueError("Invalid keyring package version")
    if (lock["repository"]["url"] != "https://mcpasi.github.io/AGENTCODI/apt/package-edition"
            or lock["repository"]["suite"] != "stable"
            or lock["repository"]["component"] != "main"):
        raise ValueError("Unexpected edition repository identity")
    relative(settings["public_key"])
    return settings, lock


def fingerprints(records):
    result, primary = [], False
    for line in records.splitlines():
        fields = line.split(":")
        if fields[0] in ("pub", "sec"):
            primary = True
        elif fields[0] in ("sub", "ssb"):
            primary = False
        elif fields[0] == "fpr" and primary:
            result.append(fields[9])
            primary = False
    return result


def export_keyring(settings, output):
    public = HERE / settings["public_key"]
    records = subprocess.check_output(
        ["gpg", "--batch", "--with-colons", "--show-keys", str(public)], text=True)
    if set(fingerprints(records)) != set(settings["trusted_fingerprints"]):
        raise ValueError("Public key file does not match the configured trust anchors")
    with tempfile.TemporaryDirectory() as home:
        subprocess.run(["gpg", "--homedir", home, "--batch", "--import", str(public)],
                       check=True, stdout=subprocess.DEVNULL)
        ring = subprocess.check_output(
            ["gpg", "--homedir", home, "--batch", "--export", *settings["trusted_fingerprints"]])
    if not ring:
        raise ValueError("Empty exported keyring")
    output.write_bytes(ring)
    return public


def signature(root, keyring):
    release = root / "dists/stable/Release"
    with tempfile.TemporaryDirectory() as home:
        for signed, detached in ((root / "dists/stable/InRelease", None),
                                 (root / "dists/stable/Release.gpg", release)):
            command = ["gpgv", "--homedir", home, "--keyring", str(keyring.resolve())]
            command += [str(signed)] + ([str(detached)] if detached else [])
            subprocess.run(command, check=True)
        plain = subprocess.check_output(
            ["gpgv", "--homedir", home, "--keyring", str(keyring.resolve()),
             "--output", "-", str(root / "dists/stable/InRelease")])
    if plain != release.read_bytes():
        raise ValueError("InRelease and Release contain different metadata")


def verify(root, keyring):
    signature(root, keyring)
    release = (root / "dists/stable/Release").read_text()
    hashes = release.split("\nSHA256:\n", 1)[1].splitlines()
    checked = set()
    for line in hashes:
        expected, size, name = line.split()
        path = root / "dists/stable" / relative(name)
        if path.is_symlink() or digest(path) != expected or path.stat().st_size != int(size):
            raise ValueError("Release checksum mismatch: " + name)
        checked.add(name)
    if not {"main/binary-aarch64/Packages", "main/binary-aarch64/Packages.gz",
            "repository-manifest.json"}.issubset(checked):
        raise ValueError("Release omits required signed metadata")
    manifest = json.loads((root / "dists/stable/repository-manifest.json").read_text())
    if manifest["format_version"] != 1:
        raise ValueError("Unsupported snapshot manifest")
    for name, record in manifest["files"].items():
        path = root / relative(name)
        if path.is_symlink() or path.stat().st_size != record["size"] or digest(path) != record["sha256"]:
            raise ValueError("Signed payload checksum mismatch: " + name)
    actual = {p.relative_to(root).as_posix()
              for folder in ("pool", "sources") for p in (root / folder).rglob("*") if p.is_file()}
    expected = {p for p in manifest["files"] if p.startswith(("pool/", "sources/"))}
    if actual != expected or any(p.is_symlink() for p in root.rglob("*")):
        raise ValueError("Snapshot contains undeclared payloads or symlinks")
    return manifest


def checked_bundle(directory, expected_lock):
    report = json.loads((directory / "bootstrap-report.json").read_text())
    if report["lock"] != expected_lock:
        raise ValueError("Artifact lock differs: " + str(directory))
    sums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split(maxsplit=1)
        name = name.removeprefix("*")
        relative(name)
        if name in sums or not re.fullmatch(r"[a-f0-9]{64}", checksum):
            raise ValueError("Invalid artifact checksum list")
        path = directory / name
        if path.is_symlink() or digest(path) != checksum:
            raise ValueError("Artifact checksum mismatch: " + name)
        sums[name] = checksum
    for name in ("bootstrap-report.json", report["corresponding_sources"]["archive"]):
        if name not in sums:
            raise ValueError("Artifact inventory omits: " + name)
    source = directory / relative(report["corresponding_sources"]["archive"])
    if digest(source) != report["corresponding_sources"]["sha256"]:
        raise ValueError("Corresponding-source checksum mismatch")
    packages = {}
    for deb in sorted(directory.glob("*.deb")):
        if deb.name not in sums:
            raise ValueError("DEB missing from artifact inventory")
        metadata = assembly.fields(assembly.command("dpkg-deb", "-f", str(deb)))
        name = metadata["Package"]
        evidence = report["packages"].get(name)
        if name in packages or not evidence or evidence["deb_sha256"] != digest(deb):
            raise ValueError("DEB differs from audited package report: " + name)
        if {k: v for k, v in evidence.items() if k != "deb_sha256"} != metadata:
            raise ValueError("DEB metadata differs from audited report: " + name)
        packages[name] = (deb, metadata)
    if set(packages) != set(report["packages"]):
        raise ValueError("Artifact package selection differs")
    return report, packages, source


def keyring_package(work, settings, keyring):
    package = work / "keyring"
    control = package / "DEBIAN"
    control.mkdir(parents=True)
    target = package / assembly.verify.PREFIX.lstrip("/") / "etc/apt/keyrings/agentcodi-package.gpg"
    target.parent.mkdir(parents=True)
    shutil.copyfile(keyring, target)
    target.chmod(0o644)
    text = ("Package: agentcodi-package-keyring\nVersion: " + settings["keyring_version"]
            + "\nArchitecture: all\nMaintainer: AGENTCODI Package Edition\n"
            "Description: Scoped public signing keys for the AGENTCODI package repository\n")
    (control / "control").write_text(text)
    deb = work / ("agentcodi-package-keyring_" + settings["keyring_version"] + "_all.deb")
    subprocess.run(["dpkg-deb", "--root-owner-group", "--build", str(package), str(deb)], check=True,
                   env={**os.environ, "SOURCE_DATE_EPOCH": "1791110575"})
    return deb, assembly.fields(text)


def audit_combined(packages, roots, output):
    # Reuse the complete bootstrap collision, prefix, ELF and interpreter audit.
    # Its temporary ZIP is discarded and never becomes an APK input.
    debs = output / "debs"
    debs.mkdir()
    for name, (deb, _) in packages.items():
        shutil.copyfile(deb, debs / (name + ".deb"))
    original_select = assembly.select
    assembly.select = lambda candidates, baseline: original_select(candidates, roots)
    try:
        assembly.assemble(debs, output / "audit", "readelf")
    finally:
        assembly.select = original_select


def sign_release(release_dir, fingerprint):
    for target, mode in (("InRelease", "--clearsign"), ("Release.gpg", "--detach-sign")):
        command = ["gpg", "--batch", "--no-tty", "--pinentry-mode", "loopback",
                   "--passphrase-fd", "0", "--digest-algo", "SHA256", "--armor",
                   "--local-user", fingerprint,
                   "--output", str(release_dir / target), mode, str(release_dir / "Release")]
        subprocess.run(command, check=True,
                       input=(os.environ.get("AGENTCODI_APT_SIGNING_PASSPHRASE", "") + "\n").encode())


def build(artifacts, output, previous):
    settings, lock = config()
    if output.exists():
        raise ValueError("Output must be a new directory")
    output.mkdir(parents=True)
    keyring = output / "keys/agentcodi-package.gpg"
    keyring.parent.mkdir()
    public = export_keyring(settings, keyring)
    shutil.copyfile(public, output / "keys/agentcodi-package.asc")
    catalog = json.loads((HERE / "catalog.json").read_text())
    prior = None
    if previous:
        prior = verify(previous, keyring)
        if prior["repository"] != lock["repository"]:
            raise ValueError("Previous snapshot belongs to a different repository")
        for folder in ("pool", "sources", "dists/stable/main/binary-aarch64/by-hash"):
            if (previous / folder).exists():
                shutil.copytree(previous / folder, output / folder, dirs_exist_ok=True)
    chosen, provenance, variants = {}, {}, {}
    bundles = [("bootstrap", "agentcodi-package-bootstrap")]
    bundles += [(group, "agentcodi-catalog-" + group) for group in sorted(catalog["groups"])]
    for group, folder in bundles:
        directory = artifacts / folder
        report, packages, source = checked_bundle(directory, lock)
        if group != "bootstrap":
            if (report["catalog"]["group"] != group
                    or report["catalog"]["roots"] != catalog["groups"][group]
                    or report["catalog"]["configuration_sha256"] != digest(HERE / "catalog.json")):
                raise ValueError("Artifact catalog configuration differs: " + group)
        source_name = sources.publish(source, output)
        provenance[group] = {"source_manifest": source_name,
                             "original_archive_sha256": digest(source)}
        if (directory / "source-build.json").exists():
            provenance[group]["source_build"] = json.loads((directory / "source-build.json").read_text())
        for name, (deb, metadata) in packages.items():
            if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name):
                raise ValueError("Invalid package name")
            if metadata["Architecture"] not in ("aarch64", "all"):
                raise ValueError("Foreign package architecture: " + name)
            variants.setdefault(name, []).append({"group": group, "sha256": digest(deb),
                                                   "installed_size": metadata.get("Installed-Size")})
            # Installed-Size describes this build's bytes, not dependency identity.
            # Keep the chosen DEB's actual value in Packages and record all variants.
            if name in chosen:
                previous_metadata = {k: v for k, v in chosen[name][1].items() if k != "Installed-Size"}
                current_metadata = {k: v for k, v in metadata.items() if k != "Installed-Size"}
                if previous_metadata != current_metadata:
                    differences = {k: [previous_metadata.get(k), current_metadata.get(k)]
                                   for k in previous_metadata.keys() | current_metadata.keys()
                                   if previous_metadata.get(k) != current_metadata.get(k)}
                    raise ValueError("Conflicting runtime metadata for " + name + ": " + json.dumps(differences))
            # Record non-bit-identical independent builds and prefer bootstrap bytes.
            chosen.setdefault(name, (deb, metadata))
    roots = [*lock["bootstrap"]["roots"], "bash", "agentcodi-package-keyring"]
    roots += [name for group in catalog["groups"].values() for name in group]
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        chosen["agentcodi-package-keyring"] = keyring_package(work, settings, keyring)
        selected = assembly.select(chosen, roots)
        if set(selected) != set(chosen):
            raise ValueError("Artifact union includes packages outside the runtime closure")
        audit_combined(chosen, roots, work)
        source_temp = work / "keyring-source.tar.xz"
        with tarfile.open(source_temp, "w:xz") as archive:
            for path in (HERE / "apt-repository.py", HERE / "assemble-bootstrap.py",
                         HERE / "verify-prefix.py", HERE / "build-apt-repository.sh",
                         HERE / "repository.json", HERE / "lock.json",
                         HERE / "source-store.py", HERE / "download-sources.py", public):
                archive.add(path, arcname="agentcodi/" + path.relative_to(HERE).as_posix())
            archive.add(HERE.parents[1] / "LICENSE", arcname="LICENSE")
        source_name = sources.publish(source_temp, output)
        provenance["keyring"] = {"source_manifest": source_name,
                                "original_archive_sha256": digest(source_temp)}
        records, package_manifest = [], {}
        for name, (deb, metadata) in sorted(chosen.items()):
            if prior and name in prior["packages"]:
                old = prior["packages"][name]
                if subprocess.run(["dpkg", "--compare-versions", metadata["Version"], "lt",
                                   old["Version"]]).returncode == 0:
                    raise ValueError("Repository update would downgrade: " + name)
                if metadata["Version"] == old["Version"] and digest(deb) != old["sha256"]:
                    raise ValueError("Changed published bytes require a version bump: " + name)
            filename = "pool/main/" + name + "/" + digest(deb) + "/" + name + ".deb"
            dest = output / filename
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(deb, dest)
            control = assembly.command("dpkg-deb", "-f", str(deb)).rstrip()
            if any(field in metadata for field in ("Filename", "Size", "SHA256")):
                raise ValueError("DEB control contains repository-only fields")
            records.append(control + "\nFilename: " + filename + "\nSize: " + str(dest.stat().st_size)
                           + "\nSHA256: " + digest(dest) + "\n")
            package_manifest[name] = {**metadata, "filename": filename, "sha256": digest(dest)}
    binary = output / "dists/stable/main/binary-aarch64"
    binary.mkdir(parents=True, exist_ok=True)
    (binary / "Packages").write_text("\n".join(records) + "\n")
    (binary / "Packages.gz").write_bytes(gzip.compress((binary / "Packages").read_bytes(), mtime=0))
    for path in (binary / "Packages", binary / "Packages.gz"):
        by_hash = binary / "by-hash/SHA256" / digest(path)
        by_hash.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, by_hash)
    source_packs = sources.pack_small_objects(output)
    files = {path.relative_to(output).as_posix(): {"size": path.stat().st_size, "sha256": digest(path)}
             for path in sorted(output.rglob("*")) if path.is_file()}
    manifest = {"format_version": 1, "repository": lock["repository"],
                "consumer_commit": os.environ.get("GITHUB_SHA"),
                "consumer_run": os.environ.get("GITHUB_RUN_ID"),
                "signing_fingerprint": settings["signing_fingerprint"],
                "packages": package_manifest, "provenance": provenance,
                "input_variants": variants, "source_packs": source_packs, "files": files}
    release_dir = output / "dists/stable"
    (release_dir / "repository-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    now = datetime.now(timezone.utc).replace(microsecond=0)
    metadata = ["Origin: AGENTCODI Package", "Label: AGENTCODI Package",
                "Suite: stable", "Codename: stable", "Architectures: aarch64",
                "Components: main", "Acquire-By-Hash: yes",
                "Date: " + format_datetime(now, usegmt=True),
                "Valid-Until: " + format_datetime(now + timedelta(seconds=settings["valid_for_seconds"]),
                                                usegmt=True), "SHA256:"]
    for path in (binary / "Packages", binary / "Packages.gz", release_dir / "repository-manifest.json"):
        metadata.append(" " + digest(path) + " " + str(path.stat().st_size)
                        + " " + path.relative_to(release_dir).as_posix())
    release = release_dir / "Release"
    release.write_text("\n".join(metadata) + "\n")
    sign_release(release_dir, settings["signing_fingerprint"])
    verify(output, keyring)
    size = sum(path.stat().st_size for path in output.rglob("*") if path.is_file())
    if size > settings["max_site_bytes"]:
        raise ValueError("Snapshot exceeds the configured Pages size budget: " + str(size))
    print(json.dumps({"packages": len(chosen), "bytes": size,
                      "signing_fingerprint": settings["signing_fingerprint"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    builder = sub.add_parser("build")
    builder.add_argument("--artifacts", type=Path, required=True)
    builder.add_argument("--output", type=Path, required=True)
    builder.add_argument("--previous", type=Path)
    verifier = sub.add_parser("verify")
    verifier.add_argument("--root", type=Path, required=True)
    verifier.add_argument("--keyring", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "build":
        build(args.artifacts, args.output, args.previous)
    else:
        verify(args.root, args.keyring)
