package de.agentcodi.app;

import android.app.Activity;
import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Looper;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.syntax.SyntaxToken;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkspaceCodeEditorUiTest {
    private ActivityController<Activity> lifecycle;
    private Activity activity;
    private WorkspaceCodeEditor surface;
    private UiTheme theme;

    @Before public void create() {
        lifecycle = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = lifecycle.get();
        theme = new UiTheme(activity);
        surface = new WorkspaceCodeEditor(activity, theme);
        activity.setContentView(surface);
        layout(390, 500);
    }

    @After public void close() {
        surface.dispose();
        lifecycle.destroy();
    }

    @Test public void colorsAllSupportedLanguagesAndLeavesOtherFilesPlain() throws Exception {
        String[] names = {"a.JS", "a.HTML", "a.CSS", "a.PY"};
        String[] text = {"const n = 2; // text", "<main title=\"x\">hi</main>", "a { color: #fff; }", "def hello():\n    print('x')"};
        String[] tokens = {"const", "main", "color", "def"};
        SyntaxToken.Kind[] kinds = {SyntaxToken.Kind.KEYWORD, SyntaxToken.Kind.TAG, SyntaxToken.Kind.ATTRIBUTE, SyntaxToken.Kind.KEYWORD};
        for (int index = 0; index < names.length; index++) {
            surface.setPath(names[index]);
            surface.input.setText(text[index]);
            surface.input.setSelection(2);
            layout(390, 500);
            awaitSyntax(surface);
            assertColor(tokens[index], highlighter().color(kinds[index]));
            assertEquals("coloring preserves the cursor", 2, surface.input.getSelectionStart());
            assertEquals(text[index], surface.input.getText().toString());
        }
        surface.setPath("unknown.txt");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertEquals(0, spans().length);
    }

    @Test public void decorationsDoNotTriggerEditorActionsOrReplaceEditable() throws Exception {
        WorkspaceEditorView editor = new WorkspaceEditorView(activity, theme);
        activity.setContentView(editor);
        final int[] edits = {0};
        editor.setActions(new WorkspaceEditorView.Actions() {
            @Override public void onTextChanged(String text) { edits[0]++; }
            @Override public void onSave() { }
            @Override public void onExport() { }
        });
        editor.bind(WorkspaceEditorDocument.newFile("new.py"));
        WorkspaceCodeEditor code = (WorkspaceCodeEditor) get(editor, "codeEditor");
        code.input.append("print('hello')");
        Editable buffer = code.input.getText();
        awaitSyntax(code);
        assertEquals("only the user's edit triggers an action", 1, edits[0]);
        assertSame(buffer, code.input.getText());
        assertTrue(buffer.getSpans(0, buffer.length(), EditorSyntaxHighlighter.ColorSpan.class).length > 0);
        editor.dispose();
    }

    @Test public void liveRetokenizationHandlesDeletionAndMultilineComments() throws Exception {
        setCode("code.js", "/* comment\nconst x = 2;\n*/\nreturn x;");
        awaitSyntax(surface);
        assertColor("const", highlighter().color(SyntaxToken.Kind.COMMENT));
        surface.input.getText().delete(0, 2);
        layout(390, 500);
        awaitSyntax(surface);
        assertColor("const", highlighter().color(SyntaxToken.Kind.KEYWORD));
        surface.input.setSelection(surface.input.length());
        surface.input.getText().replace(0, surface.input.length(), "x");
        awaitSyntax(surface);
        assertEquals("x", surface.input.getText().toString());
        assertEquals(0, spans().length);
    }

    @Test public void preservesImeCompositionSelectionAndOtherSpanOwners() throws Exception {
        setCode("code.py", "print('before')");
        awaitSyntax(surface);
        InputConnection connection = surface.input.onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        connection.setComposingRegion(7, 13);
        connection.setComposingText("draft", 1);
        Editable buffer = surface.input.getText();
        ForegroundColorSpan external = new ForegroundColorSpan(0xFFAA00AA);
        UnderlineSpan underline = new UnderlineSpan();
        buffer.setSpan(external, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        buffer.setSpan(underline, 7, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
        int cursor = surface.input.getSelectionStart();
        awaitSyntax(surface);
        assertSame(buffer, surface.input.getText());
        assertEquals(cursor, surface.input.getSelectionStart());
        assertEquals(0, buffer.getSpanStart(external));
        assertEquals(7, buffer.getSpanStart(underline));
        assertTrue((buffer.getSpanFlags(underline) & Spanned.SPAN_COMPOSING) != 0);
        connection.finishComposingText();
        assertEquals("print('draft')", surface.input.getText().toString());
    }

    @Test public void supersededResultsNeverRecolorAnotherDocument() throws Exception {
        setCode("large.js", repeat("const x = 1;\n", 15000));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        ExecutorService worker = (ExecutorService) get(highlighter(), "worker");
        surface.setPath("plain.txt");
        surface.input.setText("plain");
        if (worker != null) { worker.submit(() -> { }).get(10, TimeUnit.SECONDS); }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("plain", surface.input.getText().toString());
        assertEquals(0, spans().length);
    }

    @Test public void lineNumbersFollowActualBaselinesAndDoNotCountWraps() throws Exception {
        setCode("code.txt", "first " + repeat("wrapped ", 30) + "\n\nlast\n");
        layout(320, 500);
        assertTrue(surface.input.getLineCount() > 4);
        RecordingCanvas canvas = drawNumbers();
        assertEquals(java.util.Arrays.asList("1", "2", "3", "4"), canvas.labels);
        int origin = surface.input.getTotalPaddingTop() - surface.input.getScrollY();
        int[] starts = {0, surface.input.getText().toString().indexOf('\n') + 1,
            surface.input.getText().toString().indexOf("last"), surface.input.length()};
        for (int index = 0; index < starts.length; index++) {
            int visual = surface.input.getLayout().getLineForOffset(starts[index]);
            assertEquals(origin + surface.input.getLayout().getLineBaseline(visual), canvas.baselines.get(index), 0.01);
        }
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, numbers().getImportantForAccessibility());
        assertFalse(numbers().isFocusable());
    }

    @Test public void mixedLineEndingsRenderAsSeparateLinesWithoutChangingSource() throws Exception {
        String text = "first\r\nsecond\nthird\rlast\n";
        setCode("code.py", text);
        awaitSyntax(surface);
        assertEquals(text, surface.input.getText().toString());
        assertEquals(5, surface.input.getLayout().getLineCount());
        assertEquals(java.util.Arrays.asList("1", "2", "3", "4", "5"), drawNumbers().labels);
        assertEquals(text.length(), surface.input.getTransformationMethod().getTransformation(surface.input.getText(), surface.input).length());
        surface.input.setSelection(20);
        surface.input.getText().insert(20, "added\r");
        layout(390, 500);
        awaitSyntax(surface);
        assertEquals(text.substring(0, 20) + "added\r" + text.substring(20), surface.input.getText().toString());
        assertEquals(6, surface.input.getLayout().getLineCount());
    }

    @Test public void gutterResizesAtDigitBoundariesAndUpdatesAfterDeletion() throws Exception {
        setCode("code.txt", repeat("x\n", 8));
        int small = numbers().getWidth();
        surface.input.append("x\n");
        layout(390, 500);
        assertTrue(numbers().getWidth() > small);
        assertEquals("10", drawNumbers().labels.get(9));
        surface.input.getText().clear();
        layout(390, 500);
        assertEquals(small, numbers().getWidth());
        assertEquals(java.util.Collections.singletonList("1"), drawNumbers().labels);
    }

    @Test public void imeBackspaceRemovesDisplayedCrLfAsOneLineBreak() throws Exception {
        setCode("code.txt", "first\r\nsecond");
        surface.input.setSelection(7);
        InputConnection connection = surface.input.onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        assertTrue(connection.deleteSurroundingText(1, 0));
        layout(390, 500);
        assertEquals("firstsecond", surface.input.getText().toString());
        assertEquals(5, surface.input.getSelectionStart());
        assertEquals(java.util.Collections.singletonList("1"), drawNumbers().labels);
    }

    @Test public void imeForwardDeleteRemovesDisplayedCrLfAsOneLineBreak() throws Exception {
        setCode("code.txt", "first\r\nsecond");
        surface.input.setSelection(5);
        InputConnection connection = surface.input.onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        assertTrue(connection.deleteSurroundingText(0, 1));
        layout(390, 500);
        assertEquals("firstsecond", surface.input.getText().toString());
        assertEquals(5, surface.input.getSelectionStart());
        assertEquals(java.util.Collections.singletonList("1"), drawNumbers().labels);
    }

    @Test public void hardwareDeleteTreatsCrLfAsOneLineBreak() throws Exception {
        for (int key : new int[] {KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL}) {
            setCode("code.txt", "first\r\nsecond");
            surface.input.setSelection(key == KeyEvent.KEYCODE_DEL ? 7 : 5);
            surface.input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            surface.input.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
            assertEquals("firstsecond", surface.input.getText().toString());
            assertEquals(5, surface.input.getSelectionStart());
        }
    }

    @Test public void imeCodePointDeletionJoinsLinesAndPreservesAdjacentEmoji() throws Exception {
        for (boolean backwards : new boolean[] {true, false}) {
            setCode("code.txt", "🐱\r\n世界");
            surface.input.setSelection(backwards ? 4 : 2);
            InputConnection connection = surface.input.onCreateInputConnection(new EditorInfo());
            assertTrue(connection.deleteSurroundingTextInCodePoints(backwards ? 1 : 0, backwards ? 0 : 1));
            assertEquals("🐱世界", surface.input.getText().toString());
            assertEquals(2, surface.input.getSelectionStart());
        }
    }

    @Test public void copyingMixedLineEndingsUsesOriginalSource() throws Exception {
        String text = "first\r\nsecond\rthird\n";
        setCode("code.txt", text);
        surface.input.setSelection(0, text.length());
        assertTrue(surface.input.onTextContextMenuItem(android.R.id.copy));
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(text, clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
        assertEquals(text, surface.input.getText().toString());
    }

    @Test public void cuttingAndPastingRestoresOriginalLineEndingsWithoutColorSpans() throws Exception {
        String text = "const x = 1;\r\n// keep\rreturn x;\n";
        setCode("code.js", text);
        awaitSyntax(surface);
        surface.input.setSelection(0, text.length());
        assertTrue(surface.input.onTextContextMenuItem(android.R.id.cut));
        assertEquals("", surface.input.getText().toString());
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence copied = clipboard.getPrimaryClip().getItemAt(0).getText();
        assertEquals(text, copied.toString());
        assertFalse("syntax decorations do not enter the clipboard", copied instanceof Spanned);
        assertTrue(surface.input.onTextContextMenuItem(android.R.id.paste));
        assertEquals(text, surface.input.getText().toString());
        awaitSyntax(surface);
        assertColor("const", highlighter().color(SyntaxToken.Kind.KEYWORD));
    }

    @Test public void imeDeletionKeepsLoneLineEndingsAndComposingTextIntact() throws Exception {
        for (String ending : new String[] {"\n", "\r", "\r\n"}) {
            setCode("code.txt", "first" + ending + "second");
            InputConnection connection = surface.input.onCreateInputConnection(new EditorInfo());
            connection.setComposingRegion(5 + ending.length(), surface.input.length());
            surface.input.setSelection(surface.input.length());
            assertTrue(connection.deleteSurroundingText(1, 0));
            assertEquals("firstsecond", surface.input.getText().toString());
            assertEquals(5, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(surface.input.getText()));
            assertEquals(11, android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(surface.input.getText()));
            connection.finishComposingText();
        }
    }

    @Test public void largeFilesKeepSpansBoundedAndHighlightAfterScrolling() throws Exception {
        String text = repeat("const x = 1;\n", 9999) + "return 123;";
        setCode("code.js", text);
        layout(320, 220);
        awaitSyntax(surface);
        assertTrue("only the viewport has spans", spans().length < 1000);
        assertEquals(0, surface.input.getText().getSpans(text.length() - 11, text.length(), EditorSyntaxHighlighter.ColorSpan.class).length);
        int scroll = surface.input.getLayout().getLineTop(9990);
        surface.input.scrollTo(0, scroll);
        assertColor("return", highlighter().color(SyntaxToken.Kind.KEYWORD));
        assertTrue(spans().length < 1000);
        assertTrue(drawNumbers().labels.contains("10000"));
        assertFalse(drawNumbers().labels.contains("1"));
        assertEquals(text, surface.input.getText().toString());
    }

    @Test public void stoppingViewCancelsPendingWorkAndCanResume() throws Exception {
        setCode("code.js", "const x = 1");
        awaitSyntax(surface);
        ExecutorService worker = (ExecutorService) get(highlighter(), "worker");
        surface.dispose();
        assertTrue(worker.isShutdown());
        assertEquals(0, spans().length);
        highlighter().resume();
        awaitSyntax(surface);
        assertColor("const", highlighter().color(SyntaxToken.Kind.KEYWORD));
    }

    @Test public void lightColorsAndNumbersFitNarrowPhone() throws Exception { preview(); }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi")
    public void darkColorsAndNumbersFitNarrowPhone() throws Exception { preview(); }

    private void preview() throws Exception {
        setCode("index.html", "<!DOCTYPE html>\n<!-- Native editor -->\n<style>\n  .card { color: #fff; padding: 12px; }\n</style>\n<script>\n  const title = `Hello ${2 + 3}`;\n</script>\n<main class=\"card\">Grüße 世界 🐱</main>\n");
        layout(320, 300);
        awaitSyntax(surface);
        assertColor("const", highlighter().color(SyntaxToken.Kind.KEYWORD));
        assertTrue(surface.input.getWidth() > 200);
        assertTrue(numbers().getWidth() > 20);
        assertEquals(300, surface.input.getHeight());
        assertEquals(View.TEXT_DIRECTION_LTR, surface.input.getTextDirection());
        String report = System.getProperty("agentcodi.chatUiReport");
        if (report == null) { return; }
        Bitmap bitmap = Bitmap.createBitmap(320, 300, Bitmap.Config.ARGB_8888);
        surface.draw(new Canvas(bitmap));
        File file = new File(report, "editor-syntax-" + (theme.dark ? "dark-" : "light-") + android.os.Build.VERSION.SDK_INT + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { bitmap.recycle(); }
    }

    private void setCode(String path, String text) {
        surface.setPath(path);
        surface.input.setText(text);
        surface.input.setSelection(0);
        layout(390, 500);
    }

    private void layout(int width, int height) {
        ViewGroup.LayoutParams params = surface.getLayoutParams();
        params.width = width;
        params.height = height;
        surface.setLayoutParams(params);
        surface.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        surface.layout(0, 0, width, height);
    }

    static void awaitSyntax(WorkspaceCodeEditor code) throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        ExecutorService worker = (ExecutorService) get(get(code, "highlighter"), "worker");
        if (worker != null) { worker.submit(() -> { }).get(10, TimeUnit.SECONDS); }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private EditorSyntaxHighlighter highlighter() throws Exception { return (EditorSyntaxHighlighter) get(surface, "highlighter"); }
    private WorkspaceLineNumbers numbers() throws Exception { return (WorkspaceLineNumbers) get(surface, "lineNumbers"); }
    private EditorSyntaxHighlighter.ColorSpan[] spans() {
        return surface.input.getText().getSpans(0, surface.input.length(), EditorSyntaxHighlighter.ColorSpan.class);
    }

    private void assertColor(String fragment, int color) {
        int index = surface.input.getText().toString().indexOf(fragment);
        assertTrue(index >= 0);
        EditorSyntaxHighlighter.ColorSpan[] matches = surface.input.getText().getSpans(index, index + fragment.length(), EditorSyntaxHighlighter.ColorSpan.class);
        assertEquals("one color for " + fragment, 1, matches.length);
        assertEquals(color, matches[0].getForegroundColor());
    }

    private RecordingCanvas drawNumbers() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(numbers().getWidth(), numbers().getHeight(), Bitmap.Config.ARGB_8888);
        try {
            RecordingCanvas canvas = new RecordingCanvas(bitmap);
            numbers().draw(canvas);
            return canvas;
        } finally { bitmap.recycle(); }
    }

    private static final class RecordingCanvas extends Canvas {
        final List<String> labels = new ArrayList<>();
        final List<Float> baselines = new ArrayList<>();
        RecordingCanvas(Bitmap bitmap) { super(bitmap); }
        @Override public void drawText(String text, float x, float y, Paint paint) {
            labels.add(text);
            baselines.add(y);
        }
    }

    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder(text.length() * count);
        for (int index = 0; index < count; index++) { result.append(text); }
        return result.toString();
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
