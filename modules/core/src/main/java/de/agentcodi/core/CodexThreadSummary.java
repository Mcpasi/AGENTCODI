package de.agentcodi.core;

public final class CodexThreadSummary {
    private final String id;
    private final String title;
    private final long updatedAtSeconds;

    public CodexThreadSummary(String id, String title, long updatedAtSeconds) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Thread id must not be blank");
        }
        this.id = id;
        this.title = title == null || title.trim().isEmpty() ? "Unbenannter Chat" : title;
        this.updatedAtSeconds = updatedAtSeconds;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public long getUpdatedAtSeconds() {
        return updatedAtSeconds;
    }
}
