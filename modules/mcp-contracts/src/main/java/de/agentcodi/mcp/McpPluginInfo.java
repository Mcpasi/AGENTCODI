package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class McpPluginInfo {
    private final String id;
    private final String name;
    private final String displayName;
    private final String description;
    private final String version;
    private final String sourceType;
    private final String availability;
    private final String installPolicy;
    private final boolean installed;
    private final boolean enabled;
    private final List<String> capabilities;

    public McpPluginInfo(
        String id,
        String name,
        String displayName,
        String description,
        String version,
        String sourceType,
        String availability,
        String installPolicy,
        boolean installed,
        boolean enabled,
        List<String> capabilities
    ) {
        if (id == null || id.isEmpty() || name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Plugin id and name are required");
        }
        this.id = id;
        this.name = name;
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
        this.version = version == null ? "" : version;
        this.sourceType = sourceType == null ? "" : sourceType;
        this.availability = availability == null ? "" : availability;
        this.installPolicy = installPolicy == null ? "" : installPolicy;
        this.installed = installed;
        this.enabled = enabled;
        this.capabilities = capabilities == null
            ? Collections.<String>emptyList()
            : Collections.unmodifiableList(new ArrayList<String>(capabilities));
    }

    public String getId() {
        return id;
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

    public String getVersion() {
        return version;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getAvailability() {
        return availability;
    }

    public String getInstallPolicy() {
        return installPolicy;
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }
}
