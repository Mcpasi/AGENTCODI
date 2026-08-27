#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

APP_NAME="AGENTCODI"
APP_ID="de.agentcodi.app"
APP_VERSION="0.6.3"
VERSION_CODE="70"
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

CODEX_ANDROID_VERSION="0.148.1"
CODEX_ANDROID_URL="https://registry.npmjs.org/@mmmbuto/codex-cli-termux/-/codex-cli-termux-$CODEX_ANDROID_VERSION.tgz"
CODEX_ANDROID_SHA256="b68a6c6770752deb045db084a9637b8cf1647b996a57d454e599981b963c4092"
CODEX_TERMUX_SOURCE_TAG="v0.148.1"
CODEX_TERMUX_SOURCE_COMMIT="9d48c76abec320ae3724164d0177299b1acd31ca"
CODEX_UPSTREAM_SOURCE_TAG="rust-v0.148.0"
CODEX_UPSTREAM_SOURCE_COMMIT="3ba0f711642a888aec92a611a3f3b2211157ff89"
CODEX_APP_SERVER_SOURCE_SHA256="35c76bc8a75fc768ea44433bcc755be931a3d73215d8324a182020b57ff1aa49"
CODEX_CODE_MODE_HOST_SHA256="da7bc9b805dd069f9b4008cb749d0f192cfd83445ed6ba7202ffd5aa51c1f855"
CODEX_APP_SERVER_ANDROID_SHA256="9c74afbfa027b840228278f4483405f59dc03393185e6e3a52fbc7ca64b921b9"
CODEX_LICENSE_SHA256="d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc"
CODEX_NOTICE_SHA256="8228749dd4dd6026baed0442f80e911308430478449285c865b188d97e6a013c"
CODEX_SCHEMA_BUNDLE_SHA256="819fe7b47288cc74da5190743390c8d1faef403f5401a1868b306dac195b1944"
CODEX_V2_SCHEMA_BUNDLE_SHA256="e5a20eb7211c21540a2d4e0106479285e13778e9c53d5837cfc735a71316a51e"
CODEX_DEFAULT_HOST_NAME="codex-code-mode-host"
CODEX_PACKAGED_HOST_NAME="libcodex-codehost.so"
CODEX_DEFAULT_HOST_OFFSET="10731233"

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
NODE_UNGUARDED_RUNTIME_SHA256="e31cd5c7f5db279d638c3ad773e04f12842077f0559f4da4f369440a6f4195c3"
NODE_PREATTESTED_RUNTIME_SHA256="cbf6b5c9aade3efd2127cb610db4a9ab8d54860c26d2c1273f8e3fae0bd6719f"
NODE_RUNTIME_SHA256="6d1e83f6dd9586adaee78d17f6bac23870af6a21ccad58779bac270cc318614c"
CARES_RUNTIME_SHA256="68733ce8d4bb1bdc87d8ec550c58c70f3dcdb0f8c48d86b83235f536cb736e83"
CRYPTO_RUNTIME_SHA256="c20e21eb916f6f913aef6291af7312dd2b2c46aa60000db9c24de55c8492a0a4"
ICUDATA_RUNTIME_SHA256="3d0d02951e9bdbb32fc36e2761fbcd0d144c7ad1fca78e5b1c4c117066e892a6"
ICUI18N_RUNTIME_SHA256="ff60f64a9916536aa3e505ff519dfd72f3b491eb5c1d98a5d96a89a37f3202f7"
ICUUC_RUNTIME_SHA256="0561115e4c843c8967981e64d802150e68f6b3c9ed67241228bf276844670ae3"
SQLITE_RUNTIME_SHA256="ab224ec9350f2e9ea7cf6f8321636979dea1ef9e8461453433857a6b701b4c7a"
SSL_RUNTIME_SHA256="3d224f5c06e04351ed7e25d7fb6078ee8ce832106f1ef0d83fffa77d5e744234"
ZLIB_RUNTIME_SHA256="fc9659e5d77c32149627ef3c357a1a76cfd44b93917e29c6c1c78cb054f92b83"

NPM_VERSION="11.19.0"
NPM_URL="https://packages.termux.dev/apt/termux-main/pool/main/n/npm/npm_${NPM_VERSION}_all.deb"
NPM_SHA256="385a051111f66c56d0564e6809244f1740427805a78d2e5a5dc470fb420832f8"
PYTHON_VERSION="3.14.6"
PYTHON_PACKAGE_VERSION="3.14.6-1"
PYTHON_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/python/python_${PYTHON_PACKAGE_VERSION}_aarch64.deb"
PYTHON_SHA256="3166e56c2b6c03fff41191fbb9d736302978e7c484702814d9f6dc99dd6006bd"
ANDROID_POSIX_SEMAPHORE_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-posix-semaphore/libandroid-posix-semaphore_0.1-4_aarch64.deb"
ANDROID_POSIX_SEMAPHORE_SHA256="0efa8677a0166315ba4e685863712eba0ca0a1732827492f38226e2723730c7a"
ANDROID_SUPPORT_URL="https://packages.termux.dev/apt/termux-main/pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb"
ANDROID_SUPPORT_SHA256="f2f145d6135ad4843ac9670153be3e3944dc1e6f1736d46d2306c28f2b86f517"
BZIP2_URL="https://packages.termux.dev/apt/termux-main/pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb"
BZIP2_SHA256="4335d7f060650b0aabef545d1334c2f9f280223d5962e13c24a00ec934b794ba"
LIBFFI_URL="https://packages.termux.dev/apt/termux-main/pool/main/libf/libffi/libffi_3.5.2_aarch64.deb"
LIBFFI_SHA256="8c8c1d6ffb049d8496a21c1202d9b4dc9145140886fdbb45716684565f4ed3f5"
LIBLZMA_URL="https://packages.termux.dev/apt/termux-main/pool/main/libl/liblzma/liblzma_5.8.3_aarch64.deb"
LIBLZMA_SHA256="594925a313879f590fbd24050305551a78eadd9a9319f6e612389b1a521113c6"
NCURSES_VERSION="6.6.20260307+really6.5.20250830"
NCURSES_URL="https://packages.termux.dev/apt/termux-main/pool/main/n/ncurses/ncurses_${NCURSES_VERSION}_aarch64.deb"
NCURSES_SHA256="f44bbfdc3d42ec0217bffa978309390e59cea5a48a9a83226d4a496c42ad0b99"
NCURSES_UI_URL="https://packages.termux.dev/apt/termux-main/pool/main/n/ncurses-ui-libs/ncurses-ui-libs_${NCURSES_VERSION}_aarch64.deb"
NCURSES_UI_SHA256="7393f369009be189b3d4ec1f9b16ebd57621d6a1b22949ae07685573950d1f37"
ZSTD_VERSION="1.5.7"
ZSTD_URL="https://packages.termux.dev/apt/termux-main/pool/main/z/zstd/zstd_${ZSTD_VERSION}-1_aarch64.deb"
ZSTD_SHA256="e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3"
ZSTD_LICENSE_URL="https://raw.githubusercontent.com/facebook/zstd/v${ZSTD_VERSION}/LICENSE"
ZSTD_LICENSE_SHA256="7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8"
LIBLZMA_0BSD_LICENSE_SHA256="0b01625d853911cd0e2e088dcfb743261034a091bb379246cb25a14cc4c74bf1"
TERMUX_LICENSES_URL="https://packages.termux.dev/apt/termux-main/pool/main/t/termux-licenses/termux-licenses_2.2_all.deb"
TERMUX_LICENSES_SHA256="a3265cd1cf7d04754f2fb683eaf5b21918263792fd714457127900c1d6d9bcd9"
PYTHON_LIBRARY_NAME="libpython-bin.so"
TOOL_RUNTIME_NAME="python-${PYTHON_VERSION}-npm-${NPM_VERSION}"
TOOL_RUNTIME_MANIFEST_SHA256="e7fa5752f739c96cde42b20b38ae57d7249eb535b1524b5fc5a7be73d1008e7a"
PYTHON_NATIVE_SET_SHA256="cc9e6ea0d0ad967979d8b2763fd32a9a328d589c20401e64035e573877cb2581"
PYTHON_LICENSES_SHA256="b25c84cf10f0797356b67dd6b27d5a2cdff1c2a2bc098b2ee678c60146392892"
PYTHON_SOURCE_EXTENSION_COUNT="75"
PYTHON_PACKAGED_EXTENSION_COUNT="72"

RIPGREP_VERSION="15.2.0"
RIPGREP_LIBRARY_NAME="libripgrep.so"
RIPGREP_SOURCE_BINARY="$PROJECT_ROOT/third_party/ripgrep/ripgrep-15.2.0-android-arm64.elf"
RIPGREP_SOURCE_SHA256="4eb0d0c70d2e3c760cab4f478c7eb715082ae1d8b5f4a23bb14515154348b04d"
RIPGREP_PREATTESTED_RUNTIME_SHA256="a93343b21a76f7ff00dc05c6eddc6317d36f143093e3f7cde795720adede00aa"
RIPGREP_RUNTIME_SHA256="4cfd048c4bac29ac0d494887b519752984f66a449ed4b22bd95cca6fcf540d50"
RIPGREP_DEPENDENCIES_SOURCE="$PROJECT_ROOT/third_party/ripgrep/DEPENDENCIES"
RIPGREP_DEPENDENCIES_SHA256="78cc70f642f1bf1d4c0ecc42a835fbf2c9e3658378f4d9e536ab6dee80a12281"
RIPGREP_LICENSES_SOURCE="$PROJECT_ROOT/third_party/ripgrep/LICENSES"
RIPGREP_LICENSES_SHA256="43ba0c48735498436470bc5ceddbd1286b694b17235f6f571b14dc3bfc43d678"
RIPGREP_PROVENANCE_SOURCE="$PROJECT_ROOT/third_party/ripgrep/PROVENANCE"
RIPGREP_PROVENANCE_SHA256="96c1ff96d2ecab8a17f0186771aeeefa2c9c5567ee7ce00bd3c6cb4ef74f3848"
NODE_GUARD_LIBRARY_NAME="libagentcodi-node-guard.so"
PYTHON_GUARD_LIBRARY_NAME="libagentcodi-python-guard.so"
RIPGREP_GUARD_LIBRARY_NAME="libagentcodi-ripgrep-guard.so"
NODE_GUARD_SHA256="92d7e6740a494c687383c83c1109f133b04ac67d0ac6a0714c6c7e26a5c3e1a7"
PYTHON_GUARD_SHA256="ab8ab4014503943c14842e79507cb5975b270f4fb97cec3c3fe423ad1fe71814"
RIPGREP_GUARD_SHA256="6f38c49ad156e456248330bfddec2dc3f934f94884cd64d1fd751c08fee40a20"
NODE_ATTESTOR_SHA256="241c3c157251f94d682da6bad6082079786198d241f63be76a456c8c64f16dfa"
PYTHON_ATTESTOR_SHA256="7e275cc1b169871b100a15f82af1395f384b507234549241ad14c98a94cb762c"
RIPGREP_ATTESTOR_SHA256="206e3f43a6dd1cfa1b81cc901e86be00d19c1584866f864da9ff94e6defcba99"
PATCHELF_VERSION="0.19.1"
PATCHELF_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/patchelf/patchelf_${PATCHELF_VERSION}_aarch64.deb"
PATCHELF_SHA256="a08bea49b3c9c3bf449ee0c7b7ee9c97a9f3ab84ae06ace08a564d0903a23c3f"

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
LLVM_OBJCOPY="${AGENTCODI_LLVM_OBJCOPY:-$TERMUX_PREFIX/bin/llvm-objcopy}"
CACHE_DIR="${AGENTCODI_CACHE_DIR:-$PROJECT_ROOT/.cache/android}"
OUTPUT_DIR="${AGENTCODI_OUTPUT_DIR:-$PROJECT_ROOT/output/apk}"
BUILD_ROOT="$PROJECT_ROOT/.build"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required build command: $1" >&2
    exit 1
  fi
}

for command_name in apksigner awk cmp curl dd dpkg-deb file grep readelf realpath rg script sed sha256sum stat strings tar timeout tr unzip wc xargs zip zipalign zipinfo; do
  require_command "$command_name"
done
for executable in \
    "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL" "$CLANGXX" "$LLVM_STRIP" \
    "$LD_LLD" "$LLVM_OBJCOPY"; do
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

