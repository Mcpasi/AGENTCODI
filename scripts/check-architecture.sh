#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

unexpected_source="$(find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java" "$PROJECT_ROOT/modules/import-contracts/src/main/java" "$PROJECT_ROOT/modules/import-client/src/main/java" "$PROJECT_ROOT/modules/mcp-contracts/src/main/java" "$PROJECT_ROOT/modules/mcp-client/src/main/java" "$PROJECT_ROOT/modules/runtime/src/main/java" "$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/tests/java" "$PROJECT_ROOT/tests/cpp" -type f ! -name '*.java' ! -name '*.cpp' ! -name '*.h' -print)"
if [ -n "$unexpected_source" ]; then
  echo "Only Java and C++ source files are accepted in source roots." >&2
  printf '%s\n' "$unexpected_source" >&2
  exit 1
fi

if find "$PROJECT_ROOT/app" "$PROJECT_ROOT/modules" "$PROJECT_ROOT/tests" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.js' -o -name '*.ts' -o -name '*.dart' -o -name '*.rs' \) -print -quit | grep -q .; then
  echo "Unsupported application source language detected." >&2
  exit 1
fi

if rg -n '^import android\.' "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java" "$PROJECT_ROOT/modules/import-contracts/src/main/java" "$PROJECT_ROOT/modules/import-client/src/main/java" "$PROJECT_ROOT/modules/mcp-contracts/src/main/java" "$PROJECT_ROOT/modules/mcp-client/src/main/java"; then
  echo "Pure Java modules must not import Android APIs." >&2
  exit 1
fi

mcp_contracts="$PROJECT_ROOT/modules/mcp-contracts/src/main/java"
mcp_client="$PROJECT_ROOT/modules/mcp-client/src/main/java"
import_contracts="$PROJECT_ROOT/modules/import-contracts/src/main/java"
import_client="$PROJECT_ROOT/modules/import-client/src/main/java"
manifest="$PROJECT_ROOT/app/src/main/AndroidManifest.xml"
if rg -n '^import de\.agentcodi\.(core|storage|runtime|app|mcp|imports\.client)\.' "$import_contracts" \
    || rg -n '^import de\.agentcodi\.(runtime|app|mcp)\.' "$import_client" \
    || rg -n '^import de\.agentcodi\.imports\.client\.' "$PROJECT_ROOT/app/src/main/java" \
    || rg -n '^import de\.agentcodi\.imports\.' "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java"; then
  echo "Import contracts, client dependencies, or UI/runtime boundaries are invalid." >&2
  exit 1
fi
if rg -n '^import de\.agentcodi\.(core|mcp\.client|runtime|storage|app)\.' "$mcp_contracts" \
    || rg -n '^import de\.agentcodi\.(runtime|storage|app)\.' "$mcp_client" \
    || rg -n '^import de\.agentcodi\.mcp\.' "$PROJECT_ROOT/modules/core/src/main/java" \
    || rg -n 'java\.(io\.File|nio\.file)|get\("(path|marketplacePath|inputSchema|outputSchema)"\)' "$mcp_client"; then
  echo "MCP contracts, client dependencies, or opaque-path boundaries are invalid." >&2
  exit 1
fi
if ! rg -q '"experimentalFeature/list"' "$mcp_client" \
    || ! rg -q '"skills/list"' "$mcp_client" \
    || ! rg -q '"mcpServerStatus/list"' "$mcp_client" \
    || ! rg -q '"app/installed"' "$mcp_client" \
    || ! rg -q '"app/read"' "$mcp_client" \
    || ! rg -q '"plugin/list"' "$mcp_client" \
    || rg -n '"(config/read|config/value/write|config/batchWrite|mcpServer/tool/call|mcpServer/oauth/login|plugin/install|plugin/uninstall|marketplace/add|marketplace/remove|marketplace/upgrade)"' "$mcp_client" \
    || ! rg -q 'forceReload", Boolean\.FALSE' "$mcp_client" \
    || ! rg -q 'forceRefetch", Boolean\.FALSE' "$mcp_client" \
    || ! rg -q 'marketplaceKinds", JsonCodec\.array\("local"\)' "$mcp_client"; then
  echo "The MCP capability catalog must remain an app-server-owned, read-only projection." >&2
  exit 1
fi

