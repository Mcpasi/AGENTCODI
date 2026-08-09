#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

APP_NAME="AGENTCODI"
APP_ID="de.agentcodi.app"
APP_VERSION="0.2.2"
VERSION_CODE="6"
MIN_SDK="29"
TARGET_SDK="35"
ABI="arm64-v8a"

CODEX_ANDROID_VERSION="0.147.1"
CODEX_ANDROID_URL="https://registry.npmjs.org/@mmmbuto/codex-cli-termux/-/codex-cli-termux-$CODEX_ANDROID_VERSION.tgz"
CODEX_ANDROID_SHA256="a6b75fc5409ef92d2fc936cdf266332f5362438e6bd602d679261e95b4ac3af3"

PLATFORM_URL="https://dl.google.com/android/repository/platform-35_r02.zip"
PLATFORM_SHA256="0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0"
R8_VERSION="9.2.23"
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
R8_SHA256="c6f69c9398c2f1825cac162d0d26faa4002eb68cfc594a4aec18f574276c07cb"
AAPT2_VERSION="16.0.0.4-1"
AAPT2_URL="https://packages.termux.dev/apt/termux-main/pool/main/a/aapt2/aapt2_${AAPT2_VERSION}_aarch64.deb"
AAPT2_SHA256="d35298f13ec26eee362d4e84f534b29b8e5f288b86c89d803ba4fb8ccb9784aa"
ABSEIL_URL="https://packages.termux.dev/apt/termux-main/pool/main/a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb"
ABSEIL_SHA256="e489fac652cddc39d9436141e627285f1034a545a06fbb19c420514a419ad877"
PROTOBUF_URL="https://packages.termux.dev/apt/termux-main/pool/main/libp/libprotobuf/libprotobuf_2:35.1_aarch64.deb"
PROTOBUF_SHA256="a1ba7c7f0e5903a2134662653d3e7b9ffceaa78bdd00e07ac985e2d313ebc738"
FMT_URL="https://packages.termux.dev/apt/termux-main/pool/main/f/fmt/fmt_1:11.2.0_aarch64.deb"
FMT_SHA256="0377ac55cc99e409a5a2ba55a7cacf86fc1f79f330c2998801e293e95cac1996"
LIBCXX_URL="https://packages.termux.dev/apt/termux-main/pool/main/libc/libc++/libc++_29_aarch64.deb"
LIBCXX_SHA256="bb9f12113c137aa0e8513bb51cc49fe77a5ce3ca39ab9e92c57d228ecdf00222"
EXPAT_URL="https://packages.termux.dev/apt/termux-main/pool/main/libe/libexpat/libexpat_2.8.2_aarch64.deb"
EXPAT_SHA256="6f5eb2fd14b6fe4d7bb79bf7f0f3d7fc838fea07402477a172b147304366b372"
PNG_URL="https://packages.termux.dev/apt/termux-main/pool/main/libp/libpng/libpng_1.6.58_aarch64.deb"
PNG_SHA256="e47937405c72734867513cf0c63d27f36400d462666b65dfada984667d7228c4"
ZOPFLI_URL="https://packages.termux.dev/apt/termux-main/pool/main/libz/libzopfli/libzopfli_1.0.3-5_aarch64.deb"
ZOPFLI_SHA256="95cd7cb2209fbafb25825f5fcd4f86f021512175608e038b1c3d8d3fa0a4fe40"
ZLIB_URL="https://packages.termux.dev/apt/termux-main/pool/main/z/zlib/zlib_1.3.2_aarch64.deb"
ZLIB_SHA256="75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9"

JAVA_HOME_17="${AGENTCODI_JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
JAVA="$JAVA_HOME_17/bin/java"
JAVAC="$JAVA_HOME_17/bin/javac"
JAR="$JAVA_HOME_17/bin/jar"
KEYTOOL="$JAVA_HOME_17/bin/keytool"
TERMUX_PREFIX="${AGENTCODI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
CLANGXX="${AGENTCODI_CLANGXX:-$TERMUX_PREFIX/bin/clang++}"
LLVM_STRIP="${AGENTCODI_LLVM_STRIP:-$TERMUX_PREFIX/bin/llvm-strip}"
CACHE_DIR="${AGENTCODI_CACHE_DIR:-$PROJECT_ROOT/.cache/android}"
OUTPUT_DIR="${AGENTCODI_OUTPUT_DIR:-$PROJECT_ROOT/output/apk}"
BUILD_ROOT="$PROJECT_ROOT/.build"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required build command: $1" >&2
    exit 1
  fi
}

