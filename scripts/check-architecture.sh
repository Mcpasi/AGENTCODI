#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd -P)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

unexpected_source="$(find "$PROJECT_ROOT/app/src/main/java" "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/review-mode/src/main/java" "$PROJECT_ROOT/modules/compatibility-mode/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java" "$PROJECT_ROOT/modules/file-browser-contracts/src/main/java" "$PROJECT_ROOT/modules/file-browser-client/src/main/java" "$PROJECT_ROOT/modules/import-contracts/src/main/java" "$PROJECT_ROOT/modules/import-client/src/main/java" "$PROJECT_ROOT/modules/mcp-contracts/src/main/java" "$PROJECT_ROOT/modules/mcp-client/src/main/java" "$PROJECT_ROOT/modules/connector-contracts/src/main/java" "$PROJECT_ROOT/modules/connector-client/src/main/java" "$PROJECT_ROOT/modules/runtime/src/main/java" "$PROJECT_ROOT/modules/native-engine/src/main/cpp" "$PROJECT_ROOT/tests/java" "$PROJECT_ROOT/tests/cpp" -type f ! -name '*.java' ! -name '*.cpp' ! -name '*.h' -print)"
if [ -n "$unexpected_source" ]; then
  echo "Only Java and C++ source files are accepted in source roots." >&2
  printf '%s\n' "$unexpected_source" >&2
  exit 1
fi

if find "$PROJECT_ROOT/app" "$PROJECT_ROOT/modules" "$PROJECT_ROOT/tests" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.js' -o -name '*.ts' -o -name '*.dart' -o -name '*.rs' \) -print -quit | grep -q .; then
  echo "Unsupported application source language detected." >&2
  exit 1
fi

if rg -n '^import android\.' "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/modules/review-mode/src/main/java" "$PROJECT_ROOT/modules/compatibility-mode/src/main/java" "$PROJECT_ROOT/modules/storage/src/main/java" "$PROJECT_ROOT/modules/file-browser-contracts/src/main/java" "$PROJECT_ROOT/modules/file-browser-client/src/main/java" "$PROJECT_ROOT/modules/import-contracts/src/main/java" "$PROJECT_ROOT/modules/import-client/src/main/java" "$PROJECT_ROOT/modules/mcp-contracts/src/main/java" "$PROJECT_ROOT/modules/mcp-client/src/main/java" "$PROJECT_ROOT/modules/connector-contracts/src/main/java" "$PROJECT_ROOT/modules/connector-client/src/main/java"; then
  echo "Pure Java modules must not import Android APIs." >&2
  exit 1
fi

compatibility_mode="$PROJECT_ROOT/modules/compatibility-mode/src/main/java"
execution_mode_contract="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexExecutionMode.java"
execution_mode_card="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/ExecutionModeSettingsCard.java"
if rg -n '^import de\.agentcodi\.(app|runtime|storage|imports|mcp|mode)\.' \
      "$compatibility_mode" \
    || rg -n '^import de\.agentcodi\.mode\.' "$PROJECT_ROOT/modules/core/src/main/java" \
    || ! rg -q 'COMPATIBILITY_PERMISSION_PROFILE_ID = ":danger-full-access"' \
      "$execution_mode_contract" \
    || ! rg -q 'FullAccessExecutionMode\.get' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || rg -n 'ProtectedExecutionMode|CompatibilityExecutionMode\.afterWarningAcknowledged' \
      "$PROJECT_ROOT/modules/runtime/src/main/java" \
    || rg -n 'PROTECTED_ID|protectedButton|justInTimeApprovalSwitch|showDangerWarning' "$execution_mode_card" \
    || ! rg -q 'CodexExecutionMode.COMPATIBILITY_ID' "$execution_mode_card" \
    || ! rg -q 'packageEditionAlwaysUsesFullAccess' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/ExecutionModeTest.java" \
    || rg -n '"(baseInstructions|developerInstructions|systemPrompt|system_prompt)"' \
      "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java"; then
  echo "Package Edition must offer only Full access without mode prompt injection." >&2
  exit 1
fi

if ! rg -q 'android:targetSdkVersion="28"' "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
    || ! rg -Fq 'package="de.agentcodi.pkg"' "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
    || ! rg -Fq 'APPLICATION_ID = "de.agentcodi.pkg";' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java" \
    || ! rg -Fq 'APP_ID="de.agentcodi.pkg"' "$PROJECT_ROOT/scripts/build-debug-apk.sh" \
    || ! rg -Fq 'APP_ARTIFACT_NAME="AGENTCODI-Package"' "$PROJECT_ROOT/scripts/build-debug-apk.sh" \
    || ! rg -Fq -- '--custom-package de.agentcodi.app' "$PROJECT_ROOT/scripts/build-debug-apk.sh" \
    || ! rg -Fq -- '--custom-package de.agentcodi.app' "$PROJECT_ROOT/.github/ci/compile-android-sources.sh" \
    || ! rg -q '^TARGET_SDK="28"' "$PROJECT_ROOT/scripts/build-debug-apk.sh" \
    || ! rg -q 'TARGET_SDK = 28;' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java" \
    || ! rg -q 'getPackagePrefix' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java" \
    || ! rg -Fq 'secureChild(canonicalBase, "usr")' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java" \
    || ! rg -Fq 'layout.getPackagePrefix().getAbsolutePath()' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || ! rg -Fq 'secureChild(home, ".local")' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceLayout.java" \
    || ! rg -Fq 'package_search_path(config, "bin")' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -Fq '"PREFIX=" + prefix' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp"; then
  echo "Package Edition target SDK or shared writable package prefix is incomplete." >&2
  exit 1
