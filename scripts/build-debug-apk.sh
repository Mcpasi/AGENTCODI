#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

APP_NAME="AGENTCODI"
APP_ID="de.agentcodi.app"
APP_VERSION="0.4.8"
VERSION_CODE="28"
MIN_SDK="29"
TARGET_SDK="35"
ABI="arm64-v8a"
BUILD_VARIANT="${AGENTCODI_BUILD_VARIANT:-debug}"

case "$BUILD_VARIANT" in
  debug|release) ;;
  *)
    echo "Unsupported AGENTCODI build variant: $BUILD_VARIANT" >&2
    exit 1
    ;;
esac

CODEX_ANDROID_VERSION="0.147.2"
CODEX_ANDROID_URL="https://registry.npmjs.org/@mmmbuto/codex-cli-termux/-/codex-cli-termux-$CODEX_ANDROID_VERSION.tgz"
CODEX_ANDROID_SHA256="4b70bca7004402cf445670efe43775e76ac598f719c72a8d6c83ac8494bb2b5c"
CODEX_APP_SERVER_SOURCE_SHA256="c95b61282ed0086b9895b8d401fda274ef9ddf1a80fe808f3fad93f4444d8dc4"
CODEX_CODE_MODE_HOST_SHA256="aa90fc2ce11bc309a08ea25836019fda6c7ff7edc9eaa35f8f3746a37979fc18"
CODEX_APP_SERVER_ANDROID_SHA256="11db4fdd763e21fa81f4fb47d61c4bcbea145e817364eaa35f6e75146f85beee"
CODEX_DEFAULT_HOST_NAME="codex-code-mode-host"
CODEX_PACKAGED_HOST_NAME="libcodex-codehost.so"
CODEX_DEFAULT_HOST_OFFSET="10754589"

NODE_VERSION="24.18.0"
NODE_URL="https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs-lts/nodejs-lts_${NODE_VERSION}_aarch64.deb"
NODE_SHA256="6456b78aba9e0007de7a4c580d2b34bb3865145bebe06e75273152f8dcba4236"
CARES_VERSION="1.34.8"
CARES_URL="https://packages.termux.dev/apt/termux-main/pool/main/c/c-ares/c-ares_${CARES_VERSION}_aarch64.deb"
CARES_SHA256="7681fc23e822d7988ba8b2adf3468f93ae68f724dda365cff1385096a9fa87e6"
ICU_VERSION="78.3"
ICU_URL="https://packages.termux.dev/apt/termux-main/pool/main/libi/libicu/libicu_${ICU_VERSION}_aarch64.deb"
ICU_SHA256="f536403f65a08fe0df6e7304184e902d54def77d5c3bd5edfd9109d57601d276"
SQLITE_VERSION="3.53.4"
SQLITE_URL="https://packages.termux.dev/apt/termux-main/pool/main/libs/libsqlite/libsqlite_${SQLITE_VERSION}_aarch64.deb"
SQLITE_SHA256="0e909ce0d50fe123305446cd22e0c5edf535d40344b9b065fbdcdee52f53198d"
OPENSSL_VERSION="3.6.3"
OPENSSL_URL="https://packages.termux.dev/apt/termux-main/pool/main/o/openssl/openssl_1:${OPENSSL_VERSION}_aarch64.deb"
OPENSSL_SHA256="86760e9ce736f463236f2c15b1eb3a3fdcfc5778d0fd7077a917448dcc90f3aa"
NODE_LICENSE_URL="https://raw.githubusercontent.com/nodejs/node/v${NODE_VERSION}/LICENSE"
NODE_LICENSE_SHA256="148eacf7863ef4329224a29398623077200a27194aa075569faf4a0a85566ca5"
ICU_LICENSE_URL="https://raw.githubusercontent.com/unicode-org/icu/41bdb529bd7fa3d7c71759e0eef8600805873d61/LICENSE"
ICU_LICENSE_SHA256="e55522d81edc687a341a4411e0776e54ca654e90147f354a90458aaced4116af"
OPENSSL_LICENSE_URL="https://raw.githubusercontent.com/openssl/openssl/openssl-${OPENSSL_VERSION}/LICENSE.txt"
OPENSSL_LICENSE_SHA256="7d5450cb2d142651b8afa315b5f238efc805dad827d91ba367d8516bc9d49e7a"
TERMINAL_SHELL_NAME="libagentcodi-shell.so"
NODE_LIBRARY_NAME="libnode.so"
NODE_RUNTIME_SHA256="e31cd5c7f5db279d638c3ad773e04f12842077f0559f4da4f369440a6f4195c3"
CARES_RUNTIME_SHA256="68733ce8d4bb1bdc87d8ec550c58c70f3dcdb0f8c48d86b83235f536cb736e83"
CRYPTO_RUNTIME_SHA256="c20e21eb916f6f913aef6291af7312dd2b2c46aa60000db9c24de55c8492a0a4"
ICUDATA_RUNTIME_SHA256="3d0d02951e9bdbb32fc36e2761fbcd0d144c7ad1fca78e5b1c4c117066e892a6"
ICUI18N_RUNTIME_SHA256="ff60f64a9916536aa3e505ff519dfd72f3b491eb5c1d98a5d96a89a37f3202f7"
ICUUC_RUNTIME_SHA256="0561115e4c843c8967981e64d802150e68f6b3c9ed67241228bf276844670ae3"
SQLITE_RUNTIME_SHA256="ab224ec9350f2e9ea7cf6f8321636979dea1ef9e8461453433857a6b701b4c7a"
SSL_RUNTIME_SHA256="3d224f5c06e04351ed7e25d7fb6078ee8ce832106f1ef0d83fffa77d5e744234"
ZLIB_RUNTIME_SHA256="fc9659e5d77c32149627ef3c357a1a76cfd44b93917e29c6c1c78cb054f92b83"

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