patch_elf_name_all() {
  local file="$1"
  local old_name="$2"
  local new_name="$3"
  local count
  count="$(grep -aoF "$old_name" "$file" | wc -l || true)"
  if [ "$count" -gt 0 ]; then
    patch_elf_name "$file" "$old_name" "$new_name" "$count"
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
NPM_ARCHIVE="$CACHE_DIR/npm-$NPM_VERSION-all.deb"
PYTHON_ARCHIVE="$CACHE_DIR/python-$PYTHON_PACKAGE_VERSION-aarch64.deb"
ANDROID_POSIX_SEMAPHORE_ARCHIVE="$CACHE_DIR/libandroid-posix-semaphore-0.1-4-aarch64.deb"
ANDROID_SUPPORT_ARCHIVE="$CACHE_DIR/libandroid-support-29-1-aarch64.deb"
BZIP2_ARCHIVE="$CACHE_DIR/libbz2-1.0.8-8-aarch64.deb"
LIBFFI_ARCHIVE="$CACHE_DIR/libffi-3.5.2-aarch64.deb"
LIBLZMA_ARCHIVE="$CACHE_DIR/liblzma-5.8.3-aarch64.deb"
NCURSES_ARCHIVE="$CACHE_DIR/ncurses-$NCURSES_VERSION-aarch64.deb"
NCURSES_UI_ARCHIVE="$CACHE_DIR/ncurses-ui-libs-$NCURSES_VERSION-aarch64.deb"
ZSTD_ARCHIVE="$CACHE_DIR/zstd-$ZSTD_VERSION-1-aarch64.deb"
TERMUX_LICENSES_ARCHIVE="$CACHE_DIR/termux-licenses-2.2-all.deb"
PATCHELF_ARCHIVE="$CACHE_DIR/patchelf-$PATCHELF_VERSION-aarch64.deb"
NODE_LICENSE_FILE="$CACHE_DIR/node-$NODE_VERSION-LICENSE"
ICU_LICENSE_FILE="$CACHE_DIR/icu-$ICU_VERSION-LICENSE"
OPENSSL_LICENSE_FILE="$CACHE_DIR/openssl-$OPENSSL_VERSION-LICENSE"
ZSTD_LICENSE_FILE="$CACHE_DIR/zstd-$ZSTD_VERSION-LICENSE"

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
download_verified "$NPM_URL" "$NPM_SHA256" "$NPM_ARCHIVE"
download_verified "$PYTHON_URL" "$PYTHON_SHA256" "$PYTHON_ARCHIVE"
download_verified "$ANDROID_POSIX_SEMAPHORE_URL" "$ANDROID_POSIX_SEMAPHORE_SHA256" "$ANDROID_POSIX_SEMAPHORE_ARCHIVE"
download_verified "$ANDROID_SUPPORT_URL" "$ANDROID_SUPPORT_SHA256" "$ANDROID_SUPPORT_ARCHIVE"
download_verified "$BZIP2_URL" "$BZIP2_SHA256" "$BZIP2_ARCHIVE"
download_verified "$LIBFFI_URL" "$LIBFFI_SHA256" "$LIBFFI_ARCHIVE"
download_verified "$LIBLZMA_URL" "$LIBLZMA_SHA256" "$LIBLZMA_ARCHIVE"
download_verified "$NCURSES_URL" "$NCURSES_SHA256" "$NCURSES_ARCHIVE"
download_verified "$NCURSES_UI_URL" "$NCURSES_UI_SHA256" "$NCURSES_UI_ARCHIVE"
download_verified "$ZSTD_URL" "$ZSTD_SHA256" "$ZSTD_ARCHIVE"
download_verified "$TERMUX_LICENSES_URL" "$TERMUX_LICENSES_SHA256" "$TERMUX_LICENSES_ARCHIVE"
download_verified "$PATCHELF_URL" "$PATCHELF_SHA256" "$PATCHELF_ARCHIVE"
download_verified "$NODE_LICENSE_URL" "$NODE_LICENSE_SHA256" "$NODE_LICENSE_FILE"
download_verified "$ICU_LICENSE_URL" "$ICU_LICENSE_SHA256" "$ICU_LICENSE_FILE"
download_verified "$OPENSSL_LICENSE_URL" "$OPENSSL_LICENSE_SHA256" "$OPENSSL_LICENSE_FILE"
download_verified "$ZSTD_LICENSE_URL" "$ZSTD_LICENSE_SHA256" "$ZSTD_LICENSE_FILE"

for ripgrep_input in \
    "$RIPGREP_SOURCE_BINARY" \
    "$RIPGREP_DEPENDENCIES_SOURCE" \
    "$RIPGREP_LICENSES_SOURCE" \
    "$RIPGREP_PROVENANCE_SOURCE"; do
  if [ ! -f "$ripgrep_input" ] || [ -L "$ripgrep_input" ] \
      || [ "$(stat -c '%h' "$ripgrep_input")" -ne 1 ]; then
    echo "Pinned ripgrep input is missing or has unsafe metadata: $ripgrep_input" >&2
    exit 1
  fi
done
verify_file_sha256 "$RIPGREP_SOURCE_BINARY" "$RIPGREP_SOURCE_SHA256"
verify_file_sha256 "$RIPGREP_DEPENDENCIES_SOURCE" "$RIPGREP_DEPENDENCIES_SHA256"
verify_file_sha256 "$RIPGREP_LICENSES_SOURCE" "$RIPGREP_LICENSES_SHA256"
verify_file_sha256 "$RIPGREP_PROVENANCE_SOURCE" "$RIPGREP_PROVENANCE_SHA256"

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
CODEX_SCHEMA_DIR="$WORK_DIR/codex-schema"
CODEX_SCHEMA_HOME="$WORK_DIR/codex-schema-home"
CODEX_SCHEMA_TMP="$WORK_DIR/codex-schema-tmp"
THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/codex"
NODE_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/node"
NPM_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/npm"
PYTHON_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/python"
RIPGREP_THIRD_PARTY_ASSETS="$ADDITIONS/assets/third-party/ripgrep"
TOOL_RUNTIME_ASSETS="$ADDITIONS/assets/third-party/toolchain"
TOOL_RUNTIME_STAGE="$WORK_DIR/tool-runtime-stage"
TOOL_RUNTIME_MANIFEST="$TOOL_RUNTIME_ASSETS/RUNTIME-MANIFEST"
TOOL_RUNTIME_ARCHIVE="$TOOL_RUNTIME_ASSETS/RUNTIME.zip"
mkdir -p "$EXTRACT_DIR" "$AAPT2_EXTRACT" "$GENERATED_JAVA" "$CLASSES_ROOT" "$JARS_ROOT" "$DEX_DIR" "$NATIVE_DIR" "$CODEX_EXTRACT" "$THIRD_PARTY_ASSETS" "$NODE_THIRD_PARTY_ASSETS" "$NPM_THIRD_PARTY_ASSETS" "$PYTHON_THIRD_PARTY_ASSETS" "$RIPGREP_THIRD_PARTY_ASSETS" "$TOOL_RUNTIME_ASSETS" "$TOOL_RUNTIME_STAGE"
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

for archive in "$AAPT2_ARCHIVE" "$ABSEIL_ARCHIVE" "$PROTOBUF_ARCHIVE" "$FMT_ARCHIVE" "$LIBCXX_ARCHIVE" "$EXPAT_ARCHIVE" "$PNG_ARCHIVE" "$ZOPFLI_ARCHIVE" "$ZLIB_ARCHIVE" "$NODE_ARCHIVE" "$CARES_ARCHIVE" "$ICU_ARCHIVE" "$SQLITE_ARCHIVE" "$OPENSSL_ARCHIVE" "$NPM_ARCHIVE" "$PYTHON_ARCHIVE" "$ANDROID_POSIX_SEMAPHORE_ARCHIVE" "$ANDROID_SUPPORT_ARCHIVE" "$BZIP2_ARCHIVE" "$LIBFFI_ARCHIVE" "$LIBLZMA_ARCHIVE" "$NCURSES_ARCHIVE" "$NCURSES_UI_ARCHIVE" "$ZSTD_ARCHIVE" "$TERMUX_LICENSES_ARCHIVE" "$PATCHELF_ARCHIVE"; do
  dpkg-deb -x "$archive" "$AAPT2_EXTRACT"
done
tar -xzf "$CODEX_ANDROID_ARCHIVE" -C "$CODEX_EXTRACT"
AAPT2_BIN="$AAPT2_EXTRACT/data/data/com.termux/files/usr/bin/aapt2"
AAPT2_LIBRARY_PATH="$AAPT2_EXTRACT/data/data/com.termux/files/usr/lib"
PATCHELF_BIN="$AAPT2_EXTRACT/data/data/com.termux/files/usr/bin/patchelf"
if [ ! -x "$AAPT2_BIN" ]; then
  echo "Pinned aapt2 package did not contain an executable." >&2
  exit 1
fi
if [ ! -x "$PATCHELF_BIN" ] \
    || ! env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$PATCHELF_BIN" --version \
      | grep -Fq "patchelf $PATCHELF_VERSION"; then
  echo "Pinned build-only patchelf package is missing or invalid." >&2
  exit 1
fi
LIBCXX_SHARED="$AAPT2_LIBRARY_PATH/libc++_shared.so"
CODEX_SOURCE_BINARY="$CODEX_EXTRACT/package/bin/codex.bin"
CODEX_BINARY="$WORK_DIR/codex-app-server-android"
CODEX_CODE_MODE_HOST_BINARY="$CODEX_EXTRACT/package/bin/codex-code-mode-host"
CODEX_LICENSE="$CODEX_EXTRACT/package/LICENSE"
CODEX_NOTICE="$CODEX_EXTRACT/package/NOTICE"
CODEX_PACKAGE_JSON="$CODEX_EXTRACT/package/package.json"
CODEX_PACKAGE_README="$CODEX_EXTRACT/package/README.md"
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
NPM_SOURCE_DIRECTORY="$TERMUX_RUNTIME_PREFIX/lib/node_modules/npm"
PYTHON_SOURCE_BINARY="$TERMUX_RUNTIME_PREFIX/bin/python3.14"
PYTHON_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libpython3.14.so"
PYTHON_STDLIB_SOURCE="$TERMUX_RUNTIME_PREFIX/lib/python3.14"
PYTHON_DYNLOAD_SOURCE="$PYTHON_STDLIB_SOURCE/lib-dynload"
ANDROID_POSIX_SEMAPHORE_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libandroid-posix-semaphore.so"
ANDROID_SUPPORT_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libandroid-support.so"
BZIP2_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libbz2.so.1.0.8"
EXPAT_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libexpat.so.1.12.2"
LIBFFI_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libffi.so"
LIBLZMA_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/liblzma.so.5.8.3"
LIBLZMA_LICENSE_SUMMARY_SOURCE="$TERMUX_RUNTIME_PREFIX/share/doc/liblzma/COPYING"
LIBLZMA_0BSD_LICENSE_SOURCE="$TERMUX_RUNTIME_PREFIX/share/doc/xz/COPYING.0BSD"
NCURSESW_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libncursesw.so.6.5"
PANELW_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libpanelw.so.6.5"
ZSTD_SOURCE_LIBRARY="$TERMUX_RUNTIME_PREFIX/lib/libzstd.so.1.5.7"
if [ ! -f "$LIBCXX_SHARED" ] || ! file "$LIBCXX_SHARED" | grep -q 'ARM aarch64'; then
  echo "Pinned libc++ runtime is missing or not ARM64." >&2
  exit 1
fi
for codex_file in "$CODEX_SOURCE_BINARY" "$CODEX_CODE_MODE_HOST_BINARY" "$CODEX_LICENSE" "$CODEX_NOTICE" "$CODEX_PACKAGE_JSON" "$CODEX_PACKAGE_README"; do
  if [ ! -f "$codex_file" ]; then
    echo "Pinned Codex archive is missing: $codex_file" >&2
    exit 1
  fi
done
for tool_file in "$NPM_SOURCE_DIRECTORY/bin/npm-cli.js" "$PYTHON_SOURCE_BINARY" "$PYTHON_SOURCE_LIBRARY" "$ANDROID_POSIX_SEMAPHORE_SOURCE_LIBRARY" "$ANDROID_SUPPORT_SOURCE_LIBRARY" "$BZIP2_SOURCE_LIBRARY" "$EXPAT_SOURCE_LIBRARY" "$LIBFFI_SOURCE_LIBRARY" "$LIBLZMA_SOURCE_LIBRARY" "$LIBLZMA_LICENSE_SUMMARY_SOURCE" "$LIBLZMA_0BSD_LICENSE_SOURCE" "$NCURSESW_SOURCE_LIBRARY" "$PANELW_SOURCE_LIBRARY" "$ZSTD_SOURCE_LIBRARY"; do
  if [ ! -f "$tool_file" ]; then
    echo "Pinned npm/Python runtime packages are missing: $tool_file" >&2
    exit 1
  fi
done
if [ ! -d "$PYTHON_DYNLOAD_SOURCE" ] \
    || [ "$(find "$PYTHON_DYNLOAD_SOURCE" -type f -name '*.so' | wc -l)" -ne "$PYTHON_SOURCE_EXTENSION_COUNT" ]; then
  echo "Pinned Python package does not contain the reviewed extension-module set." >&2
  exit 1
fi
for excluded_python_extension in \
    _dbm.cpython-314-aarch64-linux-android.so \
    _gdbm.cpython-314-aarch64-linux-android.so \
    readline.cpython-314-aarch64-linux-android.so; do
  if [ ! -f "$PYTHON_DYNLOAD_SOURCE/$excluded_python_extension" ]; then
    echo "Pinned Python package is missing a reviewed optional extension: $excluded_python_extension" >&2
    exit 1
  fi
done
verify_file_sha256 "$LIBLZMA_0BSD_LICENSE_SOURCE" "$LIBLZMA_0BSD_LICENSE_SHA256"
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
verify_file_sha256 "$CODEX_LICENSE" "$CODEX_LICENSE_SHA256"
verify_file_sha256 "$CODEX_NOTICE" "$CODEX_NOTICE_SHA256"
if ! grep -Fq "\"version\": \"$CODEX_ANDROID_VERSION\"" "$CODEX_PACKAGE_JSON" \
    || ! grep -Fq "upstream $CODEX_UPSTREAM_SOURCE_TAG" "$CODEX_PACKAGE_JSON" \
    || ! grep -Fq "built from upstream OpenAI Codex \`$CODEX_UPSTREAM_SOURCE_TAG\`" "$CODEX_PACKAGE_README"; then
  echo "Pinned Codex package metadata does not match the reviewed runtime/source tag." >&2
  exit 1
fi
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

echo "Compiling the pinned Python standard library to bytecode..."
PYTHON_COMPILE_HOME="$WORK_DIR/python-compile-home"
mkdir -p "$PYTHON_COMPILE_HOME"
chmod 700 "$PYTHON_COMPILE_HOME"
env -i \
  HOME="$PYTHON_COMPILE_HOME" \
  TMPDIR="$PYTHON_COMPILE_HOME" \
  LD_LIBRARY_PATH="$TERMUX_RUNTIME_PREFIX/lib" \
  PYTHONHOME="$TERMUX_RUNTIME_PREFIX" \
  PYTHONNOUSERSITE=1 \
  PYTHONUTF8=1 \
  "$PYTHON_SOURCE_BINARY" -m compileall -b -f -q -j 1 \
    -s "$PYTHON_STDLIB_SOURCE" -p /usr/lib/python3.14 \
    "$PYTHON_STDLIB_SOURCE"
if [ ! -f "$PYTHON_STDLIB_SOURCE/encodings/__init__.pyc" ]; then
  echo "Pinned Python standard library did not compile to legacy-path bytecode." >&2
  exit 1
fi
mkdir -p "$TOOL_RUNTIME_STAGE/npm/node_modules" "$TOOL_RUNTIME_STAGE/python/lib"
cp -R "$NPM_SOURCE_DIRECTORY" "$TOOL_RUNTIME_STAGE/npm/node_modules/npm"
cp -R "$PYTHON_STDLIB_SOURCE" "$TOOL_RUNTIME_STAGE/python/lib/python3.14"
find "$TOOL_RUNTIME_STAGE/python" -type f \( -name '*.py' -o -name '*.pyi' -o -name '*.so' \) -delete
find "$TOOL_RUNTIME_STAGE/python" -type d -name '__pycache__' -prune -exec rm -rf -- {} +
if find "$TOOL_RUNTIME_STAGE" -type l -print -quit | grep -q .; then
  echo "Packaged npm/Python data archive unexpectedly contains a symbolic link." >&2
  exit 1
fi

echo "Compiling Android resources..."
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" compile --dir "$PROJECT_ROOT/app/src/main/res" -o "$COMPILED_RESOURCES"

UNSIGNED_APK="$WORK_DIR/unsigned.apk"
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$AAPT2_BIN" link -o "$UNSIGNED_APK" --manifest "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" --java "$GENERATED_JAVA" --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" --version-code "$VERSION_CODE" --version-name "$APP_VERSION" -I "$ANDROID_JAR" "$COMPILED_RESOURCES"

echo "Compiling isolated Java modules..."
CORE_CLASSES="$CLASSES_ROOT/core"
REVIEW_MODE_CLASSES="$CLASSES_ROOT/review-mode"
PROTECTED_MODE_CLASSES="$CLASSES_ROOT/protected-mode"
COMPATIBILITY_MODE_CLASSES="$CLASSES_ROOT/compatibility-mode"
STORAGE_CLASSES="$CLASSES_ROOT/storage"
FILE_BROWSER_CONTRACTS_CLASSES="$CLASSES_ROOT/file-browser-contracts"
FILE_BROWSER_CLIENT_CLASSES="$CLASSES_ROOT/file-browser-client"
IMPORT_CONTRACTS_CLASSES="$CLASSES_ROOT/import-contracts"
IMPORT_CLIENT_CLASSES="$CLASSES_ROOT/import-client"
MCP_CONTRACTS_CLASSES="$CLASSES_ROOT/mcp-contracts"
MCP_CLIENT_CLASSES="$CLASSES_ROOT/mcp-client"
RUNTIME_CLASSES="$CLASSES_ROOT/runtime"
APP_CLASSES="$CLASSES_ROOT/app"
mkdir -p \
  "$CORE_CLASSES" \
  "$REVIEW_MODE_CLASSES" \
  "$PROTECTED_MODE_CLASSES" \
  "$COMPATIBILITY_MODE_CLASSES" \
  "$STORAGE_CLASSES" \
  "$FILE_BROWSER_CONTRACTS_CLASSES" \
  "$FILE_BROWSER_CLIENT_CLASSES" \
  "$IMPORT_CONTRACTS_CLASSES" \
  "$IMPORT_CLIENT_CLASSES" \
  "$MCP_CONTRACTS_CLASSES" \
  "$MCP_CLIENT_CLASSES" \
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

find "$PROJECT_ROOT/modules/protected-mode/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/protected-mode-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR" -d "$PROTECTED_MODE_CLASSES" @"$WORK_DIR/protected-mode-sources.txt"
PROTECTED_MODE_JAR="$JARS_ROOT/protected-mode.jar"
"$JAR" cf "$PROTECTED_MODE_JAR" -C "$PROTECTED_MODE_CLASSES" .

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

find "$PROJECT_ROOT/modules/runtime/src/main/java" -type f -name '*.java' -print | sort > "$WORK_DIR/runtime-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$REVIEW_MODE_JAR:$PROTECTED_MODE_JAR:$COMPATIBILITY_MODE_JAR:$STORAGE_JAR:$FILE_BROWSER_CONTRACTS_JAR:$FILE_BROWSER_CLIENT_JAR:$IMPORT_CONTRACTS_JAR:$IMPORT_CLIENT_JAR:$MCP_CONTRACTS_JAR:$MCP_CLIENT_JAR" -d "$RUNTIME_CLASSES" @"$WORK_DIR/runtime-sources.txt"
RUNTIME_JAR="$JARS_ROOT/runtime.jar"
"$JAR" cf "$RUNTIME_JAR" -C "$RUNTIME_CLASSES" .

find "$PROJECT_ROOT/app/src/main/java" "$GENERATED_JAVA" -type f -name '*.java' -print | sort > "$WORK_DIR/app-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR" -classpath "$CORE_JAR:$REVIEW_MODE_JAR:$PROTECTED_MODE_JAR:$COMPATIBILITY_MODE_JAR:$STORAGE_JAR:$FILE_BROWSER_CONTRACTS_JAR:$IMPORT_CONTRACTS_JAR:$MCP_CONTRACTS_JAR:$MCP_CLIENT_JAR:$RUNTIME_JAR" -d "$APP_CLASSES" @"$WORK_DIR/app-sources.txt"
APP_JAR="$JARS_ROOT/app.jar"
"$JAR" cf "$APP_JAR" -C "$APP_CLASSES" .

echo "Compiling ARM64 JNI engine..."
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -shared -fPIC -std=c++17 -O2 -Wall -Wextra -Werror -pthread -fvisibility=hidden -I"$JAVA_HOME_17/include" -I"$JAVA_HOME_17/include/linux" -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/agentcodi_engine.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/png_validator.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/sha256.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_directory_reader.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_file_reader.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_import_installer.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/jni_bridge.cpp" -Wl,-soname,libagentcodi.so -lz -llog -o "$NATIVE_DIR/libagentcodi.so"
"$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/libagentcodi.so"

echo "Compiling packaged terminal shell bridge..."
"$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -fPIE -pie -std=c++17 -O2 -Wall -Wextra -Werror -pthread -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_shell_main.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_policy.cpp" "$PROJECT_ROOT/modules/native-engine/src/main/cpp/ripgrep_bridge_policy.cpp" -o "$NATIVE_DIR/$TERMINAL_SHELL_NAME"
"$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/$TERMINAL_SHELL_NAME"

echo "Compiling packaged direct-ELF policy guards..."
for guard_spec in \
    "1:$NODE_GUARD_LIBRARY_NAME" \
    "2:$PYTHON_GUARD_LIBRARY_NAME" \
    "3:$RIPGREP_GUARD_LIBRARY_NAME"; do
  guard_kind="${guard_spec%%:*}"
  guard_library="${guard_spec#*:}"
  "$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" \
    -shared -fPIC -std=c++17 -O2 -Wall -Wextra -Werror \
    -fvisibility=hidden -DAGENTCODI_GUARDED_TOOL="$guard_kind" \
    -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_guard.cpp" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_policy.cpp" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/ripgrep_bridge_policy.cpp" \
    -Wl,-soname,"$guard_library" \
    -o "$NATIVE_DIR/$guard_library"
  "$LLVM_STRIP" --strip-unneeded "$NATIVE_DIR/$guard_library"
done

verify_file_sha256 "$NATIVE_DIR/$NODE_GUARD_LIBRARY_NAME" "$NODE_GUARD_SHA256"
verify_file_sha256 "$NATIVE_DIR/$PYTHON_GUARD_LIBRARY_NAME" "$PYTHON_GUARD_SHA256"
verify_file_sha256 "$NATIVE_DIR/$RIPGREP_GUARD_LIBRARY_NAME" "$RIPGREP_GUARD_SHA256"

echo "Compiling direct-ELF guard attestors and injector..."
TOOLCHAIN_ATTESTOR_DIR="$WORK_DIR/toolchain-elf-attestors"
TOOLCHAIN_ATTESTOR_INJECTOR="$WORK_DIR/toolchain-elf-attestor-injector"
mkdir -p "$TOOLCHAIN_ATTESTOR_DIR"
"$CLANGXX" -std=c++17 -O2 -Wall -Wextra -Werror \
  -I"$PROJECT_ROOT/modules/native-engine/src/main/cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector.cpp" \
  "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector_main.cpp" \
  -o "$TOOLCHAIN_ATTESTOR_INJECTOR"
for attestor_spec in \
    "node:$NODE_LIBRARY_NAME:$NODE_GUARD_LIBRARY_NAME:$NODE_ATTESTOR_SHA256" \
    "python:$PYTHON_LIBRARY_NAME:$PYTHON_GUARD_LIBRARY_NAME:$PYTHON_ATTESTOR_SHA256" \
    "ripgrep:$RIPGREP_LIBRARY_NAME:$RIPGREP_GUARD_LIBRARY_NAME:$RIPGREP_ATTESTOR_SHA256"; do
  attestor_label="${attestor_spec%%:*}"
  attestor_remainder="${attestor_spec#*:}"
  attestor_executable="${attestor_remainder%%:*}"
  attestor_remainder="${attestor_remainder#*:}"
  attestor_guard="${attestor_remainder%%:*}"
  attestor_sha256="${attestor_remainder#*:}"
  "$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" -std=c++17 -Os \
    -Wall -Wextra -Werror -ffreestanding -fno-builtin -fno-exceptions \
    -fno-rtti -fno-unwind-tables -fno-asynchronous-unwind-tables \
    -fno-stack-protector -fvisibility=hidden -fPIE \
    "-DAGENTCODI_EXPECTED_EXECUTABLE=\"$attestor_executable\"" \
    "-DAGENTCODI_EXPECTED_GUARD=\"$attestor_guard\"" \
    -c "$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_payload.cpp" \
    -o "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.o"
  "$LD_LLD" -m aarch64elf -nostdlib -static \
    -T "$PROJECT_ROOT/scripts/toolchain_elf_attestor_payload.ld" \
    -o "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.elf" \
    "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.o"
  if [ "$(readelf -h "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.elf" \
      | awk '/Entry point address:/ {print $4}')" != "0x0" ] \
      || ! readelf -rW "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.elf" \
        | grep -Fq 'There are no relocations in this file.'; then
    echo "The $attestor_label ELF attestor is not a relocation-free entry payload." >&2
    exit 1
  fi
  "$LLVM_OBJCOPY" -O binary \
    "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.elf" \
    "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.bin"
  verify_file_sha256 \
    "$TOOLCHAIN_ATTESTOR_DIR/$attestor_label.bin" \
    "$attestor_sha256"
done

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
cp -L "$PYTHON_SOURCE_BINARY" "$NATIVE_DIR/$PYTHON_LIBRARY_NAME"
cp "$RIPGREP_SOURCE_BINARY" "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME"
cp -L "$PYTHON_SOURCE_LIBRARY" "$NATIVE_DIR/libpython3.14.so"
cp -L "$ANDROID_POSIX_SEMAPHORE_SOURCE_LIBRARY" "$NATIVE_DIR/libandroid-posix-semaphore.so"
cp -L "$ANDROID_SUPPORT_SOURCE_LIBRARY" "$NATIVE_DIR/libandroid-support.so"
cp -L "$BZIP2_SOURCE_LIBRARY" "$NATIVE_DIR/libbz2_1_0.so"
cp -L "$EXPAT_SOURCE_LIBRARY" "$NATIVE_DIR/libexpat_1.so"
cp -L "$LIBFFI_SOURCE_LIBRARY" "$NATIVE_DIR/libffi.so"
cp -L "$LIBLZMA_SOURCE_LIBRARY" "$NATIVE_DIR/liblzma_5.so"
cp -L "$NCURSESW_SOURCE_LIBRARY" "$NATIVE_DIR/libncursesw_6.so"
cp -L "$PANELW_SOURCE_LIBRARY" "$NATIVE_DIR/libpanelw_6.so"
cp -L "$ZSTD_SOURCE_LIBRARY" "$NATIVE_DIR/libzstd_1.so"

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
patch_elf_name "$NATIVE_DIR/libagentcodi.so" 'libz.so.1' 'libz_1.so' 1
patch_elf_name "$NATIVE_DIR/libsqlite3.so" 'libz.so.1' 'libz_1.so' 1

RUNTIME_LINK_RECORDS="$WORK_DIR/runtime-link-records.txt"
: > "$RUNTIME_LINK_RECORDS"
python_extension_index=0
python_packaged_extension_count=0
python_excluded_extension_count=0
while IFS= read -r python_extension; do
  python_extension_name="$(printf 'libpython_ext_%03d.so' "$python_extension_index")"
  python_extension_relative="${python_extension#"$PYTHON_STDLIB_SOURCE"/}"
  case "$python_extension_relative" in
    lib-dynload/_dbm.cpython-314-aarch64-linux-android.so|\
    lib-dynload/_gdbm.cpython-314-aarch64-linux-android.so|\
    lib-dynload/readline.cpython-314-aarch64-linux-android.so)
      python_excluded_extension_count=$((python_excluded_extension_count + 1))
      python_extension_index=$((python_extension_index + 1))
      continue
      ;;
  esac
  cp -L "$python_extension" "$NATIVE_DIR/$python_extension_name"
  for relocation in \
      'libbz2.so.1.0:libbz2_1_0.so' \
      'libcrypto.so.3:libcrypto_3.so' \
      'libexpat.so.1:libexpat_1.so' \
      'liblzma.so.5:liblzma_5.so' \
      'libncursesw.so.6:libncursesw_6.so' \
      'libpanelw.so.6:libpanelw_6.so' \
      'libssl.so.3:libssl_3.so' \
      'libz.so.1:libz_1.so' \
      'libzstd.so.1:libzstd_1.so'; do
    patch_elf_name_all \
      "$NATIVE_DIR/$python_extension_name" \
      "${relocation%%:*}" \
      "${relocation#*:}"
  done
  chmod 755 "$NATIVE_DIR/$python_extension_name"
  python_extension_sha="$(sha256sum "$NATIVE_DIR/$python_extension_name" | awk '{print $1}')"
  printf 'python/lib/python3.14/%s\tL\t%s\t%s\tpython/lib/python3.14/%s\n' \
    "$python_extension_relative" \
    "$python_extension_sha" \
    "$python_extension_name" \
    "$python_extension_relative" >> "$RUNTIME_LINK_RECORDS"
  python_packaged_extension_count=$((python_packaged_extension_count + 1))
  python_extension_index=$((python_extension_index + 1))