fi

review_mode="$PROJECT_ROOT/modules/review-mode/src/main/java"
review_contract="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexReviewMode.java"
review_request="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexReviewRequest.java"
review_state="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexReviewState.java"
review_controller="$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java"
review_ui="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/ReviewModeDialog.java"
if rg -n '^import de\.agentcodi\.(app|runtime|storage|imports|mcp|mode)\.' "$review_mode" \
    || rg -n '^import de\.agentcodi\.review\.' \
      "$PROJECT_ROOT/modules/core/src/main/java" "$PROJECT_ROOT/app/src/main/java" \
    || ! rg -q 'implements CodexReviewMode' "$review_mode" \
    || ! rg -q 'TARGET_CUSTOM = "custom"' "$review_request" \
    || ! rg -q 'DELIVERY_INLINE = "inline"' "$review_request" \
    || ! rg -q 'MAXIMUM_INSTRUCTIONS_CHARACTERS = 32 \* 1024' "$review_request" \
    || ! rg -q 'correlateStartResponse' "$review_contract" "$review_mode" \
    || ! rg -q 'authoritativeCompleted' "$review_contract" "$review_mode" \
    || ! rg -q 'Phase.COMPLETED' "$review_state" "$review_mode" \
    || ! rg -q 'getResponseTurnId' "$review_state" "$review_mode" "$review_controller" \
    || ! rg -q 'getNotificationTurnId' "$review_state" "$review_mode" "$review_controller" \
    || ! rg -q 'getControlTurnId' "$review_state" "$review_controller" \
    || ! rg -q 'acceptsTurnCompletion' "$review_contract" "$review_mode" "$review_controller" \
    || ! rg -q '"review/start"' "$review_controller" \
    || ! rg -q 'reviewThreadId' "$review_controller" \
    || ! rg -q 'isKnownTurnStatus' "$review_controller" \
    || ! rg -q 'failReviewStart' "$review_controller" \
    || ! rg -q 'CustomReviewMode\.get' \
      "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AgentRuntimeService.java" \
    || ! rg -q 'ReviewModeDialog\.show' \
      "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'setPositiveButton\(R\.string\.review_mode_start, null\)' "$review_ui" \
    || ! rg -q 'CredentialGuard\.containsLikelyCredential\(editable\)' "$review_ui" \
    || ! rg -q 'editable\.clear\(\)' "$review_ui" \
    || ! rg -q 'startsAndCorrelatesCustomReviewMode' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'rejectsMalformedCustomReviewResponse' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'correlatesReorderedLifecycleWithoutRevival' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CustomReviewModeTest.java" \
    || ! rg -q 'correlatesBoundedSplitTurnIds' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CustomReviewModeTest.java" \
    || ! rg -q 'stopsSplitIdReviewWhileStartResponseIsPending' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'TURN_INTERRUPT_TIMEOUT_MS = 120_000L' "$review_controller" \
    || ! rg -q 'turnControls\.submit' "$review_controller" \
    || ! rg -q 'successful RPC response only acknowledges the stop' "$review_controller" \
    || ! rg -q 'interrupt response does not permit a duplicate before completion' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q -- '--review-mode-roundtrip' \
      "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'turn_review_response' \
      "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'turn_review_live' \
      "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || rg -n '"(uncommittedChanges|baseBranch)"|"type"[[:space:]]*,[[:space:]]*"commit"' \
      "$review_controller" "$review_mode" "$review_request" "$review_ui"; then
  echo "The modular custom-only review request, correlation, or native UI boundary is incomplete." >&2
  exit 1
fi