for command_name in apksigner awk curl dpkg-deb file grep readelf rg sha256sum strings tar unzip zip zipalign zipinfo; do
  require_command "$command_name"
done
for executable in "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL" "$CLANGXX" "$LLVM_STRIP"; do
  if [ ! -x "$executable" ]; then
    echo "Missing required executable: $executable" >&2
    exit 1
  fi
done

download_verified() {
  url="$1"
  expected_sha="$2"
  destination="$3"
  if [ -f "$destination" ] && printf '%s  %s\n' "$expected_sha" "$destination" | sha256sum --check --status; then
    return
  fi
  if [ -e "$destination" ]; then
    rm -f -- "$destination"
  fi
  partial="$destination.partial.$$"
  curl --fail --location --retry 3 --retry-delay 2 --output "$partial" "$url"
  if ! printf '%s  %s\n' "$expected_sha" "$partial" | sha256sum --check --status; then
    rm -f -- "$partial"
    echo "SHA-256 verification failed for $url" >&2
    exit 1
  fi
  mv -- "$partial" "$destination"
}

mkdir -p "$CACHE_DIR" "$OUTPUT_DIR" "$BUILD_ROOT"
PLATFORM_ARCHIVE="$CACHE_DIR/platform-35_r02.zip"
R8_JAR="$CACHE_DIR/r8-$R8_VERSION.jar"
AAPT2_ARCHIVE="$CACHE_DIR/aapt2-$AAPT2_VERSION-aarch64.deb"
ABSEIL_ARCHIVE="$CACHE_DIR/abseil-cpp-20260526.0-aarch64.deb"
PROTOBUF_ARCHIVE="$CACHE_DIR/libprotobuf-35.1-aarch64.deb"
FMT_ARCHIVE="$CACHE_DIR/fmt-11.2.0-aarch64.deb"
LIBCXX_ARCHIVE="$CACHE_DIR/libcxx-29-aarch64.deb"
EXPAT_ARCHIVE="$CACHE_DIR/libexpat-2.8.2-aarch64.deb"
PNG_ARCHIVE="$CACHE_DIR/libpng-1.6.58-aarch64.deb"
ZOPFLI_ARCHIVE="$CACHE_DIR/libzopfli-1.0.3-5-aarch64.deb"
ZLIB_ARCHIVE="$CACHE_DIR/zlib-1.3.2-aarch64.deb"
CODEX_ANDROID_ARCHIVE="$CACHE_DIR/codex-cli-termux-$CODEX_ANDROID_VERSION.tgz"

echo "Verifying pinned Android build inputs..."
download_verified "$PLATFORM_URL" "$PLATFORM_SHA256" "$PLATFORM_ARCHIVE"
download_verified "$R8_URL" "$R8_SHA256" "$R8_JAR"
download_verified "$AAPT2_URL" "$AAPT2_SHA256" "$AAPT2_ARCHIVE"
download_verified "$ABSEIL_URL" "$ABSEIL_SHA256" "$ABSEIL_ARCHIVE"
download_verified "$PROTOBUF_URL" "$PROTOBUF_SHA256" "$PROTOBUF_ARCHIVE"
download_verified "$FMT_URL" "$FMT_SHA256" "$FMT_ARCHIVE"
download_verified "$LIBCXX_URL" "$LIBCXX_SHA256" "$LIBCXX_ARCHIVE"
download_verified "$EXPAT_URL" "$EXPAT_SHA256" "$EXPAT_ARCHIVE"
download_verified "$PNG_URL" "$PNG_SHA256" "$PNG_ARCHIVE"
download_verified "$ZOPFLI_URL" "$ZOPFLI_SHA256" "$ZOPFLI_ARCHIVE"
download_verified "$ZLIB_URL" "$ZLIB_SHA256" "$ZLIB_ARCHIVE"
download_verified "$CODEX_ANDROID_URL" "$CODEX_ANDROID_SHA256" "$CODEX_ANDROID_ARCHIVE"

echo "Running Java, C++, and architecture tests..."
"$SCRIPT_DIR/test.sh"

WORK_DIR="$(mktemp -d "$BUILD_ROOT/apk.work.XXXXXX")"
cleanup() {
  case "$WORK_DIR" in
    "$BUILD_ROOT"/apk.work.*) rm -rf -- "$WORK_DIR" ;;
    *) echo "Refusing unsafe build cleanup: $WORK_DIR" >&2 ;;
  esac
}
trap cleanup EXIT

