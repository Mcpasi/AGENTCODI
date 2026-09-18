#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -P -- "$SCRIPT_DIR/.." && pwd -P)

usage() {
  cat <<'EOF'
Usage: ./scripts/bump-version.sh [MAJOR.MINOR.PATCH[-LABEL[.COUNTER]]]

Without an argument, continue the current line: the pre-release counter of a
pre-release version (0.8.0-preview.1 -> 0.8.0-preview.2), otherwise the patch
component (0.7.5 -> 0.7.6).

An explicit version must be greater than the current one. Ordering follows
semantic versioning, so a pre-release ranks below its own release:

  ./scripts/bump-version.sh 0.8.0-preview.1   open a pre-release line
  ./scripts/bump-version.sh 0.8.0-preview.2   stay on the 0.8.0 line
  ./scripts/bump-version.sh 0.8.0-stable      close that line as plain 0.8.0
  ./scripts/bump-version.sh 0.8.1-preview.1   open the next line

A label is lowercase alphanumeric and starts with a letter; -stable is a marker
only and is never written out, it resolves to the plain MAJOR.MINOR.PATCH
version. The Android versionCode is always incremented by one, whether or not
the MAJOR.MINOR.PATCH line itself changes. Documentation is intentionally not
changed.
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
    | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[a-z][a-z0-9]*(\.(0|[1-9][0-9]*))?)?$'
}

# Splits a validated version into version_major, version_minor, version_patch,
# version_label and version_counter. The last two are empty for a release.
parse_version() {
  parse_input=$1
  case $parse_input in
    *-*)
      parse_base=${parse_input%%-*}
      parse_pre=${parse_input#*-}
      ;;
    *)
      parse_base=$parse_input
      parse_pre=
      ;;
  esac

  parse_saved_ifs=$IFS
  IFS=.
  set -- $parse_base
  IFS=$parse_saved_ifs
  version_major=$1
  version_minor=$2
  version_patch=$3

  version_label=
  version_counter=
  case $parse_pre in
    '') ;;
    *.*)
      version_label=${parse_pre%%.*}
      version_counter=${parse_pre#*.}
      ;;
    *)
      version_label=$parse_pre
      ;;
  esac

  for version_component in \
      "$version_major" "$version_minor" "$version_patch" \
      ${version_counter:+"$version_counter"}; do
    [ "$version_component" -le 2147483647 ] \
      || fail "Version components must not exceed 2147483647: $parse_input"
  done
}

# Prints -1, 0 or 1 for the precedence of the first version against the second.
version_order() {
  AGENTCODI_VERSION_LEFT=$1 \
  AGENTCODI_VERSION_RIGHT=$2 \
    awk '
      function parse(text, out,   dash, base, pre, dot, numbers) {
        dash = index(text, "-")
        if (dash == 0) {
          base = text
          pre = ""
        } else {
          base = substr(text, 1, dash - 1)
          pre = substr(text, dash + 1)
        }
        split(base, numbers, ".")
        out["major"] = numbers[1] + 0
        out["minor"] = numbers[2] + 0
        out["patch"] = numbers[3] + 0
        out["release"] = (pre == "") ? 1 : 0
        out["label"] = ""
        out["counter"] = -1
        if (pre != "") {
          dot = index(pre, ".")
          if (dot == 0) {
            out["label"] = pre
          } else {
            out["label"] = substr(pre, 1, dot - 1)
            out["counter"] = substr(pre, dot + 1) + 0
          }
        }
      }
      function rank(left_value, right_value) {
        if (left_value == right_value) {
          return 0
        }
        return (left_value < right_value) ? -1 : 1
      }
      BEGIN {
        parse(ENVIRON["AGENTCODI_VERSION_LEFT"], left)
        parse(ENVIRON["AGENTCODI_VERSION_RIGHT"], right)
        order = rank(left["major"], right["major"])
        if (order == 0) {
          order = rank(left["minor"], right["minor"])
        }
        if (order == 0) {
          order = rank(left["patch"], right["patch"])
        }
        if (order == 0) {
          order = rank(left["release"], right["release"])
        }
        if (order == 0) {
          order = rank(left["label"], right["label"])
        }
        if (order == 0) {
          order = rank(left["counter"], right["counter"])
        }
        print order
      }
    '
}

is_version "$current_version" \
  || fail "Current APP_VERSION is not a supported version: $current_version"

parse_version "$current_version"
current_major=$version_major
current_minor=$version_minor
current_patch=$version_patch
current_label=$version_label
current_counter=$version_counter

if [ -z "$requested_version" ]; then
  if [ -n "$current_label" ]; then
    [ -n "$current_counter" ] || fail \
      "The pre-release line $current_version carries no counter to increment. Pass an explicit target version."
    [ "$current_counter" -lt 2147483647 ] \
      || fail "The pre-release counter cannot be incremented further."
    target_version="$current_major.$current_minor.$current_patch-$current_label.$((current_counter + 1))"
  else
    [ "$current_patch" -lt 2147483647 ] \
      || fail "The patch component cannot be incremented further."
    target_version="$current_major.$current_minor.$((current_patch + 1))"
  fi
else
  case $requested_version in
    *-stable.*)
      fail "The -stable marker does not take a counter: $requested_version"
      ;;
    *-stable)
      requested_version=${requested_version%-stable}
      ;;
  esac
  is_version "$requested_version" \
    || fail "Target version must use MAJOR.MINOR.PATCH[-LABEL[.COUNTER]] form: $requested_version"
  target_version=$requested_version
fi

parse_version "$target_version"
target_major=$version_major
target_minor=$version_minor
target_patch=$version_patch

current_line="$current_major.$current_minor.$current_patch"
target_line="$target_major.$target_minor.$target_patch"

[ "$(version_order "$target_version" "$current_version")" -gt 0 ] \
  || fail "Target version must be greater than $current_version: $target_version"

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
if [ "$current_line" = "$target_line" ]; then
  printf 'Version line: %s (unchanged, versionCode only)\n' "$target_line"
else
  printf 'Version line: %s -> %s\n' "$current_line" "$target_line"
fi
printf '%s\n' 'Documentation was not changed.'
