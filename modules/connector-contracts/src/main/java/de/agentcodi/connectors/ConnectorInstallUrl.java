package de.agentcodi.connectors;

import java.net.URI;
import java.util.Locale;

/** Validates transient hosted connector installation and reauthentication URLs. */
public final class ConnectorInstallUrl {
    public static final int MAXIMUM_CHARACTERS = 8192;

    private ConnectorInstallUrl() {
    }

    public static boolean isTrusted(String value) {
        if (value == null || value.isEmpty() || value.length() > MAXIMUM_CHARACTERS) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return false;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return lowerHost.equals("openai.com") || lowerHost.endsWith(".openai.com")
                || lowerHost.equals("chatgpt.com") || lowerHost.endsWith(".chatgpt.com");
        } catch (Exception error) {
            return false;
        }
    }
}
