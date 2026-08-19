package de.agentcodi.core;

public final class CodexRateLimitWindow {
    public static final long UNKNOWN_VALUE = -1L;

    private final int usedPercent;
    private final long windowDurationMinutes;
    private final long resetsAtSeconds;

    public CodexRateLimitWindow(
        int usedPercent,
        long windowDurationMinutes,
        long resetsAtSeconds
    ) {
        if (usedPercent < 0 || usedPercent > 100) {
            throw new IllegalArgumentException("Rate-limit usage must be between 0 and 100");
        }
        if (windowDurationMinutes != UNKNOWN_VALUE && windowDurationMinutes <= 0L) {
            throw new IllegalArgumentException("Rate-limit duration must be positive or unknown");
        }
        if (resetsAtSeconds != UNKNOWN_VALUE && resetsAtSeconds < 0L) {
            throw new IllegalArgumentException("Rate-limit reset must be non-negative or unknown");
        }
        this.usedPercent = usedPercent;
        this.windowDurationMinutes = windowDurationMinutes;
        this.resetsAtSeconds = resetsAtSeconds;
    }

    public int getUsedPercent() {
        return usedPercent;
    }

    public long getWindowDurationMinutes() {
        return windowDurationMinutes;
    }

    public long getResetsAtSeconds() {
        return resetsAtSeconds;
    }

    public boolean hasWindowDuration() {
        return windowDurationMinutes != UNKNOWN_VALUE;
    }

    public boolean hasResetTime() {
        return resetsAtSeconds != UNKNOWN_VALUE;
    }
}
