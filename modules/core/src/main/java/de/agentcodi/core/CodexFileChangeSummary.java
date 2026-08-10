package de.agentcodi.core;

public final class CodexFileChangeSummary {
    private final String path;
    private final String kind;
    private final String movePath;
    private final String diff;

    public CodexFileChangeSummary(String path, String kind, String diff) {
        this(path, kind, "", diff);
    }

    public CodexFileChangeSummary(
        String path,
        String kind,
        String movePath,
        String diff
    ) {
        this.path = path == null ? "" : path;
        this.kind = kind == null ? "" : kind;
        this.movePath = movePath == null ? "" : movePath;
        this.diff = diff == null ? "" : diff;
    }

    public String getPath() {
        return path;
    }

    public String getKind() {
        return kind;
    }

    public String getMovePath() {
        return movePath;
    }

    public String getDiff() {
        return diff;
    }
}
