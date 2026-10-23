#!/usr/bin/env bash
# Shared by the CI image and the F-Droid buildserver's sudo phase.
set -Eeuo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run the Debian dependency installer as root." >&2
  exit 1
fi
. /etc/os-release
if [ "$ID" != debian ] || [ "$VERSION_CODENAME" != trixie ]; then
  echo "This build environment requires Debian 13 (trixie)." >&2
  exit 1
fi
export DEBIAN_FRONTEND=noninteractive
# Debian's slim image starts without a certificate store. Bootstrap it from
# the signed default archive before adding the HTTPS backports source.
apt-get update
apt-get install -y --no-install-recommends ca-certificates
# LLVM 21 is in Debian's signed backports archive. No Ubuntu repositories or
# downloaded apt.llvm.org installer are needed.
cat > /etc/apt/sources.list.d/agentcodi-backports.list <<'APT'
deb https://deb.debian.org/debian trixie-backports main
APT
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl git python3 python-is-python3 build-essential \
  cmake ninja-build autoconf automake autoconf-archive libtool-bin autopoint \
  gettext pkg-config bison flex gperf tcl texinfo help2man file binutils jq \
  patch unzip zip xz-utils zstd bzip2 lzip patchelf libssl-dev zlib1g-dev \
  libffi-dev libbz2-dev liblzma-dev libncurses-dev libreadline-dev uuid-dev \
  libc-ares-dev libsqlite3-dev nodejs rustup protobuf-compiler python3-pip \
  openjdk-21-jdk-headless apksigner zipalign ripgrep bsdutils
apt-get install -y --no-install-recommends -t trixie-backports \
  clang-21 lld-21 llvm-21 libclang-21-dev
clang++-21 --version | head -1 | grep -F '21.1.8'
# Runtime recipes install their cross-compiled dependencies in an isolated
# Termux prefix. Only its parent needs privileged creation.
mkdir -p /data/data
chmod 1777 /data /data/data
