package de.agentcodi.app;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorLimits;

/** Native, wrapping text surface. Storage and editing state are owned by the controller. */
final class WorkspaceEditorView extends LinearLayout {
    static final class Position {
        final int start, end, scrollX, scrollY;
        final boolean focused;

        Position(EditText input) {
            start = input.getSelectionStart();
            end = input.getSelectionEnd();
            scrollX = input.getScrollX();
            scrollY = input.getScrollY();
            focused = input.isFocused();
        }
    }

    interface Actions {
        void onTextChanged(String text);
        void onSave();
        void onExport();
    }

    private final UiTheme theme;
    private final TextView pathView;
    private final TextView statusView;
    private final EditText input;
    private final WorkspaceCodeEditor codeEditor;
    private final Button saveButton;
    private final ImageButton exportButton;
    private Actions actions;
    private boolean binding;

    WorkspaceEditorView(Context context, UiTheme theme) {
        super(context);
        this.theme = theme;
        setOrientation(VERTICAL);
        pathView = theme.text("", 13, theme.secondary);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        addView(pathView);

        codeEditor = new WorkspaceCodeEditor(context, theme);
        input = codeEditor.input;
        input.setFilters(new InputFilter[] {
            new InputFilter() {
                @Override public CharSequence filter(CharSequence source, int start, int end,
                    Spanned destination, int destinationStart, int destinationEnd) {
                    if (destination.length() - (destinationEnd - destinationStart) + (end - start)
                        > WorkspaceEditorLimits.MAXIMUM_FILE_BYTES) {
                        Toast.makeText(getContext(), R.string.editor_too_large, Toast.LENGTH_LONG).show();
                        // Reject the entire insertion instead of silently saving a truncated paste.
                        return destination.subSequence(destinationStart, destinationEnd);
                    }
                    return null;
                }
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                if (!binding && actions != null) {
                    actions.onTextChanged(text.toString());
                }
            }
        });
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_S && event.isCtrlPressed()) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                        && saveButton.isEnabled() && actions != null) {
                        actions.onSave();
                    }
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
        );
        body.topMargin = theme.dp(8);
        addView(codeEditor, body);

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        statusView = theme.text("", 13, theme.secondary);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        toolbar.addView(statusView, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        saveButton = theme.primaryButton(context.getString(R.string.editor_save));
        saveButton.setMinHeight(theme.dp(48));
        saveButton.setMinimumHeight(theme.dp(48));
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (actions != null) { actions.onSave(); }
            }
        });
        LinearLayout.LayoutParams save = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, theme.dp(48)
        );
        save.leftMargin = theme.dp(8);
        toolbar.addView(saveButton, save);
        exportButton = theme.iconButton(R.drawable.ic_chat_download,
            context.getString(R.string.browser_export));
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (actions != null) { actions.onExport(); }
            }
        });
        LinearLayout.LayoutParams export = new LinearLayout.LayoutParams(theme.dp(48), theme.dp(48));
        export.leftMargin = theme.dp(8);
        toolbar.addView(exportButton, export);
        theme.addWithTopMargin(this, toolbar, 8);
    }

    void setActions(Actions actions) { this.actions = actions; }

    void bind(WorkspaceEditorDocument document) {
        pathView.setText(document.getRelativePath());
        binding = true;
        codeEditor.setPath(document.getRelativePath());
        input.setText(document.getText());
        input.setSelection(0);
        binding = false;
    }

    void clear() {
        binding = true;
        codeEditor.setPath("");
        input.setText("");
        input.clearFocus();
        pathView.setText("");
        binding = false;
    }

    Position capturePosition() { return new Position(input); }

    void restoreDraft(String text, final Position position) {
        binding = true;
        input.setText(text);
        input.setSelection(Math.max(0, Math.min(position.start, text.length())),
            Math.max(0, Math.min(position.end, text.length())));
        if (position.focused) { input.requestFocus(); }
        binding = false;
        input.post(new Runnable() {
            @Override public void run() { input.scrollTo(position.scrollX, position.scrollY); }
        });
    }

    void dispose() { codeEditor.dispose(); }

    void showState(int status, boolean error, boolean dirty, boolean busy) {
        if (!getContext().getString(status).contentEquals(statusView.getText())) {
            statusView.setText(status);
        }
        statusView.setTextColor(error ? theme.danger : dirty ? theme.warning : theme.secondary);
        input.setEnabled(!busy);
        theme.setEnabled(saveButton, dirty && !busy);
        theme.setEnabled(exportButton, !dirty && !busy);
    }
}