done < <(find "$PYTHON_DYNLOAD_SOURCE" -type f -name '*.so' | sort)
if [ "$python_extension_index" -ne "$PYTHON_SOURCE_EXTENSION_COUNT" ] \
    || [ "$python_packaged_extension_count" -ne "$PYTHON_PACKAGED_EXTENSION_COUNT" ] \
    || [ "$python_excluded_extension_count" -ne 3 ]; then
  echo "Unexpected packaged Python extension-module count." >&2
  exit 1
fi
for excluded_python_native in \
    libpython_ext_015.so libpython_ext_018.so libpython_ext_065.so; do
  if [ -e "$NATIVE_DIR/$excluded_python_native" ]; then
    echo "Excluded Python extension was packaged: $excluded_python_native" >&2
    exit 1
  fi
done
if grep -Eq 'lib-dynload/(_dbm|_gdbm|readline)\.cpython-' "$RUNTIME_LINK_RECORDS"; then
  echo "Excluded Python extension leaked into the runtime manifest inputs." >&2
  exit 1
fi

for python_native in \
    "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" \
    "$NATIVE_DIR/libpython3.14.so" \
    "$NATIVE_DIR/libandroid-posix-semaphore.so" \
    "$NATIVE_DIR/libandroid-support.so" \
    "$NATIVE_DIR/libbz2_1_0.so" \
    "$NATIVE_DIR/libexpat_1.so" \
    "$NATIVE_DIR/libffi.so" \
    "$NATIVE_DIR/liblzma_5.so" \
    "$NATIVE_DIR/libncursesw_6.so" \
    "$NATIVE_DIR/libpanelw_6.so" \
    "$NATIVE_DIR/libzstd_1.so"; do
  for relocation in \
      'libbz2.so.1.0:libbz2_1_0.so' \
      'libcrypto.so.3:libcrypto_3.so' \
      'libexpat.so.1:libexpat_1.so' \
      'liblzma.so.5:liblzma_5.so' \
      'libncursesw.so.6:libncursesw_6.so' \
      'libpanelw.so.6:libpanelw_6.so' \
      'libssl.so.3:libssl_3.so' \
      'libz.so.1:libz_1.so' \
      'libzstd.so.1:libzstd_1.so'; do
    patch_elf_name_all "$python_native" "${relocation%%:*}" "${relocation#*:}"
  done
  chmod 755 "$python_native"
