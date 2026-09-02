package de.agentcodi.core;

public final class RuntimeStateMachine {
    private RuntimeSnapshot snapshot = new RuntimeSnapshot(
        0L,
        RuntimePhase.IDLE,
        "Runtime wartet auf den Start.",
        "",
        "",
        ""
    );

    public synchronized RuntimeSnapshot snapshot() {
        return snapshot;
    }

    public synchronized long beginStart() {
        return beginStart(
            CodexExecutionMode.PROTECTED_ID,
            CodexExecutionMode.PROTECTED_PERMISSION_PROFILE_ID,
            false
        );
    }

    public synchronized long beginStart(
        String executionModeId,
        String permissionProfileId
    ) {
        return beginStart(executionModeId, permissionProfileId, false);
    }

    public synchronized long beginStart(
        String executionModeId,
        String permissionProfileId,
        boolean compatibilityApprovalsEnabled
    ) {
        RuntimePhase phase = snapshot.getPhase();
        if (phase == RuntimePhase.STARTING || phase == RuntimePhase.READY) {
            throw new IllegalStateException("Runtime cannot start from " + phase);
        }
        long generation = snapshot.getGeneration() + 1L;
        snapshot = new RuntimeSnapshot(
            generation,
            RuntimePhase.STARTING,
            "Java/C++-Runtime und Codex App-Server werden gestartet.",
            "",
            "",
            "",
            requiredModeValue(executionModeId, "Execution mode"),
            requiredModeValue(permissionProfileId, "Permission profile"),
            compatibilityApprovalsEnabled
        );
        return generation;
    }

    public synchronized boolean markReady(
        long generation,
        String engineVersion,
        String diagnostics,
        String workspacePath
    ) {
        if (!isCurrentStart(generation)) {
            return false;
        }
        if (isBlank(engineVersion) || isBlank(workspacePath)) {
            throw new IllegalArgumentException("Ready state requires engine version and workspace");
        }
        snapshot = new RuntimeSnapshot(
            generation,
            RuntimePhase.READY,
            "Codex App-Server ist initialisiert.",
            engineVersion,
            diagnostics,
            workspacePath,
            snapshot.getExecutionModeId(),
            snapshot.getPermissionProfileId(),
            snapshot.isCompatibilityApprovalsEnabled()
        );
        return true;
    }

    public synchronized boolean markFailed(long generation, String message) {
        RuntimePhase phase = snapshot.getPhase();
        if (snapshot.getGeneration() != generation
            || (phase != RuntimePhase.STARTING && phase != RuntimePhase.READY)) {
            return false;
        }
        snapshot = new RuntimeSnapshot(
            generation,
            RuntimePhase.FAILED,
            isBlank(message) ? "Unbekannter Runtime-Fehler." : message,
            phase == RuntimePhase.READY ? snapshot.getEngineVersion() : "",
            phase == RuntimePhase.READY ? snapshot.getDiagnostics() : "",
            phase == RuntimePhase.READY ? snapshot.getWorkspacePath() : "",
            snapshot.getExecutionModeId(),
            snapshot.getPermissionProfileId(),
            snapshot.isCompatibilityApprovalsEnabled()
        );
        return true;
    }

    public synchronized void stop() {
        snapshot = new RuntimeSnapshot(
            snapshot.getGeneration(),
            RuntimePhase.STOPPED,
            "Runtime wurde gestoppt.",
            snapshot.getEngineVersion(),
            snapshot.getDiagnostics(),
            snapshot.getWorkspacePath(),
            snapshot.getExecutionModeId(),
            snapshot.getPermissionProfileId(),
            snapshot.isCompatibilityApprovalsEnabled()
        );
    }

    private boolean isCurrentStart(long generation) {
        return snapshot.getPhase() == RuntimePhase.STARTING
            && snapshot.getGeneration() == generation;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String requiredModeValue(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > 80) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }
}
