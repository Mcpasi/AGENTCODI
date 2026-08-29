package de.agentcodi.connectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ConnectorCatalogSnapshot {
    public static final int MAXIMUM_THREAD_ID_CHARACTERS = 256;

    private final long revision;
    private final ConnectorPhase phase;
    private final String threadId;
    private final List<ConnectorInfo> connectors;
    private final boolean truncated;

    public ConnectorCatalogSnapshot(
        long revision,
        ConnectorPhase phase,
        String threadId,
        List<ConnectorInfo> connectors,
        boolean truncated
    ) {
        if (revision < 0L || phase == null || !isSafeThreadId(threadId)) {
            throw new IllegalArgumentException("Connector catalog state is invalid");
        }
        List<ConnectorInfo> copy = connectors == null
            ? new ArrayList<ConnectorInfo>()
            : new ArrayList<ConnectorInfo>(connectors);
        if (copy.size() > ConnectorProvider.values().length) {
            throw new IllegalArgumentException("Connector catalog exceeds the provider limit");
        }
        Set<ConnectorProvider> providers = EnumSet.noneOf(ConnectorProvider.class);
        for (ConnectorInfo connector : copy) {
            if (connector == null || !providers.add(connector.getProvider())) {
                throw new IllegalArgumentException("Connector catalog providers must be unique");
            }
        }
        this.revision = revision;
        this.phase = phase;
        this.threadId = threadId;
        this.connectors = Collections.unmodifiableList(copy);
        this.truncated = truncated;
    }

    public static ConnectorCatalogSnapshot stopped() {
        return empty(0L, ConnectorPhase.STOPPED, "");
    }

    public static ConnectorCatalogSnapshot loading(
        long revision,
        String threadId,
        ConnectorCatalogSnapshot previous
    ) {
        return new ConnectorCatalogSnapshot(
            revision,
            ConnectorPhase.LOADING,
            threadId,
            previous == null || !threadId.equals(previous.threadId)
                ? placeholders()
                : previous.connectors,
            previous != null && threadId.equals(previous.threadId) && previous.truncated
        );
    }

    public static ConnectorCatalogSnapshot empty(
        long revision,
        ConnectorPhase phase,
        String threadId
    ) {
        return new ConnectorCatalogSnapshot(
            revision,
            phase,
            threadId,
            placeholders(),
            false
        );
    }

    private static List<ConnectorInfo> placeholders() {
        List<ConnectorInfo> values = new ArrayList<ConnectorInfo>();
        values.add(ConnectorInfo.unavailable(ConnectorProvider.GMAIL));
        values.add(ConnectorInfo.unavailable(ConnectorProvider.GITHUB));
        return values;
    }

    public long getRevision() {
        return revision;
    }

    public ConnectorPhase getPhase() {
        return phase;
    }

    public String getThreadId() {
        return threadId;
    }

    public List<ConnectorInfo> getConnectors() {
        return connectors;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public ConnectorInfo find(ConnectorProvider provider) {
        for (ConnectorInfo connector : connectors) {
            if (connector.getProvider() == provider) {
                return connector;
            }
        }
        return ConnectorInfo.unavailable(provider);
    }

    private static boolean isSafeThreadId(String value) {
        if (value == null || value.length() > MAXIMUM_THREAD_ID_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
