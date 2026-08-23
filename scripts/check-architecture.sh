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
runtime_service="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java"
session_snapshot="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionSnapshot.java"
thread_summary="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexThreadSummary.java"
thread_controller_test="$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java"
if ! rg -q '"thread/archive"' "$mcp_session" \
    || ! rg -q '"thread/unarchive"' "$mcp_session" \
    || ! rg -q '"thread/delete"' "$mcp_session" \
    || ! rg -q '"archived", Boolean\.valueOf\(archived\)' "$mcp_session" \
    || ! rg -q 'isShowingArchivedThreads' "$session_snapshot" "$main_activity" \
    || ! rg -q 'boolean archived' "$thread_summary" \
    || ! rg -q 'controller\.archiveThread' "$runtime_service" \
    || ! rg -q 'controller\.unarchiveThread' "$runtime_service" \
    || ! rg -q 'controller\.deleteThread' "$runtime_service" \
    || ! rg -q 'AgentRuntimeService\.archiveThread' "$main_activity" \
    || ! rg -q 'AgentRuntimeService\.unarchiveThread' "$main_activity" \
    || ! rg -q 'AgentRuntimeService\.deleteThread' "$main_activity" \
    || ! rg -q 'chat_delete_message' "$main_activity" \
    || ! rg -q 'managesThreadArchiveAndDeletion' "$thread_controller_test" \
    || ! rg -q 'rejectsThreadMutationDuringActiveTurn' "$thread_controller_test" \
    || ! rg -q -- '--thread-management-roundtrip' \
        "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp"; then
  echo "Thread archive, restore, permanent deletion, or focused coverage is incomplete." >&2
  exit 1