mcp_rpc="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexMcpConfigurationRpc.java"
mcp_session="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java"
mcp_validator="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexMcpConfigurationRequestValidator.java"
if ! rg -q 'CodexMcpConfigurationRpc' "$mcp_client" \
    || ! rg -q 'McpConfigurationController' "$mcp_client" \
    || ! rg -q '"config/read"' "$mcp_session" \
    || ! rg -q '"config/batchWrite"' "$mcp_session" \
    || ! rg -q '"config/mcpServer/reload"' "$mcp_session" \
    || rg -n '"config/value/write"' "$PROJECT_ROOT/modules" "$PROJECT_ROOT/app/src/main/java" \
    || ! rg -q 'isValidWriteRequest' "$mcp_rpc" \
    || ! rg -q '!Boolean\.FALSE\.equals\(parameters\.get\("reloadUserConfig"\)\)' "$mcp_validator" \
    || ! rg -q 'parameters\.containsKey\("filePath"\)' "$mcp_validator" \
    || ! rg -q 'promptServers\.containsAll\(enabledServers\)' "$mcp_validator" \
    || ! rg -q 'clearedToolApprovalOverrides\.containsAll\(enabledServers\)' "$mcp_validator" \
    || ! rg -q '!"prompt"\.equals\(server\.get\("default_tools_approval_mode"\)\)' "$mcp_validator" \
    || ! rg -q 'CredentialGuard\.containsLikelyCredential\(values\)' "$mcp_validator" \
    || ! rg -q 'CredentialGuard\.containsLikelyCredential\(values\)' "$mcp_client/de/agentcodi/mcp/client/McpConfigurationLoader.java" \
    || ! rg -q 'CredentialGuard\.isLikelyCredentialName\(key\)' "$mcp_client/de/agentcodi/mcp/client/McpConfigurationLoader.java" \
    || ! rg -q 'MAX_PROJECTED_CHARACTERS = CodexAppServerClient\.MAX_INCOMING_BYTES' "$mcp_client/de/agentcodi/mcp/client/McpConfigurationLoader.java" \
    || ! rg -q '"client_secret"' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CredentialGuard.java" \
    || ! rg -q '"password"' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CredentialGuard.java" \
    || ! rg -q 'edit\(prefix \+ "tools", null\)' "$mcp_client" \
    || ! rg -q 'name \+ "\.tools", null' "$mcp_client" \
    || ! rg -q '"mergeStrategy", "replace"' "$mcp_client" \
    || ! rg -q '"reloadUserConfig", Boolean\.FALSE' "$mcp_client" \
    || rg -n '"filePath"' "$mcp_client"; then
  echo "MCP configuration must use only the typed, path-free, validated app-server RPC boundary." >&2
  exit 1
fi

mcp_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/McpManagementActivity.java"
mcp_editor="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/McpServerEditorDialog.java"
if ! rg -Uq 'android:name="\.McpManagementActivity"[[:space:][:print:]]{0,220}android:exported="false"' "$manifest" \
    || ! rg -q 'McpManagementActivity' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/SettingsActivity.java" \
    || ! rg -q 'mcpCatalogSnapshot' "$mcp_activity" \
    || ! rg -q 'refreshMcpCatalog' "$mcp_activity" \
    || ! rg -q 'mcpConfigurationSnapshot' "$mcp_activity" \
    || ! rg -q 'saveMcpServer' "$mcp_activity" \
    || ! rg -q 'reloadMcpConfiguration' "$mcp_activity" \
    || ! rg -q 'hasToolApprovalOverrides' "$mcp_activity" \
    || ! rg -q 'snapshot\.getPhase\(\) != McpConfigurationPhase\.READY' "$mcp_activity" \
    || ! rg -q 'enabled\.setEnabled\(false\)' "$mcp_editor" \
    || ! rg -q 'McpServerDraft\.parseLines\(argumentText\)' "$mcp_editor" \
    || rg -q 'LinkedHashSet' "$mcp_editor" \
    || rg -n '(config/read|mcpServer/tool/call|oauth|plugin/install|marketplace/add)' "$mcp_activity"; then
  echo "The native MCP activity must remain non-exported and use only runtime facades." >&2
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

apk_builder="$PROJECT_ROOT/scripts/build-debug-apk.sh"
release_builder="$PROJECT_ROOT/scripts/build-release-apk.sh"
if rg -q 'android:debuggable="true"' "$manifest" \
    || ! rg -q 'android:debuggable="false"' "$manifest"; then
  echo "Every AGENTCODI APK must be explicitly non-debuggable." >&2
  exit 1
