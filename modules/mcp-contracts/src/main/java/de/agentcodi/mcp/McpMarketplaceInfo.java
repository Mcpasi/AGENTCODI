package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class McpMarketplaceInfo {
    private final String name;
    private final String displayName;
    private final List<McpPluginInfo> plugins;

    public McpMarketplaceInfo(
        String name,
        String displayName,
        List<McpPluginInfo> plugins
    ) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Marketplace name is required");
        }
        this.name = name;
        this.displayName = displayName == null ? "" : displayName;
        this.plugins = plugins == null
            ? Collections.<McpPluginInfo>emptyList()
            : Collections.unmodifiableList(new ArrayList<McpPluginInfo>(plugins));
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<McpPluginInfo> getPlugins() {
        return plugins;
    }
}
