#!/bin/sh
set -eu

# Build-time automation only. No npm installation, login, app launch, or APK build.
SCRIPT_DIR=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -P -- "$SCRIPT_DIR/.." && pwd -P)

case ${1-} in
  -h|--help)
    cat <<'EOF'
Usage: ./scripts/update-codex-runtime.sh [--dry-run] [MAJOR.MINOR.PATCH|latest]

Default: update to the stable npm "latest" version of @mmmbuto/codex-cli-termux.
--dry-run downloads and validates the candidate and prints the proposed pins,
without changing source files or the build cache. Downgrades are rejected.

Requires the existing Android ARM64 build host, Java/Javac 17, curl and timeout.
AGENTCODI_JAVA_HOME and AGENTCODI_CACHE_DIR have the same meaning as in the APK
builder. Downloads, generated schemas, the proposed diff and backups remain
in a private .build/codex-update.* directory for inspection.

Updates runtime/source/schema hashes, the verified ELF relocation offset,
build identity, its test, architecture checks and bundled Android notices.
Markdown documentation (including NOTICE.md), the app version and other
toolchain pins are not changed. Unexpected package/license/dependency/ELF
or incompatible schema changes abort; this tool cannot implement arbitrary
future protocol migrations. It never runs npm scripts or accesses login data.

After success: update your documentation, then run ./scripts/test.sh and
./scripts/build-debug-apk.sh. Device validation remains a separate user step.
EOF
    exit 0
    ;;
esac

JAVA_HOME_17=${AGENTCODI_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
[ -x "$JAVA_HOME_17/bin/java" ] && [ -x "$JAVA_HOME_17/bin/javac" ] || {
  printf '%s\n' 'Java/Javac 17 is required (AGENTCODI_JAVA_HOME).' >&2
  exit 1
}
umask 077
classes_dir=$(mktemp -d "${TMPDIR:-/tmp}/agentcodi-codex-updater.XXXXXX")
cleanup() {
  # Only the directory returned by mktemp is removed; update artifacts are kept.
  rm -rf -- "$classes_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
"$JAVA_HOME_17/bin/javac" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -d "$classes_dir" \
  "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/JsonCodec.java" \
  "$SCRIPT_DIR/java/de/agentcodi/tools/CodexRuntimeUpdater.java"
"$JAVA_HOME_17/bin/java" -Xmx512m -cp "$classes_dir" \
  de.agentcodi.tools.CodexRuntimeUpdater "$PROJECT_ROOT" "$@"
