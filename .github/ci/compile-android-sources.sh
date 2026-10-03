#!/usr/bin/env bash
# Compile the Android-facing sources and resources without assembling native payloads.
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
BUILD_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/agentcodi-android-compile.XXXXXX")"
trap 'build_status=$?; if [ "$build_status" -ne 0 ] && [ -f "$BUILD_DIR/badging.txt" ]; then cat "$BUILD_DIR/badging.txt"; fi; rm -rf -- "$BUILD_DIR"; exit "$build_status"' EXIT

: "${ANDROID_HOME:?The GitHub runner Android SDK is required}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
if [ ! -f "$ANDROID_JAR" ] || [ ! -x "$AAPT2" ]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-35" "build-tools;35.0.0" >/dev/null
fi
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes"
"$AAPT2" compile --dir "$PROJECT_ROOT/app/src/main/res" -o "$BUILD_DIR/resources.zip"
"$AAPT2" link -o "$BUILD_DIR/resources.apk" \
  --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/generated" --min-sdk-version 29 --target-sdk-version 28 \
  -I "$ANDROID_JAR" "$BUILD_DIR/resources.zip"
"$AAPT2" dump badging "$BUILD_DIR/resources.apk" > "$BUILD_DIR/badging.txt"
grep -Fq "targetSdkVersion:'28'" "$BUILD_DIR/badging.txt"
grep -Eq "(minSdkVersion|sdkVersion):'29'" "$BUILD_DIR/badging.txt"
grep -Fq "application-label:'AGENTCODI Package'" "$BUILD_DIR/badging.txt"

find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" "$BUILD_DIR/generated" \
  -type f -name '*.java' -print | sort > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
echo "Android sources and resources compiled for Package Edition (target 28, minimum 29)."
