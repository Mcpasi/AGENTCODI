#!/usr/bin/env bash
# Exercise the real Android chat widgets without starting the native runtime.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
BUILD_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/agentcodi-chat-ui.XXXXXX")"
trap 'rm -rf -- "$BUILD_DIR"' EXIT
: "${ANDROID_HOME:?An Android SDK with platform/build-tools 35 is required}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
REPORT_DIR="${AGENTCODI_CHAT_UI_REPORT:-$BUILD_DIR/report}"
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/test-classes/com/android/tools" "$REPORT_DIR"

"$AAPT2" compile --dir "$PROJECT_ROOT/app/src/main/res" -o "$BUILD_DIR/resources.zip"
"$AAPT2" link -o "$BUILD_DIR/resources.apk" \
  --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/generated" --custom-package de.agentcodi.app \
  --min-sdk-version 29 --target-sdk-version 28 \
  -I "$ANDROID_JAR" "$BUILD_DIR/resources.zip"
find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" "$BUILD_DIR/generated" \
  -type f -name '*.java' -print | sort > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"

cat > "$BUILD_DIR/pom.xml" <<'XML'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>de.agentcodi.validation</groupId><artifactId>chat-ui</artifactId><version>1</version>
  <dependencies>
    <dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.14.1</version></dependency>
    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
  </dependencies>
  <repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
</project>
XML
maven_arguments=()
if [ -n "${AGENTCODI_MAVEN_SETTINGS:-}" ]; then
  maven_arguments+=(-s "$AGENTCODI_MAVEN_SETTINGS")
fi
mvn -B -q "${maven_arguments[@]}" -f "$BUILD_DIR/pom.xml" \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
  -DoutputDirectory="$BUILD_DIR/lib"
python3 - "$BUILD_DIR" "$PROJECT_ROOT" <<'PY'
from pathlib import Path
import sys
import zipfile
build, project = map(Path, sys.argv[1:])
for aar in (build / "lib").glob("*.aar"):
    with zipfile.ZipFile(aar) as archive:
        (build / "lib" / (aar.stem + "-classes.jar")).write_bytes(archive.read("classes.jar"))
(build / "test-classes/com/android/tools/test_config.properties").write_text(
    "android_merged_manifest=" + str(project / "app/src/main/AndroidManifest.xml") + "\n"
    "android_merged_resources=" + str(project / "app/src/main/res") + "\n"
    "android_merged_assets=" + str(project / "app/src/main/assets") + "\n"
    "android_resource_apk=" + str(build / "resources.apk") + "\n"
    "android_custom_package=de.agentcodi.app\n", encoding="utf-8")
PY
classpath="$BUILD_DIR/classes:$BUILD_DIR/test-classes:$BUILD_DIR/lib/*:$ANDROID_JAR"
javac -encoding UTF-8 -cp "$classpath" -d "$BUILD_DIR/test-classes" \
  "$PROJECT_ROOT/tests/android/de/agentcodi/app/ChatConversationUiTest.java"
java -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2 \
  -Dagentcodi.chatUiReport="$REPORT_DIR" -cp "$classpath" \
  org.junit.runner.JUnitCore de.agentcodi.app.ChatConversationUiTest | tee "$REPORT_DIR/results.txt"
