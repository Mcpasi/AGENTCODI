#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -P -- "$SCRIPT_DIR/.." && pwd -P)

usage() {
  cat <<'EOF'
Usage: ./scripts/bump-version.sh [MAJOR.MINOR.PATCH]

Without an argument, increment the current patch version. An explicit version
must be greater than the current version. The Android versionCode is always
incremented by one. Documentation is intentionally not changed.
EOF
}

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

case $# in
  0) requested_version= ;;
  1)
    case $1 in
      -h|--help)
        usage
        exit 0
        ;;
    esac
    requested_version=$1
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

build_file="$PROJECT_ROOT/scripts/build-debug-apk.sh"
[ -f "$build_file" ] || fail "Missing build identity: scripts/build-debug-apk.sh"

current_version=$(awk -F '"' '
  /^APP_VERSION="[^"]+"$/ {
    count++
    value = $2
  }
  END {
    if (count != 1) {
      exit 1
    }
    print value
  }
' "$build_file") || fail "Expected exactly one APP_VERSION in scripts/build-debug-apk.sh."

current_version_code=$(awk -F '"' '
  /^VERSION_CODE="[0-9]+"$/ {
    count++
    value = $2
  }
  END {
    if (count != 1) {
      exit 1
    }
    print value
  }
' "$build_file") || fail "Expected exactly one VERSION_CODE in scripts/build-debug-apk.sh."

is_version() {
  printf '%s\n' "$1" \
    | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
}

is_version "$current_version" \
  || fail "Current APP_VERSION is not a plain MAJOR.MINOR.PATCH version: $current_version"

saved_ifs=$IFS
IFS=.
set -- $current_version
IFS=$saved_ifs
current_major=$1
current_minor=$2
current_patch=$3

for version_component in "$current_major" "$current_minor" "$current_patch"; do
  [ "$version_component" -le 2147483647 ] \
    || fail "Version components must not exceed 2147483647."
done

if [ -z "$requested_version" ]; then
  [ "$current_patch" -lt 2147483647 ] \
    || fail "The patch component cannot be incremented further."
  target_version="$current_major.$current_minor.$((current_patch + 1))"
else
  is_version "$requested_version" \
    || fail "Target version must use plain MAJOR.MINOR.PATCH form: $requested_version"
  target_version=$requested_version
fi

IFS=.
set -- $target_version
IFS=$saved_ifs
target_major=$1
target_minor=$2
target_patch=$3

for version_component in "$target_major" "$target_minor" "$target_patch"; do
  [ "$version_component" -le 2147483647 ] \
    || fail "Version components must not exceed 2147483647."
done

if [ "$target_major" -lt "$current_major" ] \
    || { [ "$target_major" -eq "$current_major" ] \
      && [ "$target_minor" -lt "$current_minor" ]; } \
    || { [ "$target_major" -eq "$current_major" ] \
      && [ "$target_minor" -eq "$current_minor" ] \
      && [ "$target_patch" -le "$current_patch" ]; }; then
  fail "Target version must be greater than $current_version: $target_version"
fi

case $current_version_code in
  ''|0|*[!0-9]*|0*)
    fail "Current VERSION_CODE is not a positive decimal integer: $current_version_code"
    ;;
esac
[ "$current_version_code" -lt 2100000000 ] \
  || fail "Android versionCode cannot be incremented beyond 2100000000."
target_version_code=$((current_version_code + 1))

managed_files='
app/src/main/AndroidManifest.xml
scripts/build-debug-apk.sh
scripts/check-architecture.sh
modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java
tests/java/de/agentcodi/tests/BuildIdentityTest.java
modules/native-engine/src/main/cpp/agentcodi_engine.cpp
tests/cpp/agentcodi_engine_test.cpp
tests/cpp/android_app_server_bootstrap_smoke.cpp
'

temporary_base=${TMPDIR:-/tmp}
staging_root=$(mktemp -d "$temporary_base/agentcodi-version.XXXXXX") \
  || fail "Could not create a temporary staging directory."
umask 077

cleanup() {
  for cleanup_relative in $managed_files; do
    rm -f "$PROJECT_ROOT/$cleanup_relative.version-bump.$$"
  done
  case $staging_root in
    "$temporary_base"/agentcodi-version.*)
      rm -rf "$staging_root"
      ;;
  esac
}
trap cleanup 0
trap 'exit 1' 1 2 15

stage_file() {
  stage_relative=$1
  stage_source="$PROJECT_ROOT/$stage_relative"
  stage_target="$staging_root/$stage_relative"
  [ -f "$stage_source" ] || fail "Missing managed version file: $stage_relative"
  mkdir -p "$(dirname -- "$stage_target")"
  cp -p "$stage_source" "$stage_target"
}