EXTRACT_DIR="$WORK_DIR/platform"
AAPT2_EXTRACT="$WORK_DIR/aapt2"
GENERATED_JAVA="$WORK_DIR/generated-java"
COMPILED_RESOURCES="$WORK_DIR/compiled-resources.zip"
CLASSES_ROOT="$WORK_DIR/classes"
JARS_ROOT="$WORK_DIR/jars"
DEX_DIR="$WORK_DIR/dex"
ADDITIONS="$WORK_DIR/additions"
NATIVE_DIR="$ADDITIONS/lib/$ABI"
CODEX_EXTRACT="$WORK_DIR/codex"
THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/codex"
mkdir -p "$EXTRACT_DIR" "$AAPT2_EXTRACT" "$GENERATED_JAVA" "$CLASSES_ROOT" "$JARS_ROOT" "$DEX_DIR" "$NATIVE_DIR" "$CODEX_EXTRACT" "$THIRD_PARTY_ASSETS"

(
  cd "$EXTRACT_DIR"
  "$JAR" xf "$PLATFORM_ARCHIVE" android-35/android.jar
)
ANDROID_JAR="$EXTRACT_DIR/android-35/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "Pinned platform archive did not contain android.jar." >&2
  exit 1
fi

for archive in "$AAPT2_ARCHIVE" "$ABSEIL_ARCHIVE" "$PROTOBUF_ARCHIVE" "$FMT_ARCHIVE" "$LIBCXX_ARCHIVE" "$EXPAT_ARCHIVE" "$PNG_ARCHIVE" "$ZOPFLI_ARCHIVE" "$ZLIB_ARCHIVE"; do
  dpkg-deb -x "$archive" "$AAPT2_EXTRACT"
done
tar -xzf "$CODEX_ANDROID_ARCHIVE" -C "$CODEX_EXTRACT"
AAPT2_BIN="$AAPT2_EXTRACT/data/data/com.termux/files/usr/bin/aapt2"
AAPT2_LIBRARY_PATH="$AAPT2_EXTRACT/data/data/com.termux/files/usr/lib"
if [ ! -x "$AAPT2_BIN" ]; then
  echo "Pinned aapt2 package did not contain an executable." >&2
  exit 1
fi
LIBCXX_SHARED="$AAPT2_LIBRARY_PATH/libc++_shared.so"
CODEX_BINARY="$CODEX_EXTRACT/package/bin/codex.bin"
CODEX_LICENSE="$CODEX_EXTRACT/package/LICENSE"
CODEX_NOTICE="$CODEX_EXTRACT/package/NOTICE"
if [ ! -f "$LIBCXX_SHARED" ] || ! file "$LIBCXX_SHARED" | grep -q 'ARM aarch64'; then
  echo "Pinned libc++ runtime is missing or not ARM64." >&2
  exit 1
fi
for codex_file in "$CODEX_BINARY" "$CODEX_LICENSE" "$CODEX_NOTICE"; do
  if [ ! -f "$codex_file" ]; then
    echo "Pinned Codex archive is missing: $codex_file" >&2
    exit 1
  fi
done
if ! file "$CODEX_BINARY" | grep -q 'ARM aarch64'; then
  echo "Pinned Codex app-server is not ARM64." >&2
  exit 1
fi
if ! readelf -l "$CODEX_BINARY" | grep -q '/system/bin/linker64'; then
  echo "Pinned Codex app-server does not use the Android linker." >&2
  exit 1
fi

echo "Compiling Android resources..."
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" compile --dir "$PROJECT_ROOT/app/src/main/res" -o "$COMPILED_RESOURCES"

UNSIGNED_APK="$WORK_DIR/unsigned.apk"
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" link -o "$UNSIGNED_APK" --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" --java "$GENERATED_JAVA" --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" --version-code "$VERSION_CODE" --version-name "$APP_VERSION" -I "$ANDROID_JAR" "$COMPILED_RESOURCES"

echo "Compiling isolated Java modules..."
CORE_CLASSES="$CLASSES_ROOT/core"
STORAGE_CLASSES="$CLASSES_ROOT/storage"
RUNTIME_CLASSES="$CLASSES_ROOT/runtime"
APP_CLASSES="$CLASSES_ROOT/app"
mkdir -p "$CORE_CLASSES" "$STORAGE_CLASSES" "$RUNTIME_CLASSES" "$APP_CLASSES"

