package de.agentcodi.app;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.Editable;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/** Reports scrolling and cursor movement to decorations without owning editing state. */
final class WorkspaceCodeInput extends EditText {
    private Runnable viewportChanged;

    WorkspaceCodeInput(Context context) { super(context); }
    void setViewportChanged(Runnable callback) { viewportChanged = callback; }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection connection = super.onCreateInputConnection(info);
        if (connection == null) { return null; }
        return new InputConnectionWrapper(connection, false) {
            @Override public boolean deleteSurroundingText(int before, int after) {
                return super.deleteSurroundingText(
                    deletionLength(before, true, false), deletionLength(after, false, false)
                );
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                return super.deleteSurroundingTextInCodePoints(
                    deletionLength(before, true, true), deletionLength(after, false, true)
                );
            }
        };
    }

    /** The display treats CRLF as one break; an IME deletion must cover the whole pair. */
    private int deletionLength(int requested, boolean before, boolean codePoints) {
        if (requested <= 0 || getSelectionStart() < 0 || getSelectionEnd() < 0) { return requested; }
        Editable text = getText();
        int start = Math.min(getSelectionStart(), getSelectionEnd());
        int end = Math.max(getSelectionStart(), getSelectionEnd());
        // BaseInputConnection deletes around both the selection and the composing region.
        int composingStart = BaseInputConnection.getComposingSpanStart(text);
        int composingEnd = BaseInputConnection.getComposingSpanEnd(text);
        if (composingStart >= 0 && composingEnd >= 0) {
            start = Math.min(start, Math.min(composingStart, composingEnd));
            end = Math.max(end, Math.max(composingStart, composingEnd));
        }
        int boundary;
        if (codePoints) {
            try { boundary = Character.offsetByCodePoints(text, before ? start : end, before ? -requested : requested); }
            catch (IndexOutOfBoundsException outside) { boundary = before ? 0 : text.length(); }
        } else {
            boundary = before ? start - Math.min(start, requested)
                : end + Math.min(text.length() - end, requested);
        }
        return boundary > 0 && boundary < text.length()
            && text.charAt(boundary - 1) == '\r' && text.charAt(boundary) == '\n'
            ? requested + 1 : requested;
    }

    @Override public boolean onTextContextMenuItem(int action) {
        if ((action == android.R.id.copy || action == android.R.id.cut)
            && isEnabled() && getSelectionStart() >= 0 && getSelectionEnd() >= 0
            && getSelectionStart() != getSelectionEnd()) {
            int start = Math.min(getSelectionStart(), getSelectionEnd());
            int end = Math.max(getSelectionStart(), getSelectionEnd());
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            // Copy source characters, never the display-only CR substitutions or syntax spans.
            clipboard.setPrimaryClip(ClipData.newPlainText(null, getText().subSequence(start, end).toString()));
            if (action == android.R.id.cut) {
                beginBatchEdit();
                try { getText().delete(start, end); setSelection(start); }
                finally { endBatchEdit(); }
            }
            return true;
        }
        return super.onTextContextMenuItem(action);
    }

    private void notifyViewportChanged() {
        if (viewportChanged != null) { viewportChanged.run(); }
    }

    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        notifyViewportChanged();
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        notifyViewportChanged();
    }
}
