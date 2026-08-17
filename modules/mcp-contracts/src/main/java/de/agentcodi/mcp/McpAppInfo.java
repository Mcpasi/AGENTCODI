package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class McpAppInfo {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final boolean callable;
    private final List<McpToolInfo> tools;

    public McpAppInfo(
        String id,
        String name,
        boolean enabled,
        boolean callable,
        List<McpToolInfo> tools
    ) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("App id is required");
        }
        this.id = id;
        this.name = name == null || name.isEmpty() ? id : name;
        this.enabled = enabled;
        this.callable = callable;
        this.tools = tools == null
            ? Collections.<McpToolInfo>emptyList()
            : Collections.unmodifiableList(new ArrayList<McpToolInfo>(tools));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCallable() {
        return callable;
    }

    public List<McpToolInfo> getTools() {
        return tools;
    }
}