mcp_contracts="$PROJECT_ROOT/modules/mcp-contracts/src/main/java"
mcp_client="$PROJECT_ROOT/modules/mcp-client/src/main/java"
connector_contracts="$PROJECT_ROOT/modules/connector-contracts/src/main/java"
connector_client="$PROJECT_ROOT/modules/connector-client/src/main/java"
import_contracts="$PROJECT_ROOT/modules/import-contracts/src/main/java"
import_client="$PROJECT_ROOT/modules/import-client/src/main/java"
file_browser_contracts="$PROJECT_ROOT/modules/file-browser-contracts/src/main/java"
file_browser_client="$PROJECT_ROOT/modules/file-browser-client/src/main/java"
manifest="$PROJECT_ROOT/app/src/main/AndroidManifest.xml"
if rg -n '^import de\.agentcodi\.' "$file_browser_contracts" \
    || rg -n '^import de\.agentcodi\.(app|core|imports|mcp|runtime)\.' \
      "$file_browser_client" \
    || rg -n '^import de\.agentcodi\.browser\.client\.' \
      "$PROJECT_ROOT/app/src/main/java" \
    || rg -n '^import de\.agentcodi\.browser\.' \
      "$PROJECT_ROOT/modules/core/src/main/java" \
      "$PROJECT_ROOT/modules/storage/src/main/java" \
    || ! rg -q 'class WorkspaceFileBrowser' "$file_browser_client" \
    || ! rg -q 'MAXIMUM_SCANNED_DIRECTORY_ENTRIES = 65536' \
      "$file_browser_contracts/de/agentcodi/browser/WorkspaceBrowserLimits.java" \
    || ! rg -q 'TEXT_PAGE_BYTES = 32 \* 1024' \
      "$file_browser_contracts/de/agentcodi/browser/WorkspaceBrowserLimits.java" \
    || ! rg -q 'BINARY_PAGE_BYTES = 2 \* 1024' \
      "$file_browser_contracts/de/agentcodi/browser/WorkspaceBrowserLimits.java" \
    || ! rg -q 'SecureDirectoryStream' \
      "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceDirectoryCatalog.java" \
    || ! rg -q 'NativeWorkspaceDirectoryCatalog\.reader' \
      "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/WorkspaceBrowserRepository.java" \
    || ! rg -q 'inspectArchive' \
      "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/WorkspaceBrowserRepository.java" \
    || ! rg -q 'O_NOFOLLOW' \
      "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_directory_reader.cpp" \
    || ! rg -q 'AT_SYMLINK_NOFOLLOW' \
      "$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_directory_reader.cpp" \
    || ! rg -q 'WorkspaceBrowserRepository' \
      "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/WorkspaceBrowserActivity.java" \
    || ! rg -q 'BitmapFactory\.Options' \
      "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/WorkspaceBrowserActivity.java" \
    || ! rg -q 'Intent\.ACTION_CREATE_DOCUMENT' \
      "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/WorkspaceBrowserActivity.java" \
    || ! rg -q 'R\.string\.browser_directory_export' \
      "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/WorkspaceBrowserActivity.java" \
    || ! rg -Uq 'android:name="de\.agentcodi\.app\.WorkspaceBrowserActivity"[[:space:][:print:]]{0,260}android:exported="false"' \
      "$manifest" \
    || ! rg -q 'WorkspaceFileBrowserTest\.run' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/TestMain.java" \
    || ! rg -q 'classifiesBinaryBytesBeyondTheInitialTextProbe' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceFileBrowserTest.java" \
    || ! rg -q 'class Utf8TextValidator' "$file_browser_client" \
    || ! rg -q 'workspace_directory_reader_test\.cpp' "$PROJECT_ROOT/scripts/test.sh"; then
  echo "The modular bounded workspace browser, preview, paging, or native directory boundary is incomplete." >&2
  exit 1
fi
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

