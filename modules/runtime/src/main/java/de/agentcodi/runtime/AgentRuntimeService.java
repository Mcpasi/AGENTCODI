package de.agentcodi.runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import de.agentcodi.core.BuildIdentity;
import de.agentcodi.core.CodexApprovalDecision;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.RuntimeStateMachine;
import de.agentcodi.core.TerminalSessionSnapshot;
import de.agentcodi.mcp.McpCatalogSnapshot;
import de.agentcodi.mcp.McpConfigurationSnapshot;
import de.agentcodi.mcp.McpServerDraft;
import de.agentcodi.mcp.client.McpCatalogController;
import de.agentcodi.mcp.client.McpConfigurationController;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentRuntimeService extends Service {
    private static final String TAG = "AgentCodiRuntime";
    private static final String CHANNEL_ID = "agentcodi-runtime";
    private static final int NOTIFICATION_ID = 1001;
    private static final RuntimeStateMachine STATE = new RuntimeStateMachine();
    private static final AtomicBoolean BOOTSTRAP_ACTIVE = new AtomicBoolean(false);
    private static final CodexSessionSnapshot STOPPED_SESSION = CodexSessionSnapshot.stopped();
    private static final TerminalSessionSnapshot STOPPED_TERMINAL =
        TerminalSessionSnapshot.stopped();
    private static final McpCatalogSnapshot STOPPED_MCP_CATALOG =
        McpCatalogSnapshot.stopped();
    private static final McpConfigurationSnapshot STOPPED_MCP_CONFIGURATION =
        McpConfigurationSnapshot.stopped();
    private static final Object SESSION_LOCK = new Object();
    private static volatile CodexSessionController sessionController;
    private static volatile McpCatalogController mcpCatalogController;
    private static volatile McpConfigurationController mcpConfigurationController;
    private static volatile WorkspaceLayout activeWorkspaceLayout;
    private static volatile AgentRuntimeService activeService;
    private volatile Thread bootstrapThread;
    private volatile String notificationTextKey = RuntimeText.NOTIFICATION_STARTING;

    public static RuntimeSnapshot snapshot() {
        return STATE.snapshot();
    }

    public static CodexSessionSnapshot sessionSnapshot() {
        CodexSessionController controller = sessionController;
        return controller == null ? STOPPED_SESSION : controller.snapshot();
    }

    public static TerminalSessionSnapshot terminalSnapshot() {
        CodexSessionController controller = sessionController;
        return controller == null ? STOPPED_TERMINAL : controller.terminalSnapshot();
    }

    public static McpCatalogSnapshot mcpCatalogSnapshot() {
        McpCatalogController controller = mcpCatalogController;
        return controller == null ? STOPPED_MCP_CATALOG : controller.snapshot();
    }

    public static boolean refreshMcpCatalog() {
        McpCatalogController controller = mcpCatalogController;
        return controller != null && controller.refresh();
    }

    public static McpConfigurationSnapshot mcpConfigurationSnapshot() {
        McpConfigurationController controller = mcpConfigurationController;
        return controller == null ? STOPPED_MCP_CONFIGURATION : controller.snapshot();
    }

    public static boolean refreshMcpConfiguration() {
        McpConfigurationController controller = mcpConfigurationController;
        return controller != null && controller.refresh();
    }

    public static boolean saveMcpServer(McpServerDraft draft) {
        McpConfigurationController controller = mcpConfigurationController;
        return controller != null && controller.save(draft);
    }

    public static boolean setMcpServerEnabled(String name, boolean enabled) {
        McpConfigurationController controller = mcpConfigurationController;
        return controller != null && controller.setEnabled(name, enabled);
    }

    public static boolean deleteMcpServer(String name) {
        McpConfigurationController controller = mcpConfigurationController;
        return controller != null && controller.delete(name);
    }

    public static boolean reloadMcpConfiguration() {
        McpConfigurationController controller = mcpConfigurationController;
        return controller != null && controller.reload();
    }

    public static boolean startTerminal(int rows, int columns) {
        CodexSessionController controller = sessionController;
        if (controller == null) {
            return false;
        }
        try {
            controller.startTerminal(rows, columns);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static boolean isNodeRuntimeEnabled() {
        WorkspaceLayout layout = activeWorkspaceLayout;
        if (layout == null) {
            return false;
        }
        try {
            return layout.isNodeRuntimeEnabled(BuildIdentity.NODE_RUNTIME_VERSION);
        } catch (IOException error) {
            return false;
        }
    }

    public static boolean isNpmRuntimeEnabled() {
        WorkspaceLayout layout = activeWorkspaceLayout;
        if (layout == null) {
            return false;
        }
        try {
            return layout.isNpmRuntimeEnabled(BuildIdentity.NPM_RUNTIME_VERSION);
        } catch (IOException error) {
            return false;
        }
    }

    public static boolean isPythonRuntimeEnabled() {
        WorkspaceLayout layout = activeWorkspaceLayout;
        if (layout == null) {
            return false;
        }
        try {
            return layout.isPythonRuntimeEnabled(BuildIdentity.PYTHON_RUNTIME_VERSION);
        } catch (IOException error) {
            return false;
        }
    }

    public static void sendTerminalInput(char[] input) throws java.io.IOException {
        CodexSessionController controller = sessionController;
        if (controller == null) {
            if (input != null) {
                Arrays.fill(input, '\0');
            }
            throw new java.io.IOException("Terminal runtime is not ready");
        }
        controller.sendTerminalInput(input);
    }

    public static void resizeTerminal(int rows, int columns) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.resizeTerminal(rows, columns);
        }
    }

    public static void stopTerminal() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.stopTerminal();
        }
    }

    public static void clearTerminalOutput() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.clearTerminalOutput();
        }
    }

    public static void refreshAccountAndThreads() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.refreshAccountAndThreads();
        }
    }

    public static void startChatGptLogin() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.startChatGptLogin();
        }
    }

    public static void startApiKeyLogin(char[] apiKey) {
        CodexSessionController controller = sessionController;
        if (controller == null) {
            if (apiKey != null) {
                Arrays.fill(apiKey, '\0');
            }
            return;
        }
        try {
            controller.startApiKeyLogin(apiKey);
        } catch (RuntimeException error) {
            if (apiKey != null) {
                Arrays.fill(apiKey, '\0');
            }
            throw error;
        } catch (Error error) {
            if (apiKey != null) {
                Arrays.fill(apiKey, '\0');
            }
            throw error;
        }
    }

    public static void logout() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.logout();
        }
    }

    public static void refreshThreads() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.refreshThreads();
        }
    }

    public static void selectModel(String modelId) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.selectModel(modelId);
        }
    }

    public static void selectReasoningEffort(String effort) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.selectReasoningEffort(effort);
        }
    }

    public static void startNewThread() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.startNewThread();
        }
    }

    public static void openThread(String threadId) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.openThread(threadId);
        }
    }

    public static void sendMessage(String message) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.sendMessage(message);
        }
    }

    public static void steerTurn(String message) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.steerTurn(message);
        }
    }

    public static void interruptTurn() {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.interruptTurn();
        }
    }

    public static void resolveApproval(
        long requestId,
        CodexApprovalDecision decision,
        int amendmentIndex
    ) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.resolveApproval(requestId, decision, amendmentIndex);
        }
    }

    public static void answerUserInput(long requestId, Map<String, char[]> answers) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.answerUserInput(requestId, answers);
            return;
        }
        wipeAnswers(answers);
    }

    public static void dismissUserInput(long requestId) {
        CodexSessionController controller = sessionController;
        if (controller != null) {
            controller.dismissUserInput(requestId);
        }
    }

    public static void refreshLocalizedNotification() {
        AgentRuntimeService service = activeService;
        if (service != null) {
            service.createNotificationChannel();
            service.updateNotificationSafely(service.notificationTextKey);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeService = this;
        try {
            createNotificationChannel();
            notificationTextKey = RuntimeText.NOTIFICATION_STARTING;
            startForeground(NOTIFICATION_ID, buildNotification(notificationTextKey));
            startRuntimeIfNeeded();
        } catch (Throwable error) {
            recordServiceFailure("service-onCreate", error);
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startRuntimeIfNeeded();
        } catch (Throwable error) {
            recordServiceFailure("service-onStartCommand", error);
            stopSelf(startId);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        CodexSessionController controller;
        McpCatalogController catalogController;
        McpConfigurationController configurationController;
        synchronized (SESSION_LOCK) {
            controller = sessionController;
            sessionController = null;
            catalogController = mcpCatalogController;
            mcpCatalogController = null;
            configurationController = mcpConfigurationController;
            mcpConfigurationController = null;
            activeWorkspaceLayout = null;
        }
        if (catalogController != null) {
            catalogController.close();
        }
        if (configurationController != null) {
            configurationController.close();
        }
        if (controller != null) {
            controller.close();
        }
        if (STATE.snapshot().getPhase() != RuntimePhase.FAILED) {
            STATE.stop();
        }
        BOOTSTRAP_ACTIVE.set(false);
        Thread activeThread = bootstrapThread;
        if (activeThread != null) {
            activeThread.interrupt();
        }
        super.onDestroy();
    }

    private void startRuntimeIfNeeded() {
        RuntimePhase phase = STATE.snapshot().getPhase();
        if (phase == RuntimePhase.STARTING || phase == RuntimePhase.READY) {
            return;
        }
        if (!BOOTSTRAP_ACTIVE.compareAndSet(false, true)) {
            return;
        }

        final long generation;
        try {
            generation = STATE.beginStart();
        } catch (RuntimeException error) {
            BOOTSTRAP_ACTIVE.set(false);
            return;
        }

        Thread bootstrap = new Thread(new Runnable() {
            @Override
            public void run() {
                CodexSessionController startedController = null;
                McpCatalogController startedCatalogController = null;
                McpConfigurationController startedConfigurationController = null;
                try {
                    WorkspaceLayout layout = WorkspaceLayout.create(getFilesDir());
                    NativeEngine engine = new NativeEngine();
                    int result = engine.selfTest();
                    if (result != 0) {
                        throw new IllegalStateException("C++ self-test failed with code " + result);
                    }
                    File nativeLibraryDirectory = new File(getApplicationInfo().nativeLibraryDir);
                    File codexExecutable = new File(
                        nativeLibraryDirectory,
                        BuildIdentity.CODEX_RUNTIME_LIBRARY
                    );
                    File codeModeHostExecutable = new File(
                        nativeLibraryDirectory,
                        BuildIdentity.CODEX_CODE_MODE_HOST_LIBRARY
                    );
                    File shellExecutable = new File(
                        nativeLibraryDirectory,
                        BuildIdentity.TERMINAL_SHELL_LIBRARY
                    );
                    File nodeExecutable = new File(
                        nativeLibraryDirectory,
                        BuildIdentity.NODE_RUNTIME_LIBRARY
                    );
                    File pythonExecutable = new File(
                        nativeLibraryDirectory,
                        BuildIdentity.PYTHON_RUNTIME_LIBRARY
                    );
                    layout.preparePackagedToolAliases(shellExecutable);
                    File toolRuntimeDirectory;
                    try (
                        InputStream archive = getAssets().open(
                            BuildIdentity.TOOL_RUNTIME_ARCHIVE_ASSET
                        );
                        InputStream manifest = getAssets().open(
                            BuildIdentity.TOOL_RUNTIME_MANIFEST_ASSET
                        )
                    ) {
                        toolRuntimeDirectory = layout.preparePackagedToolRuntime(
                            BuildIdentity.TOOL_RUNTIME_NAME,
                            archive,
                            manifest,
                            nativeLibraryDirectory
                        );
                    }
                    String temporaryDirectory = getCacheDir().getCanonicalPath();
                    String nativeLibraryPath = nativeLibraryDirectory.getCanonicalPath();
                    NativeAppServerTransport transport = new NativeAppServerTransport(
                        engine,
                        codexExecutable.getAbsolutePath(),
                        codeModeHostExecutable.getAbsolutePath(),
                        shellExecutable.getAbsolutePath(),
                        nodeExecutable.getAbsolutePath(),
                        pythonExecutable.getAbsolutePath(),
                        layout.getWorkspace().getAbsolutePath(),
                        layout.getToolchain().getAbsolutePath(),
                        layout.getToolBin().getAbsolutePath(),
                        toolRuntimeDirectory.getAbsolutePath(),
                        layout.getCodexHome().getAbsolutePath(),
                        layout.getHome().getAbsolutePath(),
                        layout.getState().getAbsolutePath(),
                        temporaryDirectory,
                        nativeLibraryPath
                    );
                    startedController = new CodexSessionController(
                        transport,
                        layout.getWorkspace().getAbsolutePath(),
                        new CodexSessionController.ConnectionFailureListener() {
                            @Override
                            public void onConnectionFailed(
                                CodexSessionController controller,
                                Throwable error
                            ) {
                                handleSessionConnectionFailure(
                                    controller,
                                    generation,
                                    error
                                );
                            }
                        },
                        shellExecutable.getAbsolutePath()
                    );
                    startedController.start();
                    startedCatalogController = new McpCatalogController(
                        startedController,
                        layout.getWorkspace().getAbsolutePath()
                    );
                    startedConfigurationController = new McpConfigurationController(
                        startedController
                    );
                    CodexSessionController previousController;
                    McpCatalogController previousCatalogController;
                    McpConfigurationController previousConfigurationController;
                    synchronized (SESSION_LOCK) {
                        previousController = sessionController;
                        previousCatalogController = mcpCatalogController;
                        previousConfigurationController = mcpConfigurationController;
                        sessionController = startedController;
                        mcpCatalogController = startedCatalogController;
                        mcpConfigurationController = startedConfigurationController;
                        activeWorkspaceLayout = layout;
                    }
                    if (previousCatalogController != null
                        && previousCatalogController != startedCatalogController) {
                        previousCatalogController.close();
                    }
                    if (previousConfigurationController != null
                        && previousConfigurationController != startedConfigurationController) {
                        previousConfigurationController.close();
                    }
                    if (previousController != null
                        && previousController != startedController) {
                        previousController.close();
                    }
                    boolean accepted = STATE.markReady(
                        generation,
                        engine.version(),
                        engine.diagnostics()
                            + ";codex=" + BuildIdentity.CODEX_RUNTIME_VERSION
                            + ";transport=stdio",
                        layout.getWorkspace().getAbsolutePath()
                    );
                    if (accepted) {
                        startedCatalogController.refresh();
                        startedConfigurationController.refresh();
                        startedCatalogController = null;
                        startedConfigurationController = null;
                        startedController = null;
                        clearStoredCrashReport();
                        updateNotificationSafely(RuntimeText.NOTIFICATION_READY);
                        Log.i(TAG, BuildIdentity.summary() + " app-server ready");
                    } else {
                        synchronized (SESSION_LOCK) {
                            if (sessionController == startedController) {
                                sessionController = null;
                                mcpCatalogController = null;
                                mcpConfigurationController = null;
                                activeWorkspaceLayout = null;
                            }
                        }
                    }
                } catch (Throwable error) {
                    recordServiceFailure("runtime-bootstrap", generation, error);
                } finally {
                    if (startedConfigurationController != null) {
                        synchronized (SESSION_LOCK) {
                            if (mcpConfigurationController == startedConfigurationController) {
                                mcpConfigurationController = null;
                            }
                        }
                        startedConfigurationController.close();
                    }
                    if (startedCatalogController != null) {
                        synchronized (SESSION_LOCK) {
                            if (mcpCatalogController == startedCatalogController) {
                                mcpCatalogController = null;
                            }
                        }
                        startedCatalogController.close();
                    }
                    if (startedController != null) {
                        synchronized (SESSION_LOCK) {
                            if (sessionController == startedController) {
                                sessionController = null;
                                activeWorkspaceLayout = null;
                            }
                        }
                        startedController.close();
                    }
                    BOOTSTRAP_ACTIVE.set(false);
                    if (bootstrapThread == Thread.currentThread()) {
                        bootstrapThread = null;
                    }
                }
            }
        }, "agentcodi-bootstrap");
        bootstrapThread = bootstrap;
        try {
            bootstrap.start();
        } catch (Throwable error) {
            bootstrapThread = null;
            BOOTSTRAP_ACTIVE.set(false);
            recordServiceFailure("runtime-thread-start", generation, error);
        }
    }

    private void handleSessionConnectionFailure(
        CodexSessionController failedController,
        long generation,
        Throwable error
    ) {
        boolean owned;
        McpCatalogController catalogController = null;
        McpConfigurationController configurationController = null;
        synchronized (SESSION_LOCK) {
            owned = sessionController == failedController;
            if (owned) {
                sessionController = null;
                catalogController = mcpCatalogController;
                mcpCatalogController = null;
                configurationController = mcpConfigurationController;
                mcpConfigurationController = null;
                activeWorkspaceLayout = null;
            }
        }
        RuntimeSnapshot current = STATE.snapshot();
        boolean currentBootstrapFailure = current.getGeneration() == generation
            && current.getPhase() == RuntimePhase.STARTING;
        if (!owned && !currentBootstrapFailure) {
            return;
        }

        if (catalogController != null) {
            catalogController.close();
        }
        if (configurationController != null) {
            configurationController.close();
        }
        failedController.close();
        String message = "Codex App-Server-Verbindung fehlgeschlagen: "
            + safeMessage(error == null ? null : error.getMessage());
        if (STATE.markFailed(generation, message)) {
            persistCrash("app-server-transport", error == null
                ? new IllegalStateException("Unknown app-server transport failure")
                : error);
            updateNotificationSafely(RuntimeText.NOTIFICATION_DISCONNECTED);
            Log.e(TAG, "App-server transport failed; explicit restart is available");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            RuntimeText.get(
                this,
                RuntimeText.CHANNEL_NAME,
                "AGENTCODI agent runtime"
            ),
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(RuntimeText.get(
            this,
            RuntimeText.CHANNEL_DESCRIPTION,
            "Keeps the local Codex app-server and active turns alive."
        ));
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String textKey) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        int icon = getApplicationInfo().icon != 0
            ? getApplicationInfo().icon
            : android.R.drawable.stat_notify_sync;
        builder
            .setSmallIcon(icon)
            .setContentTitle(BuildIdentity.APP_NAME)
            .setContentText(notificationText(textKey))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE);
        if (launchIntent != null) {
            PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.setContentIntent(contentIntent);
        }
        return builder.build();
    }

    private void updateNotificationSafely(String textKey) {
        try {
            notificationTextKey = textKey;
            NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(textKey));
            }
        } catch (Throwable error) {
            persistCrash("notification-update", error);
        }
    }

    private String notificationText(String textKey) {
        if (RuntimeText.NOTIFICATION_READY.equals(textKey)) {
            return RuntimeText.get(this, textKey, "Codex app-server is ready");
        }
        if (RuntimeText.NOTIFICATION_DISCONNECTED.equals(textKey)) {
            return RuntimeText.get(
                this,
                textKey,
                "Codex app-server disconnected — restart required"
            );
        }
        if (RuntimeText.NOTIFICATION_ERROR.equals(textKey)) {
            return RuntimeText.get(this, textKey, "Runtime error");
        }
        return RuntimeText.get(this, RuntimeText.NOTIFICATION_STARTING, "Native runtime is starting");
    }

    private static String safeMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "no details";
        }
        String bounded = message.length() > 240 ? message.substring(0, 240) : message;
        return CrashReportFormatter.redact(bounded);
    }

    private void recordServiceFailure(String source, Throwable error) {
        RuntimeSnapshot current = STATE.snapshot();
        long generation = current.getGeneration();
        if (current.getPhase() == RuntimePhase.FAILED) {
            persistCrash(source, error);
            updateNotificationSafely(RuntimeText.NOTIFICATION_ERROR);
            Log.e(TAG, "Additional runtime failure: " + error.getClass().getName());
            return;
        }
        if (current.getPhase() != RuntimePhase.STARTING
            && current.getPhase() != RuntimePhase.READY) {
            try {
                generation = STATE.beginStart();
            } catch (RuntimeException ignored) {
                generation = STATE.snapshot().getGeneration();
            }
        }
        recordServiceFailure(source, generation, error);
    }

    private void recordServiceFailure(String source, long generation, Throwable error) {
        String message = error.getClass().getSimpleName() + ": "
            + safeMessage(error.getMessage());
        STATE.markFailed(generation, message);
        persistCrash(source, error);
        updateNotificationSafely(RuntimeText.NOTIFICATION_ERROR);
        Log.e(TAG, "Runtime failure: " + error.getClass().getName());
    }

    private void persistCrash(String source, Throwable error) {
        try {
            CrashDiagnostics.open(getFilesDir()).record(
                source,
                Thread.currentThread(),
                error
            );
        } catch (Throwable ignored) {
            // Diagnostics must not turn a handled service error into a process crash.
        }
    }

    private void clearStoredCrashReport() {
        try {
            CrashDiagnostics.open(getFilesDir()).clear();
        } catch (Throwable ignored) {
            // A successful runtime stays usable even if stale diagnostics cannot be removed.
        }
    }

    private static void wipeAnswers(Map<String, char[]> answers) {
        if (answers == null) {
            return;
        }
        for (char[] value : answers.values()) {
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
        answers.clear();
    }
}
