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
import de.agentcodi.storage.WorkspaceLayout;

import java.io.File;
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
    private static volatile CodexSessionController sessionController;
    private volatile Thread bootstrapThread;

    public static RuntimeSnapshot snapshot() {
        return STATE.snapshot();
    }

    public static CodexSessionSnapshot sessionSnapshot() {
        CodexSessionController controller = sessionController;
        return controller == null ? STOPPED_SESSION : controller.snapshot();
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
        controller.startApiKeyLogin(apiKey);
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

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification("Native Runtime startet"));
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
        CodexSessionController controller = sessionController;
        sessionController = null;
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
                    NativeAppServerTransport transport = new NativeAppServerTransport(
                        engine,
                        codexExecutable.getAbsolutePath(),
                        codeModeHostExecutable.getAbsolutePath(),
                        layout.getWorkspace().getAbsolutePath(),
                        layout.getCodexHome().getAbsolutePath(),
                        layout.getHome().getAbsolutePath(),
                        getCacheDir().getCanonicalPath(),
                        nativeLibraryDirectory.getCanonicalPath()
                    );
                    startedController = new CodexSessionController(
                        transport,
                        layout.getWorkspace().getAbsolutePath()
                    );
                    startedController.start();
                    boolean accepted = STATE.markReady(
                        generation,
                        engine.version(),
                        engine.diagnostics()
                            + ";codex=" + BuildIdentity.CODEX_RUNTIME_VERSION
                            + ";transport=stdio",
                        layout.getWorkspace().getAbsolutePath()
                    );
                    if (accepted) {
                        sessionController = startedController;
                        startedController = null;
                        clearStoredCrashReport();
                        updateNotificationSafely("Codex App-Server bereit");
                        Log.i(TAG, BuildIdentity.summary() + " app-server ready");
                    }
                } catch (Throwable error) {
                    recordServiceFailure("runtime-bootstrap", generation, error);
                } finally {
                    if (startedController != null) {
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
            "AGENTCODI Agent-Runtime",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Hält den lokalen Codex App-Server und aktive Turns am Leben.");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        int icon = getApplicationInfo().icon != 0
            ? getApplicationInfo().icon
            : android.R.drawable.stat_notify_sync;
        builder
            .setSmallIcon(icon)
            .setContentTitle(BuildIdentity.APP_NAME)
            .setContentText(message)
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

    private void updateNotificationSafely(String message) {
        try {
            NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(message));
            }
        } catch (Throwable error) {
            persistCrash("notification-update", error);
        }
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
        if (current.getPhase() != RuntimePhase.STARTING) {
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
        updateNotificationSafely("Runtime-Fehler");
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
