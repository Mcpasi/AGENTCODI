package de.agentcodi.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CodexSessionController
    implements CodexAppServerClient.Listener, AutoCloseable {
    private static final long NORMAL_TIMEOUT_MS = 30_000L;
    private static final long INITIALIZE_TIMEOUT_MS = 20_000L;
    private static final int MAX_THREADS = 200;
    private static final int MAX_THREAD_PAGES = 4;
    private static final int MAX_MODELS = 50;
    private static final int MAX_REASONING_OPTIONS = 8;
    private static final int MAX_MESSAGES = 200;
    private static final int MAX_MESSAGE_CHARACTERS = 256 * 1024;
    private static final int MAX_HISTORY_CHARACTERS = 1024 * 1024;
    private static final int MAX_PROMPT_CHARACTERS = 32 * 1024;
    private static final int MAX_ERROR_CHARACTERS = 600;
    private static final int MAX_INTERACTIVE_REQUESTS = 8;
    private static final int MAX_USER_INPUT_QUESTIONS = 3;
    private static final int MAX_USER_INPUT_OPTIONS = 8;
    private static final int MAX_USER_INPUT_ANSWER_CHARACTERS = 16 * 1024;
    private static final int MAX_FILE_CHANGE_SUMMARIES = 24;
    private static final int MAX_FILE_CHANGE_DIFF_CHARACTERS = 12 * 1024;
    private static final int MAX_FILE_CHANGE_TOTAL_CHARACTERS = 48 * 1024;
    private static final int MAX_POLICY_AMENDMENT_PARTS = 32;
    private static final long MAX_INTERACTIVE_WAIT_MS = 10L * 60L * 1000L;
    private static final String WORKSPACE_PERMISSION_PROFILE = "agentcodi-workspace";
    private static final String OPENAI_HTTP_MODEL_PROVIDER = "agentcodi-openai-http";
    private static final String COMMAND_APPROVAL_METHOD =
        "item/commandExecution/requestApproval";
    private static final String FILE_CHANGE_APPROVAL_METHOD =
        "item/fileChange/requestApproval";
    private static final String FILE_CHANGE_PATCH_UPDATED_METHOD =
        "item/fileChange/patchUpdated";
    private static final String USER_INPUT_METHOD = "item/tool/requestUserInput";

    private final CodexAppServerClient client;
    private final String workspacePath;
    private final ExecutorService operations = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService interactiveResponses =
        Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong localMessageIds = new AtomicLong(1L);
    private final List<CodexModelOption> models = new ArrayList<CodexModelOption>();
    private final List<CodexThreadSummary> threads = new ArrayList<CodexThreadSummary>();
    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();
    private final List<CodexInteractiveRequest> interactiveRequests =
        new ArrayList<CodexInteractiveRequest>();
    private final Map<String, List<CodexFileChangeSummary>> pendingFileChanges =
        new HashMap<String, List<CodexFileChangeSummary>>();

    private long revision;
    private boolean ready;
    private boolean closed;
    private String connectionMessage = "Codex App-Server startet.";
    private boolean requiresOpenaiAuth = true;
    private String authMode = "";
    private String accountEmail = "";
    private String planType = "";
    private boolean loginPending;
    private String loginUrl = "";
    private String loginId = "";
    private boolean operationActive;
    private String operationMessage = "";
    private String selectedModelId = "";
    private String selectedReasoningEffort = "";
    private String activeThreadId = "";
    private String activeThreadTitle = "";
    private boolean turnActive;
    private String activeTurnId = "";
    private String lastCompletedTurnId = "";
    private String errorMessage = "";
    private CodexSessionSnapshot snapshot;

    public CodexSessionController(CodexRpcTransport transport, String workspacePath) {
        if (workspacePath == null || workspacePath.trim().isEmpty()
            || !workspacePath.startsWith("/")) {
            throw new IllegalArgumentException("Workspace path must be absolute");
        }
        this.workspacePath = workspacePath;
        client = new CodexAppServerClient(transport, this);
        synchronized (this) {
            publishLocked();
        }
    }

    public void start() throws Exception {
        synchronized (this) {
            if (closed || ready) {
                throw new IllegalStateException("Codex session controller cannot start");
            }
            connectionMessage = "Codex App-Server wird initialisiert.";
            publishLocked();
        }
        try {
            client.start();
            Map<String, Object> clientInfo = JsonCodec.object(
                "name", "agentcodi_android",
                "title", BuildIdentity.APP_NAME,
                "version", BuildIdentity.VERSION_NAME
            );
            client.initialize(
                JsonCodec.object(
                    "clientInfo", clientInfo,
                    "capabilities", JsonCodec.object("experimentalApi", Boolean.TRUE)
                ),
                INITIALIZE_TIMEOUT_MS
            );
            synchronized (this) {
                ready = true;
                connectionMessage = "Codex App-Server ist bereit.";
                publishLocked();
            }
            verifyWorkspacePermissionProfileInternal();
            refreshModelsInternal();
            readAccountInternal();
            refreshThreadsInternal();
        } catch (Throwable error) {
            synchronized (this) {
                ready = false;
                connectionMessage = "Codex App-Server konnte nicht initialisiert werden.";
                errorMessage = safeError(error);
                publishLocked();
            }
            client.close();
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            throw new IllegalStateException("Codex app-server startup failed", error);
        }
    }

    public synchronized CodexSessionSnapshot snapshot() {
        return snapshot;
    }

    public void refreshAccountAndThreads() {
        submit("Konto und Chats werden aktualisiert.", new Operation() {
            @Override
            public void run() throws Exception {
                readAccountInternal();
                refreshModelsInternal();
                refreshThreadsInternal();
            }
        });
    }

    public synchronized void selectModel(String modelId) {
        if (closed || !ready) {
            setUserError("Codex App-Server ist nicht bereit.");
            return;
        }
        if (turnActive) {
            setUserError("Das Modell kann erst nach dem laufenden Turn gewechselt werden.");
            return;
        }
        CodexModelOption model = findModelLocked(modelId);
        if (model == null) {
            setUserError("Das gewählte Modell wird vom App-Server nicht angeboten.");
            return;
        }
        if (model.getId().equals(selectedModelId)) {
            return;
        }
        selectedModelId = model.getId();
        selectedReasoningEffort = model.getDefaultReasoningEffort();
        errorMessage = "";
        operationMessage = "Modell für den nächsten Turn: " + model.getDisplayName();
        publishLocked();
    }

    public synchronized void selectReasoningEffort(String effort) {
        if (closed || !ready) {
            setUserError("Codex App-Server ist nicht bereit.");
            return;
        }
        if (turnActive) {
            setUserError("Die Denkstufe kann erst nach dem laufenden Turn gewechselt werden.");
            return;
        }
        CodexModelOption model = findModelLocked(selectedModelId);
        if (model == null || !model.supportsReasoningEffort(effort)) {
            setUserError("Diese Denkstufe wird vom gewählten Modell nicht unterstützt.");
            return;
        }
        if (effort.equals(selectedReasoningEffort)) {
            return;
        }
        selectedReasoningEffort = effort;
        errorMessage = "";
        operationMessage = "Denkstufe für den nächsten Turn: " + effort;
        publishLocked();
    }

    public void startChatGptLogin() {
        submit("ChatGPT-Anmeldung wird vorbereitet.", new Operation() {
            @Override
            public void run() throws Exception {
                synchronized (CodexSessionController.this) {
                    loginPending = true;
                    loginUrl = "";
                    loginId = "";
                    publishLocked();
                }
                Map<String, Object> result = client.request(
                    "account/login/start",
                    JsonCodec.object("type", "chatgpt"),
                    NORMAL_TIMEOUT_MS
                );
                String candidateUrl = JsonCodec.requireString(
                    result.get("authUrl"),
                    "account login authUrl"
                );
                if (!isTrustedLoginUrl(candidateUrl)) {
                    throw new IllegalArgumentException("App-server returned an untrusted login URL");
                }
                synchronized (CodexSessionController.this) {
                    loginId = JsonCodec.requireString(result.get("loginId"), "loginId");
                    loginUrl = candidateUrl;
                    loginPending = true;
                    operationMessage = "Anmeldeseite im Browser öffnen.";
                    publishLocked();
                }
            }
        });
    }

    public void startApiKeyLogin(final char[] apiKey) {
        if (apiKey == null || apiKey.length < 8 || apiKey.length > 16 * 1024) {
            wipe(apiKey);
            setUserError("Der API-Schlüssel hat eine ungültige Länge.");
            return;
        }
        boolean accepted = submit("API-Schlüssel wird an Codex übergeben.", new Operation() {
            @Override
            public void run() throws Exception {
                try {
                    synchronized (CodexSessionController.this) {
                        loginPending = true;
                        loginUrl = "";
                        loginId = "";
                        publishLocked();
                    }
                    String transientKey = new String(apiKey);
                    client.request(
                        "account/login/start",
                        JsonCodec.object("type", "apiKey", "apiKey", transientKey),
                        NORMAL_TIMEOUT_MS
                    );
                    readAccountInternal();
                    refreshModelsInternal();
                    synchronized (CodexSessionController.this) {
                        loginPending = false;
                        operationMessage = "API-Schlüssel wurde im kanonischen Codex-Speicher abgelegt.";
                        publishLocked();
                    }
                } finally {
                    wipe(apiKey);
                }
            }
        });
        if (!accepted) {
            wipe(apiKey);
        }
    }

    public void logout() {
        submit("Abmeldung läuft.", new Operation() {
            @Override
            public void run() throws Exception {
                client.request("account/logout", null, NORMAL_TIMEOUT_MS);
                synchronized (CodexSessionController.this) {
                    clearAccountLocked();
                    operationMessage = "Abgemeldet.";
                    publishLocked();
                }
                readAccountInternal();
            }
        });
    }

    public void refreshThreads() {
        submit("Chats werden geladen.", new Operation() {
            @Override
            public void run() throws Exception {
                refreshThreadsInternal();
            }
        });
    }

    public void startNewThread() {
        submit("Neuer Chat wird erstellt.", new Operation() {
            @Override
            public void run() throws Exception {
                startNewThreadInternal();
            }
        });
    }

    public void openThread(final String threadId) {
        if (!isValidIdentifier(threadId)) {
            setUserError("Ungültige Chat-ID.");
            return;
        }
        submit("Chat wird geöffnet.", new Operation() {
            @Override
            public void run() throws Exception {
                synchronized (CodexSessionController.this) {
                    requireNoActiveTurnOrRequestLocked();
                }
                Map<String, Object> result = client.request(
                    "thread/resume",
                    JsonCodec.object(
                        "threadId", threadId,
                        "modelProvider", OPENAI_HTTP_MODEL_PROVIDER,
                        "cwd", workspacePath,
                        "runtimeWorkspaceRoots", JsonCodec.array(workspacePath),
                        "approvalPolicy", "on-request",
                        "permissions", WORKSPACE_PERMISSION_PROFILE
                    ),
                    NORMAL_TIMEOUT_MS
                );
                requireWorkspacePermissionProfile(result, "thread/resume");
                Map<String, Object> thread = JsonCodec.requireObject(
                    result.get("thread"),
                    "thread/resume thread"
                );
                String returnedId = JsonCodec.requireString(thread.get("id"), "thread id");
                if (!threadId.equals(returnedId)) {
                    throw new IllegalArgumentException("App-server returned a different thread id");
                }
                requireHttpModelProvider(thread, "thread/resume");
                List<ChatMessage> history = parseHistory(thread);
                synchronized (CodexSessionController.this) {
                    activeThreadId = returnedId;
                    activeThreadTitle = titleForThread(thread);
                    updateSelectionFromThreadResponseLocked(result);
                    messages.clear();
                    messages.addAll(history);
                    turnActive = false;
                    activeTurnId = "";
                    lastCompletedTurnId = "";
                    interactiveRequests.clear();
                    pendingFileChanges.clear();
                    operationMessage = "Chat ist geöffnet.";
                    publishLocked();
                }
            }
        });
    }

    public void sendMessage(final String input) {
        final String prompt = input == null ? "" : input.trim();
        if (prompt.isEmpty() || prompt.length() > MAX_PROMPT_CHARACTERS) {
            setUserError("Nachrichten müssen 1 bis 32768 Zeichen enthalten.");
            return;
        }
        submit("Nachricht wird gesendet.", new Operation() {
            @Override
            public void run() throws Exception {
                String threadId;
                String requestModel;
                String requestEffort;
                synchronized (CodexSessionController.this) {
                    if (requiresOpenaiAuth && authMode.isEmpty()) {
                        throw new IllegalStateException("Bitte zuerst anmelden.");
                    }
                    if (turnActive) {
                        throw new IllegalStateException("Der aktuelle Turn läuft noch.");
                    }
                    CodexModelOption model = findModelLocked(selectedModelId);
                    if (model == null
                        || !model.supportsReasoningEffort(selectedReasoningEffort)) {
                        throw new IllegalStateException(
                            "Bitte ein angebotenes Modell und eine Denkstufe wählen."
                        );
                    }
                    requestModel = model.getModel();
                    requestEffort = selectedReasoningEffort;
                }
                if (snapshot().getActiveThreadId().isEmpty()) {
                    threadId = startNewThreadInternal();
                } else {
                    threadId = snapshot().getActiveThreadId();
                }

                String localId = "local-user-" + localMessageIds.getAndIncrement();
                synchronized (CodexSessionController.this) {
                    addBoundedMessageLocked(new ChatMessage(
                        localId,
                        ChatMessage.Role.USER,
                        prompt,
                        false
                    ));
                    turnActive = true;
                    activeTurnId = "";
                    publishLocked();
                }

                Map<String, Object> params = JsonCodec.object(
                    "threadId", threadId,
                    "input", JsonCodec.array(JsonCodec.object("type", "text", "text", prompt)),
                    "cwd", workspacePath,
                    "runtimeWorkspaceRoots", JsonCodec.array(workspacePath),
                    "approvalPolicy", "on-request",
                    "permissions", WORKSPACE_PERMISSION_PROFILE,
                    "model", requestModel,
                    "effort", requestEffort
                );
                try {
                    Map<String, Object> result = client.request(
                        "turn/start",
                        params,
                        NORMAL_TIMEOUT_MS
                    );
                    Map<String, Object> turn = JsonCodec.requireObject(
                        result.get("turn"),
                        "turn/start turn"
                    );
                    synchronized (CodexSessionController.this) {
                        String returnedTurnId = JsonCodec.requireString(turn.get("id"), "turn id");
                        activeTurnId = returnedTurnId;
                        turnActive = !returnedTurnId.equals(lastCompletedTurnId)
                            && !"completed".equals(JsonCodec.optionalString(turn.get("status")));
                        if (!turnActive) {
                            activeTurnId = "";
                        }
                        operationMessage = "Codex arbeitet.";
                        publishLocked();
                    }
                } catch (Throwable error) {
                    synchronized (CodexSessionController.this) {
                        turnActive = false;
                        activeTurnId = "";
                        addSystemMessageLocked(safeError(error));
                        publishLocked();
                    }
                    if (error instanceof Exception) {
                        throw (Exception) error;
                    }
                    throw new IllegalStateException("Turn start failed", error);
                }
            }
        });
    }

    public void interruptTurn() {
        submit("Turn wird gestoppt.", new Operation() {
            @Override
            public void run() throws Exception {
                String threadId;
                String turnId;
                synchronized (CodexSessionController.this) {
                    threadId = activeThreadId;
                    turnId = activeTurnId;
                }
                if (threadId.isEmpty() || turnId.isEmpty()) {
                    throw new IllegalStateException("Kein stoppbarer Turn ist aktiv.");
                }
                client.request(
                    "turn/interrupt",
                    JsonCodec.object("threadId", threadId, "turnId", turnId),
                    NORMAL_TIMEOUT_MS
                );
            }
        });
    }

    public void resolveApproval(
        long requestId,
        CodexApprovalDecision decision,
        int amendmentIndex
    ) {
        if (decision == null) {
            setUserError("Eine Freigabeentscheidung fehlt.");
            return;
        }
        Map<String, Object> response;
        synchronized (this) {
            int index = findInteractiveRequestLocked(requestId);
            if (index < 0) {
                setUserError("Diese Freigabe ist nicht mehr aktiv.");
                return;
            }
            CodexInteractiveRequest request = interactiveRequests.get(index);
            if (request.getKind() == CodexInteractiveRequest.Kind.USER_INPUT) {
                setUserError("Diese Anfrage erwartet eine Texteingabe.");
                return;
            }
            try {
                response = approvalResponse(request, decision, amendmentIndex);
            } catch (IllegalArgumentException error) {
                setUserError(error.getMessage());
                return;
            }
            interactiveRequests.remove(index);
            errorMessage = "";
            operationMessage = "Freigabeentscheidung wird übermittelt.";
            publishLocked();
        }
        submitInteractiveResponse(requestId, response);
    }

    public void answerUserInput(long requestId, Map<String, char[]> suppliedAnswers) {
        Map<String, Object> response = null;
        String validationError = "";
        try {
            synchronized (this) {
                int index = findInteractiveRequestLocked(requestId);
                if (index < 0) {
                    validationError = "Diese Eingabeanfrage ist nicht mehr aktiv.";
                } else {
                    CodexInteractiveRequest request = interactiveRequests.get(index);
                    if (request.getKind() != CodexInteractiveRequest.Kind.USER_INPUT) {
                        validationError = "Diese Anfrage erwartet eine Freigabeentscheidung.";
                    } else {
                        try {
                            response = userInputResponse(request, suppliedAnswers);
                        } catch (IllegalArgumentException error) {
                            validationError = error.getMessage();
                        }
                        if (response != null) {
                            interactiveRequests.remove(index);
                            errorMessage = "";
                            operationMessage = "Antwort wird an Codex übermittelt.";
                            publishLocked();
                        }
                    }
                }
                if (!validationError.isEmpty()) {
                    errorMessage = validationError;
                    publishLocked();
                }
            }
        } finally {
            wipeAnswers(suppliedAnswers);
        }
        if (response != null) {
            submitInteractiveResponse(requestId, response);
        }
    }

    public void dismissUserInput(long requestId) {
        Map<String, Object> response = null;
        synchronized (this) {
            int index = findInteractiveRequestLocked(requestId);
            if (index < 0) {
                setUserError("Diese Eingabeanfrage ist nicht mehr aktiv.");
                return;
            }
            CodexInteractiveRequest request = interactiveRequests.get(index);
            if (request.getKind() != CodexInteractiveRequest.Kind.USER_INPUT) {
                setUserError("Diese Anfrage erwartet eine Freigabeentscheidung.");
                return;
            }
            interactiveRequests.remove(index);
            response = emptyUserInputResponse();
            operationMessage = "Eingabeanfrage wird ohne Antwort geschlossen.";
            publishLocked();
        }
        submitInteractiveResponse(requestId, response);
    }

    @Override
    public boolean onServerRequest(
        final long requestId,
        String method,
        Map<String, Object> params
    ) {
        if (!COMMAND_APPROVAL_METHOD.equals(method)
            && !FILE_CHANGE_APPROVAL_METHOD.equals(method)
            && !USER_INPUT_METHOD.equals(method)) {
            return false;
        }
        final CodexInteractiveRequest request;
        try {
            request = parseInteractiveRequest(requestId, method, params);
        } catch (IllegalArgumentException error) {
            submitInteractiveError(requestId, -32602, "Invalid interactive request");
            return true;
        }

        boolean stale;
        boolean overloaded;
        synchronized (this) {
            stale = closed
                || !ready
                || !turnActive
                || !matchesActiveThread(request.getThreadId())
                || (!activeTurnId.isEmpty()
                    && !activeTurnId.equals(request.getTurnId()));
            overloaded = !stale
                && interactiveRequests.size() >= MAX_INTERACTIVE_REQUESTS;
            if (!stale && !overloaded) {
                interactiveRequests.add(request);
                operationMessage = request.getKind() == CodexInteractiveRequest.Kind.USER_INPUT
                    ? "Codex wartet auf deine Eingabe."
                    : "Codex wartet auf deine Freigabe.";
                publishLocked();
            }
        }
        if (stale || overloaded) {
            submitSafeInteractiveRejection(request);
            return true;
        }
        scheduleInteractiveTimeout(request);
        return true;
    }

    @Override
    public void onNotification(String method, Map<String, Object> params) {
        if ("item/agentMessage/delta".equals(method)) {
            handleAgentDelta(params);
        } else if (FILE_CHANGE_PATCH_UPDATED_METHOD.equals(method)) {
            handleFileChangePatchUpdated(params);
        } else if ("item/started".equals(method) || "item/completed".equals(method)) {
            handleItem(params, "item/started".equals(method));
        } else if ("turn/started".equals(method)) {
            handleTurnStarted(params);
        } else if ("turn/completed".equals(method)) {
            handleTurnCompleted(params);
        } else if ("error".equals(method)) {
            handleErrorNotification(params);
        } else if ("account/login/completed".equals(method)) {
            handleLoginCompleted(params);
        } else if ("account/updated".equals(method)) {
            queueAccountRefresh();
        } else if ("serverRequest/resolved".equals(method)) {
            handleServerRequestResolved(params);
        }
    }

    @Override
    public synchronized void onTransportClosed(Throwable error) {
        if (closed) {
            return;
        }
        ready = false;
        turnActive = false;
        activeTurnId = "";
        operationActive = false;
        interactiveRequests.clear();
        pendingFileChanges.clear();
        connectionMessage = "Verbindung zum Codex App-Server wurde beendet.";
        errorMessage = safeError(error);
        publishLocked();
        interactiveResponses.shutdownNow();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            ready = false;
            turnActive = false;
            activeTurnId = "";
            operationActive = false;
            interactiveRequests.clear();
            pendingFileChanges.clear();
            connectionMessage = "Codex App-Server wurde gestoppt.";
            loginUrl = "";
            loginId = "";
            publishLocked();
        }
        operations.shutdownNow();
        interactiveResponses.shutdownNow();
        client.close();
    }

    private void verifyWorkspacePermissionProfileInternal() throws Exception {
        Set<String> cursors = new HashSet<String>();
        String cursor = "";
        for (int page = 0; page < 4; page++) {
            Map<String, Object> params = JsonCodec.object(
                "cwd", workspacePath,
                "limit", Long.valueOf(50L)
            );
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            Map<String, Object> result = client.request(
                "permissionProfile/list",
                params,
                NORMAL_TIMEOUT_MS
            );
            for (Object value : JsonCodec.requireArray(
                result.get("data"),
                "permission profiles"
            )) {
                Map<String, Object> profile = JsonCodec.requireObject(
                    value,
                    "permission profile"
                );
                if (WORKSPACE_PERMISSION_PROFILE.equals(
                        JsonCodec.optionalString(profile.get("id")))
                    && JsonCodec.booleanValue(profile.get("allowed"), false)) {
                    return;
                }
            }
            cursor = JsonCodec.optionalString(result.get("nextCursor"));
            if (cursor.isEmpty() || !cursors.add(cursor)) {
                break;
            }
        }
        throw new IllegalStateException(
            "Das private AGENTCODI-Workspace-Berechtigungsprofil ist nicht verfügbar."
        );
    }

    private void refreshModelsInternal() throws Exception {
        List<CodexModelOption> loaded = new ArrayList<CodexModelOption>();
        Set<String> ids = new HashSet<String>();
        Set<String> cursors = new HashSet<String>();
        String cursor = "";
        for (int page = 0; page < 2 && loaded.size() < MAX_MODELS; page++) {
            Map<String, Object> params = JsonCodec.object(
                "limit", Long.valueOf(MAX_MODELS),
                "includeHidden", Boolean.FALSE
            );
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            Map<String, Object> result = client.request(
                "model/list",
                params,
                NORMAL_TIMEOUT_MS
            );
            for (Object value : JsonCodec.requireArray(result.get("data"), "model/list data")) {
                if (loaded.size() >= MAX_MODELS) {
                    break;
                }
                Map<String, Object> entry = JsonCodec.requireObject(value, "model entry");
                if (JsonCodec.booleanValue(entry.get("hidden"), false)) {
                    continue;
                }
                String id = JsonCodec.optionalString(entry.get("id"));
                String requestModel = JsonCodec.optionalString(entry.get("model"));
                String defaultEffort = JsonCodec.optionalString(
                    entry.get("defaultReasoningEffort")
                );
                if (!isSafeCatalogToken(id) || !isSafeCatalogToken(requestModel)
                    || !isSafeCatalogToken(defaultEffort) || !ids.add(id)) {
                    continue;
                }
                List<CodexReasoningOption> reasoning = new ArrayList<CodexReasoningOption>();
                for (Object effortValue : JsonCodec.optionalArray(
                    entry.get("supportedReasoningEfforts")
                )) {
                    if (reasoning.size() >= MAX_REASONING_OPTIONS) {
                        break;
                    }
                    Map<String, Object> effort = JsonCodec.requireObject(
                        effortValue,
                        "reasoning effort"
                    );
                    String effortId = JsonCodec.optionalString(effort.get("reasoningEffort"));
                    if (!isSafeCatalogToken(effortId)
                        || containsReasoningEffort(reasoning, effortId)) {
                        continue;
                    }
                    reasoning.add(new CodexReasoningOption(
                        effortId,
                        bounded(JsonCodec.optionalString(effort.get("description")), 240)
                    ));
                }
                if (reasoning.isEmpty()
                    || !containsReasoningEffort(reasoning, defaultEffort)) {
                    continue;
                }
                loaded.add(new CodexModelOption(
                    id,
                    requestModel,
                    bounded(JsonCodec.optionalString(entry.get("displayName")), 120),
                    bounded(JsonCodec.optionalString(entry.get("description")), 400),
                    defaultEffort,
                    reasoning,
                    JsonCodec.booleanValue(entry.get("isDefault"), false)
                ));
            }
            cursor = JsonCodec.optionalString(result.get("nextCursor"));
            if (cursor.isEmpty() || !cursors.add(cursor)) {
                break;
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("Der Codex App-Server bietet kein auswählbares Modell an.");
        }
        synchronized (this) {
            String previousModelId = selectedModelId;
            String previousEffort = selectedReasoningEffort;
            models.clear();
            models.addAll(loaded);
            CodexModelOption selected = findModelLocked(previousModelId);
            if (selected == null) {
                selected = defaultModelLocked();
            }
            selectedModelId = selected.getId();
            selectedReasoningEffort = selected.supportsReasoningEffort(previousEffort)
                ? previousEffort
                : selected.getDefaultReasoningEffort();
            publishLocked();
        }
    }

    private void readAccountInternal() throws Exception {
        Map<String, Object> result = client.request(
            "account/read",
            JsonCodec.object("refreshToken", Boolean.FALSE),
            NORMAL_TIMEOUT_MS
        );
        Map<String, Object> account = JsonCodec.optionalObject(result.get("account"));
        synchronized (this) {
            requiresOpenaiAuth = JsonCodec.booleanValue(
                result.get("requiresOpenaiAuth"),
                true
            );
            if (account == null) {
                authMode = "";
                accountEmail = "";
                planType = "";
            } else {
                authMode = bounded(JsonCodec.requireString(account.get("type"), "account type"), 40);
                accountEmail = bounded(JsonCodec.optionalString(account.get("email")), 320);
                planType = bounded(JsonCodec.optionalString(account.get("planType")), 40);
            }
            if (!authMode.isEmpty()) {
                loginPending = false;
                loginUrl = "";
                loginId = "";
            }
            publishLocked();
        }
    }

    private void refreshThreadsInternal() throws Exception {
        List<CodexThreadSummary> loaded = new ArrayList<CodexThreadSummary>();
        Set<String> cursors = new HashSet<String>();
        String cursor = "";
        for (int page = 0; page < MAX_THREAD_PAGES && loaded.size() < MAX_THREADS; page++) {
            Map<String, Object> params = JsonCodec.object(
                "limit", Long.valueOf(50L),
                "sortKey", "updated_at",
                "sourceKinds", JsonCodec.array("cli", "vscode", "exec", "appServer")
            );
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            Map<String, Object> result = client.request(
                "thread/list",
                params,
                NORMAL_TIMEOUT_MS
            );
            for (Object value : JsonCodec.requireArray(result.get("data"), "thread/list data")) {
                if (loaded.size() >= MAX_THREADS) {
                    break;
                }
                Map<String, Object> thread = JsonCodec.requireObject(value, "thread summary");
                String id = JsonCodec.requireString(thread.get("id"), "thread id");
                if (isValidIdentifier(id)) {
                    loaded.add(new CodexThreadSummary(
                        id,
                        titleForThread(thread),
                        JsonCodec.longValue(thread.get("updatedAt"), 0L)
                    ));
                }
            }
            cursor = JsonCodec.optionalString(result.get("nextCursor"));
            if (cursor.isEmpty() || !cursors.add(cursor)) {
                break;
            }
        }
        synchronized (this) {
            threads.clear();
            threads.addAll(loaded);
            operationMessage = loaded.isEmpty()
                ? "Noch keine Chats vorhanden."
                : loaded.size() + " Chat(s) geladen.";
            publishLocked();
        }
    }

    private String startNewThreadInternal() throws Exception {
        String requestModel;
        synchronized (this) {
            requireNoActiveTurnOrRequestLocked();
            if (requiresOpenaiAuth && authMode.isEmpty()) {
                throw new IllegalStateException("Bitte zuerst anmelden.");
            }
            CodexModelOption model = findModelLocked(selectedModelId);
            if (model == null) {
                throw new IllegalStateException("Bitte zuerst ein angebotenes Modell wählen.");
            }
            requestModel = model.getModel();
        }
        Map<String, Object> params = JsonCodec.object(
            "cwd", workspacePath,
            "runtimeWorkspaceRoots", JsonCodec.array(workspacePath),
            "approvalPolicy", "on-request",
            "permissions", WORKSPACE_PERMISSION_PROFILE,
            "modelProvider", OPENAI_HTTP_MODEL_PROVIDER,
            "model", requestModel,
            "persistExtendedHistory", Boolean.TRUE
        );
        Map<String, Object> result = client.request(
            "thread/start",
            params,
            NORMAL_TIMEOUT_MS
        );
        requireWorkspacePermissionProfile(result, "thread/start");
        Map<String, Object> thread = JsonCodec.requireObject(
            result.get("thread"),
            "thread/start thread"
        );
        String id = JsonCodec.requireString(thread.get("id"), "thread id");
        if (!isValidIdentifier(id)) {
            throw new IllegalArgumentException("App-server returned an invalid thread id");
        }
        requireHttpModelProvider(thread, "thread/start");
        synchronized (this) {
            activeThreadId = id;
            activeThreadTitle = titleForThread(thread);
            updateSelectionFromThreadResponseLocked(result);
            messages.clear();
            turnActive = false;
            activeTurnId = "";
            lastCompletedTurnId = "";
            interactiveRequests.clear();
            pendingFileChanges.clear();
            upsertThreadLocked(new CodexThreadSummary(id, activeThreadTitle, 0L));
            operationMessage = "Neuer Chat ist bereit.";
            publishLocked();
        }
        return id;
    }

    private void handleAgentDelta(Map<String, Object> params) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        String delta = JsonCodec.optionalString(params.get("delta"));
        if (itemId.isEmpty() || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!matchesActiveThread(threadId)
                || isStaleStreamingEventLocked(turnId)
                || isFinalAssistantMessageLocked(itemId)) {
                return;
            }
            int index = findMessageLocked(itemId);
            String existing = index < 0 ? "" : messages.get(index).getText();
            String combined = boundedStream(existing, delta);
            ChatMessage next = new ChatMessage(
                itemId,
                ChatMessage.Role.ASSISTANT,
                combined,
                true
            );
            if (index < 0) {
                addBoundedMessageLocked(next);
            } else {
                messages.set(index, next);
                boundTotalMessagesLocked();
            }
            publishLocked();
        }
    }

    private void handleItem(Map<String, Object> params, boolean startedEvent) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        Map<String, Object> item;
        try {
            item = JsonCodec.requireObject(params.get("item"), "item notification");
        } catch (IllegalArgumentException ignored) {
            return;
        }
        String type = JsonCodec.optionalString(item.get("type"));
        String itemId = JsonCodec.optionalString(item.get("id"));
        if (itemId.isEmpty()) {
            return;
        }
        List<CodexFileChangeSummary> fileChanges = "fileChange".equals(type)
            ? parseFileChangeSummaries(item)
            : Collections.<CodexFileChangeSummary>emptyList();
        synchronized (this) {
            if (!matchesActiveThread(threadId)) {
                return;
            }
            if ("agentMessage".equals(type)) {
                String text = bounded(JsonCodec.optionalString(item.get("text")), MAX_MESSAGE_CHARACTERS);
                if (startedEvent) {
                    if (isStaleStreamingEventLocked(turnId)
                        || findMessageLocked(itemId) >= 0) {
                        return;
                    }
                    addBoundedMessageLocked(new ChatMessage(
                        itemId,
                        ChatMessage.Role.ASSISTANT,
                        text,
                        true
                    ));
                } else {
                    upsertMessageLocked(new ChatMessage(
                        itemId,
                        ChatMessage.Role.ASSISTANT,
                        text,
                        false
                    ));
                }
                publishLocked();
            } else if ("userMessage".equals(type) && !startedEvent) {
                String text = extractUserText(item);
                if (!text.isEmpty()) {
                    replacePendingUserOrAddLocked(itemId, text);
                    publishLocked();
                }
            } else if ("fileChange".equals(type)) {
                if (startedEvent) {
                    cacheFileChangesLocked(itemId, fileChanges);
                }
                boolean changed = !fileChanges.isEmpty()
                    && enrichInteractiveFileChangeLocked(itemId, fileChanges);
                if (!startedEvent) {
                    pendingFileChanges.remove(itemId);
                }
                if (changed) {
                    publishLocked();
                }
            }
        }
    }

    private void handleFileChangePatchUpdated(Map<String, Object> params) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        if (!isSafeOpaqueIdentifier(threadId)
            || !isSafeOpaqueIdentifier(turnId)
            || !isSafeOpaqueIdentifier(itemId)) {
            return;
        }
        List<CodexFileChangeSummary> changes = parseFileChangeSummaries(params);
        synchronized (this) {
            if (!turnActive
                || !matchesActiveThread(threadId)
                || (!activeTurnId.isEmpty() && !activeTurnId.equals(turnId))) {
                return;
            }
            replaceFileChangesLocked(itemId, changes);
            if (enrichInteractiveFileChangeLocked(itemId, changes)) {
                publishLocked();
            }
        }
    }

    private void handleTurnStarted(Map<String, Object> params) {
        Map<String, Object> turn = JsonCodec.optionalObject(params.get("turn"));
        if (turn == null) {
            return;
        }
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        synchronized (this) {
            if (!matchesActiveThread(threadId)) {
                return;
            }
            String startedTurnId = JsonCodec.optionalString(turn.get("id"));
            activeTurnId = startedTurnId;
            turnActive = !startedTurnId.isEmpty() && !startedTurnId.equals(lastCompletedTurnId);
            if (!turnActive) {
                activeTurnId = "";
            }
            operationMessage = "Codex arbeitet.";
            publishLocked();
        }
    }

    private void handleTurnCompleted(Map<String, Object> params) {
        Map<String, Object> turn = JsonCodec.optionalObject(params.get("turn"));
        if (turn == null) {
            return;
        }
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        synchronized (this) {
            if (!matchesActiveThread(threadId)) {
                return;
            }
            String completedTurnId = JsonCodec.optionalString(turn.get("id"));
            if (completedTurnId.isEmpty()
                || completedTurnId.equals(lastCompletedTurnId)
                || (!activeTurnId.isEmpty() && !activeTurnId.equals(completedTurnId))) {
                return;
            }
            turnActive = false;
            lastCompletedTurnId = completedTurnId;
            activeTurnId = "";
            clearInteractiveRequestsForTurnLocked(completedTurnId);
            pendingFileChanges.clear();
            finishStreamingMessagesLocked();
            String status = JsonCodec.optionalString(turn.get("status"));
            operationMessage = "interrupted".equals(status)
                ? "Turn wurde gestoppt."
                : "failed".equals(status) ? "Turn ist fehlgeschlagen." : "Antwort abgeschlossen.";
            Map<String, Object> error = JsonCodec.optionalObject(turn.get("error"));
            if (error != null) {
                String message = JsonCodec.optionalString(error.get("message"));
                if (!message.isEmpty()) {
                    addSystemMessageLocked(message);
                }
            }
            publishLocked();
        }
        queueThreadRefresh();
    }

    private void handleErrorNotification(Map<String, Object> params) {
        Map<String, Object> error = JsonCodec.optionalObject(params.get("error"));
        String message = error == null
            ? JsonCodec.optionalString(params.get("message"))
            : JsonCodec.optionalString(error.get("message"));
        if (message.isEmpty()) {
            message = "Codex hat einen nicht näher bezeichneten Fehler gemeldet.";
        }
        synchronized (this) {
            errorMessage = bounded(CrashReportFormatter.redact(message), MAX_ERROR_CHARACTERS);
            addSystemMessageLocked(errorMessage);
            publishLocked();
        }
    }

    private void handleLoginCompleted(Map<String, Object> params) {
        boolean success = JsonCodec.booleanValue(params.get("success"), false);
        String completedLoginId = JsonCodec.optionalString(params.get("loginId"));
        synchronized (this) {
            if (!loginId.isEmpty() && !completedLoginId.isEmpty()
                && !loginId.equals(completedLoginId)) {
                return;
            }
            loginPending = false;
            loginUrl = "";
            loginId = "";
            if (!success) {
                errorMessage = bounded(
                    CrashReportFormatter.redact(JsonCodec.optionalString(params.get("error"))),
                    MAX_ERROR_CHARACTERS
                );
                if (errorMessage.isEmpty()) {
                    errorMessage = "Anmeldung wurde nicht abgeschlossen.";
                }
            }
            publishLocked();
        }
        if (success) {
            queueAccountRefresh();
        }
    }

    private void queueAccountRefresh() {
        submitSilently(new Operation() {
            @Override
            public void run() throws Exception {
                readAccountInternal();
                refreshModelsInternal();
                refreshThreadsInternal();
            }
        });
    }

    private void queueThreadRefresh() {
        submitSilently(new Operation() {
            @Override
            public void run() throws Exception {
                refreshThreadsInternal();
            }
        });
    }

    private boolean submit(final String status, final Operation operation) {
        synchronized (this) {
            if (closed || !ready) {
                errorMessage = "Codex App-Server ist nicht bereit.";
                publishLocked();
                return false;
            }
            if (operationActive) {
                errorMessage = "Eine andere Codex-Aktion läuft bereits.";
                publishLocked();
                return false;
            }
            operationActive = true;
            operationMessage = status;
            errorMessage = "";
            publishLocked();
        }
        try {
            operations.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        operation.run();
                    } catch (Throwable error) {
                        synchronized (CodexSessionController.this) {
                            errorMessage = safeError(error);
                            if (loginPending && loginUrl.isEmpty()) {
                                loginPending = false;
                            }
                        }
                    } finally {
                        synchronized (CodexSessionController.this) {
                            operationActive = false;
                            publishLocked();
                        }
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            synchronized (this) {
                operationActive = false;
                errorMessage = "Codex Runtime wird beendet.";
                publishLocked();
            }
            return false;
        }
    }

    private void submitSilently(final Operation operation) {
        synchronized (this) {
            if (closed || !ready) {
                return;
            }
        }
        try {
            operations.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        operation.run();
                    } catch (Throwable error) {
                        synchronized (CodexSessionController.this) {
                            errorMessage = safeError(error);
                            publishLocked();
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown won the race.
        }
    }

    private CodexInteractiveRequest parseInteractiveRequest(
        long requestId,
        String method,
        Map<String, Object> params
    ) {
        String threadId = requireInteractiveIdentifier(params.get("threadId"), "threadId");
        String turnId = requireInteractiveIdentifier(params.get("turnId"), "turnId");
        String itemId = requireInteractiveIdentifier(params.get("itemId"), "itemId");
        long now = System.currentTimeMillis();

        if (USER_INPUT_METHOD.equals(method)) {
            Object blockingValue = params.get("isBlocking");
            if (!(blockingValue instanceof Boolean)) {
                throw new IllegalArgumentException("isBlocking must be a boolean");
            }
            long waitMilliseconds = MAX_INTERACTIVE_WAIT_MS;
            Object autoResolution = params.get("autoResolutionMs");
            if (autoResolution != null) {
                if (!(autoResolution instanceof Long)
                    || ((Long) autoResolution).longValue() < 0L) {
                    throw new IllegalArgumentException("autoResolutionMs must be an integer");
                }
                waitMilliseconds = Math.min(
                    ((Long) autoResolution).longValue(),
                    MAX_INTERACTIVE_WAIT_MS
                );
            }
            List<Object> questionValues = JsonCodec.requireArray(
                params.get("questions"),
                "user input questions"
            );
            if (questionValues.isEmpty()
                || questionValues.size() > MAX_USER_INPUT_QUESTIONS) {
                throw new IllegalArgumentException("user input question count is invalid");
            }
            List<CodexUserInputQuestion> questions =
                new ArrayList<CodexUserInputQuestion>();
            Set<String> questionIds = new HashSet<String>();
            for (Object questionValue : questionValues) {
                Map<String, Object> question = JsonCodec.requireObject(
                    questionValue,
                    "user input question"
                );
                String questionId = requireQuestionId(question.get("id"));
                if (!questionIds.add(questionId)) {
                    throw new IllegalArgumentException("user input question ids must be unique");
                }
                String header = requireBoundedString(question.get("header"), "header", 80);
                String prompt = requireBoundedString(
                    question.get("question"),
                    "question",
                    1200
                );
                List<Object> optionValues = JsonCodec.optionalArray(question.get("options"));
                if (optionValues.size() > MAX_USER_INPUT_OPTIONS) {
                    throw new IllegalArgumentException("user input option count is invalid");
                }
                List<CodexUserInputOption> options = new ArrayList<CodexUserInputOption>();
                Set<String> optionLabels = new HashSet<String>();
                for (Object optionValue : optionValues) {
                    Map<String, Object> option = JsonCodec.requireObject(
                        optionValue,
                        "user input option"
                    );
                    String label = requireBoundedString(option.get("label"), "option label", 160);
                    if (!optionLabels.add(label)) {
                        throw new IllegalArgumentException("user input option labels must be unique");
                    }
                    options.add(new CodexUserInputOption(
                        label,
                        requireBoundedString(
                            option.get("description"),
                            "option description",
                            600
                        )
                    ));
                }
                questions.add(new CodexUserInputQuestion(
                    questionId,
                    header,
                    prompt,
                    options,
                    optionalBoolean(question, "isOther", false),
                    optionalBoolean(question, "isSecret", false)
                ));
            }
            return new CodexInteractiveRequest(
                requestId,
                CodexInteractiveRequest.Kind.USER_INPUT,
                threadId,
                turnId,
                itemId,
                "",
                "",
                "",
                "",
                "",
                "",
                Collections.<CodexFileChangeSummary>emptyList(),
                Collections.<String>emptyList(),
                Collections.<CodexNetworkPolicyAmendment>emptyList(),
                questions,
                ((Boolean) blockingValue).booleanValue(),
                now + waitMilliseconds
            );
        }

        Object startedAt = params.get("startedAtMs");
        if (!(startedAt instanceof Long) || ((Long) startedAt).longValue() < 0L) {
            throw new IllegalArgumentException("startedAtMs must be a positive integer");
        }
        if (params.containsKey("additionalPermissions")) {
            throw new IllegalArgumentException("additional permissions are not supported");
        }
        String reason = optionalBoundedString(params.get("reason"), 2000);
        String command = optionalBoundedString(params.get("command"), 16 * 1024);
        if (command.isEmpty()) {
            command = commandActionSummary(params.get("commandActions"));
        }
        String cwd = optionalBoundedString(params.get("cwd"), 2048);
        String grantRoot = optionalBoundedString(params.get("grantRoot"), 2048);
        String networkHost = "";
        String networkProtocol = "";
        Map<String, Object> networkContext = JsonCodec.optionalObject(
            params.get("networkApprovalContext")
        );
        if (networkContext != null) {
            networkHost = requireBoundedString(networkContext.get("host"), "network host", 253);
            networkProtocol = requireBoundedString(
                networkContext.get("protocol"),
                "network protocol",
                24
            );
            if (!"http".equals(networkProtocol)
                && !"https".equals(networkProtocol)
                && !"socks5Tcp".equals(networkProtocol)
                && !"socks5Udp".equals(networkProtocol)) {
                throw new IllegalArgumentException("network protocol is unsupported");
            }
        }

        List<String> execPolicy = parseBoundedStringArray(
            params.get("proposedExecpolicyAmendment"),
            MAX_POLICY_AMENDMENT_PARTS,
            1024
        );
        List<CodexNetworkPolicyAmendment> networkPolicies =
            new ArrayList<CodexNetworkPolicyAmendment>();
        List<Object> networkValues = JsonCodec.optionalArray(
            params.get("proposedNetworkPolicyAmendments")
        );
        if (networkValues.size() > MAX_POLICY_AMENDMENT_PARTS) {
            throw new IllegalArgumentException("too many network policy amendments");
        }
        for (Object networkValue : networkValues) {
            Map<String, Object> amendment = JsonCodec.requireObject(
                networkValue,
                "network policy amendment"
            );
            String action = requireBoundedString(amendment.get("action"), "network action", 8);
            if (!"allow".equals(action) && !"deny".equals(action)) {
                throw new IllegalArgumentException("network policy action is invalid");
            }
            networkPolicies.add(new CodexNetworkPolicyAmendment(
                action,
                requireBoundedString(amendment.get("host"), "network policy host", 253)
            ));
        }

        CodexInteractiveRequest.Kind kind = COMMAND_APPROVAL_METHOD.equals(method)
            ? CodexInteractiveRequest.Kind.COMMAND_APPROVAL
            : CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL;
        List<CodexFileChangeSummary> changes = Collections.emptyList();
        if (kind == CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL) {
            synchronized (this) {
                List<CodexFileChangeSummary> cached = pendingFileChanges.get(itemId);
                if (cached != null) {
                    changes = cached;
                }
            }
        }
        return new CodexInteractiveRequest(
            requestId,
            kind,
            threadId,
            turnId,
            itemId,
            reason,
            command,
            cwd,
            grantRoot,
            networkHost,
            networkProtocol,
            changes,
            execPolicy,
            networkPolicies,
            Collections.<CodexUserInputQuestion>emptyList(),
            true,
            now + MAX_INTERACTIVE_WAIT_MS
        );
    }

    private Map<String, Object> approvalResponse(
        CodexInteractiveRequest request,
        CodexApprovalDecision decision,
        int amendmentIndex
    ) {
        Object wireDecision;
        switch (decision) {
            case ACCEPT:
                requireApprovalScope(request);
                wireDecision = "accept";
                break;
            case ACCEPT_FOR_SESSION:
                requireApprovalScope(request);
                wireDecision = "acceptForSession";
                break;
            case DECLINE:
                wireDecision = "decline";
                break;
            case CANCEL:
                wireDecision = "cancel";
                break;
            case ACCEPT_WITH_EXEC_POLICY_AMENDMENT:
                requireCommandApproval(request);
                requireApprovalScope(request);
                if (request.getProposedExecPolicyAmendment().isEmpty()) {
                    throw new IllegalArgumentException(
                        "Für diese Freigabe wurde keine Befehlsregel vorgeschlagen."
                    );
                }
                wireDecision = JsonCodec.object(
                    "acceptWithExecpolicyAmendment",
                    JsonCodec.object(
                        "execpolicy_amendment",
                        new ArrayList<String>(request.getProposedExecPolicyAmendment())
                    )
                );
                break;
            case APPLY_NETWORK_POLICY_AMENDMENT:
                requireCommandApproval(request);
                if (amendmentIndex < 0
                    || amendmentIndex >= request.getProposedNetworkPolicyAmendments().size()) {
                    throw new IllegalArgumentException(
                        "Die vorgeschlagene Netzwerkregel ist nicht mehr verfügbar."
                    );
                }
                CodexNetworkPolicyAmendment amendment =
                    request.getProposedNetworkPolicyAmendments().get(amendmentIndex);
                wireDecision = JsonCodec.object(
                    "applyNetworkPolicyAmendment",
                    JsonCodec.object(
                        "network_policy_amendment",
                        JsonCodec.object(
                            "action", amendment.getAction(),
                            "host", amendment.getHost()
                        )
                    )
                );
                break;
            default:
                throw new IllegalArgumentException("Unbekannte Freigabeentscheidung.");
        }
        return JsonCodec.object("decision", wireDecision);
    }

    private static Map<String, Object> userInputResponse(
        CodexInteractiveRequest request,
        Map<String, char[]> suppliedAnswers
    ) {
        if (suppliedAnswers == null
            || suppliedAnswers.size() != request.getQuestions().size()) {
            throw new IllegalArgumentException("Bitte alle Fragen beantworten.");
        }
        Map<String, Object> answers = new LinkedHashMap<String, Object>();
        for (CodexUserInputQuestion question : request.getQuestions()) {
            char[] value = suppliedAnswers.get(question.getId());
            if (value == null || value.length == 0
                || value.length > MAX_USER_INPUT_ANSWER_CHARACTERS) {
                throw new IllegalArgumentException("Bitte alle Fragen gültig beantworten.");
            }
            answers.put(
                question.getId(),
                JsonCodec.object("answers", JsonCodec.array(new String(value)))
            );
        }
        return JsonCodec.object("answers", answers);
    }

    private static Map<String, Object> emptyUserInputResponse() {
        return JsonCodec.object("answers", new LinkedHashMap<String, Object>());
    }

    private void submitInteractiveResponse(
        final long requestId,
        final Map<String, Object> response
    ) {
        try {
            interactiveResponses.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.respondToServerRequest(requestId, response);
                    } catch (Throwable error) {
                        handleInteractiveResponseFailure(error);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Runtime shutdown is already authoritative.
        }
    }

    private void submitInteractiveError(
        final long requestId,
        final int code,
        final String message
    ) {
        try {
            interactiveResponses.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.respondToServerRequestError(requestId, code, message);
                    } catch (Throwable error) {
                        handleInteractiveResponseFailure(error);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Runtime shutdown is already authoritative.
        }
    }

    private void submitSafeInteractiveRejection(CodexInteractiveRequest request) {
        Map<String, Object> response = request.getKind()
            == CodexInteractiveRequest.Kind.USER_INPUT
            ? emptyUserInputResponse()
            : JsonCodec.object("decision", "cancel");
        submitInteractiveResponse(request.getRequestId(), response);
    }

    private void scheduleInteractiveTimeout(final CodexInteractiveRequest request) {
        long delay = Math.max(
            0L,
            request.getExpiresAtMilliseconds() - System.currentTimeMillis()
        );
        try {
            interactiveResponses.schedule(new Runnable() {
                @Override
                public void run() {
                    boolean removed;
                    synchronized (CodexSessionController.this) {
                        int index = findInteractiveRequestLocked(request.getRequestId());
                        removed = index >= 0;
                        if (removed) {
                            interactiveRequests.remove(index);
                            operationMessage = "Eine Codex-Anfrage ist sicher abgelaufen.";
                            publishLocked();
                        }
                    }
                    if (removed) {
                        try {
                            client.respondToServerRequest(
                                request.getRequestId(),
                                request.getKind() == CodexInteractiveRequest.Kind.USER_INPUT
                                    ? emptyUserInputResponse()
                                    : JsonCodec.object("decision", "cancel")
                            );
                        } catch (Throwable error) {
                            handleInteractiveResponseFailure(error);
                        }
                    }
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Runtime shutdown is already authoritative.
        }
    }

    private void handleServerRequestResolved(Map<String, Object> params) {
        Object requestIdValue = params.get("requestId");
        if (!(requestIdValue instanceof Long)) {
            return;
        }
        long requestId = ((Long) requestIdValue).longValue();
        if (requestId < 0L || requestId > CodexAppServerClient.MAX_REQUEST_ID) {
            return;
        }
        client.abandonServerRequest(requestId);
        synchronized (this) {
            int index = findInteractiveRequestLocked(requestId);
            if (index >= 0) {
                interactiveRequests.remove(index);
                operationMessage = "Codex hat die Anfrage geschlossen.";
                publishLocked();
            }
        }
    }

    private synchronized void handleInteractiveResponseFailure(Throwable error) {
        if (closed) {
            return;
        }
        ready = false;
        turnActive = false;
        activeTurnId = "";
        operationActive = false;
        interactiveRequests.clear();
        pendingFileChanges.clear();
        connectionMessage = "Eine Antwort an den Codex App-Server ist fehlgeschlagen.";
        errorMessage = safeError(error);
        publishLocked();
        client.close();
    }

    private int findInteractiveRequestLocked(long requestId) {
        for (int index = 0; index < interactiveRequests.size(); index++) {
            if (interactiveRequests.get(index).getRequestId() == requestId) {
                return index;
            }
        }
        return -1;
    }

    private void clearInteractiveRequestsForTurnLocked(String turnId) {
        for (int index = interactiveRequests.size() - 1; index >= 0; index--) {
            CodexInteractiveRequest request = interactiveRequests.get(index);
            if (turnId.equals(request.getTurnId())) {
                interactiveRequests.remove(index);
                client.abandonServerRequest(request.getRequestId());
            }
        }
    }

    private void cacheFileChangesLocked(
        String itemId,
        List<CodexFileChangeSummary> changes
    ) {
        if (changes.isEmpty()) {
            return;
        }
        if (pendingFileChanges.size() >= MAX_INTERACTIVE_REQUESTS
            && !pendingFileChanges.containsKey(itemId)) {
            String first = pendingFileChanges.keySet().iterator().next();
            pendingFileChanges.remove(first);
        }
        pendingFileChanges.put(
            itemId,
            Collections.unmodifiableList(new ArrayList<CodexFileChangeSummary>(changes))
        );
    }

    private void replaceFileChangesLocked(
        String itemId,
        List<CodexFileChangeSummary> changes
    ) {
        if (changes.isEmpty()) {
            pendingFileChanges.remove(itemId);
            return;
        }
        cacheFileChangesLocked(itemId, changes);
    }

    private boolean enrichInteractiveFileChangeLocked(
        String itemId,
        List<CodexFileChangeSummary> changes
    ) {
        for (int index = 0; index < interactiveRequests.size(); index++) {
            CodexInteractiveRequest request = interactiveRequests.get(index);
            if (request.getKind() == CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL
                && request.getItemId().equals(itemId)) {
                interactiveRequests.set(index, request.withFileChanges(changes));
                return true;
            }
        }
        return false;
    }

    private static List<CodexFileChangeSummary> parseFileChangeSummaries(
        Map<String, Object> item
    ) {
        List<CodexFileChangeSummary> changes = new ArrayList<CodexFileChangeSummary>();
        int totalCharacters = 0;
        List<Object> values;
        try {
            values = JsonCodec.optionalArray(item.get("changes"));
        } catch (IllegalArgumentException ignored) {
            return changes;
        }
        if (values.size() > MAX_FILE_CHANGE_SUMMARIES) {
            return changes;
        }
        for (Object value : values) {
            if (totalCharacters >= MAX_FILE_CHANGE_TOTAL_CHARACTERS) {
                return Collections.emptyList();
            }
            try {
                Map<String, Object> change = JsonCodec.requireObject(value, "file change");
                String path = optionalBoundedString(change.get("path"), 2048);
                Map<String, Object> kindValue = JsonCodec.requireObject(
                    change.get("kind"),
                    "file change kind"
                );
                String kind = requireBoundedString(
                    kindValue.get("type"),
                    "file change kind type",
                    16
                );
                if (!"add".equals(kind)
                    && !"delete".equals(kind)
                    && !"update".equals(kind)) {
                    throw new IllegalArgumentException("file change kind is invalid");
                }
                String movePath = "update".equals(kind)
                    ? optionalBoundedString(kindValue.get("move_path"), 2048)
                    : "";
                int metadataCharacters = path.length() + kind.length() + movePath.length();
                int remainingCharacters = MAX_FILE_CHANGE_TOTAL_CHARACTERS
                    - totalCharacters
                    - metadataCharacters;
                if (path.isEmpty() || remainingCharacters < 0) {
                    return Collections.emptyList();
                }
                Object diffValue = change.get("diff");
                if (!(diffValue instanceof String)) {
                    throw new IllegalArgumentException("file change diff must be a string");
                }
                String diff = bounded(
                    (String) diffValue,
                    Math.min(
                        MAX_FILE_CHANGE_DIFF_CHARACTERS,
                        remainingCharacters
                    )
                );
                changes.add(new CodexFileChangeSummary(path, kind, movePath, diff));
                totalCharacters += metadataCharacters + diff.length();
            } catch (IllegalArgumentException ignored) {
                // Never approve a patch whose complete path set could not be validated.
                return Collections.emptyList();
            }
        }
        return changes;
    }

    private static void requireCommandApproval(CodexInteractiveRequest request) {
        if (request.getKind() != CodexInteractiveRequest.Kind.COMMAND_APPROVAL) {
            throw new IllegalArgumentException(
                "Diese Entscheidung ist nur für Befehlsfreigaben zulässig."
            );
        }
    }

    private void requireApprovalScope(CodexInteractiveRequest request) {
        if (request.getKind() == CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL
            && request.getFileChanges().isEmpty()) {
            throw new IllegalArgumentException(
                "Die Dateiänderungsdetails sind noch nicht verfügbar; bitte nicht blind freigeben."
            );
        }
        if (request.getKind() == CodexInteractiveRequest.Kind.COMMAND_APPROVAL
            && request.getCommand().isEmpty()
            && request.getNetworkHost().isEmpty()) {
            throw new IllegalArgumentException(
                "Die Befehlsdetails sind noch nicht verfügbar; bitte nicht blind freigeben."
            );
        }
        if (!isSafeWorkspacePath(request.getCwd())
            || !isSafeWorkspacePath(request.getGrantRoot())) {
            throw new IllegalArgumentException(
                "Eine Freigabe außerhalb des privaten Workspace ist nicht zulässig."
            );
        }
        for (CodexFileChangeSummary change : request.getFileChanges()) {
            if (!isSafeWorkspacePath(change.getPath())
                || !isSafeWorkspacePath(change.getMovePath())) {
                throw new IllegalArgumentException(
                    "Eine Dateiänderung außerhalb des privaten Workspace ist nicht zulässig."
                );
            }
        }
    }

    private boolean isSafeWorkspacePath(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0) {
            return false;
        }
        if (value.equals("..") || value.startsWith("../")
            || value.endsWith("/..") || value.contains("/../")) {
            return false;
        }
        if (!value.startsWith("/")) {
            return true;
        }
        return value.equals(workspacePath) || value.startsWith(workspacePath + "/");
    }

    private void requireNoActiveTurnOrRequestLocked() {
        if (turnActive || !interactiveRequests.isEmpty()) {
            throw new IllegalStateException(
                "Der laufende Turn muss zuerst abgeschlossen oder gestoppt werden."
            );
        }
    }

    private static String requireInteractiveIdentifier(Object value, String field) {
        String identifier = JsonCodec.requireString(value, field);
        if (!isSafeOpaqueIdentifier(identifier)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return identifier;
    }

    private static String requireQuestionId(Object value) {
        String identifier = JsonCodec.requireString(value, "question id");
        if (identifier.length() > 80 || !isSafeOpaqueIdentifier(identifier)) {
            throw new IllegalArgumentException("question id is invalid");
        }
        return identifier;
    }

    private static boolean isSafeOpaqueIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 160) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_' && character != '.'
                && character != ':') {
                return false;
            }
        }
        return true;
    }

    private static String requireBoundedString(Object value, String field, int maximum) {
        String text = JsonCodec.requireString(value, field);
        if (text.length() > maximum || containsForbiddenControl(text)) {
            throw new IllegalArgumentException(field + " exceeds its limit");
        }
        return text;
    }

    private static String optionalBoundedString(Object value, int maximum) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("optional value must be a string");
        }
        String text = (String) value;
        if (text.length() > maximum || containsForbiddenControl(text)) {
            throw new IllegalArgumentException("optional value exceeds its limit");
        }
        return text;
    }

    private static boolean containsForbiddenControl(String value) {
        return value.indexOf('\0') >= 0;
    }

    private static boolean optionalBoolean(
        Map<String, Object> value,
        String field,
        boolean fallback
    ) {
        Object candidate = value.get(field);
        if (candidate == null) {
            return fallback;
        }
        if (!(candidate instanceof Boolean)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return ((Boolean) candidate).booleanValue();
    }

    private static List<String> parseBoundedStringArray(
        Object value,
        int maximumEntries,
        int maximumCharacters
    ) {
        List<Object> values = JsonCodec.optionalArray(value);
        if (values.size() > maximumEntries) {
            throw new IllegalArgumentException("string array has too many entries");
        }
        List<String> result = new ArrayList<String>();
        int totalCharacters = 0;
        for (Object entry : values) {
            String text = requireBoundedString(entry, "array entry", maximumCharacters);
            totalCharacters += text.length();
            if (totalCharacters > 16 * 1024) {
                throw new IllegalArgumentException("string array exceeds its limit");
            }
            result.add(text);
        }
        return result;
    }

    private static String commandActionSummary(Object value) {
        List<Object> actions = JsonCodec.optionalArray(value);
        if (actions.size() > 32) {
            throw new IllegalArgumentException("too many command actions");
        }
        StringBuilder summary = new StringBuilder();
        Set<String> seen = new HashSet<String>();
        for (Object actionValue : actions) {
            Map<String, Object> action = JsonCodec.requireObject(
                actionValue,
                "command action"
            );
            String command = optionalBoundedString(action.get("command"), 4096);
            if (command.isEmpty() || !seen.add(command)) {
                continue;
            }
            if (summary.length() != 0) {
                summary.append('\n');
            }
            if (summary.length() + command.length() > 16 * 1024) {
                throw new IllegalArgumentException("command actions exceed their limit");
            }
            summary.append(command);
        }
        return summary.toString();
    }

    private static void wipeAnswers(Map<String, char[]> answers) {
        if (answers == null) {
            return;
        }
        for (char[] value : answers.values()) {
            wipe(value);
        }
        answers.clear();
    }

    private synchronized void setUserError(String message) {
        errorMessage = message;
        publishLocked();
    }

    private synchronized void publishLocked() {
        revision++;
        snapshot = new CodexSessionSnapshot(
            revision,
            ready,
            connectionMessage,
            requiresOpenaiAuth,
            authMode,
            accountEmail,
            planType,
            loginPending,
            loginUrl,
            operationActive,
            operationMessage,
            models,
            selectedModelId,
            selectedReasoningEffort,
            threads,
            activeThreadId,
            activeThreadTitle,
            messages,
            turnActive,
            activeTurnId,
            interactiveRequests,
            errorMessage
        );
    }

    private synchronized boolean matchesActiveThread(String threadId) {
        return !activeThreadId.isEmpty() && (threadId.isEmpty() || activeThreadId.equals(threadId));
    }

    private boolean isStaleStreamingEventLocked(String turnId) {
        if (!turnActive) {
            return true;
        }
        if (turnId.isEmpty()) {
            return false;
        }
        if (turnId.equals(lastCompletedTurnId)) {
            return true;
        }
        return !activeTurnId.isEmpty() && !activeTurnId.equals(turnId);
    }

    private boolean isFinalAssistantMessageLocked(String itemId) {
        int index = findMessageLocked(itemId);
        if (index < 0) {
            return false;
        }
        ChatMessage message = messages.get(index);
        return message.getRole() == ChatMessage.Role.ASSISTANT && !message.isStreaming();
    }

    private void finishStreamingMessagesLocked() {
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if (message.getRole() == ChatMessage.Role.ASSISTANT && message.isStreaming()) {
                messages.set(index, new ChatMessage(
                    message.getId(),
                    message.getRole(),
                    message.getText(),
                    false
                ));
            }
        }
    }

    private static void requireWorkspacePermissionProfile(
        Map<String, Object> response,
        String method
    ) {
        Map<String, Object> active = JsonCodec.optionalObject(
            response.get("activePermissionProfile")
        );
        if (active == null || !WORKSPACE_PERMISSION_PROFILE.equals(
            JsonCodec.optionalString(active.get("id"))
        )) {
            throw new IllegalStateException(
                method + " hat das private Workspace-Berechtigungsprofil nicht aktiviert."
            );
        }
    }

    private static void requireHttpModelProvider(Map<String, Object> thread, String method) {
        if (!OPENAI_HTTP_MODEL_PROVIDER.equals(
            JsonCodec.optionalString(thread.get("modelProvider"))
        )) {
            throw new IllegalStateException(
                method + " hat den erforderlichen HTTPS-Modellprovider nicht aktiviert."
            );
        }
    }

    private void updateSelectionFromThreadResponseLocked(Map<String, Object> response) {
        String responseModel = JsonCodec.optionalString(response.get("model"));
        CodexModelOption model = findModelByRequestNameLocked(responseModel);
        if (model == null) {
            return;
        }
        boolean modelChanged = !model.getId().equals(selectedModelId);
        selectedModelId = model.getId();
        String responseEffort = JsonCodec.optionalString(response.get("reasoningEffort"));
        if (model.supportsReasoningEffort(responseEffort)) {
            selectedReasoningEffort = responseEffort;
        } else if (modelChanged || !model.supportsReasoningEffort(selectedReasoningEffort)) {
            selectedReasoningEffort = model.getDefaultReasoningEffort();
        }
    }

    private CodexModelOption defaultModelLocked() {
        for (CodexModelOption model : models) {
            if (model.isDefaultModel()) {
                return model;
            }
        }
        return models.get(0);
    }

    private CodexModelOption findModelLocked(String modelId) {
        if (modelId == null) {
            return null;
        }
        for (CodexModelOption model : models) {
            if (model.getId().equals(modelId)) {
                return model;
            }
        }
        return null;
    }

    private CodexModelOption findModelByRequestNameLocked(String requestModel) {
        if (requestModel == null) {
            return null;
        }
        for (CodexModelOption model : models) {
            if (model.getModel().equals(requestModel) || model.getId().equals(requestModel)) {
                return model;
            }
        }
        return null;
    }

    private void upsertThreadLocked(CodexThreadSummary value) {
        for (int index = 0; index < threads.size(); index++) {
            if (threads.get(index).getId().equals(value.getId())) {
                threads.remove(index);
                break;
            }
        }
        threads.add(0, value);
        while (threads.size() > MAX_THREADS) {
            threads.remove(threads.size() - 1);
        }
    }

    private void upsertMessageLocked(ChatMessage value) {
        int index = findMessageLocked(value.getId());
        if (index < 0) {
            addBoundedMessageLocked(value);
        } else {
            messages.set(index, value);
            boundTotalMessagesLocked();
        }
    }

    private void replacePendingUserOrAddLocked(String itemId, String text) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            if (message.getRole() == ChatMessage.Role.USER
                && message.getId().startsWith("local-user-")
                && message.getText().equals(text)) {
                messages.set(index, new ChatMessage(itemId, ChatMessage.Role.USER, text, false));
                return;
            }
        }
        upsertMessageLocked(new ChatMessage(itemId, ChatMessage.Role.USER, text, false));
    }

    private int findMessageLocked(String id) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index).getId().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void addBoundedMessageLocked(ChatMessage message) {
        messages.add(message);
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
        boundTotalMessagesLocked();
    }

    private void boundTotalMessagesLocked() {
        int total = 0;
        int firstRetained = messages.size();
        for (int index = messages.size() - 1; index >= 0; index--) {
            int next = total + messages.get(index).getText().length();
            if (next > MAX_HISTORY_CHARACTERS && firstRetained < messages.size()) {
                break;
            }
            total = next;
            firstRetained = index;
        }
        while (firstRetained > 0 && !messages.isEmpty()) {
            messages.remove(0);
            firstRetained--;
        }
    }

    private void addSystemMessageLocked(String text) {
        String safe = bounded(CrashReportFormatter.redact(text), MAX_ERROR_CHARACTERS);
        if (!safe.isEmpty()) {
            addBoundedMessageLocked(new ChatMessage(
                "local-system-" + localMessageIds.getAndIncrement(),
                ChatMessage.Role.SYSTEM,
                safe,
                false
            ));
        }
    }

    private static List<ChatMessage> parseHistory(Map<String, Object> thread) {
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        int totalCharacters = 0;
        for (Object turnValue : JsonCodec.optionalArray(thread.get("turns"))) {
            Map<String, Object> turn = JsonCodec.requireObject(turnValue, "thread turn");
            for (Object itemValue : JsonCodec.optionalArray(turn.get("items"))) {
                if (history.size() >= MAX_MESSAGES || totalCharacters >= MAX_HISTORY_CHARACTERS) {
                    return history;
                }
                Map<String, Object> item = JsonCodec.requireObject(itemValue, "thread item");
                String id = JsonCodec.optionalString(item.get("id"));
                String type = JsonCodec.optionalString(item.get("type"));
                String text = "";
                ChatMessage.Role role = ChatMessage.Role.SYSTEM;
                if ("userMessage".equals(type)) {
                    text = extractUserText(item);
                    role = ChatMessage.Role.USER;
                } else if ("agentMessage".equals(type)) {
                    text = JsonCodec.optionalString(item.get("text"));
                    role = ChatMessage.Role.ASSISTANT;
                }
                if (!id.isEmpty() && !text.isEmpty()) {
                    text = bounded(text, Math.min(
                        MAX_MESSAGE_CHARACTERS,
                        MAX_HISTORY_CHARACTERS - totalCharacters
                    ));
                    history.add(new ChatMessage(id, role, text, false));
                    totalCharacters += text.length();
                }
            }
        }
        return history;
    }

    private static String extractUserText(Map<String, Object> item) {
        StringBuilder text = new StringBuilder();
        for (Object inputValue : JsonCodec.optionalArray(item.get("content"))) {
            Map<String, Object> input = JsonCodec.requireObject(inputValue, "user input");
            if (!"text".equals(JsonCodec.optionalString(input.get("type")))) {
                continue;
            }
            String part = JsonCodec.optionalString(input.get("text"));
            if (!part.isEmpty()) {
                if (text.length() != 0) {
                    text.append('\n');
                }
                int remaining = MAX_MESSAGE_CHARACTERS - text.length();
                text.append(bounded(part, Math.max(0, remaining)));
                if (text.length() >= MAX_MESSAGE_CHARACTERS) {
                    break;
                }
            }
        }
        return text.toString();
    }

    private static String titleForThread(Map<String, Object> thread) {
        String title = JsonCodec.optionalString(thread.get("name"));
        if (title.isEmpty()) {
            title = JsonCodec.optionalString(thread.get("preview"));
        }
        title = firstLine(title).trim();
        if (title.isEmpty()) {
            String id = JsonCodec.optionalString(thread.get("id"));
            title = id.length() > 8 ? "Chat " + id.substring(id.length() - 8) : "Neuer Chat";
        }
        return bounded(title, 120);
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String boundedStream(String existing, String delta) {
        if (existing.length() >= MAX_MESSAGE_CHARACTERS) {
            return existing;
        }
        int remaining = MAX_MESSAGE_CHARACTERS - existing.length();
        if (delta.length() <= remaining) {
            return existing + delta;
        }
        String marker = "\n… Ausgabe gekürzt …";
        int content = Math.max(0, remaining - marker.length());
        return existing + delta.substring(0, Math.min(content, delta.length())) + marker;
    }

    private static String bounded(String value, int maximum) {
        String safe = value == null ? "" : value;
        if (maximum <= 0) {
            return "";
        }
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static boolean containsReasoningEffort(
        List<CodexReasoningOption> values,
        String effort
    ) {
        for (CodexReasoningOption value : values) {
            if (value.getEffort().equals(effort)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSafeCatalogToken(String value) {
        if (value == null || value.isEmpty() || value.length() > 160) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_' && character != '.'
                && character != ':' && character != '/') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 160) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isTrustedLoginUrl(String value) {
        if (value == null || value.length() > 8192) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || uri.getUserInfo() != null) {
                return false;
            }
            String lowerHost = host.toLowerCase(java.util.Locale.ROOT);
            return lowerHost.equals("openai.com") || lowerHost.endsWith(".openai.com")
                || lowerHost.equals("chatgpt.com") || lowerHost.endsWith(".chatgpt.com");
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static String safeError(Throwable error) {
        String message = error == null ? "Unbekannter Codex-Fehler" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "Unbekannter Codex-Fehler" : error.getClass().getSimpleName();
        }
        return bounded(CrashReportFormatter.redact(message), MAX_ERROR_CHARACTERS);
    }

    private void clearAccountLocked() {
        authMode = "";
        accountEmail = "";
        planType = "";
        loginPending = false;
        loginUrl = "";
        loginId = "";
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private interface Operation {
        void run() throws Exception;
    }
}
