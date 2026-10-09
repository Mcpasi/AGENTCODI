package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import de.agentcodi.core.CodexThreadSummary;
import de.agentcodi.runtime.AgentRuntimeService;


/** Builds the chat screen and owns its widgets and details-dialog lifecycle. */
final class ChatScreenView {
    interface Actions extends ThreadListAdapter.Actions {
        void showThreadPage();
        void openFiles();
        void openTerminal();
        void openSettings();
        void startNewThread();
        void openThread(CodexThreadSummary thread);
        void showModelDetails();
        void detachPendingConnectors();
        void detachPendingImports();
        void openDocumentImportPicker();
        void openConnectorPicker();
        void sendComposerInput();
    }

    private final Activity activity;
    private final UiTheme theme;
    private final Actions callbacks;
    private AlertDialog chatDetailsDialog;
    LinearLayout statusBanner;
    View statusIndicator;
    TextView statusText;
    ImageButton statusSettingsButton;
    ImageButton backToThreadsButton;
    TextView screenTitle;
    LinearLayout threadPage;
    LinearLayout conversationPage;
    ImageButton newThreadButton;
    ImageButton refreshThreadsButton;
    ImageButton activeThreadsButton;
    ImageButton archivedThreadsButton;
    TextView threadSectionLabel;
    TextView threadEmptyView;
    ListView threadList;
    Button loadMoreThreadsButton;
    ThreadListAdapter threadAdapter;
    Spinner modelSpinner;
    Spinner effortSpinner;
    ImageButton modelDetailsButton;
    ScrollView messageScroll;
    LinearLayout messagesContainer;
    EditText composerInput;
    LinearLayout connectorStatusRow;
    TextView connectorStatus;
    ImageButton connectorButton;
    ImageButton clearConnectorsButton;
    LinearLayout importStatusRow;
    TextView importStatus;
    ImageButton importButton;
    ImageButton clearImportsButton;
    ImageButton reviewButton;
    ImageButton sendButton;
    ImageButton stopButton;

    ChatScreenView(Activity activity, UiTheme theme, Actions actions) {
        this.activity = activity;
        this.theme = theme;
        this.callbacks = actions;
    }

