package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.widget.Toast;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorSession;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Editor lifecycle, unsaved-change navigation and asynchronous persistence. */
final class WorkspaceEditorController {
    interface Saver {
        WorkspaceEditorDocument save(WorkspaceEditorDocument document, String text) throws IOException;
    }

    /** Retains source and save progress across configuration changes without a view or Activity. */
    static final class RetainedState {
        final WorkspaceEditorSession session;
        final SaveOperation pendingSave;
        final int failure;
        final WorkspaceEditorView.Position position;

        RetainedState(WorkspaceEditorSession session, SaveOperation pendingSave, int failure,
            WorkspaceEditorView.Position position) {
            this.session = session;
            this.pendingSave = pendingSave;
            this.failure = failure;
            this.position = position;
        }
    }

    private static final class SaveOperation {
        final WorkspaceEditorSession session;
        final boolean continueAfterSave;
        boolean complete;
        int failure;
        Runnable completion;

        SaveOperation(WorkspaceEditorSession session, boolean continueAfterSave) {
            this.session = session;
            this.continueAfterSave = continueAfterSave;
        }

        void finish(WorkspaceEditorDocument saved, int failure) {
            if (saved != null) { session.acceptSaved(saved); }
            this.failure = failure;
            complete = true;
            if (completion != null) { completion.run(); }
        }
    }

    private final Activity activity;
    private final WorkspaceEditorView view;
    private final Executor operations;
    private final Handler handler;
    private final Saver saver;
    private final Runnable stateChanged;
    private WorkspaceEditorSession session;
    private SaveOperation pendingSave;
    private AlertDialog leaveDialog;
    private boolean saving;
    private boolean externalBusy;
    private boolean destroyed;
    private int failure;

    WorkspaceEditorController(Activity activity, WorkspaceEditorView view, Executor operations,
        Handler handler, Saver saver, Runnable stateChanged, final Runnable export) {
        this.activity = activity;
        this.view = view;
        this.operations = operations;
        this.handler = handler;
        this.saver = saver;
        this.stateChanged = stateChanged;
        view.setActions(new WorkspaceEditorView.Actions() {
            @Override public void onTextChanged(String text) {
                if (session != null) {
                    session.setText(text);
                    failure = 0;
                    renderState();
                }
            }
            @Override public void onSave() { save(null); }
            @Override public void onExport() {
                if (session != null && !session.isDirty() && !saving && !externalBusy) {
                    export.run();
                }
            }
        });
    }

    boolean isOpen() { return session != null; }
    boolean isSaving() { return saving; }
    boolean isNewFile() { return session != null && session.getDocument().isNewFile(); }

    void open(WorkspaceEditorDocument document) {
        close();
        session = new WorkspaceEditorSession(document);
        view.bind(document);
        renderState();
    }

    void close() {
        if (leaveDialog != null) { leaveDialog.dismiss(); leaveDialog = null; }
        if (pendingSave != null) { pendingSave.completion = null; pendingSave = null; }
        saving = false;
        session = null;
        failure = 0;
        view.clear();
    }

    void destroy() { destroyed = true; close(); view.dispose(); }

    RetainedState retain() {
        return session == null ? null : new RetainedState(session, pendingSave, failure, view.capturePosition());
    }

    void restore(RetainedState retained, Runnable continuation) {
        close();
        session = retained.session;
        failure = retained.failure;
        pendingSave = retained.pendingSave;
        view.bind(session.getDocument());
        view.restoreDraft(session.getText(), retained.position);
        if (pendingSave != null) {
            saving = true;
            observeSave(pendingSave, pendingSave.continueAfterSave ? continuation : null);
            if (pendingSave.complete) {
                // Finish after the Activity has restored its editor panel and controls.
                handler.post(pendingSave.completion);
            }
        }
        renderState();
    }

    void setExternalBusy(boolean busy) { externalBusy = busy; renderState(); }

    void requestLeave(final Runnable continuation) {
        if (saving || externalBusy || destroyed) { return; }
        if (session == null || !session.isDirty()) {
            continuation.run();
            return;
        }
        if (leaveDialog != null && leaveDialog.isShowing()) { return; }
        leaveDialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.editor_unsaved_title)
            .setMessage(R.string.editor_unsaved_message)
            .setPositiveButton(R.string.editor_save_and_continue, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { save(continuation); }
            })
            .setNegativeButton(R.string.editor_discard, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) { continuation.run(); }
            })
            .setNeutralButton(android.R.string.cancel, null)
            .show();
    }

    private void save(final Runnable afterSave) {
        if (session == null || saving || externalBusy || !session.isDirty() || destroyed) { return; }
        final WorkspaceEditorSession selected = session;
        final WorkspaceEditorDocument document = selected.getDocument();
        final String text = selected.getText();
        final Saver selectedSaver = saver;
        final SaveOperation operation = new SaveOperation(selected, afterSave != null);
        pendingSave = operation;
        observeSave(operation, afterSave);
        // Browser callbacks are cleared on destruction; persistence must still update its retained revision.
        final Handler resultHandler = new Handler(handler.getLooper());
        saving = true;
        failure = 0;
        renderState();
        stateChanged.run();
        try {
            operations.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final WorkspaceEditorDocument saved = selectedSaver.save(document, text);
                        resultHandler.post(new Runnable() {
                            @Override public void run() {
                                operation.finish(saved, 0);
                            }
                        });
                    } catch (IOException | RuntimeException error) {
                        final int message = errorMessage(error);
                        resultHandler.post(new Runnable() {
                            @Override public void run() {
                                operation.finish(null, message);
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException error) {
            operation.finish(null, R.string.editor_save_failed);
        }
    }

    private void observeSave(final SaveOperation operation, final Runnable afterSave) {
        operation.completion = new Runnable() {
            @Override public void run() {
                if (destroyed || pendingSave != operation || session != operation.session) { return; }
                pendingSave = null;
                operation.completion = null;
                if (operation.failure != 0) { saveFailed(operation.failure); return; }
                saving = false;
                failure = 0;
                renderState();
                stateChanged.run();
                Toast.makeText(activity, R.string.editor_saved, Toast.LENGTH_SHORT).show();
                if (afterSave != null && !operation.session.isDirty()) { afterSave.run(); }
            }
        };
    }

    private void saveFailed(int message) {
        saving = false;
        failure = message;
        renderState();
        stateChanged.run();
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static int errorMessage(Throwable error) {
        if (error instanceof WorkspaceEditorException) {
            WorkspaceEditorException.Reason reason = ((WorkspaceEditorException) error).getReason();
            if (reason == WorkspaceEditorException.Reason.FILE_CHANGED) { return R.string.editor_conflict; }
            if (reason == WorkspaceEditorException.Reason.TOO_LARGE) { return R.string.editor_too_large; }
            if (reason == WorkspaceEditorException.Reason.UNSUPPORTED_TEXT) { return R.string.editor_invalid_text; }
            if (reason == WorkspaceEditorException.Reason.FILE_EXISTS) { return R.string.editor_file_exists; }
        }
        return R.string.editor_save_failed;
    }

    private void renderState() {
        if (session == null) { return; }
        int status = saving ? R.string.editor_saving : externalBusy ? R.string.editor_working
            : failure != 0 ? failure : session.getDocument().isNewFile() ? R.string.editor_new_unsaved
            : session.isDirty() ? R.string.editor_unsaved : R.string.editor_saved;
        view.showState(status, failure != 0, session.isDirty(), saving || externalBusy);
    }
}
