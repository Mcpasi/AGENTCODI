package de.agentcodi.core;

import java.io.IOException;
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
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CodexSessionController
    implements CodexAppServerClient.Listener, CodexCatalogRpc, CodexMcpConfigurationRpc,
    AutoCloseable {
    public interface ConnectionFailureListener {
        void onConnectionFailed(CodexSessionController controller, Throwable error);
    }

    private static final long NORMAL_TIMEOUT_MS = 30_000L;
    private static final long INITIALIZE_TIMEOUT_MS = 20_000L;
    private static final int MAX_THREADS = 200;
    private static final int MAX_THREAD_PAGES = 4;
    private static final int MAX_MODELS = 50;
    private static final int MAX_REASONING_OPTIONS = 8;
    private static final int MAX_TRANSCRIPT_ITEMS = 240;
    private static final int MAX_MESSAGE_CHARACTERS = 256 * 1024;
    private static final int MAX_HISTORY_CHARACTERS = 1024 * 1024;
    private static final int MAX_CARD_SECTION_CHARACTERS = 64 * 1024;
    private static final int MAX_CARD_JSON_CHARACTERS = 16 * 1024;
    private static final int MAX_REASONING_PARTS = 64;
    private static final int MAX_ACTIVE_CARD_STREAMS = 32;
    private static final int MAX_PROMPT_CHARACTERS = 32 * 1024;
    private static final int MAX_ERROR_CHARACTERS = 600;
    private static final long MAX_RATE_LIMIT_WINDOW_MINUTES = 5_270_400L;
    private static final long MAX_RATE_LIMIT_RESET_SECONDS = 253_402_300_799L;
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
    private static final String REASONING_SUMMARY_DELTA_METHOD =
        "item/reasoning/summaryTextDelta";
    private static final String REASONING_SUMMARY_PART_ADDED_METHOD =
        "item/reasoning/summaryPartAdded";
    private static final String REASONING_TEXT_DELTA_METHOD =
        "item/reasoning/textDelta";
    private static final String PLAN_DELTA_METHOD = "item/plan/delta";
    private static final String COMMAND_OUTPUT_DELTA_METHOD =
        "item/commandExecution/outputDelta";
    private static final String TERMINAL_INTERACTION_METHOD =
        "item/commandExecution/terminalInteraction";
    private static final String FILE_CHANGE_OUTPUT_DELTA_METHOD =
        "item/fileChange/outputDelta";
    private static final String MCP_PROGRESS_METHOD = "item/mcpToolCall/progress";
    private static final String COMPACTED_IMAGE_RESULT =
        "<generated-image-data-omitted>";

    private final CodexAppServerClient client;
    private final CodexTerminalSession terminal;
    private final String workspacePath;
    private final ConnectionFailureListener connectionFailureListener;
    private final ExecutorService operations = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService interactiveResponses =
        Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong localMessageIds = new AtomicLong(1L);
    private final List<CodexModelOption> models = new ArrayList<CodexModelOption>();
    private final List<CodexThreadSummary> threads = new ArrayList<CodexThreadSummary>();
    private final List<CodexTranscriptItem> transcriptItems =
        new ArrayList<CodexTranscriptItem>();
    private final List<CodexInteractiveRequest> interactiveRequests =
        new ArrayList<CodexInteractiveRequest>();
    private final Map<String, List<CodexFileChangeSummary>> pendingFileChanges =
        new HashMap<String, List<CodexFileChangeSummary>>();
    private final Map<String, ReasoningAccumulator> reasoningStreams =
        new HashMap<String, ReasoningAccumulator>();
    private final Map<String, String> toolOutputStreams = new HashMap<String, String>();

    private long revision;
    private boolean ready;
    private boolean closed;
    private boolean connectionFailureReported;
    private String connectionMessage = "Codex App-Server startet.";
    private boolean requiresOpenaiAuth = true;
    private String authMode = "";
    private String accountEmail = "";
    private String planType = "";
    private CodexRateLimitsSnapshot rateLimits = CodexRateLimitsSnapshot.unavailable();
    private boolean rateLimitsRefreshQueued;
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
        this(transport, workspacePath, null);
    }

    public CodexSessionController(
        CodexRpcTransport transport,
        String workspacePath,
        ConnectionFailureListener connectionFailureListener
    ) {
        this(transport, workspacePath, connectionFailureListener, null);
    }

    public CodexSessionController(
        CodexRpcTransport transport,
        String workspacePath,
        ConnectionFailureListener connectionFailureListener,
        String terminalShellPath
    ) {
        if (workspacePath == null || workspacePath.trim().isEmpty()
            || !workspacePath.startsWith("/")) {
            throw new IllegalArgumentException("Workspace path must be absolute");
        }
        this.workspacePath = workspacePath;
        this.connectionFailureListener = connectionFailureListener;
        client = new CodexAppServerClient(transport, this);
        terminal = terminalShellPath == null
            ? null
            : new CodexTerminalSession(client, workspacePath, terminalShellPath);
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
                    "capabilities", JsonCodec.object(
                        "experimentalApi", Boolean.TRUE,
                        "optOutNotificationMethods", JsonCodec.array(
                            "rawResponseItem/completed",
                            "rawResponse/completed"
                        )
                    )
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

    @Override
    public synchronized String catalogThreadId() {
        return activeThreadId;
    }

    @Override
    public Map<String, Object> requestCatalog(
        String method,
        Map<String, Object> params,
        long timeoutMilliseconds
    ) throws Exception {
        if (!isReadOnlyCatalogMethod(method)) {
            throw new IllegalArgumentException("RPC is not part of the read-only catalog boundary");
        }
        if (timeoutMilliseconds <= 0L || timeoutMilliseconds > NORMAL_TIMEOUT_MS) {
            throw new IllegalArgumentException("Catalog RPC timeout is outside the allowed range");
        }
        synchronized (this) {
            if (closed || !ready) {
                throw new IllegalStateException("Codex app-server is not ready");
            }
        }
        return client.request(method, params, timeoutMilliseconds);
    }

    public static boolean isReadOnlyCatalogMethod(String method) {
        return "experimentalFeature/list".equals(method)
            || "skills/list".equals(method)
            || "mcpServerStatus/list".equals(method)
            || "app/installed".equals(method)
            || "app/read".equals(method)
            || "plugin/list".equals(method);
    }

    @Override
    public Map<String, Object> readMcpConfiguration(long timeoutMilliseconds)
        throws Exception {
        validateMcpConfigurationTimeout(timeoutMilliseconds);
        requireReadyMcpConfigurationClient();
        return client.request(
            "config/read",
            JsonCodec.object(
                "cwd", workspacePath,
                "includeLayers", Boolean.FALSE
            ),
            timeoutMilliseconds
        );
    }

    @Override
    public Map<String, Object> writeMcpConfiguration(
        Map<String, Object> parameters,
        long timeoutMilliseconds
    ) throws Exception {
        validateMcpConfigurationTimeout(timeoutMilliseconds);
        if (!CodexMcpConfigurationRequestValidator.isValidWrite(parameters)) {
            throw new IllegalArgumentException("MCP configuration write is outside the boundary");
        }
        requireReadyMcpConfigurationClient();
        return client.request("config/batchWrite", parameters, timeoutMilliseconds);
    }

    @Override
    public Map<String, Object> reloadMcpConfiguration(long timeoutMilliseconds)
        throws Exception {
        validateMcpConfigurationTimeout(timeoutMilliseconds);
        requireReadyMcpConfigurationClient();
        return client.request("config/mcpServer/reload", null, timeoutMilliseconds);
    }

    private static void validateMcpConfigurationTimeout(long timeoutMilliseconds) {
        if (timeoutMilliseconds <= 0L || timeoutMilliseconds > NORMAL_TIMEOUT_MS) {
            throw new IllegalArgumentException(
                "MCP configuration RPC timeout is outside the allowed range"
            );
        }
    }

    private void requireReadyMcpConfigurationClient() {
        synchronized (this) {
            if (closed || !ready) {
                throw new IllegalStateException("Codex app-server is not ready");
            }
        }
    }

    public TerminalSessionSnapshot terminalSnapshot() {
        return terminal == null ? TerminalSessionSnapshot.stopped() : terminal.snapshot();
    }

    public void startTerminal(int rows, int columns) {
        synchronized (this) {
            if (closed || !ready || terminal == null) {
                throw new IllegalStateException("Terminal runtime is not ready");
            }
        }
        terminal.start(rows, columns);
    }

    public void sendTerminalInput(char[] input) throws IOException {
        synchronized (this) {
            if (closed || !ready || terminal == null) {
                if (input != null) {
                    Arrays.fill(input, '\0');
                }
                throw new IOException("Terminal runtime is not ready");
            }
        }
        terminal.write(input);
    }

    public void resizeTerminal(int rows, int columns) {
        if (terminal != null) {
            terminal.resize(rows, columns);
        }
    }

    public void stopTerminal() {
        if (terminal != null) {
            terminal.stop();
        }
    }

    public void clearTerminalOutput() {
        if (terminal != null) {
            terminal.clearOutput();
        }
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
        submit("API-Schlüssel wird an Codex übergeben.", new Operation() {
            @Override
            public void run() throws Exception {
                synchronized (CodexSessionController.this) {
                    loginPending = true;
                    loginUrl = "";
                    loginId = "";
                    publishLocked();
                }
                client.requestApiKeyLogin(apiKey, NORMAL_TIMEOUT_MS);
                readAccountInternal();
                refreshModelsInternal();
                synchronized (CodexSessionController.this) {
                    loginPending = false;
                    operationMessage = "API-Schlüssel wurde im kanonischen Codex-Speicher abgelegt.";
                    publishLocked();
                }
            }

            @Override
            public void cancel() {
                wipe(apiKey);
            }
        });
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
                List<CodexTranscriptItem> history = parseHistory(thread);
                synchronized (CodexSessionController.this) {
                    activeThreadId = returnedId;
                    activeThreadTitle = titleForThread(thread);
                    updateSelectionFromThreadResponseLocked(result);
                    transcriptItems.clear();
                    transcriptItems.addAll(history);
                    turnActive = false;
                    activeTurnId = "";
                    lastCompletedTurnId = "";
                    interactiveRequests.clear();
                    pendingFileChanges.clear();
                    clearCardStreamsLocked();
                    operationMessage = "Chat ist geöffnet.";
                    publishLocked();
                }
            }
        });
    }

    public boolean sendMessage(final String input) {
        return sendMessage(input, Collections.<CodexFileMention>emptyList());
    }

    public boolean sendMessage(
        final String input,
        List<CodexFileMention> fileMentions
    ) {
        if (CredentialGuard.containsLikelyCredential(input)) {
            setUserError(
                "OpenAI-Zugangsdaten dürfen nur im geschützten Kontobereich eingegeben werden."
            );
            return false;
        }
        final String prompt = input == null ? "" : input.trim();
        final List<CodexFileMention> mentions;
        try {
            mentions = validateFileMentions(fileMentions);
        } catch (IllegalArgumentException error) {
            setUserError("Importierte Dateien konnten nicht sicher angehängt werden.");
            return false;
        }
        if ((prompt.isEmpty() && mentions.isEmpty())
            || prompt.length() > MAX_PROMPT_CHARACTERS) {
            setUserError(
                "Nachrichten benötigen Text oder importierte Dateien und dürfen höchstens "
                    + "32768 Textzeichen enthalten."
            );
            return false;
        }
        final List<Object> userInput = buildUserInput(prompt, mentions);
        final Map<String, Object> attachmentContext =
            CodexWorkspaceAttachmentContext.create(mentions);
        final String projectedUserText = extractUserText(JsonCodec.object(
            "content", userInput
        ));
        return submit("Nachricht wird gesendet.", new Operation() {
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
                        projectedUserText,
                        false
                    ));
                    turnActive = true;
                    activeTurnId = "";
                    publishLocked();
                }

                Map<String, Object> params = JsonCodec.object(
                    "threadId", threadId,
                    "input", userInput,
                    "cwd", workspacePath,
                    "runtimeWorkspaceRoots", JsonCodec.array(workspacePath),
                    "approvalPolicy", "on-request",
                    "permissions", WORKSPACE_PERMISSION_PROFILE,
                    "model", requestModel,
                    "effort", requestEffort,
                    "summary", "auto"
                );
                if (!attachmentContext.isEmpty()) {
                    params.put("additionalContext", attachmentContext);
                }
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

    public boolean steerTurn(final String input) {
        return steerTurn(input, Collections.<CodexFileMention>emptyList());
    }

    public boolean steerTurn(
        final String input,
        List<CodexFileMention> fileMentions
    ) {
        if (CredentialGuard.containsLikelyCredential(input)) {
            setUserError(
                "OpenAI-Zugangsdaten dürfen nur im geschützten Kontobereich eingegeben werden."
            );
            return false;
        }
        final String prompt = input == null ? "" : input.trim();
        final List<CodexFileMention> mentions;
        try {
            mentions = validateFileMentions(fileMentions);
        } catch (IllegalArgumentException error) {
            setUserError("Importierte Dateien konnten nicht sicher angehängt werden.");
            return false;
        }
        if ((prompt.isEmpty() && mentions.isEmpty())
            || prompt.length() > MAX_PROMPT_CHARACTERS) {
            setUserError(
                "Nachrichten benötigen Text oder importierte Dateien und dürfen höchstens "
                    + "32768 Textzeichen enthalten."
            );
            return false;
        }
        final List<Object> userInput = buildUserInput(prompt, mentions);
        final Map<String, Object> attachmentContext =
            CodexWorkspaceAttachmentContext.create(mentions);
        final String projectedUserText = extractUserText(JsonCodec.object(
            "content", userInput
        ));
        return submit("Ergänzung wird an den laufenden Turn gesendet.", new Operation() {
            @Override
            public void run() throws Exception {
                String threadId;
                String expectedTurnId;
                String localId;
                synchronized (CodexSessionController.this) {
                    if (requiresOpenaiAuth && authMode.isEmpty()) {
                        throw new IllegalStateException("Bitte zuerst anmelden.");
                    }
                    if (!turnActive || activeThreadId.isEmpty() || activeTurnId.isEmpty()) {
                        throw new IllegalStateException(
                            "Kein laufender Turn kann ergänzt werden."
                        );
                    }
                    threadId = activeThreadId;
                    expectedTurnId = activeTurnId;
                    localId = "local-user-" + localMessageIds.getAndIncrement();
                    addBoundedMessageLocked(new ChatMessage(
                        localId,
                        ChatMessage.Role.USER,
                        projectedUserText,
                        false
                    ));
                    publishLocked();
                }

                try {
                    Map<String, Object> params = JsonCodec.object(
                        "threadId", threadId,
                        "input", userInput,
                        "expectedTurnId", expectedTurnId
                    );
                    if (!attachmentContext.isEmpty()) {
                        params.put("additionalContext", attachmentContext);
                    }
                    Map<String, Object> result = client.request(
                        "turn/steer",
                        params,
                        NORMAL_TIMEOUT_MS
                    );
                    String returnedTurnId = JsonCodec.optionalString(result.get("turnId"));
                    if (!expectedTurnId.equals(returnedTurnId)) {
                        throw new IllegalArgumentException(
                            "Der App-Server hat einen anderen Turn bestätigt."
                        );
                    }
                    synchronized (CodexSessionController.this) {
                        operationMessage = "Ergänzung wurde in den laufenden Turn übernommen.";
                        publishLocked();
                    }
                } catch (Throwable error) {
                    synchronized (CodexSessionController.this) {
                        removeLocalMessageLocked(localId);
                        publishLocked();
                    }
                    if (error instanceof Exception) {
                        throw (Exception) error;
                    }
                    throw new IllegalStateException("Turn steer failed", error);
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
        if (terminal != null && terminal.onNotification(method, params)) {
            return;
        }
        if ("item/agentMessage/delta".equals(method)) {
            handleAgentDelta(params);
        } else if (REASONING_SUMMARY_DELTA_METHOD.equals(method)) {
            handleReasoningDelta(params, true);
        } else if (REASONING_SUMMARY_PART_ADDED_METHOD.equals(method)) {
            handleReasoningPartAdded(params);
        } else if (REASONING_TEXT_DELTA_METHOD.equals(method)) {
            handleReasoningDelta(params, false);
        } else if (PLAN_DELTA_METHOD.equals(method)) {
            handlePlanDelta(params);
        } else if (COMMAND_OUTPUT_DELTA_METHOD.equals(method)) {
            handleToolOutputDelta(params, "commandExecution");
        } else if (TERMINAL_INTERACTION_METHOD.equals(method)) {
            handleTerminalInteraction(params);
        } else if (FILE_CHANGE_OUTPUT_DELTA_METHOD.equals(method)) {
            handleToolOutputDelta(params, "fileChange");
        } else if (MCP_PROGRESS_METHOD.equals(method)) {
            handleMcpProgress(params);
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
        } else if ("account/rateLimits/updated".equals(method)) {
            JsonCodec.requireObject(
                params.get("rateLimits"),
                "account/rateLimits/updated rateLimits"
            );
            queueRateLimitsRefresh();
        } else if ("serverRequest/resolved".equals(method)) {
            handleServerRequestResolved(params);
        }
    }

    @Override
    public void onTransportClosed(Throwable error) {
        if (terminal != null) {
            terminal.onTransportClosed(error);
        }
        boolean notifyFailure;
        synchronized (this) {
            if (closed) {
                return;
            }
            ready = false;
            turnActive = false;
            activeTurnId = "";
            operationActive = false;
            rateLimitsRefreshQueued = false;
            interactiveRequests.clear();
            pendingFileChanges.clear();
            clearCardStreamsLocked();
            connectionMessage = "Verbindung zum Codex App-Server wurde beendet.";
            errorMessage = safeError(error);
            publishLocked();
            notifyFailure = !connectionFailureReported;
            connectionFailureReported = true;
        }
        shutdownOperationsNow();
        interactiveResponses.shutdownNow();
        if (notifyFailure) {
            notifyConnectionFailure(error);
        }
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
            clearCardStreamsLocked();
            connectionMessage = "Codex App-Server wurde gestoppt.";
            loginUrl = "";
            loginId = "";
            publishLocked();
        }
        shutdownOperationsNow();
        interactiveResponses.shutdownNow();
        if (terminal != null) {
            terminal.close();
        }
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
        boolean readRateLimits;
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
            readRateLimits = "chatgpt".equals(authMode);
            if (!readRateLimits) {
                rateLimits = CodexRateLimitsSnapshot.unavailable();
            }
            if (!authMode.isEmpty()) {
                loginPending = false;
                loginUrl = "";
                loginId = "";
            }
            publishLocked();
        }
        if (readRateLimits) {
            readRateLimitsOptionalInternal();
        }
    }

    private void readRateLimitsOptionalInternal() throws Exception {
        synchronized (this) {
            if (!"chatgpt".equals(authMode)) {
                rateLimits = CodexRateLimitsSnapshot.unavailable();
                return;
            }
        }
        try {
            Map<String, Object> result = client.request(
                "account/rateLimits/read",
                null,
                NORMAL_TIMEOUT_MS
            );
            CodexRateLimitsSnapshot parsed = parseRateLimits(result);
            synchronized (this) {
                if ("chatgpt".equals(authMode)) {
                    rateLimits = parsed;
                    publishLocked();
                }
            }
        } catch (CodexRpcException unavailable) {
            synchronized (this) {
                if ("chatgpt".equals(authMode)) {
                    rateLimits = CodexRateLimitsSnapshot.unavailable();
                    publishLocked();
                }
            }
        }
    }

    private static CodexRateLimitsSnapshot parseRateLimits(Map<String, Object> result) {
        Map<String, Object> value = JsonCodec.requireObject(
            result.get("rateLimits"),
            "account/rateLimits/read rateLimits"
        );
        return new CodexRateLimitsSnapshot(
            parseRateLimitWindow(value.get("primary"), "primary"),
            parseRateLimitWindow(value.get("secondary"), "secondary")
        );
    }

    private static CodexRateLimitWindow parseRateLimitWindow(Object value, String field) {
        if (value == null) {
            return null;
        }
        Map<String, Object> window = JsonCodec.requireObject(value, field + " rate-limit window");
        long usedPercent = requireProtocolInteger(
            window.get("usedPercent"),
            field + " usedPercent",
            0L,
            100L
        );
        long duration = optionalProtocolInteger(
            window.get("windowDurationMins"),
            field + " windowDurationMins",
            1L,
            MAX_RATE_LIMIT_WINDOW_MINUTES
        );
        long resetsAt = optionalProtocolInteger(
            window.get("resetsAt"),
            field + " resetsAt",
            0L,
            MAX_RATE_LIMIT_RESET_SECONDS
        );
        return new CodexRateLimitWindow((int) usedPercent, duration, resetsAt);
    }

    private static long requireProtocolInteger(
        Object value,
        String field,
        long minimum,
        long maximum
    ) {
        if (!(value instanceof Long)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        long number = ((Long) value).longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(field + " is outside its allowed range");
        }
        return number;
    }

    private static long optionalProtocolInteger(
        Object value,
        String field,
        long minimum,
        long maximum
    ) {
        return value == null
            ? CodexRateLimitWindow.UNKNOWN_VALUE
            : requireProtocolInteger(value, field, minimum, maximum);
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
            transcriptItems.clear();
            turnActive = false;
            activeTurnId = "";
            lastCompletedTurnId = "";
            interactiveRequests.clear();
            pendingFileChanges.clear();
            clearCardStreamsLocked();
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
            ChatMessage current = index < 0
                ? null
                : transcriptItems.get(index).getMessage();
            String existing = current == null ? "" : current.getText();
            String combined = boundedStream(existing, delta, MAX_MESSAGE_CHARACTERS);
            ChatMessage next = new ChatMessage(
                itemId,
                ChatMessage.Role.ASSISTANT,
                combined,
                true
            );
            if (index < 0) {
                addBoundedMessageLocked(next);
            } else {
                transcriptItems.set(index, CodexTranscriptItem.message(next));
                boundTranscriptLocked();
            }
            publishLocked();
        }
    }

    private void handleReasoningPartAdded(Map<String, Object> params) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        int partIndex = boundedIndex(params.get("summaryIndex"));
        if (!isSafeOpaqueIdentifier(itemId) || partIndex < 0) {
            return;
        }
        synchronized (this) {
            if (!acceptsCardStreamLocked(threadId, turnId, itemId)) {
                return;
            }
            ReasoningAccumulator accumulator = reasoningAccumulatorLocked(itemId);
            if (accumulator == null) {
                return;
            }
            accumulator.ensureSummaryPart(partIndex);
            upsertTranscriptItemLocked(reasoningCard(itemId, accumulator, true));
            publishLocked();
        }
    }

    private void handleReasoningDelta(Map<String, Object> params, boolean summary) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        String delta = JsonCodec.optionalString(params.get("delta"));
        int partIndex = boundedIndex(params.get(summary ? "summaryIndex" : "contentIndex"));
        if (!isSafeOpaqueIdentifier(itemId) || delta.isEmpty() || partIndex < 0) {
            return;
        }
        synchronized (this) {
            if (!acceptsCardStreamLocked(threadId, turnId, itemId)) {
                return;
            }
            ReasoningAccumulator accumulator = reasoningAccumulatorLocked(itemId);
            if (accumulator == null) {
                return;
            }
            accumulator.append(partIndex, delta, summary);
            upsertTranscriptItemLocked(reasoningCard(itemId, accumulator, true));
            publishLocked();
        }
    }

    private void handlePlanDelta(Map<String, Object> params) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        String delta = JsonCodec.optionalString(params.get("delta"));
        if (!isSafeOpaqueIdentifier(itemId) || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!acceptsCardStreamLocked(threadId, turnId, itemId)) {
                return;
            }
            String plan = appendCardStreamLocked(itemId, delta, "");
            if (plan == null) {
                return;
            }
            upsertTranscriptItemLocked(CodexTranscriptItem.card(
                itemId,
                CodexTranscriptItem.Kind.PLAN,
                "plan",
                "Plan",
                "",
                visibleText(plan, MAX_CARD_SECTION_CHARACTERS),
                "inProgress",
                true
            ));
            publishLocked();
        }
    }

    private void handleToolOutputDelta(Map<String, Object> params, String protocolType) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        String delta = JsonCodec.optionalString(params.get("delta"));
        if (!isSafeOpaqueIdentifier(itemId) || delta.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!acceptsCardStreamLocked(threadId, turnId, itemId)) {
                return;
            }
            String output = appendCardStreamLocked(itemId, delta, "");
            if (output == null) {
                return;
            }
            CodexTranscriptItem existing = cardByIdLocked(itemId);
            String title = "commandExecution".equals(protocolType)
                ? "Befehl" : "Dateiänderung";
            String summary = "";
            String detail = "";
            if (existing != null && protocolType.equals(existing.getProtocolType())) {
                title = existing.getTitle();
                summary = existing.getSummary();
                detail = existing.getDetail();
            }
            upsertTranscriptItemLocked(CodexTranscriptItem.card(
                itemId,
                CodexTranscriptItem.Kind.TOOL,
                protocolType,
                title,
                summary,
                detailWithStream(detail, "Ausgabe", output),
                "inProgress",
                true
            ));
            publishLocked();
        }
    }

    private void handleMcpProgress(Map<String, Object> params) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        String message = JsonCodec.optionalString(params.get("message"));
        if (!isSafeOpaqueIdentifier(itemId) || message.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!acceptsCardStreamLocked(threadId, turnId, itemId)) {
                return;
            }
            String progress = appendCardStreamLocked(itemId, message, "\n");
            if (progress == null) {
                return;
            }
            CodexTranscriptItem existing = cardByIdLocked(itemId);
            String summary = existing == null ? "" : existing.getSummary();
            String detail = existing == null ? "" : existing.getDetail();
            upsertTranscriptItemLocked(CodexTranscriptItem.card(
                itemId,
                CodexTranscriptItem.Kind.TOOL,
                "mcpToolCall",
                "MCP-Tool",
                summary,
                detailWithStream(detail, "Fortschritt", progress),
                "inProgress",
                true
            ));
            publishLocked();
        }
    }

    private void handleTerminalInteraction(Map<String, Object> params) {
        String threadId = JsonCodec.optionalString(params.get("threadId"));
        String turnId = JsonCodec.optionalString(params.get("turnId"));
        String itemId = JsonCodec.optionalString(params.get("itemId"));
        String stdin = JsonCodec.optionalString(params.get("stdin"));
        String processId = JsonCodec.optionalString(params.get("processId"));
        if (!isSafeOpaqueIdentifier(itemId) || stdin.isEmpty()) {
            return;
        }
        String interaction = processId.isEmpty()
            ? "[stdin] " + stdin
            : "[stdin " + processId + "] " + stdin;
        synchronized (this) {
            if (!acceptsCardStreamLocked(threadId, turnId, itemId)) {
                return;
            }
            String output = appendCardStreamLocked(itemId, interaction, "\n");
            if (output == null) {
                return;
            }
            CodexTranscriptItem existing = cardByIdLocked(itemId);
            upsertTranscriptItemLocked(CodexTranscriptItem.card(
                itemId,
                CodexTranscriptItem.Kind.TOOL,
                "commandExecution",
                "Befehl",
                existing == null ? "" : existing.getSummary(),
                detailWithStream(
                    existing == null ? "" : existing.getDetail(),
                    "Ausgabe",
                    output
                ),
                "inProgress",
                true
            ));
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
        if (!isSafeOpaqueIdentifier(itemId)) {
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
            } else {
                if (startedEvent && (isStaleStreamingEventLocked(turnId)
                    || isFinalCardLocked(itemId))) {
                    return;
                }
                boolean interactiveChanged = false;
                if ("fileChange".equals(type)) {
                    if (startedEvent) {
                        cacheFileChangesLocked(itemId, fileChanges);
                    }
                    interactiveChanged = !fileChanges.isEmpty()
                        && enrichInteractiveFileChangeLocked(itemId, fileChanges);
                }
                CodexTranscriptItem card = parseCardItem(
                    item,
                    startedEvent,
                    toolOutputStreams.get(itemId)
                );
                if (card == null) {
                    if (interactiveChanged) {
                        publishLocked();
                    }
                    return;
                }
                upsertTranscriptItemLocked(card);
                if (!startedEvent) {
                    reasoningStreams.remove(itemId);
                    toolOutputStreams.remove(itemId);
                    if ("fileChange".equals(type)) {
                        pendingFileChanges.remove(itemId);
                    }
                }
                publishLocked();
            }
        }
    }

    private CodexTranscriptItem parseCardItem(
        Map<String, Object> item,
        boolean startedEvent,
        String pendingOutput
    ) {
        String id = JsonCodec.optionalString(item.get("id"));
        String type = JsonCodec.optionalString(item.get("type"));
        if (!isSafeOpaqueIdentifier(id) || type.isEmpty()
            || "agentMessage".equals(type) || "userMessage".equals(type)) {
            return null;
        }
        String status = itemStatus(item, startedEvent);
        if ("reasoning".equals(type)) {
            String summary = joinTextArray(item.get("summary"), MAX_REASONING_PARTS);
            String content = joinTextArray(item.get("content"), MAX_REASONING_PARTS);
            if (startedEvent && summary.isEmpty() && content.isEmpty()) {
                ReasoningAccumulator accumulator = reasoningStreams.get(id);
                if (accumulator != null) {
                    return reasoningCard(id, accumulator, true);
                }
            }
            return CodexTranscriptItem.card(
                id,
                CodexTranscriptItem.Kind.REASONING,
                type,
                "Reasoning",
                summary,
                content,
                status,
                startedEvent
            );
        }
        if ("plan".equals(type)) {
            String text = visibleField(item.get("text"), MAX_CARD_SECTION_CHARACTERS);
            if (startedEvent && text.isEmpty()) {
                text = visibleText(pendingOutput, MAX_CARD_SECTION_CHARACTERS);
            }
            return CodexTranscriptItem.card(
                id,
                CodexTranscriptItem.Kind.PLAN,
                type,
                "Plan",
                "",
                text,
                status,
                startedEvent
            );
        }
        if ("commandExecution".equals(type)) {
            return commandCard(item, status, pendingOutput, startedEvent);
        }
        if ("fileChange".equals(type)) {
            List<CodexFileChangeSummary> changes = parseFileChangeSummaries(item);
            if (startedEvent && changes.isEmpty()) {
                List<CodexFileChangeSummary> cached = pendingFileChanges.get(id);
                if (cached != null) {
                    changes = cached;
                }
            }
            return fileChangeCard(id, changes, status, pendingOutput, startedEvent);
        }
        if ("mcpToolCall".equals(type)) {
            return mcpToolCard(item, status, pendingOutput, startedEvent);
        }
        if ("dynamicToolCall".equals(type)) {
            return dynamicToolCard(item, status, startedEvent);
        }
        if ("collabAgentToolCall".equals(type)) {
            return collabToolCard(item, status, startedEvent);
        }
        if ("subAgentActivity".equals(type)) {
            String path = visibleField(item.get("agentPath"), 2048);
            String thread = visibleField(item.get("agentThreadId"), 200);
            String activity = visibleField(item.get("kind"), 40);
            StringBuilder detail = new StringBuilder();
            appendField(detail, "Thread", thread);
            appendField(detail, "Aktivität", activity);
            return toolCard(id, type, "Subagent", path, detail.toString(), status, startedEvent);
        }
        if ("webSearch".equals(type)) {
            StringBuilder detail = new StringBuilder();
            appendField(detail, "Aktion", visibleJson(item.get("action")));
            appendField(detail, "Ergebnisse", visibleJson(item.get("results")));
            return toolCard(
                id,
                type,
                "Websuche",
                visibleField(item.get("query"), MAX_CARD_JSON_CHARACTERS),
                detail.toString(),
                status,
                startedEvent
            );
        }
        if ("imageView".equals(type)) {
            String imagePath = visibleField(item.get("path"), 4096);
            boolean hasImageCandidate = isImagePathCandidate(imagePath);
            CodexTranscriptItem card = toolCard(
                id,
                type,
                "Bildanzeige",
                imagePath,
                hasImageCandidate
                    ? "Die App prüft den gemeldeten Pfad gegen den tatsächlichen privaten Workspace."
                    : "",
                status,
                startedEvent
            );
            return hasImageCandidate
                ? card.withReportedImagePath(imagePath)
                : card;
        }
        if ("sleep".equals(type)) {
            long duration = nonNegativeLong(item.get("durationMs"));
            return toolCard(
                id,
                type,
                "Warten",
                duration < 0L ? "" : duration + " ms",
                "",
                status,
                startedEvent
            );
        }
        if ("imageGeneration".equals(type)) {
            StringBuilder detail = new StringBuilder();
            appendField(detail, "Überarbeiteter Prompt", visibleField(
                item.get("revisedPrompt"),
                MAX_CARD_JSON_CHARACTERS
            ));
            String savedPath = visibleField(item.get("savedPath"), 4096);
            appendField(detail, "Gemeldeter Speicherpfad", savedPath);
            String result = visibleField(item.get("result"), MAX_CARD_SECTION_CHARACTERS);
            appendField(
                detail,
                "Ergebnis",
                COMPACTED_IMAGE_RESULT.equals(result)
                    ? "Bild erzeugt; eingebettete Bilddaten wurden nicht in den UI-Zustand übernommen."
                    : result
            );
            boolean hasImageCandidate = isImagePathCandidate(savedPath);
            if (!savedPath.isEmpty()) {
                appendField(
                    detail,
                    "Export",
                    hasImageCandidate
                        ? "Die App prüft den gemeldeten Pfad kanonisch. Erst nach erfolgreicher Prüfung wird der Export freigeschaltet."
                        : "Nicht angeboten: Der gemeldete Pfad ist kein sicher prüfbarer absoluter Dateipfad."
                );
            }
            CodexTranscriptItem card = toolCard(
                id,
                type,
                "Bildgenerierung",
                "",
                detail.toString(),
                status,
                startedEvent
            );
            return hasImageCandidate ? card.withReportedImagePath(savedPath) : card;
        }
        if ("hookPrompt".equals(type)) {
            return toolCard(
                id,
                type,
                "Hook",
                "",
                hookFragments(item.get("fragments")),
                status,
                startedEvent
            );
        }
        if ("enteredReviewMode".equals(type) || "exitedReviewMode".equals(type)) {
            return toolCard(
                id,
                type,
                "Review-Modus",
                "enteredReviewMode".equals(type) ? "Gestartet" : "Beendet",
                visibleField(item.get("review"), MAX_CARD_SECTION_CHARACTERS),
                status,
                startedEvent
            );
        }
        if ("contextCompaction".equals(type)) {
            return toolCard(
                id,
                type,
                "Kontextkomprimierung",
                "Kontext wurde für den weiteren Turn verdichtet.",
                "",
                status,
                startedEvent
            );
        }
        return toolCard(
            id,
            type,
            "Tool-Aktivität",
            visibleText(type, 120),
            visibleJson(item),
            status,
            startedEvent
        );
    }

    private static CodexTranscriptItem commandCard(
        Map<String, Object> item,
        String status,
        String pendingOutput,
        boolean streaming
    ) {
        StringBuilder detail = new StringBuilder();
        appendField(detail, "Arbeitsverzeichnis", visibleField(item.get("cwd"), 4096));
        appendField(detail, "Quelle", visibleField(item.get("source"), 80));
        appendField(detail, "Aktionen", visibleJson(item.get("commandActions")));
        appendField(detail, "Plugin", visibleField(item.get("pluginId"), 200));
        appendField(detail, "Skript", visibleField(item.get("scriptPath"), 2048));
        long exitCode = numericLong(item.get("exitCode"));
        if (exitCode != Long.MIN_VALUE) {
            appendField(detail, "Exit-Code", Long.toString(exitCode));
        }
        long duration = nonNegativeLong(item.get("durationMs"));
        if (duration >= 0L) {
            appendField(detail, "Dauer", duration + " ms");
        }
        String output = visibleField(item.get("aggregatedOutput"), MAX_CARD_SECTION_CHARACTERS);
        if (output.isEmpty()) {
            output = visibleText(pendingOutput, MAX_CARD_SECTION_CHARACTERS);
        }
        if (!output.isEmpty()) {
            appendField(detail, "Ausgabe", output);
        }
        return toolCard(
            JsonCodec.optionalString(item.get("id")),
            "commandExecution",
            "Befehl",
            visibleField(item.get("command"), MAX_CARD_JSON_CHARACTERS),
            detail.toString(),
            status,
            streaming
        );
    }

    private static CodexTranscriptItem fileChangeCard(
        String id,
        List<CodexFileChangeSummary> changes,
        String status,
        String pendingOutput,
        boolean streaming
    ) {
        String detail = formatFileChanges(changes);
        String output = visibleText(pendingOutput, MAX_CARD_SECTION_CHARACTERS);
        if (!output.isEmpty()) {
            StringBuilder combined = new StringBuilder(detail);
            appendField(combined, "Ausgabe", output);
            detail = combined.toString();
        }
        String summary = changes.isEmpty()
            ? (streaming
                ? "Änderungsdetails werden vorbereitet."
                : "Keine darstellbaren Änderungsdetails.")
            : changes.size() + (changes.size() == 1 ? " Dateiänderung" : " Dateiänderungen");
        return toolCard(
            id,
            "fileChange",
            "Dateiänderung",
            summary,
            detail,
            status,
            streaming
        );
    }

    private static CodexTranscriptItem mcpToolCard(
        Map<String, Object> item,
        String status,
        String progress,
        boolean streaming
    ) {
        String server = visibleField(item.get("server"), 200);
        String tool = visibleField(item.get("tool"), 240);
        String summary = server.isEmpty() ? tool : server + (tool.isEmpty() ? "" : " · " + tool);
        StringBuilder detail = new StringBuilder();
        appendField(detail, "Argumente", visibleJson(item.get("arguments")));
        appendField(detail, "App-Kontext", visibleJson(item.get("appContext")));
        appendField(detail, "Fortschritt", visibleText(progress, MAX_CARD_SECTION_CHARACTERS));
        Map<String, Object> error = safeObject(item.get("error"));
        if (error != null) {
            appendField(detail, "Fehler", visibleField(error.get("message"), 4096));
        }
        appendField(detail, "Ergebnis", visibleJson(item.get("result")));
        long duration = nonNegativeLong(item.get("durationMs"));
        if (duration >= 0L) {
            appendField(detail, "Dauer", duration + " ms");
        }
        return toolCard(
            JsonCodec.optionalString(item.get("id")),
            "mcpToolCall",
            "MCP-Tool",
            summary,
            detail.toString(),
            status,
            streaming
        );
    }

    private static CodexTranscriptItem dynamicToolCard(
        Map<String, Object> item,
        String status,
        boolean streaming
    ) {
        String namespace = visibleField(item.get("namespace"), 160);
        String tool = visibleField(item.get("tool"), 240);
        String summary = namespace.isEmpty()
            ? tool : namespace + (tool.isEmpty() ? "" : "/" + tool);
        StringBuilder detail = new StringBuilder();
        appendField(detail, "Argumente", visibleJson(item.get("arguments")));
        appendField(detail, "Ausgabe", visibleJson(item.get("contentItems")));
        Object success = item.get("success");
        if (success instanceof Boolean) {
            appendField(detail, "Erfolg", ((Boolean) success).booleanValue() ? "Ja" : "Nein");
        }
        long duration = nonNegativeLong(item.get("durationMs"));
        if (duration >= 0L) {
            appendField(detail, "Dauer", duration + " ms");
        }
        return toolCard(
            JsonCodec.optionalString(item.get("id")),
            "dynamicToolCall",
            "Dynamisches Tool",
            summary,
            detail.toString(),
            status,
            streaming
        );
    }

    private static CodexTranscriptItem collabToolCard(
        Map<String, Object> item,
        String status,
        boolean streaming
    ) {
        StringBuilder detail = new StringBuilder();
        appendField(detail, "Prompt", visibleField(
            item.get("prompt"),
            MAX_CARD_SECTION_CHARACTERS
        ));
        appendField(detail, "Modell", visibleField(item.get("model"), 200));
        appendField(detail, "Denkstufe", visibleField(item.get("reasoningEffort"), 80));
        appendField(detail, "Sender", visibleField(item.get("senderThreadId"), 200));
        appendField(detail, "Empfänger", visibleJson(item.get("receiverThreadIds")));
        appendField(detail, "Agentenstatus", visibleJson(item.get("agentsStates")));
        return toolCard(
            JsonCodec.optionalString(item.get("id")),
            "collabAgentToolCall",
            "Agenten-Tool",
            visibleField(item.get("tool"), 120),
            detail.toString(),
            status,
            streaming
        );
    }

    private static CodexTranscriptItem toolCard(
        String id,
        String protocolType,
        String title,
        String summary,
        String detail,
        String status,
        boolean streaming
    ) {
        return CodexTranscriptItem.card(
            id,
            CodexTranscriptItem.Kind.TOOL,
            protocolType,
            title,
            visibleText(summary, MAX_CARD_SECTION_CHARACTERS),
            visibleText(detail, MAX_CARD_SECTION_CHARACTERS),
            visibleText(status, 80),
            streaming
        );
    }

    private boolean acceptsCardStreamLocked(String threadId, String turnId, String itemId) {
        return matchesActiveThread(threadId)
            && !isStaleStreamingEventLocked(turnId)
            && !isFinalCardLocked(itemId);
    }

    private ReasoningAccumulator reasoningAccumulatorLocked(String itemId) {
        ReasoningAccumulator existing = reasoningStreams.get(itemId);
        if (existing != null) {
            return existing;
        }
        if (reasoningStreams.size() + toolOutputStreams.size()
            >= MAX_ACTIVE_CARD_STREAMS) {
            return null;
        }
        ReasoningAccumulator created = new ReasoningAccumulator();
        reasoningStreams.put(itemId, created);
        return created;
    }

    private String appendCardStreamLocked(String itemId, String value, String separator) {
        String existing = toolOutputStreams.get(itemId);
        if (existing == null
            && reasoningStreams.size() + toolOutputStreams.size()
                >= MAX_ACTIVE_CARD_STREAMS) {
            return null;
        }
        if (existing == null) {
            existing = "";
        }
        String addition = existing.isEmpty() ? value : separator + value;
        String combined = boundedStream(
            existing,
            addition,
            MAX_CARD_SECTION_CHARACTERS
        );
        combined = visibleText(combined, MAX_CARD_SECTION_CHARACTERS);
        toolOutputStreams.put(itemId, combined);
        return combined;
    }

    private static CodexTranscriptItem reasoningCard(
        String itemId,
        ReasoningAccumulator accumulator,
        boolean streaming
    ) {
        return CodexTranscriptItem.card(
            itemId,
            CodexTranscriptItem.Kind.REASONING,
            "reasoning",
            "Reasoning",
            visibleText(accumulator.summaryText(), MAX_CARD_SECTION_CHARACTERS),
            visibleText(accumulator.contentText(), MAX_CARD_SECTION_CHARACTERS),
            streaming ? "inProgress" : "completed",
            streaming
        );
    }

    private static String detailWithStream(String existing, String label, String stream) {
        String marker = label + ":\n";
        String base = existing == null ? "" : existing;
        int markerIndex = base.indexOf(marker);
        if (markerIndex >= 0) {
            int prefixEnd = markerIndex;
            while (prefixEnd > 0 && base.charAt(prefixEnd - 1) == '\n') {
                prefixEnd--;
            }
            base = base.substring(0, prefixEnd);
        }
        StringBuilder detail = new StringBuilder(base);
        appendField(detail, label, visibleText(stream, MAX_CARD_SECTION_CHARACTERS));
        return visibleText(detail.toString(), MAX_CARD_SECTION_CHARACTERS);
    }

    private static String itemStatus(Map<String, Object> item, boolean startedEvent) {
        String status = visibleField(item.get("status"), 80);
        if (status.isEmpty()) {
            status = startedEvent ? "inProgress" : "completed";
        }
        return status;
    }

    private static int boundedIndex(Object value) {
        if (!(value instanceof Long)) {
            return -1;
        }
        long index = ((Long) value).longValue();
        return index >= 0L && index < MAX_REASONING_PARTS ? (int) index : -1;
    }

    private static String joinTextArray(Object value, int maximumEntries) {
        List<Object> entries;
        try {
            entries = JsonCodec.optionalArray(value);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        int count = Math.min(maximumEntries, entries.size());
        for (int index = 0; index < count; index++) {
            if (!(entries.get(index) instanceof String)) {
                continue;
            }
            String text = (String) entries.get(index);
            if (text.isEmpty()) {
                continue;
            }
            String addition = joined.length() == 0 ? text : "\n" + text;
            String combined = boundedStream(
                joined.toString(),
                addition,
                MAX_CARD_SECTION_CHARACTERS
            );
            joined.setLength(0);
            joined.append(combined);
            if (joined.length() >= MAX_CARD_SECTION_CHARACTERS) {
                break;
            }
        }
        return visibleText(joined.toString(), MAX_CARD_SECTION_CHARACTERS);
    }

    private static String hookFragments(Object value) {
        List<Object> entries;
        try {
            entries = JsonCodec.optionalArray(value);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int count = Math.min(32, entries.size());
        for (int index = 0; index < count; index++) {
            Map<String, Object> fragment = safeObject(entries.get(index));
            if (fragment != null) {
                appendField(result, "Hook", visibleField(
                    fragment.get("text"),
                    MAX_CARD_JSON_CHARACTERS
                ));
            }
        }
        return visibleText(result.toString(), MAX_CARD_SECTION_CHARACTERS);
    }

    private static String formatFileChanges(List<CodexFileChangeSummary> changes) {
        StringBuilder detail = new StringBuilder();
        for (CodexFileChangeSummary change : changes) {
            String kind;
            if ("add".equals(change.getKind())) {
                kind = "HINZUFÜGEN";
            } else if ("delete".equals(change.getKind())) {
                kind = "LÖSCHEN";
            } else {
                kind = "ÄNDERN";
            }
            StringBuilder heading = new StringBuilder(kind)
                .append(" · ")
                .append(visibleText(change.getPath(), 4096));
            if (!change.getMovePath().isEmpty()) {
                heading.append(" → ").append(visibleText(change.getMovePath(), 4096));
            }
            String diff = visibleText(change.getDiff(), MAX_FILE_CHANGE_DIFF_CHARACTERS);
            appendField(
                detail,
                heading.toString(),
                diff.isEmpty() ? "Kein Text-Diff vorhanden." : diff
            );
            if (detail.length() >= MAX_CARD_SECTION_CHARACTERS) {
                break;
            }
        }
        return visibleText(detail.toString(), MAX_CARD_SECTION_CHARACTERS);
    }

    private static void appendField(StringBuilder output, String label, String value) {
        if (value == null || value.isEmpty() || output.length() >= MAX_CARD_SECTION_CHARACTERS) {
            return;
        }
        if (output.length() != 0) {
            output.append("\n\n");
        }
        output.append(label).append(":\n");
        int remaining = MAX_CARD_SECTION_CHARACTERS - output.length();
        output.append(bounded(value, Math.max(0, remaining)));
    }

    private static String visibleField(Object value, int maximumCharacters) {
        return value instanceof String
            ? visibleText((String) value, maximumCharacters)
            : "";
    }

    private static String visibleJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return visibleText(JsonCodec.stringify(value), MAX_CARD_JSON_CHARACTERS);
        } catch (IllegalArgumentException ignored) {
            return "Inhalt konnte nicht sicher dargestellt werden.";
        }
    }

    private static String visibleText(String value, int maximumCharacters) {
        if (value == null || maximumCharacters <= 0) {
            return "";
        }
        String withoutNulls = value.indexOf('\0') < 0 ? value : value.replace("\0", "");
        return CrashReportFormatter.redactVisibleText(withoutNulls, maximumCharacters);
    }

    private static Map<String, Object> safeObject(Object value) {
        try {
            return JsonCodec.optionalObject(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long numericLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : Long.MIN_VALUE;
    }

    private static long nonNegativeLong(Object value) {
        long number = numericLong(value);
        return number >= 0L ? number : -1L;
    }

    private void clearCardStreamsLocked() {
        reasoningStreams.clear();
        toolOutputStreams.clear();
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
            boolean changed = enrichInteractiveFileChangeLocked(itemId, changes);
            if (!isFinalCardLocked(itemId)) {
                upsertTranscriptItemLocked(fileChangeCard(
                    itemId,
                    changes,
                    "inProgress",
                    toolOutputStreams.get(itemId),
                    true
                ));
                changed = true;
            }
            if (changed) {
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
            String status = JsonCodec.optionalString(turn.get("status"));
            finishStreamingTranscriptLocked(status);
            clearCardStreamsLocked();
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

    private void queueRateLimitsRefresh() {
        synchronized (this) {
            if (closed || !ready || !"chatgpt".equals(authMode)
                || rateLimitsRefreshQueued) {
                return;
            }
            rateLimitsRefreshQueued = true;
        }
        submitSilently(new Operation() {
            @Override
            public void run() throws Exception {
                readRateLimitsOptionalInternal();
            }

            @Override
            public void cancel() {
                synchronized (CodexSessionController.this) {
                    rateLimitsRefreshQueued = false;
                }
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
                operation.cancel();
                errorMessage = "Codex App-Server ist nicht bereit.";
                publishLocked();
                return false;
            }
            if (operationActive) {
                operation.cancel();
                errorMessage = "Eine andere Codex-Aktion läuft bereits.";
                publishLocked();
                return false;
            }
            operationActive = true;
            operationMessage = status;
            errorMessage = "";
            publishLocked();
        }
        OperationTask task = new OperationTask(operation, true);
        try {
            operations.execute(task);
            return true;
        } catch (RejectedExecutionException error) {
            task.cancelBeforeRun();
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
                operation.cancel();
                return;
            }
        }
        OperationTask task = new OperationTask(operation, false);
        try {
            operations.execute(task);
        } catch (RejectedExecutionException ignored) {
            task.cancelBeforeRun();
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
            if (CredentialGuard.containsLikelyCredential(value)) {
                throw new IllegalArgumentException(
                    "OpenAI-Zugangsdaten dürfen nur im geschützten Kontobereich eingegeben werden."
                );
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

    private void handleInteractiveResponseFailure(Throwable error) {
        boolean notifyFailure;
        synchronized (this) {
            if (closed) {
                return;
            }
            ready = false;
            turnActive = false;
            activeTurnId = "";
            operationActive = false;
            interactiveRequests.clear();
            pendingFileChanges.clear();
            clearCardStreamsLocked();
            connectionMessage = "Eine Antwort an den Codex App-Server ist fehlgeschlagen.";
            errorMessage = safeError(error);
            publishLocked();
            notifyFailure = !connectionFailureReported;
            connectionFailureReported = true;
        }
        shutdownOperationsNow();
        interactiveResponses.shutdownNow();
        if (terminal != null) {
            terminal.close();
        }
        client.close();
        if (notifyFailure) {
            notifyConnectionFailure(error);
        }
    }

    private void notifyConnectionFailure(Throwable error) {
        if (connectionFailureListener == null) {
            return;
        }
        try {
            connectionFailureListener.onConnectionFailed(this, error);
        } catch (Throwable ignored) {
            // A host callback must not obscure or revive a failed transport.
        }
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

    private static boolean isImagePathCandidate(String value) {
        return value != null
            && !value.isEmpty()
            && value.startsWith("/")
            && value.indexOf('\0') < 0
            && value.indexOf('\n') < 0
            && value.indexOf('\r') < 0;
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

    private void shutdownOperationsNow() {
        List<Runnable> pending = operations.shutdownNow();
        for (Runnable task : pending) {
            if (task instanceof OperationTask) {
                ((OperationTask) task).cancelBeforeRun();
            }
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
            rateLimits,
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
            transcriptItems,
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
        ChatMessage message = transcriptItems.get(index).getMessage();
        return message.getRole() == ChatMessage.Role.ASSISTANT && !message.isStreaming();
    }

    private boolean isFinalCardLocked(String itemId) {
        CodexTranscriptItem item = cardByIdLocked(itemId);
        return item != null && !item.isStreaming();
    }

    private CodexTranscriptItem cardByIdLocked(String itemId) {
        int index = findTranscriptItemLocked(itemId);
        if (index < 0 || transcriptItems.get(index).isMessage()) {
            return null;
        }
        return transcriptItems.get(index);
    }

    private void finishStreamingTranscriptLocked(String turnStatus) {
        String cardStatus = "interrupted".equals(turnStatus)
            ? "interrupted"
            : "failed".equals(turnStatus) ? "failed" : "completed";
        for (int index = 0; index < transcriptItems.size(); index++) {
            CodexTranscriptItem item = transcriptItems.get(index);
            if (item.isStreaming()) {
                transcriptItems.set(index, item.finish(
                    item.isMessage() ? "" : cardStatus
                ));
            }
        }
        boundTranscriptLocked();
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
            transcriptItems.set(index, CodexTranscriptItem.message(value));
            boundTranscriptLocked();
        }
    }

    private void replacePendingUserOrAddLocked(String itemId, String text) {
        for (int index = transcriptItems.size() - 1; index >= 0; index--) {
            CodexTranscriptItem item = transcriptItems.get(index);
            if (!item.isMessage()) {
                continue;
            }
            ChatMessage message = item.getMessage();
            if (message.getRole() == ChatMessage.Role.USER
                && message.getId().startsWith("local-user-")
                && message.getText().equals(text)) {
                transcriptItems.set(index, CodexTranscriptItem.message(new ChatMessage(
                    itemId,
                    ChatMessage.Role.USER,
                    text,
                    false
                )));
                return;
            }
        }
        upsertMessageLocked(new ChatMessage(itemId, ChatMessage.Role.USER, text, false));
    }

    private void removeLocalMessageLocked(String itemId) {
        for (int index = transcriptItems.size() - 1; index >= 0; index--) {
            CodexTranscriptItem item = transcriptItems.get(index);
            if (item.isMessage() && item.getId().equals(itemId)) {
                transcriptItems.remove(index);
                return;
            }
        }
    }

    private int findMessageLocked(String id) {
        for (int index = 0; index < transcriptItems.size(); index++) {
            CodexTranscriptItem item = transcriptItems.get(index);
            if (item.isMessage() && item.getId().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void addBoundedMessageLocked(ChatMessage message) {
        transcriptItems.add(CodexTranscriptItem.message(message));
        boundTranscriptLocked();
    }

    private void upsertTranscriptItemLocked(CodexTranscriptItem value) {
        int index = findTranscriptItemLocked(value.getId());
        if (index < 0) {
            transcriptItems.add(value);
        } else {
            transcriptItems.set(index, value);
        }
        boundTranscriptLocked();
    }

    private int findTranscriptItemLocked(String id) {
        for (int index = 0; index < transcriptItems.size(); index++) {
            if (transcriptItems.get(index).getId().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void boundTranscriptLocked() {
        while (transcriptItems.size() > MAX_TRANSCRIPT_ITEMS) {
            transcriptItems.remove(0);
        }
        int total = 0;
        int firstRetained = transcriptItems.size();
        for (int index = transcriptItems.size() - 1; index >= 0; index--) {
            int next = total + transcriptItems.get(index).getVisibleCharacterCount();
            if (next > MAX_HISTORY_CHARACTERS && firstRetained < transcriptItems.size()) {
                break;
            }
            total = next;
            firstRetained = index;
        }
        while (firstRetained > 0 && !transcriptItems.isEmpty()) {
            transcriptItems.remove(0);
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

    private List<CodexTranscriptItem> parseHistory(Map<String, Object> thread) {
        List<CodexTranscriptItem> history = new ArrayList<CodexTranscriptItem>();
        for (Object turnValue : JsonCodec.optionalArray(thread.get("turns"))) {
            Map<String, Object> turn = JsonCodec.requireObject(turnValue, "thread turn");
            for (Object itemValue : JsonCodec.optionalArray(turn.get("items"))) {
                Map<String, Object> item = JsonCodec.requireObject(itemValue, "thread item");
                String id = JsonCodec.optionalString(item.get("id"));
                String type = JsonCodec.optionalString(item.get("type"));
                if (!isSafeOpaqueIdentifier(id)) {
                    continue;
                }
                if ("userMessage".equals(type)) {
                    String text = extractUserText(item);
                    if (!text.isEmpty()) {
                        addHistoryItem(history, CodexTranscriptItem.message(new ChatMessage(
                            id,
                            ChatMessage.Role.USER,
                            text,
                            false
                        )));
                    }
                } else if ("agentMessage".equals(type)) {
                    String text = bounded(
                        JsonCodec.optionalString(item.get("text")),
                        MAX_MESSAGE_CHARACTERS
                    );
                    if (!text.isEmpty()) {
                        addHistoryItem(history, CodexTranscriptItem.message(new ChatMessage(
                            id,
                            ChatMessage.Role.ASSISTANT,
                            text,
                            false
                        )));
                    }
                } else {
                    CodexTranscriptItem card = parseCardItem(item, false, null);
                    if (card != null) {
                        addHistoryItem(history, card);
                    }
                }
            }
        }
        return history;
    }

    private static void addHistoryItem(
        List<CodexTranscriptItem> history,
        CodexTranscriptItem item
    ) {
        history.add(item);
        while (history.size() > MAX_TRANSCRIPT_ITEMS) {
            history.remove(0);
        }
        int total = 0;
        int firstRetained = history.size();
        for (int index = history.size() - 1; index >= 0; index--) {
            int next = total + history.get(index).getVisibleCharacterCount();
            if (next > MAX_HISTORY_CHARACTERS && firstRetained < history.size()) {
                break;
            }
            total = next;
            firstRetained = index;
        }
        while (firstRetained > 0) {
            history.remove(0);
            firstRetained--;
        }
    }

    private List<CodexFileMention> validateFileMentions(
        List<CodexFileMention> values
    ) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        if (values.size() > CodexFileMention.MAXIMUM_MENTIONS) {
            throw new IllegalArgumentException("Too many Codex file mentions");
        }
        String workspace = workspacePath.endsWith("/")
            ? workspacePath.substring(0, workspacePath.length() - 1)
            : workspacePath;
        String prefix = workspace + "/imports/";
        Set<String> paths = new HashSet<String>();
        List<CodexFileMention> mentions =
            new ArrayList<CodexFileMention>(values.size());
        for (CodexFileMention value : values) {
            if (value == null || !value.getPath().startsWith(prefix)) {
                throw new IllegalArgumentException(
                    "Codex file mention is outside the workspace imports directory"
                );
            }
            String fileName = value.getPath().substring(prefix.length());
            if (fileName.isEmpty() || ".".equals(fileName) || "..".equals(fileName)
                || fileName.indexOf('/') >= 0 || fileName.indexOf(':') >= 0
                || !isGeneratedImportStorageName(fileName)
                || !paths.add(value.getPath())) {
                throw new IllegalArgumentException("Codex file mention path is unsafe");
            }
            mentions.add(value);
        }
        return Collections.unmodifiableList(mentions);
    }

    private static boolean isGeneratedImportStorageName(String fileName) {
        if (fileName.length() < 32 || fileName.length() > 45) {
            return false;
        }
        for (int index = 0; index < 32; index++) {
            char character = fileName.charAt(index);
            if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        if (fileName.length() == 32) {
            return true;
        }
        if (fileName.charAt(32) != '.' || fileName.length() == 33) {
            return false;
        }
        for (int index = 33; index < fileName.length(); index++) {
            char character = fileName.charAt(index);
            if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'z')) {
                return false;
            }
        }
        return true;
    }

    private static List<Object> buildUserInput(
        String prompt,
        List<CodexFileMention> mentions
    ) {
        List<Object> input = new ArrayList<Object>(mentions.size() + 1);
        if (!prompt.isEmpty()) {
            input.add(JsonCodec.object("type", "text", "text", prompt));
        }
        for (CodexFileMention mention : mentions) {
            input.add(JsonCodec.object(
                "type", "mention",
                "name", mention.getName(),
                "path", mention.getPath()
            ));
        }
        return Collections.unmodifiableList(input);
    }

    private static String extractUserText(Map<String, Object> item) {
        StringBuilder text = new StringBuilder();
        for (Object inputValue : JsonCodec.optionalArray(item.get("content"))) {
            Map<String, Object> input = JsonCodec.requireObject(inputValue, "user input");
            String type = JsonCodec.optionalString(input.get("type"));
            String part = "";
            if ("text".equals(type)) {
                part = JsonCodec.optionalString(input.get("text"));
            } else if ("mention".equals(type)) {
                part = visibleNamedInput("@", JsonCodec.optionalString(input.get("name")));
            } else if ("skill".equals(type)) {
                part = visibleNamedInput("$", JsonCodec.optionalString(input.get("name")));
            } else if ("localImage".equals(type) || "localAudio".equals(type)) {
                part = visibleNamedInput(
                    "@",
                    fileNameFromLocalInput(JsonCodec.optionalString(input.get("path")))
                );
            }
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

    private static String visibleNamedInput(String prefix, String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        for (int index = 0;
             index < name.length() && safe.length() < CodexFileMention.MAXIMUM_NAME_CHARACTERS;
             index++) {
            char character = name.charAt(index);
            if (character < 0x20 || character == 0x7f
                || character == '/' || character == '\\') {
                return "";
            }
            safe.append(character);
        }
        return safe.length() == 0 ? "" : prefix + safe.toString();
    }

    private static String fileNameFromLocalInput(String path) {
        if (path == null || path.isEmpty() || path.length() > 4096
            || path.indexOf('\\') >= 0) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
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

    private static String boundedStream(String existing, String delta, int maximumCharacters) {
        if (existing.length() >= maximumCharacters) {
            return existing;
        }
        int remaining = maximumCharacters - existing.length();
        if (delta.length() <= remaining) {
            return existing + delta;
        }
        String marker = "\n… Ausgabe gekürzt …";
        if (remaining <= marker.length()) {
            return existing + marker.substring(0, remaining);
        }
        int content = remaining - marker.length();
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
        rateLimits = CodexRateLimitsSnapshot.unavailable();
        rateLimitsRefreshQueued = false;
        loginPending = false;
        loginUrl = "";
        loginId = "";
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static final class ReasoningAccumulator {
        private final Map<Integer, String> summaryParts = new TreeMap<Integer, String>();
        private final Map<Integer, String> contentParts = new TreeMap<Integer, String>();

        private void ensureSummaryPart(int index) {
            if (!summaryParts.containsKey(Integer.valueOf(index))) {
                summaryParts.put(Integer.valueOf(index), "");
            }
        }

        private void append(int index, String delta, boolean summary) {
            Map<Integer, String> parts = summary ? summaryParts : contentParts;
            Integer key = Integer.valueOf(index);
            String existing = parts.get(key);
            int otherCharacters = totalCharacters(parts)
                - (existing == null ? 0 : existing.length());
            int maximumForPart = Math.max(
                0,
                MAX_CARD_SECTION_CHARACTERS - otherCharacters
            );
            String combined = boundedStream(
                existing == null ? "" : existing,
                delta,
                maximumForPart
            );
            parts.put(key, visibleText(combined, maximumForPart));
        }

        private String summaryText() {
            return joinParts(summaryParts);
        }

        private String contentText() {
            return joinParts(contentParts);
        }

        private static String joinParts(Map<Integer, String> parts) {
            StringBuilder result = new StringBuilder();
            for (String part : parts.values()) {
                if (part.isEmpty()) {
                    continue;
                }
                String addition = result.length() == 0 ? part : "\n" + part;
                String combined = boundedStream(
                    result.toString(),
                    addition,
                    MAX_CARD_SECTION_CHARACTERS
                );
                result.setLength(0);
                result.append(combined);
                if (result.length() >= MAX_CARD_SECTION_CHARACTERS) {
                    break;
                }
            }
            return result.toString();
        }

        private static int totalCharacters(Map<Integer, String> parts) {
            int total = 0;
            for (String part : parts.values()) {
                total += part.length();
            }
            return total;
        }
    }

    private final class OperationTask implements Runnable {
        private final Operation operation;
        private final boolean activeOperation;
        private boolean started;

        private OperationTask(Operation operation, boolean activeOperation) {
            this.operation = operation;
            this.activeOperation = activeOperation;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (started) {
                    return;
                }
                started = true;
            }
            try {
                operation.run();
            } catch (Throwable error) {
                synchronized (CodexSessionController.this) {
                    errorMessage = safeError(error);
                    if (activeOperation && loginPending && loginUrl.isEmpty()) {
                        loginPending = false;
                    }
                    if (!activeOperation) {
                        publishLocked();
                    }
                }
            } finally {
                operation.cancel();
                if (activeOperation) {
                    synchronized (CodexSessionController.this) {
                        operationActive = false;
                        publishLocked();
                    }
                }
            }
        }

        private synchronized void cancelBeforeRun() {
            if (!started) {
                operation.cancel();
                started = true;
            }
        }
    }

    private interface Operation {
        void run() throws Exception;

        default void cancel() {
        }
    }
}