for command_name in apksigner awk cmp curl dd dpkg-deb file grep readelf realpath rg sha256sum stat strings tar timeout tr unzip wc zip zipalign zipinfo; do
  require_command "$command_name"
done
for executable in "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL" "$CLANGXX" "$LLVM_STRIP"; do
  if [ ! -x "$executable" ]; then
    echo "Missing required executable: $executable" >&2
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
CODEX_ANDROID_ARCHIVE="$CACHE_DIR/codex-cli-termux-$CODEX_ANDROID_VERSION.tgz"
NODE_ARCHIVE="$CACHE_DIR/nodejs-lts-$NODE_VERSION-aarch64.deb"
CARES_ARCHIVE="$CACHE_DIR/c-ares-$CARES_VERSION-aarch64.deb"
ICU_ARCHIVE="$CACHE_DIR/libicu-$ICU_VERSION-aarch64.deb"
SQLITE_ARCHIVE="$CACHE_DIR/libsqlite-$SQLITE_VERSION-aarch64.deb"
OPENSSL_ARCHIVE="$CACHE_DIR/openssl-$OPENSSL_VERSION-aarch64.deb"
NODE_LICENSE_FILE="$CACHE_DIR/node-$NODE_VERSION-LICENSE"
ICU_LICENSE_FILE="$CACHE_DIR/icu-$ICU_VERSION-LICENSE"
OPENSSL_LICENSE_FILE="$CACHE_DIR/openssl-$OPENSSL_VERSION-LICENSE"

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
download_verified "$NODE_URL" "$NODE_SHA256" "$NODE_ARCHIVE"
download_verified "$CARES_URL" "$CARES_SHA256" "$CARES_ARCHIVE"
download_verified "$ICU_URL" "$ICU_SHA256" "$ICU_ARCHIVE"
download_verified "$SQLITE_URL" "$SQLITE_SHA256" "$SQLITE_ARCHIVE"
download_verified "$OPENSSL_URL" "$OPENSSL_SHA256" "$OPENSSL_ARCHIVE"
download_verified "$NODE_LICENSE_URL" "$NODE_LICENSE_SHA256" "$NODE_LICENSE_FILE"
download_verified "$ICU_LICENSE_URL" "$ICU_LICENSE_SHA256" "$ICU_LICENSE_FILE"
download_verified "$OPENSSL_LICENSE_URL" "$OPENSSL_LICENSE_SHA256" "$OPENSSL_LICENSE_FILE"

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
NODE_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/node"
mkdir -p "$EXTRACT_DIR" "$AAPT2_EXTRACT" "$GENERATED_JAVA" "$CLASSES_ROOT" "$JARS_ROOT" "$DEX_DIR" "$NATIVE_DIR" "$CODEX_EXTRACT" "$THIRD_PARTY_ASSETS" "$NODE_THIRD_PARTY_ASSETS"

(
  cd "$EXTRACT_DIR"
  "$JAR" xf "$PLATFORM_ARCHIVE" android-35/android.jar
)
ANDROID_JAR="$EXTRACT_DIR/android-35/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "Pinned platform archive did not contain android.jar." >&2
  exit 1
fi

for archive in "$AAPT2_ARCHIVE" "$ABSEIL_ARCHIVE" "$PROTOBUF_ARCHIVE" "$FMT_ARCHIVE" "$LIBCXX_ARCHIVE" "$EXPAT_ARCHIVE" "$PNG_ARCHIVE" "$ZOPFLI_ARCHIVE" "$ZLIB_ARCHIVE" "$NODE_ARCHIVE" "$CARES_ARCHIVE" "$ICU_ARCHIVE" "$SQLITE_ARCHIVE" "$OPENSSL_ARCHIVE"; do
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
TERMUX_RUNTIME_PREFIX="$AAPT2_EXTRACT/data/data/com.termux/files/usr"
NODE_SOURCE_BINARY="$TERMUX_RUNTIME_PREFIX/bin/node"
CARES_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libcares.so"
SQLITE_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libsqlite3.so.3.53.4"
CRYPTO_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libcrypto.so.3"
SSL_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libssl.so.3"
ICUDATA_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libicudata.so.78.3"
ICUUC_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libicuuc.so.78.3"
ICUI18N_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libicui18n.so.78.3"
ZLIB_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libz.so.1.3.2"
CARES_LICENSE_SOURCE="$TERMUX_RUNTIME_PREFIX/share/doc/c-ares/copyright"
ZLIB_LICENSE_SOURCE="$TERMUX_RUNTIME_PREFIX/share/doc/zlib/copyright"
if [ ! -f "$LIBCXX_SHARED" ] || ! file "$LIBCXX_SHARED" | grep -q 'ARM aarch64'; then
  echo "Pinned libc++ runtime is missing or not ARM64." >&2
  exit 1
