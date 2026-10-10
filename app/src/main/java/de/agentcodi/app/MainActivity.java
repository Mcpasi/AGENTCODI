package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexThreadSummary;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.UiStartupState;
import de.agentcodi.runtime.AgentRuntimeService;
import de.agentcodi.runtime.CrashDiagnostics;

/** Coordinates the chat lifecycle, runtime refreshes and navigation. */
public final class MainActivity extends Activity implements ChatScreenView.Actions {
    private static final long ACTIVE_REFRESH_INTERVAL_MS = 250L;
    private static final long IDLE_REFRESH_INTERVAL_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UiStartupState startupState = new UiStartupState();
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (!startupState.shouldRefresh()) {
                return;
            }
            try {
                RuntimeSnapshot runtime = AgentRuntimeService.snapshot();
                CodexSessionSnapshot session = AgentRuntimeService.sessionSnapshot();
                render(runtime, session);
                long delay = runtime.getPhase() == RuntimePhase.STARTING
                    || session.isOperationActive()
                    || session.isTurnActive()
                    || session.hasInteractiveRequest()
                    ? ACTIVE_REFRESH_INTERVAL_MS
                    : IDLE_REFRESH_INTERVAL_MS;
                if (startupState.shouldRefresh()) {
                    handler.postDelayed(this, delay);
                }
            } catch (Throwable error) {
                persistCrash("chat-refresh", error);
                showEmergencyStatus(error);
            }
        }
    };

    private UiTheme theme;
    private ChatScreenView views;
    private ChatModelSelectors modelSelectors;
    private ChatTranscriptController transcript;
    private ChatComposerController composer;
    private boolean conversationVisible;
    private String pendingThreadId = "";
    private boolean pendingNewThread;
    private String newThreadBaseline = "";
    private boolean destroyed;
    private long lastSessionRevision = Long.MIN_VALUE;
    private long lastRuntimeGeneration = Long.MIN_VALUE;
    private RuntimePhase lastRuntimePhase;
    private CrashDiagnostics crashDiagnostics;
    private InteractiveRequestDialog interactiveRequestDialog;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            startupState.enter("chat-theme");
            theme = new UiTheme(this);
            interactiveRequestDialog = new InteractiveRequestDialog(this, theme);
            startupState.enter("chat-content");
            views = new ChatScreenView(this, theme, this);
            setContentView(views.buildContent());
            modelSelectors = new ChatModelSelectors(
                this, theme, views.modelSpinner, views.effortSpinner, views
            );
            transcript = new ChatTranscriptController(
                this, theme, handler, views.messageScroll, views.messagesContainer,
                views.newOutputButton
            );
            composer = new ChatComposerController(this, theme, handler, views, new Runnable() {
                @Override
                public void run() {
                    refreshLocalComposerState();
                }
            });
            startupState.complete();
        } catch (Throwable error) {
            String source = startupState.failureSource();
            startupState.fail();
            persistCrash(source, error);
            showEmergencyScreen(source, error);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refreshTask);
        lastRuntimeGeneration = Long.MIN_VALUE;
        lastSessionRevision = Long.MIN_VALUE;
        if (startupState.shouldRefresh()) {
            handler.post(refreshTask);
        }
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTask);
        if (views != null) {
            views.dismissChatDetails();
        }
        if (interactiveRequestDialog != null) {
            interactiveRequestDialog.dismissForLifecycle();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (views != null) {
            views.dismissChatDetails();
        }
        destroyed = true;
        if (composer != null) {
            composer.close();
        }
        if (transcript != null) {
            transcript.close();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (composer != null && composer.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (transcript == null || !transcript.handleActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (conversationVisible) {
            showThreadPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void sendComposerInput() {
        composer.sendComposerInput();
    }

    @Override
    public void openDocumentImportPicker() {
        composer.openDocumentImportPicker();
    }

    @Override
    public void detachPendingImports() {
        composer.detachPendingImports();
    }

    @Override
    public void openConnectorPicker() {
        composer.openConnectorPicker();
    }

    @Override
    public void detachPendingConnectors() {
        composer.detachPendingConnectors();
    }

    private void refreshLocalComposerState() {
        if (destroyed || !startupState.shouldRefresh()) {
            return;
        }
        lastSessionRevision = Long.MIN_VALUE;
        render(AgentRuntimeService.snapshot(), AgentRuntimeService.sessionSnapshot());
    }

    private void render(RuntimeSnapshot runtime, CodexSessionSnapshot session) {
        boolean runtimeChanged = runtime.getGeneration() != lastRuntimeGeneration
            || runtime.getPhase() != lastRuntimePhase;
        boolean sessionChanged = session.getRevision() != lastSessionRevision;
        if (!runtimeChanged && !sessionChanged) {
            return;
        }
        lastRuntimeGeneration = runtime.getGeneration();
        lastRuntimePhase = runtime.getPhase();
        lastSessionRevision = session.getRevision();

        reconcileNavigation(session);
        composer.reconcileSelections(session);
        renderStatus(runtime, session);
        boolean actionReady = session.isReady()
            && !session.isOperationActive()
            && !session.isTurnInterruptPending();
        boolean canChat = actionReady
            && (!session.requiresOpenaiAuth() || session.isSignedIn());
        theme.setEnabled(views.refreshThreadsButton, canChat);
        boolean interactionOpen = session.hasInteractiveRequest();
        boolean threadNavigationReady = canChat
            && !session.isTurnActive()
            && !interactionOpen;
        views.loadMoreThreadsButton.setVisibility(
            session.hasMoreThreads() ? View.VISIBLE : View.GONE
        );
        theme.setEnabled(views.loadMoreThreadsButton, threadNavigationReady);
        styleThreadFilter(
            views.activeThreadsButton,
            !session.isShowingArchivedThreads(),
            threadNavigationReady
        );
        styleThreadFilter(
            views.archivedThreadsButton,
            session.isShowingArchivedThreads(),
            threadNavigationReady
        );
        views.threadSectionLabel.setText(session.isShowingArchivedThreads()
            ? R.string.chat_archived_threads : R.string.chat_active_threads);
        views.newThreadButton.setVisibility(
            session.isShowingArchivedThreads() ? View.GONE : View.VISIBLE
        );
        theme.setEnabled(
            views.newThreadButton,
            threadNavigationReady
        );
        views.threadEmptyView.setText(
            session.isShowingArchivedThreads()
                ? R.string.chat_archived_empty
                : R.string.chat_empty
        );
        composer.render(session, canChat, interactionOpen);
        views.threadAdapter.setData(
            session.getThreads(),
            session.getActiveThreadId(),
            threadNavigationReady
        );
        bindSelectors(session, canChat && !session.isTurnActive() && !interactionOpen);
        transcript.renderTranscript(session.getActiveThreadId(), session.getTranscriptItems());
        if (conversationVisible) {
            views.screenTitle.setText(
                session.getActiveThreadTitle().isEmpty()
                    ? getString(R.string.chat_active)
                    : UiText.threadTitle(this, session.getActiveThreadTitle())
            );
        }
        if (interactiveRequestDialog != null) {
            interactiveRequestDialog.render(session);
        }
    }

    @Override
    public void startNewThread() {
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        pendingNewThread = true;
        pendingThreadId = "";
        newThreadBaseline = snapshot.getActiveThreadId();
        AgentRuntimeService.startNewThread();
    }

    @Override
    public void openThread(CodexThreadSummary thread) {
        if (thread.isArchived()) {
            showThreadActions(thread);
            return;
        }
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        if (thread.getId().equals(snapshot.getActiveThreadId())) {
            showConversationPage(snapshot);
            return;
        }
        pendingNewThread = false;
        pendingThreadId = thread.getId();
        AgentRuntimeService.openThread(thread.getId());
    }

    @Override
    public void showModelDetails() {
        modelSelectors.showDetails();
    }

    private void reconcileNavigation(CodexSessionSnapshot session) {
        if (!pendingThreadId.isEmpty()
            && pendingThreadId.equals(session.getActiveThreadId())
            && !session.isOperationActive()) {
            pendingThreadId = "";
            showConversationPage(session);
        }
        if (pendingNewThread
            && !session.getActiveThreadId().isEmpty()
            && !session.getActiveThreadId().equals(newThreadBaseline)
            && !session.isOperationActive()) {
            pendingNewThread = false;
            newThreadBaseline = "";
            showConversationPage(session);
        }
        if (conversationVisible && session.getActiveThreadId().isEmpty()) {
            showThreadPage();
        }
    }

    private void renderStatus(RuntimeSnapshot runtime, CodexSessionSnapshot session) {
        String message = "";
        boolean settingsAction = false;
        if (runtime.getPhase() != RuntimePhase.READY) {
            message = UiText.runtimeMessage(this, runtime);
            settingsAction = true;
        } else if (!session.isReady()) {
            message = UiText.coreStatus(this, session.getConnectionMessage());
            settingsAction = true;
        } else if (session.requiresOpenaiAuth() && !session.isSignedIn()) {
            message = getString(R.string.chat_sign_in_required);
            settingsAction = true;
        } else if (!session.getErrorMessage().isEmpty()) {
            message = getString(
                R.string.common_error_prefix,
                UiText.errorReason(this, session.getErrorMessage())
            );
            settingsAction = true;
        } else if (session.hasInteractiveRequest()) {
            message = getString(R.string.chat_waiting_for_input);
        } else if (session.isTurnInterruptPending()) {
            message = UiText.coreStatus(this, "Turn wird gestoppt.");
        } else if (session.isOperationActive()) {
            message = UiText.coreStatus(this, session.getOperationMessage());
        } else if (session.isTurnActive()) {
            if (session.getReviewState().isStarting()) {
                message = getString(R.string.chat_review_starting);
            } else if (session.getReviewState().isReviewModeActive()) {
                message = getString(R.string.chat_review_active);
            } else if (session.getReviewState().getPhase()
                == de.agentcodi.core.CodexReviewState.Phase.EXITED) {
                message = getString(R.string.chat_review_finishing);
            } else {
                message = getString(R.string.chat_streaming_response);
            }
        }
        if (session.isReady() && session.isDangerousExecutionMode()) {
            String warning = getString(R.string.chat_compatibility_mode_active);
            message = message.isEmpty() ? warning : warning + "\n" + message;
            settingsAction = true;
        }
        views.statusBanner.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        views.statusText.setText(message);
        boolean alerting = !session.getErrorMessage().isEmpty()
            || session.isDangerousExecutionMode();
        views.statusText.setTextColor(alerting ? theme.danger : theme.primary);
        int indicator = statusIndicatorColor(runtime, session, alerting);
        views.statusIndicator.setBackground(theme.dotShape(indicator));
        views.statusBanner.setBackground(theme.background(
            theme.tintedSurface(indicator, theme.dark ? 0.12f : 0.07f),
            theme.tintedSurface(indicator, 0.35f),
            16
        ));
        views.statusSettingsButton.setVisibility(settingsAction ? View.VISIBLE : View.GONE);
    }

    private int statusIndicatorColor(
        RuntimeSnapshot runtime,
        CodexSessionSnapshot session,
        boolean alerting
    ) {
        if (alerting || runtime.getPhase() == RuntimePhase.FAILED) {
            return theme.danger;
        }
        if (runtime.getPhase() != RuntimePhase.READY || !session.isReady()) {
            return theme.warning;
        }
        if (session.hasInteractiveRequest()) {
            return theme.warning;
        }
        return session.isTurnActive() || session.isOperationActive()
            ? theme.info
            : theme.accent;
    }

    private void bindSelectors(CodexSessionSnapshot session, boolean enabled) {
        modelSelectors.bindSelectors(session, enabled);
    }

    @Override
    public void showThreadActions(final CodexThreadSummary thread) {
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        if (!canManageThread(snapshot, thread)) {
            return;
        }
        final CharSequence[] actions = thread.isArchived()
            ? new CharSequence[] {
                getString(R.string.chat_unarchive),
                getString(R.string.chat_delete)
            }
            : new CharSequence[] {
                getString(R.string.chat_archive),
                getString(R.string.chat_delete)
            };
        new AlertDialog.Builder(this)
            .setTitle(UiText.threadTitle(this, thread.getTitle()))
            .setItems(actions, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    CodexSessionSnapshot current = AgentRuntimeService.sessionSnapshot();
                    if (!canManageThread(current, thread)) {
                        return;
                    }
                    if (which == 0) {
                        if (thread.isArchived()) {
                            AgentRuntimeService.unarchiveThread(thread.getId());
                        } else {
                            AgentRuntimeService.archiveThread(thread.getId());
                        }
                    } else if (which == 1) {
                        confirmDeleteThread(thread);
                    }
                }
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }

    private void confirmDeleteThread(final CodexThreadSummary thread) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.chat_delete_title)
            .setMessage(getString(
                R.string.chat_delete_message,
                UiText.threadTitle(this, thread.getTitle())
            ))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.chat_delete, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    CodexSessionSnapshot current = AgentRuntimeService.sessionSnapshot();
                    if (canManageThread(current, thread)) {
                        AgentRuntimeService.deleteThread(thread.getId());
                    }
                }
            })
            .show();
    }

    private static boolean canManageThread(
        CodexSessionSnapshot snapshot,
        CodexThreadSummary thread
    ) {
        if (!snapshot.isReady()
            || (snapshot.requiresOpenaiAuth() && !snapshot.isSignedIn())
            || snapshot.isOperationActive()
            || snapshot.isTurnActive()
            || snapshot.hasInteractiveRequest()
            || snapshot.isShowingArchivedThreads() != thread.isArchived()) {
            return false;
        }
        for (CodexThreadSummary current : snapshot.getThreads()) {
            if (current.getId().equals(thread.getId())
                && current.isArchived() == thread.isArchived()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void showThreadPage() {
        conversationVisible = false;
        pendingThreadId = "";
        pendingNewThread = false;
        views.threadPage.setVisibility(View.VISIBLE);
        views.conversationPage.setVisibility(View.GONE);
        views.backToThreadsButton.setVisibility(View.GONE);
        views.screenTitle.setText(R.string.chat_title);
    }

    private void showConversationPage(CodexSessionSnapshot session) {
        if (session.getActiveThreadId().isEmpty()) {
            return;
        }
        conversationVisible = true;
        views.threadPage.setVisibility(View.GONE);
        views.conversationPage.setVisibility(View.VISIBLE);
        views.backToThreadsButton.setVisibility(View.VISIBLE);
        views.screenTitle.setText(
            session.getActiveThreadTitle().isEmpty()
                ? getString(R.string.chat_active)
                : UiText.threadTitle(this, session.getActiveThreadTitle())
        );
    }

    @Override
    public void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public void openTerminal() {
        startActivity(new Intent(this, TerminalActivity.class));
    }

    @Override
    public void openFiles() {
        startActivity(new Intent(this, WorkspaceBrowserActivity.class));
    }

    private void showEmergencyStatus(Throwable error) {
        if (views == null || views.statusBanner == null || views.statusText == null) {
            return;
        }
        views.statusBanner.setVisibility(View.VISIBLE);
        views.statusText.setText(
            getString(R.string.chat_update_failed, error.getClass().getSimpleName())
        );
        views.statusText.setTextColor(theme == null ? Color.RED : theme.danger);
    }

    private void persistCrash(String source, Throwable error) {
        try {
            if (crashDiagnostics == null) {
                crashDiagnostics = CrashDiagnostics.open(getFilesDir());
            }
            crashDiagnostics.record(source, Thread.currentThread(), error);
        } catch (Throwable ignored) {
            // A diagnostics failure must not hide the original UI failure.
        }
    }

    private void showEmergencyScreen(String source, Throwable error) {
        TextView fallback = new TextView(this);
        fallback.setPadding(32, 48, 32, 48);
        fallback.setTextColor(0xFF111827);
        fallback.setBackgroundColor(0xFFF5F7FB);
        fallback.setTextSize(14);
        fallback.setTextIsSelectable(true);
        fallback.setText(
            getString(R.string.chat_initialization_failed) + "\n\n"
                + CrashReportFormatter.format(source, Thread.currentThread(), error)
        );
        setContentView(fallback);
    }

    private void styleThreadFilter(ImageButton button, boolean selected, boolean enabled) {
        button.setSelected(selected);
        button.setEnabled(enabled && !selected);
        button.setAlpha(enabled ? 1.0f : 0.45f);
        button.setColorFilter(selected ? theme.accent : theme.secondary);
        button.setBackground(theme.touchBackground(
            selected ? theme.tintedSurface(theme.accent, 0.12f) : Color.TRANSPARENT,
            Color.TRANSPARENT,
            14
        ));
    }
}
