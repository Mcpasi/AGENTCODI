#!/bin/sh
set -eu

# Build-time automation only. No npm installation, login, app launch, or APK build.
SCRIPT_DIR=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -P -- "$SCRIPT_DIR/.." && pwd -P)

case ${1-} in
  -h|--help)
    cat <<'EOF'
Usage: ./scripts/update-codex-runtime.sh [--dry-run] [--archive FILE.tgz]
                                      [--source-dir DIR] [--source-ref REF]

Default: inspect the local .tgz in the adjacent codex-termux checkout and pin it.
The filename matching npm-package/package.json is preferred; otherwise exactly
one .tgz must exist. A positional FILE.tgz is also accepted. No npm download.
AGENTCODI_CODEX_ARCHIVE and AGENTCODI_CODEX_SOURCE_DIR override these locations.
The fork Action records gitHead in new packages. For a legacy package without
that field, --source-ref identifies its build commit. Never infer a new package's
commit from an unrelated or dirty working tree.

Automatically updates archive/ELF/schema hashes, source revisions, relocation
offset, version displays, architecture checks, NOTICE.md and APK legal notices.
Rebuilds of the same fork version are supported. Downgrades, changed licenses or
dependencies, incompatible schemas and unsafe archives are rejected. Workspace
version bumps do not count as dependency changes. Author credits are preserved.

--dry-run validates and saves a proposed diff without changing project files or
the cache. Backups and proposals remain in private .build/codex-update.* folders.
Verified archives and schemas are cached by SHA-256, so replacing a .tgz with a
same-version rebuild does not destroy the baseline. Run once with the current
package to initialize this baseline before the next protocol-changing update.

Requires the Android ARM64 build host, Java/Javac 17, git and timeout.
AGENTCODI_JAVA_HOME and AGENTCODI_CACHE_DIR also apply to the APK builder.
This script never runs Cargo, the test suite or the APK build. After updating,
run ./scripts/test.sh, then ./scripts/build-debug-apk.sh. Pinning alone does not
verify sandbox operation; installed-device testing remains a separate user step.
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
"$JAVA_HOME_17/bin/javac" -J-Xmx128m -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -d "$classes_dir" \
  "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/JsonCodec.java" \
  "$SCRIPT_DIR/java/de/agentcodi/tools/CodexPackageMetadata.java" \
  "$SCRIPT_DIR/java/de/agentcodi/tools/CodexLocalSource.java" \
  "$SCRIPT_DIR/java/de/agentcodi/tools/CodexRuntimeUpdater.java"
"$JAVA_HOME_17/bin/java" -Xmx192m -cp "$classes_dir" \
  de.agentcodi.tools.CodexRuntimeUpdater "$PROJECT_ROOT" "$@"
