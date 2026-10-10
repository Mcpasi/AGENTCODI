package de.agentcodi.app;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import de.agentcodi.connectors.ConnectorProvider;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexFileMentionTransaction;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexReasoningOption;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexThreadSummary;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.runtime.AgentRuntimeService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ChatConversationUiTest {
    private ActivityController<MainActivity> lifecycle;
    private MainActivity activity;
    private ChatComposerController composer;
    private LinearLayout root;
    private CodexSessionController session;
    private boolean transcriptWindowVisible;

    @Before
    public void create() throws Exception {
        session = new CodexSessionController(new UnusedTransport(), "/chat-ui-fixture");
        set(session, "ready", true);
        set(session, "requiresOpenaiAuth", false);
        set(session, "activeThreadId", "thread");
        set(session, "activeThreadTitle", "Conversation");
        set(session, "selectedModelId", "gpt");
        set(session, "selectedReasoningEffort", "high");
        List<CodexModelOption> models = list(session, "models");
        models.add(new CodexModelOption("gpt", "gpt", "GPT-5.4 with a long display name",
            "Complete model description", "high", Arrays.asList(
                new CodexReasoningOption("low", "Quick thinking"),
                new CodexReasoningOption("high", "Detailed thinking")), true));
        models.add(new CodexModelOption("custom", "custom", "Custom model",
            "Second model description", "ultra", Arrays.asList(
                new CodexReasoningOption("max", "Maximum thinking"),
                new CodexReasoningOption("ultra", "Complete ultra thinking description")), false));
        publishSession();
        setStatic(AgentRuntimeService.class, "sessionController", session);
        lifecycle = Robolectric.buildActivity(MainActivity.class).create();
        activity = lifecycle.get();
        composer = (ChatComposerController) field(activity, "composer").get(activity);
        root = (LinearLayout) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        call("showConversationPage", new Class<?>[]{CodexSessionSnapshot.class}, session.snapshot());
        renderSession();
    }

    @After
    public void close() throws Exception {
        if (lifecycle != null) {
            lifecycle.destroy();
        }
        setStatic(AgentRuntimeService.class, "sessionController", null);
        session.close();
    }

    @Test
    public void threadListKeepsAllLoadedRowsAndUpdatesRowsBeyondEighty() throws Exception {
        List<CodexThreadSummary> threads = list(session, "threads");
        for (int index = 0; index < 240; index++) {
            threads.add(new CodexThreadSummary("thr_" + index, "Chat " + index, index, false));
        }
        publishSession();
        renderSession();
        view("backToThreadsButton").performClick();
        layout(390, 720);
        ListView threadList = view("threadList");
        ThreadListAdapter adapter = (ThreadListAdapter) threadList.getAdapter();
        assertEquals(240, adapter.getCount());
        assertEquals("thr_239", adapter.item(239).getId());
        threadList.setSelection(239);
        layout(390, 720);
        assertEquals(239, threadList.getLastVisiblePosition());
        View lastRow = adapter.getView(239, null, threadList);
        assertTrue(containsText(lastRow, "Chat 239"));
        threads.set(239, new CodexThreadSummary("thr_239", "Renamed last chat", 999, false));
        publishSession();
        renderSession();
        assertEquals("Renamed last chat", adapter.item(239).getTitle());
        assertTrue(containsText(adapter.getView(239, lastRow, threadList), "Renamed last chat"));
        threads.add(new CodexThreadSummary("thr_240", "Appended chat", 998, false));
        publishSession();
        renderSession();
        assertEquals(241, adapter.getCount());
        assertEquals("thr_240", adapter.item(240).getId());
    }

    @Test
    public void threadPaginationShowsOnlyWithCursorAndRespectsBusyState() throws Exception {
        view("backToThreadsButton").performClick();
        assertEquals(View.GONE, view("loadMoreThreadsButton").getVisibility());
        set(session, "nextThreadCursor", "next-page");
        publishSession();
        renderSession();
        layout(320, 640);
        TextView button = view("loadMoreThreadsButton");
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(activity.getString(R.string.chat_load_more), button.getText().toString());
        assertTrue(button.isEnabled());
        assertTrue(button.getHeight() >= 48);
        assertTrue(button.getBottom() <= view("threadPage").getHeight());
        for (String busyField : new String[]{"operationActive", "turnActive", "turnInterruptPending"}) {
            set(session, busyField, true);
            publishSession();
            renderSession();
            assertFalse("pagination disabled during " + busyField, button.isEnabled());
            set(session, busyField, false);
        }
        set(session, "showingArchivedThreads", true);
        publishSession();
        renderSession();
        assertTrue(button.isEnabled());
        assertEquals(View.VISIBLE, button.getVisibility());
        set(session, "nextThreadCursor", "");
        publishSession();
        renderSession();
        assertEquals(View.GONE, button.getVisibility());
    }

    @Test
    public void mobileAndTabletControlsFit() throws Exception {
        for (int width : new int[]{320, 360, 390, 430, 720}) {
            layout(width, 720);
            EditText input = view("composerInput");
            LinearLayout composer = (LinearLayout) input.getParent();
            assertEquals(composer.getWidth() - composer.getPaddingLeft() - composer.getPaddingRight(), input.getWidth());
            assertTrue("prompt gets the available width", input.getWidth() > width * 0.8);
            checkActions(false);
            ScrollView transcript = view("messageScroll");
            assertTrue("transcript stays usable", transcript.getHeight() > 300);
            if (width == 390) {
                screenshot("phone");
            }
        }
        screenshot("tablet");
    }

    @Test
    public void activeTurnKeepsReviewStopAndSteeringAccessible() throws Exception {
        set(session, "turnActive", true);
        set(session, "activeTurnId", "turn");
        publishSession();
        renderSession();
        layout(320, 520);
        checkActions(true);
        assertFalse(view("reviewButton").isEnabled());
        assertTrue(view("stopButton").isEnabled());
        assertTrue(view("composerInput").isEnabled());
        assertEquals(activity.getString(R.string.turn_steer), view("sendButton").getContentDescription());
        assertFalse(view("modelSpinner").isEnabled());
        assertFalse(view("effortSpinner").isEnabled());
    }

    @Test
    public void keyboardAndLandscapeKeepDraftAndScrollableControls() throws Exception {
        EditText input = view("composerInput");
        String prompt = String.join("\n", Collections.nCopies(24,
            "A long prompt that needs wrapping and retains every line."));
        input.setText(prompt);
        input.setSelection(32);
        input.requestFocus();
        view("connectorStatusRow").setVisibility(View.VISIBLE);
        ((TextView) view("connectorStatus")).setText("Gmail and GitHub attached");
        view("importStatusRow").setVisibility(View.VISIBLE);
        ((TextView) view("importStatus")).setText("16 attached files with long filenames");
        view("clearImportsButton").setVisibility(View.VISIBLE);
        view("clearConnectorsButton").setVisibility(View.VISIBLE);
        for (int[] size : new int[][]{{320, 300}, {640, 260}, {320, 720}}) {
            layout(size[0], size[1]);
            ShadowLooper.idleMainLooper();
            assertEquals(prompt, input.getText().toString());
            assertEquals(32, input.getSelectionStart());
            assertTrue("transcript retained", view("messageScroll").getHeight() > 0);
            ScrollView editor = (ScrollView) ((View) input.getParent()).getParent();
            View send = view("sendButton");
            ViewGroup tools = (ViewGroup) send.getParent();
            ViewGroup composer = (ViewGroup) tools.getParent();
            assertTrue("composer bounded", composer.getBottom() <= view("conversationPage").getHeight());
            assertTrue("send remains in view", tools.getTop() + send.getBottom() <= composer.getHeight());
            assertTrue("editor remains usable", editor.getHeight() >= 48);
            if (size[1] < 320) {
                View selectors = (View) view("modelSpinner").getParent();
                assertSame("compact options remain in the editor", input.getParent(), selectors.getParent());
                screenshot(size[0] == 320 ? "keyboard" : "landscape");
            } else {
                assertSame("options return above the transcript", view("conversationPage"), view("modelSpinner").getParent().getParent());
            }
            editor.setSmoothScrollingEnabled(false);
            editor.fullScroll(View.FOCUS_DOWN);
            assertTrue("prompt scrolls into view", input.getBottom() - editor.getScrollY() <= editor.getHeight());
            if (size[1] < 320) {
                input.scrollTo(0, 0);
                int beforeSwipe = editor.getScrollY();
                swipeDown(editor);
                assertTrue("scrolling past the prompt boundary reveals compact options",
                    editor.getScrollY() < beforeSwipe);
                input.setSelection(32);
            }
            checkActions(false);
        }
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            activity.getWindow().getAttributes().softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        assertTrue((input.getImeOptions() & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        assertTrue((input.getImeOptions() & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0);
    }

    @Test
    public void selectorsApplyModelAndThinkingChangesWithoutRefreshResets() throws Exception {
        Spinner model = view("modelSpinner");
        Spinner effort = view("effortSpinner");
        Object modelAdapter = model.getAdapter();
        Object effortAdapter = effort.getAdapter();
        bindSelectors();
        assertSame(modelAdapter, model.getAdapter());
        assertSame(effortAdapter, effort.getAdapter());
        assertEquals(1, effort.getSelectedItemPosition());
        effort.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getListView().performItemClick(null, 0, 0);
        ShadowLooper.idleMainLooper();
        assertEquals("low", session.snapshot().getSelectedReasoningEffort());
        model.performClick();
        dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("GPT-5.4 with a long display name", dialog.getListView().getAdapter().getItem(0));
        dialog.getListView().performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();
        assertEquals("custom", session.snapshot().getSelectedModelId());
        assertEquals("ultra", session.snapshot().getSelectedReasoningEffort());
        bindSelectors();
        assertSame(modelAdapter, model.getAdapter());
        assertNotSame(effortAdapter, effort.getAdapter());
        assertEquals("Ultra", effort.getAdapter().getItem(1));
    }

    @Test
    public void fullModelAndThinkingDescriptionsRemainInspectable() throws Exception {
        view("modelDetailsButton").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(containsText(dialog.getWindow().getDecorView(), "Complete model description"));
        assertTrue(containsText(dialog.getWindow().getDecorView(), "Detailed thinking"));
        assertTrue(containsText(dialog.getWindow().getDecorView(), "GPT-5.4 with a long display name"));
    }

    @Test
    public void allAttachmentsAndBothDetachActionsRemainAccessible() throws Exception {
        List<ImportedWorkspaceFile> imports = list(composer, "pendingImports");
        for (int i = 0; i < 16; i++) {
            imports.add(ImportedWorkspaceFile.create("imports/file-" + i + ".txt",
                "Long filename " + i + ".txt", "text/plain", 42,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        }
        list(composer, "pendingConnectors").add(new ConnectorSelection(ConnectorProvider.GMAIL, "gmail", "Gmail"));
        list(composer, "pendingConnectors").add(new ConnectorSelection(ConnectorProvider.GITHUB, "github", "GitHub"));
        set(composer, "pendingImportsThreadId", "thread");
        set(composer, "pendingConnectorsThreadId", "thread");
        renderSession();
        layout(320, 720);
        TextView summary = view("importStatus");
        assertTrue(summary.getText().toString().contains("Long filename 15.txt"));
        summary.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(containsText(dialog.getWindow().getDecorView(), "Long filename 15.txt"));
        dialog.dismiss();
        view("connectorStatus").performClick();
        dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(containsText(dialog.getWindow().getDecorView(), "Gmail"));
        assertTrue(containsText(dialog.getWindow().getDecorView(), "GitHub"));
        dialog.dismiss();
        view("clearImportsButton").performClick();
        assertTrue(list(composer, "pendingImports").isEmpty());
        view("clearConnectorsButton").performClick();
        assertTrue(list(composer, "pendingConnectors").isEmpty());
    }

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-night")
    public void largerGermanTextAndNightThemeFit() throws Exception {
        close();
        RuntimeEnvironment.setFontScale(1.6f);
        create();
        layout(320, 640);
        checkActions(false);
        assertEquals("Denkstufe", activity.getString(R.string.chat_reasoning_label));
        assertTrue(view("modelSpinner").getContentDescription().toString().contains("GPT-5.4 with a long display name"));
        screenshot("large-text-dark");
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp")
    public void chineseControlsRetainTheirLabelsAndFunctions() throws Exception {
        layout(320, 640);
        checkActions(false);
        assertEquals("推理强度", activity.getString(R.string.chat_reasoning_label));
        view("modelDetailsButton").performClick();
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    @Test
    public void unavailableSendPreservesDraftAndCredentialGuardStillRuns() throws Exception {
        setStatic(AgentRuntimeService.class, "sessionController", null);
        EditText input = view("composerInput");
        input.setText("First line\nSecond line");
        view("sendButton").performClick();
        assertEquals("First line\nSecond line", input.getText().toString());
        input.setText("sk-proj-" + String.join("", Collections.nCopies(64, "a")));
        view("sendButton").performClick();
        assertEquals("", input.getText().toString());
    }

    @Test
    public void importConnectorsReviewAndNavigationKeepTheirHandlers() throws Exception {
        view("importButton").performClick();
        Intent intent = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        view("connectorButton").performClick();
        intent = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(ConnectorActivity.class.getName(), intent.getComponent().getClassName());
        view("reviewButton").performClick();
        AlertDialog review = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(review.isShowing());
        review.dismiss();
        for (Object[] action : new Object[][]{
            {R.string.navigation_files, WorkspaceBrowserActivity.class},
            {R.string.navigation_terminal, TerminalActivity.class},
            {R.string.navigation_settings, SettingsActivity.class}
        }) {
            findAction(root, activity.getString((Integer) action[0])).performClick();
            intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(((Class<?>) action[1]).getName(), intent.getComponent().getClassName());
        }
        view("backToThreadsButton").performClick();
        assertEquals(View.VISIBLE, view("threadPage").getVisibility());
        assertEquals(View.GONE, view("conversationPage").getVisibility());
    }

    @Test
    public void pickerResultsKeepReadGrantsAndExportCancellationBoundaries() throws Exception {
        ChatTranscriptController transcript =
            (ChatTranscriptController) field(activity, "transcript").get(activity);
        set(transcript, "pendingImageExportPath", "/chat-ui-fixture/generated.png");
        Intent missingGrant = new Intent().setData(Uri.parse("content://documents/file"));
        activity.onActivityResult(7002, Activity.RESULT_OK, missingGrant);
        assertEquals(activity.getString(R.string.chat_import_read_grant_missing),
            ShadowToast.getTextOfLatestToast());
        assertTrue(list(composer, "pendingImports").isEmpty());
        assertFalse((Boolean) field(composer, "importOperationActive").get(composer));
        activity.onActivityResult(7003, Activity.RESULT_CANCELED, null);
        assertEquals("/chat-ui-fixture/generated.png",
            field(transcript, "pendingImageExportPath").get(transcript));
        activity.onActivityResult(7001, Activity.RESULT_CANCELED, null);
        assertEquals("", field(transcript, "pendingImageExportPath").get(transcript));
        assertFalse(composer.handleActivityResult(9999, Activity.RESULT_OK, missingGrant));
        assertFalse(transcript.handleActivityResult(9999, Activity.RESULT_OK, missingGrant));
    }

    @Test
    public void destroyClosesPreparedImportsAndRejectsLateCompletion() throws Exception {
        TrackingTransaction transaction = new TrackingTransaction();
        set(composer, "preparedImportSend", transaction);
        set(composer, "sendPreparationActive", true);
        EditText input = view("composerInput");
        input.setText("Retain the draft");
        ChatTranscriptController transcript =
            (ChatTranscriptController) field(activity, "transcript").get(activity);
        lifecycle.destroy();
        lifecycle = null;
        assertEquals(1, transaction.closeCalls);
        assertNull(field(composer, "preparedImportSend").get(composer));
        assertTrue(((ExecutorService) field(composer, "importOperations").get(composer)).isShutdown());
        assertTrue(((ExecutorService) field(transcript, "imageOperations").get(transcript)).isShutdown());
        completePreparedSend(transaction);
        assertEquals(0, transaction.sendCalls);
        assertEquals("Retain the draft", input.getText().toString());
    }

    @Test
    public void changedThreadRejectsPreparedSendAndClearsStaleSelections() throws Exception {
        TrackingTransaction transaction = new TrackingTransaction();
        set(composer, "preparedImportSend", transaction);
        set(composer, "sendPreparationActive", true);
        list(composer, "pendingImports").add(ImportedWorkspaceFile.create(
            "imports/file.txt", "file.txt", "text/plain", 42,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        list(composer, "pendingConnectors").add(
            new ConnectorSelection(ConnectorProvider.GITHUB, "github", "GitHub"));
        set(composer, "pendingImportsThreadId", "thread");
        set(composer, "pendingConnectorsThreadId", "thread");
        view("composerInput").setEnabled(true);
        ((EditText) view("composerInput")).setText("Keep this draft too");
        set(session, "activeThreadId", "other-thread");
        publishSession();
        completePreparedSend(transaction);
        assertEquals(1, transaction.closeCalls);
        assertEquals(0, transaction.sendCalls);
        assertTrue(list(composer, "pendingImports").isEmpty());
        assertTrue(list(composer, "pendingConnectors").isEmpty());
        assertFalse((Boolean) field(composer, "sendPreparationActive").get(composer));
        assertEquals("Keep this draft too", ((EditText) view("composerInput")).getText().toString());
    }

    @Test
    public void transcriptUpdatesRowsInPlaceAndRebuildsOnThreadChange() throws Exception {
        ChatTranscriptController transcript =
            (ChatTranscriptController) field(activity, "transcript").get(activity);
        LinearLayout messages = view("messagesContainer");
        transcript.renderTranscript("thread", Collections.singletonList(CodexTranscriptItem.message(
            new ChatMessage("response", ChatMessage.Role.ASSISTANT, "Partial response", true))));
        View row = messages.getChildAt(0);
        assertTrue(containsText(row, "Partial response"));
        transcript.renderTranscript("thread", Collections.singletonList(CodexTranscriptItem.message(
            new ChatMessage("response", ChatMessage.Role.ASSISTANT, "Complete response", false))));
        assertSame(row, messages.getChildAt(0));
        assertTrue(containsText(row, "Complete response"));
        transcript.renderTranscript("other-thread", Collections.singletonList(CodexTranscriptItem.message(
            new ChatMessage("response", ChatMessage.Role.USER, "Another conversation", false))));
        assertNotSame(row, messages.getChildAt(0));
        assertNull(row.getParent());
        assertTrue(containsText(messages, "Another conversation"));
        transcript.renderTranscript("other-thread", Collections.<CodexTranscriptItem>emptyList());
        assertTrue(containsText(messages, activity.getString(R.string.chat_no_messages)));
    }

    @Test
    public void transcriptReusesMessagesAndExpandedToolsAcrossInsertMoveAndRemoval() throws Exception {
        ChatTranscriptController transcript = transcript();
        LinearLayout messages = view("messagesContainer");
        CodexTranscriptItem first = transcriptMessage("first", "First message", false);
        CodexTranscriptItem last = transcriptMessage("last", "Last message", false);
        CodexTranscriptItem tool = transcriptTool("tool", "Tool output", false);
        transcript.renderTranscript("thread", Arrays.asList(first, tool, last));
        View firstRow = messages.getChildAt(0);
        TranscriptCardView toolRow = (TranscriptCardView) messages.getChildAt(1);
        View lastRow = messages.getChildAt(2);
        View expand = findAction(toolRow, activity.getString(R.string.transcript_expand,
            UiText.cardTitle(activity, tool)));
        assertNotNull(expand);
        expand.performClick();
        assertEquals(View.VISIBLE, toolRow.contentContainer().getVisibility());

        CodexTranscriptItem inserted = transcriptMessage("inserted", "Inserted", false);
        transcript.renderTranscript("thread", Arrays.asList(first, tool, last, inserted));
        assertSame(firstRow, messages.getChildAt(0));
        assertSame(toolRow, messages.getChildAt(1));
        assertSame(lastRow, messages.getChildAt(2));
        View insertedRow = messages.getChildAt(3);
        transcript.renderTranscript("thread", Arrays.asList(inserted, tool, first, last));
        assertSame(insertedRow, messages.getChildAt(0));
        assertSame(toolRow, messages.getChildAt(1));
        assertSame(firstRow, messages.getChildAt(2));
        assertSame(lastRow, messages.getChildAt(3));
        assertEquals(View.VISIBLE, toolRow.contentContainer().getVisibility());

        transcript.renderTranscript("thread", Arrays.asList(tool, last));
        assertEquals(2, messages.getChildCount());
        assertSame(toolRow, messages.getChildAt(0));
        assertSame(lastRow, messages.getChildAt(1));
        assertNull(firstRow.getParent());
        assertNull(insertedRow.getParent());
        assertEquals(0, ((LinearLayout.LayoutParams) toolRow.getLayoutParams()).topMargin);
        assertTrue(((LinearLayout.LayoutParams) lastRow.getLayoutParams()).topMargin > 0);
        transcript.renderTranscript("thread", Arrays.asList(
            transcriptTool("tool", "Updated tool output", false), last));
        assertSame(toolRow, messages.getChildAt(0));
        assertTrue(containsText(toolRow, "Updated tool output"));
        assertEquals(View.VISIBLE, toolRow.contentContainer().getVisibility());
    }

    @Test
    public void transcriptFollowsAppendsAndStreamingAtEndWithoutTakingComposerFocus() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        ScrollView scroll = view("messageScroll");
        EditText editor = view("composerInput");
        editor.requestFocus();
        assertTranscriptAtEnd();
        items.add(transcriptMessage("stream", "Streaming", true));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertTranscriptAtEnd();
        items.set(items.size() - 1, transcriptMessage("stream", longText("Growing"), true));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertTranscriptAtEnd();
        items.add(transcriptTool("tool", "Tool output", true));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertTranscriptAtEnd();
        assertTrue(editor.hasFocus());
        assertEquals(View.GONE, view("newOutputButton").getVisibility());
        int oldHeight = scroll.getHeight();
        layout(390, 520);
        scroll.getViewTreeObserver().dispatchOnGlobalLayout();
        assertTrue(scroll.getHeight() < oldHeight);
        assertTranscriptAtEnd();
    }

    @Test
    public void readingOlderMessagesKeepsPositionAndOffersNewOutputThenResumesFollowing() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        ScrollView scroll = view("messageScroll");
        LinearLayout messages = view("messagesContainer");
        scroll.scrollTo(0, messages.getChildAt(5).getTop() + 20);
        int readingY = scroll.getScrollY();
        int viewportHeight = scroll.getHeight();
        items.add(transcriptMessage("stream", "New output", true));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(readingY, scroll.getScrollY());
        assertEquals(viewportHeight, scroll.getHeight());
        assertEquals(View.VISIBLE, view("newOutputButton").getVisibility());
        items.set(items.size() - 1, transcriptMessage("stream", longText("New output grows"), true));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(readingY, scroll.getScrollY());
        view("newOutputButton").performClick();
        settleTranscript();
        assertTranscriptAtEnd();
        assertEquals(View.GONE, view("newOutputButton").getVisibility());
        items.add(transcriptTool("new-tool", "Latest tool output", false));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertTranscriptAtEnd();
    }

    @Test
    public void readingAnchorSurvivesGrowthInsertionReorderAndRemovalAboveIt() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        ScrollView scroll = view("messageScroll");
        LinearLayout messages = view("messagesContainer");
        View anchor = messages.getChildAt(5);
        scroll.scrollTo(0, anchor.getTop() + 20);
        int offset = anchor.getTop() - scroll.getScrollY();
        items.set(0, transcriptMessage("message-0", longText("Extra lines") + longText("More"), false));
        items.add(0, transcriptMessage("inserted", longText("Inserted above"), false));
        transcript.renderTranscript("thread", items);
        // Coalesce another update before layout without recapturing stale row coordinates.
        items.add(0, items.remove(8));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(offset, anchor.getTop() - scroll.getScrollY());
        items.remove(0);
        items.remove(0);
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(offset, anchor.getTop() - scroll.getScrollY());

        View successor = messages.getChildAt(messages.indexOfChild(anchor) + 1);
        int successorOffset = successor.getTop() - scroll.getScrollY();
        for (int index = 0; index < items.size(); index++) {
            if ("message-5".equals(items.get(index).getId())) {
                items.remove(index);
                break;
            }
        }
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertNull(anchor.getParent());
        assertEquals(successorOffset, successor.getTop() - scroll.getScrollY());
    }

    @Test
    public void removalReorderAndUnchangedRefreshDoNotAnnounceNewOutput() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        ScrollView scroll = view("messageScroll");
        scroll.scrollTo(0, 100);
        transcript.renderTranscript("thread", new ArrayList<CodexTranscriptItem>(items));
        settleTranscript();
        assertEquals(100, scroll.getScrollY());
        assertEquals(View.GONE, view("newOutputButton").getVisibility());
        items.remove(items.size() - 1);
        Collections.swap(items, 10, 11);
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(100, scroll.getScrollY());
        assertEquals(View.GONE, view("newOutputButton").getVisibility());
        items.add(transcriptTool("new-tool", "Tool output", true));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(View.VISIBLE, view("newOutputButton").getVisibility());
        scroll.scrollTo(0, Integer.MAX_VALUE);
        assertEquals(View.GONE, view("newOutputButton").getVisibility());
        items.set(items.size() - 1, transcriptTool("new-tool", "Finished output", false));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertTranscriptAtEnd();
    }

    @Test
    public void userGestureCancelsPendingFollowAndPreservesTheirNewReadingPosition() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        items.add(transcriptMessage("pending", longText("Pending output"), false));
        transcript.renderTranscript("thread", items);
        ScrollView scroll = view("messageScroll");
        // A scroll without a touch event also takes precedence (keyboard/accessibility/fling).
        scroll.scrollTo(0, 100);
        settleTranscript();
        assertEquals(100, scroll.getScrollY());
        scroll.scrollTo(0, Integer.MAX_VALUE);
        items.add(transcriptMessage("pending-gesture", longText("Pending gesture output"), false));
        transcript.renderTranscript("thread", items);
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20, 20, 0);
        scroll.dispatchTouchEvent(down);
        down.recycle();
        scroll.scrollTo(0, 100);
        items.add(transcriptMessage("during-touch", longText("More output"), false));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        assertEquals(100, scroll.getScrollY());
        MotionEvent cancel = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_CANCEL, 20, 20, 0);
        scroll.dispatchTouchEvent(cancel);
        cancel.recycle();
        settleTranscript();
        assertEquals(100, scroll.getScrollY());
        assertEquals(View.VISIBLE, view("newOutputButton").getVisibility());

        now = android.os.SystemClock.uptimeMillis();
        down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20, 20, 0);
        scroll.dispatchTouchEvent(down);
        down.recycle();
        View anchor = ((LinearLayout) view("messagesContainer")).getChildAt(0);
        int offset = anchor.getTop() - scroll.getScrollY();
        items.add(0, transcriptMessage("above-touch", longText("Inserted above the held position"), false));
        transcript.renderTranscript("thread", items);
        settleTranscript();
        cancel = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_CANCEL, 20, 20, 0);
        scroll.dispatchTouchEvent(cancel);
        cancel.recycle();
        // Restore after release even if the already-completed layout is the last one.
        assertEquals(offset, anchor.getTop() - scroll.getScrollY());
    }

    @Test
    public void threadSwitchDiscardsPendingPositionAndUnreadStateAndKeepsEmptyViewStable() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        ScrollView scroll = view("messageScroll");
        LinearLayout messages = view("messagesContainer");
        View oldRow = messages.getChildAt(0);
        scroll.scrollTo(0, 100);
        items.add(transcriptMessage("pending", "Pending", true));
        transcript.renderTranscript("thread", items);
        assertEquals(View.VISIBLE, view("newOutputButton").getVisibility());
        transcript.renderTranscript("other-thread", longTranscript());
        settleTranscript();
        assertNull(oldRow.getParent());
        assertNotSame(oldRow, messages.getChildAt(0));
        assertTranscriptAtEnd();
        assertEquals(View.GONE, view("newOutputButton").getVisibility());
        transcript.renderTranscript("other-thread", Collections.<CodexTranscriptItem>emptyList());
        settleTranscript();
        View empty = messages.getChildAt(0);
        assertEquals(1, messages.getChildCount());
        assertTrue(containsText(empty, activity.getString(R.string.chat_no_messages)));
        transcript.renderTranscript("other-thread", Collections.<CodexTranscriptItem>emptyList());
        assertSame(empty, messages.getChildAt(0));
        transcript.renderTranscript("", Collections.<CodexTranscriptItem>emptyList());
        assertSame(empty, messages.getChildAt(0));
        assertTrue(containsText(empty, activity.getString(R.string.chat_select)));
    }

    @Test
    public void closingTranscriptDiscardsPendingScrollAndIgnoresLaterRenders() throws Exception {
        ChatTranscriptController transcript = transcript();
        List<CodexTranscriptItem> items = longTranscript();
        transcript.renderTranscript("thread", items);
        settleTranscript();
        ScrollView scroll = view("messageScroll");
        scroll.scrollTo(0, 100);
        items.add(transcriptMessage("pending", "Pending", true));
        transcript.renderTranscript("thread", items);
        transcript.close();
        transcript.renderTranscript("other-thread", Collections.<CodexTranscriptItem>emptyList());
        settleTranscript();
        assertEquals(100, scroll.getScrollY());
        assertEquals(items.size(), ((LinearLayout) view("messagesContainer")).getChildCount());
    }

    private ChatTranscriptController transcript() throws Exception {
        return (ChatTranscriptController) field(activity, "transcript").get(activity);
    }

    private static CodexTranscriptItem transcriptMessage(String id, String text, boolean streaming) {
        return CodexTranscriptItem.message(new ChatMessage(id, ChatMessage.Role.ASSISTANT, text, streaming));
    }

    private static CodexTranscriptItem transcriptTool(String id, String text, boolean streaming) {
        return CodexTranscriptItem.card(id, CodexTranscriptItem.Kind.TOOL, "commandExecution",
            "Run command", text, "", streaming ? "inProgress" : "completed", streaming);
    }

    private static String longText(String prefix) {
        return prefix + "\nLine one\nLine two\nLine three\nLine four\nLine five\nLine six";
    }

    private static List<CodexTranscriptItem> longTranscript() {
        List<CodexTranscriptItem> items = new ArrayList<CodexTranscriptItem>();
        for (int index = 0; index < 20; index++) {
            items.add(transcriptMessage("message-" + index, longText("Message " + index), false));
        }
        return items;
    }

    private void settleTranscript() throws Exception {
        if (!transcriptWindowVisible) {
            lifecycle.visible();
            transcriptWindowVisible = true;
        }
        layout(390, 720);
        assertTrue("transcript fixture is visible", view("messageScroll").isShown());
        view("messageScroll").getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private void assertTranscriptAtEnd() throws Exception {
        ScrollView scroll = view("messageScroll");
        LinearLayout messages = view("messagesContainer");
        assertTrue("fixture must have scrollable history", messages.getHeight() > scroll.getHeight());
        assertEquals(messages.getBottom() + scroll.getPaddingBottom() - scroll.getHeight(), scroll.getScrollY());
    }

    private void completePreparedSend(TrackingTransaction transaction) throws Exception {
        Method method = ChatComposerController.class.getDeclaredMethod("completePreparedSend",
            String.class, List.class, CodexFileMentionTransaction.class, boolean.class,
            String.class, String.class, List.class, List.class);
        method.setAccessible(true);
        method.invoke(composer, "draft", Collections.emptyList(), transaction, false,
            "thread", "", Collections.emptyList(), Collections.emptyList());
    }

    private static final class TrackingTransaction implements CodexFileMentionTransaction {
        private int closeCalls;
        private int sendCalls;

        @Override
        public int getFileCount() {
            return 1;
        }

        @Override
        public void withVerifiedMentions(VerifiedSender sender) {
            sendCalls++;
            fail("A canceled or stale prepared send must not reach the runtime");
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }

    private void checkActions(boolean active) throws Exception {
        ViewGroup tools = (ViewGroup) view("sendButton").getParent();
        for (String name : new String[]{"importButton", "connectorButton", "reviewButton", "sendButton"}) {
            View button = view(name);
            assertEquals(name + " remains visible", View.VISIBLE, button.getVisibility());
            assertTrue(name + " touch width", button.getWidth() >= 48);
            assertTrue(name + " touch height", button.getHeight() >= 48);
        }
        if (active) {
            assertEquals(View.VISIBLE, view("stopButton").getVisibility());
        }
        int end = 0;
        for (int i = 0; i < tools.getChildCount(); i++) {
            View child = tools.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) {
                continue;
            }
            assertTrue("no action overlaps", child.getLeft() >= end);
            assertTrue("action fits", child.getRight() <= tools.getWidth());
            end = child.getRight();
        }
    }

    private void layout(int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private static void swipeDown(ScrollView editor) {
        long start = android.os.SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(start, start + i * 20,
                actions[i], editor.getWidth() / 2.0f, 16 + i * 40, 0);
            editor.dispatchTouchEvent(event);
            event.recycle();
        }
    }

    private void bindSelectors() throws Exception {
        call("bindSelectors", new Class<?>[]{CodexSessionSnapshot.class, boolean.class}, session.snapshot(), true);
    }

    private void renderSession() throws Exception {
        set(activity, "lastSessionRevision", Long.MIN_VALUE);
        call("render", new Class<?>[]{RuntimeSnapshot.class, CodexSessionSnapshot.class},
            new RuntimeSnapshot(1, RuntimePhase.READY, "", "", "", "/chat-ui-fixture"), session.snapshot());
    }

    private void publishSession() throws Exception {
        Method method = CodexSessionController.class.getDeclaredMethod("publishLocked");
        method.setAccessible(true);
        method.invoke(session);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T view(String name) throws Exception {
        Object views = field(activity, "views").get(activity);
        return (T) field(views, name).get(views);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object instance, String name) throws Exception {
        return (List<T>) field(instance, name).get(instance);
    }

    private static Field field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        field(instance, name).set(instance, value);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, args);
    }

    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static View findAction(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View action = findAction(group.getChildAt(i), description);
                if (action != null) {
                    return action;
                }
            }
        }
        return null;
    }

    private void screenshot(String name) throws IOException {
        String report = System.getProperty("agentcodi.chatUiReport", "");
        if (report.isEmpty()) {
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File image = new File(report, name + "-api" + android.os.Build.VERSION.SDK_INT + ".png");
        try (FileOutputStream output = new FileOutputStream(image)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        bitmap.recycle();
    }

    private static final class UnusedTransport implements CodexRpcTransport {
        @Override public String readLine(int maximumBytes) throws IOException {
            throw new IOException("UI fixture does not start an app server");
        }
        @Override public void writeLine(String line, int maximumBytes) throws IOException {
            throw new IOException("UI fixture does not start an app server");
        }
        @Override public void writeBytes(byte[] line, int length, int maximumBytes) throws IOException {
            Arrays.fill(line, (byte) 0);
            throw new IOException("UI fixture does not start an app server");
        }
        @Override public void close() {
        }
    }
}
