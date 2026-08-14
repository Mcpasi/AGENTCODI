package de.agentcodi.core;

public final class CodexUserInputOption {
    private final String label;
    private final String description;

    public CodexUserInputOption(String label, String description) {
        this.label = CrashReportFormatter.redactSecrets(label);
        this.description = CrashReportFormatter.redactSecrets(description);
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }
}
