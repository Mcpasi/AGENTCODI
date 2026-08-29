package de.agentcodi.connectors;

public enum ConnectorProvider {
    GMAIL("Gmail"),
    GITHUB("GitHub");

    private final String displayName;

    ConnectorProvider(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