fi
for codex_file in "$CODEX_SOURCE_BINARY" "$CODEX_CODE_MODE_HOST_BINARY" "$CODEX_LICENSE" "$CODEX_NOTICE"; do
  if [ ! -f "$codex_file" ]; then
    echo "Pinned Codex archive is missing: $codex_file" >&2
    exit 1
  fi
done
for node_file in "$NODE_SOURCE_BINARY" "$CARES_SOURCE_LIBRARY" "$SQLITE_SOURCE_LIBRARY" "$CRYPTO_SOURCE_LIBRARY" "$SSL_SOURCE_LIBRARY" "$ICUDATA_SOURCE_LIBRARY" "$ICUUC_SOURCE_LIBRARY" "$ICUI18N_SOURCE_LIBRARY" "$ZLIB_SOURCE_LIBRARY" "$CARES_LICENSE_SOURCE" "$ZLIB_LICENSE_SOURCE"; do
  if [ ! -f "$node_file" ]; then
    echo "Pinned Node.js runtime packages are missing: $node_file" >&2
    exit 1
  fi
done
if ! printf '%s  %s\n' "$CODEX_APP_SERVER_SOURCE_SHA256" "$CODEX_SOURCE_BINARY" | sha256sum --check --status \
    || ! printf '%s  %s\n' "$CODEX_CODE_MODE_HOST_SHA256" "$CODEX_CODE_MODE_HOST_BINARY" | sha256sum --check --status; then
  echo "Pinned Codex archive contains an unexpected executable." >&2
  exit 1
fi
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
if [ "$(grep -ao "$CODEX_DEFAULT_HOST_NAME" "$CODEX_BINARY" | wc -l)" -ne 1 ] \
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

echo "Compiling packaged terminal shell bridge..."
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -fPIE -pie -std=c++17 -O2 -Wall -Wextra -Werror -pthread "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_shell_main.cpp" -o "$NATIVE_DIR/$TERMINAL_SHELL_NAME"
"$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/$TERMINAL_SHELL_NAME"

cp "$LIBCXX_SHARED" "$NATIVE_DIR/libc++_shared.so"
cp "$CODEX_BINARY" "$NATIVE_DIR/libcodex.so"
cp "$CODEX_CODE_MODE_HOST_BINARY" "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME"
cp -L "$NODE_SOURCE_BINARY" "$NATIVE_DIR/$NODE_LIBRARY_NAME"
cp -L "$CARES_SOURCE_LIBRARY" "$NATIVE_DIR/libcares.so"
cp -L "$SQLITE_SOURCE_LIBRARY" "$NATIVE_DIR/libsqlite3.so"
cp -L "$CRYPTO_SOURCE_LIBRARY" "$NATIVE_DIR/libcrypto_3.so"
cp -L "$SSL_SOURCE_LIBRARY" "$NATIVE_DIR/libssl_3.so"
cp -L "$ICUDATA_SOURCE_LIBRARY" "$NATIVE_DIR/libicudata_78.so"
cp -L "$ICUUC_SOURCE_LIBRARY" "$NATIVE_DIR/libicuuc_78.so"
cp -L "$ICUI18N_SOURCE_LIBRARY" "$NATIVE_DIR/libicui18n_78.so"
cp -L "$ZLIB_SOURCE_LIBRARY" "$NATIVE_DIR/libz_1.so"

patch_elf_name "$NATIVE_DIR/$NODE_LIBRARY_NAME" 'libz.so.1' 'libz_1.so' 1
patch_elf_name "$NATIVE_DIR/$NODE_LIBRARY_NAME" 'libcrypto.so.3' 'libcrypto_3.so' 1
patch_elf_name "$NATIVE_DIR/$NODE_LIBRARY_NAME" 'libssl.so.3' 'libssl_3.so' 1
patch_elf_name "$NATIVE_DIR/$NODE_LIBRARY_NAME" 'libicui18n.so.78' 'libicui18n_78.so' 1
patch_elf_name "$NATIVE_DIR/$NODE_LIBRARY_NAME" 'libicuuc.so.78' 'libicuuc_78.so' 1
patch_elf_name "$NATIVE_DIR/libssl_3.so" 'libcrypto.so.3' 'libcrypto_3.so' 1
patch_elf_name "$NATIVE_DIR/libssl_3.so" 'libssl.so.3' 'libssl_3.so' 1
patch_elf_name "$NATIVE_DIR/libcrypto_3.so" 'libcrypto.so.3' 'libcrypto_3.so' 1
patch_elf_name "$NATIVE_DIR/libicudata_78.so" 'libicudata.so.78' 'libicudata_78.so' 1
patch_elf_name "$NATIVE_DIR/libicuuc_78.so" 'libicudata.so.78' 'libicudata_78.so' 1
patch_elf_name "$NATIVE_DIR/libicuuc_78.so" 'libicuuc.so.78' 'libicuuc_78.so' 1
patch_elf_name "$NATIVE_DIR/libicui18n_78.so" 'libicuuc.so.78' 'libicuuc_78.so' 1
patch_elf_name "$NATIVE_DIR/libicui18n_78.so" 'libicui18n.so.78' 'libicui18n_78.so' 1
patch_elf_name "$NATIVE_DIR/libz_1.so" 'libz.so.1' 'libz_1.so' 1
patch_elf_name "$NATIVE_DIR/libsqlite3.so" 'libz.so.1' 'libz_1.so' 1