done

verify_file_sha256 \
  "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
  "$NODE_UNGUARDED_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" "$RIPGREP_SOURCE_SHA256"
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$PATCHELF_BIN" \
  --page-size 16384 --add-needed "$NODE_GUARD_LIBRARY_NAME" \
  "$NATIVE_DIR/$NODE_LIBRARY_NAME"
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$PATCHELF_BIN" \
  --page-size 16384 --add-needed "$PYTHON_GUARD_LIBRARY_NAME" \
  "$NATIVE_DIR/$PYTHON_LIBRARY_NAME"
env LD_LIBRARY_PATH="$AAPT2_LIBRARY_PATH" "$PATCHELF_BIN" \
  --page-size 16384 --add-needed "$RIPGREP_GUARD_LIBRARY_NAME" \
  "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME"

verify_file_sha256 \
  "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
  "$NODE_PREATTESTED_RUNTIME_SHA256"
verify_file_sha256 \
  "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" \
  "$RIPGREP_PREATTESTED_RUNTIME_SHA256"
for attested_spec in \
    "node:$NODE_LIBRARY_NAME" \
    "python:$PYTHON_LIBRARY_NAME" \
    "ripgrep:$RIPGREP_LIBRARY_NAME"; do
  attested_label="${attested_spec%%:*}"
  attested_executable="${attested_spec#*:}"
  "$TOOLCHAIN_ATTESTOR_INJECTOR" \
    "$NATIVE_DIR/$attested_executable" \
    "$TOOLCHAIN_ATTESTOR_DIR/$attested_label.bin" \
    "$WORK_DIR/$attested_executable.attested"
  mv -f \
    "$WORK_DIR/$attested_executable.attested" \
    "$NATIVE_DIR/$attested_executable"
  chmod 755 "$NATIVE_DIR/$attested_executable"
done

verify_file_sha256 "$NATIVE_DIR/$NODE_LIBRARY_NAME" "$NODE_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libcares.so" "$CARES_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libcrypto_3.so" "$CRYPTO_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libicudata_78.so" "$ICUDATA_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libicui18n_78.so" "$ICUI18N_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libicuuc_78.so" "$ICUUC_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libsqlite3.so" "$SQLITE_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libssl_3.so" "$SSL_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/libz_1.so" "$ZLIB_RUNTIME_SHA256"
verify_file_sha256 "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" "$RIPGREP_RUNTIME_SHA256"
chmod 755 \
  "$NATIVE_DIR/$TERMINAL_SHELL_NAME" \
  "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
  "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME"

cp "$CODEX_LICENSE" "$THIRD_PARTY_ASSETS/LICENSE"
cp "$CODEX_NOTICE" "$THIRD_PARTY_ASSETS/NOTICE"
cp "$NODE_LICENSE_FILE" "$NODE_THIRD_PARTY_ASSETS/NODE-LICENSE"
cp "$CARES_LICENSE_SOURCE" "$NODE_THIRD_PARTY_ASSETS/CARES-LICENSE"
cp "$ICU_LICENSE_FILE" "$NODE_THIRD_PARTY_ASSETS/ICU-LICENSE"
cp "$OPENSSL_LICENSE_FILE" "$NODE_THIRD_PARTY_ASSETS/OPENSSL-LICENSE"
cp "$ZLIB_LICENSE_SOURCE" "$NODE_THIRD_PARTY_ASSETS/ZLIB-LICENSE"
cp "$RIPGREP_DEPENDENCIES_SOURCE" "$RIPGREP_THIRD_PARTY_ASSETS/DEPENDENCIES"
cp "$RIPGREP_LICENSES_SOURCE" "$RIPGREP_THIRD_PARTY_ASSETS/LICENSES"
cp "$RIPGREP_PROVENANCE_SOURCE" "$RIPGREP_THIRD_PARTY_ASSETS/PROVENANCE"

NPM_LICENSES="$NPM_THIRD_PARTY_ASSETS/NPM-LICENSES"
: > "$NPM_LICENSES"
while IFS= read -r license_path; do
  printf '\n===== %s =====\n\n' "${license_path#"$TERMUX_RUNTIME_PREFIX"/}" \
    >> "$NPM_LICENSES"
  cat "$license_path" >> "$NPM_LICENSES"
done < <(
  {
    find "$NPM_SOURCE_DIRECTORY" -type f \
      \( -iname 'LICENSE' -o -iname 'LICENSE.*' -o -iname 'LICENCE' -o -iname 'LICENCE.*' \)
    find "$TERMUX_RUNTIME_PREFIX/share/doc/npm" -type f
  } | sort -u
)
PYTHON_LICENSES="$PYTHON_THIRD_PARTY_ASSETS/PYTHON-LICENSES"
printf '%s\n' \
  'AGENTCODI packaged Python runtime license inventory' \
  '' \
  'This bundle covers only files actually shipped for the Python runtime.' \
  'The optional native _dbm, _gdbm, and readline extension modules and their' \
  'GNU runtime libraries are deliberately excluded from the APK.' \
  > "$PYTHON_LICENSES"
append_python_license() {
  local component="$1"
  local license_path="$2"
  if [ ! -f "$license_path" ]; then
    echo "Pinned Python dependency license file is missing: $component" >&2
    exit 1
  fi
  printf '\n===== %s =====\n\n' "$component" >> "$PYTHON_LICENSES"
  cat "$license_path" >> "$PYTHON_LICENSES"
}
append_python_license 'Python 3.14.6 / PSF license' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/python/LICENSE"
append_python_license 'libandroid-posix-semaphore / copyright' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libandroid-posix-semaphore/copyright"
append_python_license 'libandroid-support / Apache-2.0' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libandroid-support/LICENSE.txt"
append_python_license 'libandroid-support / MIT' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libandroid-support/LICENSE.txt.1"
append_python_license 'libbz2 / bzip2 license' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libbz2/copyright"
append_python_license 'libffi / MIT-style license' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libffi/copyright"
append_python_license 'liblzma / upstream license map' \
  "$LIBLZMA_LICENSE_SUMMARY_SOURCE"
append_python_license 'liblzma / BSD Zero Clause License (0BSD)' \
  "$LIBLZMA_0BSD_LICENSE_SOURCE"
append_python_license 'ncurses and ncurses-ui-libs / MIT-style license' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/ncurses/copyright"
append_python_license 'OpenSSL 3.6.3 / Apache-2.0' \
  "$OPENSSL_LICENSE_FILE"
append_python_license 'Expat / MIT license' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libexpat/copyright"
append_python_license 'SQLite / public-domain dedication' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/libsqlite/copyright"
append_python_license 'zlib / zlib license' \
  "$TERMUX_RUNTIME_PREFIX/share/doc/zlib/copyright"
append_python_license "Zstandard $ZSTD_VERSION / BSD license" \
  "$ZSTD_LICENSE_FILE"
