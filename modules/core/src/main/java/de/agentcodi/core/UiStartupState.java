package de.agentcodi.core;

public final class UiStartupState {
    private String stage = "created";
    private boolean ready;
    private boolean failed;

    public synchronized void enter(String nextStage) {
        if (ready || failed) {
            throw new IllegalStateException("UI startup already finished");
        }
        if (nextStage == null || nextStage.trim().isEmpty()) {
            throw new IllegalArgumentException("Startup stage must not be blank");
        }
        stage = nextStage;
    }

    public synchronized void complete() {
        if (failed) {
            throw new IllegalStateException("Failed UI startup cannot complete");
        }
        stage = "ready";
        ready = true;
    }

    public synchronized void fail() {
        ready = false;
        failed = true;
    }

    public synchronized boolean shouldRefresh() {
        return ready && !failed;
    }

    public synchronized String failureSource() {
        return "activity-" + stage;
    }
}
