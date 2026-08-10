package de.agentcodi.core;

public final class CodexNetworkPolicyAmendment {
    private final String action;
    private final String host;

    public CodexNetworkPolicyAmendment(String action, String host) {
        this.action = action == null ? "" : action;
        this.host = host == null ? "" : host;
    }

    public String getAction() {
        return action;
    }

    public String getHost() {
        return host;
    }
}
