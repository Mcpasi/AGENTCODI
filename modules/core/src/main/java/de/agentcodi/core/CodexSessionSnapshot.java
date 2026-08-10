package de.agentcodi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CodexSessionSnapshot {
    private final long revision;
    private final boolean ready;
    private final String connectionMessage;
    private final boolean requiresOpenaiAuth;
    private final String authMode;
    private final String accountEmail;
    private final String planType;
    private final boolean loginPending;
    private final String loginUrl;
    private final boolean operationActive;
    private final String operationMessage;
    private final List<CodexModelOption> models;
    private final String selectedModelId;
    private final String selectedReasoningEffort;
    private final List<CodexThreadSummary> threads;
    private final String activeThreadId;
    private final String activeThreadTitle;
    private final List<ChatMessage> messages;
    private final boolean turnActive;
    private final String activeTurnId;
    private final List<CodexInteractiveRequest> interactiveRequests;
    private final String errorMessage;

    public CodexSessionSnapshot(
        long revision,
        boolean ready,
        String connectionMessage,
        boolean requiresOpenaiAuth,
        String authMode,
        String accountEmail,
        String planType,
        boolean loginPending,
        String loginUrl,
        boolean operationActive,
        String operationMessage,
        List<CodexModelOption> models,
        String selectedModelId,
        String selectedReasoningEffort,
        List<CodexThreadSummary> threads,
        String activeThreadId,
        String activeThreadTitle,
        List<ChatMessage> messages,
        boolean turnActive,
        String activeTurnId,
        List<CodexInteractiveRequest> interactiveRequests,
        String errorMessage
    ) {
        this.revision = revision;
        this.ready = ready;
        this.connectionMessage = nonNull(connectionMessage);
        this.requiresOpenaiAuth = requiresOpenaiAuth;
        this.authMode = nonNull(authMode);
        this.accountEmail = nonNull(accountEmail);
        this.planType = nonNull(planType);
        this.loginPending = loginPending;
        this.loginUrl = nonNull(loginUrl);
        this.operationActive = operationActive;
        this.operationMessage = nonNull(operationMessage);
        this.models = immutableModelCopy(models);
        this.selectedModelId = nonNull(selectedModelId);
        this.selectedReasoningEffort = nonNull(selectedReasoningEffort);
        this.threads = immutableCopy(threads);
        this.activeThreadId = nonNull(activeThreadId);
        this.activeThreadTitle = nonNull(activeThreadTitle);
        this.messages = immutableMessageCopy(messages);
        this.turnActive = turnActive;
        this.activeTurnId = nonNull(activeTurnId);
        this.interactiveRequests = immutableInteractiveRequestCopy(interactiveRequests);
        this.errorMessage = nonNull(errorMessage);
    }

    public static CodexSessionSnapshot stopped() {
        return new CodexSessionSnapshot(
            0L,
            false,
            "Codex App-Server ist nicht gestartet.",
            true,
            "",
            "",
            "",
            false,
            "",
            false,
            "",
            Collections.<CodexModelOption>emptyList(),
            "",
            "",
            Collections.<CodexThreadSummary>emptyList(),
            "",
            "",
            Collections.<ChatMessage>emptyList(),
            false,
            "",
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

    public boolean isLoginPending() {
        return loginPending;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public boolean isOperationActive() {
        return operationActive;
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

    public String getActiveThreadId() {
        return activeThreadId;
    }

    public String getActiveThreadTitle() {
        return activeThreadTitle;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public boolean isTurnActive() {
        return turnActive;
    }

    public String getActiveTurnId() {
        return activeTurnId;
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

    private static List<ChatMessage> immutableMessageCopy(List<ChatMessage> values) {
        return Collections.unmodifiableList(
            new ArrayList<ChatMessage>(
                values == null ? Collections.<ChatMessage>emptyList() : values
            )
        );
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
