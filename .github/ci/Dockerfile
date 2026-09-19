# Build environment for scripts/build-debug-apk.sh on a hosted arm64 runner.
#
# The build host is a hybrid, and this image reproduces it:
#
#   * Ubuntu arm64 supplies every command the build requires. The package list
#     below was derived from the build host by resolving each command in the
#     build script's own require_command list back to its owning package:
#     apksigner, zipalign, ripgrep, binutils (readelf/strings), bsdutils
#     (script), dpkg (dpkg-deb), findutils, mawk and coreutils. Termux does not
#     package zipalign at all, so a pure Termux image cannot work.
#   * The Termux prefix supplies the pinned LLVM toolchain. Its generated code
#     is covered by the build's derived *_RUNTIME_SHA256 pins, so the version
#     is pinned here and checked again by the build script itself.
#   * The Android linker and bionic libraries come from the Termux image, which
#     ships them as aosp-libs. Without them the build cannot execute the
#     packaged aapt2, patchelf, Python or Codex app-server, all of which are
#     bionic binaries.
ARG TERMUX_IMAGE=termux/termux-docker:aarch64
ARG UBUNTU_IMAGE=ubuntu:26.04

FROM ${TERMUX_IMAGE} AS termux
# The image sets no USER, so RUN executes as root — and Termux refuses to run
# its package manager as root. Privileges are dropped by the image's own
# entrypoint, which switches to the `system` user and execs its arguments, so
# the install goes through it. That is also the invocation the image documents
# for non-interactive use. The entrypoint forwards only a fixed set of
# variables, so the pinned version is substituted into the command by Docker
# rather than passed as an environment variable.
ARG CLANG_VERSION=21.1.8-3
RUN /entrypoint.sh bash -c "apt-get update -q \
 && apt-get install -y --no-install-recommends \
      clang=${CLANG_VERSION} lld=${CLANG_VERSION} llvm=${CLANG_VERSION} \
 && clang++ --version && ld.lld --version && llvm-objcopy --version"

FROM ${UBUNTU_IMAGE}
COPY --from=termux /data/data/com.termux /data/data/com.termux
# The Termux image keeps the Android linker in an aosp-libs directory that /system
# links to. Locate it instead of assuming the path, and fail here with a clear
# message rather than leaving a dangling symlink that only breaks mid-build.
RUN set -eux; \
    linker="$(find /data/data/com.termux -name linker64 -print -quit)"; \
    if [ -z "$linker" ]; then \
      echo "No Android linker64 found in the Termux prefix." >&2; \
      exit 1; \
    fi; \
    aosp="$(dirname "$(dirname "$linker")")"; \
    echo "Android system directory: $aosp"; \
    ln -s "$aosp" /system; \
    test -x /system/bin/linker64
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-17-jdk-headless zipalign apksigner ripgrep file binutils dpkg \
      coreutils bsdutils findutils mawk diffutils grep sed tar \
      curl ca-certificates unzip zip \
 && rm -rf /var/lib/apt/lists/*
# The build host runs under C.UTF-8. Without it the JVM derives
# sun.jnu.encoding from the POSIX locale and resolves to ANSI_X3.4-1968, so any
# path holding a non-ASCII character — the workspace browser tests create one —
# fails with InvalidPathException before the test itself can run.
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENV AGENTCODI_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
WORKDIR /workspace
