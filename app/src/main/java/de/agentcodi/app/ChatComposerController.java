package de.agentcodi.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.text.Editable;
import android.view.View;
import android.widget.Toast;

import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.CodexAppMention;
import de.agentcodi.core.CodexFileMentionTransaction;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.CredentialGuard;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportGrant;
import de.agentcodi.imports.WorkspaceImportLimits;
import de.agentcodi.imports.WorkspaceImportSelection;
import de.agentcodi.runtime.AgentRuntimeService;
import de.agentcodi.runtime.WorkspaceFileImporter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Owns drafts' attachment selections, import work and prepared-send transactions. */
final class ChatComposerController {
    private static final int FILE_IMPORT_REQUEST_CODE = 7002;
    private static final int CONNECTOR_REQUEST_CODE = 7003;

    private final Activity activity;
    private final UiTheme theme;
    private final Handler handler;
    private final ChatScreenView views;
    private final Runnable refresh;
    private boolean destroyed;
    private final ExecutorService importOperations = Executors.newSingleThreadExecutor();
    private final Object preparedImportSendLock = new Object();
    private final List<ImportedWorkspaceFile> pendingImports =
        new ArrayList<ImportedWorkspaceFile>();
    private final List<ConnectorSelection> pendingConnectors =
        new ArrayList<ConnectorSelection>();
    private String pendingImportsThreadId = "";
    private String pendingConnectorsThreadId = "";
    private boolean importOperationActive;
    private boolean sendPreparationActive;
    private CodexFileMentionTransaction preparedImportSend;

    ChatComposerController(Activity activity, UiTheme theme, Handler handler,
        ChatScreenView views, Runnable refresh) {
        this.activity = activity;
        this.theme = theme;
        this.handler = handler;
        this.views = views;
        this.refresh = refresh;
    }

    private void refreshLocalComposerState() {
        refresh.run();
    }

    void reconcileSelections(CodexSessionSnapshot session) {
        reconcilePendingImports(session);
        reconcilePendingConnectors(session);
    }

    void close() {
        CodexFileMentionTransaction abandonedSend;
        synchronized (preparedImportSendLock) {
            destroyed = true;
            abandonedSend = preparedImportSend;
            preparedImportSend = null;
        }
        closeFileTransaction(abandonedSend);
        importOperations.shutdownNow();
    }

    boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CONNECTOR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                acceptConnectorSelection(data);
            }
            return true;
        }
        if (requestCode == FILE_IMPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                WorkspaceImportGrant sourceGrant =
                    WorkspaceImportGrant.fromResultIntentFlags(
                        data.getFlags(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                if (!sourceGrant.hasTransientReadPermission()) {
                    Toast.makeText(
                        activity,
                        R.string.chat_import_read_grant_missing,
                        Toast.LENGTH_LONG
                    ).show();
                    return true;
                }
                beginDocumentImport(selectedDocumentUris(data), sourceGrant);
            }
            return true;
        }
        return false;
    }

    void render(CodexSessionSnapshot session, boolean canChat, boolean interactionOpen) {
        boolean steering = session.isTurnActive();
        views.composerInput.setHint(
            steering ? R.string.composer_steer_hint : R.string.composer_hint
        );
        theme.setIcon(
            views.sendButton,
            R.drawable.ic_chat_send,
            activity.getString(steering ? R.string.turn_steer : R.string.message_send)
        );
        boolean composerReady = canChat && !interactionOpen
            && !importOperationActive && !sendPreparationActive;
        views.composerInput.setEnabled(composerReady);
        theme.setEnabled(views.sendButton, composerReady);
        theme.setEnabled(
            views.importButton,
            composerReady
                && pendingImports.size() < WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE
        );
        theme.setEnabled(
            views.connectorButton,
            composerReady && !session.getActiveThreadId().isEmpty()
        );
        views.clearConnectorsButton.setVisibility(
            pendingConnectors.isEmpty() ? View.GONE : View.VISIBLE
        );
        theme.setEnabled(
            views.clearConnectorsButton,
            composerReady && !pendingConnectors.isEmpty()
        );
        views.clearImportsButton.setVisibility(
            pendingImports.isEmpty() ? View.GONE : View.VISIBLE
        );
        theme.setEnabled(views.clearImportsButton, composerReady && !pendingImports.isEmpty());
        theme.setEnabled(
            views.reviewButton,
            composerReady
                && !session.isTurnActive()
                && !session.getActiveThreadId().isEmpty()
                && pendingImports.isEmpty()
        );
        renderConnectorSelection();
        renderImportSelection();
        views.stopButton.setVisibility(steering ? View.VISIBLE : View.GONE);
        theme.setEnabled(
            views.stopButton,
            session.isReady()
                && session.isTurnActive()
                && !session.isTurnInterruptPending()
                && !session.getActiveTurnId().isEmpty()
        );
    }

    void sendComposerInput() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        Editable editable = views.composerInput.getText();
        if (CredentialGuard.containsLikelyCredential(editable)) {
            editable.clear();
            Toast.makeText(
                activity,
                R.string.user_input_credential_warning,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        final String prompt = editable.toString();
        if (prompt.trim().isEmpty() && pendingImports.isEmpty()) {
            return;
        }
        final CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        if (!pendingImports.isEmpty()
            && !snapshot.getActiveThreadId().equals(pendingImportsThreadId)) {
            Toast.makeText(activity, R.string.chat_import_context_changed, Toast.LENGTH_LONG).show();
            detachPendingImports();
            return;
        }
        if (!pendingConnectors.isEmpty()
            && !snapshot.getActiveThreadId().equals(pendingConnectorsThreadId)) {
            Toast.makeText(
                activity,
                R.string.chat_connector_context_changed,
                Toast.LENGTH_LONG
            ).show();
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
            refreshLocalComposerState();
            return;
        }
        final List<ConnectorSelection> connectorSelections =
            ConnectorSelection.copyOf(pendingConnectors);
        if (!connectorSelections.isEmpty()
            && !AgentRuntimeService.areConnectorsCallable(connectorSelections)) {
            AgentRuntimeService.refreshConnectorCatalog(true);
            Toast.makeText(
                activity,
                R.string.chat_connector_unavailable,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        final List<CodexAppMention> appMentions = appMentions(connectorSelections);
        if (pendingImports.isEmpty()) {
            boolean accepted = snapshot.isTurnActive()
                ? AgentRuntimeService.steerTurn(prompt, appMentions)
                : AgentRuntimeService.sendMessage(prompt, appMentions);
            if (accepted) {
                editable.clear();
                pendingConnectors.clear();
                pendingConnectorsThreadId = "";
                refreshLocalComposerState();
            }
            return;
        }

        final List<ImportedWorkspaceFile> files = WorkspaceImportSelection.copyOf(
            pendingImports
        );
        final boolean steering = snapshot.isTurnActive();
        final String threadId = snapshot.getActiveThreadId();
        final String turnId = snapshot.getActiveTurnId();
        final android.content.Context applicationContext = activity.getApplicationContext();
        sendPreparationActive = true;
        refreshLocalComposerState();
        if (!submitImportOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final CodexFileMentionTransaction fileTransaction =
                        WorkspaceFileImporter.prepareForCodex(
                            applicationContext,
                            files
                        );
                    boolean posted = false;
                    try {
                        if (!registerPreparedImportSend(fileTransaction)) {
                            return;
                        }
                        posted = handler.post(new Runnable() {
                            @Override
                            public void run() {
                                completePreparedSend(
                                    prompt,
                                    files,
                                    fileTransaction,
                                    steering,
                                    threadId,
                                    turnId,
                                    connectorSelections,
                                    appMentions
                                );
                            }
                        });
                    } finally {
                        if (!posted) {
                            releasePreparedImportSend(fileTransaction);
                            closeFileTransaction(fileTransaction);
                        }
                    }
                } catch (Throwable error) {
                    final String reason = importFailureReason(error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            sendPreparationActive = false;
                            refreshLocalComposerState();
                            if (!destroyed && !activity.isFinishing()) {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.chat_import_verify_failed, reason),
                                    Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                    });
                }
            }
        })) {
            sendPreparationActive = false;
            refreshLocalComposerState();
            Toast.makeText(
                activity,
                R.string.chat_import_operation_start_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void completePreparedSend(
        String prompt,
        List<ImportedWorkspaceFile> preparedFiles,
        CodexFileMentionTransaction fileTransaction,
        boolean steering,
        String threadId,
        String turnId,
        List<ConnectorSelection> preparedConnectors,
        List<CodexAppMention> appMentions
    ) {
        if (!releasePreparedImportSend(fileTransaction)) {
            closeFileTransaction(fileTransaction);
            return;
        }
        if (destroyed) {
            closeFileTransaction(fileTransaction);
            return;
        }
        sendPreparationActive = false;
        CodexSessionSnapshot current = AgentRuntimeService.sessionSnapshot();
        boolean sameContext = threadId.equals(current.getActiveThreadId())
            && steering == current.isTurnActive()
            && (!steering || turnId.equals(current.getActiveTurnId()))
            && preparedFiles.equals(WorkspaceImportSelection.copyOf(pendingImports))
            && preparedConnectors.equals(ConnectorSelection.copyOf(pendingConnectors))
            && (preparedConnectors.isEmpty()
                || AgentRuntimeService.areConnectorsCallable(preparedConnectors));
        if (!sameContext) {
            closeFileTransaction(fileTransaction);
            refreshLocalComposerState();
            Toast.makeText(
                activity,
                R.string.chat_import_context_changed,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        boolean accepted = steering
            ? AgentRuntimeService.steerTurn(prompt, fileTransaction, appMentions)
            : AgentRuntimeService.sendMessage(prompt, fileTransaction, appMentions);
        if (accepted) {
            views.composerInput.getText().clear();
            pendingImports.clear();
            pendingImportsThreadId = "";
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
        }
        refreshLocalComposerState();
    }

    private boolean registerPreparedImportSend(
        CodexFileMentionTransaction fileTransaction
    ) {
        synchronized (preparedImportSendLock) {
            if (destroyed || preparedImportSend != null) {
                return false;
            }
            preparedImportSend = fileTransaction;
            return true;
        }
    }

    private boolean releasePreparedImportSend(
        CodexFileMentionTransaction fileTransaction
    ) {
        synchronized (preparedImportSendLock) {
            if (preparedImportSend != fileTransaction) {
                return false;
            }
            preparedImportSend = null;
            return true;
        }
    }

    private static void closeFileTransaction(
        CodexFileMentionTransaction fileTransaction
    ) {
        if (fileTransaction == null) {
            return;
        }
        try {
            fileTransaction.close();
        } catch (IOException ignored) {
            // The verified batch is already unusable and remains fail-closed.
        }
    }

    void openDocumentImportPicker() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        if (snapshot.getActiveThreadId().isEmpty()) {
            Toast.makeText(activity, R.string.chat_import_requires_chat, Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingImports.size() >= WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE
            || WorkspaceImportSelection.totalBytes(pendingImports)
                >= WorkspaceImportLimits.MAXIMUM_TOTAL_BYTES) {
            Toast.makeText(activity, R.string.chat_import_limit_reached, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(intent, FILE_IMPORT_REQUEST_CODE);
        } catch (Throwable error) {
            Toast.makeText(
                activity,
                R.string.document_picker_open_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private List<Uri> selectedDocumentUris(Intent data) {
        List<Uri> selected = new ArrayList<Uri>();
        ClipData clipData = data.getClipData();
        int maximum = WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE + 1;
        if (clipData != null) {
            int count = Math.min(clipData.getItemCount(), maximum);
            for (int index = 0; index < count; index++) {
                ClipData.Item item = clipData.getItemAt(index);
                if (item != null && item.getUri() != null) {
                    selected.add(item.getUri());
                }
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        return selected;
    }

    private void beginDocumentImport(
        final List<Uri> sourceUris,
        final WorkspaceImportGrant sourceGrant
    ) {
        if (sourceUris == null || sourceUris.isEmpty()
            || sourceGrant == null || !sourceGrant.hasTransientReadPermission()
            || importOperationActive || sendPreparationActive) {
            return;
        }
        final CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        final String threadId = snapshot.getActiveThreadId();
        if (threadId.isEmpty()) {
            Toast.makeText(activity, R.string.chat_import_requires_chat, Toast.LENGTH_LONG).show();
            return;
        }
        int remainingFiles = WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE
            - pendingImports.size();
        if (sourceUris.size() > remainingFiles) {
            Toast.makeText(activity, R.string.chat_import_too_many, Toast.LENGTH_LONG).show();
            return;
        }
        final long remainingBytes = WorkspaceImportLimits.MAXIMUM_TOTAL_BYTES
            - WorkspaceImportSelection.totalBytes(pendingImports);
        if (remainingBytes <= 0L) {
            Toast.makeText(activity, R.string.chat_import_limit_reached, Toast.LENGTH_LONG).show();
            return;
        }
        if (!pendingImports.isEmpty() && !threadId.equals(pendingImportsThreadId)) {
            detachPendingImports();
        }
        pendingImportsThreadId = threadId;
        importOperationActive = true;
        refreshLocalComposerState();
        final android.content.Context applicationContext = activity.getApplicationContext();
        if (!submitImportOperation(new Runnable() {
            @Override
            public void run() {
                importSelectedDocuments(
                    applicationContext,
                    threadId,
                    sourceUris,
                    sourceGrant,
                    remainingBytes
                );
            }
        })) {
            importOperationActive = false;
            if (pendingImports.isEmpty()) {
                pendingImportsThreadId = "";
            }
            refreshLocalComposerState();
            Toast.makeText(
                activity,
                R.string.chat_import_operation_start_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void importSelectedDocuments(
        android.content.Context applicationContext,
        String threadId,
        List<Uri> sourceUris,
        WorkspaceImportGrant sourceGrant,
        long initialRemainingBytes
    ) {
        final List<ImportedWorkspaceFile> imported =
            new ArrayList<ImportedWorkspaceFile>();
        int failures = 0;
        String firstFailure = "";
        long remainingBytes = initialRemainingBytes;
        for (Uri sourceUri : sourceUris) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (remainingBytes <= 0L) {
                failures++;
                if (firstFailure.isEmpty()) {
                    firstFailure = activity.getString(R.string.error_reason_limit);
                }
                continue;
            }
            try {
                ImportedWorkspaceFile file = WorkspaceFileImporter.importDocument(
                    applicationContext,
                    sourceUri,
                    sourceGrant,
                    remainingBytes
                );
                imported.add(file);
                remainingBytes -= file.getByteCount();
            } catch (Throwable error) {
                failures++;
                if (firstFailure.isEmpty()) {
                    firstFailure = importFailureReason(error);
                }
            }
        }
        final int failedCount = failures;
        final String failureReason = firstFailure;
        handler.post(new Runnable() {
            @Override
            public void run() {
                completeDocumentImport(threadId, imported, failedCount, failureReason);
            }
        });
    }

    private void completeDocumentImport(
        String threadId,
        List<ImportedWorkspaceFile> imported,
        int failedCount,
        String failureReason
    ) {
        if (destroyed) {
            return;
        }
        importOperationActive = false;
        boolean sameContext = threadId.equals(
            AgentRuntimeService.sessionSnapshot().getActiveThreadId()
        ) && threadId.equals(pendingImportsThreadId);
        if (sameContext) {
            List<ImportedWorkspaceFile> combined =
                new ArrayList<ImportedWorkspaceFile>(pendingImports);
            combined.addAll(imported);
            try {
                List<ImportedWorkspaceFile> validated =
                    WorkspaceImportSelection.copyOf(combined);
                pendingImports.clear();
                pendingImports.addAll(validated);
            } catch (IllegalArgumentException error) {
                failedCount += imported.size();
            }
        }
        if (pendingImports.isEmpty()) {
            pendingImportsThreadId = "";
        }
        refreshLocalComposerState();
        if (!sameContext) {
            Toast.makeText(
                activity,
                R.string.chat_import_context_changed,
                Toast.LENGTH_LONG
            ).show();
        } else if (!imported.isEmpty() && failedCount == 0) {
            Toast.makeText(
                activity,
                activity.getResources().getQuantityString(
                    R.plurals.chat_import_completed,
                    imported.size(),
                    Integer.valueOf(imported.size())
                ),
                Toast.LENGTH_SHORT
            ).show();
        } else if (!imported.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.chat_import_partial,
                    Integer.valueOf(imported.size()),
                    Integer.valueOf(failedCount)
                ),
                Toast.LENGTH_LONG
            ).show();
        } else if (failedCount > 0) {
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.chat_import_failed,
                    failureReason.isEmpty()
                        ? activity.getString(R.string.common_unknown_error)
                        : failureReason
                ),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    void detachPendingImports() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        boolean hadImports = !pendingImports.isEmpty();
        pendingImports.clear();
        pendingImportsThreadId = "";
        refreshLocalComposerState();
        if (hadImports) {
            Toast.makeText(
                activity,
                R.string.chat_import_detached_notice,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    void openConnectorPicker() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        String threadId = snapshot.getActiveThreadId();
        if (!snapshot.isReady() || threadId.isEmpty()) {
            Toast.makeText(
                activity,
                R.string.chat_connector_requires_chat,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        try {
            activity.startActivityForResult(
                ConnectorActivity.createIntent(activity, threadId, pendingConnectors),
                CONNECTOR_REQUEST_CODE
            );
        } catch (Throwable error) {
            Toast.makeText(
                activity,
                R.string.chat_connector_picker_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void acceptConnectorSelection(Intent data) {
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        String returnedThreadId = ConnectorActivity.resultThreadId(data);
        final List<ConnectorSelection> selections;
        try {
            selections = ConnectorActivity.resultSelections(data);
        } catch (RuntimeException error) {
            Toast.makeText(
                activity,
                R.string.chat_connector_unavailable,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (!snapshot.isReady()
            || returnedThreadId.isEmpty()
            || !returnedThreadId.equals(snapshot.getActiveThreadId())
            || (!selections.isEmpty()
                && !AgentRuntimeService.areConnectorsCallable(selections))) {
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
            AgentRuntimeService.refreshConnectorCatalog(true);
            refreshLocalComposerState();
            Toast.makeText(
                activity,
                R.string.chat_connector_context_changed,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        pendingConnectors.clear();
        pendingConnectors.addAll(selections);
        pendingConnectorsThreadId = selections.isEmpty() ? "" : returnedThreadId;
        refreshLocalComposerState();
    }

    void detachPendingConnectors() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        boolean hadConnectors = !pendingConnectors.isEmpty();
        pendingConnectors.clear();
        pendingConnectorsThreadId = "";
        refreshLocalComposerState();
        if (hadConnectors) {
            Toast.makeText(
                activity,
                R.string.chat_connector_detached_notice,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void reconcilePendingConnectors(CodexSessionSnapshot session) {
        if (!pendingConnectors.isEmpty()
            && !session.getActiveThreadId().equals(pendingConnectorsThreadId)) {
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
        }
    }

    private void renderConnectorSelection() {
        if (pendingConnectors.isEmpty()) {
            views.connectorStatusRow.setVisibility(View.GONE);
            views.connectorStatus.setVisibility(View.GONE);
            views.connectorStatus.setText("");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (ConnectorSelection selection : pendingConnectors) {
            if (names.length() != 0) {
                names.append(" · ");
            }
            names.append(selection.getProvider().getDisplayName());
        }
        views.connectorStatus.setText(activity.getResources().getQuantityString(
            R.plurals.chat_connector_selected,
            pendingConnectors.size(),
            names.toString()
        ));
        views.connectorStatusRow.setVisibility(View.VISIBLE);
        views.connectorStatus.setVisibility(View.VISIBLE);
    }

    private static List<CodexAppMention> appMentions(
        List<ConnectorSelection> selections
    ) {
        List<ConnectorSelection> safe = ConnectorSelection.copyOf(selections);
        List<CodexAppMention> mentions = new ArrayList<CodexAppMention>(safe.size());
        for (ConnectorSelection selection : safe) {
            mentions.add(CodexAppMention.create(
                selection.getId(),
                selection.getName()
            ));
        }
        return java.util.Collections.unmodifiableList(mentions);
    }

    private void reconcilePendingImports(CodexSessionSnapshot session) {
        if (!pendingImports.isEmpty()
            && !session.getActiveThreadId().equals(pendingImportsThreadId)) {
            pendingImports.clear();
            pendingImportsThreadId = "";
        }
    }

    private void renderImportSelection() {
        if (importOperationActive) {
            views.importStatusRow.setVisibility(View.VISIBLE);
            views.importStatus.setVisibility(View.VISIBLE);
            views.importStatus.setText(R.string.chat_import_importing);
            return;
        }
        if (sendPreparationActive) {
            views.importStatusRow.setVisibility(View.VISIBLE);
            views.importStatus.setVisibility(View.VISIBLE);
            views.importStatus.setText(R.string.chat_import_verifying);
            return;
        }
        if (pendingImports.isEmpty()) {
            views.importStatusRow.setVisibility(View.GONE);
            views.importStatus.setVisibility(View.GONE);
            views.importStatus.setText("");
            return;
        }
        long totalBytes = WorkspaceImportSelection.totalBytes(pendingImports);
        StringBuilder names = new StringBuilder();
        for (int index = 0; index < pendingImports.size(); index++) {
            if (names.length() != 0) {
                names.append(" · ");
            }
            names.append(pendingImports.get(index).getDisplayName());
        }
        String summary = activity.getResources().getQuantityString(
            R.plurals.chat_import_selected,
            pendingImports.size(),
            Integer.valueOf(pendingImports.size()),
            ChatUiFormatting.readableByteCount(activity, totalBytes)
        );
        views.importStatus.setText(summary + "\n" + names.toString());
        views.importStatusRow.setVisibility(View.VISIBLE);
        views.importStatus.setVisibility(View.VISIBLE);
    }

    private boolean submitImportOperation(Runnable operation) {
        if (destroyed || importOperations.isShutdown()) {
            return false;
        }
        try {
            importOperations.execute(operation);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private String importFailureReason(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return activity.getString(R.string.common_unknown_error);
        }
        return UiText.errorReason(
            activity,
            CrashReportFormatter.redactVisibleText(message, 180)
        );
    }

}