fi
document_importer="$import_client/de/agentcodi/imports/client/WorkspaceDocumentImporter.java"
document_installer="$import_client/de/agentcodi/imports/client/WorkspaceDocumentInstaller.java"
import_lifecycle_test="$PROJECT_ROOT/tests/java/de/agentcodi/imports/client/WorkspaceImportLifecycleTest.java"
native_document_installer="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeWorkspaceDocumentInstaller.java"
native_import_installer="$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_import_installer.cpp"
import_limits="$import_contracts/de/agentcodi/imports/WorkspaceImportLimits.java"
import_grant="$import_contracts/de/agentcodi/imports/WorkspaceImportGrant.java"
imported_file="$import_contracts/de/agentcodi/imports/ImportedWorkspaceFile.java"
attachment_context="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexWorkspaceAttachmentContext.java"
file_transaction="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexFileMentionTransaction.java"
app_server_client="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexAppServerClient.java"
storage_layout="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java"
if ! rg -q 'Intent\.ACTION_OPEN_DOCUMENT' "$main_activity" \
    || ! rg -q 'Intent\.CATEGORY_OPENABLE' "$main_activity" \
    || ! rg -q 'Intent\.EXTRA_ALLOW_MULTIPLE' "$main_activity" \
    || ! rg -q 'Intent\.FLAG_GRANT_READ_URI_PERMISSION' "$main_activity" \
    || ! rg -q 'WorkspaceImportGrant\.fromResultIntentFlags' "$main_activity" \
    || ! rg -q 'data\.getFlags\(\)' "$main_activity" \
    || ! rg -q '!sourceGrant\.hasTransientReadPermission\(\)' "$main_activity" \
    || rg -q 'takePersistableUriPermission|ACTION_OPEN_DOCUMENT_TREE' "$main_activity" "$workspace_importer" \
    || ! rg -q 'WorkspaceImportGrant sourceGrant' "$workspace_importer" \
    || ! rg -q 'requireContentSource\(sourceUri, sourceGrant\)' "$workspace_importer" \
    || ! rg -q 'sourceGrant == null \|\| !sourceGrant\.hasTransientReadPermission\(\)' "$workspace_importer" \
    || ! rg -q 'Integer\.bitCount\(readPermissionFlag\) != 1' "$import_grant" \
    || rg -q 'android\.|content://' "$import_grant" \
    || ! rg -q 'ContentResolver\.SCHEME_CONTENT' "$workspace_importer" \
    || ! rg -q 'WorkspaceLayout\.create' "$workspace_importer" \
    || ! rg -q 'NativeWorkspaceFileAccess\.opener' "$workspace_importer" \
    || ! rg -q 'NativeWorkspaceDocumentInstaller\.instance' "$workspace_importer" \
    || rg -q 'try \(InputStream source = opened\)' "$workspace_importer" \
    || ! rg -q 'WorkspaceFileImporter\.recoverPendingImports\(layout\)' "$runtime_service" \
    || ! rg -q 'CodexFileMentionTransaction prepareForCodex' "$workspace_importer" \
    || ! rg -q 'prepareForCodex\(' "$main_activity" "$document_importer" \
    || rg -q 'List<CodexFileMention>|verifyForCodex\(applicationContext' "$main_activity" "$workspace_importer" \
    || ! rg -q 'interface CodexFileMentionTransaction' "$file_transaction" \
    || ! rg -q 'SendGuard' "$file_transaction" "$document_importer" "$mcp_session" \
    || ! rg -q 'requestWithFileGuard' "$app_server_client" "$mcp_session" \
    || ! rg -Uq 'synchronized \(writeLock\)[[:space:][:print:]]{0,240}sendGuard\.verifyUnchanged\(\)[[:space:][:print:]]{0,240}transport\.writeBytes' "$app_server_client" \
    || ! rg -q 'file\.source\.verifyUnchanged\(\)' "$document_importer" \
    || ! rg -q 'SecureDirectoryStream' "$document_importer" \
    || ! rg -q 'StandardOpenOption\.CREATE_NEW' "$document_importer" \
    || ! rg -q 'LinkOption\.NOFOLLOW_LINKS' "$document_importer" \
    || ! rg -q 'interface WorkspaceDocumentInstaller' "$document_installer" \
    || ! rg -q 'installer\.installNoReplace' "$document_importer" \
    || ! rg -q 'cleanupAbandonedPendingFiles\(importRoot\)' "$document_importer" \
    || ! rg -q 'closeOwnedSource\(source, committed != null, failure\)' "$document_importer" \
    || rg -q 'requireMissing|importRoot\.move' "$document_importer" \
    || ! rg -q 'NativeEngine\.installWorkspaceImportNoReplace' "$native_document_installer" \
    || ! rg -q 'SYS_renameat2' "$native_import_installer" \
    || ! rg -q 'kRenameNoReplace' "$native_import_installer" \
    || ! rg -q 'O_NOFOLLOW' "$native_import_installer" \
    || ! rg -q 'nativeInstallWorkspaceImportNoReplace' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeEngine.java" \
    || ! rg -q 'workspace_import_installer\.cpp' "$apk_builder" \
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
    || ! rg -q 'WorkspaceImportLifecycleTest\.run' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/TestMain.java" \
    || ! rg -q 'a picker result without its read flag is not an import grant' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'model-readable storage path contains only randomness and a safe extension' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'same-size content replacement cannot enter a Codex turn' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'preparation does not hash before the synchronous send scope' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'same-size replacement before send-scope hashing cannot reach the Codex RPC' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'same-size replacement immediately before RPC write fails closed' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'first attachment replacement while hashing a later file fails closed' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'the final installation race never overwrites competing bytes' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || ! rg -q 'a source-close failure cannot hide a committed import' "$import_lifecycle_test" \
    || ! rg -q 'outer directory-close failures cannot hide a committed import' "$import_lifecycle_test" \
    || ! rg -q 'startup recovery removes an exact abandoned pending import' "$import_lifecycle_test" \
    || ! rg -q 'recovery does not follow or reinterpret the reserved symlink' "$import_lifecycle_test" \
    || ! rg -q 'never_overwrites_a_parallel_creator' "$PROJECT_ROOT/tests/cpp/workspace_import_installer_test.cpp" \
    || ! rg -q 'turn/start revalidates at transport write while verified handles remain open' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'failed final guard prevents transport write and closes transaction' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
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
  echo "The pinned Codex runtime rejects project trust CLI overrides under strict config." >&2
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
workspace_export_file="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceExportFile.java"
workspace_export_test="$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceExportTest.java"
if ! rg -q 'WorkspaceExportFile\.list' "$workspace_exporter" \
    || ! rg -q 'WorkspaceExportFile\.copyTo' "$workspace_exporter" \
    || ! rg -q 'WorkspaceArchive\.write' "$workspace_exporter" \
    || ! rg -q 'NativeWorkspaceFileAccess\.opener' "$workspace_exporter" \
    || ! rg -q 'layout\.getWorkspace\(\)' "$workspace_exporter" \
    || ! rg -q '"unix:nlink,fileKey"' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileBoundary.java" \
    || ! rg -q 'expectedFileKey\.equals\(fileKey\)' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileBoundary.java" \
    || ! rg -q 'SecureDirectoryStream' "$workspace_access" \
    || ! rg -q 'hasSameOpenedSnapshot' "$workspace_archive" \
    || ! rg -q 'maximumScannedEntries' "$workspace_archive" \
    || ! rg -q 'MAXIMUM_SCANNED_ENTRIES = 65536' "$workspace_exporter" \
    || ! rg -q 'scannedEntryCount' "$workspace_export_file" \
    || ! rg -q 'files\.size\(\) >= maximumFiles' "$workspace_export_file" \
    || ! rg -q 'defaultMaximumScannedEntries' "$workspace_export_file" \
    || ! rg -q 'getNano\(\) / 1000' "$workspace_export_file" \
    || ! rg -q 'doesNotChargeSkippedEntriesAgainstRegularFileLimit' "$workspace_export_test" \
    || ! rg -q 'keepsSkippedEntriesBoundedBySeparateScanLimit' "$workspace_export_test" \
    || ! rg -q 'archivesAcrossProviderTimestampPrecision' "$workspace_export_test" \
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
    || ! rg -q 'third-party/ripgrep/DEPENDENCIES' "$licenses_activity" \
    || ! rg -q 'third-party/ripgrep/LICENSES' "$licenses_activity" \
    || ! rg -q 'third-party/ripgrep/PROVENANCE' "$licenses_activity" \
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
toolchain_policy="$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_policy.cpp"
toolchain_elf_guard="$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_guard.cpp"
toolchain_elf_attestor="$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_payload.cpp"
toolchain_elf_injector="$PROJECT_ROOT/modules/native-engine/src/main/cpp/toolchain_elf_attestor_injector.cpp"
toolchain_elf_linker_script="$PROJECT_ROOT/scripts/toolchain_elf_attestor_payload.ld"
toolchain_elf_guard_test="$PROJECT_ROOT/tests/cpp/toolchain_elf_guard_test.cpp"
toolchain_fake_guard="$PROJECT_ROOT/tests/cpp/toolchain_fake_guard.cpp"
ripgrep_policy="$PROJECT_ROOT/modules/native-engine/src/main/cpp/ripgrep_bridge_policy.cpp"
ripgrep_policy_test="$PROJECT_ROOT/tests/cpp/ripgrep_bridge_policy_test.cpp"
ripgrep_artifact="$PROJECT_ROOT/third_party/ripgrep/ripgrep-15.2.0-android-arm64.elf"
ripgrep_dependencies="$PROJECT_ROOT/third_party/ripgrep/DEPENDENCIES"
ripgrep_licenses="$PROJECT_ROOT/third_party/ripgrep/LICENSES"
ripgrep_provenance="$PROJECT_ROOT/third_party/ripgrep/PROVENANCE"
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
    || ! rg -q 'isRipgrepRuntimeEnabled' "$storage_layout" \
    || ! rg -q 'preparePackagedToolRuntime' "$storage_layout" \
    || ! rg -q 'install <node|npm|python|ripgrep>' "$toolchain_shell" \
    || ! rg -q 'Ask the user for permission' "$toolchain_shell" \
    || ! rg -q 'node-24\.18\.0' "$toolchain_policy" \
    || ! rg -q 'npm-11\.19\.0' "$toolchain_policy" \
    || ! rg -q 'python-3\.14\.6' "$toolchain_policy" \
    || ! rg -q 'ripgrep-15\.2\.0' "$toolchain_policy" \
    || ! rg -q 'kPackagedNodeName = "libnode\.so"' "$toolchain_shell" \
    || ! rg -q 'kPackagedRipgrepName = "libripgrep\.so"' "$toolchain_shell" \
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
    || ! rg -q 'terminal_ripgrep_enabled' "$terminal_activity" \
    || ! rg -q 'AGENTCODI_TOOLCHAIN_PACKAGES=node,npm,python,ripgrep' "$native_process" \
    || ! rg -q 'AGENTCODI_TOOL_BIN=' "$native_process" \
    || ! rg -q 'AGENTCODI_TOOL_RUNTIME=' "$native_process" \
    || ! rg -q 'SHELL=" \+ std::string\(kSystemShell\)' "$native_process" \
    || ! rg -q 'config\.tool_binary_directory \+ ":/system/bin:/system/xbin"' "$native_process" \
    || rg -q 'tool_binary_directory \+ ":" \+ config\.library_directory' "$native_process" \
    || ! rg -q 'PrepareGuardedToolInvocation' "$toolchain_shell" "$toolchain_policy" "$toolchain_elf_guard" \
    || ! rg -q 'non-canonical executable entry point' "$toolchain_elf_guard" "$toolchain_elf_guard_test" \
    || ! rg -q 'untrusted policy library' "$toolchain_elf_attestor" "$toolchain_elf_guard_test" \
    || ! rg -q '/proc/self/maps' "$toolchain_elf_attestor" \
    || ! rg -q 'PT_NOTE' "$toolchain_elf_injector" \
    || ! rg -q 'PF_R \| PF_X' "$toolchain_elf_injector" \
    || ! rg -q 'AgentCodiElfAttestorEntry == 0' "$toolchain_elf_linker_script" \
    || ! rg -q 'toolchain_elf_attestor_payload\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'toolchain_elf_attestor_injector\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'toolchain_fake_guard\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'substituted policy library' "$toolchain_elf_guard_test" "$apk_builder" \
    || [ ! -f "$toolchain_fake_guard" ] \
    || ! rg -q 'validate_tool_alias' "$native_process"; then
  echo "The user-mediated Node.js, npm, Python, or ripgrep toolchain activation path is incomplete." >&2
  exit 1
