#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
JAVA_HOME_17="${AGENTCODI_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
JAVAC="$JAVA_HOME_17/bin/javac"
JAVA="$JAVA_HOME_17/bin/java"
TERMUX_PREFIX="${AGENTCODI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
CLANGXX="${AGENTCODI_CLANGXX:-$TERMUX_PREFIX/bin/clang++}"
TEST_BUILD="$PROJECT_ROOT/.build/tests"

if [ ! -x "$JAVAC" ] || [ ! -x "$JAVA" ] || [ ! -x "$CLANGXX" ]; then
  echo "Required Java 17 or Android clang tool is missing." >&2
  exit 1
fi

case "$TEST_BUILD" in
  "$PROJECT_ROOT"/.build/tests) rm -rf -- "$TEST_BUILD" ;;
  *) echo "Refusing unsafe test cleanup." >&2; exit 1 ;;
esac
mkdir -p "$TEST_BUILD/java-classes" "$TEST_BUILD/cpp"

"$SCRIPT_DIR/check-architecture.sh"

find "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java" "$PROJECT_ROOT/tests/java" -type f -name '*.java' -print | sort > "$TEST_BUILD/java-sources.txt"

"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d "$TEST_BUILD/java-classes" @"$TEST_BUILD/java-sources.txt"
"$JAVA" -cp "$TEST_BUILD/java-classes" de.agentcodi.tests.TestMain

"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror -pthread -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/agentcodi_engine.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" -o "$TEST_BUILD/cpp/agentcodi-engine-test"

env LD_LIBRARY_PATH="$TERMUX_PREFIX/lib" "$TEST_BUILD/cpp/agentcodi-engine-test"

echo "All host tests passed."
