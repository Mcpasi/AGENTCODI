package de.agentcodi.mcp;

public final class McpSkillInfo {
    private final String name;
    private final String displayName;
    private final String description;
    private final String scope;
    private final boolean enabled;
    private final int toolDependencyCount;

    public McpSkillInfo(
        String name,
        String displayName,
        String description,
        String scope,
        boolean enabled,
        int toolDependencyCount
    ) {
        if (name == null || name.isEmpty() || scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("Skill name and scope are required");
        }
        if (toolDependencyCount < 0) {
            throw new IllegalArgumentException("Skill dependency count must not be negative");
        }
        this.name = name;
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
        this.scope = scope;
        this.enabled = enabled;
        this.toolDependencyCount = toolDependencyCount;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getScope() {
        return scope;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getToolDependencyCount() {
        return toolDependencyCount;
    }
}
