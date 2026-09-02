package de.agentcodi.core;

import java.util.Objects;

public final class RuntimeSnapshot {
    private final long generation;
    private final RuntimePhase phase;
    private final String message;
    private final String engineVersion;
    private final String diagnostics;
    private final String workspacePath;
    private final String executionModeId;
    private final String permissionProfileId;
    private final boolean compatibilityApprovalsEnabled;

    public RuntimeSnapshot(
        long generation,
        RuntimePhase phase,
        String message,
        String engineVersion,
        String diagnostics,
        String workspacePath
    ) {
        this(
            generation,
            phase,
            message,
            engineVersion,
            diagnostics,
            workspacePath,
            "",
            "",
            false
        );
    }

    public RuntimeSnapshot(
        long generation,
        RuntimePhase phase,
        String message,
        String engineVersion,
        String diagnostics,
        String workspacePath,
        String executionModeId,
        String permissionProfileId
    ) {
        this(
            generation,
            phase,
            message,
            engineVersion,
            diagnostics,
            workspacePath,
            executionModeId,
            permissionProfileId,
            false
        );
    }

    public RuntimeSnapshot(
        long generation,
        RuntimePhase phase,
        String message,
        String engineVersion,
        String diagnostics,
        String workspacePath,
        String executionModeId,
        String permissionProfileId,
        boolean compatibilityApprovalsEnabled
    ) {
        this.generation = generation;
        this.phase = Objects.requireNonNull(phase, "phase");
        this.message = nonNull(message);
        this.engineVersion = nonNull(engineVersion);
        this.diagnostics = nonNull(diagnostics);
        this.workspacePath = nonNull(workspacePath);
        this.executionModeId = nonNull(executionModeId);
        this.permissionProfileId = nonNull(permissionProfileId);
        this.compatibilityApprovalsEnabled = compatibilityApprovalsEnabled;
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

    public String getExecutionModeId() {
        return executionModeId;
    }

    public String getPermissionProfileId() {
        return permissionProfileId;
    }

    public boolean isCompatibilityApprovalsEnabled() {
        return compatibilityApprovalsEnabled;
    }

    public RuntimeSnapshot withExecutionMode(
        String updatedExecutionModeId,
        String updatedPermissionProfileId
    ) {
        return withExecutionMode(
            updatedExecutionModeId,
            updatedPermissionProfileId,
            false
        );
    }

    public RuntimeSnapshot withExecutionMode(
        String updatedExecutionModeId,
        String updatedPermissionProfileId,
        boolean updatedCompatibilityApprovalsEnabled
    ) {
        return new RuntimeSnapshot(
            generation,
            phase,
            message,
            engineVersion,
            diagnostics,
            workspacePath,
            updatedExecutionModeId,
            updatedPermissionProfileId,
            updatedCompatibilityApprovalsEnabled
        );
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
