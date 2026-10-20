package de.agentcodi.editor;

/** Immutable saved revision or new-file draft. Text excludes an optional UTF-8 byte order mark. */
public final class WorkspaceEditorDocument {
    private final String relativePath;
    private final String text;
    private final byte[] savedBytes;
    private final boolean byteOrderMark;
    private final boolean newFile;

    public WorkspaceEditorDocument(
        String relativePath, String text, byte[] savedBytes, boolean byteOrderMark
    ) {
        this(relativePath, text, savedBytes, byteOrderMark, false);
    }

    private WorkspaceEditorDocument(
        String relativePath, String text, byte[] savedBytes, boolean byteOrderMark, boolean newFile
    ) {
        if (relativePath == null || relativePath.isEmpty() || text == null
            || savedBytes == null || savedBytes.length > WorkspaceEditorLimits.MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Editor document is invalid");
        }
        this.relativePath = relativePath;
        this.text = text;
        this.savedBytes = savedBytes.clone();
        this.byteOrderMark = byteOrderMark;
        this.newFile = newFile;
    }

    public static WorkspaceEditorDocument newFile(String relativePath) {
        return new WorkspaceEditorDocument(relativePath, "", new byte[0], false, true);
    }

    public String getRelativePath() { return relativePath; }
    public String getText() { return text; }
    public byte[] getSavedBytes() { return savedBytes.clone(); }
    public boolean hasByteOrderMark() { return byteOrderMark; }
    public boolean isNewFile() { return newFile; }
}
