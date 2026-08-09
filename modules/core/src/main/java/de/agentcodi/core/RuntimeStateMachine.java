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
            ""
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
            workspacePath
        );
        return true;
    }

    public synchronized boolean markFailed(long generation, String message) {
        if (!isCurrentStart(generation)) {
            return false;
        }
        snapshot = new RuntimeSnapshot(
            generation,
            RuntimePhase.FAILED,
            isBlank(message) ? "Unbekannter Runtime-Fehler." : message,
            "",
            "",
            ""
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
            snapshot.getWorkspacePath()
        );
    }

    private boolean isCurrentStart(long generation) {
        return snapshot.getPhase() == RuntimePhase.STARTING
            && snapshot.getGeneration() == generation;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
