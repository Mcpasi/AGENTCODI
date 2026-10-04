#!/bin/bash
# Fresh digest-pinned source build; no restored binary dependencies.
set -euo pipefail
group="${1:?Pass a catalog group}"
cd /home/builder/termux-packages
. ./agentcodi.env
. ./scripts/properties.sh
test "$TERMUX_PREFIX" = /data/data/de.agentcodi.pkg/files/usr
test "$TERMUX_PKG_API_LEVEL" = 29
export TERMUX_PKG_MAKE_PROCESSES=4
mapfile -t roots < <(python3 - "$group" <<'PY'
import json, sys
from pathlib import Path
catalog = json.loads(Path("/audit/scripts/package-edition/catalog.json").read_text())
for name in catalog["groups"][sys.argv[1]]:
    print(name)
PY
)
(( ${#roots[@]} > 0 ))
./agentcodi-build-package.sh dash bash ca-certificates dpkg apt "${roots[@]}"
python3 /audit/scripts/package-edition/audit-catalog.py --debs ./output --output /catalog --group "$group"
cp agentcodi-preparation.json /catalog/
python3 /audit/scripts/package-edition/collect-bootstrap-sources.py \
    --recipes "$PWD" --build /home/builder/.termux-build --output /catalog
