#!/usr/bin/env python3
"""Preserve complete source tar members using shared content-addressed objects."""
import hashlib
import json
import lzma
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile


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