replace_literal() {
  replacement_relative=$1
  replacement_old=$2
  replacement_new=$3
  replacement_expected=$4
  replacement_file="$staging_root/$replacement_relative"
  replacement_next="$replacement_file.next"

  replacement_actual=$(
    AGENTCODI_VERSION_NEEDLE=$replacement_old awk '
      BEGIN {
        needle = ENVIRON["AGENTCODI_VERSION_NEEDLE"]
        if (length(needle) == 0) {
          exit 2
        }
      }
      {
        remaining = $0
        while ((position = index(remaining, needle)) != 0) {
          count++
          remaining = substr(remaining, position + length(needle))
        }
      }
      END {
        print count + 0
      }
    ' "$replacement_file"
  ) || fail "Could not inspect managed version file: $replacement_relative"

  [ "$replacement_actual" -eq "$replacement_expected" ] || fail \
    "Refusing an inconsistent bump in $replacement_relative: expected $replacement_expected occurrence(s), found $replacement_actual."

  AGENTCODI_VERSION_NEEDLE=$replacement_old \
  AGENTCODI_VERSION_REPLACEMENT=$replacement_new \
    awk '
      BEGIN {
        needle = ENVIRON["AGENTCODI_VERSION_NEEDLE"]
        replacement = ENVIRON["AGENTCODI_VERSION_REPLACEMENT"]
      }
      {
        remaining = $0
        output = ""
        while ((position = index(remaining, needle)) != 0) {
          output = output substr(remaining, 1, position - 1) replacement
          remaining = substr(remaining, position + length(needle))
        }
        print output remaining
      }
    ' "$replacement_file" > "$replacement_next"
  cp "$replacement_next" "$replacement_file"
  rm -f "$replacement_next"
}

for managed_relative in $managed_files; do
  stage_file "$managed_relative"
done

replace_literal \
  'app/src/main/AndroidManifest.xml' \
  "android:versionName=\"$current_version\"" \
  "android:versionName=\"$target_version\"" \
  1
replace_literal \
  'app/src/main/AndroidManifest.xml' \
  "android:versionCode=\"$current_version_code\"" \
  "android:versionCode=\"$target_version_code\"" \
  1

replace_literal \
  'scripts/build-debug-apk.sh' \
  "APP_VERSION=\"$current_version\"" \
  "APP_VERSION=\"$target_version\"" \
  1
replace_literal \
  'scripts/build-debug-apk.sh' \
  "VERSION_CODE=\"$current_version_code\"" \
  "VERSION_CODE=\"$target_version_code\"" \
  1

escaped_current_version=$(printf '%s\n' "$current_version" | sed 's/[.]/\\./g')
escaped_target_version=$(printf '%s\n' "$target_version" | sed 's/[.]/\\./g')
replace_literal \
  'scripts/check-architecture.sh' \
  "VERSION_NAME = \"$escaped_current_version\"" \
  "VERSION_NAME = \"$escaped_target_version\"" \
  1
replace_literal \
  'scripts/check-architecture.sh' \
  "android:versionName=\"$escaped_current_version\"" \
  "android:versionName=\"$escaped_target_version\"" \
  1
replace_literal \
  'scripts/check-architecture.sh' \
  "APP_VERSION=\"$escaped_current_version\"" \
  "APP_VERSION=\"$escaped_target_version\"" \
  1
replace_literal \
  'scripts/check-architecture.sh' \
  "VERSION_CODE = $current_version_code" \
  "VERSION_CODE = $target_version_code" \
  1
replace_literal \
  'scripts/check-architecture.sh' \
  "android:versionCode=\"$current_version_code\"" \
  "android:versionCode=\"$target_version_code\"" \
  1
replace_literal \
  'scripts/check-architecture.sh' \
  "VERSION_CODE=\"$current_version_code\"" \
  "VERSION_CODE=\"$target_version_code\"" \
  1
replace_literal \
  'scripts/check-architecture.sh' \
  "The $current_version /" \
  "The $target_version /" \
  1

replace_literal \
  'modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java' \
  "VERSION_NAME = \"$current_version\"" \
  "VERSION_NAME = \"$target_version\"" \
  1
replace_literal \
  'modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java' \
  "VERSION_CODE = $current_version_code;" \
  "VERSION_CODE = $target_version_code;" \
  1

replace_literal \
  'tests/java/de/agentcodi/tests/BuildIdentityTest.java' \
  "assertEquals(\"$current_version\", BuildIdentity.VERSION_NAME" \
  "assertEquals(\"$target_version\", BuildIdentity.VERSION_NAME" \
  1
replace_literal \
  'tests/java/de/agentcodi/tests/BuildIdentityTest.java' \
  "assertEquals($current_version_code, BuildIdentity.VERSION_CODE" \
  "assertEquals($target_version_code, BuildIdentity.VERSION_CODE" \
  1

replace_literal \
  'modules/native-engine/src/main/cpp/agentcodi_engine.cpp' \
  "agentcodi-native/$current_version" \
  "agentcodi-native/$target_version" \
  1
replace_literal \
  'tests/cpp/agentcodi_engine_test.cpp' \
  "agentcodi-native/$current_version" \
  "agentcodi-native/$target_version" \
  1
replace_literal \
  'tests/cpp/android_app_server_bootstrap_smoke.cpp' \
  "$current_version" \
  "$target_version" \
  2

for managed_relative in $managed_files; do
  managed_source="$PROJECT_ROOT/$managed_relative"
  managed_staged="$staging_root/$managed_relative"
  managed_commit="$managed_source.version-bump.$$"
  cmp "$managed_source" "$managed_staged" >/dev/null 2>&1 \
    && fail "Managed version file did not change: $managed_relative"
  cp -p "$managed_staged" "$managed_commit"
done

for managed_relative in $managed_files; do
  managed_source="$PROJECT_ROOT/$managed_relative"
  managed_commit="$managed_source.version-bump.$$"
  mv -f "$managed_commit" "$managed_source"
done

printf 'AGENTCODI version: %s -> %s\n' "$current_version" "$target_version"
printf 'Android versionCode: %s -> %s\n' "$current_version_code" "$target_version_code"
printf '%s\n' 'Documentation was not changed.'
