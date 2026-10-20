package de.agentcodi.editor;

/** Draft state stays separate from storage and the Android text widget. */
public final class WorkspaceEditorSession {
    private WorkspaceEditorDocument document;
    private String text;

    public WorkspaceEditorSession(WorkspaceEditorDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("Document is required");
        }
        this.document = document;
        this.text = document.getText();
    }

    public WorkspaceEditorDocument getDocument() { return document; }
    public String getText() { return text; }

    public void setText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text is required");
        }
        this.text = text;
    }

    public boolean isDirty() { return document.isNewFile() || !text.equals(document.getText()); }

    public void acceptSaved(WorkspaceEditorDocument saved) {
        if (saved == null || !saved.getRelativePath().equals(document.getRelativePath())) {
            throw new IllegalArgumentException("Saved revision belongs to another document");
        }
        // A completed save never replaces a newer draft.
        document = saved;
    }
}
