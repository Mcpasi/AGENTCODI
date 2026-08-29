package de.agentcodi.connectors;

public final class ConnectorInfo {
    public static final int MAXIMUM_DESCRIPTION_CHARACTERS = 600;
    public static final int MAXIMUM_TOOL_COUNT = 256;

    private final ConnectorProvider provider;
    private final String id;
    private final String name;
    private final String description;
    private final String installUrl;
    private final boolean offered;
    private final boolean accessible;
    private final boolean enabled;
    private final boolean installed;
    private final boolean callable;
    private final int toolCount;

    public ConnectorInfo(
        ConnectorProvider provider,
        String id,
        String name,
        String description,
        String installUrl,
        boolean offered,
        boolean accessible,
        boolean enabled,
        boolean installed,
        boolean callable,
        int toolCount
    ) {
        if (provider == null || toolCount < 0 || toolCount > MAXIMUM_TOOL_COUNT) {
            throw new IllegalArgumentException("Connector metadata is invalid");
        }
        if (offered && (!ConnectorSelection.isSafeId(id) || !isSafeText(name, 160))) {
            throw new IllegalArgumentException("Offered connector identity is invalid");
        }
        if (!isSafeText(description, MAXIMUM_DESCRIPTION_CHARACTERS)) {
            throw new IllegalArgumentException("Connector description is invalid");
        }
        if (installUrl != null && !installUrl.isEmpty()
            && !ConnectorInstallUrl.isTrusted(installUrl)) {
            throw new IllegalArgumentException("Connector installation URL is untrusted");
        }
        if (callable && (!offered || !accessible || !enabled || !installed)) {
            throw new IllegalArgumentException("Callable connector state is inconsistent");
        }
        this.provider = provider;
        this.id = offered ? id : "";
        this.name = offered ? name : provider.getDisplayName();
        this.description = description == null ? "" : description;
        this.installUrl = installUrl == null ? "" : installUrl;
        this.offered = offered;
        this.accessible = offered && accessible;
        this.enabled = offered && enabled;
        this.installed = offered && installed;
        this.callable = offered && callable;
        this.toolCount = offered ? toolCount : 0;
    }

    public static ConnectorInfo unavailable(ConnectorProvider provider) {
        return new ConnectorInfo(
            provider,
            "",
            provider.getDisplayName(),
            "",
            "",
            false,
            false,
            false,
            false,
            false,
            0
        );
    }

    public ConnectorProvider getProvider() {
        return provider;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getInstallUrl() {
        return installUrl;
    }

    public boolean isOffered() {
        return offered;
    }

    public boolean isAccessible() {
        return accessible;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isCallable() {
        return callable;
    }

    public int getToolCount() {
        return toolCount;
    }

    public boolean hasTrustedInstallUrl() {
        return !installUrl.isEmpty();
    }

    public ConnectorSelection selection() {
        if (!callable) {
            throw new IllegalStateException("Connector is not callable");
        }
        return new ConnectorSelection(provider, id, name);
    }

    private static boolean isSafeText(String value, int maximumCharacters) {
        if (value == null) {
            return true;
        }
        if (value.length() > maximumCharacters) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t') {
                return false;
            }
        }
        return true;
    }
}
