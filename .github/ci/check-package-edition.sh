#!/bin/bash
# Hosted contract checks. Package/bootstrap product builds are a later step.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
build_root="$(mktemp -d "${RUNNER_TEMP:-/tmp}/agentcodi-package-contract.XXXXXX")"
report_dir="${AGENTCODI_PACKAGE_REPORT:-$build_root/report}"
mkdir -p "$report_dir"
read_pin() {
    python3 - "$repo_root/scripts/package-edition/lock.json" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1]))
for key in sys.argv[2].split("."):
    value = value[key]
print(value)
PY
}
source_dir="$build_root/upstream"
prepared_dir="$build_root/prepared"
git init -q "$source_dir"
git -C "$source_dir" remote add origin "$(read_pin source.repository)"
git -C "$source_dir" -c protocol.version=2 fetch --depth=1 origin "$(read_pin source.commit)"
git -C "$source_dir" checkout -q --detach FETCH_HEAD
python3 -B "$repo_root/scripts/package-edition/prepare.py" \
    --source "$source_dir" --output "$prepared_dir" > "$report_dir/preparation.json"
python3 -B "$repo_root/.github/ci/test-package-edition.py" \
    --source "$source_dir" --prepared "$prepared_dir"
for path in scripts/properties.sh build-package.sh scripts/build-bootstraps.sh \
    scripts/build/termux_step_setup_variables.sh scripts/build/termux_step_get_dependencies.sh \
    scripts/build/termux_step_start_build.sh packages/apt/build.sh agentcodi-build-package.sh; do
    bash -n "$prepared_dir/$path"
done
# The complete host toolchain and SDK are immutable container bytes, not a
# floating tag or a fresh apt upgrade. No Android payload is executed here.
chmod 777 "$report_dir"
docker run --rm --platform "$(read_pin builder.platform)" --network none \
    -v "$repo_root":/audit:ro \
    -v "$prepared_dir":/recipes:ro \
    -v "$report_dir":/report \
    "$(read_pin builder.image)" bash /audit/scripts/package-edition/check-toolchain.sh /recipes /report
echo "Package build contracts verified; report: $report_dir"
