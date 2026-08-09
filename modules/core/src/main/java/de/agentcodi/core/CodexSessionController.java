package de.agentcodi.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    private static final String WORKSPACE_PERMISSION_PROFILE = "agentcodi-workspace";
    private static final String OPENAI_HTTP_MODEL_PROVIDER = "agentcodi-openai-http";

    private final CodexAppServerClient client;
    private final String workspacePath;
    private final ExecutorService operations = Executors.newSingleThreadExecutor();
    private final AtomicLong localMessageIds = new AtomicLong(1L);
    private final List<CodexModelOption> models = new ArrayList<CodexModelOption>();
    private final List<CodexThreadSummary> threads = new ArrayList<CodexThreadSummary>();
    private final List<ChatMessage> messages = new ArrayList<ChatMessage>();

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
                Map<String, Object> result = client.request(
                    "thread/resume",
                    JsonCodec.object(
                        "threadId", threadId,
                        "modelProvider", OPENAI_HTTP_MODEL_PROVIDER,
                        "cwd", workspacePath,
                        "runtimeWorkspaceRoots", JsonCodec.array(workspacePath),
                        "approvalPolicy", "never",
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
                    "approvalPolicy", "never",
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

    @Override
    public void onNotification(String method, Map<String, Object> params) {
        if ("item/agentMessage/delta".equals(method)) {
            handleAgentDelta(params);
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
        connectionMessage = "Verbindung zum Codex App-Server wurde beendet.";
        errorMessage = safeError(error);
        publishLocked();
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
            connectionMessage = "Codex App-Server wurde gestoppt.";
            loginUrl = "";
            loginId = "";
            publishLocked();
        }
        operations.shutdownNow();
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
            "approvalPolicy", "never",
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