fi

if [ ! -f "$ripgrep_artifact" ] \
    || [ ! -f "$ripgrep_dependencies" ] \
    || [ ! -f "$ripgrep_licenses" ] \
    || [ ! -f "$ripgrep_provenance" ] \
    || ! rg -q 'ValidateRipgrepArguments' "$toolchain_policy" "$ripgrep_policy" \
    || ! rg -q 'PrepareRipgrepEnvironment' "$toolchain_policy" "$ripgrep_policy" \
    || ! rg -q 'RIPGREP_CONFIG_PATH' "$ripgrep_policy" "$ripgrep_policy_test" \
    || ! rg -q -- '--pre' "$ripgrep_policy" "$ripgrep_policy_test" \
    || ! rg -q -- '--search-zip' "$ripgrep_policy" "$ripgrep_policy_test" \
    || ! rg -q -- '--follow' "$ripgrep_policy" "$ripgrep_policy_test" \
    || ! rg -q 'ripgrep_bridge_policy_test\.cpp' "$PROJECT_ROOT/scripts/test.sh" \
    || ! rg -q 'ripgrep_bridge_policy\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'toolchain_elf_guard\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'toolchain_elf_attestor_payload\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'toolchain_elf_attestor_injector\.cpp' "$PROJECT_ROOT/scripts/test.sh" "$apk_builder" \
    || ! rg -q 'toolchain_elf_guard_test\.cpp' "$PROJECT_ROOT/scripts/test.sh"; then
  echo "The ripgrep bridge policy or its focused C++ regression coverage is incomplete." >&2
  exit 1
