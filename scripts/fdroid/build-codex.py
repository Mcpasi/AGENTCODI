#!/usr/bin/env python3
"""Run the pinned fork's build recipes locally, without publishing anything."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = Path(__file__).with_name("source-lock.json")
CODEX_RECIPE = ".github/workflows/termux-npm-build-publish.yml"
V8_RECIPE = ".github/workflows/rusty-v8-android-release.yml"
PAYLOAD = ("bin/codex.bin", "bin/codex-code-mode-host", "bin/libc++_shared.so",
           "LICENSE", "NOTICE", "package.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(args, cwd=None, env=None, capture=False):
    return subprocess.run(args, cwd=cwd, env=env, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def recipe_step(source, path, name):
    """Read one literal run block; reject ambiguous steps or expressions."""
    text = (source / path).read_text()
    marker = "      - name: " + name + "\n"
    require(text.count(marker) == 1, f"Recipe step is missing or ambiguous: {name}")
    step = text.split(marker, 1)[1].split("\n      - ", 1)[0]
    match = re.search(r"^        run: \|\n((?:          .*\n|\n)+)", step + "\n", re.M)
    require(match is not None, f"No literal shell recipe: {name}")
    script = "\n".join(line[10:] if line else "" for line in match[1].splitlines()) + "\n"
    require("${{" not in script, "Unresolved GitHub expression in build recipe")
    return script


def verify_checkout(path, commit, clean=True):
    require(path.is_dir() and not path.is_symlink(), f"Missing source checkout: {path}")
    actual = run(["git", "rev-parse", "HEAD"], path, capture=True).strip()
    require(actual == commit, f"Wrong source revision at {path}: {actual}; expected {commit}")
    if clean:
        require(not run(["git", "status", "--porcelain", "--untracked-files=normal"],
                        path, capture=True).strip(), f"Source checkout has local changes: {path}")


def checkout(path, repository, commit):
    if not path.exists():
        path.mkdir(parents=True)
        run(["git", "init", "--quiet", str(path)])
        run(["git", "remote", "add", "origin", repository], path)
    head = subprocess.run(["git", "rev-parse", "--verify", "HEAD"], cwd=path,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if head.returncode != 0:
        run(["git", "fetch", "--depth=1", "origin", commit], path)
        run(["git", "checkout", "--detach", "FETCH_HEAD"], path)
    verify_checkout(path, commit)


def verify_fork(source, lock):
    verify_checkout(source, lock["commit"])
    for name, expected in lock["files"].items():
        require(sha256(source / name) == expected, f"Pinned recipe/source changed: {name}")
    package = json.loads((source / "npm-package/package.json").read_text())
    require(package["version"] == lock["version"], "Fork package version differs from source lock")


def environment(source, v8, lock, cache):
    env = dict(os.environ)
    ndk = Path(env.get("ANDROID_NDK_HOME", env.get("ANDROID_NDK_ROOT", "/missing-ndk"))).resolve()
    sdk = Path(env.get("ANDROID_HOME", "/missing-sdk")).resolve()
    clang = Path(env.get("V8_SYSTEM_LIBCLANG_DIR", "/usr/lib/llvm-21/lib")).resolve()
    require(shutil.which("rustup") is not None, "Install Rust 1.95.0 with rustup and the aarch64-linux-android target")
    rust = run(["rustup", "run", lock["rust"], "rustc", "--version"], capture=True)
    require(rust.startswith("rustc " + lock["rust"] + " "), "Rust version differs from source lock")
    props = ndk / "source.properties"
    require(props.is_file() and re.search(r"^Pkg.Revision\s*=\s*" + re.escape(lock["ndk"]) + r"\s*$",
                                         props.read_text(), re.M), "ANDROID_NDK_HOME must select NDK " + lock["ndk"])
    require((sdk / "platforms" / ("android-" + lock["sdk_platform"])).is_dir(), "Missing Android SDK platform " + lock["sdk_platform"])
    require((sdk / "build-tools" / lock["sdk_build_tools"]).is_dir(), "Missing Android SDK build-tools " + lock["sdk_build_tools"])
    driver = clang.parent / "bin/clang"
    require(driver.is_file(), "V8_SYSTEM_LIBCLANG_DIR must select LLVM 21 libclang and its matching clang driver")
    require(re.search(r"clang version " + lock["libclang_major"] + r"\.", run([str(driver), "--version"], capture=True)),
            "V8 bindgen requires LLVM " + lock["libclang_major"])
    require(os.uname().machine == "x86_64" and sys.platform == "linux", "The fork recipes require a Linux x86_64 source builder")
    # Do not inherit external archives, Cargo flags, output directories or V8 overrides.
    for name in list(env):
        if name.startswith(("RUSTY_V8_", "V8_", "CARGO_TARGET_", "BINDGEN_")) or name in (
            "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_TARGET_DIR", "GN_ARGS", "GN", "SCCACHE",
            "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "CC", "CXX", "AR", "RANLIB", "CCACHE"):
            env.pop(name)
    env.update({"ANDROID_NDK_HOME": str(ndk), "ANDROID_NDK_ROOT": str(ndk),
                "ANDROID_HOME": str(sdk), "ANDROID_NDK_RELEASE": "r28",
                "V8_SYSTEM_LIBCLANG_DIR": str(clang), "V8_VERSION": lock["v8"]["version"],
                "CLANG_BASE_PATH": str(v8 / "third_party/llvm-build/Release+Asserts"),
                "CHECKOUT": str(v8), "TARGET": lock["target"], "SANDBOX": "true",
                "ANDROID_API_LEVEL": "21", "GITHUB_WORKSPACE": str(source),
                "RUSTUP_TOOLCHAIN": lock["rust"], "CARGO_INCREMENTAL": "0",
                "SOURCE_DATE_EPOCH": str(lock["source_date_epoch"]), "TZ": "UTC",
                "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "ZERO_AR_DATE": "1",
                "CARGO_BUILD_JOBS": env.get("AGENTCODI_BUILD_JOBS", "2")})
    env["PATH"] = str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin") + os.pathsep + env["PATH"]
    cargo_home = Path(env.get("CARGO_HOME", Path.home() / ".cargo")).resolve()
    require(not any(c.isspace() for c in str(cargo_home)), "Cargo's cache path must not contain whitespace")
    env["CARGO_HOME"] = str(cargo_home)
    native_remap = f"-ffile-prefix-map={source}=/usr/src/codex-termux -ffile-prefix-map={cargo_home}=/usr/src/cargo"
    env["CFLAGS_aarch64_linux_android"] = native_remap
    env["CXXFLAGS_aarch64_linux_android"] = native_remap
    env["GN"] = str(cache / "v8-tools/gn")
    env["NINJA"] = str(cache / "v8-tools/ninja")
    require(not any(c.isspace() for c in str(source)), "The fork recipes require a source path without whitespace")
    return env


def verify_v8_tools(cache, lock):
    for name, pin in lock["v8"]["tools"].items():
        path = cache / "v8-tools" / name
        require(path.is_file() and not path.is_symlink()
                and sha256(path) == pin["binary_sha256"],
                "Missing or changed locked V8 build tool: " + name + "; run prepare")


def verify_android_sources(v8, lock):
    for name, pin in lock["v8"]["android_sources"].items():
        verify_checkout(v8 / "third_party" / name, pin["commit"])


def prepare_v8_tools(v8, cache, lock):
    # The upstream helper uses HTTPSConnection directly and skips HTTP proxies.
    # urllib retains the managed proxy and CA settings. Resolve no mutable tags:
    # package IDs and both archive/binary hashes are committed in the lock.
    deps = (v8 / "v8/DEPS").read_text()
    directory = cache / "v8-tools"
    directory.mkdir(parents=True, exist_ok=True)
    for name, pin in lock["v8"]["tools"].items():
        version = re.search(r"'" + name + r"_version': '([^']+)'", deps)
        require(version and version[1] == pin["version"], "V8 build-tool version differs from its source DEPS")
        binary = directory / name
        if binary.is_file() and not binary.is_symlink() and sha256(binary) == pin["binary_sha256"]:
            continue
        print("Preparing locked V8 tool: " + name, flush=True)
        query = urllib.parse.urlencode({"package_name": pin["package"], "instance_id": pin["instance_id"]})
        url = "https://chrome-infra-packages.appspot.com/_ah/api/repo/v1/instance?" + query
        with urllib.request.urlopen(url, timeout=120) as response:
            metadata = json.load(response)
        with urllib.request.urlopen(metadata["fetch_url"], timeout=120) as response:
            archive_bytes = response.read()
        require(hashlib.sha256(archive_bytes).hexdigest() == pin["archive_sha256"], "V8 build-tool archive hash differs: " + name)
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            contents = archive.read(name)
        require(hashlib.sha256(contents).hexdigest() == pin["binary_sha256"], "V8 build-tool binary hash differs: " + name)
        partial = binary.with_suffix(".partial")
        partial.write_bytes(contents)
        partial.chmod(0o755)
        partial.replace(binary)
    verify_v8_tools(cache, lock)


def shell(script, source, env):
    run(["bash", "-Eeuo", "pipefail", "-c", script], source, env)


def v8_state(source):
    """Include submodule revisions and their actual patches, not just 'dirty'."""
    status = run(["git", "submodule", "status", "--recursive"], source, capture=True)
    require(all(line.startswith(" ") for line in status.splitlines()),
            "V8 submodules must match their pinned gitlink revisions")
    state = [status, run(["git", "diff", "--binary", "HEAD"], source, capture=True)]
    for line in status.splitlines():
        relative = line.split()[1]
        state.append(relative)
        state.append(run(["git", "diff", "--binary", "HEAD"], source / relative, capture=True))
    return hashlib.sha256("\n".join(state).encode()).hexdigest()


def load_exports(path, env, allowed):
    for line in path.read_text().splitlines():
        name, sep, value = line.partition("=")
        require(sep and name in allowed and "\x00" not in value, "Unexpected recipe export: " + name)
        env[name] = value


def deterministic_package(destination, files, epoch):
    """Explicit paths, owner, modes, ordering, and gzip header; no npm timestamps."""
    with destination.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
                for name, contents in sorted(files.items()):
                    entry = tarfile.TarInfo("package/" + name)
                    entry.size = len(contents)
                    entry.mtime = epoch
                    entry.mode = 0o755 if name in ("bin/codex.bin", "bin/codex-code-mode-host") else 0o644
                    entry.uid = entry.gid = 0
                    archive.addfile(entry, io.BytesIO(contents))


def verify_output(output, lock):
    manifest = output / "source-build.json"
    require(manifest.is_file() and not manifest.is_symlink(), "Build Codex from source first; missing " + str(manifest))
    record = json.loads(manifest.read_text())
    require(record.get("schema") == 1 and record.get("source_lock_sha256") == sha256(LOCK_PATH), "Source build uses a different source lock")
    require(record.get("builder_sha256") == sha256(__file__), "Source build uses a different builder")
    require(record.get("commit") == lock["commit"] and record.get("v8_commit") == lock["v8"]["commit"]
            and record.get("v8_from_source") is True, "Source build must include the locked fork and sandbox V8 source build")
    archive = output / "package.tgz"
    require(archive.is_file() and not archive.is_symlink() and sha256(archive) == record["archive_sha256"], "Source-built Codex archive checksum mismatch")
    with tarfile.open(archive, "r:gz") as package:
        entries = package.getmembers()
        require(sorted(m.name for m in entries) == sorted("package/" + n for n in PAYLOAD), "Unexpected source-built Codex archive entries")
        for entry in entries:
            require(entry.isfile() and entry.size <= 512 * 1024 * 1024, "Invalid source-built Codex archive member")
            data = package.extractfile(entry).read()
            require(hashlib.sha256(data).hexdigest() == record["payload_sha256"][entry.name[8:]], "Source-built Codex payload checksum mismatch")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "checkout", "host-dependencies", "disk-preparation", "prepare", "build", "verify", "shell-env"), nargs="?", default="build")
    args = parser.parse_args()
    lock = json.loads(LOCK_PATH.read_text())
    cache = Path(os.environ.get("AGENTCODI_SOURCE_CACHE", ROOT / ".cache/fdroid")).resolve()
    source = Path(os.environ.get("AGENTCODI_CODEX_SOURCE_DIR", cache / "codex-termux")).resolve()
    output = Path(os.environ.get("AGENTCODI_CODEX_BUILD_DIR", ROOT / "output/codex-source")).resolve()
    v8 = source / ".artifacts/rusty_v8-src" / ("v" + lock["v8"]["version"])
    if args.command == "plan":
        print(json.dumps(lock, indent=2, sort_keys=True))
        return
    if args.command in ("verify", "shell-env"):
        record = verify_output(output, lock)
        if args.command == "verify":
            print("Verified source-built Codex " + lock["commit"])
        else:
            values = {"CODEX_ANDROID_ARCHIVE": str(output / "package.tgz"),
                      "CODEX_ANDROID_SHA256": record["archive_sha256"],
                      "CODEX_APP_SERVER_SOURCE_SHA256": record["payload_sha256"]["bin/codex.bin"],
                      "CODEX_APP_SERVER_ANDROID_SHA256": record["payload_sha256"]["bin/codex.bin"],
                      "CODEX_CODE_MODE_HOST_SHA256": record["payload_sha256"]["bin/codex-code-mode-host"]}
            for name, value in values.items():
                print(name + "=" + shlex.quote(value))
        return
    if args.command in ("checkout", "prepare"):
        checkout(source, lock["repository"], lock["commit"])
    verify_fork(source, lock)
    if args.command == "checkout":
        print("Verified source checkout " + lock["commit"])
        return
    if args.command == "host-dependencies":
        print(recipe_step(source, V8_RECIPE, "Install host dependencies (gn / sysroot tools)"), end="")
        return
    if args.command == "disk-preparation":
        print(recipe_step(source, V8_RECIPE, "Free up disk before the V8 build"), end="")
        return
    env = environment(source, v8, lock, cache)
    with tempfile.TemporaryDirectory(prefix="agentcodi-recipes-") as temporary:
        env_file = Path(temporary) / "environment"
        env_file.touch()
        env["GITHUB_ENV"] = str(env_file)
        env["GITHUB_OUTPUT"] = str(Path(temporary) / "outputs")
        if args.command == "prepare":
            if v8.exists():
                verify_checkout(v8, lock["v8"]["commit"], clean=False)
                changed = run(["git", "diff", "--name-only", "HEAD"], v8, capture=True).splitlines()
                require(set(changed) <= {"build.rs", "build"},
                        "V8 checkout has changes outside the recipe's bindgen/config patch")
            else:
                checkout(v8, lock["v8"]["repository"], lock["v8"]["commit"])
            script = recipe_step(source, V8_RECIPE, "Prepare rusty_v8 source checkout at v${{ inputs.v8_version }}")
            shell(script, source, env)
            # rusty_v8's legacy Android cross path also names android_ndk
            # directly. Both layouts must select the same locked NDK, avoiding
            # its hidden fallback download of r26c during compilation.
            ndk_alias = v8 / "third_party/android_ndk"
            if not ndk_alias.exists() and not ndk_alias.is_symlink():
                ndk_alias.symlink_to(env["ANDROID_NDK_HOME"], target_is_directory=True)
            require(ndk_alias.resolve() == Path(env["ANDROID_NDK_HOME"]),
                    "V8 android_ndk alias must select the locked NDK")
            for name, pin in lock["v8"]["android_sources"].items():
                checkout(v8 / "third_party" / name, pin["repository"], pin["commit"])
            shell('(cd "$CHECKOUT" && python3 build/linux/sysroot_scripts/install-sysroot.py --arch=amd64)', source, env)
            # rusty_v8's build.rs also requests Chromium's Rust toolchain. Warm
            # its pinned sentinel here so the offline build never downloads it.
            shell('(cd "$CHECKOUT" && python3 tools/rust_toolchain.py)', source, env)
            prepare_v8_tools(v8, cache, lock)
            for directory in (source / "codex-rs", v8):
                run(["rustup", "run", lock["rust"], "cargo", "fetch", "--locked", "--target", lock["target"]], directory, env)
            cache.mkdir(parents=True, exist_ok=True)
            prepared = {"source_lock_sha256": sha256(LOCK_PATH), "v8_diff_sha256": v8_state(v8)}
            (cache / "prepared.json").write_text(json.dumps(prepared, sort_keys=True) + "\n")
            print("Sources prepared. Run build in a network-disabled environment next.")
            return
        prepared = json.loads((cache / "prepared.json").read_text())
        require(prepared["source_lock_sha256"] == sha256(LOCK_PATH), "Prepared sources use a different source lock")
        verify_v8_tools(cache, lock)
        verify_android_sources(v8, lock)
        require((v8 / "third_party/android_ndk").resolve() == Path(env["ANDROID_NDK_HOME"]),
                "Prepared V8 NDK alias differs from the locked NDK; run prepare")
        require((Path(env["CLANG_BASE_PATH"]) / "bin/clang").is_file(),
                "Prepared Chromium clang is missing; run prepare")
        verify_checkout(v8, lock["v8"]["commit"], clean=False)
        require(prepared["v8_diff_sha256"] == v8_state(v8), "Prepared V8 source patch has changed")
        env["CARGO_NET_OFFLINE"] = "true"
        shell(recipe_step(source, V8_RECIPE, "Check the bindgen toolchain before the long build"), source, env)
        load_exports(env_file, env, {"V8_LIBCLANG_DIR", "V8_CLANG_RESOURCE_DIR", "V8_ANDROID_SYSROOT", "V8_BINDGEN_CLANG_ARGS", "V8_ANDROID_API_LEVEL"})
        script = recipe_step(source, V8_RECIPE, "Build rusty_v8 from source for ${{ inputs.target }}")
        script = script.replace('cargo build --release --target', 'cargo build --frozen --release --target')
        # The sysroot was fetched in prepare; the build phase must never fetch it.
        script = script.replace('python3 build/linux/sysroot_scripts/install-sysroot.py --arch=amd64',
                                'test -n "$(find build/linux -maxdepth 1 -type d -name \'*_amd64-sysroot\' -print -quit)"')
        shell(script, source, env)
        shell(recipe_step(source, V8_RECIPE, "Locate build outputs"), source, env)
        located = {}
        load_exports(Path(env["GITHUB_OUTPUT"]), located, {"archive", "binding"})
        env["RUSTY_V8_ARCHIVE"] = located["archive"]
        env["RUSTY_V8_SRC_BINDING_PATH"] = located["binding"]
        shell('python3 scripts/check_v8_sandbox.py "$RUSTY_V8_ARCHIVE"', source, env)
        script = recipe_step(source, CODEX_RECIPE, "Build")
        start = script.index('eval "$(python3 scripts/fetch_rusty_v8_android.py')
        end = script.index("\n", start)
        script = script[:start] + "# V8 was built from the pinned source recipe above." + script[end:]
        script = script.replace('cargo build --target', 'cargo build --frozen --target')
        script = script.replace("          cd codex-rs", "cd codex-rs")
        script = script.replace("cd codex-rs", 'export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="$CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS --remap-path-prefix=$GITHUB_WORKSPACE=/usr/src/codex-termux --remap-path-prefix=$CARGO_HOME=/usr/src/cargo -Clink-arg=-Wl,--build-id=sha1"\ncd codex-rs')
        shell(script, source, env)
        output.mkdir(parents=True, exist_ok=True)
        target = source / "codex-rs/target" / lock["target"] / "release"
        toolchain = Path(env["ANDROID_NDK_HOME"]) / "toolchains/llvm/prebuilt/linux-x86_64"
        with tempfile.TemporaryDirectory(prefix="codex-package-") as package_temp:
            stage = Path(package_temp)
            files = {}
            for name, src in (("bin/codex.bin", target / "codex"),
                              ("bin/codex-code-mode-host", target / "codex-code-mode-host"),
                              ("bin/libc++_shared.so", toolchain / "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")):
                dst = stage / Path(name).name
                shutil.copyfile(src, dst)
                run([str(toolchain / "bin/llvm-strip"), "--strip-all", str(dst)])
                files[name] = dst.read_bytes()
            for name in ("LICENSE", "NOTICE"):
                files[name] = (source / "npm-package" / name).read_bytes()
            metadata = json.loads((source / "npm-package/package.json").read_text())
            metadata.update(gitHead=lock["commit"], agentcodiUpstreamCommit=lock["upstream_commit"])
            files["package.json"] = (json.dumps(metadata, sort_keys=True, indent=2) + "\n").encode()
            partial = output / "package.tgz.partial"
            deterministic_package(partial, files, lock["source_date_epoch"])
            partial.replace(output / "package.tgz")
        verify_fork(source, lock)
        verify_android_sources(v8, lock)
        require(prepared["v8_diff_sha256"] == v8_state(v8), "V8 sources changed during the build")
        record = {"schema": 1, "source_lock_sha256": sha256(LOCK_PATH), "builder_sha256": sha256(__file__),
                  "commit": lock["commit"], "v8_commit": lock["v8"]["commit"], "v8_from_source": True,
                  "source_date_epoch": lock["source_date_epoch"], "rustc": run(["rustup", "run", lock["rust"], "rustc", "-vV"], capture=True),
                  "ndk": lock["ndk"], "v8_archive_sha256": sha256(located["archive"]),
                  "v8_binding_sha256": sha256(located["binding"]), "archive_sha256": sha256(output / "package.tgz"),
                  "payload_sha256": {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}}
        (output / "source-build.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n")
        verify_output(output, lock)
        print("Built " + str(output / "package.tgz"))


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(f"Codex source build: {Path(error.cmd[0]).name} failed with exit status {error.returncode}; see the build output above.", file=sys.stderr)
        sys.exit(1)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print("Codex source build: " + str(error), file=sys.stderr)
        sys.exit(1)