find "$PROJECT_ROOT/modules/core/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/core-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$CORE_CLASSES" @"$WORK_DIR/core-sources.txt"
CORE_JAR="$JARS_ROOT/core.jar"
"$JAR" cf "$CORE_JAR" -C "$CORE_CLASSES" .

find "$PROJECT_ROOT/modules/storage/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/storage-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$STORAGE_CLASSES" @"$WORK_DIR/storage-sources.txt"
STORAGE_JAR="$JARS_ROOT/storage.jar"
"$JAR" cf "$STORAGE_JAR" -C "$STORAGE_CLASSES" .

find "$PROJECT_ROOT/modules/runtime/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/runtime-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$STORAGE_JAR" -d "$RUNTIME_CLASSES" @"$WORK_DIR/runtime-sources.txt"
RUNTIME_JAR="$JARS_ROOT/runtime.jar"
"$JAR" cf "$RUNTIME_JAR" -C "$RUNTIME_CLASSES" .

find "$PROJECT_ROOT/app/src/main/java" "$GENERATED_JAVA" -type f -name '*.java' -print | sort > "$WORK_DIR/app-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$STORAGE_JAR:$RUNTIME_JAR" -d "$APP_CLASSES" @"$WORK_DIR/app-sources.txt"
APP_JAR="$JARS_ROOT/app.jar"
"$JAR" cf "$APP_JAR" -C "$APP_CLASSES" .

echo "Compiling ARM64 JNI engine..."
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -shared -fPIC -std=c++17 -O2 -Wall -Wextra -Werror -pthread -fvisibility=hidden -I"$JAVA_HOME_17/include" -I"$JAVA_HOME_17/include/linux" -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/agentcodi_engine.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/jni_bridge.cpp" -Wl,-soname,libagentcodi.so -llog -o "$NATIVE_DIR/libagentcodi.so"
"$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/libagentcodi.so"
cp "$LIBCXX_SHARED" "$NATIVE_DIR/libc++_shared.so"
cp "$CODEX_BINARY" "$NATIVE_DIR/libcodex.so"
cp "$CODEX_LICENSE" "$THIRD_PARTY_ASSETS/LICENSE"
cp "$CODEX_NOTICE" "$THIRD_PARTY_ASSETS/NOTICE"

if ! file "$NATIVE_DIR/libagentcodi.so" | grep -q 'ARM aarch64'; then
  echo "Native library is not ARM64." >&2
  exit 1
fi
if ! readelf -Ws "$NATIVE_DIR/libagentcodi.so" | grep -q 'Java_de_agentcodi_runtime_NativeEngine_nativeSelfTest'; then
  echo "JNI self-test symbol is missing." >&2
  exit 1
fi
if ! readelf -Ws "$NATIVE_DIR/libagentcodi.so" | grep -q 'Java_de_agentcodi_runtime_NativeEngine_nativeStartAppServer'; then
  echo "JNI app-server supervisor symbol is missing." >&2
  exit 1
fi
if ! readelf -d "$NATIVE_DIR/libagentcodi.so" | grep -q 'Shared library: \[libc++_shared.so\]'; then
  echo "Native engine did not declare its packaged C++ runtime dependency." >&2
  exit 1
fi
strings "$NATIVE_DIR/libagentcodi.so" > "$WORK_DIR/native-engine-strings.txt"
if ! grep -Fq 'model_provider="agentcodi-openai-http"' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing the HTTPS Responses provider selection." >&2
  exit 1
fi
if ! grep -Fq 'model_providers.agentcodi-openai-http.supports_websockets=false' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine does not disable the failing Responses WebSocket path." >&2
  exit 1
fi
for native_file in "$NATIVE_DIR/libagentcodi.so" "$NATIVE_DIR/libc++_shared.so" "$NATIVE_DIR/libcodex.so"; do
  if ! readelf -lW "$native_file" | awk '$1 == "LOAD" { seen = 1; if ($NF != "0x4000") bad = 1 } END { exit (!seen || bad) }'; then
    echo "Native library is not compatible with 16 KiB Android pages: $native_file" >&2
    exit 1
  fi
done

echo "Creating DEX and APK..."
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 --debug --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$DEX_DIR" "$CORE_JAR" "$STORAGE_JAR" "$RUNTIME_JAR" "$APP_JAR"
cp "$DEX_DIR/classes.dex" "$ADDITIONS/classes.dex"