if [ ! -s "$NPM_LICENSES" ] || [ ! -s "$PYTHON_LICENSES" ] \
    || [ "$(wc -c < "$NPM_LICENSES")" -gt 524288 ] \
    || [ "$(wc -c < "$PYTHON_LICENSES")" -gt 131072 ]; then
  echo "Packaged npm/Python license bundle is missing or exceeds the UI bound." >&2
  exit 1
fi
if grep -Fq 'GNU GENERAL PUBLIC LICENSE' "$PYTHON_LICENSES"; then
  echo "Python license bundle unexpectedly contains a full GNU license text." >&2
  exit 1
fi
actual_python_licenses_sha="$(sha256sum "$PYTHON_LICENSES" | awk '{print $1}')"
if [ -n "$PYTHON_LICENSES_SHA256" ] \
    && [ "$actual_python_licenses_sha" != "$PYTHON_LICENSES_SHA256" ]; then
  echo "Derived Python license inventory hash mismatch." >&2
  exit 1
fi
echo "Derived Python license inventory SHA-256: $actual_python_licenses_sha"

RUNTIME_RECORDS="$WORK_DIR/runtime-records.txt"
RUNTIME_FILE_LIST="$WORK_DIR/runtime-file-list.txt"
RUNTIME_FILE_SIZES="$WORK_DIR/runtime-file-sizes.txt"
RUNTIME_FILE_HASHES="$WORK_DIR/runtime-file-hashes.txt"
find "$TOOL_RUNTIME_STAGE" -type f -print | sort > "$RUNTIME_FILE_LIST"
if find "$TOOL_RUNTIME_STAGE" -type f -printf '%P\n' \
    | grep -Eq '[[:space:][:cntrl:]]'; then
  echo "Packaged npm/Python runtime contains a manifest-unsafe path." >&2
  exit 1
fi
find "$TOOL_RUNTIME_STAGE" -type f -exec chmod 600 {} +
find "$TOOL_RUNTIME_STAGE" -type f -printf '%p\t%s\n' \
  | sort -t $'\t' -k1,1 > "$RUNTIME_FILE_SIZES"
xargs -d '\n' -r sha256sum < "$RUNTIME_FILE_LIST" > "$RUNTIME_FILE_HASHES"
awk -v prefix="$TOOL_RUNTIME_STAGE/" '
  NR == FNR {
    hashes[substr($0, 67)] = substr($0, 1, 64)
    next
  }
  {
    separator = index($0, "\t")
    path = substr($0, 1, separator - 1)
    size = substr($0, separator + 1)
    relative = substr(path, length(prefix) + 1)
    if (!(path in hashes)) {
      exit 2
    }
    printf "%s\tF\t%s\t%s\t%s\n", relative, size, hashes[path], relative
  }
' "$RUNTIME_FILE_HASHES" "$RUNTIME_FILE_SIZES" > "$RUNTIME_RECORDS"
cat "$RUNTIME_LINK_RECORDS" >> "$RUNTIME_RECORDS"
{
  printf 'AGENTCODI_TOOL_RUNTIME_V1\n'
  sort -t $'\t' -k1,1 "$RUNTIME_RECORDS" | cut -f2-
} > "$TOOL_RUNTIME_MANIFEST"
runtime_entry_count="$(tail -n +2 "$TOOL_RUNTIME_MANIFEST" | wc -l)"
runtime_total_bytes="$(awk -F '\t' '$1 == "F" { total += $2 } END { print total + 0 }' "$TOOL_RUNTIME_MANIFEST")"
if [ "$runtime_entry_count" -gt 8192 ] || [ "$runtime_total_bytes" -gt 67108864 ]; then
  echo "Packaged npm/Python runtime exceeds the Java extraction bounds." >&2
  exit 1
fi
find "$TOOL_RUNTIME_STAGE" -type d -exec chmod 700 {} +
find "$TOOL_RUNTIME_STAGE" -exec touch -t 202001010000 {} +
(
  cd "$TOOL_RUNTIME_STAGE"
  zip -q -X -9 -r "$TOOL_RUNTIME_ARCHIVE" npm python
)
actual_tool_runtime_manifest_sha="$(sha256sum "$TOOL_RUNTIME_MANIFEST" | awk '{print $1}')"
if [ -n "$TOOL_RUNTIME_MANIFEST_SHA256" ] \
    && [ "$actual_tool_runtime_manifest_sha" != "$TOOL_RUNTIME_MANIFEST_SHA256" ]; then
  echo "Derived npm/Python runtime manifest hash mismatch." >&2
  exit 1
fi
echo "Derived tool runtime manifest SHA-256: $actual_tool_runtime_manifest_sha"

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
if ! grep -Fq 'shell_environment_policy={inherit="none"' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'analytics.enabled=false' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'otel.exporter="none"' "$WORK_DIR/native-engine-strings.txt" \
    || ! grep -Fq 'feedback.enabled=false' "$WORK_DIR/native-engine-strings.txt"; then
  echo "Native engine is missing the closed environment or telemetry policy." >&2
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
    "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
    "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" \
    "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME"; do
  if ! file "$packaged_executable" | grep -q 'ARM aarch64' \
      || ! readelf -l "$packaged_executable" | grep -q '/system/bin/linker64'; then
    echo "Packaged terminal executable is not an Android ARM64 binary: $packaged_executable" >&2
    exit 1
  fi
done
for guard_library in \
    "$NODE_GUARD_LIBRARY_NAME" \
    "$PYTHON_GUARD_LIBRARY_NAME" \
    "$RIPGREP_GUARD_LIBRARY_NAME"; do
  if ! file "$NATIVE_DIR/$guard_library" | grep -q 'ARM aarch64' \
      || ! readelf -dW "$NATIVE_DIR/$guard_library" \
        | grep -Fq 'Shared library: [libc++_shared.so]' \
      || ! strings "$NATIVE_DIR/$guard_library" \
        | grep -Fq 'Guarded tool rejected a non-canonical executable entry point'; then
    echo "Packaged direct-ELF guard is incomplete: $guard_library" >&2
    exit 1
  fi
done
for attested_executable in \
    "$NODE_LIBRARY_NAME" \
    "$PYTHON_LIBRARY_NAME" \
    "$RIPGREP_LIBRARY_NAME"; do
  attested_entry="$(readelf -h "$NATIVE_DIR/$attested_executable" \
      | awk '/Entry point address:/ {print $4}')"
  if ! grep -aFq \
        'Guarded tool rejected an untrusted policy library' \
        "$NATIVE_DIR/$attested_executable" \
      || ! grep -aFq \
        'AGENTCODI-ATTEST' \
        "$NATIVE_DIR/$attested_executable" \
      || ! readelf -lW "$NATIVE_DIR/$attested_executable" \
        | awk -v entry="$attested_entry" \
          'function canonical_hex(value) { \
             sub(/^0x0+/, "0x", value); \
             return value == "0x" ? "0x0" : tolower(value); \
           } \
           $1 == "LOAD" \
              && canonical_hex($3) == canonical_hex(entry) \
              && $(NF - 2) == "R" \
              && $(NF - 1) == "E" && $NF == "0x4000" { found = 1 } \
           END { exit !found }'; then
    echo "Packaged tool ELF lacks its in-binary guard attestor: $attested_executable" >&2
    exit 1
  fi
done
if readelf -dW "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" | grep -Eq '(RPATH|RUNPATH)' \
    || [ "$(readelf -dW "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" | awk '/NEEDED/ {gsub(/[][]/, "", $NF); print $NF}' | sort | tr '\n' ' ')" != "$RIPGREP_GUARD_LIBRARY_NAME libc.so libdl.so " ] \
    || strings "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" | grep -Fq '/data/data/com.termux' \
    || strings "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" | grep -Fq "$PROJECT_ROOT"; then
  echo "Packaged ripgrep has an unsafe host path or unexpected ELF dependency." >&2
  exit 1
fi
ripgrep_version_output="$("$RIPGREP_SOURCE_BINARY" --version)"
if [ "$(printf '%s\n' "$ripgrep_version_output" | sed -n '1p')" != "ripgrep $RIPGREP_VERSION" ] \
    || ! printf '%s\n' "$ripgrep_version_output" | grep -Fq 'features:-pcre2'; then
  echo "Packaged ripgrep version or no-PCRE2 feature marker is invalid." >&2
  exit 1
fi
if ripgrep_pcre2_output="$("$RIPGREP_SOURCE_BINARY" --pcre2-version 2>&1)"; then
  echo "Packaged ripgrep unexpectedly provides PCRE2." >&2
  exit 1
fi
if ! printf '%s\n' "$ripgrep_pcre2_output" \
    | grep -Fq 'PCRE2 is not available in this build of ripgrep.' \
    || ! grep -Fq 'Normal target package count: 34' "$RIPGREP_DEPENDENCIES_SOURCE" \
    || grep -Eiq '^(pcre2|pcre2-sys) [0-9]' "$RIPGREP_DEPENDENCIES_SOURCE"; then
  echo "Packaged ripgrep feature or dependency inventory is inconsistent." >&2
  exit 1
fi
for dependency in libcares.so libcrypto_3.so libicudata_78.so libicui18n_78.so libicuuc_78.so libsqlite3.so libssl_3.so libz_1.so libpython3.14.so libandroid-posix-semaphore.so libandroid-support.so libbz2_1_0.so libexpat_1.so libffi.so liblzma_5.so libncursesw_6.so libpanelw_6.so libzstd_1.so; do
  if ! file "$NATIVE_DIR/$dependency" | grep -q 'ARM aarch64'; then
    echo "Packaged Node.js dependency is not ARM64: $dependency" >&2
    exit 1
  fi
done
if find "$NATIVE_DIR" -maxdepth 1 -type f \
    \( -name 'libgdbm*.so' -o -name 'libreadline*.so' \) -print -quit | grep -q .; then
  echo "A forbidden GNU database or line-editing library was packaged." >&2
  exit 1