verify_file_sha256 "$NATIVE_DIR/$NODE_LIBRARY_NAME" "$NODE_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libcares.so" "$CARES_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libcrypto_3.so" "$CRYPTO_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libicudata_78.so" "$ICUDATA_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libicui18n_78.so" "$ICUI18N_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libicuuc_78.so" "$ICUUC_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libsqlite3.so" "$SQLITE_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libssl_3.so" "$SSL_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libz_1.so" "$ZLIB_RUNTIME_SHA256"
chmod 755 "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$NATIVE_DIR/$NODE_LIBRARY_NAME"

cp "$CODEX_LICENSE" "$THIRD_PARTY_ASSETS/LICENSE"
cp "$CODEX_NOTICE" "$THIRD_PARTY_ASSETS/NOTICE"
cp "$NODE_LICENSE_FILE" "$NODE_THIRD_PARTY_ASSETS/NODE-LICENSE"
cp "$CARES_LICENSE_SOURCE" "$NODE_THIRD_PARTY_ASSETS/CARES-LICENSE"
cp "$ICU_LICENSE_FILE" "$NODE_THIRD_PARTY_ASSETS/ICU-LICENSE"
cp "$OPENSSL_LICENSE_FILE" "$NODE_THIRD_PARTY_ASSETS/OPENSSL-LICENSE"
cp "$ZLIB_LICENSE_SOURCE" "$NODE_THIRD_PARTY_ASSETS/ZLIB-LICENSE"

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
if readelf -Ws "$NATIVE_DIR/libagentcodi.so" \
    | grep -Eq 'Java_de_agentcodi_runtime_NativeEngine_native(Start|Read|Write|Resize|Poll|Stop)Terminal'; then
  echo "JNI library contains an obsolete same-UID terminal process path." >&2
  exit 1
fi
if ! readelf -d "$NATIVE_DIR/libagentcodi.so" | grep -q 'Shared library: \[libc++_shared.so\]'; then
  echo "Native engine did not declare its packaged C++ runtime dependency." >&2
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
if ! grep -Fq 'shell_environment_policy={inherit="none"' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'analytics.enabled=false' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'otel.exporter="none"' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'feedback.enabled=false' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing the closed environment or telemetry policy." >&2
  exit 1
fi
if ! grep -Fq 'generated_images' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'Generated image does not have the required PNG signature' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'Generated image could not be installed atomically in the workspace' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing validated workspace image materialization." >&2
  exit 1
fi
if ! grep -aFq "$CODEX_PACKAGED_HOST_NAME" "$NATIVE_DIR/libcodex.so" \
    || [ "$(grep -ao "$CODEX_PACKAGED_HOST_NAME" "$NATIVE_DIR/libcodex.so" | wc -l)" -ne 1 ]; then
  echo "Packaged app-server does not resolve the Android-native host sibling." >&2
  exit 1
fi
for packaged_executable in "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$NATIVE_DIR/$NODE_LIBRARY_NAME"; do
  if ! file "$packaged_executable" | grep -q 'ARM aarch64' \
      || ! readelf -l "$packaged_executable" | grep -q '/system/bin/linker64'; then
    echo "Packaged terminal executable is not an Android ARM64 binary: $packaged_executable" >&2
    exit 1
  fi
done
for dependency in libcares.so libcrypto_3.so libicudata_78.so libicui18n_78.so libicuuc_78.so libsqlite3.so libssl_3.so libz_1.so; do
  if ! file "$NATIVE_DIR/$dependency" | grep -q 'ARM aarch64'; then
    echo "Packaged Node.js dependency is not ARM64: $dependency" >&2
    exit 1
  fi
done
if readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
    | grep -Eq 'lib(z\.so\.1|crypto\.so\.3|ssl\.so\.3|icui18n\.so\.78|icuuc\.so\.78)' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libz_1.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libcrypto_3.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libssl_3.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libicui18n_78.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libicuuc_78.so]'; then
  echo "Packaged Node.js dependency relocation is incomplete." >&2
  exit 1
fi

