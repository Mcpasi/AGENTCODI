#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

APP_NAME="AGENTCODI Package"
APP_ARTIFACT_NAME="AGENTCODI-Package"
APP_ID="de.agentcodi.pkg"
APP_VERSION="0.1.0-package.3"
VERSION_CODE="3"
MIN_SDK="29"
# Package Edition allows execution of user-installed files in private app storage.
TARGET_SDK="28"
ABI="arm64-v8a"
BUILD_VARIANT="${AGENTCODI_BUILD_VARIANT:-debug}"
BOOTSTRAP_LAYOUT="${AGENTCODI_BOOTSTRAP_LAYOUT:-nested}"

case "$BOOTSTRAP_LAYOUT" in
  nested|flat) ;;
  *)
    echo "Unsupported AGENTCODI bootstrap layout: $BOOTSTRAP_LAYOUT" >&2
    exit 1
    ;;
esac

if [ "$BOOTSTRAP_LAYOUT" = flat ] && [ ! -w / ]; then
  echo "Flat bootstrap fixtures require a disposable container with a writable /." >&2
  exit 1
fi

case "$BUILD_VARIANT" in
  debug|release) ;;
  *)
    echo "Unsupported AGENTCODI build variant: $BUILD_VARIANT" >&2
    exit 1
    ;;
esac

CODEX_ANDROID_VERSION="0.156.1-termux.1"
# Community Android ARM64 release, with verified downloadable or local bytes.
CODEX_ANDROID_URL="https://github.com/DioNanos/codex-termux/releases/download/v${CODEX_ANDROID_VERSION}/mmmbuto-codex-cli-termux-${CODEX_ANDROID_VERSION}.tgz"
CODEX_ANDROID_SHA256="44cee2f3a4a110fd79d4f7d61378d46fd72406f45cffb3163e809d63e86d946a"
CODEX_TERMUX_SOURCE_TAG="v0.156.1-termux.1"
CODEX_TERMUX_SOURCE_COMMIT="ea762071ec4acbf1531fcc7daf47524836f70a09"
CODEX_UPSTREAM_SOURCE_TAG="rust-v0.156.1"
CODEX_UPSTREAM_SOURCE_COMMIT="b412ff32c417f855c2b2d1581b77058eed87c84b"
CODEX_APP_SERVER_SOURCE_SHA256="6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5"
CODEX_CODE_MODE_HOST_SHA256="8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf"
CODEX_APP_SERVER_ANDROID_SHA256="cf1b406252928b0d68cb0f8f81adde6a02bf357a7fffb762d10cb503a235be06"
CODEX_LICENSE_SHA256="d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc"
CODEX_NOTICE_SHA256="8228749dd4dd6026baed0442f80e911308430478449285c865b188d97e6a013c"
CODEX_SCHEMA_BUNDLE_SHA256="eb1ba91bd0fab656523092f6ed7de3ea7aef278921a650f14dc871ae7dcfaf84"
CODEX_V2_SCHEMA_BUNDLE_SHA256="995fc3b8f8c469f6787e8fc5be4038c4f31359025edd8480b862e83355f3bf3b"
CODEX_DEFAULT_HOST_NAME="codex-code-mode-host"
CODEX_PACKAGED_HOST_NAME="libcodex-codehost.so"
CODEX_DEFAULT_HOST_OFFSET="10568364"

# User tools are installed from the edition APT catalog, never from APK payloads.
TERMINAL_SHELL_NAME="libagentcodi-shell.so"
ZLIB_RUNTIME_SHA256="fc9659e5d77c32149627ef3c357a1a76cfd44b93917e29c6c1c78cb054f92b83"
CLANG_TOOLCHAIN_VERSION="21.1.8"

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
LD_LLD="${AGENTCODI_LD_LLD:-$TERMUX_PREFIX/bin/ld.lld}"
CACHE_DIR="${AGENTCODI_CACHE_DIR:-$PROJECT_ROOT/.cache/android}"
OUTPUT_DIR="${AGENTCODI_OUTPUT_DIR:-$PROJECT_ROOT/output/apk}"
BUILD_ROOT="$PROJECT_ROOT/.build"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required build command: $1" >&2
    exit 1
  fi
}

for command_name in apksigner awk cmp curl dd diff dpkg-deb file grep python3 readelf realpath rg sed sha256sum stat strings tar timeout tr unzip wc xargs zip zipalign zipinfo; do
  require_command "$command_name"
done
for executable in \
    "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL" "$CLANGXX" "$LLVM_STRIP" \
    "$LD_LLD"; do
  if [ ! -x "$executable" ]; then
    echo "Missing required executable: $executable" >&2
    exit 1
  fi
done

for toolchain_executable in \
    "$CLANGXX" "$LLVM_STRIP" "$LD_LLD"; do
  toolchain_version="$("$toolchain_executable" --version 2>/dev/null \
    | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
  if [ "$toolchain_version" != "$CLANG_TOOLCHAIN_VERSION" ]; then
    echo "Pinned LLVM toolchain mismatch: $toolchain_executable" >&2
    echo "Expected $CLANG_TOOLCHAIN_VERSION, found ${toolchain_version:-none}." >&2
    echo "The derived runtime hashes cover this toolchain's generated code." >&2
    exit 1
  fi
done