fi
for packaged_native in "$NATIVE_DIR"/*.so; do
  if readelf -d "$packaged_native" 2>/dev/null \
      | grep -Eq 'Shared library: \[lib(gdbm(_compat)?|readline)(\.so|_)'; then
    echo "Packaged native file retains a forbidden GNU dependency: $packaged_native" >&2
    exit 1
  fi
done
for python_native in "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" "$NATIVE_DIR/libpython3.14.so" "$NATIVE_DIR"/libpython_ext_*.so "$NATIVE_DIR/libbz2_1_0.so" "$NATIVE_DIR/libexpat_1.so" "$NATIVE_DIR/liblzma_5.so" "$NATIVE_DIR/libncursesw_6.so" "$NATIVE_DIR/libpanelw_6.so" "$NATIVE_DIR/libzstd_1.so"; do
  if readelf -d "$python_native" | grep -Eq 'Shared library: \[lib(bz2\.so\.1\.0|crypto\.so\.3|expat\.so\.1|lzma\.so\.5|ncursesw\.so\.6|panelw\.so\.6|ssl\.so\.3|z\.so\.1|zstd\.so\.1)\]'; then
    echo "Packaged Python dependency relocation is incomplete: $python_native" >&2
    exit 1
  fi
done
PYTHON_NATIVE_SET="$WORK_DIR/python-native-set.sha256"
{
  printf '%s\n' \
    "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" \
    "$NATIVE_DIR/libpython3.14.so" \
    "$NATIVE_DIR/libandroid-posix-semaphore.so" \
    "$NATIVE_DIR/libandroid-support.so" \
    "$NATIVE_DIR/libbz2_1_0.so" \
    "$NATIVE_DIR/libcrypto_3.so" \
    "$NATIVE_DIR/libexpat_1.so" \
    "$NATIVE_DIR/libffi.so" \
    "$NATIVE_DIR/liblzma_5.so" \
    "$NATIVE_DIR/libncursesw_6.so" \
    "$NATIVE_DIR/libpanelw_6.so" \
    "$NATIVE_DIR/libsqlite3.so" \
    "$NATIVE_DIR/libssl_3.so" \
    "$NATIVE_DIR/libz_1.so" \
    "$NATIVE_DIR/libzstd_1.so"
  find "$NATIVE_DIR" -maxdepth 1 -type f -name 'libpython_ext_*.so'
} | sort | while IFS= read -r python_native; do
  printf '%s  %s\n' \
    "$(sha256sum "$python_native" | awk '{print $1}')" \
    "$(basename "$python_native")"
done > "$PYTHON_NATIVE_SET"
actual_python_native_set_sha="$(sha256sum "$PYTHON_NATIVE_SET" | awk '{print $1}')"
if [ -n "$PYTHON_NATIVE_SET_SHA256" ] \
    && [ "$actual_python_native_set_sha" != "$PYTHON_NATIVE_SET_SHA256" ]; then
  echo "Derived Python native runtime-set hash mismatch." >&2
  exit 1
fi
echo "Derived Python native set SHA-256: $actual_python_native_set_sha"
if readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
    | grep -Eq 'lib(z\.so\.1|crypto\.so\.3|ssl\.so\.3|icui18n\.so\.78|icuuc\.so\.78)' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libz_1.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libcrypto_3.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libssl_3.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libicui18n_78.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" | grep -Fq 'Shared library: [libicuuc_78.so]' \
    || ! readelf -d "$NATIVE_DIR/$NODE_LIBRARY_NAME" \
      | grep -Fq "Shared library: [$NODE_GUARD_LIBRARY_NAME]" \
    || ! readelf -d "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" \
      | grep -Fq "Shared library: [$PYTHON_GUARD_LIBRARY_NAME]"; then
  echo "Packaged Node.js dependency relocation is incomplete." >&2
  exit 1
fi

TOOLCHAIN_SMOKE_ROOT="$WORK_DIR/toolchain-smoke"
TOOLCHAIN_SMOKE_WORKSPACE="$TOOLCHAIN_SMOKE_ROOT/workspace"
TOOLCHAIN_SMOKE_DIRECTORY="$TOOLCHAIN_SMOKE_WORKSPACE/toolchain"
TOOLCHAIN_SMOKE_TOOL_BIN="$TOOLCHAIN_SMOKE_ROOT/tool-bin"
TOOLCHAIN_SMOKE_RUNTIME="$TOOL_RUNTIME_STAGE"
TOOLCHAIN_SMOKE_HOME="$TOOLCHAIN_SMOKE_ROOT/home"
TOOLCHAIN_SMOKE_TEMP="$TOOLCHAIN_SMOKE_ROOT/temp"
mkdir -p "$TOOLCHAIN_SMOKE_WORKSPACE" "$TOOLCHAIN_SMOKE_DIRECTORY" "$TOOLCHAIN_SMOKE_TOOL_BIN" "$TOOLCHAIN_SMOKE_HOME" "$TOOLCHAIN_SMOKE_TEMP"
chmod 700 "$TOOLCHAIN_SMOKE_ROOT" "$TOOLCHAIN_SMOKE_WORKSPACE" "$TOOLCHAIN_SMOKE_DIRECTORY" "$TOOLCHAIN_SMOKE_TOOL_BIN" "$TOOLCHAIN_SMOKE_HOME" "$TOOLCHAIN_SMOKE_TEMP"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/node"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/npm"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/python"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/python3"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/rg"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$TOOLCHAIN_SMOKE_TOOL_BIN/agentcodi-toolchain"
while IFS=$'\t' read -r ignored record_type ignored_sha native_name runtime_path; do
  if [ "$record_type" = "L" ]; then
    ln -s "$NATIVE_DIR/$native_name" "$TOOL_RUNTIME_STAGE/$runtime_path"
  fi
done < "$RUNTIME_LINK_RECORDS"
toolchain_smoke() {
  env -i \
    HOME="$TOOLCHAIN_SMOKE_HOME" \
    TMPDIR="$TOOLCHAIN_SMOKE_TEMP" \
    TMP="$TOOLCHAIN_SMOKE_TEMP" \
    TEMP="$TOOLCHAIN_SMOKE_TEMP" \
    PATH="$TOOLCHAIN_SMOKE_TOOL_BIN:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$TOOLCHAIN_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$TOOLCHAIN_SMOKE_DIRECTORY" \
    AGENTCODI_TOOL_BIN="$TOOLCHAIN_SMOKE_TOOL_BIN" \
    AGENTCODI_TOOL_RUNTIME="$TOOLCHAIN_SMOKE_RUNTIME" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_NPM_VERSION="$NPM_VERSION" \
    AGENTCODI_PYTHON_VERSION="$PYTHON_VERSION" \
    AGENTCODI_RIPGREP_VERSION="$RIPGREP_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node,npm,python,ripgrep" \
    RIPGREP_CONFIG_PATH="${RIPGREP_CONFIG_PATH-}" \
    "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$@"
}
toolchain_model_smoke() {
  env -i \
    HOME="$TOOLCHAIN_SMOKE_HOME" \
    TMPDIR="$TOOLCHAIN_SMOKE_TEMP" \
    TMP="$TOOLCHAIN_SMOKE_TEMP" \
    TEMP="$TOOLCHAIN_SMOKE_TEMP" \
    PATH="$TOOLCHAIN_SMOKE_TOOL_BIN:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$TOOLCHAIN_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$TOOLCHAIN_SMOKE_DIRECTORY" \
    AGENTCODI_TOOL_BIN="$TOOLCHAIN_SMOKE_TOOL_BIN" \
    AGENTCODI_TOOL_RUNTIME="$TOOLCHAIN_SMOKE_RUNTIME" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_NPM_VERSION="$NPM_VERSION" \
    AGENTCODI_PYTHON_VERSION="$PYTHON_VERSION" \
    AGENTCODI_RIPGREP_VERSION="$RIPGREP_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node,npm,python,ripgrep" \
    /system/bin/sh -c "$1"
}
guarded_tool_raw_smoke() {
  env -i \
    HOME="$TOOLCHAIN_SMOKE_HOME" \
    TMPDIR="$TOOLCHAIN_SMOKE_TEMP" \
    TMP="$TOOLCHAIN_SMOKE_TEMP" \
    TEMP="$TOOLCHAIN_SMOKE_TEMP" \
    PATH="$TOOLCHAIN_SMOKE_TOOL_BIN:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    LD_LIBRARY_PATH="$NATIVE_DIR" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$TOOLCHAIN_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$TOOLCHAIN_SMOKE_DIRECTORY" \
    AGENTCODI_TOOL_BIN="$TOOLCHAIN_SMOKE_TOOL_BIN" \
    AGENTCODI_TOOL_RUNTIME="$TOOLCHAIN_SMOKE_RUNTIME" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_NPM_VERSION="$NPM_VERSION" \
    AGENTCODI_PYTHON_VERSION="$PYTHON_VERSION" \
    AGENTCODI_RIPGREP_VERSION="$RIPGREP_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node,npm,python,ripgrep" \
    RIPGREP_CONFIG_PATH="${RIPGREP_CONFIG_PATH-}" \
    "$@"
}
TOOLCHAIN_FAKE_GUARD_DIR="$TOOLCHAIN_SMOKE_ROOT/fake-guards"
mkdir -p "$TOOLCHAIN_FAKE_GUARD_DIR"
chmod 700 "$TOOLCHAIN_FAKE_GUARD_DIR"
for fake_guard_library in \
    "$NODE_GUARD_LIBRARY_NAME" \
    "$PYTHON_GUARD_LIBRARY_NAME" \
    "$RIPGREP_GUARD_LIBRARY_NAME"; do
  "$CLANGXX" --target=aarch64-linux-android"$MIN_SDK" \
    -shared -fPIC -std=c++17 -O2 -Wall -Wextra -Werror \
    "$PROJECT_ROOT/tests/cpp/toolchain_fake_guard.cpp" \
    -Wl,-soname,"$fake_guard_library" \
    -o "$TOOLCHAIN_FAKE_GUARD_DIR/$fake_guard_library"
done
guarded_tool_substituted_smoke() {
  env -i \
    HOME="$TOOLCHAIN_SMOKE_HOME" \
    TMPDIR="$TOOLCHAIN_SMOKE_TEMP" \
    TMP="$TOOLCHAIN_SMOKE_TEMP" \
    TEMP="$TOOLCHAIN_SMOKE_TEMP" \
    PATH="$TOOLCHAIN_SMOKE_TOOL_BIN:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    LD_LIBRARY_PATH="$TOOLCHAIN_FAKE_GUARD_DIR:$NATIVE_DIR" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$TOOLCHAIN_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$TOOLCHAIN_SMOKE_DIRECTORY" \
    AGENTCODI_TOOL_BIN="$TOOLCHAIN_SMOKE_TOOL_BIN" \
    AGENTCODI_TOOL_RUNTIME="$TOOLCHAIN_SMOKE_RUNTIME" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_NPM_VERSION="$NPM_VERSION" \
    AGENTCODI_PYTHON_VERSION="$PYTHON_VERSION" \
    AGENTCODI_RIPGREP_VERSION="$RIPGREP_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node,npm,python,ripgrep" \
    "$@"
}
for guarded_raw_spec in \
    "$NATIVE_DIR/$NODE_LIBRARY_NAME:Node.js" \
    "$NATIVE_DIR/$PYTHON_LIBRARY_NAME:Python" \
    "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME:ripgrep"; do
  guarded_raw_path="${guarded_raw_spec%%:*}"
  guarded_raw_label="${guarded_raw_spec#*:}"
  if guarded_tool_raw_smoke "$guarded_raw_path" --version \
      >"$WORK_DIR/direct-elf-disabled.out" 2>&1 \
      || ! grep -Fq 'available but not enabled' \
        "$WORK_DIR/direct-elf-disabled.out"; then
    echo "Direct $guarded_raw_label ELF bypassed activation." >&2
    exit 1
  fi
done
for hidden_runtime_name in \
    "$NODE_LIBRARY_NAME" "$PYTHON_LIBRARY_NAME" "$RIPGREP_LIBRARY_NAME"; do
  if toolchain_model_smoke "command -v $hidden_runtime_name" \
      >"$WORK_DIR/direct-elf-path.out" 2>&1; then
    echo "A real packaged tool ELF remains directly searchable in PATH: $hidden_runtime_name" >&2
    exit 1
  fi
done
if ! toolchain_smoke --toolchain list | grep -Fq "python $PYTHON_VERSION — available, not enabled" \
    || ! toolchain_smoke --toolchain list | grep -Fq "ripgrep $RIPGREP_VERSION — available, not enabled" \
    || ! toolchain_smoke --toolchain install npm | grep -Fq "Enabled packaged npm $NPM_VERSION." \
    || [ "$(stat -c '%a' "$TOOLCHAIN_SMOKE_DIRECTORY/installed/node-$NODE_VERSION")" != "600" ] \
    || [ "$(stat -c '%a' "$TOOLCHAIN_SMOKE_DIRECTORY/installed/npm-$NPM_VERSION")" != "600" ] \
    || [ "$(toolchain_smoke -c 'node --version' | tr -d '\r')" != "v$NODE_VERSION" ] \
    || [ "$(toolchain_smoke --npm --version | tr -d '\r')" != "$NPM_VERSION" ] \
    || ! toolchain_smoke --toolchain install python | grep -Fq "Enabled packaged Python $PYTHON_VERSION." \
    || [ "$(stat -c '%a' "$TOOLCHAIN_SMOKE_DIRECTORY/installed/python-$PYTHON_VERSION")" != "600" ] \
    || [ "$(toolchain_smoke --python --version 2>&1 | tr -d '\r')" != "Python $PYTHON_VERSION" ] \
    || [ "$(toolchain_smoke --python -c "import json, ssl, sqlite3, zlib; print('python-imports-ok')" | tr -d '\r')" != "python-imports-ok" ] \
    || ! toolchain_smoke --toolchain install ripgrep | grep -Fq "Enabled packaged ripgrep $RIPGREP_VERSION." \
    || [ "$(stat -c '%a' "$TOOLCHAIN_SMOKE_DIRECTORY/installed/ripgrep-$RIPGREP_VERSION")" != "600" ] \
    || [ "$(toolchain_smoke --ripgrep --version | sed -n '1p')" != "ripgrep $RIPGREP_VERSION" ]; then
  echo "Packaged terminal shell and npm/Python/ripgrep activation smoke test failed." >&2
  exit 1
fi
if [ "$(guarded_tool_raw_smoke "$NATIVE_DIR/$NODE_LIBRARY_NAME" --version | tr -d '\r')" != "v$NODE_VERSION" ] \
    || [ "$(guarded_tool_raw_smoke "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" --version 2>&1 | tr -d '\r')" != "Python $PYTHON_VERSION" ] \
    || [ "$(guarded_tool_raw_smoke "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" --version | sed -n '1p')" != "ripgrep $RIPGREP_VERSION" ]; then
  echo "An activated direct ELF lost guarded runtime functionality." >&2
  exit 1
fi
for substituted_raw_spec in \
    "$NATIVE_DIR/$NODE_LIBRARY_NAME:Node.js" \
    "$NATIVE_DIR/$PYTHON_LIBRARY_NAME:Python" \
    "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME:ripgrep"; do
  substituted_raw_path="${substituted_raw_spec%%:*}"
  substituted_raw_label="${substituted_raw_spec#*:}"
  if guarded_tool_substituted_smoke "$substituted_raw_path" --version \
      >"$WORK_DIR/direct-elf-substituted.out" 2>&1 \
      || ! grep -Fq 'untrusted policy library' \
        "$WORK_DIR/direct-elf-substituted.out"; then
    echo "Direct $substituted_raw_label ELF accepted a substituted policy library." >&2
    exit 1
  fi
done
for blocked_ripgrep_option in --pre=/system/bin/sh --search-zip --follow -z -L; do
  if toolchain_smoke --ripgrep "$blocked_ripgrep_option" needle . \
      >"$WORK_DIR/ripgrep-blocked.out" 2>&1 \
      || ! grep -Fq 'options --pre, --search-zip and --follow are disabled' \
        "$WORK_DIR/ripgrep-blocked.out"; then
    echo "Packaged ripgrep bridge accepted a blocked option: $blocked_ripgrep_option" >&2
    exit 1
  fi
  if guarded_tool_raw_smoke \
      "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" \
      "$blocked_ripgrep_option" needle . \
      >"$WORK_DIR/ripgrep-direct-blocked.out" 2>&1 \
      || ! grep -Fq 'options --pre, --search-zip and --follow are disabled' \
        "$WORK_DIR/ripgrep-direct-blocked.out"; then
    echo "Direct ripgrep ELF accepted a blocked option: $blocked_ripgrep_option" >&2
    exit 1
  fi
done
if guarded_tool_raw_smoke \
    /system/bin/linker64 "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" --version \
    >"$WORK_DIR/ripgrep-linker-bypass.out" 2>&1 \
    || ! grep -Fq 'non-canonical executable entry point' \
      "$WORK_DIR/ripgrep-linker-bypass.out"; then
  echo "The Android dynamic linker bypassed the ripgrep ELF guard." >&2
  exit 1
fi
printf '%s\n' '--max-count=0' > "$TOOLCHAIN_SMOKE_ROOT/ripgrep-config"
printf '%s\n' 'agentcodi-ripgrep-config-scrub-proof' \
  > "$TOOLCHAIN_SMOKE_WORKSPACE/ripgrep-config-proof.txt"
ripgrep_config_output="$(
  RIPGREP_CONFIG_PATH="$TOOLCHAIN_SMOKE_ROOT/ripgrep-config" \
    toolchain_smoke --ripgrep \
      --no-filename --no-line-number \
      agentcodi-ripgrep-config-scrub-proof \
      "$TOOLCHAIN_SMOKE_WORKSPACE/ripgrep-config-proof.txt"
)"
if [ "$ripgrep_config_output" != 'agentcodi-ripgrep-config-scrub-proof' ]; then
  echo "Packaged ripgrep bridge did not clear RIPGREP_CONFIG_PATH." >&2
  exit 1
fi
direct_ripgrep_config_output="$(
  RIPGREP_CONFIG_PATH="$TOOLCHAIN_SMOKE_ROOT/ripgrep-config" \
    guarded_tool_raw_smoke "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" \
      --no-filename --no-line-number \
      agentcodi-ripgrep-config-scrub-proof \
      "$TOOLCHAIN_SMOKE_WORKSPACE/ripgrep-config-proof.txt"
)"
if [ "$direct_ripgrep_config_output" != 'agentcodi-ripgrep-config-scrub-proof' ]; then
  echo "Direct ripgrep ELF did not apply shared configuration cleanup." >&2
  exit 1
fi
python_dbm_output="$(toolchain_smoke --python -c \
  'import dbm, importlib.util, os, shelve; assert importlib.util.find_spec("_dbm") is None; assert importlib.util.find_spec("_gdbm") is None; assert importlib.util.find_spec("readline") is None; db_path = os.path.join(os.environ["TMPDIR"], "dbm-smoke"); database = dbm.open(db_path, "n"); assert database.__class__.__module__ == "dbm.sqlite3"; database[b"key"] = b"value"; database.close(); database = dbm.open(db_path, "r"); assert database[b"key"] == b"value"; database.close(); shelf = shelve.open(os.path.join(os.environ["TMPDIR"], "shelve-smoke")); shelf["answer"] = 42; shelf.close(); shelf = shelve.open(os.path.join(os.environ["TMPDIR"], "shelve-smoke")); assert shelf["answer"] == 42; shelf.close(); print("python-sqlite-dbm-shelve-ok")' \
  | tr -d '\r')"
if [ "$python_dbm_output" != "python-sqlite-dbm-shelve-ok" ]; then
  echo "Packaged Python SQLite dbm/shelve compatibility smoke test failed." >&2
  exit 1
fi
printf -v python_repl_command \
  'env -i HOME=%q TMPDIR=%q TMP=%q TEMP=%q PATH=%q SHELL=%q LD_LIBRARY_PATH=%q HISTFILE=%q NODE_REPL_HISTORY=%q SSL_CERT_DIR=%q AGENTCODI_WORKSPACE=%q AGENTCODI_TOOLCHAIN=%q AGENTCODI_TOOL_BIN=%q AGENTCODI_TOOL_RUNTIME=%q AGENTCODI_NODE_VERSION=%q AGENTCODI_NPM_VERSION=%q AGENTCODI_PYTHON_VERSION=%q AGENTCODI_RIPGREP_VERSION=%q AGENTCODI_TOOLCHAIN_COMMAND=%q AGENTCODI_TOOLCHAIN_PACKAGES=%q %q --python' \
  "$TOOLCHAIN_SMOKE_HOME" "$TOOLCHAIN_SMOKE_TEMP" "$TOOLCHAIN_SMOKE_TEMP" \
  "$TOOLCHAIN_SMOKE_TEMP" "$TOOLCHAIN_SMOKE_TOOL_BIN:/system/bin:/system/xbin" \
  '/system/bin/sh' "$NATIVE_DIR" '/dev/null' '/dev/null' \
  '/system/etc/security/cacerts' "$TOOLCHAIN_SMOKE_WORKSPACE" \
  "$TOOLCHAIN_SMOKE_DIRECTORY" "$TOOLCHAIN_SMOKE_TOOL_BIN" \
  "$TOOLCHAIN_SMOKE_RUNTIME" "$NODE_VERSION" "$NPM_VERSION" "$PYTHON_VERSION" \
  "$RIPGREP_VERSION" 'agentcodi-toolchain' 'node,npm,python,ripgrep' \
  "$NATIVE_DIR/$TERMINAL_SHELL_NAME"
if ! python_repl_output="$(
    printf '%s\n' 'print(__import__("_pyrepl").__name__ + "-ok")' 'exit()' \
      | timeout 30s script -qfec "$python_repl_command" /dev/null \
      | tr -d '\r'
  )" \
    || ! printf '%s\n' "$python_repl_output" | grep -Fq '_pyrepl-ok'; then
  echo "Packaged Python interactive PyREPL smoke test failed." >&2
  exit 1
fi
toolchain_model_output="$(toolchain_model_smoke 'command -v node; command -v npm; command -v python; command -v rg; command -v agentcodi-toolchain; node --version; npm --version; python --version; rg --version; agentcodi-toolchain status' 2>&1 | tr -d '\r')"
for expected_tool_output in \
    "$TOOLCHAIN_SMOKE_TOOL_BIN/node" \
    "$TOOLCHAIN_SMOKE_TOOL_BIN/npm" \
    "$TOOLCHAIN_SMOKE_TOOL_BIN/python" \
    "$TOOLCHAIN_SMOKE_TOOL_BIN/rg" \
    "$TOOLCHAIN_SMOKE_TOOL_BIN/agentcodi-toolchain" \
    "v$NODE_VERSION" \
    "$NPM_VERSION" \
    "Python $PYTHON_VERSION" \
    "ripgrep $RIPGREP_VERSION" \
    "node $NODE_VERSION — enabled" \
    "npm $NPM_VERSION — enabled" \
    "python $PYTHON_VERSION — enabled" \
    "ripgrep $RIPGREP_VERSION — enabled"; do
  if ! printf '%s\n' "$toolchain_model_output" | grep -Fq "$expected_tool_output"; then
    echo "Model-shell toolchain smoke omitted: $expected_tool_output" >&2
    exit 1
  fi
done
if ! toolchain_smoke --toolchain remove node | grep -Fq "Disabled Node.js $NODE_VERSION." \
    || [ -e "$TOOLCHAIN_SMOKE_DIRECTORY/installed/npm-$NPM_VERSION" ] \
    || ! toolchain_smoke --toolchain remove python | grep -Fq "Disabled Python $PYTHON_VERSION." \
    || ! toolchain_smoke --toolchain remove ripgrep | grep -Fq "Disabled ripgrep $RIPGREP_VERSION."; then
  echo "Packaged toolchain dependency removal smoke test failed." >&2
  exit 1
fi
if guarded_tool_raw_smoke "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" --version \
    >"$WORK_DIR/direct-elf-removed.out" 2>&1 \
    || ! grep -Fq 'available but not enabled' "$WORK_DIR/direct-elf-removed.out"; then
  echo "Direct ripgrep ELF ignored deactivation." >&2
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
CONFIG_SMOKE_TOOL_RUNTIME="$TOOL_RUNTIME_STAGE"
CONFIG_SMOKE_TEMP="$WORK_DIR/config-smoke-temp"
mkdir -p "$CONFIG_SMOKE_HOME" "$CONFIG_SMOKE_CODEX_HOME" "$CONFIG_SMOKE_WORKSPACE" "$CONFIG_SMOKE_TOOLCHAIN" "$CONFIG_SMOKE_TOOL_BIN" "$CONFIG_SMOKE_TEMP"
chmod 700 "$CONFIG_SMOKE_HOME" "$CONFIG_SMOKE_CODEX_HOME" "$CONFIG_SMOKE_WORKSPACE" "$CONFIG_SMOKE_TOOLCHAIN" "$CONFIG_SMOKE_TOOL_BIN" "$CONFIG_SMOKE_TEMP"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/node"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/npm"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/python"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/python3"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$CONFIG_SMOKE_TOOL_BIN/rg"
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
    PATH="$CONFIG_SMOKE_TOOL_BIN:/system/bin:/system/xbin" \
    SHELL="/system/bin/sh" \
    HISTFILE="/dev/null" \
    NODE_REPL_HISTORY="/dev/null" \
    SSL_CERT_DIR="/system/etc/security/cacerts" \
    AGENTCODI_WORKSPACE="$CONFIG_SMOKE_WORKSPACE" \
    AGENTCODI_TOOLCHAIN="$CONFIG_SMOKE_TOOLCHAIN" \
    AGENTCODI_TOOL_BIN="$CONFIG_SMOKE_TOOL_BIN" \
    AGENTCODI_TOOL_RUNTIME="$CONFIG_SMOKE_TOOL_RUNTIME" \
    AGENTCODI_NODE_VERSION="$NODE_VERSION" \
    AGENTCODI_NPM_VERSION="$NPM_VERSION" \
    AGENTCODI_PYTHON_VERSION="$PYTHON_VERSION" \
    AGENTCODI_RIPGREP_VERSION="$RIPGREP_VERSION" \
    AGENTCODI_TOOLCHAIN_COMMAND="agentcodi-toolchain" \
    AGENTCODI_TOOLCHAIN_PACKAGES="node,npm,python,ripgrep" \
    CODEX_SELF_EXE="$NATIVE_DIR/libcodex.so" \
    CODEX_CODE_MODE_HOST_PATH="$NATIVE_DIR/$CODEX_PACKAGED_HOST_NAME" \
    "$NATIVE_DIR/libcodex.so" app-server --stdio --strict-config \
    -c 'cli_auth_credentials_store="file"' \
    -c 'approval_policy="on-request"' \
    -c "shell_environment_policy={inherit=\"none\",ignore_default_excludes=false,set={PATH=\"$CONFIG_SMOKE_TOOL_BIN:/system/bin:/system/xbin\",SHELL=\"/system/bin/sh\",HOME=\"$CONFIG_SMOKE_HOME\",TMPDIR=\"$CONFIG_SMOKE_TEMP\",TMP=\"$CONFIG_SMOKE_TEMP\",TEMP=\"$CONFIG_SMOKE_TEMP\",LD_LIBRARY_PATH=\"$NATIVE_DIR\",HISTFILE=\"/dev/null\",NODE_REPL_HISTORY=\"/dev/null\",SSL_CERT_DIR=\"/system/etc/security/cacerts\",AGENTCODI_WORKSPACE=\"$CONFIG_SMOKE_WORKSPACE\",AGENTCODI_TOOLCHAIN=\"$CONFIG_SMOKE_TOOLCHAIN\",AGENTCODI_TOOL_BIN=\"$CONFIG_SMOKE_TOOL_BIN\",AGENTCODI_TOOL_RUNTIME=\"$CONFIG_SMOKE_TOOL_RUNTIME\",AGENTCODI_NODE_VERSION=\"$NODE_VERSION\",AGENTCODI_NPM_VERSION=\"$NPM_VERSION\",AGENTCODI_PYTHON_VERSION=\"$PYTHON_VERSION\",AGENTCODI_RIPGREP_VERSION=\"$RIPGREP_VERSION\",AGENTCODI_TOOLCHAIN_COMMAND=\"agentcodi-toolchain\",AGENTCODI_TOOLCHAIN_PACKAGES=\"node,npm,python,ripgrep\"}}" \
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
    -c "permissions.agentcodi-workspace.filesystem={\":minimal\"=\"read\",\"$CONFIG_SMOKE_TOOL_BIN\"=\"read\",\"$CONFIG_SMOKE_TOOL_RUNTIME\"=\"read\",\":workspace_roots\"={\".\"=\"write\"}}" \
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
BOOTSTRAP_SMOKE_TOOLCHAIN="$BOOTSTRAP_SMOKE_WORKSPACE/toolchain"
BOOTSTRAP_SMOKE_TOOL_BIN="$BOOTSTRAP_SMOKE_ROOT/tool-bin"
BOOTSTRAP_SMOKE_TOOL_RUNTIME="$TOOL_RUNTIME_STAGE"
BOOTSTRAP_SMOKE_CODEX_HOME="$BOOTSTRAP_SMOKE_ROOT/codex-home"
BOOTSTRAP_SMOKE_HOME="$BOOTSTRAP_SMOKE_ROOT/home"
BOOTSTRAP_SMOKE_STATE="$BOOTSTRAP_SMOKE_ROOT/state"
BOOTSTRAP_SMOKE_TEMP="$BOOTSTRAP_SMOKE_ROOT/temp"
mkdir -p "$BOOTSTRAP_SMOKE_WORKSPACE" "$BOOTSTRAP_SMOKE_IMPORTS" "$BOOTSTRAP_SMOKE_TOOLCHAIN" "$BOOTSTRAP_SMOKE_TOOL_BIN" "$BOOTSTRAP_SMOKE_CODEX_HOME" "$BOOTSTRAP_SMOKE_HOME" "$BOOTSTRAP_SMOKE_STATE" "$BOOTSTRAP_SMOKE_TEMP"
chmod 700 "$BOOTSTRAP_SMOKE_ROOT" "$BOOTSTRAP_SMOKE_WORKSPACE" "$BOOTSTRAP_SMOKE_IMPORTS" "$BOOTSTRAP_SMOKE_TOOLCHAIN" "$BOOTSTRAP_SMOKE_TOOL_BIN" "$BOOTSTRAP_SMOKE_CODEX_HOME" "$BOOTSTRAP_SMOKE_HOME" "$BOOTSTRAP_SMOKE_STATE" "$BOOTSTRAP_SMOKE_TEMP"
printf '%s\n' 'agentcodi-import-content-smoke' > "$BOOTSTRAP_SMOKE_IMPORTS/0123456789abcdef0123456789abcdef.bin"
chmod 600 "$BOOTSTRAP_SMOKE_IMPORTS/0123456789abcdef0123456789abcdef.bin"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/node"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/npm"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/python"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/python3"
ln -s "$NATIVE_DIR/$TERMINAL_SHELL_NAME" "$BOOTSTRAP_SMOKE_TOOL_BIN/rg"
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
    "$NATIVE_DIR/$PYTHON_LIBRARY_NAME" \
    "$NATIVE_DIR/$RIPGREP_LIBRARY_NAME" \
    "$BOOTSTRAP_SMOKE_WORKSPACE" \
    "$BOOTSTRAP_SMOKE_TOOLCHAIN" \
    "$BOOTSTRAP_SMOKE_TOOL_BIN" \
    "$BOOTSTRAP_SMOKE_TOOL_RUNTIME" \
    "$BOOTSTRAP_SMOKE_CODEX_HOME" \
    "$BOOTSTRAP_SMOKE_HOME" \
    "$BOOTSTRAP_SMOKE_STATE" \
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
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 "$DEX_MODE" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$DEX_DIR" "$CORE_JAR" "$REVIEW_MODE_JAR" "$PROTECTED_MODE_JAR" "$COMPATIBILITY_MODE_JAR" "$STORAGE_JAR" "$FILE_BROWSER_CONTRACTS_JAR" "$FILE_BROWSER_CLIENT_JAR" "$IMPORT_CONTRACTS_JAR" "$IMPORT_CLIENT_JAR" "$MCP_CONTRACTS_JAR" "$MCP_CLIENT_JAR" "$RUNTIME_JAR" "$APP_JAR"
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
grep -Fx "lib/$ABI/$PYTHON_LIBRARY_NAME" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/$RIPGREP_LIBRARY_NAME" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/$NODE_GUARD_LIBRARY_NAME" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/$PYTHON_GUARD_LIBRARY_NAME" "$WORK_DIR/apk-entries.txt"
grep -Fx "lib/$ABI/$RIPGREP_GUARD_LIBRARY_NAME" "$WORK_DIR/apk-entries.txt"
for dependency in libcares.so libcrypto_3.so libicudata_78.so libicui18n_78.so libicuuc_78.so libsqlite3.so libssl_3.so libz_1.so libpython3.14.so libandroid-posix-semaphore.so libandroid-support.so libbz2_1_0.so libexpat_1.so libffi.so liblzma_5.so libncursesw_6.so libpanelw_6.so libzstd_1.so; do
  grep -Fx "lib/$ABI/$dependency" "$WORK_DIR/apk-entries.txt"
done
if [ "$(grep -Ec "^lib/$ABI/libpython_ext_[0-9]{3}\.so$" "$WORK_DIR/apk-entries.txt")" -ne "$PYTHON_PACKAGED_EXTENSION_COUNT" ]; then
  echo "APK does not contain the reviewed Python extension-module set." >&2
  exit 1
fi
for forbidden_apk_entry in \
    "lib/$ABI/libgdbm.so" \
    "lib/$ABI/libgdbm_compat.so" \
    "lib/$ABI/libreadline_8.so" \
    "lib/$ABI/libpython_ext_015.so" \
    "lib/$ABI/libpython_ext_018.so" \
    "lib/$ABI/libpython_ext_065.so"; do
  if grep -Fxq "$forbidden_apk_entry" "$WORK_DIR/apk-entries.txt"; then
    echo "APK contains a deliberately excluded Python/GNU payload: $forbidden_apk_entry" >&2
    exit 1
  fi
done
grep -Fx 'assets/third-party/codex/LICENSE' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/codex/NOTICE' "$WORK_DIR/apk-entries.txt"
for license_file in NODE-LICENSE CARES-LICENSE ICU-LICENSE OPENSSL-LICENSE ZLIB-LICENSE; do
  grep -Fx "assets/third-party/node/$license_file" "$WORK_DIR/apk-entries.txt"
done
grep -Fx 'assets/third-party/npm/NPM-LICENSES' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/python/PYTHON-LICENSES' "$WORK_DIR/apk-entries.txt"
for ripgrep_asset in DEPENDENCIES LICENSES PROVENANCE; do
  grep -Fx "assets/third-party/ripgrep/$ripgrep_asset" "$WORK_DIR/apk-entries.txt"
done
grep -Fx 'assets/third-party/toolchain/RUNTIME-MANIFEST' "$WORK_DIR/apk-entries.txt"
grep -Fx 'assets/third-party/toolchain/RUNTIME.zip' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/raw/third_party_notices.txt' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/raw/agentcodi_apache_2_0.txt' "$WORK_DIR/apk-entries.txt"
grep -Fx 'res/xml/locales_config.xml' "$WORK_DIR/apk-entries.txt"
unzip -p "$VERSIONED_APK" 'assets/third-party/toolchain/RUNTIME-MANIFEST' \
  > "$WORK_DIR/apk-runtime-manifest"
if ! cmp -s "$TOOL_RUNTIME_MANIFEST" "$WORK_DIR/apk-runtime-manifest"; then
  echo "APK tool-runtime manifest differs from the reviewed build output." >&2
  exit 1
fi
if grep -Eq 'lib-dynload/(_dbm|_gdbm|readline)\.cpython-' "$WORK_DIR/apk-runtime-manifest"; then
  echo "APK tool-runtime manifest references an excluded Python extension." >&2
  exit 1
fi
unzip -p "$VERSIONED_APK" 'assets/third-party/python/PYTHON-LICENSES' \
  > "$WORK_DIR/apk-python-licenses"
if ! cmp -s "$PYTHON_LICENSES" "$WORK_DIR/apk-python-licenses" \
    || grep -Fq 'GNU GENERAL PUBLIC LICENSE' "$WORK_DIR/apk-python-licenses" \
    || [ "$(wc -c < "$WORK_DIR/apk-python-licenses")" -gt 131072 ]; then
  echo "APK Python license inventory is stale, overbroad, or oversized." >&2
  exit 1
fi
for ripgrep_asset in DEPENDENCIES LICENSES PROVENANCE; do
  unzip -p "$VERSIONED_APK" "assets/third-party/ripgrep/$ripgrep_asset" \
    > "$WORK_DIR/apk-ripgrep-$ripgrep_asset"
  if ! cmp -s \
      "$RIPGREP_THIRD_PARTY_ASSETS/$ripgrep_asset" \
      "$WORK_DIR/apk-ripgrep-$ripgrep_asset"; then
    echo "APK ripgrep legal/provenance asset differs: $ripgrep_asset" >&2
    exit 1
  fi
done
if [ "$(wc -c < "$WORK_DIR/apk-ripgrep-LICENSES")" -gt 131072 ] \
    || grep -Eiq '(^|/)(pcre2|pcre2-sys)(/|$)' "$WORK_DIR/apk-entries.txt"; then
  echo "APK ripgrep license bundle is oversized or a PCRE2 payload was included." >&2
  exit 1
fi
unzip -p "$VERSIONED_APK" 'assets/third-party/toolchain/RUNTIME.zip' \
  > "$WORK_DIR/apk-runtime.zip"
zipinfo -1 "$WORK_DIR/apk-runtime.zip" > "$WORK_DIR/apk-runtime-entries.txt"
if grep -E '^python/.*\.(py|pyi|so)$' "$WORK_DIR/apk-runtime-entries.txt" \
    || ! grep -Fxq 'python/lib/python3.14/encodings/__init__.pyc' "$WORK_DIR/apk-runtime-entries.txt" \
    || ! grep -Fxq 'npm/node_modules/npm/bin/npm-cli.js' "$WORK_DIR/apk-runtime-entries.txt"; then
  echo "APK tool-runtime archive contains an unexpected source/native layout." >&2
  exit 1
fi
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
for runtime_spec in \
    "$NODE_LIBRARY_NAME:$NODE_RUNTIME_SHA256" \
    "$NODE_GUARD_LIBRARY_NAME:$NODE_GUARD_SHA256" \
    "$PYTHON_GUARD_LIBRARY_NAME:$PYTHON_GUARD_SHA256" \
    "$RIPGREP_GUARD_LIBRARY_NAME:$RIPGREP_GUARD_SHA256" \
    "libcares.so:$CARES_RUNTIME_SHA256" \
    "libcrypto_3.so:$CRYPTO_RUNTIME_SHA256" \
    "libicudata_78.so:$ICUDATA_RUNTIME_SHA256" \
    "libicui18n_78.so:$ICUI18N_RUNTIME_SHA256" \
    "libicuuc_78.so:$ICUUC_RUNTIME_SHA256" \
    "libsqlite3.so:$SQLITE_RUNTIME_SHA256" \
    "libssl_3.so:$SSL_RUNTIME_SHA256" \
    "libz_1.so:$ZLIB_RUNTIME_SHA256" \
    "$RIPGREP_LIBRARY_NAME:$RIPGREP_RUNTIME_SHA256"; do
  runtime_name="${runtime_spec%%:*}"
  expected_runtime_sha="${runtime_spec#*:}"
  packaged_runtime_sha="$(unzip -p "$VERSIONED_APK" "lib/$ABI/$runtime_name" | sha256sum | awk '{print $1}')"
  if [ "$packaged_runtime_sha" != "$expected_runtime_sha" ]; then
      echo "APK contains an unexpected pinned runtime file: $runtime_name" >&2
    exit 1
  fi
done
unzip -p "$VERSIONED_APK" classes.dex | strings > "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/MainActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/SettingsActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/TerminalActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/LicensesActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/McpManagementActivity;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AppLanguage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/app/AgentCodiApplication;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/UiLanguage;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CrashReportFormatter;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CredentialGuard;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexFileMention;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexWorkspaceAttachmentContext;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/TerminalOutputBuffer;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/TerminalSessionSnapshot;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexTerminalSession;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/ToolchainCommand;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexSessionController;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexModelOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexReasoningOption;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexInteractiveRequest;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/core/CodexApprovalDecision;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/mcp/McpCatalogSnapshot;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/mcp/client/McpCatalogLoader;' "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/mcp/client/McpCatalogController;' "$WORK_DIR/dex-strings.txt"
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
grep -Fq "$NODE_LIBRARY_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq "$NODE_VERSION" "$WORK_DIR/dex-strings.txt"
grep -Fq "$NPM_VERSION" "$WORK_DIR/dex-strings.txt"
grep -Fq "$PYTHON_VERSION" "$WORK_DIR/dex-strings.txt"
grep -Fq "$RIPGREP_LIBRARY_NAME" "$WORK_DIR/dex-strings.txt"
grep -Fq "$RIPGREP_VERSION" "$WORK_DIR/dex-strings.txt"
grep -Fq 'Lde/agentcodi/storage/PackagedToolRuntime;' "$WORK_DIR/dex-strings.txt"
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