fi
if [ ! -x "$release_builder" ] \
    || ! rg -Fq 'AGENTCODI_BUILD_VARIANT=release' "$release_builder" \
    || ! rg -Fq 'AGENTCODI_RELEASE_KEYSTORE' "$apk_builder" \
    || ! rg -Fq 'AGENTCODI_RELEASE_PASSWORD_MODE' "$apk_builder" \
    || ! rg -Fq 'AGENTCODI_RELEASE_STORE_PASSWORD_FILE' "$apk_builder" \
    || ! rg -Fq 'AGENTCODI_RELEASE_KEY_PASSWORD_FILE' "$apk_builder" \
    || ! rg -Fq 'AGENTCODI_RELEASE_CERT_SHA256' "$apk_builder" \
    || ! rg -Fq -- '--ks-pass "file:$RELEASE_STORE_PASSWORD_FILE"' "$apk_builder" \
    || ! rg -Fq -- '--key-pass "file:$RELEASE_KEY_PASSWORD_FILE"' "$apk_builder" \
    || ! rg -Fq 'must remain outside the project tree.' "$apk_builder" \
    || ! rg -Fq 'must not be hard-linked.' "$apk_builder" \
    || ! rg -Fq 'Interactive release password mode requires a terminal.' "$apk_builder" \
    || ! rg -Fq 'application-debuggable' "$apk_builder" \
    || ! rg -Fq 'Release signer certificate does not match AGENTCODI_RELEASE_CERT_SHA256.' "$apk_builder" \
    || ! rg -Fq 'Release APK must not use an Android debug certificate.' "$apk_builder"; then
  echo "Non-debuggable APK and external release-signing gates are incomplete." >&2
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

rate_limits_snapshot="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexRateLimitsSnapshot.java"
rate_limit_window="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexRateLimitWindow.java"
if ! rg -q '"account/rateLimits/read"' "$mcp_session" \
    || ! rg -q '"account/rateLimits/updated"' "$mcp_session" \
    || ! rg -q 'rateLimitsRefreshQueued' "$mcp_session" \
    || ! rg -q 'usedPercent < 0 \|\| usedPercent > 100' "$rate_limit_window" \
    || ! rg -q 'CodexRateLimitsSnapshot getRateLimits' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionSnapshot.java" \
    || ! rg -q 'formatRateLimits' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/SettingsActivity.java" \
    || ! rg -q 'account/rateLimits/updated' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || rg -n 'account/(rateLimitResetCredit/consume|sendAddCreditsNudgeEmail)' \
      "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules"; then
  echo "Rate limits must remain a bounded, read-only app-server projection." >&2
  exit 1
fi

if rg -n 'readOnlyAccess|sandboxPolicy' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java"; then
  echo "Legacy app-server read-access fields must not return to turn requests." >&2
  exit 1
fi

if ! rg -q '"turn/steer"' "$mcp_session" \
    || ! rg -q '"expectedTurnId"' "$mcp_session" \
    || ! rg -q 'controller\.steerTurn' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || ! rg -q 'AgentRuntimeService\.steerTurn' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -Fq 'composerInput.setEnabled(composerReady)' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'turn/steer has only its supported fields' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q -- '--turn-steer-roundtrip' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp"; then
  echo "Correlated active-turn steering is incomplete." >&2
  exit 1
fi

main_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java"
workspace_importer="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/WorkspaceFileImporter.java"
document_importer="$import_client/de/agentcodi/imports/client/WorkspaceDocumentImporter.java"
import_limits="$import_contracts/de/agentcodi/imports/WorkspaceImportLimits.java"
imported_file="$import_contracts/de/agentcodi/imports/ImportedWorkspaceFile.java"
attachment_context="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexWorkspaceAttachmentContext.java"
storage_layout="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java"
if ! rg -q 'Intent\.ACTION_OPEN_DOCUMENT' "$main_activity" \
    || ! rg -q 'Intent\.CATEGORY_OPENABLE' "$main_activity" \
    || ! rg -q 'Intent\.EXTRA_ALLOW_MULTIPLE' "$main_activity" \
    || ! rg -q 'Intent\.FLAG_GRANT_READ_URI_PERMISSION' "$main_activity" \
    || rg -q 'takePersistableUriPermission|ACTION_OPEN_DOCUMENT_TREE' "$main_activity" "$workspace_importer" \
    || ! rg -q 'ContentResolver\.SCHEME_CONTENT' "$workspace_importer" \
    || ! rg -q 'WorkspaceLayout\.create' "$workspace_importer" \
    || ! rg -q 'NativeWorkspaceFileAccess\.opener' "$workspace_importer" \
    || ! rg -q 'SecureDirectoryStream' "$document_importer" \
    || ! rg -q 'StandardOpenOption\.CREATE_NEW' "$document_importer" \
    || ! rg -q 'LinkOption\.NOFOLLOW_LINKS' "$document_importer" \
    || ! rg -q 'importRoot\.move' "$document_importer" \
    || ! rg -q 'OWNER_READ' "$document_importer" \
    || ! rg -q 'OWNER_WRITE' "$document_importer" \
    || ! rg -q 'MessageDigest\.getInstance\("SHA-256"\)' "$document_importer" \
    || ! rg -q 'MessageDigest\.isEqual' "$document_importer" \
    || ! rg -q 'token \+ storageExtension' "$document_importer" \
    || ! rg -q 'safeStorageExtension' "$document_importer" \
    || ! rg -q 'getSha256' "$imported_file" "$document_importer" \
    || ! rg -q 'MAXIMUM_FILES_PER_MESSAGE = 16' "$import_limits" \
    || ! rg -q 'MAXIMUM_FILE_BYTES = 512L \* 1024L \* 1024L' "$import_limits" \
    || ! rg -q 'MAXIMUM_TOTAL_BYTES = 1024L \* 1024L \* 1024L' "$import_limits" \
    || rg -q 'android\.net\.Uri|content://' "$imported_file" "$document_importer" \
    || ! rg -q 'getImports' "$storage_layout" \
    || ! rg -q '"type", "mention"' "$mcp_session" \
    || ! rg -q '"additionalContext", attachmentContext' "$mcp_session" \
    || ! rg -q 'CONTEXT_KIND = "application"' "$attachment_context" \
    || ! rg -q "Read the file's actual bytes with the workspace tools" "$attachment_context" \
    || ! rg -q 'workspace \+ "/imports/"' "$mcp_session" \
    || ! rg -q 'isGeneratedImportStorageName' "$mcp_session" \
    || ! rg -q 'sendsImportedFilesWithModelReadableContext' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'WorkspaceImportTest\.run' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/TestMain.java" \
    || ! rg -q 'model-readable storage path contains only randomness and a safe extension' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'same-size content replacement cannot enter a Codex turn' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q -- '--turn-import-roundtrip' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp"; then
  echo "The bounded in-chat workspace import, mention, or model-readable context path is incomplete." >&2
  exit 1
