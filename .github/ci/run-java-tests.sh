#!/usr/bin/env bash
# CI-only driver for the existing Java host test suite.
#
# scripts/test.sh is the authoritative local runner, but it requires the Termux
# Android toolchain and rebuilds every C++ target, so it cannot run on a hosted
# runner. This script compiles and runs the very same Java sources with a stock
# JDK 17 and nothing else. It never writes into the working tree.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
SOURCE_LIST="$SCRIPT_DIR/java-sources.txt"
BUILD_DIR="${AGENTCODI_CI_BUILD_DIR:-${RUNNER_TEMP:-/tmp}/agentcodi-ci-java}"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC="$(command -v javac || true)"
  JAVA="$(command -v java || true)"
fi
if [ -z "$JAVAC" ] || [ -z "$JAVA" ]; then
  echo "A JDK providing javac and java is required." >&2
  exit 1
fi
echo "Using $("$JAVAC" -version 2>&1)"

# The Java source list is duplicated from scripts/test.sh on purpose: that
# script stays untouched. Catch the duplication drifting out of sync.
if [ "${AGENTCODI_CI_SKIP_SOURCE_SYNC:-0}" != "1" ] && [ -r "$PROJECT_ROOT/scripts/test.sh" ]; then
  local_list="$(awk '/^find \\$/{block=1;next} block && /-type f -name/{block=0} block' \
      "$PROJECT_ROOT/scripts/test.sh" \
    | sed -e 's/^[[:space:]]*"//' -e 's/"[[:space:]]*\\*$//' -e 's|\$PROJECT_ROOT/||' \
    | sed '/^$/d' | sort)"
  if [ -n "$local_list" ]; then
    ci_list="$(grep -v '^[[:space:]]*#' "$SOURCE_LIST" | sed '/^[[:space:]]*$/d' | sort)"
    if [ "$local_list" != "$ci_list" ]; then
      echo "The CI Java source list no longer matches scripts/test.sh." >&2
      echo "Update .github/ci/java-sources.txt so both compile the same sources:" >&2
      diff <(printf '%s\n' "$ci_list") <(printf '%s\n' "$local_list") \
        --label '.github/ci/java-sources.txt' --label 'scripts/test.sh' -u >&2 || true
      exit 1
    fi
    echo "Java source list is in sync with scripts/test.sh."
  fi
fi

# The build directory is removed below, so refuse obviously unsafe values.
case "$BUILD_DIR" in
  ''|/|/*/..|"$PROJECT_ROOT"|"$PROJECT_ROOT"/|"${HOME:-/nonexistent}"|"${HOME:-/nonexistent}"/)
    echo "Refusing unsafe build directory: $BUILD_DIR" >&2
    exit 1
    ;;
  /*) ;;
  *)
    echo "The build directory must be an absolute path: $BUILD_DIR" >&2
    exit 1
    ;;
esac

rm -rf -- "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes"

sources=()
while IFS= read -r entry; do
  case "$entry" in ''|\#*) continue ;; esac
  if [ ! -e "$PROJECT_ROOT/$entry" ]; then
    echo "Listed Java source path is missing: $entry" >&2
    exit 1
  fi
  sources+=("$PROJECT_ROOT/$entry")
done < "$SOURCE_LIST"

find "${sources[@]}" -type f -name '*.java' -print | sort > "$BUILD_DIR/java-sources.txt"
echo "Compiling $(wc -l < "$BUILD_DIR/java-sources.txt") Java files."
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -d "$BUILD_DIR/classes" @"$BUILD_DIR/java-sources.txt"

"$JAVA" -Dagentcodi.projectRoot="$PROJECT_ROOT" \
  -cp "$BUILD_DIR/classes" de.agentcodi.tests.TestMain
