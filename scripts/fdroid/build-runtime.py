#!/usr/bin/env python3
"""Build AGENTCODI's Android inputs from locked sources and local recipes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
LOCK_PATH = HERE / "runtime-lock.json"
PREFIX = Path("/data/data/com.termux/files/usr")
ARCHIVES = {
    "AAPT2": "aapt2", "ABSEIL": "abseil-cpp", "PROTOBUF": "libprotobuf",
    "FMT": "fmt", "LIBCXX": "libc++", "EXPAT": "libexpat", "PNG": "libpng",
    "ZOPFLI": "libzopfli", "ZLIB": "zlib", "NODE": "nodejs-lts", "CARES": "c-ares",
    "ICU": "libicu", "SQLITE": "libsqlite", "OPENSSL": "openssl", "NPM": "npm",
    "PYTHON": "python", "ANDROID_POSIX_SEMAPHORE": "libandroid-posix-semaphore",
    "ANDROID_SUPPORT": "libandroid-support", "BZIP2": "libbz2", "LIBFFI": "libffi",
    "LIBLZMA": "liblzma", "NCURSES": "ncurses", "NCURSES_UI": "ncurses-ui-libs",
    "ZSTD": "zstd", "TERMUX_LICENSES": "termux-licenses", "PATCHELF": "patchelf",
    "RIPGREP": "ripgrep",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def capture(*args, **kwargs):
    return subprocess.check_output(list(map(str, args)), text=True, **kwargs).strip()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def recipe_hashes():
    files = [Path(__file__), HERE / "stage-npm.py", HERE / "compile-python-stdlib.py"] \
        + sorted((HERE / "recipes").glob("*.sh"))
    return {str(path.relative_to(ROOT)): sha256(path) for path in files}


def identity():
    return {"schema": 1, "lock_sha256": sha256(LOCK_PATH), "recipes_sha256": recipe_hashes()}


def verify_git(path, pin):
    require(capture("git", "-C", path, "rev-parse", "HEAD") == pin["commit"],
            "Source commit changed: " + str(path))
    require(not capture("git", "-C", path, "status", "--porcelain", "--untracked-files=all"),
            "Source checkout is dirty: " + str(path))
    status = subprocess.check_output(["git", "-C", str(path), "submodule", "status", "--recursive"], text=True).rstrip()
    require(all(line.startswith(" ") for line in status.splitlines()) if status else True,
            "Source submodule revision changed: " + str(path))
    run("git", "-C", path, "submodule", "foreach", "--quiet", "--recursive",
        'test -z "$(git status --porcelain --untracked-files=all)"')


def checkout(path, pin):
    if not (path / ".git").exists():
        path.mkdir(parents=True, exist_ok=True)
        run("git", "init", "--quiet", path)
        run("git", "-C", path, "remote", "add", "origin", pin["repository"])
        run("git", "-C", path, "fetch", "--depth=1", "origin", pin["commit"])
        run("git", "-C", path, "checkout", "--quiet", "--detach", "FETCH_HEAD")
        run("git", "-C", path, "submodule", "update", "--init", "--recursive", "--depth=1")
    verify_git(path, pin)


def download(cache, url, digest):
    require(re.fullmatch(r"[a-f0-9]{64}", digest), "Unhashed source: " + url)
    require(url.startswith("https://") and ".deb" not in url and "packages.termux.dev" not in url,
            "Prebuilt Android package is not a source: " + url)
    destination = cache / "blobs" / digest
    require(not destination.is_symlink(), "Linked source cache input")
    if destination.is_file() and sha256(destination) == digest:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(".partial")
    run("curl", "--fail", "--location", "--retry", "2", "--connect-timeout", "20",
        "--max-time", "900", "--silent", "--show-error", "--output", partial, url)
    require(sha256(partial) == digest, "Source checksum mismatch: " + url)
    partial.replace(destination)


def metadata(base, package):
    env = dict(os.environ, AGENTCODI_BASE_RECIPES=str(base), TERMUX_SCRIPTDIR=str(base),
               TERMUX_BUILD_TUPLE="x86_64-pc-linux-gnu", TERMUX_PREFIX=str(PREFIX),
               TERMUX_PACKAGE_FORMAT="debian", TERMUX__PREFIX__LIB_SUBDIR="lib",
               TERMUX__PREFIX__INCLUDE_SUBDIR="include")
    script = '''set -e
source "$1"
printf '%s\\0' "${TERMUX_PKG_VERSION[0]}"
for index in "${!TERMUX_PKG_SRCURL[@]}"; do
  printf '%s\\0%s\\0' "${TERMUX_PKG_SRCURL[$index]}" "${TERMUX_PKG_SHA256[$index]}"
done
'''
    values = subprocess.check_output(["bash", "-c", script, "recipe", str(HERE / "recipes" / (package + ".sh"))], env=env)
    values = values.decode().split("\0")[:-1]
    require(len(values) % 2 == 1, "Invalid source recipe metadata: " + package)
    return {"version": values[0], "sources": list(zip(values[1::2], values[2::2]))}


def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as stream:
        stream.extractall(destination, filter="data")


def prepare(cache, lock):
    checkout(cache / "base", lock["termux"])
    for package, pin in lock["git"].items():
        checkout(cache / "git" / package, pin)
    sources = {}
    for package in lock["order"]:
        item = metadata(cache / "base", package)
        sources[package] = item
        if package not in lock["git"]:
            for url, digest in item["sources"]:
                download(cache, url, digest)
    for item in lock["host_inputs"]:
        download(cache, item["url"], item["sha256"])
    # Cargo's vendor checksums also protect the unpacked source tree at build.
    rg = cache / "ripgrep-source"
    if not rg.exists():
        temporary = cache / "ripgrep-unpack"
        extract(cache / "blobs" / sources["ripgrep"]["sources"][0][1], temporary)
        next(temporary.iterdir()).rename(rg)
        temporary.rmdir()
    require(sha256(rg / "Cargo.lock") == lock["ripgrep_lock_sha256"], "ripgrep Cargo.lock changed")
    env = dict(os.environ, CARGO_HOME=str(cache / "cargo"))
    vendor = cache / "vendor"
    if not vendor.exists():
        config = subprocess.check_output(["cargo", "+" + lock["rust"], "vendor", "--locked",
            "--manifest-path", str(rg / "Cargo.toml"), "--versioned-dirs", str(vendor)], env=env)
        (cache / "cargo").mkdir(exist_ok=True)
        (cache / "cargo/config.toml").write_bytes(config)
    # Regenerate the directory selector when a prepared cache is moved.
    (cache / "cargo/config.toml").write_text('[source.crates-io]\nreplace-with = "vendored-sources"\n'
        '[source.vendored-sources]\ndirectory = ' + json.dumps(str(vendor)) + '\n')
    record = identity()
    record["sources"] = sources
    record["cargo_config_sha256"] = sha256(cache / "cargo/config.toml")
    write_json(cache / "prepared.json", record)


def verify_prepared(cache, lock):
    record = json.loads((cache / "prepared.json").read_text())
    require(all(record.get(k) == v for k, v in identity().items()), "Prepared recipes or source lock changed; run prepare")
    require(record.get("cargo_config_sha256") == sha256(cache / "cargo/config.toml"), "Prepared Cargo source selection changed")
    verify_git(cache / "base", lock["termux"])
    for package, pin in lock["git"].items():
        verify_git(cache / "git" / package, pin)
    for package in lock["order"]:
        actual = metadata(cache / "base", package)
        require(json.loads(json.dumps(actual)) == record["sources"][package], "Prepared source selection changed")
        if package not in lock["git"]:
            for _, digest in actual["sources"]:
                path = cache / "blobs" / digest
                require(path.is_file() and not path.is_symlink() and sha256(path) == digest,
                        "Prepared source changed: " + package)
    for item in lock["host_inputs"]:
        path = cache / "blobs" / item["sha256"]
        require(path.is_file() and not path.is_symlink() and sha256(path) == item["sha256"], "Prepared host input changed")
    return record


def setup_work(cache, work, lock):
    marker = work / "identity.json"
    if marker.exists():
        require(json.loads(marker.read_text()) == identity(), "Build recipes changed; use a fresh AGENTCODI_RUNTIME_WORK")
        require(PREFIX.exists(), "Build work belongs to a previous container; use a fresh AGENTCODI_RUNTIME_WORK")
        return
    require(not PREFIX.exists(), "Run the runtime source build in a fresh disposable Linux container (Termux prefix exists)")
    work.mkdir(parents=True, exist_ok=True)
    engine = work / "engine"
    require(not engine.exists(), "Incomplete engine setup; remove the temporary runtime work directory and retry")
    shutil.copytree(cache / "base", engine, symlinks=True,
                    ignore=shutil.ignore_patterns(".git"))
    shutil.copy2(HERE / "recipes/download.sh", engine / "scripts/build/termux_download.sh")
    # Copy the NDK once; a FUSE overlay needs privileges that F-Droid does not provide.
    toolchain = engine / "scripts/build/toolchain/termux_setup_toolchain_29.sh"
    text = toolchain.read_text()
    start = text.index('\tif ! mountpoint -q "${TERMUX_STANDALONE_TOOLCHAIN}"; then')
    end = text.index('\n\tif [ -f "${TERMUX_STANDALONE_TOOLCHAIN}/.termux-standalone-toolchain"', start)
    text = text[:start] + '''\tif [ ! -e "$TERMUX_STANDALONE_TOOLCHAIN/bin/clang" ]; then
        cp -a "$NDK/toolchains/llvm/prebuilt/linux-x86_64/." "$TERMUX_STANDALONE_TOOLCHAIN/"
    fi
''' + text[end:]
    toolchain.write_text(text)
    # The host interpreter only freezes/builds Python's target sources. Its
    # tests and pip are unnecessary and otherwise dominate disk/compile time.
    host_python = engine / "scripts/build/setup/termux_setup_build_python.sh"
    text = host_python.read_text()
    require(text.count("--with-ensurepip=install") == 1, "Host Python setup recipe changed")
    host_python.write_text(text.replace("--with-ensurepip=install", "--without-ensurepip --disable-test-modules"))
    # The two package recipes that use git copy only these verified checkouts.
    # No dependency resolver is used: the explicit local recipe order is complete.
    for package in lock["order"]:
        path = engine / "packages" / package
        path.mkdir(parents=True, exist_ok=True)
        (path / "build.sh").write_text('source "$AGENTCODI_PROJECT_ROOT/scripts/fdroid/recipes/' + package + '.sh"\n'
            'source "$AGENTCODI_PROJECT_ROOT/scripts/fdroid/recipes/common.sh"\n')
    # Unused Debian packaging helpers have a patch requiring extra source input.
    (engine / "packages/python/debpython.patch").unlink(missing_ok=True)
    prepare_toolchain(cache, work, lock, fetch=False)
    write_json(marker, identity())


def prepare_toolchain(cache, work, lock, fetch=True):
    """Also used by the x86_64 assembler, which needs no target build prefix."""
    items = [item for item in lock["host_inputs"] if item.get("extract") == "ndk"]
    require(len(items) == 1, "Expected one locked assembly NDK")
    item = items[0]
    if fetch:
        download(cache, item["url"], item["sha256"])
    archive = cache / "blobs" / item["sha256"]
    require(archive.is_file() and not archive.is_symlink() and sha256(archive) == item["sha256"],
            "Missing or changed prepared assembly NDK")
    destination = work / "tools"
    marker = destination / "ndk.sha256"
    if marker.is_file() and marker.read_text().strip() == item["sha256"]:
        require((destination / "android-ndk-r29/source.properties").is_file(), "Incomplete assembly NDK")
        return
    require(not destination.exists(), "Incomplete assembly NDK; remove " + str(destination))
    destination.mkdir(parents=True)
    run("unzip", "-q", archive, "-d", destination)
    marker.write_text(item["sha256"] + "\n")


def build(cache, output, work, lock, through):
    verify_prepared(cache, lock)
    require(capture("uname", "-m") == "x86_64", "Runtime recipes require Linux x86_64")
    require(capture("rustc", "+" + lock["rust"], "--version").startswith("rustc " + lock["rust"] + " "), "Wrong Rust toolchain")
    setup_work(cache, work, lock)
    initial_identity = identity()
    output.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, AGENTCODI_PROJECT_ROOT=str(ROOT), AGENTCODI_BASE_RECIPES=str(cache / "base"),
        AGENTCODI_RUNTIME_CACHE=str(cache), AGENTCODI_RUNTIME_WORK=str(work),
        TERMUX_TOPDIR=str(work / "build"), TERMUX_PKG_API_LEVEL=str(lock["android_api"]),
        NDK=str(work / "tools/android-ndk-r29"), ANDROID_HOME=str(work / "sdk"),
        TERMUX_PKG_MAKE_PROCESSES=os.environ.get("AGENTCODI_BUILD_JOBS", str(os.cpu_count())),
        CARGO_HOME=str(cache / "cargo"), SOURCE_DATE_EPOCH=str(lock["source_date_epoch"]),
        LANG="C.UTF-8", LC_ALL="C.UTF-8", TZ="UTC")
    for key in list(env):
        if key.startswith("CARGO_PROFILE_") or key in (
                "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_RUSTFLAGS",
                "RUSTC", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER"):
            del env[key]
    env["CARGO_INCREMENTAL"] = "0"
    for package in lock["order"]:
        require(identity() == initial_identity, "Source recipes changed during this build")
        print("Building from source:", package, flush=True)
        run("bash", work / "engine/build-package.sh", "-a", "aarch64", "-s",
            "-o", output / "debs", package, env=env)
        # The target prefix and DEBs contain the completed dependency. Keep
        # ICU's host build until Node has installed its code generators; all
        # other package work can be discarded to fit small source builders.
        if package != "libicu" and (work / "build" / package).exists():
            shutil.rmtree(work / "build" / package)
        if package == "nodejs-lts" and (work / "build/libicu").exists():
            shutil.rmtree(work / "build/libicu")
        if through == package:
            print("Partial build only; no complete source-build.json was created.")
            return
    # Select exactly one source-built package for each assembler input.
    selected = {}
    for name, package in ARCHIVES.items():
        matches = list((output / "debs").glob(package + "_*.deb"))
        require(len(matches) == 1, "Missing or ambiguous source-built package: " + package)
        selected[name] = str(matches[0].relative_to(output))
    require(identity() == initial_identity, "Source recipes changed during this build")
    record = initial_identity
    record.update({"from_source": True, "archives": selected,
        "files_sha256": {path: sha256(output / path) for path in sorted(selected.values())},
        "sources": json.loads((cache / "prepared.json").read_text())["sources"]})
    write_json(output / "source-build.json", record)
    verify_output(output)


def verify_output(output):
    record = json.loads((output / "source-build.json").read_text())
    require(all(record.get(k) == v for k, v in identity().items()), "Source build uses different recipes or source lock")
    require(record.get("from_source") is True and set(record.get("archives", {})) == set(ARCHIVES),
            "Incomplete source-built input set")
    require(set(record["archives"].values()) == set(record.get("files_sha256", {})), "Source archive inventory differs")
    for name, path in record["archives"].items():
        require(re.fullmatch(r"debs/" + re.escape(ARCHIVES[name]) + r"_[A-Za-z0-9.+:~_-]+\.deb", path), "Invalid source archive path")
        file = output / path
        require(file.is_file() and not file.is_symlink() and file.stat().st_nlink == 1
                and sha256(file) == record["files_sha256"][path], "Source-built package changed: " + path)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("plan", "prepare", "prepare-toolchain", "build", "verify", "shell-env"))
    parser.add_argument("--through", help="Stop after a recipe for diagnosis, without producing a complete build receipt")
    args = parser.parse_args()
    lock = json.loads(LOCK_PATH.read_text())
    cache = Path(os.environ.get("AGENTCODI_RUNTIME_CACHE", ROOT / ".cache/runtime-source")).resolve()
    output = Path(os.environ.get("AGENTCODI_RUNTIME_BUILD_DIR", ROOT / "output/runtime-source")).resolve()
    work = Path(os.environ.get("AGENTCODI_RUNTIME_WORK", ROOT / ".build/runtime-source")).resolve()
    require(args.through is None or (args.phase == "build" and args.through in lock["order"]), "Invalid partial build selection")
    if args.phase == "plan":
        print(json.dumps(lock, indent=2))
    elif args.phase == "prepare":
        prepare(cache, lock)
    elif args.phase == "prepare-toolchain":
        prepare_toolchain(cache, work, lock)
    elif args.phase == "build":
        build(cache, output, work, lock, args.through)
    else:
        record = verify_output(output)
        if args.phase == "shell-env":
            for name, path in record["archives"].items():
                print(name + "_ARCHIVE=" + shlex.quote(str(output / path)))
                print(name + "_SHA256=" + shlex.quote(record["files_sha256"][path]))
        else:
            print("Verified the complete source-built Android input set.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print("Runtime source build:", error, file=sys.stderr)
        sys.exit(1)
