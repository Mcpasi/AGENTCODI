package de.agentcodi.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexReasoningOption;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexThreadSummary;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.UiStartupState;
import de.agentcodi.runtime.AgentRuntimeService;
import de.agentcodi.runtime.CrashDiagnostics;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class MainActivity extends Activity {
    private static final long ACTIVE_REFRESH_INTERVAL_MS = 250L;
    private static final long IDLE_REFRESH_INTERVAL_MS = 900L;
    private static final int MAX_VISIBLE_THREADS = 80;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UiStartupState startupState = new UiStartupState();
    private final List<String> renderedMessageIds = new ArrayList<String>();
    private final List<TextView> renderedMessageViews = new ArrayList<TextView>();
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
    private Button statusSettingsButton;
    private Button backToThreadsButton;
    private TextView screenTitle;
    private LinearLayout threadPage;
    private LinearLayout conversationPage;
    private Button newThreadButton;
    private Button refreshThreadsButton;
    private ListView threadList;
    private ThreadAdapter threadAdapter;
    private Spinner modelSpinner;
    private Spinner effortSpinner;
    private TextView modelDescription;
    private ScrollView messageScroll;
    private LinearLayout messagesContainer;
    private EditText composerInput;
    private Button sendButton;
    private Button stopButton;
    private boolean bindingSelectors;
    private boolean conversationVisible;
    private String pendingThreadId = "";
    private boolean pendingNewThread;
    private String newThreadBaseline = "";
    private String renderedThreadId = "";
    private long lastSessionRevision = Long.MIN_VALUE;
    private long lastRuntimeGeneration = Long.MIN_VALUE;
    private RuntimePhase lastRuntimePhase;
    private CrashDiagnostics crashDiagnostics;
    private InteractiveRequestDialog interactiveRequestDialog;

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

        backToThreadsButton = theme.compactButton("‹ Chats");
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

        screenTitle = theme.text("Chats", 24, theme.primary);
        screenTitle.setTypeface(Typeface.DEFAULT_BOLD);
        screenTitle.setSingleLine(true);
        screenTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(12);
        titleParams.rightMargin = theme.dp(10);
        topBar.addView(screenTitle, titleParams);

        Button settingsButton = theme.compactButton("Einstellungen");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSettings();
            }
        });
        topBar.addView(settingsButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(topBar);

        statusBanner = new LinearLayout(this);
        statusBanner.setOrientation(LinearLayout.HORIZONTAL);
        statusBanner.setGravity(Gravity.CENTER_VERTICAL);
        statusBanner.setPadding(theme.dp(14), theme.dp(12), theme.dp(12), theme.dp(12));
        statusBanner.setBackground(theme.background(theme.surfaceRaised, theme.border, 14));
        statusText = theme.text("Runtime wird geprüft …", 13, theme.primary);
        statusText.setLineSpacing(0.0f, 1.15f);
        statusBanner.addView(statusText, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        statusSettingsButton = theme.compactButton("Öffnen");
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
        TextView intro = theme.text("Deine Codex-Unterhaltungen", 14, theme.secondary);
        actions.addView(intro, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        refreshThreadsButton = theme.compactButton("Aktualisieren");
        refreshThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshThreads();
            }
        });
        actions.addView(refreshThreadsButton);
        page.addView(actions);

        newThreadButton = theme.primaryButton("+ Neuer Chat");
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
        theme.addWithTopMargin(page, newThreadButton, 12);

        TextView emptyView = theme.text(
            "Noch keine Chats. Starte oben eine neue Unterhaltung.",
            15,
            theme.secondary
        );
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(theme.dp(24), theme.dp(48), theme.dp(24), theme.dp(48));
        page.addView(emptyView, new LinearLayout.LayoutParams(
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
        threadList.setEmptyView(emptyView);
        threadList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                CodexThreadSummary thread = threadAdapter.item(position);
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
        TextView modelLabel = theme.sectionLabel("MODELL");
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
        effortColumn.addView(theme.sectionLabel("DENKSTUFE"));
        effortSpinner = new Spinner(this);
        effortColumn.addView(effortSpinner);
        selectorRow.addView(effortColumn, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.85f
        ));
        selectors.addView(selectorRow);
        modelDescription = theme.text("Modelle werden vom App-Server geladen.", 12, theme.secondary);
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
        composerInput = new EditText(this);
        composerInput.setHint("Aufgabe für Codex …");
        composerInput.setHintTextColor(theme.secondary);
        composerInput.setTextColor(theme.primary);
        composerInput.setMinLines(2);
        composerInput.setMaxLines(7);
        composerInput.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        composer.addView(composerInput);

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        stopButton = theme.secondaryButton("Stopp");
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.interruptTurn();
            }
        });
        sendRow.addView(stopButton, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.35f
        ));
        sendButton = theme.primaryButton("Senden");
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String prompt = composerInput.getText().toString();
                if (prompt.trim().isEmpty()) {
                    return;
                }
                composerInput.getText().clear();
                AgentRuntimeService.sendMessage(prompt);
            }
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.65f
        );
        sendParams.leftMargin = theme.dp(8);
        sendRow.addView(sendButton, sendParams);
        theme.addWithTopMargin(composer, sendRow, 8);
        page.addView(composer);
        return page;
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
        renderStatus(runtime, session);
        boolean actionReady = session.isReady() && !session.isOperationActive();
        boolean canChat = actionReady
            && (!session.requiresOpenaiAuth() || session.isSignedIn());
        theme.setEnabled(refreshThreadsButton, canChat);
        boolean interactionOpen = session.hasInteractiveRequest();
        theme.setEnabled(
            newThreadButton,
            canChat && !session.isTurnActive() && !interactionOpen
        );
        composerInput.setEnabled(canChat && !session.isTurnActive() && !interactionOpen);
        theme.setEnabled(sendButton, canChat && !session.isTurnActive() && !interactionOpen);
        theme.setEnabled(stopButton, session.isReady() && session.isTurnActive());
        threadAdapter.setData(
            session.getThreads(),
            session.getActiveThreadId(),
            canChat && !session.isOperationActive() && !session.isTurnActive() && !interactionOpen
        );
        bindSelectors(session, canChat && !session.isTurnActive() && !interactionOpen);
        renderMessages(session.getActiveThreadId(), session.getMessages());
        if (conversationVisible) {
            screenTitle.setText(
                session.getActiveThreadTitle().isEmpty()
                    ? "Aktiver Chat"
                    : session.getActiveThreadTitle()
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
            message = runtime.getMessage();
            settingsAction = true;
        } else if (!session.isReady()) {
            message = session.getConnectionMessage();
            settingsAction = true;
        } else if (session.requiresOpenaiAuth() && !session.isSignedIn()) {
            message = "Bitte in den Einstellungen mit ChatGPT oder einem API-Schlüssel anmelden.";
            settingsAction = true;
        } else if (!session.getErrorMessage().isEmpty()) {
            message = "Fehler: " + session.getErrorMessage();
            settingsAction = true;
        } else if (session.hasInteractiveRequest()) {
            message = "Codex wartet auf deine Freigabe oder Eingabe.";
        } else if (session.isOperationActive()) {
            message = session.getOperationMessage();
        } else if (session.isTurnActive()) {
            message = "Codex arbeitet und streamt die Antwort …";
        }
        statusBanner.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        statusText.setText(message);
        statusText.setTextColor(
            !session.getErrorMessage().isEmpty() ? theme.danger : theme.primary
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

    private void renderMessages(String threadId, List<ChatMessage> messages) {
        boolean rebuild = !threadId.equals(renderedThreadId)
            || messages.size() != renderedMessageIds.size();
        if (!rebuild) {
            for (int index = 0; index < messages.size(); index++) {
                if (!messages.get(index).getId().equals(renderedMessageIds.get(index))) {
                    rebuild = true;
                    break;
                }
            }
        }
        if (rebuild) {
            renderedThreadId = threadId;
            renderedMessageIds.clear();
            renderedMessageViews.clear();
            messagesContainer.removeAllViews();
            if (messages.isEmpty()) {
                TextView empty = theme.text(
                    threadId.isEmpty()
                        ? "Wähle einen Chat aus."
                        : "Noch keine Nachrichten. Beschreibe unten deine Aufgabe.",
                    14,
                    theme.secondary
                );
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(theme.dp(12), theme.dp(40), theme.dp(12), theme.dp(40));
                messagesContainer.addView(empty);
                return;
            }
            for (int index = 0; index < messages.size(); index++) {
                ChatMessage message = messages.get(index);
                TextView view = createMessageView(message);
                renderedMessageIds.add(message.getId());
                renderedMessageViews.add(view);
                theme.addWithTopMargin(messagesContainer, view, index == 0 ? 0 : 10);
            }
            scrollMessagesToBottom();
            return;
        }
        boolean changed = false;
        for (int index = 0; index < messages.size(); index++) {
            String value = messageText(messages.get(index));
            if (!value.contentEquals(renderedMessageViews.get(index).getText())) {
                renderedMessageViews.get(index).setText(value);
                changed = true;
            }
        }
        if (changed) {
            scrollMessagesToBottom();
        }
    }

    private TextView createMessageView(ChatMessage message) {
        TextView view = theme.text(messageText(message), 14, theme.primary);
        view.setTextIsSelectable(true);
        view.setLineSpacing(0.0f, 1.2f);
        view.setPadding(theme.dp(14), theme.dp(12), theme.dp(14), theme.dp(12));
        int fill;
        if (message.getRole() == ChatMessage.Role.USER) {
            fill = theme.dark ? 0xFF123B3A : 0xFFE7FAF6;
        } else if (message.getRole() == ChatMessage.Role.SYSTEM) {
            fill = theme.dark ? 0xFF3A2420 : 0xFFFFF4E5;
        } else {
            fill = theme.surfaceRaised;
        }
        view.setBackground(theme.background(fill, theme.border, 16));
        return view;
    }

    private void scrollMessagesToBottom() {
        messageScroll.post(new Runnable() {
            @Override
            public void run() {
                messageScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void showThreadPage() {
        conversationVisible = false;
        pendingThreadId = "";
        pendingNewThread = false;
        threadPage.setVisibility(View.VISIBLE);
        conversationPage.setVisibility(View.GONE);
        backToThreadsButton.setVisibility(View.GONE);
        screenTitle.setText("Chats");
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
                ? "Aktiver Chat"
                : session.getActiveThreadTitle()
        );
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void showEmergencyStatus(Throwable error) {
        if (statusBanner == null || statusText == null) {
            return;
        }
        statusBanner.setVisibility(View.VISIBLE);
        statusText.setText(
            "Die Chat-Oberfläche konnte nicht aktualisiert werden: "
                + error.getClass().getSimpleName()
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
            "AGENTCODI konnte die Chat-Oberfläche nicht initialisieren.\n\n"
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

    private static String selectorDescription(CodexModelOption model, String effort) {
        if (model == null) {
            return "Keine Modelle verfügbar.";
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

    private static String reasoningLabel(String effort) {
        if ("low".equals(effort)) {
            return "Niedrig";
        }
        if ("medium".equals(effort)) {
            return "Mittel";
        }
        if ("high".equals(effort)) {
            return "Hoch";
        }
        if ("xhigh".equals(effort)) {
            return "Sehr hoch";
        }
        if ("max".equals(effort)) {
            return "Maximum";
        }
        if ("ultra".equals(effort)) {
            return "Ultra";
        }
        return effort;
    }

    private static String messageText(ChatMessage message) {
        String role = message.getRole() == ChatMessage.Role.USER
            ? "DU"
            : message.getRole() == ChatMessage.Role.ASSISTANT ? "CODEX" : "SYSTEM";
        return role + (message.isStreaming() ? " · STREAM" : "") + "\n" + message.getText();
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
                    .append(value.getUpdatedAtSeconds()).append('\1');
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
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(theme.dp(18), theme.dp(14), theme.dp(18), theme.dp(14));
                TextView title = theme.text("", 16, theme.primary);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                container.addView(title);
                TextView metadata = theme.text("", 12, theme.secondary);
                metadata.setSingleLine(true);
                metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
                theme.addWithTopMargin(container, metadata, 4);
                row = new ThreadRow(container, title, metadata);
                container.setTag(row);
            }
            CodexThreadSummary value = item(position);
            boolean active = value.getId().equals(activeId);
            row.title.setText(value.getTitle());
            String updated = value.getUpdatedAtSeconds() <= 0
                ? "Noch nicht aktualisiert"
                : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(value.getUpdatedAtSeconds() * 1000L));
            row.metadata.setText(active ? "Aktiver Chat · " + updated : updated);
            row.root.setBackground(theme.background(
                active ? (theme.dark ? 0xFF123B3A : 0xFFE7FAF6) : theme.surface,
                Color.TRANSPARENT,
                0
            ));
            row.root.setAlpha(enabled ? 1.0f : 0.55f);
            return row.root;
        }
    }

    private static final class ThreadRow {
        private final LinearLayout root;
        private final TextView title;
        private final TextView metadata;

        private ThreadRow(LinearLayout root, TextView title, TextView metadata) {
            this.root = root;
            this.title = title;
            this.metadata = metadata;
        }
    }
}