validate_external_private_file() {
  local configuration_name="$1"
  local configured_path="$2"
  local canonical_path
  local file_mode
  local link_count

  if [ -z "$configured_path" ]; then
    echo "Missing release signing configuration: $configuration_name" >&2
    exit 1
  fi
  case "$configured_path" in
    /*) ;;
    *)
      echo "$configuration_name must be an absolute path outside the project." >&2
      exit 1
      ;;
  esac
  if [ ! -f "$configured_path" ] || [ -L "$configured_path" ] || [ ! -s "$configured_path" ]; then
    echo "$configuration_name must name a non-empty, non-symlink regular file." >&2
    exit 1
  fi
  canonical_path="$(realpath -- "$configured_path")"
  case "$canonical_path" in
    "$PROJECT_ROOT"|"$PROJECT_ROOT"/*)
      echo "$configuration_name must remain outside the project tree." >&2
      exit 1
      ;;
  esac
  file_mode="$(stat -c '%a' "$canonical_path")"
  if (( (8#$file_mode & 077) != 0 )); then
    echo "$configuration_name must not be accessible by group or other users." >&2
    exit 1
  fi
  link_count="$(stat -c '%h' "$canonical_path")"
  if [ "$link_count" -ne 1 ]; then
    echo "$configuration_name must not be hard-linked." >&2
    exit 1
  fi
  printf '%s\n' "$canonical_path"
}

RELEASE_KEYSTORE=""
RELEASE_KEY_ALIAS=""
RELEASE_PASSWORD_MODE=""
RELEASE_STORE_PASSWORD_FILE=""
RELEASE_KEY_PASSWORD_FILE=""
EXPECTED_RELEASE_CERT_SHA256=""
if [ "$BUILD_VARIANT" = "release" ]; then
  RELEASE_KEYSTORE="$(validate_external_private_file \
    AGENTCODI_RELEASE_KEYSTORE "${AGENTCODI_RELEASE_KEYSTORE:-}")"
  RELEASE_PASSWORD_MODE="${AGENTCODI_RELEASE_PASSWORD_MODE:-file}"
  case "$RELEASE_PASSWORD_MODE" in
    file)
      RELEASE_STORE_PASSWORD_FILE="$(validate_external_private_file \
        AGENTCODI_RELEASE_STORE_PASSWORD_FILE "${AGENTCODI_RELEASE_STORE_PASSWORD_FILE:-}")"
      RELEASE_KEY_PASSWORD_FILE="$(validate_external_private_file \
        AGENTCODI_RELEASE_KEY_PASSWORD_FILE "${AGENTCODI_RELEASE_KEY_PASSWORD_FILE:-}")"
      ;;
    prompt)
      if [ ! -t 0 ]; then
        echo "Interactive release password mode requires a terminal." >&2
        exit 1
      fi
      ;;
    *)
      echo "AGENTCODI_RELEASE_PASSWORD_MODE must be file or prompt." >&2
      exit 1
      ;;
  esac
  RELEASE_KEY_ALIAS="${AGENTCODI_RELEASE_KEY_ALIAS:-}"
  if [ -z "$RELEASE_KEY_ALIAS" ] \
      || [ "${#RELEASE_KEY_ALIAS}" -gt 128 ] \
      || [[ "$RELEASE_KEY_ALIAS" == *[!A-Za-z0-9._-]* ]]; then
    echo "AGENTCODI_RELEASE_KEY_ALIAS must contain 1-128 safe alias characters." >&2
    exit 1
  fi
  EXPECTED_RELEASE_CERT_SHA256="${AGENTCODI_RELEASE_CERT_SHA256:-}"
  if ! printf '%s\n' "$EXPECTED_RELEASE_CERT_SHA256" | grep -Eq '^[0-9A-Fa-f]{64}$'; then
    echo "AGENTCODI_RELEASE_CERT_SHA256 must be exactly 64 hexadecimal characters." >&2
    exit 1
  fi
  EXPECTED_RELEASE_CERT_SHA256="$(printf '%s' "$EXPECTED_RELEASE_CERT_SHA256" | tr '[:upper:]' '[:lower:]')"
fi

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

patch_elf_name() {
  local file="$1"
  local old_name="$2"
  local new_name="$3"
  local expected_count="$4"
  local matches
  local actual_count

  if [ "${#old_name}" -ne "${#new_name}" ]; then
    echo "ELF dependency relocation must preserve string length." >&2
    exit 1
  fi
  matches="$(grep -aboF "$old_name" "$file" || true)"
  actual_count="$(printf '%s\n' "$matches" | grep -c . || true)"
  if [ "$actual_count" -ne "$expected_count" ]; then
    echo "Unexpected ELF dependency occurrence count for $old_name in $file." >&2
    exit 1
  fi
  while IFS=: read -r offset ignored; do
    if [ -z "$offset" ]; then
      continue
    fi
    printf '%s' "$new_name" \
      | dd of="$file" bs=1 seek="$offset" conv=notrunc status=none
  done <<EOF
$matches
EOF
  if grep -aFq "$old_name" "$file" \
      || [ "$(grep -aoF "$new_name" "$file" | wc -l)" -ne "$expected_count" ]; then
    echo "ELF dependency relocation failed for $file." >&2
    exit 1
  fi
}

verify_file_sha256() {
  local file="$1"
  local expected="$2"
  if ! printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status; then
    echo "Derived runtime hash mismatch: $file" >&2
    echo "Expected SHA-256: $expected" >&2
    echo "Actual SHA-256: $(sha256sum "$file" | awk '{print $1}')" >&2
    exit 1
  fi
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
# Prefer the supplied/local Community archive. A replaced .tgz must never be hidden
# by the old content-addressed cache; the updater handles deliberate repinning.
CODEX_CACHED_ARCHIVE="$CACHE_DIR/codex/$CODEX_ANDROID_SHA256/package.tgz"
download_verified "$CODEX_ANDROID_URL" "$CODEX_ANDROID_SHA256" "$CODEX_CACHED_ARCHIVE"
CODEX_ANDROID_ARCHIVE="$("$PROJECT_ROOT/scripts/update-codex-runtime.sh" --select-build-archive)"

# End of build-input configuration.
echo "Verifying pinned Android build inputs..."
if [ ! -f "$CODEX_ANDROID_ARCHIVE" ] || [ -L "$CODEX_ANDROID_ARCHIVE" ]; then
  echo "Pinned local Codex archive is missing or linked: $CODEX_ANDROID_ARCHIVE" >&2
  echo "AGENTCODI_CODEX_ARCHIVE may select another location for the same SHA-256-pinned archive." >&2
  exit 1
fi
verify_file_sha256 "$CODEX_ANDROID_ARCHIVE" "$CODEX_ANDROID_SHA256"
echo "Codex Community source: $CODEX_TERMUX_SOURCE_COMMIT"
echo "Codex archive SHA-256: $CODEX_ANDROID_SHA256"

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

echo "Running Java, C++, and architecture tests..."
"$SCRIPT_DIR/test.sh"

WORK_DIR="$(mktemp -d "$BUILD_ROOT/apk.work.XXXXXX")"
BOOTSTRAP_FLAT_DIRS=()
cleanup() {
  local directory
  for directory in "${BOOTSTRAP_FLAT_DIRS[@]}"; do
    case "$directory" in
      /agentcodi-bootstrap-*.??????) rm -rf -- "$directory" ;;
      *) echo "Refusing unsafe bootstrap cleanup: $directory" >&2 ;;
    esac
  done
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
CODEX_SCHEMA_DIR="$WORK_DIR/codex-schema"
CODEX_SCHEMA_HOME="$WORK_DIR/codex-schema-home"
CODEX_SCHEMA_TMP="$WORK_DIR/codex-schema-tmp"
THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/codex"
ZLIB_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/zlib"
LIBCXX_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/libcxx"
mkdir -p "$LIBCXX_THIRD_PARTY_ASSETS"
mkdir -p "$EXTRACT_DIR" "$AAPT2_EXTRACT" "$GENERATED_JAVA" "$CLASSES_ROOT" "$JARS_ROOT" "$DEX_DIR" "$NATIVE_DIR" "$CODEX_EXTRACT" "$THIRD_PARTY_ASSETS" "$ZLIB_THIRD_PARTY_ASSETS"
# The bootstrap is built from the pinned edition recipes, not from Termux DEBs.
PACKAGE_BOOTSTRAP_INPUT="$PROJECT_ROOT/output/package-bootstrap"
PACKAGE_BOOTSTRAP_ASSETS="$ADDITIONS/assets/third-party/package-bootstrap"
test -f "$PACKAGE_BOOTSTRAP_INPUT/bootstrap-aarch64.zip"
test -f "$PACKAGE_BOOTSTRAP_INPUT/BOOTSTRAP-MANIFEST"
(
  cd "$PACKAGE_BOOTSTRAP_INPUT"
  sha256sum -c SHA256SUMS
)
cp "$PROJECT_ROOT/third_party/community-codex/DEPENDENCY-LICENSE-INDEX.json" \
  "$PROJECT_ROOT/third_party/community-codex/DEPENDENCY-LICENSES.zip" \
  "$PROJECT_ROOT/third_party/community-codex/DEPENDENCY-PROVENANCE.json" \
  "$PROJECT_ROOT/third_party/community-codex/MPL-SOURCE-INDEX.json" \
  "$PROJECT_ROOT/third_party/community-codex/MPL-SOURCE-OFFER.txt" \
  "$PROJECT_ROOT/third_party/community-codex/MPL-SOURCES.zip" "$THIRD_PARTY_ASSETS/"
mkdir -p "$PACKAGE_BOOTSTRAP_ASSETS"
cp "$PACKAGE_BOOTSTRAP_INPUT/bootstrap-aarch64.zip" "$PACKAGE_BOOTSTRAP_INPUT/BOOTSTRAP-MANIFEST" \
  "$PACKAGE_BOOTSTRAP_INPUT/bootstrap-report.json" "$PACKAGE_BOOTSTRAP_INPUT/BOOTSTRAP-LICENSE-INDEX.json" "$PACKAGE_BOOTSTRAP_ASSETS/"
mkdir -m 700 "$CODEX_SCHEMA_DIR" "$CODEX_SCHEMA_HOME" "$CODEX_SCHEMA_TMP"
mkdir -m 700 "$CODEX_SCHEMA_HOME/codex-home"

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
CODEX_SOURCE_BINARY="$CODEX_EXTRACT/package/bin/codex.bin"
CODEX_BINARY="$WORK_DIR/codex-app-server-android"
CODEX_CODE_MODE_HOST_BINARY="$CODEX_EXTRACT/package/bin/codex-code-mode-host"
CODEX_LICENSE="$CODEX_EXTRACT/package/LICENSE"
CODEX_NOTICE="$CODEX_EXTRACT/package/NOTICE"
CODEX_PACKAGE_JSON="$CODEX_EXTRACT/package/package.json"
TERMUX_RUNTIME_PREFIX="$AAPT2_EXTRACT/data/data/com.termux/files/usr"
ZLIB_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libz.so.1.3.2"
ZLIB_LICENSE_SOURCE="$TERMUX_RUNTIME_PREFIX/share/doc/zlib/copyright"
if [ ! -f "$LIBCXX_SHARED" ] || ! file "$LIBCXX_SHARED" | grep -q 'ARM aarch64'; then
  echo "Pinned libc++ runtime is missing or not ARM64." >&2
  exit 1
fi
for codex_file in "$CODEX_SOURCE_BINARY" "$CODEX_CODE_MODE_HOST_BINARY" "$CODEX_LICENSE" "$CODEX_NOTICE" "$CODEX_PACKAGE_JSON"; do
  if [ ! -f "$codex_file" ]; then
    echo "Pinned Codex archive is missing: $codex_file" >&2
    exit 1
  fi
done
if ! printf '%s  %s\n' "$CODEX_APP_SERVER_SOURCE_SHA256" "$CODEX_SOURCE_BINARY" | sha256sum --check --status \
    || ! printf '%s  %s\n' "$CODEX_CODE_MODE_HOST_SHA256" "$CODEX_CODE_MODE_HOST_BINARY" | sha256sum --check --status; then
  echo "Pinned Codex archive contains an unexpected executable." >&2
  exit 1
fi
verify_file_sha256 "$CODEX_LICENSE" "$CODEX_LICENSE_SHA256"
verify_file_sha256 "$CODEX_NOTICE" "$CODEX_NOTICE_SHA256"
# Archive/executable/license hashes above bind these declarations to the pinned
# release. Human-maintained README text can lag behind the actual package.
CODEX_METADATA_CLASSES="$WORK_DIR/codex-metadata-classes"
mkdir -p "$CODEX_METADATA_CLASSES"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -d "$CODEX_METADATA_CLASSES" \
  "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/JsonCodec.java" \
  "$PROJECT_ROOT/scripts/java/de/agentcodi/tools/CodexPackageMetadata.java"
"$JAVA" -Xmx64m -cp "$CODEX_METADATA_CLASSES" \
  de.agentcodi.tools.CodexPackageMetadata \
  "$CODEX_PACKAGE_JSON" "$CODEX_ANDROID_VERSION" "$CODEX_UPSTREAM_SOURCE_TAG"
env -i \
  HOME="$CODEX_SCHEMA_HOME" \
  CODEX_HOME="$CODEX_SCHEMA_HOME/codex-home" \
  TMPDIR="$CODEX_SCHEMA_TMP" \
  LD_LIBRARY_PATH="$CODEX_EXTRACT/package/bin" \
  "$CODEX_SOURCE_BINARY" app-server generate-json-schema --out "$CODEX_SCHEMA_DIR"
verify_file_sha256 \
  "$CODEX_SCHEMA_DIR/codex_app_server_protocol.schemas.json" \
  "$CODEX_SCHEMA_BUNDLE_SHA256"
verify_file_sha256 \
  "$CODEX_SCHEMA_DIR/codex_app_server_protocol.v2.schemas.json" \
  "$CODEX_V2_SCHEMA_BUNDLE_SHA256"
for required_schema_method in \
    'item/commandExecution/requestApproval' \
    'item/fileChange/requestApproval' \
    'item/tool/requestUserInput' \
    'thread/list' \
    'thread/resume' \
    'turn/start' \
    'turn/steer' \
    'app/list' \
    'app/list/updated' \
    'app/installed' \
    'app/read' \
    'command/exec'; do
  if ! grep -Fq "$required_schema_method" \
      "$CODEX_SCHEMA_DIR/codex_app_server_protocol.schemas.json"; then
    echo "Pinned Codex schema is missing required method: $required_schema_method" >&2
    exit 1
  fi
done
if [ "${#CODEX_DEFAULT_HOST_NAME}" -ne "${#CODEX_PACKAGED_HOST_NAME}" ]; then
  echo "Android host-name relocation must preserve the Codex binary layout." >&2
  exit 1
fi
actual_default_host_name="$(dd if="$CODEX_SOURCE_BINARY" bs=1 skip="$CODEX_DEFAULT_HOST_OFFSET" count="${#CODEX_DEFAULT_HOST_NAME}" status=none)"
if [ "$actual_default_host_name" != "$CODEX_DEFAULT_HOST_NAME" ]; then
  echo "Pinned Codex app-server no longer contains the reviewed host-name field." >&2
  exit 1
fi
cp "$CODEX_SOURCE_BINARY" "$CODEX_BINARY"
printf '%s' "$CODEX_PACKAGED_HOST_NAME" \
  | dd of="$CODEX_BINARY" bs=1 seek="$CODEX_DEFAULT_HOST_OFFSET" conv=notrunc status=none
if ! printf '%s  %s\n' "$CODEX_APP_SERVER_ANDROID_SHA256" "$CODEX_BINARY" | sha256sum --check --status; then
  echo "Deterministic Android host-name relocation produced an unexpected app-server." >&2
  exit 1
fi
source_host_references="$(grep -ao "$CODEX_DEFAULT_HOST_NAME" "$CODEX_SOURCE_BINARY" | wc -l)"
if [ "$(grep -ao "$CODEX_DEFAULT_HOST_NAME" "$CODEX_BINARY" | wc -l)" -ne "$((source_host_references - 1))" ] \
    || [ "$(grep -ao "$CODEX_PACKAGED_HOST_NAME" "$CODEX_BINARY" | wc -l)" -ne 1 ]; then
  echo "Codex app-server host-name relocation did not change exactly one reviewed field." >&2
  exit 1
fi
for codex_executable in "$CODEX_BINARY" "$CODEX_CODE_MODE_HOST_BINARY"; do
  if ! file "$codex_executable" | grep -q 'ARM aarch64'; then
    echo "Pinned Codex executable is not ARM64: $codex_executable" >&2
    exit 1
  fi
  if ! readelf -l "$codex_executable" | grep -q '/system/bin/linker64'; then
    echo "Pinned Codex executable does not use the Android linker: $codex_executable" >&2
    exit 1
  fi
done
if cmp -s "$CODEX_BINARY" "$CODEX_CODE_MODE_HOST_BINARY"; then
  echo "Pinned Codex archive substituted the app-server binary for the code-mode host." >&2
  exit 1
fi

echo "Compiling Android resources..."
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" compile --dir "$PROJECT_ROOT/app/src/main/res" -o "$COMPILED_RESOURCES"

UNSIGNED_APK="$WORK_DIR/unsigned.apk"
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" link -o "$UNSIGNED_APK" --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" --java "$GENERATED_JAVA" --custom-package de.agentcodi.app --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" --version-code "$VERSION_CODE" --version-name "$APP_VERSION" -I "$ANDROID_JAR" "$COMPILED_RESOURCES"

echo "Compiling isolated Java modules..."
CORE_CLASSES="$CLASSES_ROOT/core"
REVIEW_MODE_CLASSES="$CLASSES_ROOT/review-mode"
COMPATIBILITY_MODE_CLASSES="$CLASSES_ROOT/compatibility-mode"
STORAGE_CLASSES="$CLASSES_ROOT/storage"
FILE_BROWSER_CONTRACTS_CLASSES="$CLASSES_ROOT/file-browser-contracts"
FILE_BROWSER_CLIENT_CLASSES="$CLASSES_ROOT/file-browser-client"
IMPORT_CONTRACTS_CLASSES="$CLASSES_ROOT/import-contracts"
IMPORT_CLIENT_CLASSES="$CLASSES_ROOT/import-client"
MCP_CONTRACTS_CLASSES="$CLASSES_ROOT/mcp-contracts"
MCP_CLIENT_CLASSES="$CLASSES_ROOT/mcp-client"
CONNECTOR_CONTRACTS_CLASSES="$CLASSES_ROOT/connector-contracts"
CONNECTOR_CLIENT_CLASSES="$CLASSES_ROOT/connector-client"
RUNTIME_CLASSES="$CLASSES_ROOT/runtime"
APP_CLASSES="$CLASSES_ROOT/app"
mkdir -p \
  "$CORE_CLASSES" \
  "$REVIEW_MODE_CLASSES" \
  "$COMPATIBILITY_MODE_CLASSES" \
  "$STORAGE_CLASSES" \
  "$FILE_BROWSER_CONTRACTS_CLASSES" \
  "$FILE_BROWSER_CLIENT_CLASSES" \
  "$IMPORT_CONTRACTS_CLASSES" \
  "$IMPORT_CLIENT_CLASSES" \
  "$MCP_CONTRACTS_CLASSES" \
  "$MCP_CLIENT_CLASSES" \
  "$CONNECTOR_CONTRACTS_CLASSES" \
  "$CONNECTOR_CLIENT_CLASSES" \
  "$RUNTIME_CLASSES" \
  "$APP_CLASSES"

find "$PROJECT_ROOT/modules/core/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/core-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$CORE_CLASSES" @"$WORK_DIR/core-sources.txt"
CORE_JAR="$JARS_ROOT/core.jar"
"$JAR" cf "$CORE_JAR" -C "$CORE_CLASSES" .

find "$PROJECT_ROOT/modules/review-mode/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/review-mode-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR" -d "$REVIEW_MODE_CLASSES" @"$WORK_DIR/review-mode-sources.txt"
REVIEW_MODE_JAR="$JARS_ROOT/review-mode.jar"
"$JAR" cf "$REVIEW_MODE_JAR" -C "$REVIEW_MODE_CLASSES" .


find "$PROJECT_ROOT/modules/compatibility-mode/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/compatibility-mode-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR" -d "$COMPATIBILITY_MODE_CLASSES" @"$WORK_DIR/compatibility-mode-sources.txt"
COMPATIBILITY_MODE_JAR="$JARS_ROOT/compatibility-mode.jar"
"$JAR" cf "$COMPATIBILITY_MODE_JAR" -C "$COMPATIBILITY_MODE_CLASSES" .

find "$PROJECT_ROOT/modules/storage/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/storage-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$STORAGE_CLASSES" @"$WORK_DIR/storage-sources.txt"
STORAGE_JAR="$JARS_ROOT/storage.jar"
"$JAR" cf "$STORAGE_JAR" -C "$STORAGE_CLASSES" .

find "$PROJECT_ROOT/modules/file-browser-contracts/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/file-browser-contracts-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$FILE_BROWSER_CONTRACTS_CLASSES" @"$WORK_DIR/file-browser-contracts-sources.txt"
FILE_BROWSER_CONTRACTS_JAR="$JARS_ROOT/file-browser-contracts.jar"
"$JAR" cf "$FILE_BROWSER_CONTRACTS_JAR" -C "$FILE_BROWSER_CONTRACTS_CLASSES" .

find "$PROJECT_ROOT/modules/file-browser-client/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/file-browser-client-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$STORAGE_JAR:$FILE_BROWSER_CONTRACTS_JAR" -d "$FILE_BROWSER_CLIENT_CLASSES" @"$WORK_DIR/file-browser-client-sources.txt"
FILE_BROWSER_CLIENT_JAR="$JARS_ROOT/file-browser-client.jar"
"$JAR" cf "$FILE_BROWSER_CLIENT_JAR" -C "$FILE_BROWSER_CLIENT_CLASSES" .

find "$PROJECT_ROOT/modules/import-contracts/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/import-contracts-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$IMPORT_CONTRACTS_CLASSES" @"$WORK_DIR/import-contracts-sources.txt"
IMPORT_CONTRACTS_JAR="$JARS_ROOT/import-contracts.jar"
"$JAR" cf "$IMPORT_CONTRACTS_JAR" -C "$IMPORT_CONTRACTS_CLASSES" .

find "$PROJECT_ROOT/modules/import-client/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/import-client-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$STORAGE_JAR:$IMPORT_CONTRACTS_JAR" -d "$IMPORT_CLIENT_CLASSES" @"$WORK_DIR/import-client-sources.txt"
IMPORT_CLIENT_JAR="$JARS_ROOT/import-client.jar"
"$JAR" cf "$IMPORT_CLIENT_JAR" -C "$IMPORT_CLIENT_CLASSES" .

find "$PROJECT_ROOT/modules/mcp-contracts/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/mcp-contracts-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$MCP_CONTRACTS_CLASSES" @"$WORK_DIR/mcp-contracts-sources.txt"
MCP_CONTRACTS_JAR="$JARS_ROOT/mcp-contracts.jar"
"$JAR" cf "$MCP_CONTRACTS_JAR" -C "$MCP_CONTRACTS_CLASSES" .

find "$PROJECT_ROOT/modules/mcp-client/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/mcp-client-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$MCP_CONTRACTS_JAR" -d "$MCP_CLIENT_CLASSES" @"$WORK_DIR/mcp-client-sources.txt"
MCP_CLIENT_JAR="$JARS_ROOT/mcp-client.jar"
"$JAR" cf "$MCP_CLIENT_JAR" -C "$MCP_CLIENT_CLASSES" .

find "$PROJECT_ROOT/modules/connector-contracts/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/connector-contracts-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -d "$CONNECTOR_CONTRACTS_CLASSES" @"$WORK_DIR/connector-contracts-sources.txt"
CONNECTOR_CONTRACTS_JAR="$JARS_ROOT/connector-contracts.jar"
"$JAR" cf "$CONNECTOR_CONTRACTS_JAR" -C "$CONNECTOR_CONTRACTS_CLASSES" .

find "$PROJECT_ROOT/modules/connector-client/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/connector-client-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$CONNECTOR_CONTRACTS_JAR" -d "$CONNECTOR_CLIENT_CLASSES" @"$WORK_DIR/connector-client-sources.txt"
CONNECTOR_CLIENT_JAR="$JARS_ROOT/connector-client.jar"
"$JAR" cf "$CONNECTOR_CLIENT_JAR" -C "$CONNECTOR_CLIENT_CLASSES" .

find "$PROJECT_ROOT/modules/runtime/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/runtime-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$REVIEW_MODE_JAR:$COMPATIBILITY_MODE_JAR:$STORAGE_JAR:$FILE_BROWSER_CONTRACTS_JAR:$FILE_BROWSER_CLIENT_JAR:$IMPORT_CONTRACTS_JAR:$IMPORT_CLIENT_JAR:$MCP_CONTRACTS_JAR:$MCP_CLIENT_JAR:$CONNECTOR_CONTRACTS_JAR:$CONNECTOR_CLIENT_JAR" -d "$RUNTIME_CLASSES" @"$WORK_DIR/runtime-sources.txt"
RUNTIME_JAR="$JARS_ROOT/runtime.jar"
"$JAR" cf "$RUNTIME_JAR" -C "$RUNTIME_CLASSES" .

find "$PROJECT_ROOT/app/src/main/java" "$GENERATED_JAVA" -type f -name '*.java' -print | sort > "$WORK_DIR/app-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$REVIEW_MODE_JAR:$COMPATIBILITY_MODE_JAR:$STORAGE_JAR:$FILE_BROWSER_CONTRACTS_JAR:$IMPORT_CONTRACTS_JAR:$MCP_CONTRACTS_JAR:$MCP_CLIENT_JAR:$CONNECTOR_CONTRACTS_JAR:$CONNECTOR_CLIENT_JAR:$RUNTIME_JAR" -d "$APP_CLASSES" @"$WORK_DIR/app-sources.txt"
APP_JAR="$JARS_ROOT/app.jar"
"$JAR" cf "$APP_JAR" -C "$APP_CLASSES" .

echo "Compiling ARM64 JNI engine..."
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -shared -fPIC -std=c++17 -O2 -Wall -Wextra -Werror -pthread -fvisibility=hidden -I"$JAVA_HOME_17/include" -I"$JAVA_HOME_17/include/linux" -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/agentcodi_engine.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/png_validator.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/sha256.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_directory_reader.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_file_reader.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_import_installer.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/jni_bridge.cpp" -Wl,-soname,libagentcodi.so -lz -llog -o "$NATIVE_DIR/libagentcodi.so"
"$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/libagentcodi.so"

echo "Compiling packaged terminal shell bridge..."
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -fPIE -pie -std=c++17 -O2 -Wall -Wextra -Werror -pthread "$PROJECT_ROOT/modules/native-engine/src/main/cpp/package_shell_main.cpp" -o "$NATIVE_DIR/$TERMINAL_SHELL_NAME"
"$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/$TERMINAL_SHELL_NAME"


cp "$LIBCXX_SHARED" "$NATIVE_DIR/libc++_shared.so"
cp "$CODEX_BINARY" "$NATIVE_DIR/libcodex.so"
cp "$CODEX_CODE_MODE_HOST_BINARY" "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME"
cp -L "$ZLIB_SOURCE_LIBRARY" "$NATIVE_DIR/libz_1.so"
patch_elf_name "$NATIVE_DIR/libz_1.so" 'libz.so.1' 'libz_1.so' 1
patch_elf_name "$NATIVE_DIR/libagentcodi.so" 'libz.so.1' 'libz_1.so' 1
verify_file_sha256 "$NATIVE_DIR/libz_1.so" "$ZLIB_RUNTIME_SHA256"
cp "$CODEX_LICENSE" "$THIRD_PARTY_ASSETS/LICENSE"
cp "$CODEX_NOTICE" "$THIRD_PARTY_ASSETS/NOTICE"
cp "$ZLIB_LICENSE_SOURCE" "$ZLIB_THIRD_PARTY_ASSETS/ZLIB-LICENSE"
# Keep the exact distributor bytes and supplement its generic NCSA template
# with the complete upstream LLVM notices, including the LLVM exceptions.
LIBCXX_LICENSE_SOURCE="$TERMUX_RUNTIME_PREFIX/share/doc/libc++/copyright"
if [ -L "$LIBCXX_LICENSE_SOURCE" ]; then
  test "$(readlink "$LIBCXX_LICENSE_SOURCE")" = '../../LICENSES/NCSA.txt' || {
    echo "Pinned libc++ distributor license link changed." >&2
    exit 1
  }
  # termux-licenses is not an APK build input. Use its verbatim checked-in
  # source text for the declared link without downloading another package.
  cp "$PROJECT_ROOT/third_party/libcxx/DISTRIBUTOR-LICENSE" "$LIBCXX_THIRD_PARTY_ASSETS/DISTRIBUTOR-LICENSE"
else
  cp "$LIBCXX_LICENSE_SOURCE" "$LIBCXX_THIRD_PARTY_ASSETS/DISTRIBUTOR-LICENSE"
  cmp "$PROJECT_ROOT/third_party/libcxx/DISTRIBUTOR-LICENSE" "$LIBCXX_THIRD_PARTY_ASSETS/DISTRIBUTOR-LICENSE"
fi
cp "$PROJECT_ROOT/third_party/libcxx/LLVM-LICENSES" "$LIBCXX_THIRD_PARTY_ASSETS/LLVM-LICENSES"

# Exact executable/library closure, before any runtime execution.
EXPECTED_NATIVE_FILES="$WORK_DIR/expected-native-files"
python3 -B "$PROJECT_ROOT/scripts/package-edition/verify-apk-contract.py" --native-files | sort > "$EXPECTED_NATIVE_FILES"
find "$NATIVE_DIR" -maxdepth 1 -type f -printf '%f\n' | sort > "$WORK_DIR/actual-native-files"
diff -u "$EXPECTED_NATIVE_FILES" "$WORK_DIR/actual-native-files"
for native_payload in "$NATIVE_DIR"/*.so; do
  while IFS= read -r dependency; do
    case "$dependency" in
      libc.so|libdl.so|libm.so|liblog.so) ;;
      *) test -f "$NATIVE_DIR/$dependency" || {
        echo "Minimal APK has an unresolved ELF dependency: $native_payload -> $dependency" >&2
        exit 1
      } ;;
    esac
  done < <(readelf -dW "$native_payload" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
done
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
for workspace_symbol in \
  nativeOpenWorkspaceFile \
  nativeWorkspaceFileMetadata \
  nativeReadWorkspaceFile \
  nativePositionWorkspaceFile \
  nativeVerifyWorkspaceFile \
  nativeCloseWorkspaceFile \
  nativeListWorkspaceDirectory \
  nativeInstallWorkspaceImportNoReplace; do
  if ! readelf -Ws "$NATIVE_DIR/libagentcodi.so" \
      | grep -q "Java_de_agentcodi_runtime_NativeEngine_${workspace_symbol}"; then
    echo "JNI workspace file reader symbol is missing: $workspace_symbol" >&2
    exit 1
  fi
done
if readelf -Ws "$NATIVE_DIR/libagentcodi.so" \
    | grep -Eq 'Java_de_agentcodi_runtime_NativeEngine_native(Start|Read|Write|Resize|Poll|Stop)Terminal'; then
  echo "JNI library contains an obsolete same-UID terminal process path." >&2
  exit 1
fi
if ! readelf -d "$NATIVE_DIR/libagentcodi.so" | grep -q 'Shared library: \[libc++_shared.so\]' \
    || ! readelf -d "$NATIVE_DIR/libagentcodi.so" | grep -q 'Shared library: \[libz_1.so\]'; then
  echo "Native engine did not declare its packaged C++ and PNG zlib dependencies." >&2
  exit 1
fi
if readelf -Ws "$NATIVE_DIR/libagentcodi.so" | grep -F ' clearenv' >/dev/null \
    || ! readelf -Ws "$NATIVE_DIR/libagentcodi.so" | grep -F ' execve' >/dev/null; then
  echo "Native supervisor must use explicit execve environment storage without post-fork clearenv." >&2
  exit 1
fi
strings "$NATIVE_DIR/libagentcodi.so" > "$WORK_DIR/native-engine-strings.txt"
if ! grep -Fq 'model_provider="agentcodi-openai-http"' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing the HTTPS Responses provider selection." >&2
  exit 1
fi
if ! grep -Fq 'approval_policy="on-request"' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing the user-mediated approval policy." >&2
  exit 1
fi
if grep -Fq 'approval_policy="never"' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine still contains the obsolete no-prompt approval policy." >&2
  exit 1
fi
if ! grep -Fq 'model_providers.agentcodi-openai-http.supports_websockets=false' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine does not disable the failing Responses WebSocket path." >&2
  exit 1
fi
if ! grep -Fq 'CODEX_CODE_MODE_HOST_PATH' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine does not provide the packaged code-mode host path." >&2
  exit 1
fi
if grep -Fq 'Terminal forkpty' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine still contains the obsolete direct PTY implementation." >&2
  exit 1
fi
if ! grep -Fq 'shell_environment_policy={inherit="core"' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'include_only=[' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'analytics.enabled=false' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'otel.exporter="none"' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'feedback.enabled=false' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing the explicit tool environment allowlist or telemetry policy." >&2
  exit 1
fi
if ! grep -Fq 'generated_images' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'Generated image is not a complete valid bounded PNG' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'chunk CRC does not match its type and data' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'IEND precedes a complete IHDR-shaped IDAT stream' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'Materialized generated image changed during validation' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'Generated image could not be installed atomically in the workspace' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing complete PNG validation or workspace materialization." >&2
  exit 1
fi
if ! grep -aFq "$CODEX_PACKAGED_HOST_NAME" "$NATIVE_DIR/libcodex.so" \
    || [ "$(grep -ao "$CODEX_PACKAGED_HOST_NAME" "$NATIVE_DIR/libcodex.so" | wc -l)" -ne 1 ]; then
  echo "Packaged app-server does not resolve the Android-native host sibling." >&2
  exit 1
fi
for packaged_executable in \
    "$NATIVE_DIR/$TERMINAL_SHELL_NAME" \
    "$NATIVE_DIR/libcodex.so" \
    "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME"; do
  if ! file "$packaged_executable" | grep -q 'ARM aarch64' \
      || ! readelf -l "$packaged_executable" | grep -q '/system/bin/linker64'; then
    echo "Packaged terminal executable is not an Android ARM64 binary: $packaged_executable" >&2
    exit 1
  fi
done
# Node, npm, Python and ripgrep are absent from this APK. Their actual APT
# implementations remain covered by the separate Package catalog workflow.
app_server_help="$(env LD_LIBRARY_PATH="$NATIVE_DIR" "$NATIVE_DIR/libcodex.so" app-server --help)"
if ! printf '%s\n' "$app_server_help" | grep -Fq 'Transport endpoint URL'; then
  echo "Android-adapted app-server did not pass its native startup smoke test." >&2
  exit 1
fi
CONFIG_SMOKE_HOME="$WORK_DIR/config-smoke-home"
CONFIG_SMOKE_CODEX_HOME="$WORK_DIR/config-smoke-codex-home"
CONFIG_SMOKE_WORKSPACE="$WORK_DIR/config-smoke-workspace"
CONFIG_SMOKE_TEMP="$WORK_DIR/config-smoke-temp"
mkdir -p "$CONFIG_SMOKE_HOME" "$CONFIG_SMOKE_CODEX_HOME" "$CONFIG_SMOKE_WORKSPACE" "$CONFIG_SMOKE_TEMP"
chmod 700 "$CONFIG_SMOKE_HOME" "$CONFIG_SMOKE_CODEX_HOME" "$CONFIG_SMOKE_WORKSPACE" "$CONFIG_SMOKE_TEMP"
printf '%s\n' \
  'approval_policy="never"' \
  'shell_environment_policy={inherit="all"}' \
  '[analytics]' \
  'enabled=true' \
  > "$CONFIG_SMOKE_CODEX_HOME/config.toml"
chmod 600 "$CONFIG_SMOKE_CODEX_HOME/config.toml"
config_smoke_status=0
(
    printf '%s\n' "{\"method\":\"initialize\",\"id\":1,\"params\":{\"clientInfo\":{\"name\":\"agentcodi_android\",\"title\":\"AGENTCODI\",\"version\":\"$APP_VERSION\"},\"capabilities\":{\"experimentalApi\":true,\"optOutNotificationMethods\":[\"rawResponseItem/completed\",\"rawResponse/completed\",\"app/list/updated\"]}}}"
    sleep 15
  ) | timeout 60s env -i \
    HOME="$CONFIG_SMOKE_HOME" \
    CODEX_HOME="$CONFIG_SMOKE_CODEX_HOME" \
    TMPDIR="$CONFIG_SMOKE_TEMP" \
    TMP="$CONFIG_SMOKE_TEMP" \
    TEMP="$CONFIG_SMOKE_TEMP" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    PATH="/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$CONFIG_SMOKE_WORKSPACE" \
    CODEX_SELF_EXE="$NATIVE_DIR/libcodex.so" \
    CODEX_CODE_MODE_HOST_PATH="$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" \
    "$NATIVE_DIR/libcodex.so" app-server --stdio --strict-config \
    -c 'cli_auth_credentials_store="file"' \
    -c 'approval_policy="on-request"' \
    -c "shell_environment_policy={inherit=\"none\",ignore_default_excludes=false,set={PATH=\"/system/bin:/system/xbin\",SHELL=\"/system/bin/sh\",HOME=\"$CONFIG_SMOKE_HOME\",TMPDIR=\"$CONFIG_SMOKE_TEMP\",TMP=\"$CONFIG_SMOKE_TEMP\",TEMP=\"$CONFIG_SMOKE_TEMP\",LD_LIBRARY_PATH=\"$NATIVE_DIR\",HISTFILE=\"/dev/null\",NODE_REPL_HISTORY=\"/dev/null\",SSL_CERT_DIR=\"/system/etc/security/cacerts\",AGENTCODI_WORKSPACE=\"$CONFIG_SMOKE_WORKSPACE\"}}" \
    -c 'analytics.enabled=false' \
    -c 'otel.exporter="none"' \
    -c 'otel.log_user_prompt=false' \
    -c 'feedback.enabled=false' \
    -c 'check_for_update_on_startup=false' \
    -c 'allow_login_shell=false' \
    -c 'model_provider="agentcodi-openai-http"' \
    -c 'model_providers.agentcodi-openai-http.name="OpenAI"' \
    -c 'model_providers.agentcodi-openai-http.wire_api="responses"' \
    -c 'model_providers.agentcodi-openai-http.requires_openai_auth=true' \
    -c 'model_providers.agentcodi-openai-http.supports_websockets=false' \
    -c 'model_providers.agentcodi-openai-http.supports_standalone_web_search=true' \
    -c 'default_permissions=":danger-full-access"' \
    >"$WORK_DIR/config-smoke.stdout" 2>"$WORK_DIR/config-smoke.stderr" \
    || config_smoke_status=$?
if [ "$config_smoke_status" -ne 0 ]; then
  echo "Packaged app-server rejected the closed runtime configuration or initialize request (status $config_smoke_status)." >&2
  exit 1
fi
if ! grep -Fq '"id":1' "$WORK_DIR/config-smoke.stdout" \
    || ! grep -Fq '"codexHome":' "$WORK_DIR/config-smoke.stdout"; then
  echo "Packaged app-server did not complete the required initialize handshake." >&2
  exit 1
fi

BOOTSTRAP_SMOKE_BIN="$WORK_DIR/android-app-server-bootstrap-smoke"
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/png_validator.cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/sha256.cpp" \
  "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
  -lz -o "$BOOTSTRAP_SMOKE_BIN"
patch_elf_name "$BOOTSTRAP_SMOKE_BIN" 'libz.so.1' 'libz_1.so' 1
BOOTSTRAP_SMOKE_ROOT="$WORK_DIR/supervisor-bootstrap-smoke"
BOOTSTRAP_SMOKE_WORKSPACE="$BOOTSTRAP_SMOKE_ROOT/workspace"
BOOTSTRAP_SMOKE_IMPORTS="$BOOTSTRAP_SMOKE_WORKSPACE/imports"
BOOTSTRAP_SMOKE_CODEX_HOME="$BOOTSTRAP_SMOKE_ROOT/codex-home"
BOOTSTRAP_SMOKE_HOME="$BOOTSTRAP_SMOKE_ROOT/home"
BOOTSTRAP_SMOKE_PREFIX="$BOOTSTRAP_SMOKE_ROOT/usr"
BOOTSTRAP_SMOKE_STATE="$BOOTSTRAP_SMOKE_ROOT/state"
BOOTSTRAP_SMOKE_TEMP="$BOOTSTRAP_SMOKE_ROOT/temp"
BOOTSTRAP_SMOKE_NATIVE="$NATIVE_DIR"
if [ "$BOOTSTRAP_LAYOUT" = flat ]; then
  # Optional flat fixture layout for disposable Bionic containers. All copied
  # payload bytes are identical to the staged APK; Full access remains active.
  # This opt-in layout needs a disposable container with a writable /.
  bootstrap_flat_directory() {
    local variable="$1"
    local label="$2"
    local directory
    directory="$(mktemp -d "/agentcodi-bootstrap-$label.XXXXXX")"
    BOOTSTRAP_FLAT_DIRS+=("$directory")
    printf -v "$variable" '%s' "$directory"
  }
  bootstrap_flat_directory BOOTSTRAP_SMOKE_WORKSPACE workspace
  bootstrap_flat_directory BOOTSTRAP_SMOKE_NATIVE native
  bootstrap_flat_directory BOOTSTRAP_SMOKE_CODEX_HOME codex-home
  bootstrap_flat_directory BOOTSTRAP_SMOKE_HOME home
  bootstrap_flat_directory BOOTSTRAP_SMOKE_PREFIX usr
  bootstrap_flat_directory BOOTSTRAP_SMOKE_STATE state
  bootstrap_flat_directory BOOTSTRAP_SMOKE_TEMP temp
  BOOTSTRAP_SMOKE_ROOT="$BOOTSTRAP_SMOKE_WORKSPACE"
  BOOTSTRAP_SMOKE_IMPORTS="$BOOTSTRAP_SMOKE_WORKSPACE/imports"
  cp -a "$NATIVE_DIR/." "$BOOTSTRAP_SMOKE_NATIVE/"
  chmod 700 "$BOOTSTRAP_SMOKE_NATIVE"
  echo "Using flat bootstrap fixture roots for the container's Android linker."
fi
mkdir -p "$BOOTSTRAP_SMOKE_PREFIX/bin" "$BOOTSTRAP_SMOKE_PREFIX/lib" "$BOOTSTRAP_SMOKE_HOME/.local/bin"
chmod 700 "$BOOTSTRAP_SMOKE_PREFIX" "$BOOTSTRAP_SMOKE_PREFIX/bin" "$BOOTSTRAP_SMOKE_PREFIX/lib" "$BOOTSTRAP_SMOKE_HOME/.local" "$BOOTSTRAP_SMOKE_HOME/.local/bin"
mkdir -p "$BOOTSTRAP_SMOKE_WORKSPACE" "$BOOTSTRAP_SMOKE_IMPORTS" "$BOOTSTRAP_SMOKE_CODEX_HOME" "$BOOTSTRAP_SMOKE_HOME" "$BOOTSTRAP_SMOKE_STATE" "$BOOTSTRAP_SMOKE_TEMP"
chmod 700 "$BOOTSTRAP_SMOKE_ROOT" "$BOOTSTRAP_SMOKE_WORKSPACE" "$BOOTSTRAP_SMOKE_IMPORTS" "$BOOTSTRAP_SMOKE_CODEX_HOME" "$BOOTSTRAP_SMOKE_HOME" "$BOOTSTRAP_SMOKE_STATE" "$BOOTSTRAP_SMOKE_TEMP"
printf '%s\n' 'agentcodi-import-content-smoke' > "$BOOTSTRAP_SMOKE_IMPORTS/0123456789abcdef0123456789abcdef.bin"
chmod 600 "$BOOTSTRAP_SMOKE_IMPORTS/0123456789abcdef0123456789abcdef.bin"
printf '%s\n' \
  'approval_policy="never"' \
  'shell_environment_policy={inherit="all"}' \
  '[analytics]' \
  'enabled=true' \
  > "$BOOTSTRAP_SMOKE_CODEX_HOME/config.toml"
chmod 600 "$BOOTSTRAP_SMOKE_CODEX_HOME/config.toml"
# The real app-server bootstrap covers Full access, PTY, imports and tools.
# Individual commands and the complete sequence retain finite deadlines.
if timeout --kill-after=5s 300s env -i \
    LD_LIBRARY_PATH="$BOOTSTRAP_SMOKE_NATIVE" \
    PATH="/system/bin:/system/xbin" \
    "$BOOTSTRAP_SMOKE_BIN" \
    "$BOOTSTRAP_SMOKE_NATIVE/libcodex.so" \
    "$BOOTSTRAP_SMOKE_NATIVE/$CODEX_PACKAGED_HOST_NAME" \
    "$BOOTSTRAP_SMOKE_NATIVE/$TERMINAL_SHELL_NAME" \
    "$BOOTSTRAP_SMOKE_WORKSPACE" \
    "$BOOTSTRAP_SMOKE_CODEX_HOME" \
    "$BOOTSTRAP_SMOKE_HOME" \
    "$BOOTSTRAP_SMOKE_STATE" \
    "$BOOTSTRAP_SMOKE_TEMP" \
    "$BOOTSTRAP_SMOKE_NATIVE" \
    "$BOOTSTRAP_SMOKE_PREFIX"; then
  :
else
  bootstrap_status=$?
  if [ "$bootstrap_status" -eq 124 ] || [ "$bootstrap_status" -eq 137 ]; then
    echo "Packaged app-server bootstrap exceeded its 300-second overall deadline." >&2
  fi
  echo "Native supervisor failed the packaged app-server bootstrap sequence." >&2
  exit 1
fi
code_mode_host_help="$(env LD_LIBRARY_PATH="$NATIVE_DIR" "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" --help)"
if ! printf '%s\n' "$code_mode_host_help" | grep -Fq 'Transport endpoint:'; then
  echo "Packaged code-mode host did not pass its native startup smoke test." >&2
  exit 1
fi
while IFS= read -r native_file; do
  if ! readelf -lW "$native_file" | awk '$1 == "LOAD" { seen = 1; if ($NF != "0x4000") bad = 1 } END { exit (!seen || bad) }'; then
    echo "Native library is not compatible with 16 KiB Android pages: $native_file" >&2
    exit 1
  fi
done < <(find "$NATIVE_DIR" -maxdepth 1 -type f -name 'lib*.so' | sort)

echo "Creating DEX and APK..."
DEX_MODE="--debug"
if [ "$BUILD_VARIANT" = "release" ]; then
  DEX_MODE="--release"
fi
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 "$DEX_MODE" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$DEX_DIR" "$CORE_JAR" "$REVIEW_MODE_JAR" "$COMPATIBILITY_MODE_JAR" "$STORAGE_JAR" "$FILE_BROWSER_CONTRACTS_JAR" "$FILE_BROWSER_CLIENT_JAR" "$IMPORT_CONTRACTS_JAR" "$IMPORT_CLIENT_JAR" "$MCP_CONTRACTS_JAR" "$MCP_CLIENT_JAR" "$CONNECTOR_CONTRACTS_JAR" "$CONNECTOR_CLIENT_JAR" "$RUNTIME_JAR" "$APP_JAR"
cp "$DEX_DIR/classes.dex" "$ADDITIONS/classes.dex"

UNALIGNED_APK="$WORK_DIR/unaligned.apk"
ALIGNED_APK="$WORK_DIR/aligned.apk"
cp "$UNSIGNED_APK" "$UNALIGNED_APK"
(
  cd "$ADDITIONS"
  zip -q -9 -r "$UNALIGNED_APK" classes.dex lib assets
)
zipalign -f -p 4 "$UNALIGNED_APK" "$ALIGNED_APK"

DEBUG_CERT_SHA256="$(python3 "$PROJECT_ROOT/scripts/sign-debug-apk.py" --certificate-sha256)"
if [ "$BUILD_VARIANT" = "debug" ]; then
  VERSIONED_APK="$OUTPUT_DIR/$APP_ARTIFACT_NAME-$APP_VERSION-$ABI-debug.apk"
  NAMED_APK="$OUTPUT_DIR/$APP_ARTIFACT_NAME-debug.apk"
  python3 "$PROJECT_ROOT/scripts/sign-debug-apk.py" \
    --unsigned "$ALIGNED_APK" --output "$VERSIONED_APK" --min-sdk "$MIN_SDK"
else
  VERSIONED_APK="$OUTPUT_DIR/$APP_ARTIFACT_NAME-$APP_VERSION-$ABI-release.apk"
  NAMED_APK="$OUTPUT_DIR/$APP_ARTIFACT_NAME-release.apk"
  if [ "$RELEASE_PASSWORD_MODE" = "file" ]; then
    apksigner sign --min-sdk-version "$MIN_SDK" --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" --ks-pass "file:$RELEASE_STORE_PASSWORD_FILE" --key-pass "file:$RELEASE_KEY_PASSWORD_FILE" --out "$VERSIONED_APK" "$ALIGNED_APK"
  else
    apksigner sign --min-sdk-version "$MIN_SDK" --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" --out "$VERSIONED_APK" "$ALIGNED_APK"
  fi
fi
cp "$VERSIONED_APK" "$NAMED_APK"

echo "Verifying APK identity, signature, alignment, ABI, and payload..."
zipalign -c -p 4 "$VERSIONED_APK"
certificate_report="$(apksigner verify --verbose --print-certs "$VERSIONED_APK")"
printf '%s\n' "$certificate_report"
signer_count="$(printf '%s\n' "$certificate_report" | awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ { count++ } END { print count + 0 }')"
actual_signer_cert_sha256="$(printf '%s\n' "$certificate_report" | awk -F': ' '/^Signer #1 certificate SHA-256 digest:/ { print $2; exit }' | tr '[:upper:]' '[:lower:]')"
if [ "$signer_count" -ne 1 ] || ! printf '%s\n' "$actual_signer_cert_sha256" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "APK must contain exactly one signer with a valid SHA-256 certificate digest." >&2
  exit 1
fi
if [ "$BUILD_VARIANT" = "release" ]; then
  if [ "$actual_signer_cert_sha256" != "$EXPECTED_RELEASE_CERT_SHA256" ]; then
    echo "Release signer certificate does not match AGENTCODI_RELEASE_CERT_SHA256." >&2
    exit 1
  fi
  if [ "$actual_signer_cert_sha256" = "$DEBUG_CERT_SHA256" ]; then
    echo "Release APK must not use the public development test certificate." >&2
    exit 1
  fi
  if printf '%s\n' "$certificate_report" | grep -Fiq 'android debug'; then
    echo "Release APK must not use an Android debug certificate." >&2
    exit 1
  fi
elif [ "$actual_signer_cert_sha256" != "$DEBUG_CERT_SHA256" ]; then
  echo "Debug signer certificate does not match the pinned development identity." >&2
  exit 1
fi
badging="$(env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" dump badging "$VERSIONED_APK")"
printf '%s\n' "$badging" | grep -Fq "package: name='$APP_ID'"
printf '%s\n' "$badging" | grep -Fq "versionCode='$VERSION_CODE'"
printf '%s\n' "$badging" | grep -Fq "versionName='$APP_VERSION'"
printf '%s\n' "$badging" | grep -Fq "minSdkVersion:'$MIN_SDK'"
printf '%s\n' "$badging" | grep -Fq "targetSdkVersion:'$TARGET_SDK'"
printf '%s\n' "$badging" | grep -Fq "application-label:'$APP_NAME'"
printf '%s\n' "$badging" | grep -Fq "launchable-activity: name='de.agentcodi.app.MainActivity'"
printf '%s\n' "$badging" | grep -Fq "native-code: '$ABI'"
if printf '%s\n' "$badging" | grep -Fq 'application-debuggable'; then
  echo "Refusing an Android-debuggable APK." >&2
  exit 1
fi
printf '%s\n' "$badging" | grep -F "package: name='$APP_ID'"
printf '%s\n' "$badging" | grep -F "application-label:'$APP_NAME'"

zipinfo -1 "$VERSIONED_APK" > "$WORK_DIR/apk-entries.txt"
grep -Fx 'classes.dex' "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/libagentcodi.so" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/libc++_shared.so" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/libcodex.so" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/$CODEX_PACKAGED_HOST_NAME" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/$TERMINAL_SHELL_NAME" "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/package-bootstrap/bootstrap-aarch64.zip' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/package-bootstrap/BOOTSTRAP-MANIFEST' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/codex/LICENSE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/codex/NOTICE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/zlib/ZLIB-LICENSE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/raw/third_party_notices.txt' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/raw/agentcodi_apache_2_0.txt' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/xml/locales_config.xml' "$WORK_DIR/apk-entries.txt"
unzip -p "$VERSIONED_APK" resources.arsc | strings > "$WORK_DIR/resource-strings.txt"
grep -Fq 'Copyright 2026 Pascal (Mc Pasi)' "$WORK_DIR/resource-strings.txt"
grep -Fxq ' Apache License 2.0.' "$WORK_DIR/resource-strings.txt"
packaged_app_server_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/libcodex.so" | sha256sum | awk '{print $1}')"
packaged_code_mode_host_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/$CODEX_PACKAGED_HOST_NAME" | sha256sum | awk '{print $1}')"
if [ "$packaged_app_server_sha" != "$CODEX_APP_SERVER_ANDROID_SHA256" ] \
    || [ "$packaged_code_mode_host_sha" != "$CODEX_CODE_MODE_HOST_SHA256" ]; then
  echo "APK does not contain the reviewed Codex app-server/host pair." >&2
  exit 1
fi
packaged_zlib_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/libz_1.so" | sha256sum | awk '{print $1}')"
test "$packaged_zlib_sha" = "$ZLIB_RUNTIME_SHA256"
grep "^lib/$ABI/[^/]*\.so$" "$WORK_DIR/apk-entries.txt" | sed "s|^lib/$ABI/||" | sort > "$WORK_DIR/apk-native-files"
diff -u "$EXPECTED_NATIVE_FILES" "$WORK_DIR/apk-native-files"
if grep -Eq '^assets/third-party/(node|npm|python|ripgrep|toolchain)/' "$WORK_DIR/apk-entries.txt"; then
  echo "APK contains retired user-tool assets." >&2
  exit 1
fi
# Compare every shipped native byte with the verified staged payload.
while IFS= read -r native_name; do
  unzip -p "$VERSIONED_APK" "lib/$ABI/$native_name" > "$WORK_DIR/packaged-native"
  cmp "$NATIVE_DIR/$native_name" "$WORK_DIR/packaged-native"
done < "$EXPECTED_NATIVE_FILES"
# Check the complete native/asset set, every staged byte, bootstrap legal
# ownership and corresponding-source evidence. CI success is not release approval.
contract_release_args=()
if [ "$BUILD_VARIANT" = release ]; then contract_release_args+=(--release); fi
python3 -B "$PROJECT_ROOT/scripts/package-edition/verify-apk-contract.py" \
  --apk "$VERSIONED_APK" --staged "$ADDITIONS" \
  --output "$OUTPUT_DIR/package-apk-contract.json" "${contract_release_args[@]}"
unzip -p "$VERSIONED_APK" classes.dex | strings > "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/MainActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/SettingsActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/TerminalActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/LicensesActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/McpManagementActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/ConnectorActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AppLanguage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AgentCodiApplication;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/UiLanguage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CrashReportFormatter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CredentialGuard;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexFileMention;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexAppMention;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexWorkspaceAttachmentContext;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/TerminalOutputBuffer;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/TerminalSessionSnapshot;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexTerminalSession;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexSessionController;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexModelOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexReasoningOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexInteractiveRequest;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexApprovalDecision;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/mcp/McpCatalogSnapshot;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/mcp/client/McpCatalogLoader;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/mcp/client/McpCatalogController;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/connectors/ConnectorCatalogSnapshot;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/connectors/client/ConnectorCatalogLoader;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/connectors/client/ConnectorCatalogController;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/InteractiveRequestDialog;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/AgentRuntimeService;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/RuntimeText;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeAppServerTransport;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/CrashDiagnostics;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/WorkspaceImageExporter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/WorkspaceFileExporter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/WorkspaceFileImporter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/browser/WorkspaceBrowserPage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/browser/WorkspaceFilePreview;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/browser/client/WorkspaceFileBrowser;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceDirectoryCatalog;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/WorkspaceBrowserRepository;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeWorkspaceDirectoryCatalog;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/WorkspaceBrowserActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeWorkspaceDocumentInstaller;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeEngine;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'libcodex.so' "$WORK_DIR/dex-strings.txt"
grep -Fq "$CODEX_PACKAGED_HOST_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq "$TERMINAL_SHELL_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/CrashReportStore;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceImageFile;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceExportFile;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceArchive;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceFileAccess;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeWorkspaceFileAccess;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/imports/ImportedWorkspaceFile;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/imports/WorkspaceImportLimits;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/imports/client/WorkspaceDocumentImporter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/imports/client/WorkspaceDocumentInstaller;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'android.intent.action.OPEN_DOCUMENT' "$WORK_DIR/dex-strings.txt"
grep -Fq 'android.intent.action.CREATE_DOCUMENT' "$WORK_DIR/dex-strings.txt"
grep -Fq 'image_export' "$WORK_DIR/dex-strings.txt"
grep -Fq 'browser_directory_export' "$WORK_DIR/dex-strings.txt"
grep -Fq 'language_system' "$WORK_DIR/dex-strings.txt"
grep -Fq 'licenses_open' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Hard-linked workspace files are not exportable' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Codex configuration must be a regular file' "$WORK_DIR/dex-strings.txt"
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
echo "Built $APP_NAME $APP_VERSION ($BUILD_VARIANT-signed, non-debuggable, $ABI)"
echo "APK: $NAMED_APK"
echo "Versioned APK: $VERSIONED_APK"
echo "SHA-256: $(sha256sum "$VERSIONED_APK" | awk '{print $1}')"
du -h "$VERSIONED_APK"
