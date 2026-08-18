package de.agentcodi.mcp.client;

import de.agentcodi.core.CodexMcpConfigurationRpc;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.mcp.McpConfigurationNotice;
import de.agentcodi.mcp.McpServerConfiguration;
import de.agentcodi.mcp.McpServerDraft;
import de.agentcodi.mcp.McpTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class McpConfigurationMutations {
    private McpConfigurationMutations() {
    }

    static Mutation add(McpServerDraft draft, String expectedVersion) {
        if (draft.isEnabled()) {
            throw new IllegalArgumentException("New MCP servers must be saved disabled first");
        }
        Map<String, Object> server = JsonCodec.object(
            "enabled", Boolean.FALSE,
            "required", Boolean.FALSE,
            "startup_timeout_sec", Long.valueOf(draft.getStartupTimeoutSeconds()),
            "tool_timeout_sec", Long.valueOf(draft.getToolTimeoutSeconds()),
            "default_tools_approval_mode", "prompt"
        );
        if (draft.getTransport() == McpTransport.STDIO) {
            server.put("command", draft.getCommand());
            if (!draft.getArguments().isEmpty()) {
                server.put("args", strings(draft.getArguments()));
            }
        } else {
            server.put("url", draft.getUrl());
        }
        putOptionalPolicy(server, draft);
        return mutation(
            McpConfigurationNotice.SAVED,
            expectedVersion,
            edit("mcp_servers." + draft.getName(), server)
        );
    }

    static Mutation update(
        McpServerConfiguration existing,
        McpServerDraft draft,
        String expectedVersion
    ) {
        requireExpectedVersion(expectedVersion);
        if (existing == null || !existing.getName().equals(draft.getName())
            || existing.getTransport() != draft.getTransport()) {
            throw new IllegalArgumentException("Matching MCP configuration is required");
        }
        List<Object> edits = new ArrayList<Object>();
        String prefix = "mcp_servers." + draft.getName() + ".";
        // Emit only deltas from the bounded read projection. An oversized or malformed known
        // value is represented by a safe fallback, which must not be written back merely because
        // the user changed another field.
        if (draft.getTransport() == McpTransport.STDIO) {
            if (!existing.getCommand().equals(draft.getCommand())) {
                edits.add(edit(prefix + "command", draft.getCommand()));
            }
            if (!existing.getArguments().equals(draft.getArguments())) {
                edits.add(edit(
                    prefix + "args",
                    draft.getArguments().isEmpty() ? null : strings(draft.getArguments())
                ));
            }
        } else {
            if (!existing.getUrl().equals(draft.getUrl())) {
                edits.add(edit(prefix + "url", draft.getUrl()));
            }
        }
        if (existing.isEnabled() != draft.isEnabled()) {
            edits.add(edit(prefix + "enabled", Boolean.valueOf(draft.isEnabled())));
        }
        if (existing.isRequired() != draft.isRequired()) {
            edits.add(edit(prefix + "required", Boolean.valueOf(draft.isRequired())));
        }
        if (existing.getStartupTimeoutSeconds() != draft.getStartupTimeoutSeconds()) {
            edits.add(edit(
                prefix + "startup_timeout_sec",
                Long.valueOf(draft.getStartupTimeoutSeconds())
            ));
        }
        if (existing.getToolTimeoutSeconds() != draft.getToolTimeoutSeconds()) {
            edits.add(edit(
                prefix + "tool_timeout_sec",
                Long.valueOf(draft.getToolTimeoutSeconds())
            ));
        }
        if (!existing.getEnabledTools().equals(draft.getEnabledTools())) {
            edits.add(edit(
                prefix + "enabled_tools",
                draft.getEnabledTools().isEmpty() ? null : strings(draft.getEnabledTools())
            ));
        }
        if (!existing.getDisabledTools().equals(draft.getDisabledTools())) {
            edits.add(edit(
                prefix + "disabled_tools",
                draft.getDisabledTools().isEmpty() ? null : strings(draft.getDisabledTools())
            ));
        }
        // Editing is also the explicit approval-hardening action promised by the UI.
        edits.add(edit(prefix + "tools", null));
        edits.add(edit(
            prefix + "default_tools_approval_mode",
            "prompt"
        ));
        return mutation(McpConfigurationNotice.SAVED, expectedVersion, edits);
    }

    static Mutation setEnabled(String name, boolean enabled, String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (!enabled) {
            return mutation(
                McpConfigurationNotice.DISABLED,
                expectedVersion,
                edit("mcp_servers." + name + ".enabled", Boolean.FALSE)
            );
        }
        List<Object> edits = new ArrayList<Object>();
        edits.add(edit("mcp_servers." + name + ".tools", null));
        edits.add(edit(
            "mcp_servers." + name + ".default_tools_approval_mode",
            "prompt"
        ));
        edits.add(edit("mcp_servers." + name + ".enabled", Boolean.TRUE));
        return mutation(McpConfigurationNotice.ENABLED, expectedVersion, edits);
    }

    static Mutation delete(String name, String expectedVersion) {
        requireExpectedVersion(expectedVersion);
        return mutation(
            McpConfigurationNotice.DELETED,
            expectedVersion,
            edit("mcp_servers." + name, null)
        );
    }

    private static void putOptionalPolicy(Map<String, Object> server, McpServerDraft draft) {
        if (!draft.getEnabledTools().isEmpty()) {
            server.put("enabled_tools", strings(draft.getEnabledTools()));
        }
        if (!draft.getDisabledTools().isEmpty()) {
            server.put("disabled_tools", strings(draft.getDisabledTools()));
        }
    }

    private static Mutation mutation(
        McpConfigurationNotice notice,
        String expectedVersion,
        Map<String, Object> edit
    ) {
        List<Object> edits = new ArrayList<Object>();
        edits.add(edit);
        return mutation(notice, expectedVersion, edits);
    }

    private static Mutation mutation(
        McpConfigurationNotice notice,
        String expectedVersion,
        List<Object> edits
    ) {
        Map<String, Object> parameters = JsonCodec.object(
            "edits", edits,
            "reloadUserConfig", Boolean.FALSE
        );
        if (expectedVersion != null && !expectedVersion.isEmpty()) {
            parameters.put("expectedVersion", expectedVersion);
        }
        if (!CodexMcpConfigurationRpc.isValidWriteRequest(parameters)) {
            throw new IllegalArgumentException("MCP configuration mutation failed validation");
        }
        return new Mutation(notice, parameters);
    }

    private static Map<String, Object> edit(String keyPath, Object value) {
        return JsonCodec.object(
            "keyPath", keyPath,
            "value", value,
            "mergeStrategy", "replace"
        );
    }

    private static List<Object> strings(List<String> values) {
        List<Object> result = new ArrayList<Object>();
        result.addAll(values);
        return result;
    }

    private static void requireExpectedVersion(String version) {
        if (version == null || version.isEmpty()) {
            throw new IllegalStateException("A current user configuration version is required");
        }
    }

    static final class Mutation {
        final McpConfigurationNotice notice;
        final Map<String, Object> parameters;

        private Mutation(
            McpConfigurationNotice notice,
            Map<String, Object> parameters
        ) {
            this.notice = notice;
            this.parameters = parameters;
        }
    }
}
