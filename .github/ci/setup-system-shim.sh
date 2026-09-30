#!/usr/bin/env bash
# Creates the minimal /system layout the packaged-Android supervisor tests
# expect on a hosted Linux runner.
#
# tests/cpp/agentcodi_engine_test.cpp drives the real supervisor, which spawns
# and canonicalizes /system/bin/sh and validates the native payload read grant
# against /system/lib64. Three paths are enough:
#
#   /system/bin/sh   a real shell binary. It must be a COPY, not a symlink:
#                    the supervisor resolves the executable with realpath and
#                    compares it to the configured path, so a symlink pointing
#                    at /usr/bin/dash fails the canonical code-mode host check.
#   /system/lib64    the library grant directory used in the rejection tests.
#   /system/xbin     part of the tool search path the supervisor reports.
#
# On an Android device /system/bin/sh always exists, so this script exits
# without touching anything.
set -Eeuo pipefail

if [ -e /system/bin/sh ]; then
  echo "/system/bin/sh already exists; leaving /system untouched."
  exit 0
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

host_shell="$(readlink -f /bin/sh)"
if [ ! -x "$host_shell" ]; then
  echo "No usable host shell found at /bin/sh." >&2
  exit 1
fi

$SUDO mkdir -p /system/bin /system/xbin /system/lib64
$SUDO cp -- "$host_shell" /system/bin/sh
$SUDO chmod 755 /system/bin/sh
echo "Created /system/bin/sh (copy of $host_shell), /system/lib64 and /system/xbin."
