package de.agentcodi.tests;

import de.agentcodi.core.CodexMcpConfigurationRpc;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.mcp.McpConfigurationNotice;
import de.agentcodi.mcp.McpConfigurationPhase;
import de.agentcodi.mcp.McpConfigurationSnapshot;
import de.agentcodi.mcp.McpServerConfiguration;
import de.agentcodi.mcp.McpServerDraft;
import de.agentcodi.mcp.McpServerOrigin;
import de.agentcodi.mcp.McpTransport;
import de.agentcodi.mcp.client.McpConfigurationController;
import de.agentcodi.mcp.client.McpConfigurationLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpConfigurationControllerTest {
    private static final String VERSION_A = version('a');
    private static final String VERSION_B = version('b');

    private McpConfigurationControllerTest() {
    }

    public static int run() throws Exception {
        loadsSecretFreeConfigurationAndClassifiesOrigins();
        validatesTheNarrowWriteBoundary();
        serializesAddEditEnableDeleteAndReload();
        preservesRepeatedStdioOptionsFromEditorInput();
        roundTripsMaximumWritableServerProjection();
        preservesUnchangedUnprojectableAndNormalizedFields();
        requiresPromptBeforeEnablingAnExistingServer();
        hardensPerToolApprovalOverridesWithoutDroppingOtherFields();
        reportsReloadFailureAfterAnAcceptedWrite();
        return 9;
    }

    private static void loadsSecretFreeConfigurationAndClassifiesOrigins() throws Exception {
        Map<String, Object> servers = new LinkedHashMap<String, Object>();
        servers.put("local-safe", JsonCodec.object(
            "command", "node",
            "args", JsonCodec.array("server.js"),
            "enabled", Boolean.FALSE,
            "required", Boolean.FALSE,
            "startup_timeout_sec", Long.valueOf(12L),
            "tool_timeout_sec", Long.valueOf(80L),
            "default_tools_approval_mode", "prompt",
            "enabled_tools", JsonCodec.array("search"),
            "tools", JsonCodec.object(
                "search", JsonCodec.object("approval_mode", "approve")
            )
        ));
        servers.put("project-safe", JsonCodec.object(
            "url", "https://project.example/mcp",
            "enabled", Boolean.TRUE
        ));
        servers.put("secret-user", JsonCodec.object(
            "url", "https://secret.example/mcp?token=sk-private-value",
            "enabled", Boolean.FALSE,
            "http_headers", JsonCodec.object(
                "Authorization", "Bearer private-header-value"
            )
        ));
        servers.put("secret-args", JsonCodec.object(
            "command", "node",
            "args", JsonCodec.array(
                "server.js",
                "--password",
                "fixture-password-value"
            ),
            "enabled", Boolean.FALSE,
            "required", Boolean.FALSE,
            "default_tools_approval_mode", "prompt"
        ));
        servers.put("secret-field", JsonCodec.object(
            "command", "node",
            "args", JsonCodec.array("server.js"),
            "client_secret", "fixture-client-field-value",
            "enabled", Boolean.FALSE,
            "required", Boolean.FALSE,
            "default_tools_approval_mode", "prompt"
        ));

        Map<String, Object> origins = new LinkedHashMap<String, Object>();
        addOrigin(origins, "mcp_servers.local-safe.command", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.local-safe.enabled", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.project-safe.url", "project", VERSION_B);
        addOrigin(origins, "mcp_servers.secret-user.url", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.secret-user.http_headers", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.secret-args.command", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.secret-args.args", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.secret-field.command", "user", VERSION_A);
        addOrigin(origins, "mcp_servers.secret-field.client_secret", "user", VERSION_A);

        McpConfigurationSnapshot snapshot = new McpConfigurationLoader(
            new StaticRpc(JsonCodec.object(
                "config", JsonCodec.object("mcp_servers", servers),
                "origins", origins,
                "layers", null
            ))
        ).load(9L, McpConfigurationNotice.NONE);

        TestSupport.assertEquals(McpConfigurationPhase.READY, snapshot.getPhase(), "phase");
        TestSupport.assertEquals(VERSION_A, snapshot.getExpectedVersion(), "user version");
        TestSupport.assertEquals(Integer.valueOf(5), Integer.valueOf(snapshot.getServers().size()), "server count");

        McpServerConfiguration local = find(snapshot, "local-safe");
        TestSupport.assertEquals(McpServerOrigin.USER, local.getOrigin(), "user origin");
        TestSupport.assertTrue(local.isEditable(), "safe user entry editable");
        TestSupport.assertEquals(McpTransport.STDIO, local.getTransport(), "stdio transport");
        TestSupport.assertEquals(Integer.valueOf(12), Integer.valueOf(local.getStartupTimeoutSeconds()), "startup timeout");
        TestSupport.assertTrue(
            local.hasToolApprovalOverrides(),
            "per-tool approval policy is projected explicitly"
        );
        TestSupport.assertFalse(
            local.hasPreservedAdvancedFields(),
            "per-tool approval policy is not hidden as a generic advanced field"
        );

        McpServerConfiguration project = find(snapshot, "project-safe");
        TestSupport.assertEquals(McpServerOrigin.PROJECT, project.getOrigin(), "project origin");
        TestSupport.assertFalse(project.isUserOwned(), "project entry not user owned");
        TestSupport.assertFalse(project.isEditable(), "project entry view-only");

        McpServerConfiguration secret = find(snapshot, "secret-user");
        TestSupport.assertTrue(secret.isUserOwned(), "sensitive entry origin retained");
        TestSupport.assertFalse(secret.isEditable(), "sensitive entry cannot open editor");
        TestSupport.assertTrue(secret.hasSensitiveValuesHidden(), "sensitive values hidden");
        TestSupport.assertEquals("", secret.getUrl(), "secret-bearing URL omitted");

        McpServerConfiguration secretArguments = find(snapshot, "secret-args");
        TestSupport.assertFalse(
            secretArguments.isEditable(),
            "split credential arguments cannot open editor"
        );
        TestSupport.assertTrue(
            secretArguments.hasSensitiveValuesHidden(),
            "split credential arguments are classified as sensitive"
        );
        TestSupport.assertTrue(
            secretArguments.getArguments().isEmpty(),
            "split credential arguments are omitted"
        );

        McpServerConfiguration secretField = find(snapshot, "secret-field");
        TestSupport.assertFalse(
            secretField.isEditable(),
            "named credential field cannot open editor"
        );
        TestSupport.assertTrue(
            secretField.hasSensitiveValuesHidden(),
            "named credential field is classified as sensitive"
        );

        String projected = flatten(snapshot);
        TestSupport.assertFalse(projected.contains("sk-private"), "token omitted");
        TestSupport.assertFalse(projected.contains("private-header"), "header value omitted");
        TestSupport.assertFalse(
            projected.contains("fixture-password-value"),
            "split password value omitted"
        );
        TestSupport.assertFalse(
            projected.contains("fixture-client-field-value"),
            "named client secret value omitted"
        );
        TestSupport.assertFalse(projected.contains("/private/codex-home"), "origin path omitted");
        TestSupport.assertFalse(projected.contains("config.toml"), "config filename omitted");
    }

    private static void validatesTheNarrowWriteBoundary() {
        Map<String, Object> valid = writeParameters(
            JsonCodec.object(
                "keyPath", "mcp_servers.safe-server",
                "value", JsonCodec.object(
                    "url", "https://example.com/mcp",
                    "enabled", Boolean.FALSE,
                    "required", Boolean.FALSE,
                    "startup_timeout_sec", Long.valueOf(10L),
                    "tool_timeout_sec", Long.valueOf(60L),
                    "default_tools_approval_mode", "prompt"
                ),
                "mergeStrategy", "replace"
            )
        );
        TestSupport.assertTrue(
            CodexMcpConfigurationRpc.isValidWriteRequest(valid),
            "bounded whole-server add allowed"
        );

        Map<String, Object> withPath = copy(valid);
        withPath.put("filePath", "/private/codex-home/config.toml");
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(withPath),
            "caller-selected file path denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.url",
                "https://example.com/mcp?token=secret"
            ))),
            "secret-bearing URL denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.env.API_KEY",
                "fixture-secret"
            ))),
            "static environment write denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.args",
                JsonCodec.array("password=x")
            ))),
            "password assignment argument denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.args",
                JsonCodec.array("client_secret=fixture-client-secret")
            ))),
            "client secret assignment argument denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.args",
                JsonCodec.array("--password", "fixture-password-value")
            ))),
            "password split across arguments denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.args",
                JsonCodec.array("--client-secret", "&")
            ))),
            "punctuation-only client secret split across arguments denied"
        );
        TestSupport.assertTrue(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.args",
                JsonCodec.array(
                    "--password-stdin",
                    "--client-secret-file",
                    "credential-input.txt"
                )
            ))),
            "non-value credential transport arguments remain allowed"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.unsafe.name.enabled",
                Boolean.TRUE
            ))),
            "ambiguous dotted server name denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.default_tools_approval_mode",
                "approve"
            ))),
            "automatic MCP tool approval denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.enabled",
                Boolean.TRUE
            ))),
            "enable without an atomic prompt edit denied"
        );

        Map<String, Object> enableWithoutToolClear = JsonCodec.object(
            "edits", JsonCodec.array(
                edit("mcp_servers.safe.default_tools_approval_mode", "prompt"),
                edit("mcp_servers.safe.enabled", Boolean.TRUE)
            ),
            "expectedVersion", VERSION_A,
            "reloadUserConfig", Boolean.FALSE
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(enableWithoutToolClear),
            "enable without an atomic per-tool override clear denied"
        );
        Map<String, Object> safelyHardenedEnable = JsonCodec.object(
            "edits", JsonCodec.array(
                edit("mcp_servers.safe.tools", null),
                edit("mcp_servers.safe.default_tools_approval_mode", "prompt"),
                edit("mcp_servers.safe.enabled", Boolean.TRUE)
            ),
            "expectedVersion", VERSION_A,
            "reloadUserConfig", Boolean.FALSE
        );
        TestSupport.assertTrue(
            CodexMcpConfigurationRpc.isValidWriteRequest(safelyHardenedEnable),
            "enable with atomic prompt and per-tool override clear allowed"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.tools",
                JsonCodec.object("search", JsonCodec.object("approval_mode", "approve"))
            ))),
            "per-tool approval policy writes denied"
        );
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(writeParameters(edit(
                "mcp_servers.safe.tools.search.approval_mode",
                "prompt"
            ))),
            "caller-controlled nested tool paths denied"
        );

        Map<String, Object> autoReload = copy(valid);
        autoReload.put("reloadUserConfig", Boolean.TRUE);
        TestSupport.assertFalse(
            CodexMcpConfigurationRpc.isValidWriteRequest(autoReload),
            "implicit broad user-config reload denied"
        );
    }

    private static void serializesAddEditEnableDeleteAndReload() throws Exception {
        final StatefulRpc rpc = new StatefulRpc();
        final McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "initial refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "initial configuration");

        McpServerDraft added = draft(
            "expert-http",
            "https://example.com/mcp",
            false,
            false,
            10,
            60,
            "prompt"
        );
        TestSupport.assertTrue(controller.save(added), "disabled add accepted");
        waitReady(controller, McpConfigurationNotice.SAVED, "added configuration");
        TestSupport.assertFalse(find(controller.snapshot(), "expert-http").isEnabled(), "new entry remains disabled");
        assertWriteHasNoPath(rpc.writes.get(0));
        Map<String, Object> firstEdit = firstEdit(rpc.writes.get(0));
        TestSupport.assertEquals("mcp_servers.expert-http", firstEdit.get("keyPath"), "whole add path");
        Map<String, Object> addedValue = JsonCodec.requireObject(firstEdit.get("value"), "added server");
        TestSupport.assertEquals(Boolean.FALSE, addedValue.get("enabled"), "add forced disabled");
        TestSupport.assertEquals(Boolean.FALSE, addedValue.get("required"), "add forced optional");

        TestSupport.assertTrue(controller.setEnabled("expert-http", true), "explicit enable accepted");
        waitReady(controller, McpConfigurationNotice.ENABLED, "enabled configuration");
        TestSupport.assertTrue(find(controller.snapshot(), "expert-http").isEnabled(), "entry enabled");
        TestSupport.assertEquals(
            null,
            findEdit(rpc.writes.get(1), "mcp_servers.expert-http.tools").get("value"),
            "enable atomically clears per-tool approval overrides"
        );

        McpServerDraft edited = draft(
            "expert-http",
            "https://example.net/mcp",
            true,
            true,
            25,
            90,
            "prompt"
        );
        TestSupport.assertTrue(controller.save(edited), "field-level edit accepted");
        waitReady(controller, McpConfigurationNotice.SAVED, "edited configuration");
        McpServerConfiguration saved = find(controller.snapshot(), "expert-http");
        TestSupport.assertEquals("https://example.net/mcp", saved.getUrl(), "endpoint edited");
        TestSupport.assertTrue(saved.isRequired(), "required edited");
        TestSupport.assertEquals(Integer.valueOf(25), Integer.valueOf(saved.getStartupTimeoutSeconds()), "timeout edited");
        for (Map<String, Object> change : edits(rpc.writes.get(2))) {
            TestSupport.assertTrue(
                ((String) change.get("keyPath")).startsWith("mcp_servers.expert-http."),
                "edit stays field-level"
            );
        }

        TestSupport.assertTrue(controller.delete("expert-http"), "delete accepted");
        waitReady(controller, McpConfigurationNotice.DELETED, "deleted configuration");
        TestSupport.assertTrue(controller.snapshot().getServers().isEmpty(), "entry removed");
        TestSupport.assertEquals(Integer.valueOf(4), Integer.valueOf(rpc.reloadCount), "every write explicitly reloaded");
        TestSupport.assertFalse(flatten(controller.snapshot()).contains("config.toml"), "write response path discarded");
        controller.close();
    }

    private static void roundTripsMaximumWritableServerProjection() throws Exception {
        String serverName = repeatedCharacter('s', 64);
        List<String> arguments = repeatedValues(64, repeatedCharacter('a', 2048));
        List<String> enabledTools = repeatedValues(128, repeatedCharacter('e', 160));
        List<String> disabledTools = repeatedValues(128, repeatedCharacter('d', 160));
        McpServerDraft draft = new McpServerDraft(
            serverName,
            McpTransport.STDIO,
            repeatedCharacter('c', 1024),
            arguments,
            "",
            false,
            false,
            3600,
            3600,
            "prompt",
            enabledTools,
            disabledTools
        );

        StatefulRpc rpc = new StatefulRpc();
        McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "maximum projection refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "maximum projection initial read");
        TestSupport.assertTrue(controller.save(draft), "maximum projection write accepted");
        waitReady(controller, McpConfigurationNotice.SAVED, "maximum projection round trip");

        McpServerConfiguration saved = find(controller.snapshot(), serverName);
        TestSupport.assertEquals(
            draft.getCommand(),
            saved.getCommand(),
            "maximum command retained"
        );
        TestSupport.assertEquals(arguments, saved.getArguments(), "maximum arguments retained");
        TestSupport.assertEquals(
            enabledTools,
            saved.getEnabledTools(),
            "maximum enabled tools retained"
        );
        TestSupport.assertEquals(
            disabledTools,
            saved.getDisabledTools(),
            "maximum disabled tools retained"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(rpc.writes.size()),
            "maximum projection written once"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(rpc.reloadCount),
            "maximum projection reloaded once"
        );
        controller.close();
    }

    private static void preservesRepeatedStdioOptionsFromEditorInput() throws Exception {
        List<String> expectedArguments = new ArrayList<String>();
        expectedArguments.add("--header");
        expectedArguments.add("A");
        expectedArguments.add("--header");
        expectedArguments.add("B");
        List<String> parsedArguments = McpServerDraft.parseLines(
            "  --header  \r\nA\n\n--header\r\n  B  \n"
        );
        TestSupport.assertEquals(
            expectedArguments,
            parsedArguments,
            "editor parser retains repeated STDIO options in order"
        );

        McpServerDraft draft = new McpServerDraft(
            "duplicate-stdio",
            McpTransport.STDIO,
            "runner",
            parsedArguments,
            "",
            false,
            false,
            10,
            60,
            "prompt",
            Collections.<String>emptyList(),
            Collections.<String>emptyList()
        );
        StatefulRpc rpc = new StatefulRpc();
        McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "duplicate STDIO refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "duplicate STDIO initial read");
        TestSupport.assertTrue(controller.save(draft), "duplicate STDIO write accepted");
        waitReady(controller, McpConfigurationNotice.SAVED, "duplicate STDIO round trip");

        Map<String, Object> added = JsonCodec.requireObject(
            firstEdit(rpc.writes.get(0)).get("value"),
            "duplicate STDIO server"
        );
        TestSupport.assertEquals(
            expectedArguments,
            added.get("args"),
            "batch write retains both repeated STDIO options"
        );
        TestSupport.assertEquals(
            expectedArguments,
            find(controller.snapshot(), "duplicate-stdio").getArguments(),
            "reload and read retain both repeated STDIO options"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(rpc.reloadCount),
            "duplicate STDIO write reloads exactly once"
        );
        controller.close();
    }

    private static void preservesUnchangedUnprojectableAndNormalizedFields()
        throws Exception {
        List<String> oversizedArguments = repeatedValues(65, "argument");
        List<String> oversizedEnabledTools = repeatedValues(129, "enabled-tool");
        List<String> duplicateDisabledTools = McpServerDraft.parseLines(
            "  duplicate-tool  \r\nduplicate-tool\n"
        );
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(duplicateDisabledTools.size()),
            "editor line parser preserves duplicate entries"
        );

        StatefulRpc rpc = new StatefulRpc();
        rpc.servers.put("lossless-edit", JsonCodec.object(
            "command", "runner",
            "args", oversizedArguments,
            "enabled", "legacy-enabled-value",
            "required", "legacy-required-value",
            "startup_timeout_sec", Long.valueOf(0L),
            "tool_timeout_sec", "legacy-timeout-value",
            "default_tools_approval_mode", "prompt",
            "enabled_tools", oversizedEnabledTools,
            "disabled_tools", duplicateDisabledTools,
            "custom_behavior", "preserve-me"
        ));
        McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "lossless fixture refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "lossless fixture ready");

        McpServerConfiguration projected = find(controller.snapshot(), "lossless-edit");
        TestSupport.assertTrue(projected.isEditable(), "advanced entry remains editable");
        TestSupport.assertTrue(
            projected.hasPreservedAdvancedFields(),
            "unprojectable known fields are disclosed as preserved"
        );
        TestSupport.assertTrue(
            projected.getArguments().isEmpty(),
            "oversized arguments are not projected into the editor"
        );
        TestSupport.assertTrue(
            projected.getEnabledTools().isEmpty(),
            "oversized enabled tools are not projected into the editor"
        );
        TestSupport.assertEquals(
            duplicateDisabledTools,
            projected.getDisabledTools(),
            "valid duplicate list entries remain visible in order"
        );

        McpServerDraft endpointEdit = new McpServerDraft(
            projected.getName(),
            projected.getTransport(),
            "runner-v2",
            projected.getArguments(),
            projected.getUrl(),
            projected.isEnabled(),
            projected.isRequired(),
            projected.getStartupTimeoutSeconds(),
            projected.getToolTimeoutSeconds(),
            "prompt",
            projected.getEnabledTools(),
            projected.getDisabledTools()
        );
        TestSupport.assertTrue(
            controller.save(endpointEdit),
            "editing a different field on an advanced entry is accepted"
        );
        waitReady(controller, McpConfigurationNotice.SAVED, "lossless endpoint edit");

        Map<String, Object> endpointWrite = rpc.writes.get(0);
        TestSupport.assertEquals(
            "runner-v2",
            findEdit(endpointWrite, "mcp_servers.lossless-edit.command").get("value"),
            "changed endpoint is written"
        );
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.args");
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.enabled");
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.required");
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.startup_timeout_sec");
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.tool_timeout_sec");
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.enabled_tools");
        assertNoEdit(endpointWrite, "mcp_servers.lossless-edit.disabled_tools");

        Map<String, Object> retained = rpc.servers.get("lossless-edit");
        TestSupport.assertEquals(
            oversizedArguments,
            retained.get("args"),
            "unprojectable arguments survive an unrelated edit"
        );
        TestSupport.assertEquals(
            oversizedEnabledTools,
            retained.get("enabled_tools"),
            "unprojectable tool list survives an unrelated edit"
        );
        TestSupport.assertEquals(
            duplicateDisabledTools,
            retained.get("disabled_tools"),
            "duplicate projected values survive an unrelated edit"
        );
        TestSupport.assertEquals(
            "legacy-enabled-value",
            retained.get("enabled"),
            "normalized enabled fallback is not written back"
        );
        TestSupport.assertEquals(
            "legacy-required-value",
            retained.get("required"),
            "normalized required fallback is not written back"
        );
        TestSupport.assertEquals(
            Long.valueOf(0L),
            retained.get("startup_timeout_sec"),
            "normalized startup timeout is not written back"
        );
        TestSupport.assertEquals(
            "legacy-timeout-value",
            retained.get("tool_timeout_sec"),
            "normalized tool timeout is not written back"
        );
        TestSupport.assertEquals(
            "preserve-me",
            retained.get("custom_behavior"),
            "unknown advanced field remains preserved"
        );

        McpServerConfiguration afterEndpointEdit = find(
            controller.snapshot(),
            "lossless-edit"
        );
        List<String> replacementArguments = new ArrayList<String>();
        replacementArguments.add("replacement-one");
        replacementArguments.add("replacement-two");
        McpServerDraft explicitReplacement = new McpServerDraft(
            afterEndpointEdit.getName(),
            afterEndpointEdit.getTransport(),
            afterEndpointEdit.getCommand(),
            replacementArguments,
            afterEndpointEdit.getUrl(),
            afterEndpointEdit.isEnabled(),
            afterEndpointEdit.isRequired(),
            afterEndpointEdit.getStartupTimeoutSeconds(),
            afterEndpointEdit.getToolTimeoutSeconds(),
            "prompt",
            afterEndpointEdit.getEnabledTools(),
            afterEndpointEdit.getDisabledTools()
        );
        TestSupport.assertTrue(
            controller.save(explicitReplacement),
            "an explicit replacement for an unprojectable field remains supported"
        );
        waitReady(controller, McpConfigurationNotice.SAVED, "explicit list replacement");
        TestSupport.assertEquals(
            replacementArguments,
            findEdit(
                rpc.writes.get(1),
                "mcp_servers.lossless-edit.args"
            ).get("value"),
            "explicit argument replacement is written"
        );
        TestSupport.assertEquals(
            replacementArguments,
            rpc.servers.get("lossless-edit").get("args"),
            "explicit argument replacement reaches the effective configuration"
        );
        TestSupport.assertEquals(
            oversizedEnabledTools,
            rpc.servers.get("lossless-edit").get("enabled_tools"),
            "other unprojectable fields remain preserved during replacement"
        );
        controller.close();
    }

    private static void hardensPerToolApprovalOverridesWithoutDroppingOtherFields()
        throws Exception {
        final StatefulRpc rpc = new StatefulRpc();
        rpc.servers.put("override-enable", serverWithToolOverride(
            "https://enable.example/mcp"
        ));
        rpc.servers.put("override-edit", serverWithToolOverride(
            "https://edit.example/mcp"
        ));
        final McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "override fixture refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "override fixture ready");

        McpServerConfiguration enabled = find(controller.snapshot(), "override-enable");
        TestSupport.assertTrue(enabled.isEditable(), "override entry remains editable");
        TestSupport.assertTrue(
            enabled.hasToolApprovalOverrides(),
            "override entry exposes its approval hardening state"
        );
        TestSupport.assertTrue(
            enabled.hasPreservedAdvancedFields(),
            "unrelated advanced field remains separately classified"
        );
        TestSupport.assertTrue(
            controller.setEnabled("override-enable", true),
            "explicit enable hardens an override entry"
        );
        waitReady(controller, McpConfigurationNotice.ENABLED, "override enable hardened");
        Map<String, Object> enableWrite = rpc.writes.get(0);
        TestSupport.assertEquals(
            null,
            findEdit(enableWrite, "mcp_servers.override-enable.tools").get("value"),
            "enable clears the complete tool approval override table"
        );
        TestSupport.assertEquals(
            "prompt",
            findEdit(
                enableWrite,
                "mcp_servers.override-enable.default_tools_approval_mode"
            ).get("value"),
            "enable fixes the server approval default"
        );
        TestSupport.assertEquals(
            Boolean.TRUE,
            findEdit(enableWrite, "mcp_servers.override-enable.enabled").get("value"),
            "enable remains part of the same write"
        );
        McpServerConfiguration hardenedEnabled = find(
            controller.snapshot(),
            "override-enable"
        );
        TestSupport.assertTrue(hardenedEnabled.isEnabled(), "hardened server is enabled");
        TestSupport.assertFalse(
            hardenedEnabled.hasToolApprovalOverrides(),
            "effective enabled server no longer has a tool approval override"
        );
        TestSupport.assertEquals(
            "preserve-me",
            rpc.servers.get("override-enable").get("custom_behavior"),
            "enable preserves unrelated advanced fields"
        );

        TestSupport.assertTrue(controller.save(draft(
            "override-edit",
            "https://edited.example/mcp",
            false,
            false,
            10,
            60,
            "prompt"
        )), "editing hardens an override entry");
        waitReady(controller, McpConfigurationNotice.SAVED, "override edit hardened");
        Map<String, Object> editWrite = rpc.writes.get(1);
        TestSupport.assertEquals(
            null,
            findEdit(editWrite, "mcp_servers.override-edit.tools").get("value"),
            "edit clears the complete tool approval override table"
        );
        TestSupport.assertFalse(
            find(controller.snapshot(), "override-edit").hasToolApprovalOverrides(),
            "edited server no longer has a tool approval override"
        );
        TestSupport.assertEquals(
            "preserve-me",
            rpc.servers.get("override-edit").get("custom_behavior"),
            "edit preserves unrelated advanced fields"
        );
        controller.close();
    }

    private static void reportsReloadFailureAfterAnAcceptedWrite() throws Exception {
        final StatefulRpc rpc = new StatefulRpc();
        rpc.failReload = true;
        final McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "failure fixture refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "failure fixture ready");
        TestSupport.assertTrue(controller.save(draft(
            "reload-failure",
            "https://example.com/mcp",
            false,
            false,
            10,
            60,
            "prompt"
        )), "failure fixture write accepted");
        waitReady(controller, McpConfigurationNotice.RELOAD_REQUIRED, "reload failure surfaced");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(rpc.writes.size()), "write happened once");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(rpc.reloadCount), "reload not retried silently");
        controller.close();
    }

    private static void requiresPromptBeforeEnablingAnExistingServer() throws Exception {
        final StatefulRpc rpc = new StatefulRpc();
        rpc.servers.put("legacy-auto", JsonCodec.object(
            "url", "https://example.com/mcp",
            "enabled", Boolean.FALSE,
            "required", Boolean.FALSE,
            "startup_timeout_sec", Long.valueOf(10L),
            "tool_timeout_sec", Long.valueOf(60L),
            "default_tools_approval_mode", "auto"
        ));
        final McpConfigurationController controller = new McpConfigurationController(rpc);
        TestSupport.assertTrue(controller.refresh(), "legacy mode refresh accepted");
        waitReady(controller, McpConfigurationNotice.NONE, "legacy mode ready");
        TestSupport.assertFalse(
            controller.setEnabled("legacy-auto", true),
            "automatic approval mode cannot be enabled"
        );
        TestSupport.assertTrue(controller.save(draft(
            "legacy-auto",
            "https://example.com/mcp",
            false,
            false,
            10,
            60,
            "prompt"
        )), "legacy mode can be tightened through edit");
        waitReady(controller, McpConfigurationNotice.SAVED, "legacy mode tightened");
        TestSupport.assertEquals(
            "prompt",
            find(controller.snapshot(), "legacy-auto").getApprovalMode(),
            "edit forces prompt approval"
        );
        TestSupport.assertTrue(
            controller.setEnabled("legacy-auto", true),
            "prompt-only server can be enabled"
        );
        waitReady(controller, McpConfigurationNotice.ENABLED, "prompt-only enable");
        controller.close();
    }

    private static McpServerDraft draft(
        String name,
        String url,
        boolean enabled,
        boolean required,
        int startup,
        int tool,
        String approval
    ) {
        return new McpServerDraft(
            name,
            McpTransport.STREAMABLE_HTTP,
            "",
            Collections.<String>emptyList(),
            url,
            enabled,
            required,
            startup,
            tool,
            approval,
            Collections.<String>emptyList(),
            Collections.<String>emptyList()
        );
    }

    private static List<String> repeatedValues(int count, String value) {
        List<String> values = new ArrayList<String>();
        for (int index = 0; index < count; index++) {
            values.add(value);
        }
        return values;
    }

    private static String repeatedCharacter(char character, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            value.append(character);
        }
        return value.toString();
    }

    private static Map<String, Object> serverWithToolOverride(String url) {
        return JsonCodec.object(
            "url", url,
            "enabled", Boolean.FALSE,
            "required", Boolean.FALSE,
            "startup_timeout_sec", Long.valueOf(10L),
            "tool_timeout_sec", Long.valueOf(60L),
            "default_tools_approval_mode", "prompt",
            "tools", JsonCodec.object(
                "dangerous", JsonCodec.object("approval_mode", "approve")
            ),
            "custom_behavior", "preserve-me"
        );
    }

    private static Map<String, Object> writeParameters(Map<String, Object> edit) {
        return JsonCodec.object(
            "edits", JsonCodec.array(edit),
            "expectedVersion", VERSION_A,
            "reloadUserConfig", Boolean.FALSE
        );
    }

    private static Map<String, Object> edit(String path, Object value) {
        return JsonCodec.object(
            "keyPath", path,
            "value", value,
            "mergeStrategy", "replace"
        );
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<String, Object>(source);
    }

    private static List<Map<String, Object>> edits(Map<String, Object> parameters) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object value : JsonCodec.requireArray(parameters.get("edits"), "edits")) {
            result.add(JsonCodec.requireObject(value, "edit"));
        }
        return result;
    }

    private static Map<String, Object> firstEdit(Map<String, Object> parameters) {
        return edits(parameters).get(0);
    }

    private static Map<String, Object> findEdit(
        Map<String, Object> parameters,
        String keyPath
    ) {
        for (Map<String, Object> change : edits(parameters)) {
            if (keyPath.equals(change.get("keyPath"))) {
                return change;
            }
        }
        throw new AssertionError("Missing MCP configuration edit: " + keyPath);
    }

    private static void assertNoEdit(Map<String, Object> parameters, String keyPath) {
        for (Map<String, Object> change : edits(parameters)) {
            if (keyPath.equals(change.get("keyPath"))) {
                throw new AssertionError("Unexpected MCP configuration edit: " + keyPath);
            }
        }
    }

    private static void assertWriteHasNoPath(Map<String, Object> parameters) {
        TestSupport.assertFalse(parameters.containsKey("filePath"), "no caller file path");
        TestSupport.assertEquals(Boolean.FALSE, parameters.get("reloadUserConfig"), "bounded write reload flag");
        TestSupport.assertTrue(
            CodexMcpConfigurationRpc.isValidWriteRequest(parameters),
            "emitted write passes core validator"
        );
    }

    private static McpServerConfiguration find(McpConfigurationSnapshot snapshot, String name) {
        for (McpServerConfiguration server : snapshot.getServers()) {
            if (name.equals(server.getName())) {
                return server;
            }
        }
        throw new AssertionError("Missing MCP server: " + name);
    }

    private static String flatten(McpConfigurationSnapshot snapshot) {
        StringBuilder output = new StringBuilder();
        output.append(snapshot.getExpectedVersion());
        for (McpServerConfiguration server : snapshot.getServers()) {
            output.append(server.getName()).append(server.getCommand()).append(server.getUrl());
            output.append(server.getArguments()).append(server.getEnabledTools());
            output.append(server.getDisabledTools()).append(server.getApprovalMode());
        }
        return output.toString();
    }

    private static void waitReady(
        final McpConfigurationController controller,
        final McpConfigurationNotice notice,
        String message
    ) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            McpConfigurationSnapshot snapshot = controller.snapshot();
            if (!snapshot.isBusy() && snapshot.getPhase() == McpConfigurationPhase.READY
                && snapshot.getNotice() == notice) {
                return;
            }
            Thread.sleep(10L);
        }
        McpConfigurationSnapshot snapshot = controller.snapshot();
        throw new AssertionError(
            message + " phase=<" + snapshot.getPhase() + "> notice=<"
                + snapshot.getNotice() + ">"
        );
    }

    private static void addOrigin(
        Map<String, Object> origins,
        String key,
        String type,
        String version
    ) {
        Map<String, Object> name;
        if ("user".equals(type)) {
            name = JsonCodec.object(
                "type", "user",
                "file", "/private/codex-home/config.toml",
                "profile", null
            );
        } else if ("project".equals(type)) {
            name = JsonCodec.object(
                "type", "project",
                "dotCodexFolder", "/private/workspace/.codex"
            );
        } else {
            name = JsonCodec.object("type", type);
        }
        origins.put(key, JsonCodec.object("name", name, "version", version));
    }

    private static String version(char value) {
        StringBuilder output = new StringBuilder("sha256:");
        while (output.length() < 71) {
            output.append(value);
        }
        return output.toString();
    }

    private static final class StaticRpc implements CodexMcpConfigurationRpc {
        private final Map<String, Object> response;

        private StaticRpc(Map<String, Object> response) {
            this.response = response;
        }

        @Override
        public Map<String, Object> readMcpConfiguration(long timeoutMilliseconds) {
            TestSupport.assertEquals(Long.valueOf(20_000L), Long.valueOf(timeoutMilliseconds), "read timeout");
            return response;
        }

        @Override
        public Map<String, Object> writeMcpConfiguration(
            Map<String, Object> parameters,
            long timeoutMilliseconds
        ) {
            throw new AssertionError("static loader fixture must not write");
        }

        @Override
        public Map<String, Object> reloadMcpConfiguration(long timeoutMilliseconds) {
            throw new AssertionError("static loader fixture must not reload");
        }
    }

    private static final class StatefulRpc implements CodexMcpConfigurationRpc {
        private final Map<String, Map<String, Object>> servers =
            new LinkedHashMap<String, Map<String, Object>>();
        private final List<Map<String, Object>> writes =
            new ArrayList<Map<String, Object>>();
        private int versionIndex;
        private int reloadCount;
        private boolean failReload;

        @Override
        public synchronized Map<String, Object> readMcpConfiguration(
            long timeoutMilliseconds
        ) {
            TestSupport.assertEquals(Long.valueOf(20_000L), Long.valueOf(timeoutMilliseconds), "state read timeout");
            Map<String, Object> projectedServers = new LinkedHashMap<String, Object>();
            Map<String, Object> origins = new LinkedHashMap<String, Object>();
            String currentVersion = currentVersion();
            addOrigin(origins, "model", "user", currentVersion);
            for (Map.Entry<String, Map<String, Object>> server : servers.entrySet()) {
                projectedServers.put(
                    server.getKey(),
                    new LinkedHashMap<String, Object>(server.getValue())
                );
                for (String field : server.getValue().keySet()) {
                    addOrigin(
                        origins,
                        "mcp_servers." + server.getKey() + "." + field,
                        "user",
                        currentVersion
                    );
                }
            }
            return JsonCodec.object(
                "config", JsonCodec.object(
                    "model", "fixture",
                    "mcp_servers", projectedServers
                ),
                "origins", origins,
                "layers", null
            );
        }

        @Override
        public synchronized Map<String, Object> writeMcpConfiguration(
            Map<String, Object> parameters,
            long timeoutMilliseconds
        ) {
            TestSupport.assertEquals(Long.valueOf(20_000L), Long.valueOf(timeoutMilliseconds), "state write timeout");
            TestSupport.assertTrue(
                CodexMcpConfigurationRpc.isValidWriteRequest(parameters),
                "state fixture receives validated write"
            );
            TestSupport.assertEquals(currentVersion(), parameters.get("expectedVersion"), "optimistic version");
            Map<String, Object> captured = new LinkedHashMap<String, Object>(parameters);
            writes.add(captured);
            for (Map<String, Object> change : edits(parameters)) {
                apply(change);
            }
            versionIndex++;
            return JsonCodec.object(
                "status", "ok",
                "version", currentVersion(),
                "filePath", "/private/codex-home/config.toml",
                "overriddenMetadata", null
            );
        }

        @Override
        public synchronized Map<String, Object> reloadMcpConfiguration(
            long timeoutMilliseconds
        ) throws Exception {
            TestSupport.assertEquals(Long.valueOf(20_000L), Long.valueOf(timeoutMilliseconds), "reload timeout");
            reloadCount++;
            if (failReload) {
                throw new Exception("fixture reload failure /private/codex-home/config.toml");
            }
            return JsonCodec.object();
        }

        private void apply(Map<String, Object> change) {
            String path = (String) change.get("keyPath");
            String remainder = path.substring("mcp_servers.".length());
            int separator = remainder.indexOf('.');
            if (separator < 0) {
                if (change.get("value") == null) {
                    servers.remove(remainder);
                } else {
                    servers.put(
                        remainder,
                        new LinkedHashMap<String, Object>(JsonCodec.requireObject(
                            change.get("value"),
                            "whole server"
                        ))
                    );
                }
                return;
            }
            String name = remainder.substring(0, separator);
            String field = remainder.substring(separator + 1);
            Map<String, Object> server = servers.get(name);
            if (server == null) {
                throw new AssertionError("Field edit without server fixture");
            }
            if (change.get("value") == null) {
                server.remove(field);
            } else {
                server.put(field, change.get("value"));
            }
        }

        private String currentVersion() {
            return version((char) ('a' + versionIndex));
        }
    }
}