connector_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/ConnectorActivity.java"
connector_loader="$connector_client/de/agentcodi/connectors/client/ConnectorCatalogLoader.java"
connector_controller="$connector_client/de/agentcodi/connectors/client/ConnectorCatalogController.java"
connector_snapshot="$connector_contracts/de/agentcodi/connectors/ConnectorCatalogSnapshot.java"
connector_url="$connector_contracts/de/agentcodi/connectors/ConnectorInstallUrl.java"
if rg -n '^import de\.agentcodi\.' "$connector_contracts" \
    || rg -n '^import de\.agentcodi\.(app|runtime|storage|imports|mcp|browser|mode|review)\.' "$connector_client" \
    || rg -n '^import de\.agentcodi\.connectors\.' "$PROJECT_ROOT/modules/core/src/main/java" \
    || rg -n 'java\.(io|nio|net)\.' "$connector_client" \
    || ! rg -q '"app/list/updated"' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q '"app/list"' "$connector_loader" \
    || ! rg -q '"app/installed"' "$connector_loader" \
    || ! rg -q '"app/read"' "$connector_loader" \
    || rg -n '"(config/|mcpServer/|plugin/|tool/call|account/)' "$connector_client" \
    || ! rg -q '"app/list"\.equals\(method\)' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexSessionController.java" \
    || ! rg -q 'MAXIMUM_SCANNED_APPS = 200' "$connector_loader" \
    || ! rg -q 'MAXIMUM_PROJECTED_CHARACTERS = 64 \* 1024' "$connector_loader" \
    || ! rg -q 'DIRECTORY_TIMEOUT_MS = 8_000L' "$connector_loader" \
    || ! rg -q 'INSTALLED_TIMEOUT_MS = 6_000L' "$connector_loader" \
    || ! rg -q 'OPTIONAL_DETAILS_TIMEOUT_MS = 3_000L' "$connector_loader" \
    || ! rg -q 'directorySnapshot' "$connector_loader" \
    || ! rg -q 'refreshInstalled' "$connector_loader" \
    || ! rg -q 'enrichOptionalDetails' "$connector_loader" \
    || ! rg -q 'PARALLEL_ESSENTIAL_TIMEOUT_MS = 9_000L' "$connector_controller" \
    || ! rg -q 'runtimeExecutor' "$connector_controller" \
    || ! rg -q 'detailsExecutor' "$connector_controller" \
    || ! rg -q 'queryInstalledAsync' "$connector_controller" \
    || ! rg -q 'hasReusableDirectoryState' "$connector_snapshot" \
    || ! rg -q 'previous\.hasReusableDirectoryState' "$connector_controller" \
    || ! rg -q 'fromReusableDirectorySnapshot' "$connector_loader" \
    || ! rg -q 'ConnectorInstallUrl\.isTrusted' "$connector_loader" "$connector_activity" \
    || ! rg -q 'lowerHost\.endsWith\("\.openai\.com"\)' "$connector_url" \
    || ! rg -q 'lowerHost\.endsWith\("\.chatgpt\.com"\)' "$connector_url" \
    || ! rg -q 'Intent\.ACTION_VIEW' "$connector_activity" \
    || ! rg -q 'pendingConnectionProvider' "$connector_activity" \
    || ! rg -q 'MAXIMUM_AUTOMATIC_CONNECTION_CHECKS = 2' "$connector_activity" \
    || ! rg -q 'refreshConnectorAvailability' "$connector_activity" \
    || ! rg -q 'needsFreshDirectory' "$connector_activity" \
    || ! rg -q '!catalog\.hasReusableDirectoryState' "$connector_activity" \
    || ! rg -q 'catalog\.getRevision\(\) < pendingConnectionCheckRevision' "$connector_activity" \
    || ! rg -U -q 'if \(catalog\.getPhase\(\) == ConnectorPhase\.LOADING\) \{\n[[:space:]]*// A refresh that began before the browser return' "$connector_activity" \
    || ! rg -q 'connector_manage_sign_in_provider' "$connector_activity" \
    || ! rg -q 'connector_sign_in_provider' "$connector_activity" \
    || ! rg -q 'connector_manage_sign_in_provider' \
      "$PROJECT_ROOT/app/src/main/res/values/strings.xml" \
      "$PROJECT_ROOT/app/src/main/res/values-de/strings.xml" \
    || ! rg -q 'connector_sign_in_provider' \
      "$PROJECT_ROOT/app/src/main/res/values/strings.xml" \
      "$PROJECT_ROOT/app/src/main/res/values-de/strings.xml" \
    || ! rg -q 'protected void onResume()' "$connector_activity" \
    || ! rg -q 'ConnectorSelection\.afterSuccessfulConnection' "$connector_activity" \
    || ! rg -q 'connector_connection_checking' \
      "$PROJECT_ROOT/app/src/main/res/values/strings.xml" \
      "$PROJECT_ROOT/app/src/main/res/values-de/strings.xml" \
    || ! rg -q 'catalog == lastCatalogSnapshot' "$connector_activity" \
    || ! rg -q 'AgentRuntimeService\.connectorCatalogSnapshot' "$connector_activity" \
    || ! rg -q 'AgentRuntimeService\.areConnectorsCallable' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'CodexAppMention\.create' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q '"app://" \+ id' "$PROJECT_ROOT/modules/core/src/main/java/de/agentcodi/core/CodexAppMention.java" \
    || ! rg -q '\$gmail \$github' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q 'ConnectorCatalogLoaderTest\.run' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/TestMain.java" \
    || ! rg -q 'failedDirectoryCannotBeLaunderedByRuntimeRefresh' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/ConnectorCatalogLoaderTest.java" \
    || ! rg -q 'suppressesUnboundedConnectorCatalogNotifications' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/CodexSessionControllerTest.java" \
    || ! rg -q -- '--connector-roundtrip' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q -- '--emit-oversized-app-list-update' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -Uq 'android:name="de\.agentcodi\.app\.ConnectorActivity"[[:space:][:print:]]{0,220}android:exported="false"' "$manifest" \
    || rg -n 'SharedPreferences|getSharedPreferences|onSaveInstanceState|WebView' "$connector_activity" \
    || rg -n 'mcpServer/oauth/login|auth\.json|client_secret|access_token|refresh_token|api[_ -]?key' \
      "$connector_activity" "$connector_client" "$connector_contracts"; then
  echo "Gmail/GitHub connector discovery, transient selection, or authentication boundary is incomplete." >&2
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
if ! rg -Uq 'android:name="de\.agentcodi\.app\.McpManagementActivity"[[:space:][:print:]]{0,220}android:exported="false"' "$manifest" \
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

if rg -q 'DEBUG_KEYSTORE=|-genkeypair' "$apk_builder" \
    || ! rg -Fq 'scripts/sign-debug-apk.py' "$apk_builder" \
    || ! rg -Fq 'Debug signer certificate does not match the pinned development identity.' "$apk_builder" \
    || ! rg -Fq 'Release APK must not use the public development test certificate.' "$apk_builder" \
    || [ ! -s "$PROJECT_ROOT/scripts/debug-signing/testkey.pk8.b64" ] \
    || [ ! -s "$PROJECT_ROOT/scripts/debug-signing/testkey.x509.pem" ]; then
  echo "Stable debug signing or the release test-key rejection is incomplete." >&2
  exit 1
