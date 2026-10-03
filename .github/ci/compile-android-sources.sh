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
  --java "$BUILD_DIR/generated" --custom-package de.agentcodi.app --min-sdk-version 29 --target-sdk-version 28 \
  -I "$ANDROID_JAR" "$BUILD_DIR/resources.zip"
"$AAPT2" dump badging "$BUILD_DIR/resources.apk" > "$BUILD_DIR/badging.txt"
grep -Fq "targetSdkVersion:'28'" "$BUILD_DIR/badging.txt"
grep -Eq "(minSdkVersion|sdkVersion):'29'" "$BUILD_DIR/badging.txt"
grep -Fq "application-label:'AGENTCODI Package'" "$BUILD_DIR/badging.txt"
grep -Fq "package: name='de.agentcodi.pkg'" "$BUILD_DIR/badging.txt"
grep -Fq "launchable-activity: name='de.agentcodi.app.MainActivity'" "$BUILD_DIR/badging.txt"

find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" "$BUILD_DIR/generated" \
  -type f -name '*.java' -print | sort > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
# The installation ID differs from the Java namespace. Resolve every manifest
# component against the compiled classes to catch an accidentally relative name.
python3 - "$PROJECT_ROOT" "$BUILD_DIR" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

project, build = map(Path, sys.argv[1:])
manifest = ET.parse(project / "app/src/main/AndroidManifest.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
identity = (project / "modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java").read_text()
builder = (project / "scripts/build-debug-apk.sh").read_text()
badging = (build / "badging.txt").read_text()

for java_key, shell_key, manifest_value, badging_key in (
    ("APPLICATION_ID", "APP_ID", manifest.attrib["package"], "name"),
    ("VERSION_NAME", "APP_VERSION", manifest.attrib[android + "versionName"], "versionName"),
    ("VERSION_CODE", "VERSION_CODE", manifest.attrib[android + "versionCode"], "versionCode"),
):
    java_value = re.search(r"\b" + java_key + r' = "?([^";]+)"?;', identity).group(1)
    shell_value = re.search(r"^" + shell_key + r'="([^"]+)"$', builder, re.M).group(1)
    assert manifest_value == java_value == shell_value, (java_key, manifest_value, java_value, shell_value)
    assert badging_key + "='" + manifest_value + "'" in badging, java_key

application = manifest.find("application")
for component in [application] + list(application):
    if component.tag not in ("application", "activity", "service", "receiver", "provider"):
        continue
    name = component.attrib[android + "name"]
    if name.startswith("."):
        name = manifest.attrib["package"] + name
    elif "." not in name:
        name = manifest.attrib["package"] + "." + name
    assert (build / "classes" / (name.replace(".", "/") + ".class")).is_file(), name
assert (build / "classes/de/agentcodi/app/R.class").is_file(), "Java resource namespace"
print("Package installation identity and all manifest component classes verified.")
PY
echo "Android sources and resources compiled for Package Edition (target 28, minimum 29)."
