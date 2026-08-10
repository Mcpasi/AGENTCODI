#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

unexpected_source="$(find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java" "$PROJECT_ROOT/modules/runtime/src/main/java" "$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/tests/java" "$PROJECT_ROOT/tests/cpp" -type f ! -name '*.java' ! -name '*.cpp' ! -name '*.h' -print)"
if [ -n "$unexpected_source" ]; then
  echo "Only Java and C++ source files are accepted in source roots." >&2
  printf '%s\n' "$unexpected_source" >&2
  exit 1
fi

if find "$PROJECT_ROOT/app" "$PROJECT_ROOT/modules" "$PROJECT_ROOT/tests" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.js' -o -name '*.ts' -o -name '*.dart' -o -name '*.rs' \) -print -quit | grep -q .; then
  echo "Unsupported application source language detected." >&2
  exit 1
fi

if rg -n '^import android\.' "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java"; then
  echo "Pure Java modules must not import Android APIs." >&2
  exit 1
fi

if rg -n '^import de\.agentcodi\.storage\.' "$PROJECT_ROOT/app/src/main/java"; then
  echo "The app module must reach storage only through the runtime facade." >&2
  exit 1
fi

if rg -n '^import android\.webkit' "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules"; then
  echo "The native UI boundary must not depend on WebView." >&2
  exit 1
fi

if rg -n 'new[[:space:]]+ProcessBuilder|Runtime\.getRuntime\(\)\.exec' "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" --glob '*.java'; then
  echo "Child processes must be owned by the C++ process supervisor." >&2
  exit 1
fi

if rg -n '^import (java\.net\.ServerSocket|com\.sun\.net\.httpserver)|WebSocketServer' "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" --glob '*.java'; then
  echo "The native app-server client must not open an HTTP/WebSocket listener." >&2
  exit 1
fi

native_declarations="$(rg -l '^[[:space:]]*(private|protected|public)?[[:space:]]+(static[[:space:]]+)?native[[:space:]]+' "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules" --glob '*.java' || true)"
expected_native="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeEngine.java"
if [ "$native_declarations" != "$expected_native" ]; then
  echo "JNI declarations must exist only in NativeEngine.java." >&2
  printf '%s\n' "$native_declarations" >&2
  exit 1
fi

if rg -n 'System\.loadLibrary' "$PROJECT_ROOT" --glob '*.java' | grep -v '/modules/runtime/src/main/java/de/agentcodi/runtime/NativeEngine.java:'; then
  echo "Native library loading escaped the runtime gateway." >&2
  exit 1
fi

if rg -n 'startChatGptLogin|startApiKeyLogin|TYPE_TEXT_VARIATION_PASSWORD' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java"; then
  echo "Authentication controls belong in SettingsActivity, not the chat surface." >&2
  exit 1
fi

if ! rg -q 'startChatGptLogin' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/SettingsActivity.java" \
    || ! rg -q 'startApiKeyLogin' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/SettingsActivity.java" \
    || ! rg -q 'ListView' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'selectModel' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'selectReasoningEffort' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java"; then
  echo "Chat navigation, settings authentication, and model selectors are incomplete." >&2
  exit 1
fi

if rg -n 'readOnlyAccess|sandboxPolicy' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java"; then
  echo "Legacy app-server read-access fields must not return to turn requests." >&2
  exit 1
fi

if rg -n 'approvalPolicy", "never"' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || rg -n 'approval_policy=\\"never\\"' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp"; then
  echo "The native interactive flow must not be disabled by the old never-approval policy." >&2
  exit 1
fi

if ! rg -q 'item/commandExecution/requestApproval' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/fileChange/requestApproval' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/fileChange/patchUpdated' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/tool/requestUserInput' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'InteractiveRequestDialog' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'InteractiveRequestDialog' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/SettingsActivity.java"; then
  echo "Native approval and user-input routing is incomplete." >&2
  exit 1
fi

approval_dialog="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/InteractiveRequestDialog.java"
if rg -q '\.setItems\(' "$approval_dialog" \
    || ! rg -q 'setPositiveButton\([[:space:]]*$' "$approval_dialog" \
    || ! rg -q '"Erlauben"' "$approval_dialog" \
    || ! rg -q 'setNegativeButton\([[:space:]]*$' "$approval_dialog" \
    || ! rg -q '"Ablehnen"' "$approval_dialog" \
    || ! rg -q '"Turn stoppen"' "$approval_dialog"; then
  echo "Approval details must keep explicit allow and decline buttons visible." >&2
  exit 1
fi

if ! rg -q 'CODEX_CODE_MODE_HOST_PATH' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'CODEX_CODE_MODE_HOST_LIBRARY' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || ! rg -q 'libcodex-codehost\.so' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java"; then
  echo "The packaged code-mode host is not wired through the native supervisor." >&2
  exit 1
fi

echo "Architecture checks passed."