fi

if rg -n 'approvalPolicy", "never"' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || rg -n 'approval_policy=\\"never\\"' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp"; then
  echo "The native interactive flow must not be disabled by the old never-approval policy." >&2
  exit 1
fi

if rg -n 'projects\..*trust_level' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp"; then
  echo "Pinned Codex 0.147.2 rejects project trust CLI overrides under strict config." >&2
  exit 1
fi

if rg -n 'rejectRuntimePolicyFiles|reject_codex_policy_files|Codex runtime policy must be supplied' \
    "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java" \
    "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp"; then
  echo "Normal private Codex configuration files must not be rejected as foreign policy." >&2
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

if ! rg -q 'item/reasoning/summaryTextDelta' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/reasoning/textDelta' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/plan/delta' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/commandExecution/outputDelta' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/commandExecution/terminalInteraction' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'item/mcpToolCall/progress' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'getTranscriptItems' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java"; then
  echo "Reasoning, plan, and tool-card notification routing is incomplete." >&2
  exit 1
fi

if ! rg -q 'CompactInboundImagePayloads' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'MaterializeAndCompactInboundImagePayloads' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'kGeneratedImagesDirectory = "generated_images"' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'SYS_renameat2' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'rawResponseItem/completed' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'ConnectionFailureListener' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java"; then
  echo "Bounded image-result framing and recoverable transport failure handling are incomplete." >&2
    exit 1
fi

png_validator="$PROJECT_ROOT/modules/native-engine/src/main/cpp/png_validator.cpp"
sha256_implementation="$PROJECT_ROOT/modules/native-engine/src/main/cpp/sha256.cpp"
workspace_image="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceImageFile.java"
java_png_validator="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/PngImageValidator.java"
if ! rg -q 'ValidatePngImage' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'read_and_validate_materialized_png' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'ensure_materialization_proof' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'stored SHA-256 proof' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'image-materialization-proofs' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'Sha256Hex' "$sha256_implementation" \
    || rg -q 'has_png_signature' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'kMaximumInflatedPngBytes' "$png_validator" \
    || ! rg -q 'IHDR' "$png_validator" \
    || ! rg -q 'IDAT' "$png_validator" \
    || ! rg -q 'IEND' "$png_validator" \
    || ! rg -q 'crc32' "$png_validator" \
    || ! rg -q 'inflateInit' "$png_validator" \
    || ! rg -q 'PngImageValidator\.validate' "$workspace_image" \
    || ! rg -q 'CRC32' "$java_png_validator" \
    || ! rg -q 'Inflater' "$java_png_validator" \
    || ! rg -q 'MAXIMUM_INFLATED_BYTES' "$java_png_validator" \
    || ! rg -q 'png_validator\.cpp' "$PROJECT_ROOT/scripts/test.sh" \
    || ! rg -q 'png_validator\.cpp' "$apk_builder" \
    || ! rg -q 'sha256\.cpp' "$PROJECT_ROOT/scripts/test.sh" \
    || ! rg -q 'sha256\.cpp' "$apk_builder" \
    || ! rg -q 'libagentcodi\.so.*libz\.so\.1.*libz_1\.so' "$apk_builder" \
    || ! rg -q 'signature followed by arbitrary bytes' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'reject a valid replacement PNG whose digest lacks prior proof' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'clear app-server savedPath when no proven materialization exists' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'keepsScrubbedResumeImagePathNonExportable' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'layout\.getState\(\)\.getAbsolutePath\(\)' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || ! rg -q 'rejectsPngSignatureFollowedByGarbage' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceLayoutTest.java"; then
  echo "Complete bounded PNG materialization and export validation is incomplete." >&2
  exit 1
