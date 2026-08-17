package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class McpServerInfo {
    private final String name;
    private final String title;
    private final String version;
    private final String description;
    private final String authStatus;
    private final List<McpToolInfo> tools;
    private final int resourceCount;
    private final int resourceTemplateCount;

    public McpServerInfo(
        String name,
        String title,
        String version,
        String description,
        String authStatus,
        List<McpToolInfo> tools,
        int resourceCount,
        int resourceTemplateCount
    ) {
        if (name == null || name.isEmpty() || authStatus == null || authStatus.isEmpty()) {
            throw new IllegalArgumentException("MCP server name and auth status are required");
        }
        if (resourceCount < 0 || resourceTemplateCount < 0) {
            throw new IllegalArgumentException("MCP resource counts must not be negative");
        }
        this.name = name;
        this.title = title == null ? "" : title;
        this.version = version == null ? "" : version;
        this.description = description == null ? "" : description;
        this.authStatus = authStatus;
        this.tools = immutable(tools);
        this.resourceCount = resourceCount;
        this.resourceTemplateCount = resourceTemplateCount;
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthStatus() {
        return authStatus;
    }

    public List<McpToolInfo> getTools() {
        return tools;
    }

    public int getResourceCount() {
        return resourceCount;
    }

    public int getResourceTemplateCount() {
        return resourceTemplateCount;
    }

    private static List<McpToolInfo> immutable(List<McpToolInfo> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<McpToolInfo>(values));
    }
}