fi

if rg -n 'workspace/console|SharedPreferences|onSaveInstanceState' "$terminal_activity" "$terminal_session" "$toolchain_shell" \
    || rg -n 'CODEX_HOME|auth\.json|"sandboxPolicy"' "$terminal_activity" "$terminal_session" "$toolchain_shell"; then
  echo "Terminal output/input must remain transient and outside the authentication boundary." >&2
  exit 1
fi

if ! rg -q 'NODE_VERSION="24\.18\.0"' "$apk_builder" \
    || ! rg -q 'NODE_SHA256="6456b78aba9e0007de7a4c580d2b34bb3865145bebe06e75273152f8dcba4236"' "$apk_builder" \
    || ! rg -q 'NODE_UNGUARDED_RUNTIME_SHA256="e31cd5c7f5db279d638c3ad773e04f12842077f0559f4da4f369440a6f4195c3"' "$apk_builder" \
    || ! rg -q 'NODE_PREATTESTED_RUNTIME_SHA256="cbf6b5c9aade3efd2127cb610db4a9ab8d54860c26d2c1273f8e3fae0bd6719f"' "$apk_builder" \
    || ! rg -q 'NODE_RUNTIME_SHA256="6d1e83f6dd9586adaee78d17f6bac23870af6a21ccad58779bac270cc318614c"' "$apk_builder" \
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