fi

image_exporter="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/WorkspaceImageExporter.java"
if ! rg -q 'WorkspaceImageFile\.inspect' "$image_exporter" \
    || ! rg -q 'WorkspaceImageFile\.copyTo' "$image_exporter" \
    || ! rg -q 'NativeWorkspaceFileAccess\.opener' "$image_exporter" \
    || ! rg -q 'Intent\.ACTION_CREATE_DOCUMENT' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'getReportedImagePath' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'R\.string\.image_export' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'ImageValidationState' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java"; then
  echo "Validated workspace-image export through Android's document picker is incomplete." >&2
  exit 1
fi

workspace_exporter="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/WorkspaceFileExporter.java"
settings_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/SettingsActivity.java"
workspace_reader="$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_file_reader.cpp"
workspace_access="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileAccess.java"
workspace_archive="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceArchive.java"
if ! rg -q 'WorkspaceExportFile\.list' "$workspace_exporter" \
    || ! rg -q 'WorkspaceExportFile\.copyTo' "$workspace_exporter" \
    || ! rg -q 'WorkspaceArchive\.write' "$workspace_exporter" \
    || ! rg -q 'NativeWorkspaceFileAccess\.opener' "$workspace_exporter" \
    || ! rg -q 'layout\.getWorkspace\(\)' "$workspace_exporter" \
    || ! rg -q '"unix:nlink,fileKey"' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileBoundary.java" \
    || ! rg -q 'expectedFileKey\.equals\(fileKey\)' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileBoundary.java" \
    || ! rg -q 'SecureDirectoryStream' "$workspace_access" \
    || ! rg -q 'hasSameOpenedSnapshot' "$workspace_archive" \
    || ! rg -q 'getNano\(\) / 1000' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceExportFile.java" \
    || ! rg -q 'archivesAcrossProviderTimestampPrecision' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceExportTest.java" \
    || ! rg -q 'openat\(' "$workspace_reader" \
    || ! rg -q 'O_NOFOLLOW' "$workspace_reader" \
    || ! rg -q 'fstat\(' "$workspace_reader" \
    || ! rg -q 'st_nlink != 1' "$workspace_reader" \
    || ! rg -q 'nativeOpenWorkspaceFile' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeEngine.java" \
    || ! rg -q 'workspace_file_reader\.cpp' "$apk_builder" \
    || rg -q 'FileInputStream' \
      "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceExportFile.java" \
      "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceImageFile.java" \
    || rg -q 'getCodexHome|auth\.json' "$workspace_exporter" \
    || ! rg -q 'R\.string\.workspace_file_choose' "$settings_activity" \
    || ! rg -q 'R\.string\.workspace_archive_export' "$settings_activity" \
    || ! rg -q 'Intent\.ACTION_CREATE_DOCUMENT' "$settings_activity" \
    || rg -q 'Intent\.ACTION_OPEN_DOCUMENT_TREE' "$settings_activity"; then
  echo "Bounded all-type workspace file and archive export is incomplete." >&2
  exit 1
fi

approval_dialog="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/InteractiveRequestDialog.java"
if rg -q '\.setItems\(' "$approval_dialog" \
    || ! rg -q 'setPositiveButton\([[:space:]]*$' "$approval_dialog" \
    || ! rg -q 'R\.string\.approval_allow' "$approval_dialog" \
    || ! rg -q 'setNegativeButton\([[:space:]]*$' "$approval_dialog" \
    || ! rg -q 'R\.string\.approval_decline' "$approval_dialog" \
    || ! rg -q 'R\.string\.approval_stop_turn' "$approval_dialog"; then
  echo "Approval details must keep explicit allow and decline buttons visible." >&2
  exit 1
fi

default_strings="$PROJECT_ROOT/app/src/main/res/values/strings.xml"
german_strings="$PROJECT_ROOT/app/src/main/res/values-de/strings.xml"
default_names="$(rg -o 'name="[a-z0-9_]+"' "$default_strings" | sort -u)"
german_names="$(rg -o 'name="[a-z0-9_]+"' "$german_strings" | sort -u)"
if [ "$default_names" != "$german_names" ] \
    || ! rg -q '<locale android:name="en"' "$PROJECT_ROOT/app/src/main/res/xml/locales_config.xml" \
    || ! rg -q '<locale android:name="de"' "$PROJECT_ROOT/app/src/main/res/xml/locales_config.xml" \
    || ! rg -q 'android:localeConfig="@xml/locales_config"' "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
    || ! rg -q 'AppLanguage\.attach' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'AppLanguage\.attach' "$settings_activity" \
    || ! rg -q 'UiLanguage\.SYSTEM' "$settings_activity" \
    || ! rg -q 'LocaleManager' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/AppLanguage.java" \
    || ! rg -q 'LocaleManager' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/RuntimeText.java"; then
  echo "English/German resources or the device-language selection contract are incomplete." >&2
  exit 1