fi
python3 "$PROJECT_ROOT/scripts/sign-debug-apk.py" --certificate-sha256 >/dev/null

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
browser_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/WorkspaceBrowserActivity.java"
workspace_reader="$PROJECT_ROOT/modules/native-engine/src/main/cpp/workspace_file_reader.cpp"
workspace_access="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileAccess.java"
workspace_archive="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceArchive.java"
workspace_export_file="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceExportFile.java"
workspace_export_transaction="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceExportTransaction.java"
android_document_export_destination="$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/AndroidDocumentExportDestination.java"
workspace_export_test="$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceExportTest.java"
if ! rg -q 'WorkspaceExportFile\.list' "$workspace_exporter" \
    || ! rg -q 'WorkspaceExportFile\.copyTo' "$workspace_exporter" \
    || ! rg -q 'WorkspaceArchive\.write' "$workspace_exporter" \
    || ! rg -q 'WorkspaceExportTransaction\.execute' "$workspace_exporter" \
    || ! rg -q 'WorkspaceExportTransaction\.execute' "$image_exporter" \
    || ! rg -q 'destination\.rollback\(\)' "$workspace_export_transaction" \
    || ! rg -q 'DocumentsContract\.deleteDocument\(resolver, destination\)' "$android_document_export_destination" \
    || ! rg -q 'resolver\.delete\(destination, null, null\)' "$android_document_export_destination" \
    || ! rg -q 'openOutputStream\(destination, "wt"\)' "$android_document_export_destination" \
    || ! rg -q 'NativeWorkspaceFileAccess\.opener' "$workspace_exporter" \
    || ! rg -q 'NativeWorkspaceDirectoryCatalog\.reader' "$workspace_exporter" \
    || ! rg -q 'layout\.getWorkspace\(\)' "$workspace_exporter" \
    || ! rg -q '"unix:nlink,fileKey,ctime"' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileBoundary.java" \
    || ! rg -q 'expectedFileKey\.equals\(fileKey\)' "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileBoundary.java" \
    || ! rg -q 'SecureDirectoryStream' "$workspace_access" \
    || ! rg -q 'hasSameOpenedSnapshot' "$workspace_archive" \
    || ! rg -q 'maximumScannedEntries' "$workspace_archive" \
    || ! rg -q 'getOmittedEntryCount' "$workspace_archive" "$workspace_exporter" \
    || ! rg -q 'PortablePathIndex' "$workspace_archive" \
    || ! rg -q 'MAXIMUM_SCANNED_ENTRIES = 65536' "$workspace_exporter" \
    || ! rg -q 'scannedEntryCount' "$workspace_export_file" \
    || ! rg -q 'files\.size\(\) >= maximumFiles' "$workspace_export_file" \
    || ! rg -q 'defaultMaximumScannedEntries' "$workspace_export_file" \
    || ! rg -q 'getNano\(\) / 1000' "$workspace_export_file" \
    || ! rg -q 'doesNotChargeSkippedEntriesAgainstRegularFileLimit' "$workspace_export_test" \
    || ! rg -q 'keepsSkippedEntriesBoundedBySeparateScanLimit' "$workspace_export_test" \
    || ! rg -q 'archivesAcrossProviderTimestampPrecision' "$workspace_export_test" \
    || ! rg -q 'archivesOnlyTheSelectedFolderContents' "$workspace_export_test" \
    || ! rg -q 'archivesRegularFilesWhileOmittingHardLinks' "$workspace_export_test" \
    || ! rg -q 'omitsPortableArchiveNameCollisionWithoutBlockingSibling' "$workspace_export_test" \
    || ! rg -q 'failed file export leaves no target bytes' "$workspace_export_test" \
    || ! rg -q 'failed archive export leaves no target bytes' "$workspace_export_test" \
    || ! rg -q 'destination close failure leaves no target bytes' "$workspace_export_test" \
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
    || rg -q 'workspace_file_choose|workspace_archive_export|WorkspaceFileExporter' \
      "$settings_activity" \
    || ! rg -q 'Intent\.ACTION_CREATE_DOCUMENT' "$browser_activity" \
    || ! rg -q 'R\.string\.browser_export' "$browser_activity" \
    || ! rg -q 'R\.string\.browser_directory_export' "$browser_activity" \
    || rg -q 'Intent\.ACTION_OPEN_DOCUMENT_TREE' "$settings_activity" "$browser_activity"; then
  echo "Bounded all-type workspace file and archive export is incomplete." >&2
  exit 1
fi


# Package Edition keeps explicit file areas and stages imports only in workspace.
package_file_scope="$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/WorkspaceFileScope.java"
package_file_scope_test="$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceFileScopeTest.java"
if ! rg -q 'scope\.root\(layout\)' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/WorkspaceBrowserRepository.java" \
    || ! rg -q 'scope\.reader\(NativeWorkspaceDirectoryCatalog\.reader\(\)\)' "$workspace_exporter" \
    || ! rg -q 'scope\.opener\(NativeWorkspaceFileAccess\.opener\(\)\)' "$workspace_exporter" \
    || ! rg -q 'MANAGED_PACKAGES' "$package_file_scope" \
    || ! rg -q 'USER_PACKAGES' "$package_file_scope" \
    || ! rg -q 'requireCanonicalRoot' "$package_file_scope" \
    || ! rg -q '"auth\.conf\.d"' "$package_file_scope" \
    || ! rg -q 'Intent\.ACTION_OPEN_DOCUMENT' "$browser_activity" \
    || ! rg -q 'WorkspaceImportGrant\.fromResultIntentFlags' "$browser_activity" \
    || ! rg -q 'WorkspaceFileImporter\.importDocument' "$browser_activity" \
    || ! rg -q 'export\.repository' "$browser_activity" \
    || ! rg -q 'WorkspaceFileScopeTest\.run' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/TestMain.java" \
    || ! rg -q 'excludesAccountLinksAndCredentialPathsFromArchives' "$package_file_scope_test" \
    || ! rg -q 'importsPackageArtifactsWithoutInstalling' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/WorkspaceImportTest.java" \
    || rg -q 'takePersistableUriPermission|ACTION_OPEN_DOCUMENT_TREE' "$browser_activity"; then
  echo "Explicit package file areas, account exclusions, or workspace-only imports are incomplete." >&2
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
chinese_strings="$PROJECT_ROOT/app/src/main/res/values-b+zh+Hans/strings.xml"
default_names="$(rg -o 'name="[a-z0-9_]+"' "$default_strings" | sort -u)"
german_names="$(rg -o 'name="[a-z0-9_]+"' "$german_strings" | sort -u)"
chinese_names="$(rg -o 'name="[a-z0-9_]+"' "$chinese_strings" | sort -u)"
if [ "$default_names" != "$german_names" ] \
    || [ "$default_names" != "$chinese_names" ] \
    || ! rg -q '<locale android:name="en"' "$PROJECT_ROOT/app/src/main/res/xml/locales_config.xml" \
    || ! rg -q '<locale android:name="de"' "$PROJECT_ROOT/app/src/main/res/xml/locales_config.xml" \
    || ! rg -q '<locale android:name="zh-Hans"' "$PROJECT_ROOT/app/src/main/res/xml/locales_config.xml" \
    || ! rg -q 'android:localeConfig="@xml/locales_config"' "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
    || ! rg -q 'AppLanguage\.attach' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java" \
    || ! rg -q 'AppLanguage\.attach' "$settings_activity" \
    || ! rg -q 'UiLanguage\.SYSTEM' "$settings_activity" \
    || ! rg -q 'LocaleManager' "$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/AppLanguage.java" \
    || ! rg -q 'LocaleManager' "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/RuntimeText.java"; then
  echo "English/German/Simplified Chinese resources or the device-language selection contract are incomplete." >&2
  exit 1
