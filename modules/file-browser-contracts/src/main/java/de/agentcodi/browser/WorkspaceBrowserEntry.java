package de.agentcodi.browser;

public final class WorkspaceBrowserEntry {
    public enum Kind {
        DIRECTORY,
        FILE,
        UNAVAILABLE
    }

    private final String displayName;
    private final String relativePath;
    private final Kind kind;
    private final long byteCount;
    private final long lastModifiedMillis;
    private final String unavailableReason;

    private WorkspaceBrowserEntry(
        String displayName,
        String relativePath,
        Kind kind,
        long byteCount,
        long lastModifiedMillis,
        String unavailableReason
    ) {
        this.displayName = requireDisplayName(displayName);
        this.relativePath = requirePath(relativePath, kind);
        this.kind = requireKind(kind);
        this.byteCount = requireByteCount(byteCount, kind);
        this.lastModifiedMillis = Math.max(0L, lastModifiedMillis);
        this.unavailableReason = kind == Kind.UNAVAILABLE
            ? requireReason(unavailableReason)
            : "";
    }

    public static WorkspaceBrowserEntry directory(
        String displayName,
        String relativePath,
        long lastModifiedMillis
    ) {
        return new WorkspaceBrowserEntry(
            displayName,
            relativePath,
            Kind.DIRECTORY,
            -1L,
            lastModifiedMillis,
            ""
        );
    }

    public static WorkspaceBrowserEntry file(
        String displayName,
        String relativePath,
        long byteCount,
        long lastModifiedMillis
    ) {
        return new WorkspaceBrowserEntry(
            displayName,
            relativePath,
            Kind.FILE,
            byteCount,
            lastModifiedMillis,
            ""
        );
    }

    public static WorkspaceBrowserEntry unavailable(
        String displayName,
        String relativePath,
        long lastModifiedMillis,
        String reason
    ) {
        return new WorkspaceBrowserEntry(
            displayName,
            relativePath,
            Kind.UNAVAILABLE,
            -1L,
            lastModifiedMillis,
            reason
        );
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public Kind getKind() {
        return kind;
    }

    public long getByteCount() {
        return byteCount;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public boolean isOpenable() {
        return kind != Kind.UNAVAILABLE && !relativePath.isEmpty();
    }

    private static String requireDisplayName(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 255) {
            throw new IllegalArgumentException("Browser display name is invalid");
        }
        return value;
    }

    private static String requirePath(String value, Kind kind) {
        if (value == null || value.length() > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS) {
            throw new IllegalArgumentException("Browser relative path is invalid");
        }
        if (kind != Kind.UNAVAILABLE && value.isEmpty()) {
            throw new IllegalArgumentException("Openable browser entries require a path");
        }
        return value;
    }

    private static Kind requireKind(Kind value) {
        if (value == null) {
            throw new IllegalArgumentException("Browser entry kind is required");
        }
        return value;
    }

    private static long requireByteCount(long value, Kind kind) {
        if (kind == Kind.FILE && value < 0L) {
            throw new IllegalArgumentException("Browser file byte count is invalid");
        }
        return kind == Kind.FILE ? value : -1L;
    }

    private static String requireReason(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 64) {
            throw new IllegalArgumentException("Unavailable browser reason is invalid");
        }
        return value;
    }
}
