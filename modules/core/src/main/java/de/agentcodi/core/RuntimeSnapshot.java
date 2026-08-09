package de.agentcodi.core;

import java.util.Objects;

public final class RuntimeSnapshot {
    private final long generation;
    private final RuntimePhase phase;
    private final String message;
    private final String engineVersion;
    private final String diagnostics;
    private final String workspacePath;

    public RuntimeSnapshot(
        long generation,
        RuntimePhase phase,
        String message,
        String engineVersion,
        String diagnostics,
        String workspacePath
    ) {
        this.generation = generation;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.message = nonNull(message);
        this.engineVersion = nonNull(engineVersion);
        this.diagnostics = nonNull(diagnostics);
        this.workspacePath = nonNull(workspacePath);
    }

    public long getGeneration() {
        return generation;
    }

    public RuntimePhase getPhase() {
        return phase;
    }

    public String getMessage() {
        return message;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public String getDiagnostics() {
        return diagnostics;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}

