package de.agentcodi.mcp;

public final class McpFeatureInfo {
    private final String name;
    private final String displayName;
    private final String description;
    private final String stage;
    private final boolean enabled;
    private final boolean defaultEnabled;

    public McpFeatureInfo(
        String name,
        String displayName,
        String description,
        String stage,
        boolean enabled,
        boolean defaultEnabled
    ) {
        this.name = required(name, "feature name");
        this.displayName = optional(displayName);
        this.description = optional(description);
        this.stage = required(stage, "feature stage");
        this.enabled = enabled;
        this.defaultEnabled = defaultEnabled;
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

    public String getStage() {
        return stage;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDefaultEnabled() {
        return defaultEnabled;
    }

    private static String required(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String optional(String value) {
        return value == null ? "" : value;
    }
}
