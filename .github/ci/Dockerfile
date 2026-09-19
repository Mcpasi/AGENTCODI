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
# Must not run as root: Termux disables its package manager for root. The
# image's own user is used, and apt is called by absolute path so the stage
# does not depend on the entrypoint having set PATH.
ARG CLANG_VERSION=21.1.8-3
RUN /data/data/com.termux/files/usr/bin/apt-get update -q \
 && /data/data/com.termux/files/usr/bin/apt-get install -y --no-install-recommends \
      "clang=${CLANG_VERSION}" "lld=${CLANG_VERSION}" "llvm=${CLANG_VERSION}"

FROM ${UBUNTU_IMAGE}
COPY --from=termux /data/data/com.termux /data/data/com.termux
# In the Termux image /system is a symlink into the prefix; recreate it so the
# packaged bionic executables find /system/bin/linker64.
RUN ln -s /data/data/com.termux/files/usr/opt/aosp /system
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-17-jdk-headless zipalign apksigner ripgrep file binutils dpkg \
      coreutils bsdutils findutils mawk diffutils grep sed tar \
      curl ca-certificates unzip zip \
 && rm -rf /var/lib/apt/lists/*
ENV AGENTCODI_JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
WORKDIR /workspace