fi

licenses_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/LicensesActivity.java"
agentcodi_license_resource="$PROJECT_ROOT/app/src/main/res/raw/agentcodi_apache_2_0.txt"
if ! rg -q 'LicensesActivity' "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
    || [ ! -f "$agentcodi_license_resource" ] \
    || ! rg -q 'R\.raw\.agentcodi_apache_2_0' "$licenses_activity" \
    || ! rg -q 'license_show_text' "$licenses_activity" "$default_strings" "$german_strings" \
    || ! rg -q 'third-party/codex/LICENSE' "$licenses_activity" \
    || ! rg -q 'third-party/codex/NOTICE' "$licenses_activity" \
    || ! rg -q 'R\.raw\.third_party_notices' "$licenses_activity" \
    || ! rg -q '<string name="license_agentcodi_summary">Copyright 2026 Pascal \(Mc Pasi\) · Apache License 2\.0\.</string>' "$default_strings" \
    || ! rg -q '<string name="license_agentcodi_summary">Copyright 2026 Pascal \(Mc Pasi\) · Apache License 2\.0\.</string>' "$german_strings" \
    || ! rg -q 'components bundled in the APK' "$default_strings"; then
  echo "The legal-notices screen or its first-/third-party license boundaries are incomplete." >&2
  exit 1
fi

if ! rg -q 'CODEX_CODE_MODE_HOST_PATH' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'CODEX_CODE_MODE_HOST_LIBRARY' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || ! rg -q 'libcodex-codehost\.so' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java"; then
  echo "The packaged code-mode host is not wired through the native supervisor." >&2
  exit 1
fi

core_root="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core"
native_process="$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp"
storage_layout="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java"
terminal_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/TerminalActivity.java"
terminal_session="$core_root/CodexTerminalSession.java"
session_controller="$core_root/CodexSessionController.java"
app_server_client="$core_root/CodexAppServerClient.java"
runtime_service="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java"
toolchain_shell="$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_shell_main.cpp"
if ! rg -Uq 'android:name="\.TerminalActivity"[[:space:][:print:]]{0,220}android:exported="false"' "$manifest" \
    || ! rg -q 'openTerminal' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'AgentRuntimeService\.startTerminal' "$terminal_activity" \
    || ! rg -q 'AgentRuntimeService\.sendTerminalInput' "$terminal_activity" \
    || ! rg -q 'controller\.startTerminal' "$runtime_service" \
    || ! rg -q 'new CodexTerminalSession' "$session_controller" \
    || ! rg -q 'terminal\.onNotification' "$session_controller" \
    || ! rg -q '"command/exec"' "$terminal_session" \
    || ! rg -q '"command/exec/write"' "$app_server_client" \
    || ! rg -q '"command/exec/resize"' "$terminal_session" \
    || ! rg -q '"command/exec/terminate"' "$terminal_session" \
    || ! rg -q 'command/exec/outputDelta' "$terminal_session" \
    || ! rg -q 'PERMISSION_PROFILE = "agentcodi-workspace"' "$terminal_session" \
    || ! rg -q '"permissionProfile", PERMISSION_PROFILE' "$terminal_session" \
    || ! rg -q '"tty", Boolean\.TRUE' "$terminal_session" \
    || ! rg -q 'OUTPUT_BYTES_CAP = 8L \* 1024L \* 1024L' "$terminal_session" \
    || ! rg -q 'SERVER_TIMEOUT_MS = 30L \* 60L \* 1000L' "$terminal_session"; then
  echo "The sandboxed app-server PTY, runtime facade, or non-exported UI route is incomplete." >&2
  exit 1
fi

if [ -e "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/TerminalController.java" ] \
    || [ -e "$PROJECT_ROOT/modules/native-engine/src/main/cpp/terminal_process.cpp" ] \
    || [ -e "$PROJECT_ROOT/modules/native-engine/src/main/cpp/terminal_process.h" ] \
    || rg -q 'native(Start|Read|Write|Resize|Poll|Stop)Terminal|forkpty' \
      "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeEngine.java" \
      "$PROJECT_ROOT/modules/native-engine/src/main/cpp/jni_bridge.cpp"; then
  echo "A separate same-UID terminal process path bypasses the app-server sandbox." >&2
  exit 1
fi