UNALIGNED_APK="$WORK_DIR/unaligned.apk"
ALIGNED_APK="$WORK_DIR/aligned.apk"
cp "$UNSIGNED_APK" "$UNALIGNED_APK"
(
  cd "$ADDITIONS"
  zip -q -9 -r "$UNALIGNED_APK" classes.dex lib assets
)
zipalign -f -p 4 "$UNALIGNED_APK" "$ALIGNED_APK"

DEBUG_KEYSTORE="$CACHE_DIR/agentcodi-debug.keystore"
if [ ! -f "$DEBUG_KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair -noprompt -keystore "$DEBUG_KEYSTORE" -storepass android -keypass android -alias androiddebugkey -dname "CN=AGENTCODI Android Debug,O=AGENTCODI,C=DE" -keyalg RSA -keysize 2048 -validity 10000
fi

VERSIONED_APK="$OUTPUT_DIR/$APP_NAME-$APP_VERSION-$ABI-debug.apk"
NAMED_APK="$OUTPUT_DIR/$APP_NAME-debug.apk"
apksigner sign --min-sdk-version "$MIN_SDK" --ks "$DEBUG_KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$VERSIONED_APK" "$ALIGNED_APK"
cp "$VERSIONED_APK" "$NAMED_APK"

echo "Verifying APK identity, signature, alignment, ABI, and payload..."
zipalign -c -p 4 "$VERSIONED_APK"
apksigner verify --verbose --print-certs "$VERSIONED_APK"
badging="$(env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" dump badging "$VERSIONED_APK")"
printf '%s\n' "$badging" | grep -Fq "package: name='$APP_ID'"
printf '%s\n' "$badging" | grep -Fq "versionCode='$VERSION_CODE'"
printf '%s\n' "$badging" | grep -Fq "versionName='$APP_VERSION'"
printf '%s\n' "$badging" | grep -Fq "minSdkVersion:'$MIN_SDK'"
printf '%s\n' "$badging" | grep -Fq "targetSdkVersion:'$TARGET_SDK'"
printf '%s\n' "$badging" | grep -Fq "application-label:'$APP_NAME'"
printf '%s\n' "$badging" | grep -Fq "launchable-activity: name='de.agentcodi.app.MainActivity'"
printf '%s\n' "$badging" | grep -Fq "native-code: '$ABI'"
printf '%s\n' "$badging" | grep -F "package: name='$APP_ID'"
printf '%s\n' "$badging" | grep -F "application-label:'$APP_NAME'"

zipinfo -1 "$VERSIONED_APK" > "$WORK_DIR/apk-entries.txt"
grep -Fx 'classes.dex' "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/libagentcodi.so" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/libc++_shared.so" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/libcodex.so" "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/codex/LICENSE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/codex/NOTICE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/raw/third_party_notices.txt' "$WORK_DIR/apk-entries.txt"
unzip -p "$VERSIONED_APK" classes.dex | strings > "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/MainActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/SettingsActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AgentCodiApplication;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CrashReportFormatter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexSessionController;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexModelOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexReasoningOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/AgentRuntimeService;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeAppServerTransport;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/CrashDiagnostics;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeEngine;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/CrashReportStore;' "$WORK_DIR/dex-strings.txt"
if grep -Eq 'sk-[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}' "$WORK_DIR/dex-strings.txt"; then
  echo "Credential-shaped value found in DEX strings." >&2
  exit 1
fi
if grep -E '\.(js|ts|kt|kts|dart|rs)$' "$WORK_DIR/apk-entries.txt"; then
  echo "Forbidden source/runtime language payload found in APK." >&2
  exit 1
fi
if grep -Ei '(^|/)(auth\.json|.*access-token.*|.*credentials.*|.*api[-_]?key.*)($|/)' "$WORK_DIR/apk-entries.txt"; then
  echo "Forbidden credential-shaped APK path found." >&2
  exit 1
fi

sha256sum "$VERSIONED_APK" > "$VERSIONED_APK.sha256"
sha256sum "$NAMED_APK" > "$NAMED_APK.sha256"

echo
echo "Built $APP_NAME $APP_VERSION (debug, $ABI)"
echo "APK: $NAMED_APK"
echo "Versioned APK: $VERSIONED_APK"
echo "SHA-256: $(sha256sum "$VERSIONED_APK" | awk '{print $1}')"
du -h "$VERSIONED_APK"
