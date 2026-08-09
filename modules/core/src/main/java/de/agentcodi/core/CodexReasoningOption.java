package de.agentcodi.core;

public final class CodexReasoningOption {
    private final String effort;
    private final String description;

    public CodexReasoningOption(String effort, String description) {
        if (effort == null || effort.trim().isEmpty()) {
            throw new IllegalArgumentException("Reasoning effort must not be blank");
        }
        this.effort = effort;
        this.description = description == null ? "" : description;
    }

    public String getEffort() {
        return effort;
    }

    public String getDescription() {
        return description;
    }
}
