package de.agentcodi.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
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
import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.CodexRateLimitWindow;
import de.agentcodi.core.CodexRateLimitsSnapshot;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.UiLanguage;
import de.agentcodi.core.UiStartupState;
import de.agentcodi.runtime.AgentRuntimeService;
import de.agentcodi.runtime.CrashDiagnostics;

import java.net.URI;
import java.text.DateFormat;
import java.util.Date;
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
    private TextView rateLimitsView;
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
    private String pendingLaunchExecutionModeId = CodexExecutionMode.PROTECTED_ID;
    private boolean pendingLaunchDangerWarningAcknowledged;
    private CrashDiagnostics crashDiagnostics;
    private InteractiveRequestDialog interactiveRequestDialog;
    private boolean destroyed;
    private TextView languageStatusView;
    private ExecutionModeSettingsCard executionModeSettingsCard;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

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
        if (executionModeSettingsCard != null) {
            executionModeSettingsCard.dismiss();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
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
        Button closeButton = theme.compactButton(getString(R.string.navigation_chats));
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(closeButton);
        TextView title = theme.text(getString(R.string.settings_title), 26, theme.primary);
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
            getString(R.string.settings_subtitle),
            14,
            theme.secondary
        );
        theme.addWithTopMargin(page, subtitle, 8);

        if (previousCrash != null && !previousCrash.trim().isEmpty()) {
            addCrashReportCard(page, previousCrash);
        }

        addLanguageCard(page);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.execution_mode_section)),
            28
        );
        executionModeSettingsCard = new ExecutionModeSettingsCard(
            this,
            theme,
            new ExecutionModeSettingsCard.ActiveModeListener() {
                @Override
                public boolean onActiveModeRequested(
                    String executionModeId,
                    boolean dangerWarningAcknowledged
                ) {
                    boolean accepted = AgentRuntimeService.selectExecutionMode(
                        executionModeId,
                        dangerWarningAcknowledged
                    );
                    if (!accepted) {
                        Toast.makeText(
                            SettingsActivity.this,
                            R.string.execution_mode_change_rejected,
                            Toast.LENGTH_LONG
                        ).show();
                    }
                    return accepted;
                }
            }
        );
        theme.addWithTopMargin(page, executionModeSettingsCard.getView(), 10);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.settings_runtime_section)),
            28
        );
        runtimeCard = theme.card();
        phaseView = theme.text(getString(R.string.runtime_phase_idle), 12, theme.secondary);
        phaseView.setTypeface(Typeface.DEFAULT_BOLD);
        runtimeCard.addView(phaseView);
        runtimeMessageView = theme.text(
            getString(R.string.runtime_status_idle),
            18,
            theme.primary
        );
        runtimeMessageView.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(runtimeCard, runtimeMessageView, 8);
        technicalView = theme.text("", 13, theme.secondary);
        technicalView.setTextIsSelectable(true);
        technicalView.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(runtimeCard, technicalView, 10);
        startRuntimeButton = theme.primaryButton(
            getString(R.string.settings_runtime_start)
        );
        startRuntimeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestPermissionAndLaunchRuntime();
            }
        });
        theme.addWithTopMargin(runtimeCard, startRuntimeButton, 16);
        Button copyDiagnosticsButton = theme.secondaryButton(
            getString(R.string.settings_copy_diagnostics)
        );
        copyDiagnosticsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                copyDiagnostics();
            }
        });
        theme.addWithTopMargin(runtimeCard, copyDiagnosticsButton, 8);
        theme.addWithTopMargin(page, runtimeCard, 10);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.workspace_export_section)),
            28
        );
        LinearLayout workspaceCard = theme.card();
        workspaceCard.addView(theme.body(getString(R.string.workspace_export_description)));
        TextView workspaceExportLimits = theme.text(
            getString(R.string.workspace_export_ready),
            13,
            theme.secondary
        );
        workspaceExportLimits.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(workspaceCard, workspaceExportLimits, 12);
        Button workspaceBrowserButton = theme.secondaryButton(
            getString(R.string.workspace_browser_open)
        );
        workspaceBrowserButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(
                    SettingsActivity.this,
                    WorkspaceBrowserActivity.class
                ));
            }
        });
        theme.addWithTopMargin(workspaceCard, workspaceBrowserButton, 14);
        theme.addWithTopMargin(page, workspaceCard, 10);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.account_section)),
            28
        );
        LinearLayout accountCard = theme.card();
        sessionStatusView = theme.text(
            getString(R.string.account_server_not_started),
            13,
            theme.secondary
        );
        sessionStatusView.setLineSpacing(0.0f, 1.16f);
        accountCard.addView(sessionStatusView);
        accountView = theme.text(getString(R.string.account_not_signed_in), 18, theme.primary);
        accountView.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(accountCard, accountView, 12);

        rateLimitsView = theme.text("", 13, theme.secondary);
        rateLimitsView.setLineSpacing(0.0f, 1.18f);
        rateLimitsView.setVisibility(View.GONE);
        theme.addWithTopMargin(accountCard, rateLimitsView, 12);

        TextView credentialBoundary = theme.text(
            getString(R.string.account_credential_boundary),
            13,
            theme.secondary
        );
        credentialBoundary.setLineSpacing(0.0f, 1.18f);
        theme.addWithTopMargin(accountCard, credentialBoundary, 8);

        chatGptLoginButton = theme.primaryButton(
            getString(R.string.account_chatgpt_sign_in)
        );
        chatGptLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.startChatGptLogin();
            }
        });
        theme.addWithTopMargin(accountCard, chatGptLoginButton, 16);

        openLoginButton = theme.secondaryButton(getString(R.string.account_open_login));
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
        apiKeyInput.setHint(R.string.account_api_key_hint);
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
        apiKeyLoginButton = theme.secondaryButton(
            getString(R.string.account_api_key_submit)
        );
        apiKeyLoginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitApiKey();
            }
        });
        theme.addWithTopMargin(accountCard, apiKeyLoginButton, 8);

        logoutButton = theme.secondaryButton(getString(R.string.account_sign_out));
        logoutButton.setVisibility(View.GONE);
        logoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.logout();
            }
        });
        theme.addWithTopMargin(accountCard, logoutButton, 8);

        refreshAccountButton = theme.secondaryButton(getString(R.string.account_refresh));
        refreshAccountButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshAccountAndThreads();
            }
        });
        theme.addWithTopMargin(accountCard, refreshAccountButton, 8);
        theme.addWithTopMargin(page, accountCard, 10);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.mcp_settings_section)),
            28
        );
        LinearLayout mcpCard = theme.card();
        mcpCard.addView(theme.body(getString(R.string.mcp_settings_description)));
        Button mcpButton = theme.secondaryButton(getString(R.string.mcp_settings_open));
        mcpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, McpManagementActivity.class));
            }
        });
        theme.addWithTopMargin(mcpCard, mcpButton, 14);
        theme.addWithTopMargin(page, mcpCard, 10);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.licenses_section)),
            28
        );
        LinearLayout licensesCard = theme.card();
        licensesCard.addView(theme.body(getString(R.string.licenses_settings_description)));
        Button licensesButton = theme.secondaryButton(getString(R.string.licenses_open));
        licensesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, LicensesActivity.class));
            }
        });
        theme.addWithTopMargin(licensesCard, licensesButton, 14);
        theme.addWithTopMargin(page, licensesCard, 10);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.security_section)),
            28
        );
        LinearLayout securityCard = theme.card();
        securityCard.addView(theme.body(
            getString(
                R.string.security_summary,
                BuildIdentity.summary(),
                BuildIdentity.CODEX_RUNTIME_VERSION
            )
        ));
        theme.addWithTopMargin(page, securityCard, 10);
        return scroll;
    }

    private void addLanguageCard(LinearLayout page) {
        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.language_section)),
            28
        );
        LinearLayout card = theme.card();
        card.addView(theme.body(getString(R.string.language_description)));
        languageStatusView = theme.text(languageSummary(), 15, theme.primary);
        languageStatusView.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(card, languageStatusView, 12);
        Button changeButton = theme.secondaryButton(getString(R.string.language_change));
        changeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLanguageDialog();
            }
        });
        theme.addWithTopMargin(card, changeButton, 12);
        theme.addWithTopMargin(page, card, 10);
    }

    private String languageSummary() {
        UiLanguage selected = AppLanguage.selected(this);
        String effective = "de".equals(AppLanguage.effectiveLanguageTag(this))
            ? getString(R.string.language_german)
            : getString(R.string.language_english);
        return selected.followsSystem()
            ? getString(R.string.language_current_system, effective)
            : getString(R.string.language_current_explicit, effective);
    }

    private void showLanguageDialog() {
        final UiLanguage[] choices = new UiLanguage[] {
            UiLanguage.SYSTEM,
            UiLanguage.ENGLISH,
            UiLanguage.GERMAN
        };
        String[] labels = new String[] {
            getString(R.string.language_system),
            getString(R.string.language_english),
            getString(R.string.language_german)
        };
        UiLanguage selected = AppLanguage.selected(this);
        int checked = selected == UiLanguage.ENGLISH
            ? 1
            : selected == UiLanguage.GERMAN ? 2 : 0;
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(labels, checked, null)
            .setNegativeButton(R.string.common_cancel, null)
            .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface source) {
                dialog.getListView().setOnItemClickListener(
                    new android.widget.AdapterView.OnItemClickListener() {
                        @Override
                        public void onItemClick(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                        ) {
                            if (position < 0 || position >= choices.length) {
                                return;
                            }
                            dialog.dismiss();
                            if (AppLanguage.select(SettingsActivity.this, choices[position])) {
                                AgentRuntimeService.refreshLocalizedNotification();
                            }
                        }
                    }
                );
            }
        });
        dialog.show();
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

        phaseView.setText(UiText.phase(this, runtime.getPhase()));
        phaseView.setTextColor(phaseColor(runtime.getPhase()));
        runtimeMessageView.setText(UiText.runtimeMessage(this, runtime));
        StringBuilder technical = new StringBuilder();
        if (!runtime.getEngineVersion().isEmpty()) {
            technical.append(getString(R.string.settings_engine_label))
                .append(": ").append(runtime.getEngineVersion()).append('\n');
        }
        if (!runtime.getDiagnostics().isEmpty()) {
            technical.append(getString(R.string.settings_diagnostics_label))
                .append(": ").append(runtime.getDiagnostics()).append('\n');
        }
        if (!runtime.getWorkspacePath().isEmpty()) {
            technical.append(getString(R.string.settings_workspace_label))
                .append(": ").append(runtime.getWorkspacePath());
        }
        technicalView.setText(technical.length() == 0
            ? getString(R.string.settings_generation, Long.valueOf(runtime.getGeneration()))
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
                ? getString(R.string.settings_runtime_running)
                : runtime.getPhase() == RuntimePhase.STARTING
                    ? getString(R.string.settings_runtime_starting)
                    : runtime.getPhase() == RuntimePhase.FAILED
                        ? getString(R.string.settings_runtime_restart)
                        : getString(R.string.settings_runtime_start)
        );

        StringBuilder status = new StringBuilder(
            UiText.coreStatus(this, session.getConnectionMessage())
        );
        if (!session.getOperationMessage().isEmpty()) {
            status.append('\n').append(UiText.coreStatus(this, session.getOperationMessage()));
        }
        if (!session.getErrorMessage().isEmpty()) {
            status.append('\n').append(getString(
                R.string.common_error_prefix,
                UiText.errorReason(this, session.getErrorMessage())
            ));
        }
        sessionStatusView.setText(status.toString());
        sessionStatusView.setTextColor(
            session.getErrorMessage().isEmpty() ? theme.secondary : theme.danger
        );

        if (session.isSignedIn()) {
            StringBuilder account = new StringBuilder(getString(
                R.string.account_signed_in_through,
                authLabel(session.getAuthMode())
            ));
            if (!session.getAccountEmail().isEmpty()) {
                account.append('\n').append(session.getAccountEmail());
            }
            if (!session.getPlanType().isEmpty()) {
                account.append(" · ").append(session.getPlanType());
            }
            accountView.setText(account.toString());
        } else if (!session.requiresOpenaiAuth()) {
            accountView.setText(R.string.account_provider_no_auth);
        } else {
            accountView.setText(R.string.account_not_signed_in);
        }

        boolean showRateLimits = "chatgpt".equals(session.getAuthMode());
        rateLimitsView.setVisibility(showRateLimits ? View.VISIBLE : View.GONE);
        rateLimitsView.setText(showRateLimits
            ? formatRateLimits(session.getRateLimits())
            : ""
        );

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
                ? getString(R.string.account_login_preparing)
                : getString(R.string.account_login_browser_hint)
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
        if (executionModeSettingsCard != null) {
            executionModeSettingsCard.render(runtime, session);
        }
    }

    private String formatRateLimits(CodexRateLimitsSnapshot rateLimits) {
        StringBuilder text = new StringBuilder(getString(R.string.account_rate_limits_title));
        if (rateLimits == null || !rateLimits.isAvailable()) {
            return text.append('\n')
                .append(getString(R.string.account_rate_limits_unavailable))
                .toString();
        }
        appendRateLimitWindow(text, rateLimits.getPrimary());
        appendRateLimitWindow(text, rateLimits.getSecondary());
        return text.toString();
    }

    private void appendRateLimitWindow(StringBuilder text, CodexRateLimitWindow window) {
        if (window == null) {
            return;
        }
        text.append('\n');
        if (window.hasWindowDuration()) {
            text.append(getString(
                R.string.account_rate_limit_window,
                Long.valueOf(window.getWindowDurationMinutes()),
                Integer.valueOf(window.getUsedPercent())
            ));
        } else {
            text.append(getString(
                R.string.account_rate_limit_window_unknown_duration,
                Integer.valueOf(window.getUsedPercent())
            ));
        }
        if (window.hasResetTime()) {
            String reset = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(window.getResetsAtSeconds() * 1000L));
            text.append(getString(R.string.account_rate_limit_reset, reset));
        }
    }

    private void requestPermissionAndLaunchRuntime() {
        if (executionModeSettingsCard == null) {
            pendingLaunchExecutionModeId = CodexExecutionMode.PROTECTED_ID;
            pendingLaunchDangerWarningAcknowledged = false;
            requestNotificationPermissionAndLaunchRuntime();
            return;
        }
        executionModeSettingsCard.confirmSelectedModeForLaunch(
            new ExecutionModeSettingsCard.ConfirmedLaunchListener() {
                @Override
                public void onLaunchConfirmed(
                    String executionModeId,
                    boolean dangerWarningAcknowledged
                ) {
                    pendingLaunchExecutionModeId = executionModeId;
                    pendingLaunchDangerWarningAcknowledged =
                        dangerWarningAcknowledged;
                    requestNotificationPermissionAndLaunchRuntime();
                }
            }
        );
    }

    private void requestNotificationPermissionAndLaunchRuntime() {
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
        Intent runtimeIntent;
        try {
            runtimeIntent = AgentRuntimeService.createLaunchIntent(
                this,
                pendingLaunchExecutionModeId,
                pendingLaunchDangerWarningAcknowledged
            );
            pendingLaunchDangerWarningAcknowledged = false;
            clearCrashReport();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(runtimeIntent);
            } else {
                startService(runtimeIntent);
            }
            lastRuntimeGeneration = Long.MIN_VALUE;
            lastSessionRevision = Long.MIN_VALUE;
        } catch (Throwable error) {
            pendingLaunchDangerWarningAcknowledged = false;
            persistCrash("settings-start-service", error);
            showInlineFailure(error);
            Toast.makeText(
                this,
                getString(
                    R.string.settings_runtime_launch_failed,
                    error.getClass().getSimpleName()
                ),
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
            Toast.makeText(
                this,
                R.string.account_login_url_rejected,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentLoginUrl)));
        } catch (Throwable error) {
            Toast.makeText(
                this,
                R.string.account_login_page_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(
                this,
                R.string.diagnostics_clipboard_unavailable,
                Toast.LENGTH_SHORT
            ).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
            getString(R.string.diagnostics_clip_label),
            localizedRuntimeReport(AgentRuntimeService.snapshot())
        ));
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show();
    }

    private String localizedRuntimeReport(RuntimeSnapshot snapshot) {
        StringBuilder report = new StringBuilder();
        report.append(BuildIdentity.summary()).append('\n');
        report.append(UiText.phase(this, snapshot.getPhase())).append('\n');
        report.append(UiText.runtimeMessage(this, snapshot)).append('\n');
        if (!snapshot.getEngineVersion().isEmpty()) {
            report.append(getString(R.string.settings_engine_label))
                .append(": ").append(snapshot.getEngineVersion()).append('\n');
        }
        if (!snapshot.getDiagnostics().isEmpty()) {
            report.append(getString(R.string.settings_diagnostics_label))
                .append(": ").append(snapshot.getDiagnostics()).append('\n');
        }
        if (!snapshot.getWorkspacePath().isEmpty()) {
            report.append(getString(R.string.settings_workspace_label))
                .append(": ").append(snapshot.getWorkspacePath()).append('\n');
        }
        return report.toString();
    }

    private void addCrashReportCard(LinearLayout page, final String report) {
        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.crash_section)),
            24
        );
        LinearLayout card = theme.card();
        TextView explanation = theme.body(getString(R.string.crash_explanation));
        card.addView(explanation);
        TextView reportView = theme.text(report, 12, theme.secondary);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        theme.addWithTopMargin(card, reportView, 12);
        Button clearButton = theme.secondaryButton(getString(R.string.crash_delete));
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (clearCrashReport()) {
                    recreate();
                }
            }
        });
        theme.addWithTopMargin(card, clearButton, 12);
        theme.addWithTopMargin(page, card, 10);
    }

    private boolean clearCrashReport() {
        try {
            if (crashDiagnostics == null) {
                crashDiagnostics = CrashDiagnostics.open(getFilesDir());
            }
            crashDiagnostics.clear();
            return true;
        } catch (Throwable error) {
            Toast.makeText(
                this,
                R.string.crash_delete_failed,
                Toast.LENGTH_SHORT
            ).show();
            return false;
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
        phaseView.setText(R.string.runtime_phase_failed);
        phaseView.setTextColor(theme.danger);
        runtimeMessageView.setText(R.string.settings_runtime_failure_caught);
        technicalView.setText(
            error.getClass().getName() + "\n" + getString(R.string.settings_report_saved)
        );
    }

    private void showEmergencyScreen(String source, Throwable error) {
        TextView fallback = new TextView(this);
        fallback.setPadding(32, 48, 32, 48);
        fallback.setTextColor(0xFF111827);
        fallback.setBackgroundColor(0xFFF5F7FB);
        fallback.setTextSize(14);
        fallback.setTextIsSelectable(true);
        fallback.setText(
            getString(R.string.settings_initialization_failed) + "\n\n"
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

    private String authLabel(String authMode) {
        if ("chatgpt".equalsIgnoreCase(authMode)) {
            return "ChatGPT";
        }
        if ("apiKey".equalsIgnoreCase(authMode) || "apikey".equalsIgnoreCase(authMode)) {
            return getString(R.string.account_api_key_label);
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
