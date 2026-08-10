package de.agentcodi.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.BuildIdentity;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeReportFormatter;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.UiStartupState;
import de.agentcodi.runtime.AgentRuntimeService;
import de.agentcodi.runtime.CrashDiagnostics;

import java.net.URI;
import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 42;
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
                    || session.isLoginPending()
                    || session.hasInteractiveRequest()
                    ? ACTIVE_REFRESH_INTERVAL_MS
                    : IDLE_REFRESH_INTERVAL_MS;
                if (startupState.shouldRefresh()) {
                    handler.postDelayed(this, delay);
                }
            } catch (Throwable error) {
                persistCrash("settings-refresh", error);
                showInlineFailure(error);
            }
        }
    };

    private UiTheme theme;
    private TextView phaseView;
    private TextView runtimeMessageView;
    private TextView technicalView;
    private LinearLayout runtimeCard;
    private Button startRuntimeButton;
    private TextView sessionStatusView;
    private TextView accountView;
    private TextView loginHintView;
    private Button chatGptLoginButton;
    private Button openLoginButton;
    private Button apiKeyLoginButton;
    private Button logoutButton;
    private Button refreshAccountButton;
    private EditText apiKeyInput;
    private String currentLoginUrl = "";
    private long lastRuntimeGeneration = Long.MIN_VALUE;
    private RuntimePhase lastRuntimePhase;
    private long lastSessionRevision = Long.MIN_VALUE;
    private boolean launchAfterNotificationPermission;
    private CrashDiagnostics crashDiagnostics;
    private InteractiveRequestDialog interactiveRequestDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String previousCrash = "";
        try {
            startupState.enter("settings-diagnostics");
            try {
                crashDiagnostics = CrashDiagnostics.open(getFilesDir());
                previousCrash = crashDiagnostics.read();
            } catch (Throwable diagnosticsError) {
                previousCrash = CrashReportFormatter.format(
                    "settings-diagnostics",
                    Thread.currentThread(),
                    diagnosticsError
                );
            }
            startupState.enter("settings-theme");
            theme = new UiTheme(this);
            interactiveRequestDialog = new InteractiveRequestDialog(this, theme);
            startupState.enter("settings-content");
            setContentView(buildContent(previousCrash));
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
        if (interactiveRequestDialog != null) {
            interactiveRequestDialog.dismissForLifecycle();
        }
        super.onStop();
    }

    private View buildContent(String previousCrash) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(theme.page);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(theme.dp(18), theme.dp(20), theme.dp(18), theme.dp(36));
        scroll.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Button closeButton = theme.compactButton("‹ Chats");
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(closeButton);
        TextView title = theme.text("Einstellungen", 26, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(14);
        topBar.addView(title, titleParams);
        page.addView(topBar);

        TextView subtitle = theme.text(
            "Runtime, Anmeldung, Sicherheit und technische Diagnose.",
            14,
            theme.secondary
        );
        theme.addWithTopMargin(page, subtitle, 8);

        if (previousCrash != null && !previousCrash.trim().isEmpty()) {
            addCrashReportCard(page, previousCrash);
        }

        theme.addWithTopMargin(page, theme.sectionLabel("RUNTIME"), 28);
        runtimeCard = theme.card();
        phaseView = theme.text("IDLE", 12, theme.secondary);
        phaseView.setTypeface(Typeface.DEFAULT_BOLD);
        runtimeCard.addView(phaseView);
        runtimeMessageView = theme.text("Runtime wartet auf den Start.", 18, theme.primary);
        runtimeMessageView.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(runtimeCard, runtimeMessageView, 8);
        technicalView = theme.text("", 13, theme.secondary);
        technicalView.setTextIsSelectable(true);
        technicalView.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(runtimeCard, technicalView, 10);
        startRuntimeButton = theme.primaryButton("Codex Runtime starten");
        startRuntimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestPermissionAndLaunchRuntime();
            }
        });
        theme.addWithTopMargin(runtimeCard, startRuntimeButton, 16);
        Button copyDiagnosticsButton = theme.secondaryButton("Technische Diagnose kopieren");
        copyDiagnosticsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyDiagnostics();
            }
        });
        theme.addWithTopMargin(runtimeCard, copyDiagnosticsButton, 8);
        theme.addWithTopMargin(page, runtimeCard, 10);

        theme.addWithTopMargin(page, theme.sectionLabel("ANMELDUNG"), 28);
        LinearLayout accountCard = theme.card();
        sessionStatusView = theme.text("Codex App-Server ist nicht gestartet.", 13, theme.secondary);
        sessionStatusView.setLineSpacing(0.0f, 1.16f);
        accountCard.addView(sessionStatusView);
        accountView = theme.text("Nicht angemeldet", 18, theme.primary);
        accountView.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(accountCard, accountView, 12);

        TextView credentialBoundary = theme.text(
            "Zugangsdaten verwaltet ausschließlich Codex im privaten CODEX_HOME/auth.json. "
                + "Sie erscheinen weder in Chats noch in Diagnose, Zwischenablage oder APK.",
            13,
            theme.secondary
        );
        credentialBoundary.setLineSpacing(0.0f, 1.18f);
        theme.addWithTopMargin(accountCard, credentialBoundary, 8);

        chatGptLoginButton = theme.primaryButton("Mit ChatGPT anmelden");
        chatGptLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.startChatGptLogin();
            }
        });
        theme.addWithTopMargin(accountCard, chatGptLoginButton, 16);

        openLoginButton = theme.secondaryButton("Sichere Anmeldeseite öffnen");
        openLoginButton.setVisibility(View.GONE);
        openLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openCurrentLoginUrl();
            }
        });
        theme.addWithTopMargin(accountCard, openLoginButton, 8);
        loginHintView = theme.text("", 13, theme.secondary);
        loginHintView.setVisibility(View.GONE);
        theme.addWithTopMargin(accountCard, loginHintView, 8);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("OpenAI API-Schlüssel (optional)");
        apiKeyInput.setHintTextColor(theme.secondary);
        apiKeyInput.setTextColor(theme.primary);
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setSaveEnabled(false);
        apiKeyInput.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        if (Build.VERSION.SDK_INT >= 26) {
            apiKeyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            apiKeyInput.setAutofillHints(new String[0]);
        }
        theme.addWithTopMargin(accountCard, apiKeyInput, 14);
        apiKeyLoginButton = theme.secondaryButton("API-Schlüssel an Codex übergeben");
        apiKeyLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitApiKey();
            }
        });
        theme.addWithTopMargin(accountCard, apiKeyLoginButton, 8);

        logoutButton = theme.secondaryButton("Abmelden");
        logoutButton.setVisibility(View.GONE);
        logoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.logout();
            }
        });
        theme.addWithTopMargin(accountCard, logoutButton, 8);

        refreshAccountButton = theme.secondaryButton("Konto und Modelle aktualisieren");
        refreshAccountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshAccountAndThreads();
            }
        });
        theme.addWithTopMargin(accountCard, refreshAccountButton, 8);
        theme.addWithTopMargin(page, accountCard, 10);

        theme.addWithTopMargin(page, theme.sectionLabel("SICHERHEIT UND STATUS"), 28);
        LinearLayout securityCard = theme.card();
        securityCard.addView(theme.body(
            "• App-Server ausschließlich über begrenztes stdio, ohne Netzwerk-Listener\n"
                + "• Eigenes Permissions-Profil mit privatem Workspace als Runtime-Root\n"
                + "• Modelle und Denkstufen werden live vom App-Server geladen\n"
                + "• Native Freigabe- und Rückfragedialoge ohne automatische Zustimmung\n\n"
                + BuildIdentity.summary() + " · Codex " + BuildIdentity.CODEX_RUNTIME_VERSION
        ));
        theme.addWithTopMargin(page, securityCard, 10);
        return scroll;
    }

    private void render(RuntimeSnapshot runtime, CodexSessionSnapshot session) {
        if (runtime.getGeneration() == lastRuntimeGeneration
            && runtime.getPhase() == lastRuntimePhase
            && session.getRevision() == lastSessionRevision) {
            return;
        }
        lastRuntimeGeneration = runtime.getGeneration();
        lastRuntimePhase = runtime.getPhase();
        lastSessionRevision = session.getRevision();
        currentLoginUrl = session.getLoginUrl();

        phaseView.setText(runtime.getPhase().name());
        phaseView.setTextColor(phaseColor(runtime.getPhase()));
        runtimeMessageView.setText(runtime.getMessage());
        StringBuilder technical = new StringBuilder();
        if (!runtime.getEngineVersion().isEmpty()) {
            technical.append("Engine: ").append(runtime.getEngineVersion()).append('\n');
        }
        if (!runtime.getDiagnostics().isEmpty()) {
            technical.append("Diagnose: ").append(runtime.getDiagnostics()).append('\n');
        }
        if (!runtime.getWorkspacePath().isEmpty()) {
            technical.append("Workspace: ").append(runtime.getWorkspacePath());
        }
        technicalView.setText(technical.length() == 0
            ? "Generation " + runtime.getGeneration()
            : technical.toString()
        );
        runtimeCard.setBackground(theme.background(
            theme.surface,
            phaseColor(runtime.getPhase()),
            18
        ));
        boolean runtimeCanStart = runtime.getPhase() == RuntimePhase.IDLE
            || runtime.getPhase() == RuntimePhase.FAILED
            || runtime.getPhase() == RuntimePhase.STOPPED;
        theme.setEnabled(startRuntimeButton, runtimeCanStart);
        startRuntimeButton.setText(
            runtime.getPhase() == RuntimePhase.READY
                ? "Runtime läuft"
                : runtime.getPhase() == RuntimePhase.STARTING
                    ? "Runtime startet …"
                    : runtime.getPhase() == RuntimePhase.FAILED
                        ? "Runtime erneut starten"
                        : "Codex Runtime starten"
        );

        StringBuilder status = new StringBuilder(session.getConnectionMessage());
        if (!session.getOperationMessage().isEmpty()) {
            status.append('\n').append(session.getOperationMessage());
        }
        if (!session.getErrorMessage().isEmpty()) {
            status.append('\n').append("Fehler: ").append(session.getErrorMessage());
        }
        sessionStatusView.setText(status.toString());
        sessionStatusView.setTextColor(
            session.getErrorMessage().isEmpty() ? theme.secondary : theme.danger
        );

        if (session.isSignedIn()) {
            StringBuilder account = new StringBuilder("Angemeldet über ")
                .append(authLabel(session.getAuthMode()));
            if (!session.getAccountEmail().isEmpty()) {
                account.append('\n').append(session.getAccountEmail());
            }
            if (!session.getPlanType().isEmpty()) {
                account.append(" · ").append(session.getPlanType());
            }
            accountView.setText(account.toString());
        } else if (!session.requiresOpenaiAuth()) {
            accountView.setText("Der aktive Provider benötigt keine OpenAI-Anmeldung.");
        } else {
            accountView.setText("Nicht angemeldet");
        }

        boolean actionReady = session.isReady() && !session.isOperationActive();
        boolean showLogin = !session.isSignedIn();
        chatGptLoginButton.setVisibility(showLogin ? View.VISIBLE : View.GONE);
        apiKeyInput.setVisibility(showLogin ? View.VISIBLE : View.GONE);
        apiKeyLoginButton.setVisibility(showLogin ? View.VISIBLE : View.GONE);
        logoutButton.setVisibility(session.isSignedIn() ? View.VISIBLE : View.GONE);
        openLoginButton.setVisibility(currentLoginUrl.isEmpty() ? View.GONE : View.VISIBLE);
        loginHintView.setVisibility(session.isLoginPending() ? View.VISIBLE : View.GONE);
        loginHintView.setText(session.isLoginPending()
            ? currentLoginUrl.isEmpty()
                ? "Anmeldung wird vorbereitet …"
                : "Schließe die Anmeldung im externen Browser ab. Der Kontostatus aktualisiert sich automatisch."
            : ""
        );
        theme.setEnabled(
            chatGptLoginButton,
            actionReady && showLogin && !session.isLoginPending()
        );
        theme.setEnabled(
            apiKeyLoginButton,
            actionReady && showLogin && !session.isLoginPending()
        );
        theme.setEnabled(openLoginButton, !currentLoginUrl.isEmpty());
        theme.setEnabled(logoutButton, actionReady && session.isSignedIn());
        theme.setEnabled(refreshAccountButton, actionReady);
        apiKeyInput.setEnabled(actionReady && showLogin && !session.isLoginPending());
        if (interactiveRequestDialog != null) {
            interactiveRequestDialog.render(session);
        }
    }

    private void requestPermissionAndLaunchRuntime() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            launchAfterNotificationPermission = true;
            requestPermissions(
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
            );
            return;
        }
        launchRuntime();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST
            && launchAfterNotificationPermission) {
            launchAfterNotificationPermission = false;
            launchRuntime();
        }
    }

    private void launchRuntime() {
        Intent runtimeIntent = new Intent(this, AgentRuntimeService.class);
        try {
            clearCrashReport();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(runtimeIntent);
            } else {
                startService(runtimeIntent);
            }
            lastRuntimeGeneration = Long.MIN_VALUE;
            lastSessionRevision = Long.MIN_VALUE;
        } catch (Throwable error) {
            persistCrash("settings-start-service", error);
            showInlineFailure(error);
            Toast.makeText(
                this,
                "Runtime konnte nicht gestartet werden: " + error.getClass().getSimpleName(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void submitApiKey() {
        int length = apiKeyInput.length();
        char[] key = new char[length];
        for (int index = 0; index < length; index++) {
            key[index] = apiKeyInput.getText().charAt(index);
        }
        apiKeyInput.getText().clear();
        AgentRuntimeService.startApiKeyLogin(key);
    }

    private void openCurrentLoginUrl() {
        if (!isTrustedLoginUrl(currentLoginUrl)) {
            Toast.makeText(this, "Die Anmelde-URL wurde abgelehnt.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentLoginUrl)));
        } catch (Throwable error) {
            Toast.makeText(
                this,
                "Die Anmeldeseite konnte nicht geöffnet werden.",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(this, "Zwischenablage ist nicht verfügbar.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
            "AGENTCODI Diagnose",
            RuntimeReportFormatter.format(AgentRuntimeService.snapshot())
        ));
        Toast.makeText(this, "Diagnose wurde kopiert.", Toast.LENGTH_SHORT).show();
    }

    private void addCrashReportCard(LinearLayout page, final String report) {
        theme.addWithTopMargin(page, theme.sectionLabel("LETZTER STARTFEHLER"), 24);
        LinearLayout card = theme.card();
        TextView explanation = theme.body(
            "Der vorherige Prozess wurde unerwartet beendet. Der lokal gespeicherte, "
                + "bereinigte Bericht kann vor dem Neustart geprüft werden."
        );
        card.addView(explanation);
        TextView reportView = theme.text(report, 12, theme.secondary);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        theme.addWithTopMargin(card, reportView, 12);
        Button clearButton = theme.secondaryButton("Bericht löschen");
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearCrashReport();
                recreate();
            }
        });
        theme.addWithTopMargin(card, clearButton, 12);
        theme.addWithTopMargin(page, card, 10);
    }

    private void clearCrashReport() {
        try {
            if (crashDiagnostics != null) {
                crashDiagnostics.clear();
            }
        } catch (Throwable ignored) {
            Toast.makeText(
                this,
                "Crashbericht konnte nicht gelöscht werden.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void persistCrash(String source, Throwable error) {
        try {
            if (crashDiagnostics == null) {
                crashDiagnostics = CrashDiagnostics.open(getFilesDir());
            }
            crashDiagnostics.record(source, Thread.currentThread(), error);
        } catch (Throwable ignored) {
            // Diagnostics must not turn a handled settings error into a crash.
        }
    }

    private void showInlineFailure(Throwable error) {
        if (phaseView == null || runtimeMessageView == null || technicalView == null) {
            return;
        }
        phaseView.setText(RuntimePhase.FAILED.name());
        phaseView.setTextColor(theme.danger);
        runtimeMessageView.setText("Einstellungs- oder Runtime-Fehler wurde abgefangen.");
        technicalView.setText(error.getClass().getName() + "\nBericht wurde lokal gespeichert.");
    }

    private void showEmergencyScreen(String source, Throwable error) {
        TextView fallback = new TextView(this);
        fallback.setPadding(32, 48, 32, 48);
        fallback.setTextColor(0xFF111827);
        fallback.setBackgroundColor(0xFFF5F7FB);
        fallback.setTextSize(14);
        fallback.setTextIsSelectable(true);
        fallback.setText(
            "AGENTCODI konnte die Einstellungen nicht initialisieren.\n\n"
                + CrashReportFormatter.format(source, Thread.currentThread(), error)
        );
        setContentView(fallback);
    }

    private int phaseColor(RuntimePhase phase) {
        switch (phase) {
            case READY:
                return theme.dark ? 0xFF6EE7B7 : 0xFF047857;
            case FAILED:
                return theme.danger;
            case STARTING:
                return theme.dark ? 0xFFFCD34D : 0xFFB45309;
            default:
                return theme.secondary;
        }
    }

    private static String authLabel(String authMode) {
        if ("chatgpt".equalsIgnoreCase(authMode)) {
            return "ChatGPT";
        }
        if ("apiKey".equalsIgnoreCase(authMode) || "apikey".equalsIgnoreCase(authMode)) {
            return "OpenAI API-Schlüssel";
        }
        return authMode;
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
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return lowerHost.equals("openai.com") || lowerHost.endsWith(".openai.com")
                || lowerHost.equals("chatgpt.com") || lowerHost.endsWith(".chatgpt.com");
        } catch (Exception error) {
            return false;
        }
    }
}