if ! rg -q 'getToolchain\(\)' "$storage_layout" \
    || ! rg -q 'secureChild\(workspace, "toolchain"\)' "$storage_layout" \
    || ! rg -q 'getToolBin\(\)' "$storage_layout" \
    || ! rg -q 'secureChild\(root, "tool-bin"\)' "$storage_layout" \
    || ! rg -q 'preparePackagedToolAliases' "$storage_layout" \
    || ! rg -q 'isNodeRuntimeEnabled' "$storage_layout" \
    || ! rg -q 'isNpmRuntimeEnabled' "$storage_layout" \
    || ! rg -q 'isPythonRuntimeEnabled' "$storage_layout" \
    || ! rg -q 'preparePackagedToolRuntime' "$storage_layout" \
    || ! rg -q 'install <node|npm|python>' "$toolchain_shell" \
    || ! rg -q 'Ask the user for permission' "$toolchain_shell" \
    || ! rg -q 'node-24\.18\.0' "$toolchain_shell" \
    || ! rg -q 'npm-11\.19\.0' "$toolchain_shell" \
    || ! rg -q 'python-3\.14\.6' "$toolchain_shell" \
    || ! rg -q 'kPackagedNodeName = "libnode\.so"' "$toolchain_shell" \
    || ! rg -q 'realpath\("/proc/self/exe"' "$toolchain_shell" \
    || rg -q 'required_environment\("AGENTCODI_NODE_PATH"\)' "$toolchain_shell" \
    || rg -q 'AGENTCODI_(SHELL|NODE)_PATH=' "$native_process" \
    || ! rg -q 'ToolchainCommand\.requestedInstallationPackage' "$approval_dialog" \
    || ! rg -q 'layout\.preparePackagedToolAliases' "$runtime_service" \
    || ! rg -q 'layout\.preparePackagedToolRuntime' "$runtime_service" \
    || ! rg -q 'layout\.getToolBin\(\)' "$runtime_service" \
    || ! rg -q 'isNodeRuntimeEnabled' "$terminal_activity" \
    || ! rg -q 'terminal_node_enabled' "$terminal_activity" \
    || ! rg -q 'terminal_npm_enabled' "$terminal_activity" \
    || ! rg -q 'terminal_python_enabled' "$terminal_activity" \
    || ! rg -q 'AGENTCODI_TOOLCHAIN_PACKAGES=node,npm,python' "$native_process" \
    || ! rg -q 'AGENTCODI_TOOL_BIN=' "$native_process" \
    || ! rg -q 'AGENTCODI_TOOL_RUNTIME=' "$native_process" \
    || ! rg -q 'SHELL=" \+ std::string\(kSystemShell\)' "$native_process" \
    || ! rg -q 'config\.tool_binary_directory \+ ":"' "$native_process" \
    || ! rg -q 'validate_tool_alias' "$native_process"; then
  echo "The user-mediated Node.js, npm, or Python toolchain activation path is incomplete." >&2
  exit 1
fi

if rg -n 'workspace/console|SharedPreferences|onSaveInstanceState' "$terminal_activity" "$terminal_session" "$toolchain_shell" \
    || rg -n 'CODEX_HOME|auth\.json|"sandboxPolicy"' "$terminal_activity" "$terminal_session" "$toolchain_shell"; then
  echo "Terminal output/input must remain transient and outside the authentication boundary." >&2
  exit 1
fi

if ! rg -q 'NODE_VERSION="24\.18\.0"' "$apk_builder" \
    || ! rg -q 'NODE_SHA256="6456b78aba9e0007de7a4c580d2b34bb3865145bebe06e75273152f8dcba4236"' "$apk_builder" \
    || ! rg -q 'NODE_RUNTIME_SHA256="e31cd5c7f5db279d638c3ad773e04f12842077f0559f4da4f369440a6f4195c3"' "$apk_builder" \
    || ! rg -q 'NPM_SHA256="385a051111f66c56d0564e6809244f1740427805a78d2e5a5dc470fb420832f8"' "$apk_builder" \
    || ! rg -q 'PYTHON_SHA256="3166e56c2b6c03fff41191fbb9d736302978e7c484702814d9f6dc99dd6006bd"' "$apk_builder" \
    || ! rg -q 'AGENTCODI_TOOL_RUNTIME_V1' "$apk_builder" \
    || ! rg -q 'Compiling packaged terminal shell bridge' "$apk_builder" \
    || ! rg -q "toolchain_smoke -c 'node --version'" "$apk_builder" \
    || ! rg -q 'toolchain_model_smoke' "$apk_builder" \
    || ! rg -q 'command -v agentcodi-toolchain' "$apk_builder" \
    || ! rg -q 'command/exec/outputDelta' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'command/exec/write' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'command/exec/resize' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'command/exec/terminate' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'config/read' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'config/batchWrite' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'config/mcpServer/reload' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'third-party/node/NODE-LICENSE' "$licenses_activity" \
    || ! rg -q 'assets/third-party/node/' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt"; then
  echo "Pinned Node.js packaging, execution smoke, or legal notices are incomplete." >&2
  exit 1
