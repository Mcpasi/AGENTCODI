package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.runtime.WorkspaceEditorRepository;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Filename input and asynchronous preflight; opening the draft does not write a file. */
final class WorkspaceNewFileDialog {
    interface Listener { void onReady(WorkspaceEditorDocument document); }

    private WorkspaceNewFileDialog() {
    }

    static AlertDialog show(final Activity activity, final String directory,
        final WorkspaceEditorRepository repository, final Executor operations,
        final Handler handler, final Listener listener) {
        UiTheme theme = new UiTheme(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(8));
        TextView location = theme.text(activity.getString(R.string.editor_new_file_location,
            directory.isEmpty() ? activity.getString(R.string.browser_workspace_root) : directory),
            13, theme.secondary);
        location.setMaxLines(2);
        location.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        body.addView(location);
        final EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        name.setHint(R.string.editor_new_file_name_hint);
        name.setContentDescription(activity.getString(R.string.editor_new_file_name));
        theme.addWithTopMargin(body, name, 8);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.editor_new_file)
            .setView(body)
            .setPositiveButton(R.string.editor_new_file_open, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        final Runnable submit = new Runnable() {
            @Override public void run() {
                if (!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()) { return; }
                final String filename = name.getText().toString();
                name.setError(null);
                name.setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                try {
                    operations.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                final WorkspaceEditorDocument document = repository.newDocument(directory, filename);
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        if (!isActive(activity, dialog)) { return; }
                                        dialog.dismiss();
                                        listener.onReady(document);
                                    }
                                });
                            } catch (IOException | RuntimeException error) {
                                final int message = errorMessage(error);
                                handler.post(new Runnable() {
                                    @Override public void run() {
                                        if (isActive(activity, dialog)) { showError(dialog, name, message); }
                                    }
                                });
                            }
                        }
                    });
                } catch (RejectedExecutionException error) {
                    showError(dialog, name, R.string.editor_new_file_failed);
                }
            }
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { submit.run(); }
        });
        name.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView view, int action, KeyEvent event) {
                if (action == EditorInfo.IME_ACTION_DONE) { submit.run(); return true; }
                return false;
            }
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        name.requestFocus();
        name.post(new Runnable() {
            @Override public void run() {
                if (!isActive(activity, dialog)) { return; }
                InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) { keyboard.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT); }
            }
        });
        return dialog;
    }

    private static boolean isActive(Activity activity, AlertDialog dialog) {
        return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void showError(AlertDialog dialog, EditText name, int message) {
        name.setEnabled(true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        name.setError(name.getContext().getString(message));
        name.requestFocus();
    }

    private static int errorMessage(Throwable error) {
        if (error instanceof WorkspaceEditorException) {
            WorkspaceEditorException.Reason reason = ((WorkspaceEditorException) error).getReason();
            if (reason == WorkspaceEditorException.Reason.INVALID_NAME) { return R.string.editor_invalid_name; }
            if (reason == WorkspaceEditorException.Reason.FILE_EXISTS) { return R.string.editor_file_exists; }
        }
        return R.string.editor_new_file_failed;
    }
}
