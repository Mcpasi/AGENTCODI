package de.agentcodi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CodexSessionSnapshot {
    private final long revision;
    private final boolean ready;
    private final String connectionMessage;
    private final String executionModeId;
    private final String permissionProfileId;
    private final boolean dangerousExecutionMode;
    private final boolean compatibilityApprovalsEnabled;
    private final boolean requiresOpenaiAuth;
    private final String authMode;
    private final String accountEmail;
    private final String planType;
    private final CodexRateLimitsSnapshot rateLimits;
    private final boolean loginPending;
    private final String loginUrl;
    private final boolean operationActive;
    private final boolean turnInterruptPending;
    private final String operationMessage;
    private final List<CodexModelOption> models;
    private final String selectedModelId;
    private final String selectedReasoningEffort;
    private final List<CodexThreadSummary> threads;
    private final boolean showingArchivedThreads;
    private final String activeThreadId;
    private final String activeThreadTitle;
    private final List<CodexTranscriptItem> transcriptItems;
    private final List<ChatMessage> messages;
    private final boolean turnActive;
    private final String activeTurnId;
    private final CodexReviewState reviewState;
    private final List<CodexInteractiveRequest> interactiveRequests;
    private final String errorMessage;

    public CodexSessionSnapshot(
        long revision,
        boolean ready,
        String connectionMessage,
        String executionModeId,
        String permissionProfileId,
        boolean dangerousExecutionMode,
        boolean compatibilityApprovalsEnabled,
        boolean requiresOpenaiAuth,
        String authMode,
        String accountEmail,
        String planType,
        CodexRateLimitsSnapshot rateLimits,
        boolean loginPending,
        String loginUrl,
        boolean operationActive,
        boolean turnInterruptPending,
        String operationMessage,
        List<CodexModelOption> models,
        String selectedModelId,
        String selectedReasoningEffort,
        List<CodexThreadSummary> threads,
        boolean showingArchivedThreads,
        String activeThreadId,
        String activeThreadTitle,
        List<CodexTranscriptItem> transcriptItems,
        boolean turnActive,
        String activeTurnId,
        CodexReviewState reviewState,
        List<CodexInteractiveRequest> interactiveRequests,
        String errorMessage
    ) {
        this.revision = revision;
        this.ready = ready;
        this.connectionMessage = nonNull(connectionMessage);
        this.executionModeId = nonNull(executionModeId);
        this.permissionProfileId = nonNull(permissionProfileId);
        this.dangerousExecutionMode = dangerousExecutionMode;
        this.compatibilityApprovalsEnabled = compatibilityApprovalsEnabled;
        this.requiresOpenaiAuth = requiresOpenaiAuth;
        this.authMode = nonNull(authMode);
        this.accountEmail = nonNull(accountEmail);
        this.planType = nonNull(planType);
        this.rateLimits = rateLimits == null
            ? CodexRateLimitsSnapshot.unavailable()
            : rateLimits;
        this.loginPending = loginPending;
        this.loginUrl = nonNull(loginUrl);
        this.operationActive = operationActive;
        this.turnInterruptPending = turnInterruptPending;
        this.operationMessage = nonNull(operationMessage);
        this.models = immutableModelCopy(models);
        this.selectedModelId = nonNull(selectedModelId);
        this.selectedReasoningEffort = nonNull(selectedReasoningEffort);
        this.threads = immutableCopy(threads);
        this.showingArchivedThreads = showingArchivedThreads;
        this.activeThreadId = nonNull(activeThreadId);
        this.activeThreadTitle = nonNull(activeThreadTitle);
        this.transcriptItems = immutableTranscriptCopy(transcriptItems);
        this.messages = messagesFromTranscript(this.transcriptItems);
        this.turnActive = turnActive;
        this.activeTurnId = nonNull(activeTurnId);
        this.reviewState = reviewState == null
            ? CodexReviewState.idle()
            : reviewState;
        this.interactiveRequests = immutableInteractiveRequestCopy(interactiveRequests);
        this.errorMessage = nonNull(errorMessage);
    }

    public static CodexSessionSnapshot stopped() {
        return new CodexSessionSnapshot(
            0L,
            false,
            "Codex App-Server ist nicht gestartet.",
            CodexExecutionMode.PROTECTED_ID,
            CodexExecutionMode.PROTECTED_PERMISSION_PROFILE_ID,
            false,
            false,
            true,
            "",
            "",
            "",
            CodexRateLimitsSnapshot.unavailable(),
            false,
            "",
            false,
            false,
            "",
            Collections.<CodexModelOption>emptyList(),
            "",
            "",
            Collections.<CodexThreadSummary>emptyList(),
            false,
            "",
            "",
            Collections.<CodexTranscriptItem>emptyList(),
            false,
            "",
            CodexReviewState.idle(),
            Collections.<CodexInteractiveRequest>emptyList(),
            ""
        );
    }

    public long getRevision() {
        return revision;
    }

    public boolean isReady() {
        return ready;
    }

    public String getConnectionMessage() {
        return connectionMessage;
    }

    public String getExecutionModeId() {
        return executionModeId;
    }

    public String getPermissionProfileId() {
        return permissionProfileId;
    }

    public boolean isDangerousExecutionMode() {
        return dangerousExecutionMode;
    }

    public boolean isCompatibilityApprovalsEnabled() {
        return compatibilityApprovalsEnabled;
    }

    public boolean requiresOpenaiAuth() {
        return requiresOpenaiAuth;
    }

    public boolean isSignedIn() {
        return !authMode.isEmpty();
    }

    public String getAuthMode() {
        return authMode;
    }

    public String getAccountEmail() {
        return accountEmail;
    }

    public String getPlanType() {
        return planType;
    }

    public CodexRateLimitsSnapshot getRateLimits() {
        return rateLimits;
    }

    public boolean isLoginPending() {
        return loginPending;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public boolean isOperationActive() {
        return operationActive;
    }

    public boolean isTurnInterruptPending() {
        return turnInterruptPending;
    }

    public String getOperationMessage() {
        return operationMessage;
    }

    public List<CodexModelOption> getModels() {
        return models;
    }

    public String getSelectedModelId() {
        return selectedModelId;
    }

    public String getSelectedReasoningEffort() {
        return selectedReasoningEffort;
    }

    public List<CodexThreadSummary> getThreads() {
        return threads;
    }

    public boolean isShowingArchivedThreads() {
        return showingArchivedThreads;
    }

    public String getActiveThreadId() {
        return activeThreadId;
    }

    public String getActiveThreadTitle() {
        return activeThreadTitle;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public List<CodexTranscriptItem> getTranscriptItems() {
        return transcriptItems;
    }

    public boolean isTurnActive() {
        return turnActive;
    }

    public String getActiveTurnId() {
        return activeTurnId;
    }

    public CodexReviewState getReviewState() {
        return reviewState;
    }

    public List<CodexInteractiveRequest> getInteractiveRequests() {
        return interactiveRequests;
    }

    public boolean hasInteractiveRequest() {
        return !interactiveRequests.isEmpty();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    private static List<CodexThreadSummary> immutableCopy(List<CodexThreadSummary> values) {
        return Collections.unmodifiableList(
            new ArrayList<CodexThreadSummary>(
                values == null ? Collections.<CodexThreadSummary>emptyList() : values
            )
        );
    }

    private static List<CodexModelOption> immutableModelCopy(List<CodexModelOption> values) {
        return Collections.unmodifiableList(
            new ArrayList<CodexModelOption>(
                values == null ? Collections.<CodexModelOption>emptyList() : values
            )
        );
    }

    private static List<CodexTranscriptItem> immutableTranscriptCopy(
        List<CodexTranscriptItem> values
    ) {
        return Collections.unmodifiableList(
            new ArrayList<CodexTranscriptItem>(
                values == null ? Collections.<CodexTranscriptItem>emptyList() : values
            )
        );
    }

    private static List<ChatMessage> messagesFromTranscript(
        List<CodexTranscriptItem> values
    ) {
        List<ChatMessage> result = new ArrayList<ChatMessage>();
        for (CodexTranscriptItem value : values) {
            if (value.isMessage()) {
                result.add(value.getMessage());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<CodexInteractiveRequest> immutableInteractiveRequestCopy(
        List<CodexInteractiveRequest> values
    ) {
        return Collections.unmodifiableList(
            new ArrayList<CodexInteractiveRequest>(
                values == null ? Collections.<CodexInteractiveRequest>emptyList() : values
            )
        );
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
