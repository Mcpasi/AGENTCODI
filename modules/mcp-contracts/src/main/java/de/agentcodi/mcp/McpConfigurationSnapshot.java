package de.agentcodi.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class McpConfigurationSnapshot {
    private final long revision;
    private final McpConfigurationPhase phase;
    private final McpConfigurationNotice notice;
    private final List<McpServerConfiguration> servers;
    private final String expectedVersion;

    public McpConfigurationSnapshot(
        long revision,
        McpConfigurationPhase phase,
        McpConfigurationNotice notice,
        List<McpServerConfiguration> servers,
        String expectedVersion
    ) {
        if (revision < 0L || phase == null || notice == null) {
            throw new IllegalArgumentException("MCP configuration snapshot state is invalid");
        }
        this.revision = revision;
        this.phase = phase;
        this.notice = notice;
        this.servers = Collections.unmodifiableList(
            servers == null
                ? new ArrayList<McpServerConfiguration>()
                : new ArrayList<McpServerConfiguration>(servers)
        );
        this.expectedVersion = expectedVersion == null ? "" : expectedVersion;
    }

    public static McpConfigurationSnapshot stopped() {
        return new McpConfigurationSnapshot(
            0L,
            McpConfigurationPhase.STOPPED,
            McpConfigurationNotice.NONE,
            Collections.<McpServerConfiguration>emptyList(),
            ""
        );
    }

    public static McpConfigurationSnapshot carrying(
        long revision,
        McpConfigurationPhase phase,
        McpConfigurationSnapshot previous
    ) {
        return new McpConfigurationSnapshot(
            revision,
            phase,
            McpConfigurationNotice.NONE,
            previous == null ? null : previous.servers,
            previous == null ? "" : previous.expectedVersion
        );
    }

    public static McpConfigurationSnapshot failed(
        long revision,
        McpConfigurationSnapshot previous,
        McpConfigurationNotice notice
    ) {
        return new McpConfigurationSnapshot(
            revision,
            McpConfigurationPhase.FAILED,
            notice,
            previous == null ? null : previous.servers,
            previous == null ? "" : previous.expectedVersion
        );
    }

    public long getRevision() {
        return revision;
    }

    public McpConfigurationPhase getPhase() {
        return phase;
    }

    public McpConfigurationNotice getNotice() {
        return notice;
    }

    public List<McpServerConfiguration> getServers() {
        return servers;
    }

    public String getExpectedVersion() {
        return expectedVersion;
    }

    public boolean isBusy() {
        return phase == McpConfigurationPhase.LOADING
            || phase == McpConfigurationPhase.SAVING;
    }
}
