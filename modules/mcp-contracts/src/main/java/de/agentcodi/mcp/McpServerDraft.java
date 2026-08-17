package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** User-entered, secret-free subset of one MCP server configuration. */
public final class McpServerDraft {
    public static final int DEFAULT_STARTUP_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 60;

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

    public McpServerDraft(
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
        List<String> disabledTools
    ) {
        if (!isSafeName(name)) {
            throw new IllegalArgumentException("MCP server name is outside the supported format");
        }
        if (transport != McpTransport.STDIO && transport != McpTransport.STREAMABLE_HTTP) {
            throw new IllegalArgumentException("A supported MCP transport is required");
        }
        String normalizedCommand = normalize(command);
        String normalizedUrl = normalize(url);
        if ((transport == McpTransport.STDIO && normalizedCommand.isEmpty())
            || (transport == McpTransport.STREAMABLE_HTTP && normalizedUrl.isEmpty())) {
            throw new IllegalArgumentException("The selected MCP endpoint is required");
        }
        if (normalizedCommand.length() > 1024 || normalizedUrl.length() > 2048) {
            throw new IllegalArgumentException("The MCP endpoint exceeds the limit");
        }
        if (startupTimeoutSeconds < 1 || startupTimeoutSeconds > 3600
            || toolTimeoutSeconds < 1 || toolTimeoutSeconds > 3600) {
            throw new IllegalArgumentException("MCP timeouts are outside the supported range");
        }
        String normalizedApprovalMode = normalize(approvalMode);
        if (!"prompt".equals(normalizedApprovalMode)) {
            throw new IllegalArgumentException("MCP tool approval must remain prompt");
        }
        this.name = name;
        this.transport = transport;
        this.command = normalizedCommand;
        this.arguments = immutableValues(arguments, 64, 2048, "MCP arguments");
        this.url = normalizedUrl;
        this.enabled = enabled;
        this.required = required;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
        this.approvalMode = normalizedApprovalMode;
        this.enabledTools = immutableValues(enabledTools, 128, 160, "enabled MCP tools");
        this.disabledTools = immutableValues(disabledTools, 128, 160, "disabled MCP tools");
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

    public static boolean isSafeName(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static List<String> immutableValues(
        List<String> values,
        int maximumEntries,
        int maximumCharacters,
        String label
    ) {
        List<String> copy = new ArrayList<String>();
        if (values != null) {
            if (values.size() > maximumEntries) {
                throw new IllegalArgumentException(label + " exceed the entry limit");
            }
            for (String value : values) {
                String normalized = normalize(value);
                if (normalized.isEmpty() || normalized.length() > maximumCharacters
                    || hasControl(normalized)) {
                    throw new IllegalArgumentException(label + " contain an invalid value");
                }
                copy.add(normalized);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
