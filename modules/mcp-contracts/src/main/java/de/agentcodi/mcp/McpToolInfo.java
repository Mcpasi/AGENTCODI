package de.agentcodi.mcp;

public final class McpToolInfo {
    private final String name;
    private final String title;
    private final String description;
    private final boolean enabled;
    private final boolean readOnly;
    private final String disabledReason;

    public McpToolInfo(
        String name,
        String title,
        String description,
        boolean enabled,
        boolean readOnly,
        String disabledReason
    ) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Tool name is required");
        }
        this.name = name;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.enabled = enabled;
        this.readOnly = readOnly;
        this.disabledReason = disabledReason == null ? "" : disabledReason;
    }

    public String getName() {
        return name;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String getDisabledReason() {
        return disabledReason;
    }
}