if ! rg -q 'RIPGREP_VERSION="15\.2\.0"' "$apk_builder" \
    || ! rg -q 'RIPGREP_SOURCE_SHA256="4eb0d0c70d2e3c760cab4f478c7eb715082ae1d8b5f4a23bb14515154348b04d"' "$apk_builder" \
    || ! rg -q 'RIPGREP_PREATTESTED_RUNTIME_SHA256="a93343b21a76f7ff00dc05c6eddc6317d36f143093e3f7cde795720adede00aa"' "$apk_builder" \
    || ! rg -q 'RIPGREP_RUNTIME_SHA256="4cfd048c4bac29ac0d494887b519752984f66a449ed4b22bd95cca6fcf540d50"' "$apk_builder" \
    || ! rg -q 'RIPGREP_LIBRARY_NAME="libripgrep\.so"' "$apk_builder" \
    || ! rg -q 'features:-pcre2' "$apk_builder" \
    || ! rg -q 'Normal target package count: 34' "$ripgrep_dependencies" \
    || rg -iq '^(pcre2|pcre2-sys) [0-9]' "$ripgrep_dependencies" \
    || ! rg -q 'MIT License' "$ripgrep_licenses" \
    || ! rg -q 'toolchain_smoke --ripgrep' "$apk_builder" \
    || ! rg -q 'blocked_ripgrep_option' "$apk_builder" \
    || ! rg -q 'ripgrep-direct-blocked' "$apk_builder" \
    || ! rg -q 'NODE_GUARD_LIBRARY_NAME="libagentcodi-node-guard\.so"' "$apk_builder" \
    || ! rg -q 'PYTHON_GUARD_LIBRARY_NAME="libagentcodi-python-guard\.so"' "$apk_builder" \
    || ! rg -q 'RIPGREP_GUARD_LIBRARY_NAME="libagentcodi-ripgrep-guard\.so"' "$apk_builder" \
    || ! rg -q 'NODE_GUARD_SHA256="92d7e6740a494c687383c83c1109f133b04ac67d0ac6a0714c6c7e26a5c3e1a7"' "$apk_builder" \
    || ! rg -q 'PYTHON_GUARD_SHA256="ab8ab4014503943c14842e79507cb5975b270f4fb97cec3c3fe423ad1fe71814"' "$apk_builder" \
    || ! rg -q 'RIPGREP_GUARD_SHA256="6f38c49ad156e456248330bfddec2dc3f934f94884cd64d1fd751c08fee40a20"' "$apk_builder" \
    || ! rg -q 'NODE_ATTESTOR_SHA256="241c3c157251f94d682da6bad6082079786198d241f63be76a456c8c64f16dfa"' "$apk_builder" \
    || ! rg -q 'PYTHON_ATTESTOR_SHA256="7e275cc1b169871b100a15f82af1395f384b507234549241ad14c98a94cb762c"' "$apk_builder" \
    || ! rg -q 'RIPGREP_ATTESTOR_SHA256="206e3f43a6dd1cfa1b81cc901e86be00d19c1584866f864da9ff94e6defcba99"' "$apk_builder" \
    || ! rg -q -- '--add-needed "\$RIPGREP_GUARD_LIBRARY_NAME"' "$apk_builder" \
    || rg -q 'PENDING_' "$apk_builder" \
    || ! rg -q 'RIPGREP_CONFIG_PATH' "$apk_builder" \
    || ! rg -q 'assets/third-party/ripgrep/' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt" \
    || ! rg -q 'third-party/ripgrep/PROVENANCE' "$licenses_activity"; then
  echo "Pinned ripgrep packaging, dependency inventory, policy smokes, or legal notices are incomplete." >&2
  exit 1
fi

