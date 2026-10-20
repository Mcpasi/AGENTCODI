package de.agentcodi.app;

import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.EditText;

import de.agentcodi.browser.client.WorkspaceFileBrowser;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorLimits;
import de.agentcodi.editor.WorkspaceEditorStore;
import de.agentcodi.runtime.WorkspaceBrowserRepository;
import de.agentcodi.runtime.WorkspaceEditorRepository;
import de.agentcodi.storage.WorkspaceDirectoryCatalog;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceFileEdit;
import de.agentcodi.storage.WorkspaceLayout;

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
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkspaceEditorUiTest {
    private ActivityController<WorkspaceBrowserActivity> lifecycle;
    private WorkspaceBrowserActivity activity;
    private View root;
    private Path workspace;
    private ExecutorService operations;

    @Before public void create() throws Exception {
        lifecycle = Robolectric.buildActivity(WorkspaceBrowserActivity.class).create();
        activity = lifecycle.get();
        assertNotNull("browser UI initialized", get(activity, "editor"));
        workspace = WorkspaceLayout.create(activity.getFilesDir()).getWorkspace().toPath();
        installHostRepositories();
        invoke("loadDirectory", new Class<?>[] {String.class, int.class}, "", 0);
        drain();
        root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
        lifecycle.visible();
        layout(390, 844);
    }

    private void installHostRepositories() throws Exception {
        operations = (ExecutorService) get(activity, "operations");
        set(activity, "repository", browser());
        Constructor<WorkspaceEditorRepository> constructor = WorkspaceEditorRepository.class
            .getDeclaredConstructor(WorkspaceEditorStore.class);
        constructor.setAccessible(true);
        set(activity, "editorRepository", constructor.newInstance(new WorkspaceEditorStore() {
            @Override public void checkCreate(String path) throws IOException {
                try {
                    WorkspaceFileEdit.checkCreate(workspace.toFile(), path);
                } catch (FileAlreadyExistsException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS);
                }
            }
            @Override public void create(String path, byte[] contents) throws IOException {
                try {
                    WorkspaceFileEdit.create(workspace.toFile(), path, contents,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, WorkspaceFileEdit.secureNioCreator());
                } catch (FileAlreadyExistsException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS);
                }
            }
            @Override public byte[] read(String path) throws IOException {
                return WorkspaceFileEdit.read(workspace.toFile(), path,
                    WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, WorkspaceFileAccess.secureNioOpener());
            }
            @Override public void replace(String path, byte[] expected, byte[] replacement) throws IOException {
                try {
                    WorkspaceFileEdit.replace(workspace.toFile(), path, expected, replacement,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, WorkspaceFileAccess.secureNioOpener());
                } catch (WorkspaceFileEdit.FileChangedException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_CHANGED);
                }
            }
        }));
    }

    @After public void close() {
        if (lifecycle != null) { lifecycle.destroy(); }
    }

    @Test public void editsAndSavesTheWholeFileThenReloads() throws Exception {
        open("print('before')\n");
        assertEquals(View.VISIBLE, editorView().getVisibility());
        assertEquals(View.GONE, ((View) get(activity, "previewPanel")).getVisibility());
        assertEquals(View.GONE, ((View) get(activity, "fileActions")).getVisibility());
        assertFalse(saveButton().isEnabled());
        input().setText("print('Grüße 世界 🐱')\n");
        assertTrue(saveButton().isEnabled());
        assertFalse(((View) get(editorView(), "exportButton")).isEnabled());
        saveButton().performClick();
        assertFalse("editing is blocked during a save", input().isEnabled());
        drain();
        assertEquals("print('Grüße 世界 🐱')\n", diskText());
        assertFalse(saveButton().isEnabled());
        assertTrue(((View) get(editorView(), "exportButton")).isEnabled());
        activity.onBackPressed();
        drain();
        assertEquals(View.VISIBLE, ((View) get(activity, "directoryPanel")).getVisibility());
        loadFile();
        assertEquals(diskText(), input().getText().toString());
    }

    @Test public void cancelKeepsDraftAndDiscardReloadsSavedText() throws Exception {
        open("saved\n");
        input().setText("draft\n");
        activity.onBackPressed();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("draft\n", input().getText().toString());
        assertEquals("saved\n", diskText());
        ((Button) get(activity, "refreshButton")).performClick();
        dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("refresh also protects the draft", dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        drain();
        assertEquals("saved\n", input().getText().toString());
        assertFalse(saveButton().isEnabled());
    }

    @Test public void savesBeforeCompletingBackNavigation() throws Exception {
        open("saved");
        input().setText("draft");
        activity.onBackPressed();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain();
        // Completing the save queues the directory reload after the UI callback.
        drain();
        assertEquals("draft", diskText());
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertEquals(View.VISIBLE, ((View) get(activity, "directoryPanel")).getVisibility());
    }

    @Test public void conflictKeepsDraftAndStopsSaveAndLeave() throws Exception {
        open("saved");
        input().setText("draft");
        Files.write(workspace.resolve("code.txt"), "external".getBytes(StandardCharsets.UTF_8));
        activity.onBackPressed();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain();
        assertEquals("external", diskText());
        assertEquals("draft", input().getText().toString());
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertTrue(saveButton().isEnabled());
        assertEquals(activity.getString(R.string.editor_conflict),
            ((android.widget.TextView) get(editorView(), "statusView")).getText().toString());
    }

    @Test public void binaryFilesKeepTheirReadOnlyPreview() throws Exception {
        Files.write(workspace.resolve("code.txt"), new byte[] {0, 1, 2});
        loadFile();
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertEquals(View.VISIBLE, ((View) get(activity, "previewPanel")).getVisibility());
    }

    @Test public void oversizedTextStaysPagedAndReadOnly() throws Exception {
        byte[] bytes = new byte[WorkspaceEditorLimits.MAXIMUM_FILE_BYTES + 1];
        Arrays.fill(bytes, (byte) 'x');
        Files.write(workspace.resolve("code.txt"), bytes);
        loadFile();
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertEquals(activity.getString(R.string.editor_large_file_preview),
            ((android.widget.TextView) get(activity, "statusView")).getText().toString());
        assertTrue(((Button) get(activity, "nextPreviewPageButton")).isEnabled());
    }

    @Test public void preservesMixedLineEndingsAndSupportsControlS() throws Exception {
        open("\ufefffirst\r\nsecond\nthird\rlast");
        assertEquals("first\r\nsecond\nthird\rlast", input().getText().toString());
        input().append("\r\nadded");
        assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            input().getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        assertTrue((input().getImeOptions() & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        input().dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON));
        input().dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON));
        drain();
        assertEquals("\ufefffirst\r\nsecond\nthird\rlast\r\nadded", diskText());
    }

    @Test public void highlightedCodeSavesOnlySourceAndPreservesBomAndLineEndings() throws Exception {
        String source = "\ufeffconst text = 'Grüße 世界 🐱';\r\n// comment\nreturn text;\r";
        Path target = workspace.resolve("highlight.js");
        Files.write(target, source.getBytes(StandardCharsets.UTF_8));
        invoke("loadPreview", new Class<?>[] {String.class, int.class}, "highlight.js", 0);
        drain();
        layout(390, 844);
        WorkspaceCodeEditor code = (WorkspaceCodeEditor) get(editorView(), "codeEditor");
        WorkspaceCodeEditorUiTest.awaitSyntax(code);
        assertTrue(input().getText().getSpans(0, input().length(), EditorSyntaxHighlighter.ColorSpan.class).length > 0);
        assertFalse("colors do not make a saved document dirty", saveButton().isEnabled());
        input().append("const n = 2;\n");
        WorkspaceCodeEditorUiTest.awaitSyntax(code);
        saveButton().performClick();
        drain();
        assertEquals(source + "const n = 2;\n", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(saveButton().isEnabled());
    }

    @Test public void oversizedPasteIsRejectedAsAWhole() throws Exception {
        open("keep this");
        char[] chars = new char[WorkspaceEditorLimits.MAXIMUM_FILE_BYTES + 1];
        Arrays.fill(chars, 'x');
        input().getText().replace(0, input().length(), new String(chars));
        assertEquals("keep this", input().getText().toString());
        assertFalse(saveButton().isEnabled());
    }

    @Test public void imeLineJoinSavesSourceWithoutOrphanedCarriageReturn() throws Exception {
        open("\ufefffirst\r\nsecond\r\nthird\n");
        input().setSelection(7);
        InputConnection connection = input().onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        assertTrue(connection.deleteSurroundingText(1, 0));
        assertEquals("firstsecond\r\nthird\n", input().getText().toString());
        assertTrue(saveButton().isEnabled());
        saveButton().performClick();
        drain();
        assertEquals("\ufefffirstsecond\r\nthird\n", diskText());
        assertFalse(saveButton().isEnabled());
    }

    @Test public void orientationChangesKeepUnsavedTextAndCursor() throws Exception {
        open("saved");
        input().setText("draft while rotating");
        input().setSelection(7);
        Configuration configuration = new Configuration(activity.getResources().getConfiguration());
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        lifecycle.configurationChange(configuration);
        assertEquals("draft while rotating", input().getText().toString());
        assertEquals(7, input().getSelectionStart());
        assertTrue(saveButton().isEnabled());
    }

    @Test public void activityRecreationKeepsUnsavedDraftAndSelection() throws Exception {
        open("saved\r\nsource");
        input().setText("draft\r\nwhile recreating");
        input().setSelection(2, 9);
        lifecycle.recreate();
        activity = lifecycle.get();
        assertTrue("editor survives a configuration that recreates its activity",
            ((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertEquals("draft\r\nwhile recreating", input().getText().toString());
        assertEquals(2, input().getSelectionStart());
        assertEquals(9, input().getSelectionEnd());
        assertTrue(saveButton().isEnabled());
        assertEquals("saved\r\nsource", diskText());
        installHostRepositories();
        saveButton().performClick();
        drain();
        assertEquals("draft\r\nwhile recreating", diskText());
        assertFalse(saveButton().isEnabled());
    }

    @Test public void activityRecreationKeepsAnEmptyNewFileDraft() throws Exception {
        newFile("retained.py");
        lifecycle.recreate();
        activity = lifecycle.get();
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        assertEquals("", input().getText().toString());
        assertTrue(saveButton().isEnabled());
        assertFalse(Files.exists(workspace.resolve("retained.py")));
        installHostRepositories();
        saveButton().performClick();
        drain();
        assertEquals(0, Files.size(workspace.resolve("retained.py")));
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
    }

    @Test public void activityRecreationWaitsForThePendingSaveAndKeepsItsRevision() throws Exception {
        open("before");
        input().setText("saved during recreation");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        WorkspaceEditorController controller = (WorkspaceEditorController) get(activity, "editor");
        holdNextSave(entered, release);
        saveButton().performClick();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        ExecutorService previousOperations = operations;
        try {
            for (int recreation = 0; recreation < 2; recreation++) {
                lifecycle.recreate();
                activity = lifecycle.get();
                controller = (WorkspaceEditorController) get(activity, "editor");
                assertTrue(controller.isOpen());
                assertTrue(controller.isSaving());
                assertEquals("saved during recreation", input().getText().toString());
                assertFalse(input().isEnabled());
            }
            release.countDown();
            assertTrue(previousOperations.awaitTermination(10, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("saved during recreation", diskText());
            assertFalse(controller.isSaving());
            assertTrue(input().isEnabled());
            assertFalse(saveButton().isEnabled());
            installHostRepositories();
            input().append("\nnext revision");
            saveButton().performClick();
            drain();
            assertEquals("saved during recreation\nnext revision", diskText());
        } finally { release.countDown(); }
    }

    @Test public void activityRecreationDuringSaveAndBackStillCompletesNavigation() throws Exception {
        open("before");
        input().setText("saved before leaving");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdNextSave(entered, release);
        activity.onBackPressed();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        ExecutorService previousOperations = operations;
        try {
            lifecycle.recreate();
            activity = lifecycle.get();
            installHostRepositories();
            release.countDown();
            assertTrue(previousOperations.awaitTermination(10, TimeUnit.SECONDS));
            drain();
            assertEquals("saved before leaving", diskText());
            assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
            assertEquals(View.VISIBLE, ((View) get(activity, "directoryPanel")).getVisibility());
        } finally { release.countDown(); }
    }

    @Test public void activityRecreationDuringSaveConflictKeepsDraftAndFailure() throws Exception {
        open("before");
        input().setText("keep this draft");
        Files.write(workspace.resolve("code.txt"), "external revision".getBytes(StandardCharsets.UTF_8));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdNextSave(entered, release);
        saveButton().performClick();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        ExecutorService previousOperations = operations;
        try {
            lifecycle.recreate();
            activity = lifecycle.get();
            release.countDown();
            assertTrue(previousOperations.awaitTermination(10, TimeUnit.SECONDS));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("keep this draft", input().getText().toString());
            assertEquals("external revision", diskText());
            assertFalse(((WorkspaceEditorController) get(activity, "editor")).isSaving());
            assertTrue(input().isEnabled());
            assertTrue(saveButton().isEnabled());
            assertEquals(activity.getString(R.string.editor_conflict),
                ((android.widget.TextView) get(editorView(), "statusView")).getText().toString());
        } finally { release.countDown(); }
    }

    private void holdNextSave(final CountDownLatch entered, final CountDownLatch release) throws Exception {
        WorkspaceEditorController controller = (WorkspaceEditorController) get(activity, "editor");
        WorkspaceEditorRepository repository = (WorkspaceEditorRepository) get(activity, "editorRepository");
        set(controller, "saver", (WorkspaceEditorController.Saver) (document, text) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) { throw new IOException("Save did not resume"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Pending save was interrupted by recreation", interrupted);
            }
            return repository.save(document, text);
        });
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi")
    public void narrowPhoneKeepsSaveAboveTheKeyboardAndWrapsLongLines() throws Exception {
        open("// " + new String(new char[120]).replace('\0', 'x') + "\nreturn true;\n");
        input().append("// Änderung\n");
        layout(320, 370);
        assertTrue("long lines wrap within the phone width", input().getLineCount() > 3);
        assertTrue("usable editing area with keyboard", input().getHeight() >= 200);
        assertTrue("save target is at least 48 dp", saveButton().getHeight() >= 48);
        int[] button = new int[2];
        int[] bounds = new int[2];
        saveButton().getLocationOnScreen(button);
        root.getLocationOnScreen(bounds);
        assertTrue(button[0] >= bounds[0]);
        assertTrue(button[0] + saveButton().getWidth() <= bounds[0] + root.getWidth());
        assertTrue(button[1] + saveButton().getHeight() <= bounds[1] + root.getHeight());
        screenshot("editor-phone-keyboard-" + android.os.Build.VERSION.SDK_INT + ".png");
        layout(320, 640);
        screenshot("editor-phone-" + android.os.Build.VERSION.SDK_INT + ".png");
    }

    @Test public void systemInsetsKeepEditorControlsAwayFromSystemBars() throws Exception {
        open("saved");
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
            .setSystemWindowInsets(Insets.of(5, 24, 7, 18)).build());
        assertEquals(19, root.getPaddingLeft());
        assertEquals(42, root.getPaddingTop());
        assertEquals(21, root.getPaddingRight());
        assertEquals(30, root.getPaddingBottom());
        root.dispatchApplyWindowInsets(new WindowInsets.Builder()
            .setSystemWindowInsets(Insets.of(5, 24, 7, 18)).build());
        assertEquals("insets do not accumulate", 42, root.getPaddingTop());
    }

    @Test public void newFileOpensDraftInSelectedFolderAndSavesThenUpdates() throws Exception {
        Files.createDirectories(workspace.resolve("project"));
        invoke("loadDirectory", new Class<?>[] {String.class, int.class}, "project", 0);
        drain();
        newFile("新文件 🚀.py");
        Path target = workspace.resolve("project/新文件 🚀.py");
        assertFalse(Files.exists(target));
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        assertTrue("new empty draft can be saved", saveButton().isEnabled());
        assertFalse(((View) get(editorView(), "exportButton")).isEnabled());
        input().setText("print('Grüße 世界')\n");
        saveButton().performClick();
        drain();
        assertEquals("print('Grüße 世界')\n", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        input().setText("second revision\n");
        saveButton().performClick();
        drain();
        assertEquals("second revision\n", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        activity.onBackPressed();
        drain();
        de.agentcodi.browser.WorkspaceBrowserPage page =
            (de.agentcodi.browser.WorkspaceBrowserPage) get(activity, "currentDirectoryPage");
        assertTrue("new file appears in its folder", page.getEntries().stream()
            .anyMatch(entry -> entry.getDisplayName().equals("新文件 🚀.py")));
    }

    @Test public void firstSaveCanCreateAnEmptyFile() throws Exception {
        newFile("empty.txt");
        saveButton().performClick();
        drain();
        assertEquals(0, Files.size(workspace.resolve("empty.txt")));
        assertFalse(saveButton().isEnabled());
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
    }

    @Test public void cancelingNameDialogAndPendingPreflightCreatesNothing() throws Exception {
        AlertDialog dialog = openNewFileDialog();
        findEditText(dialog.getWindow().getDecorView()).setText("canceled.txt");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        drain();
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertFalse(Files.exists(workspace.resolve("canceled.txt")));
        dialog = openNewFileDialog();
        findEditText(dialog.getWindow().getDecorView()).setText("pending.txt");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        dialog.dismiss();
        drain();
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
        assertFalse(Files.exists(workspace.resolve("pending.txt")));
    }

    @Test public void emptyNewDraftBackCanBeCanceledOrDiscardedWithoutCreatingFile() throws Exception {
        newFile("discard.txt");
        activity.onBackPressed();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("empty new drafts also protect unsaved work", dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        assertTrue(saveButton().isEnabled());
        activity.onBackPressed();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        drain();
        assertFalse(Files.exists(workspace.resolve("discard.txt")));
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isOpen());
    }

    @Test public void refreshNewDraftDiscardsToDirectoryOrSavesBeforeReloading() throws Exception {
        newFile("refresh.txt");
        input().setText("discard me");
        ((Button) get(activity, "refreshButton")).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        drain();
        assertFalse(Files.exists(workspace.resolve("refresh.txt")));
        assertEquals(View.VISIBLE, ((View) get(activity, "directoryPanel")).getVisibility());
        newFile("refresh.txt");
        input().setText("save me");
        ((Button) get(activity, "refreshButton")).performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain();
        assertEquals("save me", new String(Files.readAllBytes(workspace.resolve("refresh.txt")), StandardCharsets.UTF_8));
        assertFalse(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        assertEquals("save me", input().getText().toString());
    }

    @Test public void invalidAndExistingNamesKeepDialogOpenForCorrection() throws Exception {
        Files.write(workspace.resolve("existing.txt"), "original".getBytes(StandardCharsets.UTF_8));
        AlertDialog dialog = openNewFileDialog();
        EditText filename = findEditText(dialog.getWindow().getDecorView());
        filename.setText("../escape.txt");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain();
        assertTrue(dialog.isShowing());
        assertEquals(activity.getString(R.string.editor_invalid_name), filename.getError().toString());
        filename.setText("existing.txt");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain();
        assertTrue(dialog.isShowing());
        assertEquals(activity.getString(R.string.editor_file_exists), filename.getError().toString());
        assertEquals("original", new String(Files.readAllBytes(workspace.resolve("existing.txt")), StandardCharsets.UTF_8));
        filename.setText("valid.txt");
        filename.onEditorAction(EditorInfo.IME_ACTION_DONE);
        drain();
        assertFalse(dialog.isShowing());
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        assertFalse(Files.exists(workspace.resolve("valid.txt")));
    }

    @Test public void lateNameCollisionNeverOverwritesFileOrLosesDraft() throws Exception {
        newFile("race.txt");
        input().setText("my draft");
        Path target = workspace.resolve("race.txt");
        Files.write(target, "external".getBytes(StandardCharsets.UTF_8));
        saveButton().performClick();
        drain();
        assertEquals("external", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertEquals("my draft", input().getText().toString());
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
        assertTrue(saveButton().isEnabled());
        assertEquals(activity.getString(R.string.editor_file_exists),
            ((android.widget.TextView) get(editorView(), "statusView")).getText().toString());
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi")
    public void newFileControlsAndNameInputFitNarrowPhones() throws Exception {
        layout(320, 640);
        Button create = (Button) get(activity, "newFileButton");
        assertTrue(create.getWidth() <= root.getWidth());
        assertTrue(create.getHeight() >= 48);
        screenshot("new-file-phone-" + android.os.Build.VERSION.SDK_INT + ".png");
        AlertDialog dialog = openNewFileDialog();
        EditText filename = findEditText(dialog.getWindow().getDecorView());
        assertTrue(filename.getWidth() > 0);
        assertTrue((filename.getImeOptions() & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
        filename.setText("new-file.py");
        filename.onEditorAction(EditorInfo.IME_ACTION_DONE);
        drain();
        layout(320, 370);
        assertTrue(input().getHeight() >= 200);
        assertTrue(saveButton().isEnabled());
        screenshot("new-file-editor-keyboard-" + android.os.Build.VERSION.SDK_INT + ".png");
    }

    private WorkspaceBrowserRepository browser() throws Exception {
        Constructor<WorkspaceBrowserRepository> constructor = WorkspaceBrowserRepository.class
            .getDeclaredConstructor(Context.class, File.class, WorkspaceFileBrowser.class);
        constructor.setAccessible(true);
        return constructor.newInstance(activity, workspace.toFile(),
            new WorkspaceFileBrowser(workspace.toFile(), WorkspaceDirectoryCatalog.secureNioReader(),
                WorkspaceFileAccess.secureNioOpener()));
    }

    private AlertDialog openNewFileDialog() throws Exception {
        ((Button) get(activity, "newFileButton")).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = (AlertDialog) get(activity, "newFileDialog");
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private void newFile(String name) throws Exception {
        AlertDialog dialog = openNewFileDialog();
        findEditText(dialog.getWindow().getDecorView()).setText(name);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drain();
        assertFalse(dialog.isShowing());
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isNewFile());
    }

    private static EditText findEditText(View view) {
        if (view instanceof EditText) { return (EditText) view; }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int index = 0; index < parent.getChildCount(); index++) {
                EditText input = findEditText(parent.getChildAt(index));
                if (input != null) { return input; }
            }
        }
        return null;
    }

    private void open(String text) throws Exception {
        Files.write(workspace.resolve("code.txt"), text.getBytes(StandardCharsets.UTF_8));
        loadFile();
        assertTrue(((WorkspaceEditorController) get(activity, "editor")).isOpen());
    }

    private void loadFile() throws Exception {
        invoke("loadPreview", new Class<?>[] {String.class, int.class}, "code.txt", 0);
        drain();
        layout(390, 844);
    }

    private String diskText() throws IOException {
        return new String(Files.readAllBytes(workspace.resolve("code.txt")), StandardCharsets.UTF_8);
    }

    private WorkspaceEditorView editorView() throws Exception {
        return (WorkspaceEditorView) get(activity, "editorView");
    }
    private EditText input() throws Exception { return (EditText) get(editorView(), "input"); }
    private Button saveButton() throws Exception { return (Button) get(editorView(), "saveButton"); }

    private void drain() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        operations.submit(new Runnable() { @Override public void run() { } }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        operations.submit(new Runnable() { @Override public void run() { } }).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void layout(int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private void screenshot(String name) throws IOException {
        String report = System.getProperty("agentcodi.chatUiReport");
        if (report == null) { return; }
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream output = new FileOutputStream(new File(report, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
    }

    private void invoke(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = WorkspaceBrowserActivity.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        method.invoke(activity, arguments);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
