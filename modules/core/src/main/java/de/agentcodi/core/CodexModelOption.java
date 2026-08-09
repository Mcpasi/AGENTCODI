package de.agentcodi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CodexModelOption {
    private final String id;
    private final String model;
    private final String displayName;
    private final String description;
    private final String defaultReasoningEffort;
    private final List<CodexReasoningOption> reasoningOptions;
    private final boolean defaultModel;

    public CodexModelOption(
        String id,
        String model,
        String displayName,
        String description,
        String defaultReasoningEffort,
        List<CodexReasoningOption> reasoningOptions,
        boolean defaultModel
    ) {
        if (id == null || id.trim().isEmpty() || model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("Model id and request model must not be blank");
        }
        this.id = id;
        this.model = model;
        this.displayName = displayName == null || displayName.trim().isEmpty()
            ? model
            : displayName;
        this.description = description == null ? "" : description;
        this.defaultReasoningEffort = defaultReasoningEffort == null
            ? ""
            : defaultReasoningEffort;
        this.reasoningOptions = Collections.unmodifiableList(
            new ArrayList<CodexReasoningOption>(
                reasoningOptions == null
                    ? Collections.<CodexReasoningOption>emptyList()
                    : reasoningOptions
            )
        );
        this.defaultModel = defaultModel;
        if (!this.reasoningOptions.isEmpty()
            && !supportsReasoningEffort(this.defaultReasoningEffort)) {
            throw new IllegalArgumentException("Default reasoning effort is not supported");
        }
    }

    public String getId() {
        return id;
    }

    public String getModel() {
        return model;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public String getDefaultReasoningEffort() {
        return defaultReasoningEffort;
    }

    public List<CodexReasoningOption> getReasoningOptions() {
        return reasoningOptions;
    }

    public boolean isDefaultModel() {
        return defaultModel;
    }

    public boolean supportsReasoningEffort(String value) {
        if (value == null || value.isEmpty()) {
            return reasoningOptions.isEmpty();
        }
        for (CodexReasoningOption option : reasoningOptions) {
            if (option.getEffort().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
