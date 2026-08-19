package de.agentcodi.core;

public final class CodexRateLimitsSnapshot {
    private static final CodexRateLimitsSnapshot UNAVAILABLE =
        new CodexRateLimitsSnapshot(null, null);

    private final CodexRateLimitWindow primary;
    private final CodexRateLimitWindow secondary;

    public CodexRateLimitsSnapshot(
        CodexRateLimitWindow primary,
        CodexRateLimitWindow secondary
    ) {
        this.primary = primary;
        this.secondary = secondary;
    }

    public static CodexRateLimitsSnapshot unavailable() {
        return UNAVAILABLE;
    }

    public boolean isAvailable() {
        return primary != null || secondary != null;
    }

    public CodexRateLimitWindow getPrimary() {
        return primary;
    }

    public CodexRateLimitWindow getSecondary() {
        return secondary;
    }
}