if rg -q '^(GDBM|READLINE)_(URL|SHA256|ARCHIVE|SOURCE)' "$apk_builder" \
    || rg -q 'cp -L .*lib(gdbm|readline)' "$apk_builder"; then
  echo "The APK builder must not fetch or package GNU dbm/readline runtimes." >&2
  exit 1
fi
if ! rg -q 'PYTHON_SOURCE_EXTENSION_COUNT="75"' "$apk_builder" \
    || ! rg -q 'PYTHON_PACKAGED_EXTENSION_COUNT="72"' "$apk_builder" \
    || ! rg -q 'PYTHON_NATIVE_SET_SHA256="cc9e6ea0d0ad967979d8b2763fd32a9a328d589c20401e64035e573877cb2581"' "$apk_builder" \
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

if ! rg -q 'VERSION_NAME = "0\.5\.19"' "$core_root/BuildIdentity.java" \
    || ! rg -q 'VERSION_CODE = 56' "$core_root/BuildIdentity.java" \
    || ! rg -q 'CODEX_RUNTIME_VERSION = "0\.148\.1"' "$core_root/BuildIdentity.java" \
    || ! rg -q 'android:versionName="0\.5\.19"' "$manifest" \
    || ! rg -q 'android:versionCode="56"' "$manifest" \
    || ! rg -q 'APP_VERSION="0\.5\.19"' "$apk_builder" \
    || ! rg -q 'VERSION_CODE="56"' "$apk_builder" \
    || ! rg -q 'CODEX_ANDROID_VERSION="0\.148\.1"' "$apk_builder" \
    || ! rg -q 'CODEX_TERMUX_SOURCE_TAG="v0\.148\.1"' "$apk_builder" \
    || ! rg -q 'CODEX_TERMUX_SOURCE_COMMIT="9d48c76abec320ae3724164d0177299b1acd31ca"' "$apk_builder" \
    || ! rg -q 'CODEX_UPSTREAM_SOURCE_TAG="rust-v0\.148\.0"' "$apk_builder" \
    || ! rg -q 'CODEX_UPSTREAM_SOURCE_COMMIT="3ba0f711642a888aec92a611a3f3b2211157ff89"' "$apk_builder" \
    || ! rg -q 'CODEX_ANDROID_SHA256="b68a6c6770752deb045db084a9637b8cf1647b996a57d454e599981b963c4092"' "$apk_builder" \
    || ! rg -q 'CODEX_APP_SERVER_SOURCE_SHA256="35c76bc8a75fc768ea44433bcc755be931a3d73215d8324a182020b57ff1aa49"' "$apk_builder" \
    || ! rg -q 'CODEX_CODE_MODE_HOST_SHA256="da7bc9b805dd069f9b4008cb749d0f192cfd83445ed6ba7202ffd5aa51c1f855"' "$apk_builder" \
    || ! rg -q 'CODEX_APP_SERVER_ANDROID_SHA256="9c74afbfa027b840228278f4483405f59dc03393185e6e3a52fbc7ca64b921b9"' "$apk_builder" \
    || ! rg -q 'CODEX_LICENSE_SHA256="d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc"' "$apk_builder" \
    || ! rg -q 'CODEX_NOTICE_SHA256="8228749dd4dd6026baed0442f80e911308430478449285c865b188d97e6a013c"' "$apk_builder" \
    || ! rg -q 'CODEX_SCHEMA_BUNDLE_SHA256="819fe7b47288cc74da5190743390c8d1faef403f5401a1868b306dac195b1944"' "$apk_builder" \
    || ! rg -q 'CODEX_V2_SCHEMA_BUNDLE_SHA256="e5a20eb7211c21540a2d4e0106479285e13778e9c53d5837cfc735a71316a51e"' "$apk_builder" \
    || ! rg -q 'app-server generate-json-schema' "$apk_builder" \
    || ! rg -q '0\.148\.1' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q '0\.148\.1' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt" \
    || ! rg -q '9d48c76abec320ae3724164d0177299b1acd31ca' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q '9d48c76abec320ae3724164d0177299b1acd31ca' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt" \
    || ! rg -q '3ba0f711642a888aec92a611a3f3b2211157ff89' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q '3ba0f711642a888aec92a611a3f3b2211157ff89' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt"; then
  echo "The 0.5.19 / Codex 0.148.1 identity is inconsistent." >&2
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
