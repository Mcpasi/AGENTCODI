package de.agentcodi.editor;

/** Full-document editing stays bounded independently of paged previews. */
public final class WorkspaceEditorLimits {
    public static final int MAXIMUM_FILE_BYTES = 1024 * 1024;

    private WorkspaceEditorLimits() {
    }
}
