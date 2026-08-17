package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class McpCatalogSnapshot {
    private final long revision;
    private final McpCatalogPhase phase;
    private final List<McpFeatureInfo> features;
    private final List<McpSkillInfo> skills;
    private final List<McpServerInfo> servers;
    private final List<McpAppInfo> apps;
    private final List<McpMarketplaceInfo> marketplaces;
    private final List<McpCatalogWarning> warnings;

    public McpCatalogSnapshot(
        long revision,
        McpCatalogPhase phase,
        List<McpFeatureInfo> features,
        List<McpSkillInfo> skills,
        List<McpServerInfo> servers,
        List<McpAppInfo> apps,
        List<McpMarketplaceInfo> marketplaces,
        List<McpCatalogWarning> warnings
    ) {
        if (revision < 0L || phase == null) {
            throw new IllegalArgumentException("Catalog revision and phase are required");
        }
        this.revision = revision;
        this.phase = phase;
        this.features = immutable(features);
        this.skills = immutable(skills);
        this.servers = immutable(servers);
        this.apps = immutable(apps);
        this.marketplaces = immutable(marketplaces);
        this.warnings = immutable(warnings);
    }

    public static McpCatalogSnapshot stopped() {
        return empty(0L, McpCatalogPhase.STOPPED);
    }

    public static McpCatalogSnapshot loading(long revision, McpCatalogSnapshot previous) {
        McpCatalogSnapshot source = previous == null ? stopped() : previous;
        return new McpCatalogSnapshot(
            revision,
            McpCatalogPhase.LOADING,
            source.features,
            source.skills,
            source.servers,
            source.apps,
            source.marketplaces,
            source.warnings
        );
    }

    public static McpCatalogSnapshot failed(long revision) {
        return empty(revision, McpCatalogPhase.FAILED);
    }

    public static McpCatalogSnapshot empty(long revision, McpCatalogPhase phase) {
        return new McpCatalogSnapshot(
            revision,
            phase,
            Collections.<McpFeatureInfo>emptyList(),
            Collections.<McpSkillInfo>emptyList(),
            Collections.<McpServerInfo>emptyList(),
            Collections.<McpAppInfo>emptyList(),
            Collections.<McpMarketplaceInfo>emptyList(),
            Collections.<McpCatalogWarning>emptyList()
        );
    }

    public long getRevision() {
        return revision;
    }

    public McpCatalogPhase getPhase() {
        return phase;
    }

    public List<McpFeatureInfo> getFeatures() {
        return features;
    }

    public List<McpSkillInfo> getSkills() {
        return skills;
    }

    public List<McpServerInfo> getServers() {
        return servers;
    }

    public List<McpAppInfo> getApps() {
        return apps;
    }

    public List<McpMarketplaceInfo> getMarketplaces() {
        return marketplaces;
    }

    public List<McpCatalogWarning> getWarnings() {
        return warnings;
    }

    public int getToolCount() {
        int count = 0;
        for (McpServerInfo server : servers) {
            count += server.getTools().size();
        }
        for (McpAppInfo app : apps) {
            count += app.getTools().size();
        }
        return count;
    }

    public int getPluginCount() {
        int count = 0;
        for (McpMarketplaceInfo marketplace : marketplaces) {
            count += marketplace.getPlugins().size();
        }
        return count;
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