fi

chat_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/MainActivity.java"
ui_theme="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/UiTheme.java"
chat_icon_count="$(find "$PROJECT_ROOT/app/src/main/res/drawable" -maxdepth 1 \
  -type f -name 'ic_chat_*.xml' | wc -l | tr -d '[:space:]')"
if [ "$chat_icon_count" != "17" ] \
    || rg -q 'import android\.widget\.Button;|theme\.(compactButton|primaryButton|secondaryButton)\(' "$chat_activity" \
    || ! rg -q 'import android\.widget\.ImageButton;' "$chat_activity" \
    || ! rg -q 'R\.drawable\.ic_chat_folder' "$chat_activity" \
    || ! rg -q 'R\.drawable\.ic_chat_add' "$chat_activity" \
    || ! rg -q 'R\.drawable\.ic_chat_connectors' "$chat_activity" \
    || ! rg -q 'R\.drawable\.ic_chat_send' "$chat_activity" \
    || ! rg -q 'R\.drawable\.ic_chat_stop' "$chat_activity" \
    || ! rg -q 'setMinimumWidth\(dp\(48\)\)' "$ui_theme" \
    || ! rg -q 'setMinimumHeight\(dp\(48\)\)' "$ui_theme" \
    || ! rg -q 'setContentDescription\(description\)' "$ui_theme" \
    || ! rg -q 'setTooltipText\(description\)' "$ui_theme" \
    || ! rg -q 'ChatUiIconResourcesTest\.run' "$PROJECT_ROOT/tests/java/de/agentcodi/tests/TestMain.java"; then
  echo "The accessible icon-only chat action contract is incomplete." >&2
  exit 1
fi

licenses_activity="$PROJECT_ROOT/app/src/main/java/de/agentcodi/app/LicensesActivity.java"
agentcodi_license_resource="$PROJECT_ROOT/app/src/main/res/raw/agentcodi_apache_2_0.txt"
material_icons_resource="$PROJECT_ROOT/app/src/main/res/raw/material_icons_notice.txt"
if ! rg -q 'LicensesActivity' "$PROJECT_ROOT/app/src/main/AndroidManifest.xml" \
    || [ ! -f "$agentcodi_license_resource" ] \
    || [ ! -f "$material_icons_resource" ] \
    || ! rg -q 'R\.raw\.agentcodi_apache_2_0' "$licenses_activity" \
    || ! rg -q 'R\.raw\.material_icons_notice' "$licenses_activity" \
    || ! rg -q 'e083cc60a0828fdd3b404cea0cb8a5b900e9c23e' "$material_icons_resource" \
    || ! rg -q 'e083cc60a0828fdd3b404cea0cb8a5b900e9c23e' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q 'e083cc60a0828fdd3b404cea0cb8a5b900e9c23e' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt" \
    || ! rg -q 'license_show_text' "$licenses_activity" "$default_strings" "$german_strings" \
    || ! rg -q 'third-party/codex/LICENSE' "$licenses_activity" \
    || ! rg -q 'third-party/codex/NOTICE' "$licenses_activity" \
    || ! rg -q 'R\.raw\.third_party_notices' "$licenses_activity" \
    || ! rg -q 'third-party/codex/MPL-SOURCE-OFFER[.]txt' "$licenses_activity" \
    || ! rg -q 'third-party/codex/MPL-SOURCES[.]zip' "$licenses_activity" \
    || ! rg -q 'Intent[.]ACTION_CREATE_DOCUMENT' "$licenses_activity" \
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

if ! rg -q 'mark_inherited_descriptors_close_on_exec' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'SYS_getdents64' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q -- '--inherited-fd-probe' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'descriptor_probe == "EBADF"' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp"; then
  echo "The app-server exec boundary must reject unrelated inherited descriptors." >&2
  exit 1
fi

