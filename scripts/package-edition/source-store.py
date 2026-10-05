#!/usr/bin/env python3
"""Preserve complete source tar members using shared content-addressed objects."""
import hashlib
import json
import lzma
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile
import zipfile


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError("Unsafe source member: " + name)
    return path


def publish(source, root):
    members = []
    with tarfile.open(source, "r|xz") as archive:
        for member in archive:
            safe_path(member.name)
            if member.islnk():
                safe_path(member.linkname)
            if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                raise ValueError("Unsupported source member type: " + member.name)
            info = member.get_info()
            info["type"] = info["type"].decode("ascii")
            record = {"info": info, "pax_headers": member.pax_headers}
            if member.isfile():
                hasher = hashlib.sha256()
                count = 0
                with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024) as data:
                    with archive.extractfile(member) as stream:
                        while chunk := stream.read(1024 * 1024):
                            hasher.update(chunk)
                            data.write(chunk)
                            count += len(chunk)
                    if count != member.size:
                        raise ValueError("Truncated source member: " + member.name)
                    digest = hasher.hexdigest()
                    name = "sources/objects/" + digest[:2] + "/" + digest + ".xz"
                    target = root / name
                    if not target.exists():
                        target.parent.mkdir(parents=True, exist_ok=True)
                        data.seek(0)
                        with lzma.open(target, "wb", preset=6) as compressed:
                            shutil.copyfileobj(data, compressed)
                    record.update({"object": name, "sha256": digest})
            members.append(record)
    manifest = {"format_version": 1, "original_archive_sha256": checksum(source),
                "members": members}
    data = (json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n").encode()
    name = "sources/manifests/" + hashlib.sha256(data).hexdigest() + ".json"
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return name


def reconstruct(manifest, object_path, output):
    """Create an archive, never extract into the user's filesystem."""
    if manifest["format_version"] != 1:
        raise ValueError("Unsupported source manifest")
    if output.exists():
        raise ValueError("Source output already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        archive_path = Path(directory) / "sources.tar.xz"
        with tarfile.open(archive_path, "w:xz") as archive:
            for record in manifest["members"]:
                info = record["info"]
                safe_path(info["name"])
                member = tarfile.TarInfo(info["name"])
                for name, value in info.items():
                    setattr(member, name, value.encode("ascii") if name == "type" else value)
                member.pax_headers = record["pax_headers"]
                if member.islnk():
                    safe_path(member.linkname)
                if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                    raise ValueError("Unsupported source member type")
                if member.isfile():
                    expected = record["sha256"]
                    object_name = "sources/objects/" + expected[:2] + "/" + expected + ".xz"
                    if record["object"] != object_name:
                        raise ValueError("Source object identity differs")
                    hasher, count = hashlib.sha256(), 0
                    with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024) as data:
                        with lzma.open(object_path(object_name), "rb") as stream:
                            while chunk := stream.read(1024 * 1024):
                                count += len(chunk)
                                if count > member.size:
                                    raise ValueError("Oversized source object")
                                hasher.update(chunk)
                                data.write(chunk)
                        if count != member.size or hasher.hexdigest() != expected:
                            raise ValueError("Source object checksum or size differs")
                        data.seek(0)
                        archive.addfile(member, data)
                else:
                    archive.addfile(member)
        archive_path.replace(output)


def pack_small_objects(root):
    """Provide bounded-request downloads while retaining direct object URLs."""
    groups = {}
    for path in sorted((root / "sources/objects").rglob("*.xz")):
        if path.stat().st_size <= 65536:
            name = path.relative_to(root).as_posix()
            groups.setdefault(path.stem[0], []).append(name)
    packs = {}
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        for prefix, names in sorted(groups.items()):
            archive_path = temporary / (prefix + ".zip")
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_STORED) as archive:
                for name in names:
                    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                    info.create_system = 3
                    info.external_attr = 0o100644 << 16
                    archive.writestr(info, (root / name).read_bytes())
            name = "sources/packs/" + checksum(archive_path) + ".zip"
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.exists():
                shutil.copyfile(archive_path, target)
            packs[name] = names
    return packs


def packed_object(pack, name, record, output):
    """Check a named compressed object before writing it; never extract a ZIP."""
    safe_path(name)
    with zipfile.ZipFile(pack) as archive:
        matches = [info for info in archive.infolist() if info.filename == name]
        if len(matches) != 1 or matches[0].file_size != record["size"]:
            raise ValueError("Source pack entry is missing, duplicated or oversized")
        data = archive.read(matches[0])
    if len(data) != record["size"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
        raise ValueError("Packed source checksum differs")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    return output
