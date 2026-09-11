package de.agentcodi.core;

public enum RuntimePhase {
    IDLE,
    STARTING,
    READY,
    FAILED,
    STOPPING,
    STOPPED;

    public boolean canStart() {
        return this == IDLE || this == FAILED || this == STOPPED;
    }

    public boolean canStop() {
        return this == STARTING || this == READY || this == FAILED;
    }
}
