package de.agentcodi.editor;

import java.io.IOException;

public final class WorkspaceEditorException extends IOException {
    public enum Reason { UNSUPPORTED_TEXT, TOO_LARGE, FILE_CHANGED, INVALID_NAME, FILE_EXISTS }

    private final Reason reason;

    public WorkspaceEditorException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
