package de.agentcodi.app;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import de.agentcodi.connectors.ConnectorProvider;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexReasoningOption;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.CodexSessionSnapshot;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ChatConversationUiTest {
    private ActivityController<MainActivity> lifecycle;
    private MainActivity activity;
    private LinearLayout root;
    private CodexSessionController session;

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
        root = (LinearLayout) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        call("showConversationPage", new Class<?>[]{CodexSessionSnapshot.class}, session.snapshot());
        renderSession();
    }

    @After
    public void close() throws Exception {
        lifecycle.destroy();
        setStatic(AgentRuntimeService.class, "sessionController", null);
        session.close();
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
        List<ImportedWorkspaceFile> imports = list(activity, "pendingImports");
        for (int i = 0; i < 16; i++) {
            imports.add(ImportedWorkspaceFile.create("imports/file-" + i + ".txt",
                "Long filename " + i + ".txt", "text/plain", 42,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        }
        list(activity, "pendingConnectors").add(new ConnectorSelection(ConnectorProvider.GMAIL, "gmail", "Gmail"));
        list(activity, "pendingConnectors").add(new ConnectorSelection(ConnectorProvider.GITHUB, "github", "GitHub"));
        set(activity, "pendingImportsThreadId", "thread");
        set(activity, "pendingConnectorsThreadId", "thread");
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
        assertTrue(list(activity, "pendingImports").isEmpty());
        view("clearConnectorsButton").performClick();
        assertTrue(list(activity, "pendingConnectors").isEmpty());
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
        return (T) field(activity, name).get(activity);
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
