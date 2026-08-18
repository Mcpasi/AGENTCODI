package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded, path-free and secret-free projection of one effective MCP server entry. */
public final class McpServerConfiguration {
    private final String name;
    private final McpTransport transport;
    private final String command;
    private final List<String> arguments;
    private final String url;
    private final boolean enabled;
    private final boolean required;
    private final int startupTimeoutSeconds;
    private final int toolTimeoutSeconds;
    private final String approvalMode;
    private final List<String> enabledTools;
    private final List<String> disabledTools;
    private final McpServerOrigin origin;
    private final boolean editable;
    private final boolean toolApprovalOverrides;
    private final boolean preservedAdvancedFields;
    private final boolean sensitiveValuesHidden;

    public McpServerConfiguration(
        String name,
        McpTransport transport,
        String command,
        List<String> arguments,
        String url,
        boolean enabled,
        boolean required,
        int startupTimeoutSeconds,
        int toolTimeoutSeconds,
        String approvalMode,
        List<String> enabledTools,
        List<String> disabledTools,
        McpServerOrigin origin,
        boolean editable,
        boolean toolApprovalOverrides,
        boolean preservedAdvancedFields,
        boolean sensitiveValuesHidden
    ) {
        if (name == null || name.isEmpty() || transport == null || origin == null) {
            throw new IllegalArgumentException("MCP configuration identity is required");
        }
        this.name = name;
        this.transport = transport;
        this.command = command == null ? "" : command;
        this.arguments = immutable(arguments);
        this.url = url == null ? "" : url;
        this.enabled = enabled;
        this.required = required;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.approvalMode = approvalMode == null ? "" : approvalMode;
        this.enabledTools = immutable(enabledTools);
        this.disabledTools = immutable(disabledTools);
        this.origin = origin;
        this.editable = editable;
        this.toolApprovalOverrides = toolApprovalOverrides;
        this.preservedAdvancedFields = preservedAdvancedFields;
        this.sensitiveValuesHidden = sensitiveValuesHidden;
    }

    public String getName() {
        return name;
    }

    public McpTransport getTransport() {
        return transport;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArguments() {
        return arguments;
    }

    public String getUrl() {
        return url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRequired() {
        return required;
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    public int getToolTimeoutSeconds() {
        return toolTimeoutSeconds;
    }

    public String getApprovalMode() {
        return approvalMode;
    }

    public List<String> getEnabledTools() {
        return enabledTools;
    }

    public List<String> getDisabledTools() {
        return disabledTools;
    }

    public McpServerOrigin getOrigin() {
        return origin;
    }

    public boolean isUserOwned() {
        return origin == McpServerOrigin.USER;
    }

    public boolean isEditable() {
        return editable;
    }

    public boolean hasToolApprovalOverrides() {
        return toolApprovalOverrides;
    }

    public boolean hasPreservedAdvancedFields() {
        return preservedAdvancedFields;
    }

    public boolean hasSensitiveValuesHidden() {
        return sensitiveValuesHidden;
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(
            values == null ? new ArrayList<String>() : new ArrayList<String>(values)
        );
    }
}
