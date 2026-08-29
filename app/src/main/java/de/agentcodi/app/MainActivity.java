package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexAppMention;
import de.agentcodi.core.CodexFileMentionTransaction;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexReasoningOption;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexThreadSummary;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.CredentialGuard;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.UiStartupState;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportGrant;
import de.agentcodi.imports.WorkspaceImportLimits;
import de.agentcodi.imports.WorkspaceImportSelection;
import de.agentcodi.runtime.AgentRuntimeService;
import de.agentcodi.runtime.CrashDiagnostics;
import de.agentcodi.runtime.WorkspaceFileImporter;
import de.agentcodi.runtime.WorkspaceImageExporter;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends Activity {
    private static final long ACTIVE_REFRESH_INTERVAL_MS = 250L;
    private static final long IDLE_REFRESH_INTERVAL_MS = 900L;
    private static final int MAX_VISIBLE_THREADS = 80;
    private static final int IMAGE_EXPORT_REQUEST_CODE = 7001;
    private static final int FILE_IMPORT_REQUEST_CODE = 7002;
    private static final int CONNECTOR_REQUEST_CODE = 7003;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UiStartupState startupState = new UiStartupState();
    private final List<String> renderedTranscriptKeys = new ArrayList<String>();
    private final List<TranscriptRow> renderedTranscriptRows =
        new ArrayList<TranscriptRow>();
    private final ExecutorService imageOperations = Executors.newSingleThreadExecutor();
    private final ExecutorService importOperations = Executors.newSingleThreadExecutor();
    private final Object preparedImportSendLock = new Object();
    private final List<ImportedWorkspaceFile> pendingImports =
        new ArrayList<ImportedWorkspaceFile>();
    private final List<ConnectorSelection> pendingConnectors =
        new ArrayList<ConnectorSelection>();
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
    private LinearLayout statusBanner;
    private TextView statusText;
    private ImageButton statusSettingsButton;
    private ImageButton backToThreadsButton;
    private TextView screenTitle;
    private LinearLayout threadPage;
    private LinearLayout conversationPage;
    private ImageButton newThreadButton;
    private ImageButton refreshThreadsButton;
    private ImageButton activeThreadsButton;
    private ImageButton archivedThreadsButton;
    private TextView threadEmptyView;
    private ListView threadList;
    private ThreadAdapter threadAdapter;
    private Spinner modelSpinner;
    private Spinner effortSpinner;
    private TextView modelDescription;
    private ScrollView messageScroll;
    private LinearLayout messagesContainer;
    private EditText composerInput;
    private LinearLayout connectorStatusRow;
    private TextView connectorStatus;
    private ImageButton connectorButton;
    private ImageButton clearConnectorsButton;
    private LinearLayout importStatusRow;
    private TextView importStatus;
    private ImageButton importButton;
    private ImageButton clearImportsButton;
    private ImageButton reviewButton;
    private ImageButton sendButton;
    private ImageButton stopButton;
    private boolean bindingSelectors;
    private boolean conversationVisible;
    private String pendingThreadId = "";
    private boolean pendingNewThread;
    private String newThreadBaseline = "";
    private String renderedThreadId = "";
    private String pendingImageExportPath = "";
    private String pendingImportsThreadId = "";
    private String pendingConnectorsThreadId = "";
    private boolean importOperationActive;
    private boolean sendPreparationActive;
    private CodexFileMentionTransaction preparedImportSend;
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
            startupState.enter("chat-theme");
            theme = new UiTheme(this);
            interactiveRequestDialog = new InteractiveRequestDialog(this, theme);
            startupState.enter("chat-content");
            setContentView(buildContent());
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

    @Override
    protected void onDestroy() {
        CodexFileMentionTransaction abandonedSend;
        synchronized (preparedImportSendLock) {
            destroyed = true;
            abandonedSend = preparedImportSend;
            preparedImportSend = null;
        }
        closeFileTransaction(abandonedSend);
        handler.removeCallbacksAndMessages(null);
        imageOperations.shutdownNow();
        importOperations.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CONNECTOR_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                acceptConnectorSelection(data);
            }
            return;
        }
        if (requestCode == FILE_IMPORT_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                WorkspaceImportGrant sourceGrant =
                    WorkspaceImportGrant.fromResultIntentFlags(
                        data.getFlags(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                if (!sourceGrant.hasTransientReadPermission()) {
                    Toast.makeText(
                        this,
                        R.string.chat_import_read_grant_missing,
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }
                beginDocumentImport(selectedDocumentUris(data), sourceGrant);
            }
            return;
        }
        if (requestCode != IMAGE_EXPORT_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        final String sourcePath = pendingImageExportPath;
        pendingImageExportPath = "";
        final Uri destination = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || destination == null || sourcePath.isEmpty()) {
            return;
        }
        final android.content.Context applicationContext = getApplicationContext();
        if (!submitImageOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceImageExporter.ImageExport exported =
                        WorkspaceImageExporter.export(
                            applicationContext,
                            sourcePath,
                            destination
                    );
                    showExportToast(
                        getString(R.string.image_exported, exported.getDisplayName()),
                        Toast.LENGTH_LONG
                    );
                } catch (Throwable error) {
                    showExportFailure(sourcePath, error);
                }
            }
        })) {
            showExportToast(
                getString(R.string.image_export_start_failed),
                Toast.LENGTH_LONG
            );
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

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(theme.dp(16), theme.dp(18), theme.dp(16), theme.dp(12));
        root.setBackgroundColor(theme.page);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        backToThreadsButton = theme.iconButton(
            R.drawable.ic_chat_back,
            getString(R.string.navigation_chats)
        );
        backToThreadsButton.setVisibility(View.GONE);
        backToThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showThreadPage();
            }
        });
        topBar.addView(backToThreadsButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        screenTitle = theme.text(getString(R.string.chat_title), 24, theme.primary);
        screenTitle.setTypeface(Typeface.DEFAULT_BOLD);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(8);
        titleParams.rightMargin = theme.dp(4);
        topBar.addView(screenTitle, titleParams);

        ImageButton filesButton = theme.iconButton(
            R.drawable.ic_chat_folder,
            getString(R.string.navigation_files)
        );
        filesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openFiles();
            }
        });
        topBar.addView(filesButton, iconMarginParams(0));

        ImageButton terminalButton = theme.iconButton(
            R.drawable.ic_chat_terminal,
            getString(R.string.navigation_terminal)
        );
        terminalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openTerminal();
            }
        });
        topBar.addView(terminalButton, iconMarginParams(4));

        ImageButton settingsButton = theme.iconButton(
            R.drawable.ic_chat_settings,
            getString(R.string.navigation_settings)
        );
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSettings();
            }
        });
        topBar.addView(settingsButton, iconMarginParams(4));

        root.addView(topBar);

        statusBanner = new LinearLayout(this);
        statusBanner.setOrientation(LinearLayout.HORIZONTAL);
        statusBanner.setGravity(Gravity.CENTER_VERTICAL);
        statusBanner.setPadding(theme.dp(14), theme.dp(12), theme.dp(12), theme.dp(12));
        statusBanner.setBackground(theme.background(theme.surfaceRaised, theme.border, 14));
        statusText = theme.text(getString(R.string.chat_runtime_checking), 13, theme.primary);
        statusText.setLineSpacing(0.0f, 1.15f);
        statusBanner.addView(statusText, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        statusSettingsButton = theme.iconButton(
            R.drawable.ic_chat_settings,
            getString(R.string.chat_open_settings)
        );
        statusSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSettings();
            }
        });
        LinearLayout.LayoutParams statusActionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusActionParams.leftMargin = theme.dp(10);
        statusBanner.addView(statusSettingsButton, statusActionParams);
        theme.addWithTopMargin(root, statusBanner, 14);

        threadPage = buildThreadPage();
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        contentParams.topMargin = theme.dp(14);
        root.addView(threadPage, contentParams);

        conversationPage = buildConversationPage();
        conversationPage.setVisibility(View.GONE);
        LinearLayout.LayoutParams conversationParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        conversationParams.topMargin = theme.dp(14);
        root.addView(conversationPage, conversationParams);
        return root;
    }

    private LinearLayout buildThreadPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView intro = theme.text(getString(R.string.chat_intro), 14, theme.secondary);
        actions.addView(intro, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        newThreadButton = theme.primaryIconButton(
            R.drawable.ic_chat_add_thread,
            getString(R.string.chat_new)
        );
        newThreadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
                pendingNewThread = true;
                pendingThreadId = "";
                newThreadBaseline = snapshot.getActiveThreadId();
                AgentRuntimeService.startNewThread();
            }
        });
        actions.addView(newThreadButton, iconMarginParams(8));

        refreshThreadsButton = theme.iconButton(
            R.drawable.ic_chat_refresh,
            getString(R.string.chat_refresh)
        );
        refreshThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshThreads();
            }
        });
        actions.addView(refreshThreadsButton, iconMarginParams(4));
        page.addView(actions);

        LinearLayout threadViews = new LinearLayout(this);
        threadViews.setOrientation(LinearLayout.HORIZONTAL);
        threadViews.setGravity(Gravity.END);
        activeThreadsButton = theme.iconButton(
            R.drawable.ic_chat_active_threads,
            getString(R.string.chat_active_threads)
        );
        activeThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.showActiveThreads();
            }
        });
        threadViews.addView(activeThreadsButton);
        archivedThreadsButton = theme.iconButton(
            R.drawable.ic_chat_archived_threads,
            getString(R.string.chat_archived_threads)
        );
        archivedThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.showArchivedThreads();
            }
        });
        threadViews.addView(archivedThreadsButton, iconMarginParams(6));
        theme.addWithTopMargin(page, threadViews, 8);

        threadEmptyView = theme.text(
            getString(R.string.chat_empty),
            15,
            theme.secondary
        );
        threadEmptyView.setGravity(Gravity.CENTER);
        threadEmptyView.setPadding(
            theme.dp(24),
            theme.dp(48),
            theme.dp(24),
            theme.dp(48)
        );
        page.addView(threadEmptyView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));

        threadList = new ListView(this);
        threadList.setBackground(theme.background(theme.surface, theme.border, 18));
        threadList.setDivider(new ColorDrawable(theme.border));
        threadList.setDividerHeight(theme.dp(1));
        threadList.setClipToPadding(false);
        threadList.setPadding(0, theme.dp(4), 0, theme.dp(4));
        threadAdapter = new ThreadAdapter();
        threadList.setAdapter(threadAdapter);
        threadList.setEmptyView(threadEmptyView);
        threadList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                CodexThreadSummary thread = threadAdapter.item(position);
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
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        listParams.topMargin = theme.dp(12);
        page.addView(threadList, listParams);
        return page;
    }

    private LinearLayout buildConversationPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout selectors = new LinearLayout(this);
        selectors.setOrientation(LinearLayout.VERTICAL);
        selectors.setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12));
        selectors.setBackground(theme.background(theme.surface, theme.border, 16));

        LinearLayout selectorRow = new LinearLayout(this);
        selectorRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout modelColumn = new LinearLayout(this);
        modelColumn.setOrientation(LinearLayout.VERTICAL);
        TextView modelLabel = theme.sectionLabel(getString(R.string.model_section));
        modelColumn.addView(modelLabel);
        modelSpinner = new Spinner(this);
        modelColumn.addView(modelSpinner);
        selectorRow.addView(modelColumn, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.15f
        ));

        LinearLayout effortColumn = new LinearLayout(this);
        effortColumn.setOrientation(LinearLayout.VERTICAL);
        effortColumn.setPadding(theme.dp(10), 0, 0, 0);
        effortColumn.addView(theme.sectionLabel(getString(R.string.reasoning_effort_section)));
        effortSpinner = new Spinner(this);
        effortColumn.addView(effortSpinner);
        selectorRow.addView(effortColumn, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.85f
        ));
        selectors.addView(selectorRow);
        modelDescription = theme.text(
            getString(R.string.models_loading),
            12,
            theme.secondary
        );
        modelDescription.setLineSpacing(0.0f, 1.15f);
        theme.addWithTopMargin(selectors, modelDescription, 6);
        page.addView(selectors);

        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelectors) {
                    return;
                }
                CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
                List<CodexModelOption> models = snapshot.getModels();
                if (position >= 0 && position < models.size()
                    && !models.get(position).getId().equals(snapshot.getSelectedModelId())) {
                    AgentRuntimeService.selectModel(models.get(position).getId());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        effortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelectors) {
                    return;
                }
                CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
                CodexModelOption model = selectedModel(snapshot);
                if (model != null && position >= 0
                    && position < model.getReasoningOptions().size()
                    && !model.getReasoningOptions().get(position).getEffort().equals(
                        snapshot.getSelectedReasoningEffort()
                    )) {
                    AgentRuntimeService.selectReasoningEffort(model
                        .getReasoningOptions()
                        .get(position)
                        .getEffort());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(0, theme.dp(14), 0, theme.dp(10));
        messageScroll.addView(messagesContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        page.addView(messageScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
        composer.setBackground(theme.background(theme.surface, theme.border, 16));

        connectorStatusRow = new LinearLayout(this);
        connectorStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        connectorStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        connectorStatusRow.setVisibility(View.GONE);
        connectorStatus = theme.text("", 12, theme.secondary);
        connectorStatus.setLineSpacing(0.0f, 1.15f);
        connectorStatus.setMaxLines(3);
        connectorStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectorStatusRow.addView(connectorStatus, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        clearConnectorsButton = theme.iconButton(
            R.drawable.ic_chat_detach,
            getString(R.string.chat_connector_detach)
        );
        clearConnectorsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                detachPendingConnectors();
            }
        });
        connectorStatusRow.addView(clearConnectorsButton, iconMarginParams(8));
        composer.addView(connectorStatusRow);

        importStatusRow = new LinearLayout(this);
        importStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        importStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        importStatusRow.setVisibility(View.GONE);
        importStatus = theme.text("", 12, theme.secondary);
        importStatus.setLineSpacing(0.0f, 1.15f);
        importStatus.setMaxLines(4);
        importStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        importStatusRow.addView(importStatus, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        clearImportsButton = theme.iconButton(
            R.drawable.ic_chat_detach,
            getString(R.string.chat_import_detach)
        );
        clearImportsButton.setVisibility(View.GONE);
        clearImportsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                detachPendingImports();
            }
        });
        importStatusRow.addView(clearImportsButton, iconMarginParams(8));
        composer.addView(importStatusRow);

        LinearLayout composerRow = new LinearLayout(this);
        composerRow.setOrientation(LinearLayout.HORIZONTAL);
        composerRow.setGravity(Gravity.CENTER_VERTICAL);
        importButton = theme.iconButton(
            R.drawable.ic_chat_add,
            getString(R.string.chat_import_files)
        );
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openDocumentImportPicker();
            }
        });
        composerRow.addView(importButton);

        connectorButton = theme.iconButton(
            R.drawable.ic_chat_connectors,
            getString(R.string.chat_connectors)
        );
        connectorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openConnectorPicker();
            }
        });
        composerRow.addView(connectorButton, iconMarginParams(4));

        composerInput = new EditText(this);
        composerInput.setHint(R.string.composer_hint);
        composerInput.setHintTextColor(theme.secondary);
        composerInput.setTextColor(theme.primary);
        composerInput.setMinLines(2);
        composerInput.setMaxLines(7);
        composerInput.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        inputParams.leftMargin = theme.dp(6);
        inputParams.rightMargin = theme.dp(6);
        composerRow.addView(composerInput, inputParams);

        reviewButton = theme.iconButton(
            R.drawable.ic_chat_review,
            getString(R.string.review_mode_action)
        );
        reviewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ReviewModeDialog.show(
                    MainActivity.this,
                    theme,
                    AgentRuntimeService.maximumReviewInstructionsCharacters(),
                    new ReviewModeDialog.Starter() {
                        @Override
                        public boolean start(String instructions) {
                            return AgentRuntimeService.startCustomReview(instructions);
                        }
                    }
                );
            }
        });
        composerRow.addView(reviewButton);

        stopButton = theme.dangerIconButton(
            R.drawable.ic_chat_stop,
            getString(R.string.turn_stop)
        );
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.interruptTurn();
            }
        });
        composerRow.addView(stopButton);

        sendButton = theme.primaryIconButton(
            R.drawable.ic_chat_send,
            getString(R.string.message_send)
        );
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendComposerInput();
            }
        });
        composerRow.addView(sendButton, iconMarginParams(6));
        theme.addWithTopMargin(composer, composerRow, 6);
        page.addView(composer);
        return page;
    }

    private LinearLayout.LayoutParams iconMarginParams(int leftMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.leftMargin = theme.dp(leftMarginDp);
        return params;
    }

    private void sendComposerInput() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        Editable editable = composerInput.getText();
        if (CredentialGuard.containsLikelyCredential(editable)) {
            editable.clear();
            Toast.makeText(
                MainActivity.this,
                R.string.user_input_credential_warning,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        final String prompt = editable.toString();
        if (prompt.trim().isEmpty() && pendingImports.isEmpty()) {
            return;
        }
        final CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        if (!pendingImports.isEmpty()
            && !snapshot.getActiveThreadId().equals(pendingImportsThreadId)) {
            Toast.makeText(this, R.string.chat_import_context_changed, Toast.LENGTH_LONG).show();
            detachPendingImports();
            return;
        }
        if (!pendingConnectors.isEmpty()
            && !snapshot.getActiveThreadId().equals(pendingConnectorsThreadId)) {
            Toast.makeText(
                this,
                R.string.chat_connector_context_changed,
                Toast.LENGTH_LONG
            ).show();
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
            refreshLocalComposerState();
            return;
        }
        final List<ConnectorSelection> connectorSelections =
            ConnectorSelection.copyOf(pendingConnectors);
        if (!connectorSelections.isEmpty()
            && !AgentRuntimeService.areConnectorsCallable(connectorSelections)) {
            AgentRuntimeService.refreshConnectorCatalog(true);
            Toast.makeText(
                this,
                R.string.chat_connector_unavailable,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        final List<CodexAppMention> appMentions = appMentions(connectorSelections);
        if (pendingImports.isEmpty()) {
            boolean accepted = snapshot.isTurnActive()
                ? AgentRuntimeService.steerTurn(prompt, appMentions)
                : AgentRuntimeService.sendMessage(prompt, appMentions);
            if (accepted) {
                editable.clear();
                pendingConnectors.clear();
                pendingConnectorsThreadId = "";
                refreshLocalComposerState();
            }
            return;
        }

        final List<ImportedWorkspaceFile> files = WorkspaceImportSelection.copyOf(
            pendingImports
        );
        final boolean steering = snapshot.isTurnActive();
        final String threadId = snapshot.getActiveThreadId();
        final String turnId = snapshot.getActiveTurnId();
        final android.content.Context applicationContext = getApplicationContext();
        sendPreparationActive = true;
        refreshLocalComposerState();
        if (!submitImportOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final CodexFileMentionTransaction fileTransaction =
                        WorkspaceFileImporter.prepareForCodex(
                            applicationContext,
                            files
                        );
                    boolean posted = false;
                    try {
                        if (!registerPreparedImportSend(fileTransaction)) {
                            return;
                        }
                        posted = handler.post(new Runnable() {
                            @Override
                            public void run() {
                                completePreparedSend(
                                    prompt,
                                    files,
                                    fileTransaction,
                                    steering,
                                    threadId,
                                    turnId,
                                    connectorSelections,
                                    appMentions
                                );
                            }
                        });
                    } finally {
                        if (!posted) {
                            releasePreparedImportSend(fileTransaction);
                            closeFileTransaction(fileTransaction);
                        }
                    }
                } catch (Throwable error) {
                    final String reason = importFailureReason(error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            sendPreparationActive = false;
                            refreshLocalComposerState();
                            if (!destroyed && !isFinishing()) {
                                Toast.makeText(
                                    MainActivity.this,
                                    getString(R.string.chat_import_verify_failed, reason),
                                    Toast.LENGTH_LONG
                                ).show();
                            }
                        }
                    });
                }
            }
        })) {
            sendPreparationActive = false;
            refreshLocalComposerState();
            Toast.makeText(
                this,
                R.string.chat_import_operation_start_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void completePreparedSend(
        String prompt,
        List<ImportedWorkspaceFile> preparedFiles,
        CodexFileMentionTransaction fileTransaction,
        boolean steering,
        String threadId,
        String turnId,
        List<ConnectorSelection> preparedConnectors,
        List<CodexAppMention> appMentions
    ) {
        if (!releasePreparedImportSend(fileTransaction)) {
            closeFileTransaction(fileTransaction);
            return;
        }
        if (destroyed) {
            closeFileTransaction(fileTransaction);
            return;
        }
        sendPreparationActive = false;
        CodexSessionSnapshot current = AgentRuntimeService.sessionSnapshot();
        boolean sameContext = threadId.equals(current.getActiveThreadId())
            && steering == current.isTurnActive()
            && (!steering || turnId.equals(current.getActiveTurnId()))
            && preparedFiles.equals(WorkspaceImportSelection.copyOf(pendingImports))
            && preparedConnectors.equals(ConnectorSelection.copyOf(pendingConnectors))
            && (preparedConnectors.isEmpty()
                || AgentRuntimeService.areConnectorsCallable(preparedConnectors));
        if (!sameContext) {
            closeFileTransaction(fileTransaction);
            refreshLocalComposerState();
            Toast.makeText(
                this,
                R.string.chat_import_context_changed,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        boolean accepted = steering
            ? AgentRuntimeService.steerTurn(prompt, fileTransaction, appMentions)
            : AgentRuntimeService.sendMessage(prompt, fileTransaction, appMentions);
        if (accepted) {
            composerInput.getText().clear();
            pendingImports.clear();
            pendingImportsThreadId = "";
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
        }
        refreshLocalComposerState();
    }

    private boolean registerPreparedImportSend(
        CodexFileMentionTransaction fileTransaction
    ) {
        synchronized (preparedImportSendLock) {
            if (destroyed || preparedImportSend != null) {
                return false;
            }
            preparedImportSend = fileTransaction;
            return true;
        }
    }

    private boolean releasePreparedImportSend(
        CodexFileMentionTransaction fileTransaction
    ) {
        synchronized (preparedImportSendLock) {
            if (preparedImportSend != fileTransaction) {
                return false;
            }
            preparedImportSend = null;
            return true;
        }
    }

    private static void closeFileTransaction(
        CodexFileMentionTransaction fileTransaction
    ) {
        if (fileTransaction == null) {
            return;
        }
        try {
            fileTransaction.close();
        } catch (IOException ignored) {
            // The verified batch is already unusable and remains fail-closed.
        }
    }

    private void openDocumentImportPicker() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        if (snapshot.getActiveThreadId().isEmpty()) {
            Toast.makeText(this, R.string.chat_import_requires_chat, Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingImports.size() >= WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE
            || WorkspaceImportSelection.totalBytes(pendingImports)
                >= WorkspaceImportLimits.MAXIMUM_TOTAL_BYTES) {
            Toast.makeText(this, R.string.chat_import_limit_reached, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, FILE_IMPORT_REQUEST_CODE);
        } catch (Throwable error) {
            Toast.makeText(
                this,
                R.string.document_picker_open_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private List<Uri> selectedDocumentUris(Intent data) {
        List<Uri> selected = new ArrayList<Uri>();
        ClipData clipData = data.getClipData();
        int maximum = WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE + 1;
        if (clipData != null) {
            int count = Math.min(clipData.getItemCount(), maximum);
            for (int index = 0; index < count; index++) {
                ClipData.Item item = clipData.getItemAt(index);
                if (item != null && item.getUri() != null) {
                    selected.add(item.getUri());
                }
            }
        } else if (data.getData() != null) {
            selected.add(data.getData());
        }
        return selected;
    }

    private void beginDocumentImport(
        final List<Uri> sourceUris,
        final WorkspaceImportGrant sourceGrant
    ) {
        if (sourceUris == null || sourceUris.isEmpty()
            || sourceGrant == null || !sourceGrant.hasTransientReadPermission()
            || importOperationActive || sendPreparationActive) {
            return;
        }
        final CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        final String threadId = snapshot.getActiveThreadId();
        if (threadId.isEmpty()) {
            Toast.makeText(this, R.string.chat_import_requires_chat, Toast.LENGTH_LONG).show();
            return;
        }
        int remainingFiles = WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE
            - pendingImports.size();
        if (sourceUris.size() > remainingFiles) {
            Toast.makeText(this, R.string.chat_import_too_many, Toast.LENGTH_LONG).show();
            return;
        }
        final long remainingBytes = WorkspaceImportLimits.MAXIMUM_TOTAL_BYTES
            - WorkspaceImportSelection.totalBytes(pendingImports);
        if (remainingBytes <= 0L) {
            Toast.makeText(this, R.string.chat_import_limit_reached, Toast.LENGTH_LONG).show();
            return;
        }
        if (!pendingImports.isEmpty() && !threadId.equals(pendingImportsThreadId)) {
            detachPendingImports();
        }
        pendingImportsThreadId = threadId;
        importOperationActive = true;
        refreshLocalComposerState();
        final android.content.Context applicationContext = getApplicationContext();
        if (!submitImportOperation(new Runnable() {
            @Override
            public void run() {
                importSelectedDocuments(
                    applicationContext,
                    threadId,
                    sourceUris,
                    sourceGrant,
                    remainingBytes
                );
            }
        })) {
            importOperationActive = false;
            if (pendingImports.isEmpty()) {
                pendingImportsThreadId = "";
            }
            refreshLocalComposerState();
            Toast.makeText(
                this,
                R.string.chat_import_operation_start_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void importSelectedDocuments(
        android.content.Context applicationContext,
        String threadId,
        List<Uri> sourceUris,
        WorkspaceImportGrant sourceGrant,
        long initialRemainingBytes
    ) {
        final List<ImportedWorkspaceFile> imported =
            new ArrayList<ImportedWorkspaceFile>();
        int failures = 0;
        String firstFailure = "";
        long remainingBytes = initialRemainingBytes;
        for (Uri sourceUri : sourceUris) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            if (remainingBytes <= 0L) {
                failures++;
                if (firstFailure.isEmpty()) {
                    firstFailure = getString(R.string.error_reason_limit);
                }
                continue;
            }
            try {
                ImportedWorkspaceFile file = WorkspaceFileImporter.importDocument(
                    applicationContext,
                    sourceUri,
                    sourceGrant,
                    remainingBytes
                );
                imported.add(file);
                remainingBytes -= file.getByteCount();
            } catch (Throwable error) {
                failures++;
                if (firstFailure.isEmpty()) {
                    firstFailure = importFailureReason(error);
                }
            }
        }
        final int failedCount = failures;
        final String failureReason = firstFailure;
        handler.post(new Runnable() {
            @Override
            public void run() {
                completeDocumentImport(threadId, imported, failedCount, failureReason);
            }
        });
    }

    private void completeDocumentImport(
        String threadId,
        List<ImportedWorkspaceFile> imported,
        int failedCount,
        String failureReason
    ) {
        if (destroyed) {
            return;
        }
        importOperationActive = false;
        boolean sameContext = threadId.equals(
            AgentRuntimeService.sessionSnapshot().getActiveThreadId()
        ) && threadId.equals(pendingImportsThreadId);
        if (sameContext) {
            List<ImportedWorkspaceFile> combined =
                new ArrayList<ImportedWorkspaceFile>(pendingImports);
            combined.addAll(imported);
            try {
                List<ImportedWorkspaceFile> validated =
                    WorkspaceImportSelection.copyOf(combined);
                pendingImports.clear();
                pendingImports.addAll(validated);
            } catch (IllegalArgumentException error) {
                failedCount += imported.size();
            }
        }
        if (pendingImports.isEmpty()) {
            pendingImportsThreadId = "";
        }
        refreshLocalComposerState();
        if (!sameContext) {
            Toast.makeText(
                this,
                R.string.chat_import_context_changed,
                Toast.LENGTH_LONG
            ).show();
        } else if (!imported.isEmpty() && failedCount == 0) {
            Toast.makeText(
                this,
                getResources().getQuantityString(
                    R.plurals.chat_import_completed,
                    imported.size(),
                    Integer.valueOf(imported.size())
                ),
                Toast.LENGTH_SHORT
            ).show();
        } else if (!imported.isEmpty()) {
            Toast.makeText(
                this,
                getString(
                    R.string.chat_import_partial,
                    Integer.valueOf(imported.size()),
                    Integer.valueOf(failedCount)
                ),
                Toast.LENGTH_LONG
            ).show();
        } else if (failedCount > 0) {
            Toast.makeText(
                this,
                getString(
                    R.string.chat_import_failed,
                    failureReason.isEmpty()
                        ? getString(R.string.common_unknown_error)
                        : failureReason
                ),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void detachPendingImports() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        boolean hadImports = !pendingImports.isEmpty();
        pendingImports.clear();
        pendingImportsThreadId = "";
        refreshLocalComposerState();
        if (hadImports) {
            Toast.makeText(
                this,
                R.string.chat_import_detached_notice,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openConnectorPicker() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        String threadId = snapshot.getActiveThreadId();
        if (!snapshot.isReady() || threadId.isEmpty()) {
            Toast.makeText(
                this,
                R.string.chat_connector_requires_chat,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        try {
            startActivityForResult(
                ConnectorActivity.createIntent(this, threadId, pendingConnectors),
                CONNECTOR_REQUEST_CODE
            );
        } catch (Throwable error) {
            Toast.makeText(
                this,
                R.string.chat_connector_picker_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void acceptConnectorSelection(Intent data) {
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        String returnedThreadId = ConnectorActivity.resultThreadId(data);
        final List<ConnectorSelection> selections;
        try {
            selections = ConnectorActivity.resultSelections(data);
        } catch (RuntimeException error) {
            Toast.makeText(
                this,
                R.string.chat_connector_unavailable,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (!snapshot.isReady()
            || returnedThreadId.isEmpty()
            || !returnedThreadId.equals(snapshot.getActiveThreadId())
            || (!selections.isEmpty()
                && !AgentRuntimeService.areConnectorsCallable(selections))) {
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
            AgentRuntimeService.refreshConnectorCatalog(true);
            refreshLocalComposerState();
            Toast.makeText(
                this,
                R.string.chat_connector_context_changed,
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        pendingConnectors.clear();
        pendingConnectors.addAll(selections);
        pendingConnectorsThreadId = selections.isEmpty() ? "" : returnedThreadId;
        refreshLocalComposerState();
    }

    private void detachPendingConnectors() {
        if (importOperationActive || sendPreparationActive) {
            return;
        }
        boolean hadConnectors = !pendingConnectors.isEmpty();
        pendingConnectors.clear();
        pendingConnectorsThreadId = "";
        refreshLocalComposerState();
        if (hadConnectors) {
            Toast.makeText(
                this,
                R.string.chat_connector_detached_notice,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void reconcilePendingConnectors(CodexSessionSnapshot session) {
        if (!pendingConnectors.isEmpty()
            && !session.getActiveThreadId().equals(pendingConnectorsThreadId)) {
            pendingConnectors.clear();
            pendingConnectorsThreadId = "";
        }
    }

    private void renderConnectorSelection() {
        if (pendingConnectors.isEmpty()) {
            connectorStatusRow.setVisibility(View.GONE);
            connectorStatus.setVisibility(View.GONE);
            connectorStatus.setText("");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (ConnectorSelection selection : pendingConnectors) {
            if (names.length() != 0) {
                names.append(" · ");
            }
            names.append(selection.getProvider().getDisplayName());
        }
        connectorStatus.setText(getResources().getQuantityString(
            R.plurals.chat_connector_selected,
            pendingConnectors.size(),
            names.toString()
        ));
        connectorStatusRow.setVisibility(View.VISIBLE);
        connectorStatus.setVisibility(View.VISIBLE);
    }

    private static List<CodexAppMention> appMentions(
        List<ConnectorSelection> selections
    ) {
        List<ConnectorSelection> safe = ConnectorSelection.copyOf(selections);
        List<CodexAppMention> mentions = new ArrayList<CodexAppMention>(safe.size());
        for (ConnectorSelection selection : safe) {
            mentions.add(CodexAppMention.create(
                selection.getId(),
                selection.getName()
            ));
        }
        return java.util.Collections.unmodifiableList(mentions);
    }

    private void reconcilePendingImports(CodexSessionSnapshot session) {
        if (!pendingImports.isEmpty()
            && !session.getActiveThreadId().equals(pendingImportsThreadId)) {
            pendingImports.clear();
            pendingImportsThreadId = "";
        }
    }

    private void renderImportSelection() {
        if (importOperationActive) {
            importStatusRow.setVisibility(View.VISIBLE);
            importStatus.setVisibility(View.VISIBLE);
            importStatus.setText(R.string.chat_import_importing);
            return;
        }
        if (sendPreparationActive) {
            importStatusRow.setVisibility(View.VISIBLE);
            importStatus.setVisibility(View.VISIBLE);
            importStatus.setText(R.string.chat_import_verifying);
            return;
        }
        if (pendingImports.isEmpty()) {
            importStatusRow.setVisibility(View.GONE);
            importStatus.setVisibility(View.GONE);
            importStatus.setText("");
            return;
        }
        long totalBytes = WorkspaceImportSelection.totalBytes(pendingImports);
        StringBuilder names = new StringBuilder();
        int visible = Math.min(3, pendingImports.size());
        for (int index = 0; index < visible; index++) {
            if (names.length() != 0) {
                names.append(" · ");
            }
            names.append(pendingImports.get(index).getDisplayName());
        }
        if (pendingImports.size() > visible) {
            names.append(" · …");
        }
        String summary = getResources().getQuantityString(
            R.plurals.chat_import_selected,
            pendingImports.size(),
            Integer.valueOf(pendingImports.size()),
            readableByteCount(totalBytes)
        );
        importStatus.setText(summary + "\n" + names.toString());
        importStatusRow.setVisibility(View.VISIBLE);
        importStatus.setVisibility(View.VISIBLE);
    }

    private boolean submitImportOperation(Runnable operation) {
        if (destroyed || importOperations.isShutdown()) {
            return false;
        }
        try {
            importOperations.execute(operation);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void refreshLocalComposerState() {
        if (destroyed || !startupState.shouldRefresh()) {
            return;
        }
        lastSessionRevision = Long.MIN_VALUE;
        render(AgentRuntimeService.snapshot(), AgentRuntimeService.sessionSnapshot());
    }

    private String importFailureReason(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return getString(R.string.common_unknown_error);
        }
        return UiText.errorReason(
            this,
            CrashReportFormatter.redactVisibleText(message, 180)
        );
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
        reconcilePendingImports(session);
        reconcilePendingConnectors(session);
        renderStatus(runtime, session);
        boolean actionReady = session.isReady()
            && !session.isOperationActive()
            && !session.isTurnInterruptPending();
        boolean canChat = actionReady
            && (!session.requiresOpenaiAuth() || session.isSignedIn());
        theme.setEnabled(refreshThreadsButton, canChat);
        boolean interactionOpen = session.hasInteractiveRequest();
        boolean threadNavigationReady = canChat
            && !session.isTurnActive()
            && !interactionOpen;
        theme.setEnabled(
            activeThreadsButton,
            threadNavigationReady && session.isShowingArchivedThreads()
        );
        theme.setEnabled(
            archivedThreadsButton,
            threadNavigationReady && !session.isShowingArchivedThreads()
        );
        newThreadButton.setVisibility(
            session.isShowingArchivedThreads() ? View.GONE : View.VISIBLE
        );
        theme.setEnabled(
            newThreadButton,
            threadNavigationReady
        );
        threadEmptyView.setText(
            session.isShowingArchivedThreads()
                ? R.string.chat_archived_empty
                : R.string.chat_empty
        );
        boolean steering = session.isTurnActive();
        composerInput.setHint(
            steering ? R.string.composer_steer_hint : R.string.composer_hint
        );
        theme.setIcon(
            sendButton,
            R.drawable.ic_chat_send,
            getString(steering ? R.string.turn_steer : R.string.message_send)
        );
        boolean composerReady = canChat && !interactionOpen
            && !importOperationActive && !sendPreparationActive;
        composerInput.setEnabled(composerReady);
        theme.setEnabled(sendButton, composerReady);
        theme.setEnabled(
            importButton,
            composerReady
                && pendingImports.size() < WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE
        );
        theme.setEnabled(
            connectorButton,
            composerReady && !session.getActiveThreadId().isEmpty()
        );
        clearConnectorsButton.setVisibility(
            pendingConnectors.isEmpty() ? View.GONE : View.VISIBLE
        );
        theme.setEnabled(
            clearConnectorsButton,
            composerReady && !pendingConnectors.isEmpty()
        );
        clearImportsButton.setVisibility(
            pendingImports.isEmpty() ? View.GONE : View.VISIBLE
        );
        theme.setEnabled(clearImportsButton, composerReady && !pendingImports.isEmpty());
        theme.setEnabled(
            reviewButton,
            composerReady
                && !session.isTurnActive()
                && !session.getActiveThreadId().isEmpty()
                && pendingImports.isEmpty()
        );
        reviewButton.setVisibility(steering ? View.GONE : View.VISIBLE);
        renderConnectorSelection();
        renderImportSelection();
        stopButton.setVisibility(steering ? View.VISIBLE : View.GONE);
        theme.setEnabled(
            stopButton,
            session.isReady()
                && session.isTurnActive()
                && !session.isTurnInterruptPending()
                && !session.getActiveTurnId().isEmpty()
        );
        threadAdapter.setData(
            session.getThreads(),
            session.getActiveThreadId(),
            threadNavigationReady
        );
        bindSelectors(session, canChat && !session.isTurnActive() && !interactionOpen);
        renderTranscript(session.getActiveThreadId(), session.getTranscriptItems());
        if (conversationVisible) {
            screenTitle.setText(
                session.getActiveThreadTitle().isEmpty()
                    ? getString(R.string.chat_active)
                    : UiText.threadTitle(this, session.getActiveThreadTitle())
            );
        }
        if (interactiveRequestDialog != null) {
            interactiveRequestDialog.render(session);
        }
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
        statusBanner.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(
            !session.getErrorMessage().isEmpty() || session.isDangerousExecutionMode()
                ? theme.danger
                : theme.primary
        );
        statusSettingsButton.setVisibility(settingsAction ? View.VISIBLE : View.GONE);
    }

    private void bindSelectors(CodexSessionSnapshot session, boolean enabled) {
        bindingSelectors = true;
        try {
            List<String> modelLabels = new ArrayList<String>();
            int modelIndex = 0;
            for (int index = 0; index < session.getModels().size(); index++) {
                CodexModelOption model = session.getModels().get(index);
                modelLabels.add(model.getDisplayName());
                if (model.getId().equals(session.getSelectedModelId())) {
                    modelIndex = index;
                }
            }
            ArrayAdapter<String> modelAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                modelLabels
            );
            modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            modelSpinner.setAdapter(modelAdapter);
            if (!modelLabels.isEmpty()) {
                modelSpinner.setSelection(modelIndex, false);
            }

            CodexModelOption selected = selectedModel(session);
            List<String> effortLabels = new ArrayList<String>();
            int effortIndex = 0;
            if (selected != null) {
                for (int index = 0; index < selected.getReasoningOptions().size(); index++) {
                    CodexReasoningOption option = selected.getReasoningOptions().get(index);
                    effortLabels.add(reasoningLabel(option.getEffort()));
                    if (option.getEffort().equals(session.getSelectedReasoningEffort())) {
                        effortIndex = index;
                    }
                }
            }
            ArrayAdapter<String> effortAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                effortLabels
            );
            effortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            effortSpinner.setAdapter(effortAdapter);
            if (!effortLabels.isEmpty()) {
                effortSpinner.setSelection(effortIndex, false);
            }
            modelSpinner.setEnabled(enabled && !modelLabels.isEmpty());
            effortSpinner.setEnabled(enabled && !effortLabels.isEmpty());
            modelSpinner.setAlpha(modelSpinner.isEnabled() ? 1.0f : 0.5f);
            effortSpinner.setAlpha(effortSpinner.isEnabled() ? 1.0f : 0.5f);
            modelDescription.setText(selectorDescription(
                selected,
                session.getSelectedReasoningEffort()
            ));
        } finally {
            bindingSelectors = false;
        }
    }

    private void renderTranscript(String threadId, List<CodexTranscriptItem> items) {
        boolean rebuild = !threadId.equals(renderedThreadId)
            || items.size() != renderedTranscriptKeys.size();
        if (!rebuild) {
            for (int index = 0; index < items.size(); index++) {
                if (!transcriptKey(items.get(index)).equals(renderedTranscriptKeys.get(index))) {
                    rebuild = true;
                    break;
                }
            }
        }
        if (rebuild) {
            renderedThreadId = threadId;
            renderedTranscriptKeys.clear();
            renderedTranscriptRows.clear();
            messagesContainer.removeAllViews();
            if (items.isEmpty()) {
                TextView empty = theme.text(
                    threadId.isEmpty()
                        ? getString(R.string.chat_select)
                        : getString(R.string.chat_no_messages),
                    14,
                    theme.secondary
                );
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(theme.dp(12), theme.dp(40), theme.dp(12), theme.dp(40));
                messagesContainer.addView(empty);
                return;
            }
            for (int index = 0; index < items.size(); index++) {
                CodexTranscriptItem item = items.get(index);
                TranscriptRow row = createTranscriptRow(item);
                renderedTranscriptKeys.add(transcriptKey(item));
                renderedTranscriptRows.add(row);
                theme.addWithTopMargin(messagesContainer, row.root, index == 0 ? 0 : 10);
            }
            scrollMessagesToBottom();
            return;
        }
        boolean changed = false;
        for (int index = 0; index < items.size(); index++) {
            CodexTranscriptItem item = items.get(index);
            String value = transcriptText(item);
            TranscriptRow row = renderedTranscriptRows.get(index);
            if (!value.contentEquals(row.text.getText())) {
                row.text.setText(value);
                styleTranscriptView(row.text, item);
                changed = true;
            }
            bindImageAction(row, item);
        }
        if (changed) {
            scrollMessagesToBottom();
        }
    }

    private TranscriptRow createTranscriptRow(CodexTranscriptItem item) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView text = theme.text(
            transcriptText(item),
            item.isMessage() ? 14 : 13,
            theme.primary
        );
        text.setTextIsSelectable(true);
        text.setLineSpacing(0.0f, 1.2f);
        text.setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12));
        root.addView(text, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView imageStatus = theme.text("", 12, theme.secondary);
        imageStatus.setLineSpacing(0.0f, 1.15f);
        imageStatus.setVisibility(View.GONE);
        theme.addWithTopMargin(root, imageStatus, 6);

        ImageButton imageAction = theme.iconButton(
            R.drawable.ic_chat_download,
            getString(R.string.image_export)
        );
        imageAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams imageActionParams = iconMarginParams(0);
        imageActionParams.topMargin = theme.dp(6);
        root.addView(imageAction, imageActionParams);

        TranscriptRow row = new TranscriptRow(root, text, imageStatus, imageAction);
        styleTranscriptView(text, item);
        bindImageAction(row, item);
        return row;
    }

    private void styleTranscriptView(TextView view, CodexTranscriptItem item) {
        int fill;
        if (!item.isMessage()) {
            fill = cardFill(item);
        } else if (item.getMessage().getRole() == ChatMessage.Role.USER) {
            fill = theme.dark ? 0xFF123B3A : 0xFFE7FAF6;
        } else if (item.getMessage().getRole() == ChatMessage.Role.SYSTEM) {
            fill = theme.dark ? 0xFF3A2420 : 0xFFFFF4E5;
        } else {
            fill = theme.surfaceRaised;
        }
        view.setBackground(theme.background(fill, theme.border, 16));
        view.setTextColor(theme.primary);
    }

    private void bindImageAction(TranscriptRow row, CodexTranscriptItem item) {
        String imagePath = item.getReportedImagePath();
        if (item.isStreaming() || imagePath.isEmpty()) {
            row.imagePath = "";
            row.imageState = ImageValidationState.NONE;
            row.imageInfo = null;
            row.imageFailure = "";
            row.imageStatus.setVisibility(View.GONE);
            row.imageAction.setVisibility(View.GONE);
            row.imageAction.setOnClickListener(null);
            return;
        }
        if (!imagePath.equals(row.imagePath)) {
            row.imagePath = imagePath;
            beginImageInspection(row, imagePath);
            return;
        }
        applyImageAction(row);
    }

    private void beginImageInspection(final TranscriptRow row, final String imagePath) {
        row.imageState = ImageValidationState.CHECKING;
        row.imageInfo = null;
        row.imageFailure = "";
        row.checkingMessage = getString(R.string.image_path_checking);
        applyImageAction(row);
        final android.content.Context applicationContext = getApplicationContext();
        if (!submitImageOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceImageExporter.ImageExport image =
                        WorkspaceImageExporter.inspect(applicationContext, imagePath);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            completeImageInspection(row, imagePath, image, "");
                        }
                    });
                } catch (Throwable error) {
                    final String failure = exportFailureMessage(error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            completeImageInspection(row, imagePath, null, failure);
                        }
                    });
                }
            }
        })) {
            completeImageInspection(
                row,
                imagePath,
                null,
                getString(R.string.image_inspection_start_failed)
            );
        }
    }

    private void completeImageInspection(
        TranscriptRow row,
        String imagePath,
        WorkspaceImageExporter.ImageExport image,
        String failure
    ) {
        if (!isCurrentImageRow(row, imagePath)) {
            return;
        }
        row.imageInfo = image;
        row.imageFailure = failure == null ? "" : failure;
        row.imageState = image == null
            ? ImageValidationState.INVALID
            : ImageValidationState.VALID;
        applyImageAction(row);
    }

    private void applyImageAction(final TranscriptRow row) {
        if (row.imageState == ImageValidationState.NONE) {
            row.imageStatus.setVisibility(View.GONE);
            row.imageAction.setVisibility(View.GONE);
            row.imageAction.setOnClickListener(null);
            return;
        }
        row.imageStatus.setVisibility(View.VISIBLE);
        row.imageAction.setVisibility(View.VISIBLE);
        if (row.imageState == ImageValidationState.CHECKING) {
            row.imageStatus.setText(row.checkingMessage);
            row.imageStatus.setTextColor(theme.secondary);
            theme.setIcon(
                row.imageAction,
                R.drawable.ic_chat_hourglass,
                getString(R.string.image_inspection_running)
            );
            row.imageAction.setOnClickListener(null);
            theme.setEnabled(row.imageAction, false);
            return;
        }
        if (row.imageState == ImageValidationState.INVALID) {
            row.imageStatus.setText(row.imageFailure);
            row.imageStatus.setTextColor(theme.danger);
            theme.setIcon(
                row.imageAction,
                R.drawable.ic_chat_refresh,
                getString(R.string.image_path_recheck)
            );
            row.imageAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View ignored) {
                    beginImageInspection(row, row.imagePath);
                }
            });
            theme.setEnabled(row.imageAction, true);
            return;
        }
        WorkspaceImageExporter.ImageExport image = row.imageInfo;
        row.imageStatus.setText(
            getString(
                R.string.image_workspace_confirmed,
                image.getDisplayName(),
                readableByteCount(image.getByteCount())
            )
        );
        row.imageStatus.setTextColor(theme.secondary);
        theme.setIcon(
            row.imageAction,
            R.drawable.ic_chat_download,
            getString(R.string.image_export)
        );
        row.imageAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                verifyAndOpenImageExport(row, row.imagePath);
            }
        });
        theme.setEnabled(row.imageAction, true);
    }

    private void verifyAndOpenImageExport(
        final TranscriptRow row,
        final String imagePath
    ) {
        row.imageState = ImageValidationState.CHECKING;
        row.checkingMessage = getString(R.string.image_pre_export_check);
        applyImageAction(row);
        final android.content.Context applicationContext = getApplicationContext();
        if (!submitImageOperation(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceImageExporter.ImageExport image =
                        WorkspaceImageExporter.inspect(applicationContext, imagePath);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isCurrentImageRow(row, imagePath)) {
                                return;
                            }
                            row.imageState = ImageValidationState.VALID;
                            row.imageInfo = image;
                            row.imageFailure = "";
                            applyImageAction(row);
                            openImageExportDocument(row, imagePath, image);
                        }
                    });
                } catch (Throwable error) {
                    final String failure = exportFailureMessage(error);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            completeImageInspection(row, imagePath, null, failure);
                        }
                    });
                }
            }
        })) {
            completeImageInspection(
                row,
                imagePath,
                null,
                getString(R.string.image_recheck_start_failed)
            );
        }
    }

    private void openImageExportDocument(
        TranscriptRow row,
        String sourcePath,
        WorkspaceImageExporter.ImageExport image
    ) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(image.getMimeType());
            intent.putExtra(Intent.EXTRA_TITLE, image.getDisplayName());
            pendingImageExportPath = sourcePath;
            startActivityForResult(intent, IMAGE_EXPORT_REQUEST_CODE);
        } catch (Throwable error) {
            pendingImageExportPath = "";
            row.imageState = ImageValidationState.VALID;
            applyImageAction(row);
            Toast.makeText(
                this,
                R.string.document_picker_open_failed,
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean isCurrentImageRow(TranscriptRow row, String imagePath) {
        return !destroyed
            && imagePath.equals(row.imagePath)
            && row.root.getParent() != null;
    }

    private boolean submitImageOperation(Runnable operation) {
        if (destroyed || imageOperations.isShutdown()) {
            return false;
        }
        try {
            imageOperations.execute(operation);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void showExportFailure(final String imagePath, Throwable error) {
        final String message = exportFailureMessage(error);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                for (TranscriptRow row : renderedTranscriptRows) {
                    if (imagePath.equals(row.imagePath)) {
                        row.imageInfo = null;
                        row.imageFailure = message;
                        row.imageState = ImageValidationState.INVALID;
                        applyImageAction(row);
                    }
                }
                if (!isFinishing()) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private String readableByteCount(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + " MiB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KiB";
        }
        return getString(R.string.byte_count_bytes, Long.valueOf(bytes));
    }

    private void showExportToast(final String message, final int duration) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && !isFinishing()) {
                    Toast.makeText(MainActivity.this, message, duration).show();
                }
            }
        });
    }

    private String exportFailureMessage(Throwable error) {
        String reason = error == null || error.getMessage() == null
            ? getString(R.string.common_unknown_error)
            : UiText.errorReason(
                this,
                CrashReportFormatter.redactVisibleText(error.getMessage(), 180)
            );
        return getString(R.string.image_export_path_invalid, reason);
    }

    private void scrollMessagesToBottom() {
        messageScroll.post(new Runnable() {
            @Override
            public void run() {
                messageScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void showThreadActions(final CodexThreadSummary thread) {
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

    private void showThreadPage() {
        conversationVisible = false;
        pendingThreadId = "";
        pendingNewThread = false;
        threadPage.setVisibility(View.VISIBLE);
        conversationPage.setVisibility(View.GONE);
        backToThreadsButton.setVisibility(View.GONE);
        screenTitle.setText(R.string.chat_title);
    }

    private void showConversationPage(CodexSessionSnapshot session) {
        if (session.getActiveThreadId().isEmpty()) {
            return;
        }
        conversationVisible = true;
        threadPage.setVisibility(View.GONE);
        conversationPage.setVisibility(View.VISIBLE);
        backToThreadsButton.setVisibility(View.VISIBLE);
        screenTitle.setText(
            session.getActiveThreadTitle().isEmpty()
                ? getString(R.string.chat_active)
                : UiText.threadTitle(this, session.getActiveThreadTitle())
        );
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openTerminal() {
        startActivity(new Intent(this, TerminalActivity.class));
    }

    private void openFiles() {
        startActivity(new Intent(this, WorkspaceBrowserActivity.class));
    }

    private void showEmergencyStatus(Throwable error) {
        if (statusBanner == null || statusText == null) {
            return;
        }
        statusBanner.setVisibility(View.VISIBLE);
        statusText.setText(
            getString(R.string.chat_update_failed, error.getClass().getSimpleName())
        );
        statusText.setTextColor(theme == null ? Color.RED : theme.danger);
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

    private static CodexModelOption selectedModel(CodexSessionSnapshot session) {
        for (CodexModelOption model : session.getModels()) {
            if (model.getId().equals(session.getSelectedModelId())) {
                return model;
            }
        }
        return null;
    }

    private String selectorDescription(CodexModelOption model, String effort) {
        if (model == null) {
            return getString(R.string.models_unavailable);
        }
        String effortDescription = "";
        for (CodexReasoningOption option : model.getReasoningOptions()) {
            if (option.getEffort().equals(effort)) {
                effortDescription = option.getDescription();
                break;
            }
        }
        StringBuilder value = new StringBuilder(model.getDescription());
        if (!effortDescription.isEmpty()) {
            if (value.length() != 0) {
                value.append(" · ");
            }
            value.append(effortDescription);
        }
        return value.toString();
    }

    private String reasoningLabel(String effort) {
        if ("low".equals(effort)) {
            return getString(R.string.reasoning_low);
        }
        if ("medium".equals(effort)) {
            return getString(R.string.reasoning_medium);
        }
        if ("high".equals(effort)) {
            return getString(R.string.reasoning_high);
        }
        if ("xhigh".equals(effort)) {
            return getString(R.string.reasoning_xhigh);
        }
        if ("max".equals(effort)) {
            return getString(R.string.reasoning_max);
        }
        if ("ultra".equals(effort)) {
            return getString(R.string.reasoning_ultra);
        }
        return effort;
    }

    private String messageText(ChatMessage message) {
        String role = message.getRole() == ChatMessage.Role.USER
            ? getString(R.string.transcript_role_you)
            : message.getRole() == ChatMessage.Role.ASSISTANT
                ? getString(R.string.transcript_role_codex)
                : getString(R.string.transcript_role_system);
        String body = message.getRole() == ChatMessage.Role.SYSTEM
            ? UiText.coreStatus(this, message.getText())
            : message.getText();
        return role
            + (message.isStreaming()
                ? " · " + getString(R.string.transcript_stream)
                : "")
            + "\n"
            + body;
    }

    private static String transcriptKey(CodexTranscriptItem item) {
        return item.getKind().name() + ":" + item.getId();
    }

    private String transcriptText(CodexTranscriptItem item) {
        if (item.isMessage()) {
            return messageText(item.getMessage());
        }
        StringBuilder text = new StringBuilder(
            UiText.cardTitle(this, item).toUpperCase(java.util.Locale.ROOT)
        );
        String status = statusLabel(item.getStatus());
        if (!status.isEmpty()) {
            text.append(" · ").append(status);
        } else if (item.isStreaming()) {
            text.append(" · ").append(getString(R.string.transcript_stream));
        }
        String summary = UiText.cardSummary(this, item);
        String detail = UiText.cardDetail(this, item.getDetail());
        if (!summary.isEmpty()) {
            text.append("\n").append(summary);
        }
        if (!detail.isEmpty()) {
            if (item.getKind() == CodexTranscriptItem.Kind.REASONING
                && !summary.isEmpty()) {
                text.append("\n\n").append(getString(R.string.transcript_details)).append('\n');
            } else {
                text.append("\n");
            }
            text.append(detail);
        }
        if (summary.isEmpty() && detail.isEmpty() && item.isStreaming()) {
            text.append("\n").append(getString(R.string.transcript_receiving));
        }
        return text.toString();
    }

    private String statusLabel(String status) {
        if ("inProgress".equals(status)) {
            return getString(R.string.status_in_progress);
        }
        if ("completed".equals(status)) {
            return getString(R.string.status_completed);
        }
        if ("failed".equals(status)) {
            return getString(R.string.status_failed);
        }
        if ("declined".equals(status)) {
            return getString(R.string.status_declined);
        }
        if ("interrupted".equals(status)) {
            return getString(R.string.status_interrupted);
        }
        return status == null ? "" : status.toUpperCase(java.util.Locale.ROOT);
    }

    private int cardFill(CodexTranscriptItem item) {
        if ("failed".equals(item.getStatus())
            || "declined".equals(item.getStatus())
            || "interrupted".equals(item.getStatus())) {
            return theme.dark ? 0xFF3A2420 : 0xFFFFF1F2;
        }
        if (item.getKind() == CodexTranscriptItem.Kind.REASONING) {
            return theme.dark ? 0xFF25203D : 0xFFF5F3FF;
        }
        if (item.getKind() == CodexTranscriptItem.Kind.PLAN) {
            return theme.dark ? 0xFF172E46 : 0xFFEFF6FF;
        }
        if (item.isStreaming()) {
            return theme.dark ? 0xFF1C3040 : 0xFFECFEFF;
        }
        return theme.dark ? 0xFF162D29 : 0xFFF0FDFA;
    }

    private final class ThreadAdapter extends BaseAdapter {
        private final List<CodexThreadSummary> values = new ArrayList<CodexThreadSummary>();
        private String activeId = "";
        private boolean enabled;
        private String fingerprint = "";

        void setData(List<CodexThreadSummary> threads, String activeThreadId, boolean rowsEnabled) {
            StringBuilder nextFingerprint = new StringBuilder();
            int count = Math.min(MAX_VISIBLE_THREADS, threads.size());
            for (int index = 0; index < count; index++) {
                CodexThreadSummary value = threads.get(index);
                nextFingerprint.append(value.getId()).append('\0')
                    .append(value.getTitle()).append('\0')
                    .append(value.getUpdatedAtSeconds()).append('\0')
                    .append(value.isArchived()).append('\1');
            }
            nextFingerprint.append('|').append(activeThreadId).append('|').append(rowsEnabled);
            if (nextFingerprint.toString().equals(fingerprint)) {
                return;
            }
            fingerprint = nextFingerprint.toString();
            values.clear();
            for (int index = 0; index < count; index++) {
                values.add(threads.get(index));
            }
            activeId = activeThreadId;
            enabled = rowsEnabled;
            notifyDataSetChanged();
        }

        CodexThreadSummary item(int position) {
            return values.get(position);
        }

        @Override
        public int getCount() {
            return values.size();
        }

        @Override
        public Object getItem(int position) {
            return item(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return enabled;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ThreadRow row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof ThreadRow) {
                row = (ThreadRow) convertView.getTag();
            } else {
                LinearLayout container = new LinearLayout(MainActivity.this);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setGravity(Gravity.CENTER_VERTICAL);
                container.setPadding(theme.dp(18), theme.dp(14), theme.dp(18), theme.dp(14));
                LinearLayout textColumn = new LinearLayout(MainActivity.this);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                TextView title = theme.text("", 16, theme.primary);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                textColumn.addView(title);
                TextView metadata = theme.text("", 12, theme.secondary);
                metadata.setSingleLine(true);
                metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
                theme.addWithTopMargin(textColumn, metadata, 4);
                container.addView(textColumn, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1.0f
                ));
                ImageButton action = theme.iconButton(
                    R.drawable.ic_chat_more,
                    getString(R.string.chat_actions)
                );
                action.setFocusable(false);
                LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                actionParams.leftMargin = theme.dp(10);
                container.addView(action, actionParams);
                row = new ThreadRow(container, title, metadata, action);
                container.setTag(row);
            }
            final CodexThreadSummary value = item(position);
            boolean active = !value.isArchived() && value.getId().equals(activeId);
            row.title.setText(UiText.threadTitle(MainActivity.this, value.getTitle()));
            String updated = value.getUpdatedAtSeconds() <= 0
                ? getString(R.string.chat_not_updated)
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(value.getUpdatedAtSeconds() * 1000L));
            if (value.isArchived()) {
                row.metadata.setText(getString(R.string.chat_archived_metadata, updated));
            } else {
                row.metadata.setText(
                    active ? getString(R.string.chat_active_metadata, updated) : updated
                );
            }
            row.root.setBackground(theme.background(
                active ? (theme.dark ? 0xFF123B3A : 0xFFE7FAF6) : theme.surface,
                Color.TRANSPARENT,
                0
            ));
            row.root.setAlpha(enabled ? 1.0f : 0.55f);
            row.action.setEnabled(enabled);
            theme.setIcon(
                row.action,
                R.drawable.ic_chat_more,
                getString(
                    R.string.chat_actions_for,
                    UiText.threadTitle(MainActivity.this, value.getTitle())
                )
            );
            row.action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showThreadActions(value);
                }
            });
            return row.root;
        }
    }

    private enum ImageValidationState {
        NONE,
        CHECKING,
        VALID,
        INVALID
    }

    private static final class TranscriptRow {
        private final LinearLayout root;
        private final TextView text;
        private final TextView imageStatus;
        private final ImageButton imageAction;
        private String imagePath = "";
        private ImageValidationState imageState = ImageValidationState.NONE;
        private WorkspaceImageExporter.ImageExport imageInfo;
        private String imageFailure = "";
        private String checkingMessage = "";

        private TranscriptRow(
            LinearLayout root,
            TextView text,
            TextView imageStatus,
            ImageButton imageAction
        ) {
            this.root = root;
            this.text = text;
            this.imageStatus = imageStatus;
            this.imageAction = imageAction;
        }
    }

    private static final class ThreadRow {
        private final LinearLayout root;
        private final TextView title;
        private final TextView metadata;
        private final ImageButton action;

        private ThreadRow(
            LinearLayout root,
            TextView title,
            TextView metadata,
            ImageButton action
        ) {
            this.root = root;
            this.title = title;
            this.metadata = metadata;
            this.action = action;
        }
    }
}
