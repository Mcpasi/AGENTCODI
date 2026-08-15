package de.agentcodi.core;

public final class TerminalSessionSnapshot {
    private final long revision;
    private final boolean running;
    private final boolean starting;
    private final int exitCode;
    private final String output;
    private final String failure;

    public TerminalSessionSnapshot(
        long revision,
        boolean running,
        boolean starting,
        int exitCode,
        String output,
        String failure
    ) {
        this.revision = revision;
        this.running = running;
        this.starting = starting;
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.failure = failure == null ? "" : failure;
    }

    public static TerminalSessionSnapshot stopped() {
        return new TerminalSessionSnapshot(0L, false, false, Integer.MIN_VALUE, "", "");
    }

    public long getRevision() {
        return revision;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isStarting() {
        return starting;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() {
        return output;
    }

    public String getFailure() {
        return failure;
    }
}