TOOLCHAIN_SMOKE_ROOT="$WORK_DIR/toolchain-smoke"
TOOLCHAIN_SMOKE_WORKSPACE="$TOOLCHAIN_SMOKE_ROOT/workspace"
TOOLCHAIN_SMOKE_DIRECTORY="$TOOLCHAIN_SMOKE_WORKSPACE/toolchain"
TOOLCHAIN_SMOKE_TOOL_BIN="$TOOLCHAIN_SMOKE_ROOT/tool-bin"
TOOLCHAIN_SMOKE_HOME="$TOOLCHAIN_SMOKE_ROOT/home"
TOOLCHAIN_SMOKE_TEMP="$TOOLCHAIN_SMOKE_ROOT/temp"
mkdir -p "$TOOLCHAIN_SMOKE_WORKSPACE" "$TOOLCHAIN_SMOKE_DIRECTORY" "$TOOLCHAIN_SMOKE_TOOL_BIN" "$TOOLCHAIN_SMOKE_HOME" "$TOOLCHAIN_SMOKE_TEMP"
chmod 700 "$TOOLCHAIN_SMOKE_ROOT" "$TOOLCHAIN_SMOKE_WORKSPACE" "$TOOLCHAIN_SMOKE_DIRECTORY" "$TOOLCHAIN_SMOKE_TOOL_BIN" "$TOOLCHAIN_SMOKE_HOME" "$TOOLCHAIN_SMOKE_TEMP"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/node"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/agentcodi-toolchain"
toolchain_smoke() {
  env -i \
    HOME="$TOOLCHAIN_SMOKE_HOME" \
    TMPDIR="$TOOLCHAIN_SMOKE_TEMP" \
    TMP="$TOOLCHAIN_SMOKE_TEMP" \
    TEMP="$TOOLCHAIN_SMOKE_TEMP" \
    PATH="$TOOLCHAIN_SMOKE_TOOL_BIN:$NATIVE_DIR:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$TOOLCHAIN_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$TOOLCHAIN_SMOKE_DIRECTORY" \
    AGENTCODI_TOOL_BIN="$TOOLCHAIN_SMOKE_TOOL_BIN" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node" \
    "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$@"
}
toolchain_model_smoke() {
  env -i \
    HOME="$TOOLCHAIN_SMOKE_HOME" \
    TMPDIR="$TOOLCHAIN_SMOKE_TEMP" \
    TMP="$TOOLCHAIN_SMOKE_TEMP" \
    TEMP="$TOOLCHAIN_SMOKE_TEMP" \
    PATH="$TOOLCHAIN_SMOKE_TOOL_BIN:$NATIVE_DIR:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$TOOLCHAIN_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$TOOLCHAIN_SMOKE_DIRECTORY" \
    AGENTCODI_TOOL_BIN="$TOOLCHAIN_SMOKE_TOOL_BIN" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node" \
    /system/bin/sh -c "$1"
}
if ! toolchain_smoke --toolchain list | grep -Fq "node $NODE_VERSION — available, not enabled" \
    || ! toolchain_smoke --toolchain install node | grep -Fq "Enabled packaged Node.js $NODE_VERSION." \
    || [ "$(stat -c '%a' "$TOOLCHAIN_SMOKE_DIRECTORY/installed/node-$NODE_VERSION")" != "600" ] \
    || [ "$(toolchain_smoke -c 'node --version' | tr -d '\r')" != "v$NODE_VERSION" ] \
    || [ "$(toolchain_model_smoke 'command -v node; command -v agentcodi-toolchain; node --version; agentcodi-toolchain status' | tr -d '\r')" != "$TOOLCHAIN_SMOKE_TOOL_BIN/node
$TOOLCHAIN_SMOKE_TOOL_BIN/agentcodi-toolchain
v$NODE_VERSION
node $NODE_VERSION — enabled" ] \
    || ! toolchain_smoke --toolchain remove node | grep -Fq "Disabled Node.js $NODE_VERSION."; then
  echo "Packaged terminal shell and Node.js activation smoke test failed." >&2
  exit 1
fi
if find "$TOOLCHAIN_SMOKE_ROOT" -type f \( -name '*history*' -o -name '.ash_history' -o -name '.sh_history' \) -print -quit | grep -q .; then
  echo "Packaged terminal smoke test persisted a shell or Node.js history." >&2
  exit 1
fi
app_server_help="$(env LD_LIBRARY_PATH="$NATIVE_DIR" "$NATIVE_DIR/libcodex.so" app-server --help)"
if ! printf '%s\n' "$app_server_help" | grep -Fq 'Transport endpoint URL'; then
  echo "Android-adapted app-server did not pass its native startup smoke test." >&2
  exit 1
fi
CONFIG_SMOKE_HOME="$WORK_DIR/config-smoke-home"
CONFIG_SMOKE_CODEX_HOME="$WORK_DIR/config-smoke-codex-home"
CONFIG_SMOKE_WORKSPACE="$WORK_DIR/config-smoke-workspace"
CONFIG_SMOKE_TOOLCHAIN="$CONFIG_SMOKE_WORKSPACE/toolchain"
CONFIG_SMOKE_TOOL_BIN="$WORK_DIR/config-smoke-tool-bin"
CONFIG_SMOKE_TEMP="$WORK_DIR/config-smoke-temp"
mkdir -p "$CONFIG_SMOKE_HOME" "$CONFIG_SMOKE_CODEX_HOME" "$CONFIG_SMOKE_WORKSPACE" "$CONFIG_SMOKE_TOOLCHAIN" "$CONFIG_SMOKE_TOOL_BIN" "$CONFIG_SMOKE_TEMP"
chmod 700 "$CONFIG_SMOKE_HOME" "$CONFIG_SMOKE_CODEX_HOME" "$CONFIG_SMOKE_WORKSPACE" "$CONFIG_SMOKE_TOOLCHAIN" "$CONFIG_SMOKE_TOOL_BIN" "$CONFIG_SMOKE_TEMP"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/node"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/agentcodi-toolchain"
printf '%s\n' \
  'approval_policy="never"' \
  'shell_environment_policy={inherit="all"}' \
  '[analytics]' \
  'enabled=true' \
  > "$CONFIG_SMOKE_CODEX_HOME/config.toml"
chmod 600 "$CONFIG_SMOKE_CODEX_HOME/config.toml"
config_smoke_status=0
(
    printf '%s\n' "{\"method\":\"initialize\",\"id\":1,\"params\":{\"clientInfo\":{\"name\":\"agentcodi_android\",\"title\":\"AGENTCODI\",\"version\":\"$APP_VERSION\"},\"capabilities\":{\"experimentalApi\":true,\"optOutNotificationMethods\":[\"rawResponseItem/completed\",\"rawResponse/completed\"]}}}"
    sleep 15
  ) | timeout 60s env -i \
    HOME="$CONFIG_SMOKE_HOME" \
    CODEX_HOME="$CONFIG_SMOKE_CODEX_HOME" \
    TMPDIR="$CONFIG_SMOKE_TEMP" \
    TMP="$CONFIG_SMOKE_TEMP" \
    TEMP="$CONFIG_SMOKE_TEMP" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    PATH="$CONFIG_SMOKE_TOOL_BIN:$NATIVE_DIR:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$CONFIG_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$CONFIG_SMOKE_TOOLCHAIN" \
    AGENTCODI_TOOL_BIN="$CONFIG_SMOKE_TOOL_BIN" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node" \
    CODEX_SELF_EXE="$NATIVE_DIR/libcodex.so" \
    CODEX_CODE_MODE_HOST_PATH="$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" \
    "$NATIVE_DIR/libcodex.so" app-server --stdio --strict-config \
    -c 'cli_auth_credentials_store="file"' \
    -c 'approval_policy="on-request"' \
    -c "shell_environment_policy={inherit=\"none\",ignore_default_excludes=false,set={PATH=\"$CONFIG_SMOKE_TOOL_BIN:$NATIVE_DIR:/system/bin:/system/xbin\",SHELL=\"/system/bin/sh\",HOME=\"$CONFIG_SMOKE_HOME\",TMPDIR=\"$CONFIG_SMOKE_TEMP\",TMP=\"$CONFIG_SMOKE_TEMP\",TEMP=\"$CONFIG_SMOKE_TEMP\",LD_LIBRARY_PATH=\"$NATIVE_DIR\",HISTFILE=\"/dev/null\",NODE_REPL_HISTORY=\"/dev/null\",SSL_CERT_DIR=\"/system/etc/security/cacerts\",AGENTCODI_WORKSPACE=\"$CONFIG_SMOKE_WORKSPACE\",AGENTCODI_TOOLCHAIN=\"$CONFIG_SMOKE_TOOLCHAIN\",AGENTCODI_TOOL_BIN=\"$CONFIG_SMOKE_TOOL_BIN\",AGENTCODI_NODE_VERSION=\"$NODE_VERSION\",AGENTCODI_TOOLCHAIN_COMMAND=\"agentcodi-toolchain\",AGENTCODI_TOOLCHAIN_PACKAGES=\"node\"}}" \
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
    -c 'default_permissions="agentcodi-workspace"' \
    -c 'permissions.agentcodi-workspace.description="AGENTCODI private workspace"' \
    -c "permissions.agentcodi-workspace.filesystem={\":minimal\"=\"read\",\"$CONFIG_SMOKE_TOOL_BIN\"=\"read\",\":workspace_roots\"={\".\"=\"write\"}}" \
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
  "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
  -o "$BOOTSTRAP_SMOKE_BIN"
BOOTSTRAP_SMOKE_ROOT="$WORK_DIR/supervisor-bootstrap-smoke"
BOOTSTRAP_SMOKE_WORKSPACE="$BOOTSTRAP_SMOKE_ROOT/workspace"
BOOTSTRAP_SMOKE_TOOLCHAIN="$BOOTSTRAP_SMOKE_WORKSPACE/toolchain"
BOOTSTRAP_SMOKE_TOOL_BIN="$BOOTSTRAP_SMOKE_ROOT/tool-bin"
BOOTSTRAP_SMOKE_CODEX_HOME="$BOOTSTRAP_SMOKE_ROOT/codex-home"
BOOTSTRAP_SMOKE_HOME="$BOOTSTRAP_SMOKE_ROOT/home"
BOOTSTRAP_SMOKE_TEMP="$BOOTSTRAP_SMOKE_ROOT/temp"
mkdir -p "$BOOTSTRAP_SMOKE_WORKSPACE" "$BOOTSTRAP_SMOKE_TOOLCHAIN" "$BOOTSTRAP_SMOKE_TOOL_BIN" "$BOOTSTRAP_SMOKE_CODEX_HOME" "$BOOTSTRAP_SMOKE_HOME" "$BOOTSTRAP_SMOKE_TEMP"
chmod 700 "$BOOTSTRAP_SMOKE_ROOT" "$BOOTSTRAP_SMOKE_WORKSPACE" "$BOOTSTRAP_SMOKE_TOOLCHAIN" "$BOOTSTRAP_SMOKE_TOOL_BIN" "$BOOTSTRAP_SMOKE_CODEX_HOME" "$BOOTSTRAP_SMOKE_HOME" "$BOOTSTRAP_SMOKE_TEMP"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/node"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/agentcodi-toolchain"
printf '%s\n' \
  'approval_policy="never"' \
  'shell_environment_policy={inherit="all"}' \
  '[analytics]' \
  'enabled=true' \
  > "$BOOTSTRAP_SMOKE_CODEX_HOME/config.toml"
chmod 600 "$BOOTSTRAP_SMOKE_CODEX_HOME/config.toml"
if ! timeout 30s env -i \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    PATH="/system/bin:/system/xbin" \
    "$BOOTSTRAP_SMOKE_BIN" \
    "$NATIVE_DIR/libcodex.so" \
    "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" \
    "$NATIVE_DIR/$TERMINAL_SHELL_NAME" \
    "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
    "$BOOTSTRAP_SMOKE_WORKSPACE" \
    "$BOOTSTRAP_SMOKE_TOOLCHAIN" \
    "$BOOTSTRAP_SMOKE_TOOL_BIN" \
    "$BOOTSTRAP_SMOKE_CODEX_HOME" \
    "$BOOTSTRAP_SMOKE_HOME" \
    "$BOOTSTRAP_SMOKE_TEMP" \
    "$NATIVE_DIR"; then
  echo "Native supervisor failed the packaged app-server bootstrap sequence." >&2
  exit 1
fi
code_mode_host_help="$(env LD_LIBRARY_PATH="$NATIVE_DIR" "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" --help)"
if ! printf '%s\n' "$code_mode_host_help" | grep -Fq 'Transport endpoint:'; then
  echo "Packaged code-mode host did not pass its native startup smoke test." >&2
  exit 1
fi
for native_file in \
    "$NATIVE_DIR/libagentcodi.so" \
    "$NATIVE_DIR/libc++_shared.so" \
    "$NATIVE_DIR/libcodex.so" \
    "$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" \
    "$NATIVE_DIR/$TERMINAL_SHELL_NAME" \
    "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
    "$NATIVE_DIR/libcares.so" \
    "$NATIVE_DIR/libcrypto_3.so" \
    "$NATIVE_DIR/libicudata_78.so" \
    "$NATIVE_DIR/libicui18n_78.so" \
    "$NATIVE_DIR/libicuuc_78.so" \
    "$NATIVE_DIR/libsqlite3.so" \
    "$NATIVE_DIR/libssl_3.so" \
    "$NATIVE_DIR/libz_1.so"; do
  if ! readelf -lW "$native_file" | awk '$1 == "LOAD" { seen = 1; if ($NF != "0x4000") bad = 1 } END { exit (!seen || bad) }'; then
    echo "Native library is not compatible with 16 KiB Android pages: $native_file" >&2
    exit 1
  fi
done

echo "Creating DEX and APK..."
DEX_MODE="--debug"
if [ "$BUILD_VARIANT" = "release" ]; then
  DEX_MODE="--release"
fi
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 "$DEX_MODE" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$DEX_DIR" "$CORE_JAR" "$STORAGE_JAR" "$RUNTIME_JAR" "$APP_JAR"
cp "$DEX_DIR/classes.dex" "$ADDITIONS/classes.dex"

UNALIGNED_APK="$WORK_DIR/unaligned.apk"
ALIGNED_APK="$WORK_DIR/aligned.apk"
cp "$UNSIGNED_APK" "$UNALIGNED_APK"
(
  cd "$ADDITIONS"
  zip -q -9 -r "$UNALIGNED_APK" classes.dex lib assets
)
zipalign -f -p 4 "$UNALIGNED_APK" "$ALIGNED_APK"

if [ "$BUILD_VARIANT" = "debug" ]; then
  DEBUG_KEYSTORE="$CACHE_DIR/agentcodi-debug.keystore"
  if [ ! -f "$DEBUG_KEYSTORE" ]; then
    "$KEYTOOL" -genkeypair -noprompt -keystore "$DEBUG_KEYSTORE" -storepass android -keypass android -alias androiddebugkey -dname "CN=AGENTCODI Android Debug,O=AGENTCODI,C=DE" -keyalg RSA -keysize 2048 -validity 10000
  fi
  chmod 600 "$DEBUG_KEYSTORE"
  VERSIONED_APK="$OUTPUT_DIR/$APP_NAME-$APP_VERSION-$ABI-debug.apk"
  NAMED_APK="$OUTPUT_DIR/$APP_NAME-debug.apk"
  apksigner sign --min-sdk-version "$MIN_SDK" --ks "$DEBUG_KEYSTORE" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$VERSIONED_APK" "$ALIGNED_APK"
else
  VERSIONED_APK="$OUTPUT_DIR/$APP_NAME-$APP_VERSION-$ABI-release.apk"
  NAMED_APK="$OUTPUT_DIR/$APP_NAME-release.apk"
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
  if printf '%s\n' "$certificate_report" | grep -Fiq 'android debug'; then
    echo "Release APK must not use an Android debug certificate." >&2
    exit 1
  fi
elif ! printf '%s\n' "$certificate_report" | grep -Fq 'Signer #1 certificate DN: CN=AGENTCODI Android Debug, O=AGENTCODI, C=DE'; then
  echo "Debug APK was not signed by the local AGENTCODI test signer." >&2
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
grep -Fx "lib/$ABI/$NODE_LIBRARY_NAME" "$WORK_DIR/apk-entries.txt"
for dependency in libcares.so libcrypto_3.so libicudata_78.so libicui18n_78.so libicuuc_78.so libsqlite3.so libssl_3.so libz_1.so; do
  grep -Fx "lib/$ABI/$dependency" "$WORK_DIR/apk-entries.txt"
done
grep -Fx 'assets/third-party/codex/LICENSE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/codex/NOTICE' "$WORK_DIR/apk-entries.txt"
for license_file in NODE-LICENSE CARES-LICENSE ICU-LICENSE OPENSSL-LICENSE ZLIB-LICENSE; do
  grep -Fx "assets/third-party/node/$license_file" "$WORK_DIR/apk-entries.txt"
done
grep -Fx 'res/raw/third_party_notices.txt' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/xml/locales_config.xml' "$WORK_DIR/apk-entries.txt"
if grep -Fxq 'res/raw/agentcodi_apache_2_0.txt' "$WORK_DIR/apk-entries.txt"; then
  echo "APK unexpectedly contains a public license for AGENTCODI original work." >&2
  exit 1
fi
unzip -p "$VERSIONED_APK" resources.arsc | strings > "$WORK_DIR/resource-strings.txt"
grep -Fq 'Copyright 2026 Pascal (Mc Pasi)' "$WORK_DIR/resource-strings.txt"
grep -Fq 'All rights reserved' "$WORK_DIR/resource-strings.txt"
grep -Fq 'Alle Rechte vorbehalten' "$WORK_DIR/resource-strings.txt"
packaged_app_server_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/libcodex.so" | sha256sum | awk '{print $1}')"
packaged_code_mode_host_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/$CODEX_PACKAGED_HOST_NAME" | sha256sum | awk '{print $1}')"
if [ "$packaged_app_server_sha" != "$CODEX_APP_SERVER_ANDROID_SHA256" ] \
    || [ "$packaged_code_mode_host_sha" != "$CODEX_CODE_MODE_HOST_SHA256" ]; then
  echo "APK does not contain the reviewed Codex app-server/host pair." >&2
  exit 1
fi
for runtime_spec in \
    "$NODE_LIBRARY_NAME:$NODE_RUNTIME_SHA256" \
    "libcares.so:$CARES_RUNTIME_SHA256" \
    "libcrypto_3.so:$CRYPTO_RUNTIME_SHA256" \
    "libicudata_78.so:$ICUDATA_RUNTIME_SHA256" \
    "libicui18n_78.so:$ICUI18N_RUNTIME_SHA256" \
    "libicuuc_78.so:$ICUUC_RUNTIME_SHA256" \
    "libsqlite3.so:$SQLITE_RUNTIME_SHA256" \
    "libssl_3.so:$SSL_RUNTIME_SHA256" \
    "libz_1.so:$ZLIB_RUNTIME_SHA256"; do
  runtime_name="${runtime_spec%%:*}"
  expected_runtime_sha="${runtime_spec#*:}"
  packaged_runtime_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/$runtime_name" | sha256sum | awk '{print $1}')"
  if [ "$packaged_runtime_sha" != "$expected_runtime_sha" ]; then
    echo "APK contains an unexpected Node.js runtime file: $runtime_name" >&2
    exit 1
  fi
done
unzip -p "$VERSIONED_APK" classes.dex | strings > "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/MainActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/SettingsActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/TerminalActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/LicensesActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AppLanguage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AgentCodiApplication;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/UiLanguage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CrashReportFormatter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CredentialGuard;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/TerminalOutputBuffer;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/TerminalSessionSnapshot;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexTerminalSession;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/ToolchainCommand;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexSessionController;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexModelOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexReasoningOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexInteractiveRequest;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexApprovalDecision;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/InteractiveRequestDialog;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/AgentRuntimeService;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/RuntimeText;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeAppServerTransport;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/CrashDiagnostics;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/WorkspaceImageExporter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/WorkspaceFileExporter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/runtime/NativeEngine;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'libcodex.so' "$WORK_DIR/dex-strings.txt"
grep -Fq "$CODEX_PACKAGED_HOST_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq "$TERMINAL_SHELL_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq "$NODE_LIBRARY_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq "$NODE_VERSION" "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/CrashReportStore;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceImageFile;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceExportFile;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/WorkspaceArchive;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'android.intent.action.CREATE_DOCUMENT' "$WORK_DIR/dex-strings.txt"
grep -Fq 'image_export' "$WORK_DIR/dex-strings.txt"
grep -Fq 'workspace_file_choose' "$WORK_DIR/dex-strings.txt"
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
