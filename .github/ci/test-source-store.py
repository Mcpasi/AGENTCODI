#!/usr/bin/env python3
"""Check complete source reconstruction, shared storage and corruption rejection."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("sources", ROOT / "scripts/package-edition/source-store.py")
sources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sources)


def make(path, variant):
    with tarfile.open(path, "w:xz") as archive:
        folder = tarfile.TarInfo("source")
        folder.type, folder.mode = tarfile.DIRTYPE, 0o755
        archive.addfile(folder)
        for name, data, mode in (("shared", b"shared source\n" * 1024, 0o644),
                                 ("build.sh", variant.encode(), 0o755)):
            member = tarfile.TarInfo("source/" + name)
            member.size, member.mode, member.mtime = len(data), mode, 123456789
            member.pax_headers = {"comment": "source metadata"}
            archive.addfile(member, io.BytesIO(data))
        for kind, name, target in ((tarfile.SYMTYPE, "symbolic", "shared"),
                                   (tarfile.LNKTYPE, "hard", "source/shared")):
            member = tarfile.TarInfo("source/" + name)
            member.type, member.linkname = kind, target
            archive.addfile(member)


def inventory(path):
    with tarfile.open(path, "r:xz") as archive:
        return [(member.get_info(), member.pax_headers,
                 archive.extractfile(member).read() if member.isfile() or member.islnk() else None)
                for member in archive]


with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    first, second = root / "first.tar.xz", root / "second.tar.xz"
    make(first, "first recipe")
    make(second, "second recipe")
    names = [sources.publish(path, root / "site") for path in (first, second)]
    assert len(list((root / "site/sources/objects").rglob("*.xz"))) == 3
    for index, original in enumerate((first, second)):
        manifest = json.loads((root / "site" / names[index]).read_text())
        rebuilt = root / ("rebuilt-" + str(index) + ".tar.xz")
        sources.reconstruct(manifest, lambda name: root / "site" / name, rebuilt)
        assert inventory(original) == inventory(rebuilt), "Lost source data or tar metadata"
    manifest = json.loads((root / "site" / names[0]).read_text())
    record = next(item for item in manifest["members"] if "object" in item)
    (root / "site" / record["object"]).write_bytes(b"corrupted compressed source")
    failed = root / "failed.tar.xz"
    try:
        sources.reconstruct(manifest, lambda name: root / "site" / name, failed)
        raise AssertionError("Corrupt source accepted")
    except (ValueError, sources.lzma.LZMAError):
        assert not failed.exists(), "Partial archive published"
print("Complete source round trips, cross-group deduplication and corruption rejection passed.")
