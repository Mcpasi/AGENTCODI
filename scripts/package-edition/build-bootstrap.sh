#!/bin/bash
# Inside the digest-pinned builder; fresh source-built target dependencies only.
set -euo pipefail
cd /home/builder/termux-packages
. ./agentcodi.env
. ./scripts/properties.sh
test "$TERMUX_PREFIX" = /data/data/de.agentcodi.pkg/files/usr
test "$TERMUX_PKG_API_LEVEL" = 29
export TERMUX_PKG_MAKE_PROCESSES=4
./agentcodi-build-package.sh dash bash ca-certificates dpkg apt
python3 /audit/scripts/package-edition/assemble-bootstrap.py \
    --debs ./output --output /bootstrap
cp agentcodi-preparation.json /bootstrap/