if ! rg -q 'setpgid\(0, 0\)' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'signal_process_group' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'PR_SET_CHILD_SUBREAPER' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'PR_GET_CHILD_SUBREAPER' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'kMaximumProcProcessEntries' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'read_process_identity' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q 'owned_direct_children' "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.cpp" \
    || ! rg -q -- '--process-tree-probe' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'setsid\(\)' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'single app-server supervisor boundary' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'restore subreaper state' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'kill\(grandchild, 0\)' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp" \
    || ! rg -q 'detached SIGTERM-ignoring grandchild' "$PROJECT_ROOT/tests/cpp/agentcodi_engine_test.cpp"; then
  echo "The app-server supervisor must terminate and reap its complete process tree, including detached sessions." >&2
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
toolchain_shell="$PROJECT_ROOT/modules/native-engine/src/main/cpp/package_shell_main.cpp"
if ! rg -Uq 'android:name="de\.agentcodi\.app\.TerminalActivity"[[:space:][:print:]]{0,220}android:exported="false"' "$manifest" \
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
    || ! rg -q 'setPermissionProfile' "$terminal_session" \
    || ! rg -q '"permissionProfile", requestedPermissionProfile' "$terminal_session" \
    || ! rg -q '"tty", Boolean\.TRUE' "$terminal_session" \
    || ! rg -q 'OUTPUT_BYTES_CAP = 8L \* 1024L \* 1024L' "$terminal_session" \
    || ! rg -q 'SERVER_TIMEOUT_MS = 30L \* 60L \* 1000L' "$terminal_session"; then
  echo "The Full-access app-server PTY, runtime facade, or non-exported UI route is incomplete." >&2
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

# Active Package Edition payload and start API contract. Retired tool helpers
# must not return; only the bounded alias migration remains.
if ! rg -q 'package_shell_main\.cpp' "$apk_builder" \
    || ! rg -q 'retireLegacyToolAliases' "$storage_layout" "$runtime_service" \
    || rg -q 'layout\.preparePackagedTool(Aliases|Runtime)' "$runtime_service" \
    || rg -q '(NODE|NPM|PYTHON|RIPGREP)_(VERSION|LIBRARY_NAME|URL|SHA256)|toolchain_elf_(guard|attestor|injector)\.cpp' "$apk_builder" \
    || rg -q 'AGENTCODI_(TOOLCHAIN|TOOL_BIN|TOOL_RUNTIME|NODE_VERSION|NPM_VERSION|PYTHON_VERSION|RIPGREP_VERSION)=' "$native_process" \
    || rg -q 'validate_tool_alias' "$native_process" \
    || ! rg -q 'EXPECTED_NATIVE_FILES' "$apk_builder" \
    || ! rg -q 'unresolved ELF dependency' "$apk_builder" \
    || ! rg -q 'retired user-tool assets' "$apk_builder" \
    || ! rg -Fq 'PackageDiagnosticsCommand.create()' "$terminal_activity" \
    || ! rg -q 'third-party/zlib/ZLIB-LICENSE' "$licenses_activity" \
    || ! rg -q 'SHELL=" \+ std::string\(kSystemShell\)' "$native_process"; then
  echo "The minimal APK, retired aliases, package diagnostics or legal assets are inconsistent." >&2
  exit 1
fi

if [ -e "$PROJECT_ROOT/modules/storage/src/main/java/de/agentcodi/storage/PackagedToolRuntime.java" ] \
    || [ -e "$core_root/ToolchainCommand.java" ] \
    || find "$PROJECT_ROOT/modules/native-engine/src/main/cpp" -maxdepth 1 \
         -name 'toolchain_*' -print -quit | rg -q . \
    || rg -n '(NODE|NPM|PYTHON|RIPGREP)_RUNTIME_|TOOL_RUNTIME_' "$core_root/BuildIdentity.java" \
    || rg -n 'nodeExecutable|pythonExecutable|ripgrepExecutable|toolBinaryDirectory|toolRuntimeDirectory|String toolchain' \
         "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeEngine.java" \
         "$PROJECT_ROOT/modules/runtime/src/main/java/de/agentcodi/runtime/NativeAppServerTransport.java" \
    || rg -n 'node_executable|python_executable|ripgrep_executable|toolchain_directory|tool_binary_directory|tool_runtime_directory|just_in_time_approvals' \
         "$PROJECT_ROOT/modules/native-engine/src/main/cpp/app_server_process.h" "$native_process" \
         "$PROJECT_ROOT/modules/native-engine/src/main/cpp/jni_bridge.cpp" \
    || rg -n 'secureChild\(.*"(tool-bin|tool-runtime|toolchain)"|preparePackagedTool|is(Node|Npm|Python|Ripgrep)RuntimeEnabled' \
         "$storage_layout" "$runtime_service"; then
  echo "Retired APK tool sources, activation pins or startup prerequisites remain." >&2
  exit 1
fi

if rg -n 'workspace/console|SharedPreferences|onSaveInstanceState' "$terminal_activity" "$terminal_session" "$toolchain_shell" \
    || rg -n 'CODEX_HOME|auth\.json|"sandboxPolicy"' "$terminal_activity" "$terminal_session" "$toolchain_shell"; then
  echo "Terminal output/input must remain transient and outside the authentication boundary." >&2
  exit 1
fi

if ! rg -q 'command/exec/outputDelta' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'command/exec/write' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'command/exec/resize' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'command/exec/terminate' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'config/read' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'config/batchWrite' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'config/mcpServer/reload' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'minimal-package-shell-ok' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'check_package_prefix' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp" \
    || ! rg -q 'check_stdio_package_environment' "$PROJECT_ROOT/tests/cpp/android_app_server_bootstrap_smoke.cpp"; then
  echo "Minimal Package Edition runtime smoke coverage is incomplete." >&2
  exit 1
fi