    View buildContent() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setFocusableInTouchMode(true);
        root.setPadding(theme.dp(12), theme.dp(8), theme.dp(12), theme.dp(8));
        root.setBackgroundColor(theme.page);

        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        backToThreadsButton = theme.iconButton(
            R.drawable.ic_chat_back,
            activity.getString(R.string.navigation_chats)
        );
        backToThreadsButton.setVisibility(View.GONE);
        backToThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.showThreadPage();
            }
        });
        topBar.addView(backToThreadsButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        screenTitle = theme.text(activity.getString(R.string.chat_title), 20, theme.primary);
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
            activity.getString(R.string.navigation_files)
        );
        filesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.openFiles();
            }
        });
        topBar.addView(filesButton, iconMarginParams(0));

        ImageButton terminalButton = theme.iconButton(
            R.drawable.ic_chat_terminal,
            activity.getString(R.string.navigation_terminal)
        );
        terminalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.openTerminal();
            }
        });
        topBar.addView(terminalButton, iconMarginParams(4));

        ImageButton settingsButton = theme.iconButton(
            R.drawable.ic_chat_settings,
            activity.getString(R.string.navigation_settings)
        );
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.openSettings();
            }
        });
        topBar.addView(settingsButton, iconMarginParams(4));

        root.addView(topBar);

        statusBanner = new LinearLayout(activity);
        statusBanner.setOrientation(LinearLayout.HORIZONTAL);
        statusBanner.setGravity(Gravity.CENTER_VERTICAL);
        statusBanner.setPadding(theme.dp(10), theme.dp(6), theme.dp(10), theme.dp(6));
        statusBanner.setBackground(theme.background(theme.surfaceRaised, theme.border, 16));
        statusIndicator = theme.statusDot(theme.accent);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(
            theme.dp(8), theme.dp(8)
        );
        indicatorParams.rightMargin = theme.dp(10);
        statusBanner.addView(statusIndicator, indicatorParams);
        statusText = theme.text(activity.getString(R.string.chat_runtime_checking), 13, theme.primary);
        statusText.setLineSpacing(0.0f, 1.15f);
        statusBanner.addView(statusText, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        statusSettingsButton = theme.iconButton(
            R.drawable.ic_chat_settings,
            activity.getString(R.string.chat_open_settings)
        );
        statusSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.openSettings();
            }
        });
        LinearLayout.LayoutParams statusActionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusActionParams.leftMargin = theme.dp(10);
        statusBanner.addView(statusSettingsButton, statusActionParams);
        theme.addWithTopMargin(root, statusBanner, 6);

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
        conversationParams.topMargin = theme.dp(6);
        root.addView(conversationPage, conversationParams);
        return root;
    }

    private LinearLayout buildThreadPage() {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView intro = theme.text(activity.getString(R.string.chat_intro), 14, theme.secondary);
        actions.addView(intro, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        newThreadButton = theme.primaryIconButton(
            R.drawable.ic_chat_add_thread,
            activity.getString(R.string.chat_new)
        );
        newThreadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.startNewThread();
            }
        });
        actions.addView(newThreadButton, iconMarginParams(8));

        refreshThreadsButton = theme.iconButton(
            R.drawable.ic_chat_refresh,
            activity.getString(R.string.chat_refresh)
        );
        refreshThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshThreads();
            }
        });
        actions.addView(refreshThreadsButton, iconMarginParams(4));
        page.addView(actions);

        LinearLayout threadViews = new LinearLayout(activity);
        threadViews.setOrientation(LinearLayout.HORIZONTAL);
        threadViews.setGravity(Gravity.CENTER_VERTICAL);
        threadSectionLabel = theme.sectionLabel(activity.getString(R.string.chat_active_threads));
        threadViews.addView(threadSectionLabel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));
        LinearLayout filters = new LinearLayout(activity);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4));
        filters.setBackground(theme.background(theme.surface, theme.border, 18));
        activeThreadsButton = theme.iconButton(
            R.drawable.ic_chat_active_threads,
            activity.getString(R.string.chat_active_threads)
        );
        activeThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.showActiveThreads();
            }
        });
        filters.addView(activeThreadsButton, iconMarginParams(0));
        archivedThreadsButton = theme.iconButton(
            R.drawable.ic_chat_archived_threads,
            activity.getString(R.string.chat_archived_threads)
        );
        archivedThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.showArchivedThreads();
            }
        });
        filters.addView(archivedThreadsButton, iconMarginParams(4));
        threadViews.addView(filters);
        theme.addWithTopMargin(page, threadViews, 14);

        threadEmptyView = theme.text(
            activity.getString(R.string.chat_empty),
            15,
            theme.secondary
        );
        threadEmptyView.setGravity(Gravity.CENTER);
        threadEmptyView.setLineSpacing(0.0f, 1.25f);
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

        threadList = new ListView(activity);
        threadList.setBackgroundColor(Color.TRANSPARENT);
        threadList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        threadList.setDividerHeight(theme.dp(10));
        threadList.setSelector(theme.touchBackground(Color.TRANSPARENT, Color.TRANSPARENT, 20));
        threadList.setDrawSelectorOnTop(true);
        threadList.setClipToPadding(false);
        threadList.setPadding(0, theme.dp(4), 0, theme.dp(4));
        threadAdapter = new ThreadListAdapter(activity, theme, callbacks);
        threadList.setAdapter(threadAdapter);
        threadList.setEmptyView(threadEmptyView);
        threadList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                CodexThreadSummary thread = threadAdapter.item(position);
                callbacks.openThread(thread);
            }
        });
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        listParams.topMargin = theme.dp(12);
        page.addView(threadList, listParams);
        loadMoreThreadsButton = theme.secondaryButton(
            activity.getString(R.string.chat_load_more)
        );
        loadMoreThreadsButton.setVisibility(View.GONE);
        loadMoreThreadsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.loadMoreThreads();
            }
        });
        theme.addWithTopMargin(page, loadMoreThreadsButton, 8);
        return page;
    }

    private LinearLayout buildConversationPage() {
        ConversationLayout page = new ConversationLayout(activity, theme);

        LinearLayout selectors = new LinearLayout(activity);
        selectors.setOrientation(LinearLayout.HORIZONTAL);
        selectors.setGravity(Gravity.CENTER_VERTICAL);
        modelSpinner = new Spinner(activity, Spinner.MODE_DIALOG);
        modelSpinner.setPrompt(activity.getString(R.string.model_section));
        styleSpinner(modelSpinner);
        selectors.addView(modelSpinner, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.2f
        ));

        effortSpinner = new Spinner(activity, Spinner.MODE_DIALOG);
        effortSpinner.setPrompt(activity.getString(R.string.reasoning_effort_section));
        styleSpinner(effortSpinner);
        LinearLayout.LayoutParams effortParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        effortParams.setMarginStart(theme.dp(6));
        selectors.addView(effortSpinner, effortParams);
        modelDetailsButton = theme.iconButton(
            R.drawable.ic_chat_more,
            activity.getString(R.string.chat_model_details)
        );
        modelDetailsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.showModelDetails();
            }
        });
        selectors.addView(modelDetailsButton, iconMarginParams(6));
        page.addView(selectors);

        messageScroll = new ScrollView(activity);
        messageScroll.setFillViewport(true);
        messagesContainer = new LinearLayout(activity);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(0, theme.dp(8), 0, theme.dp(8));
        messageScroll.addView(messagesContainer, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        page.addView(messageScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));

        LinearLayout composer = new LinearLayout(activity);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8));
        composer.setBackground(theme.background(theme.surface, theme.border, 16));

        LinearLayout editorContent = new LinearLayout(activity);
        editorContent.setOrientation(LinearLayout.VERTICAL);
        ScrollView editorScroll = new ScrollView(activity);
        editorScroll.setFillViewport(false);
        editorScroll.addView(editorContent, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        composer.addView(editorScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout attachments = new LinearLayout(activity);
        attachments.setOrientation(LinearLayout.HORIZONTAL);
        editorContent.addView(attachments);

        connectorStatusRow = new LinearLayout(activity);
        connectorStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        connectorStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        connectorStatusRow.setVisibility(View.GONE);
        connectorStatus = theme.text("", 12, theme.secondary);
        connectorStatus.setLineSpacing(0.0f, 1.15f);
        connectorStatus.setSingleLine(true);
        connectorStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        styleSelectionSummary(connectorStatus, R.string.chat_connectors);
        connectorStatusRow.addView(connectorStatus, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        clearConnectorsButton = theme.iconButton(
            R.drawable.ic_chat_detach,
            activity.getString(R.string.chat_connector_detach)
        );
        clearConnectorsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.detachPendingConnectors();
            }
        });
        connectorStatusRow.addView(clearConnectorsButton, iconMarginParams(0));
        attachments.addView(connectorStatusRow, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));

        importStatusRow = new LinearLayout(activity);
        importStatusRow.setOrientation(LinearLayout.HORIZONTAL);
        importStatusRow.setGravity(Gravity.CENTER_VERTICAL);
        importStatusRow.setVisibility(View.GONE);
        importStatus = theme.text("", 12, theme.secondary);
        importStatus.setLineSpacing(0.0f, 1.15f);
        importStatus.setSingleLine(true);
        importStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        styleSelectionSummary(importStatus, R.string.chat_import_files);
        importStatusRow.addView(importStatus, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        clearImportsButton = theme.iconButton(
            R.drawable.ic_chat_detach,
            activity.getString(R.string.chat_import_detach)
        );
        clearImportsButton.setVisibility(View.GONE);
        clearImportsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.detachPendingImports();
            }
        });
        importStatusRow.addView(clearImportsButton, iconMarginParams(0));
        attachments.addView(importStatusRow, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));

        composerInput = new EditText(activity);
        composerInput.setHint(R.string.composer_hint);
        composerInput.setHintTextColor(theme.secondary);
        composerInput.setTextColor(theme.primary);
        composerInput.setTextSize(16);
        composerInput.setBackground(theme.background(theme.surfaceRaised, theme.border, 14));
        composerInput.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
        composerInput.setGravity(Gravity.TOP | Gravity.START);
        composerInput.setMinLines(2);
        composerInput.setMaxLines(6);
        composerInput.setMinimumHeight(theme.dp(48));
        composerInput.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        composerInput.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        composerInput.setVerticalScrollBarEnabled(true);
        composerInput.setOnTouchListener(new View.OnTouchListener() {
            private float lastY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    lastY = event.getY();
                    view.getParent().requestDisallowInterceptTouchEvent(
                        view.canScrollVertically(-1) || view.canScrollVertically(1)
                    );
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int direction = event.getY() < lastY ? 1 : -1;
                    view.getParent().requestDisallowInterceptTouchEvent(view.canScrollVertically(direction));
                    lastY = event.getY();
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            }
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        editorContent.addView(composerInput, inputParams);

        LinearLayout composerRow = new LinearLayout(activity);
        composerRow.setOrientation(LinearLayout.HORIZONTAL);
        composerRow.setGravity(Gravity.CENTER_VERTICAL);
        importButton = theme.iconButton(
            R.drawable.ic_chat_add,
            activity.getString(R.string.chat_import_files)
        );
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.openDocumentImportPicker();
            }
        });
        composerRow.addView(importButton);

        connectorButton = theme.iconButton(
            R.drawable.ic_chat_connectors,
            activity.getString(R.string.chat_connectors)
        );
        connectorButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.openConnectorPicker();
            }
        });
        composerRow.addView(connectorButton, iconMarginParams(4));

        reviewButton = theme.iconButton(
            R.drawable.ic_chat_review,
            activity.getString(R.string.review_mode_action)
        );
        reviewButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ReviewModeDialog.show(
                    activity,
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
        composerRow.addView(reviewButton, iconMarginParams(4));
        composerRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1.0f));

        stopButton = theme.dangerIconButton(
            R.drawable.ic_chat_stop,
            activity.getString(R.string.turn_stop)
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
            activity.getString(R.string.message_send)
        );
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                callbacks.sendComposerInput();
            }
        });
        composerRow.addView(sendButton, iconMarginParams(6));
        theme.addWithTopMargin(composer, composerRow, 6);
        page.addView(composer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        page.bindComposer(selectors, composer, editorScroll, composerInput);
        return page;
    }

    private void styleSpinner(Spinner spinner) {
        spinner.setBackground(theme.background(theme.surfaceRaised, theme.border, 12));
        spinner.setPadding(theme.dp(10), 0, theme.dp(8), 0);
        spinner.setMinimumHeight(theme.dp(48));
    }

    private void styleSelectionSummary(final TextView summary, final int titleResource) {
        summary.setMinimumHeight(theme.dp(48));
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(theme.dp(6), 0, theme.dp(6), 0);
        summary.setBackground(theme.touchBackground(theme.surfaceRaised, Color.TRANSPARENT, 10));
        summary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showChatDetails(activity.getString(titleResource), summary.getText());
            }
        });
    }

    void showChatDetails(String title, CharSequence details) {
        dismissChatDetails();
        TextView body = theme.body(details.toString());
        body.setTextIsSelectable(true);
        body.setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        chatDetailsDialog = new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create();
        chatDetailsDialog.show();
    }

    void dismissChatDetails() {
        if (chatDetailsDialog != null) {
            chatDetailsDialog.dismiss();
            chatDetailsDialog = null;
        }
    }

    private LinearLayout.LayoutParams iconMarginParams(int leftMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMarginStart(theme.dp(leftMarginDp));
        return params;
    }

}