fi

if rg -q '^(GDBM|READLINE)_(URL|SHA256|ARCHIVE|SOURCE)' "$apk_builder" \
    || rg -q 'cp -L .*lib(gdbm|readline)' "$apk_builder"; then
  echo "The APK builder must not fetch or package GNU dbm/readline runtimes." >&2
  exit 1
fi
if ! rg -q 'PYTHON_SOURCE_EXTENSION_COUNT="75"' "$apk_builder" \
    || ! rg -q 'PYTHON_PACKAGED_EXTENSION_COUNT="72"' "$apk_builder" \
    || ! rg -q 'lib-dynload/_dbm\.cpython-314-aarch64-linux-android\.so' "$apk_builder" \
    || ! rg -q 'lib-dynload/_gdbm\.cpython-314-aarch64-linux-android\.so' "$apk_builder" \
    || ! rg -q 'lib-dynload/readline\.cpython-314-aarch64-linux-android\.so' "$apk_builder" \
    || ! rg -q 'database\.__class__\.__module__ == "dbm\.sqlite3"' "$apk_builder" \
    || ! rg -q 'python-sqlite-dbm-shelve-ok' "$apk_builder" \
    || ! rg -q '_pyrepl-ok' "$apk_builder" \
    || ! rg -q 'ZSTD_LICENSE_SHA256="7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8"' "$apk_builder" \
    || ! rg -q 'LIBLZMA_0BSD_LICENSE_SHA256="0b01625d853911cd0e2e088dcfb743261034a091bb379246cb25a14cc4c74bf1"' "$apk_builder" \
    || ! rg -q 'GNU runtime libraries are deliberately excluded' "$apk_builder" \
    || ! rg -q 'GNU Readline and GNU dbm are not bundled' "$PROJECT_ROOT/app/src/main/res/values/strings.xml" \
    || ! rg -q 'GNU Readline und GNU dbm sind nicht enthalten' "$PROJECT_ROOT/app/src/main/res/values-de/strings.xml" \
    || ! rg -q '72 native extension modules' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt"; then
  echo "The reduced Python runtime, compatibility smokes, or precise license inventory is incomplete." >&2
  exit 1
fi

if ! rg -q 'VERSION_NAME = "0\.5\.7"' "$core_root/BuildIdentity.java" \
    || ! rg -q 'VERSION_CODE = 44' "$core_root/BuildIdentity.java" \
    || ! rg -q 'android:versionName="0\.5\.7"' "$manifest" \
    || ! rg -q 'android:versionCode="44"' "$manifest" \
    || ! rg -q 'APP_VERSION="0\.5\.7"' "$apk_builder" \
    || ! rg -q 'VERSION_CODE="44"' "$apk_builder"; then
  echo "The 0.5.7 identity is inconsistent." >&2
  exit 1
fi

if ! rg -q 'containsLikelyCredential' "$core_root/CredentialGuard.java" \
    || ! rg -q 'CredentialGuard\.containsLikelyCredential\(input\)' "$core_root/CodexSessionController.java" \
    || ! rg -q 'CredentialGuard\.containsLikelyCredential\(editable\)' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'containsCredential\(answers\)' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/InteractiveRequestDialog.java" \
    || ! rg -q 'final char\[\] apiKey' "$core_root/CodexAppServerClient.java" \
    || rg -q 'new String\(apiKey\)' "$core_root" "$PROJECT_ROOT/modules/runtime/src/main/java" \
    || ! rg -q 'writeBytes\(byte\[\] line' "$core_root/CodexRpcTransport.java" \
    || rg -q 'clearenv\(\)|set_child_environment' "$native_process" \
    || ! rg -q 'child_environment\(const ProcessConfig& config\)' "$native_process" \
    || ! rg -q 'execve\(config\.executable\.c_str\(\), arguments\.data\(\), environment\.data\(\)\)' "$native_process" \
    || ! rg -q 'umask\(0077\)' "$native_process" \
    || ! rg -q 'shell_environment_policy=\{inherit=\\"none\\"' "$native_process" \
    || ! rg -q 'analytics\.enabled=false' "$native_process" \
    || ! rg -q 'otel\.exporter=\\"none\\"' "$native_process" \
    || ! rg -q 'feedback\.enabled=false' "$native_process" \
    || ! rg -q '"config\.toml", "requirements\.toml", "hooks\.json"' "$storage_layout" \
    || ! rg -q 'validateRuntimeConfigurationFiles' "$storage_layout" \
    || ! rg -q 'createStateDirectory' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/CrashReportStore.java"; then
  echo "Authentication and token-leak prevention boundaries are incomplete." >&2
  exit 1
fi

echo "Architecture checks passed."