if ! rg -q 'VERSION_NAME = "0\.1\.2"' "$core_root/BuildIdentity.java" \
    || ! rg -q 'VERSION_CODE = 5' "$core_root/BuildIdentity.java" \
    || ! rg -q 'CODEX_RUNTIME_VERSION = "0\.156\.1-termux\.1"' "$core_root/BuildIdentity.java" \
    || ! rg -q 'android:versionName="0\.1\.2"' "$manifest" \
    || ! rg -q 'android:versionCode="5"' "$manifest" \
    || ! rg -q 'APP_VERSION="0\.1\.2"' "$apk_builder" \
    || ! rg -q 'VERSION_CODE="5"' "$apk_builder" \
    || ! rg -q 'CODEX_ANDROID_VERSION="0\.156\.1-termux\.1"' "$apk_builder" \
    || ! rg -q 'CODEX_TERMUX_SOURCE_TAG="v0\.156\.1-termux\.1"' "$apk_builder" \
    || ! rg -q 'CODEX_TERMUX_SOURCE_COMMIT="ea762071ec4acbf1531fcc7daf47524836f70a09"' "$apk_builder" \
    || ! rg -q 'CODEX_UPSTREAM_SOURCE_TAG="rust-v0\.156\.1"' "$apk_builder" \
    || ! rg -q 'CODEX_UPSTREAM_SOURCE_COMMIT="b412ff32c417f855c2b2d1581b77058eed87c84b"' "$apk_builder" \
    || ! rg -q 'CODEX_ANDROID_SHA256="44cee2f3a4a110fd79d4f7d61378d46fd72406f45cffb3163e809d63e86d946a"' "$apk_builder" \
    || ! rg -q 'CODEX_APP_SERVER_SOURCE_SHA256="6cbfa7f1660095e9cf2df7de242014579a0fb0d42545652fb0e22d1b6c8571a5"' "$apk_builder" \
    || ! rg -q 'CODEX_CODE_MODE_HOST_SHA256="8afb196579c3fd8ecac558dbebfcba5467f91389b3754e485728ce6904e6ceaf"' "$apk_builder" \
    || ! rg -q 'CODEX_APP_SERVER_ANDROID_SHA256="cf1b406252928b0d68cb0f8f81adde6a02bf357a7fffb762d10cb503a235be06"' "$apk_builder" \
    || ! rg -q 'CODEX_LICENSE_SHA256="d17f227e4df5da1600391338865ce0f3055211760a36688f816941d58232d8dc"' "$apk_builder" \
    || ! rg -q 'CODEX_NOTICE_SHA256="8228749dd4dd6026baed0442f80e911308430478449285c865b188d97e6a013c"' "$apk_builder" \
    || ! rg -q 'CODEX_SCHEMA_BUNDLE_SHA256="eb1ba91bd0fab656523092f6ed7de3ea7aef278921a650f14dc871ae7dcfaf84"' "$apk_builder" \
    || ! rg -q 'CODEX_V2_SCHEMA_BUNDLE_SHA256="995fc3b8f8c469f6787e8fc5be4038c4f31359025edd8480b862e83355f3bf3b"' "$apk_builder" \
    || ! rg -q 'app-server generate-json-schema' "$apk_builder" \
    || ! rg -q '0\.156\.1-termux\.1' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q '0\.156\.1-termux\.1' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt" \
    || ! rg -q 'ea762071ec4acbf1531fcc7daf47524836f70a09' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q 'ea762071ec4acbf1531fcc7daf47524836f70a09' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt" \
    || ! rg -q 'b412ff32c417f855c2b2d1581b77058eed87c84b' "$PROJECT_ROOT/NOTICE.md" \
    || ! rg -q 'b412ff32c417f855c2b2d1581b77058eed87c84b' "$PROJECT_ROOT/app/src/main/res/raw/third_party_notices.txt"; then
  echo "The 0.1.2 / Codex 0.156.1-termux.1 identity is inconsistent." >&2
  exit 1
fi

if ! rg -Fq 'CODEX_ANDROID_ARCHIVE="$("$PROJECT_ROOT/scripts/update-codex-runtime.sh" --select-build-archive)"' "$apk_builder" \
    || ! rg -Fq 'buildOptions.selectBuildArchive(cache, pins.get("CODEX_ANDROID_SHA256"))' \
      "$PROJECT_ROOT/scripts/java/de/agentcodi/tools/CodexRuntimeUpdater.java" \
    || ! rg -Fq 'pinnedHash.equals(digest(selected, "SHA-256"))' \
      "$PROJECT_ROOT/scripts/java/de/agentcodi/tools/CodexLocalSource.java" \
    || ! rg -q 'refusesToHideReplacedBuildInputsBehindTheCache' \
      "$PROJECT_ROOT/tests/java/de/agentcodi/tools/CodexRuntimeUpdaterTest.java" \
    || ! rg -Fq 'verify_file_sha256 "$CODEX_ANDROID_ARCHIVE" "$CODEX_ANDROID_SHA256"' "$apk_builder" \
    || ! rg -Fq 'https://github.com/DioNanos/codex-termux/releases/download/v' "$apk_builder" \
    || rg -q 'Mcpasi/codex-termux|-agentcodi' "$PROJECT_ROOT/scripts/java/de/agentcodi/tools/CodexPackageMetadata.java"; then
  echo "Community Codex must remain SHA-256-pinned and reject the previous sandbox channel." >&2
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
    || ! rg -q 'shell_environment_policy=\{inherit=\\"core\\"' "$native_process" \
    || ! rg -q 'include_only=\[' "$native_process" \
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


# One final payload contract is shared by source checks and the assembled APK.
python3 -B "$PROJECT_ROOT/scripts/package-edition/verify-apk-contract.py" --check-sources
