package de.agentcodi.tests;

import de.agentcodi.editor.WorkspaceEditorDocument;
import de.agentcodi.editor.WorkspaceEditorException;
import de.agentcodi.editor.WorkspaceEditorLimits;
import de.agentcodi.editor.WorkspaceEditorSession;
import de.agentcodi.editor.WorkspaceEditorStore;
import de.agentcodi.editor.client.WorkspaceFileEditor;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceFileEdit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

public final class WorkspaceFileEditorTest {
    private WorkspaceFileEditorTest() {
    }

    public static int run() throws Exception {
        preservesUnicodeBomAndLineEndings();
        savesEmptyFilesAndSuccessiveRevisions();
        detectsExternalEditsWithRestoredTimestamps();
        rejectsBinaryAndMalformedUtf8();
        enforcesByteLimitsWithoutTruncating();
        preservesDraftAfterFailureOrNewerEdits();
        rejectsUnsafePathsAndLinks();
        replacesAtomicallyAndPreservesExecutablePermissions();
        failedSaveKeepsOriginalAndCleansTemporaryFiles();
        rejectsMutationBeforeReplacement();
        newDraftWritesOnlyOnSaveAndCanBeEditedAgain();
        newEmptyFileIsUnsavedUntilFirstSave();
        rejectsInvalidNamesBeforeAccessingStorage();
        refusesExistingAndLaterAppearingDestinations();
        newFilesCannotEscapeThroughUnsafePathsOrLinks();
        failedNewFileSaveKeepsDraftAndDoesNotCreateAFile();
        oversizedNewFileDoesNotWritePartialContents();
        return 17;
    }

    private static void preservesUnicodeBomAndLineEndings() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String source = "\ufeffGrüße 世界 🐱\r\n\treturn 1;\nlast\rline";
            Files.write(fixture.file, utf8(source));
            WorkspaceEditorDocument opened = fixture.editor.open("code.txt");
            TestSupport.assertEquals(source.substring(1), opened.getText(), "BOM is not an editable character");
            fixture.editor.save(opened, opened.getText());
            TestSupport.assertTrue(Arrays.equals(utf8(source), Files.readAllBytes(fixture.file)),
                "unchanged Unicode, BOM and mixed newlines survive saving byte for byte");
            WorkspaceEditorDocument saved = fixture.editor.save(opened, opened.getText() + "\r\n終わり");
            TestSupport.assertEquals(source + "\r\n終わり", new String(Files.readAllBytes(fixture.file),
                StandardCharsets.UTF_8), "edited text keeps BOM and original line endings");
            byte[] leaked = saved.getSavedBytes();
            leaked[0] = 0;
            TestSupport.assertEquals(Byte.valueOf((byte) 0xef), Byte.valueOf(saved.getSavedBytes()[0]),
                "saved revision bytes cannot be mutated by a caller");
        }
    }

    private static void savesEmptyFilesAndSuccessiveRevisions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, new byte[0]);
            WorkspaceEditorDocument saved = fixture.editor.save(fixture.editor.open("code.txt"), "first\n");
            saved = fixture.editor.save(saved, "second\n");
            fixture.editor.save(saved, "");
            TestSupport.assertEquals(Long.valueOf(0), Long.valueOf(Files.size(fixture.file)),
                "deleting all text writes a valid empty file");
        }
    }

    private static void detectsExternalEditsWithRestoredTimestamps() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, utf8("first"));
            FileTime modified = Files.getLastModifiedTime(fixture.file);
            WorkspaceEditorDocument opened = fixture.editor.open("code.txt");
            Files.write(fixture.file, utf8("other"));
            Files.setLastModifiedTime(fixture.file, modified);
            expectReason(WorkspaceEditorException.Reason.FILE_CHANGED,
                () -> fixture.editor.save(opened, "draft"));
            TestSupport.assertEquals("other", new String(Files.readAllBytes(fixture.file),
                StandardCharsets.UTF_8), "external edit is not overwritten despite identical size and mtime");
        }
    }

    private static void rejectsBinaryAndMalformedUtf8() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (byte[] bytes : new byte[][] {
                new byte[] {'a', 0, 'b'}, new byte[] {(byte) 0xc3, 0x28},
                new byte[] {(byte) 0xff, (byte) 0xfe, 'x', 0}, new byte[] {'a', 1}
            }) {
                Files.write(fixture.file, bytes);
                expectReason(WorkspaceEditorException.Reason.UNSUPPORTED_TEXT,
                    () -> fixture.editor.open("code.txt"));
            }
            Files.write(fixture.file, utf8("valid"));
            WorkspaceEditorDocument opened = fixture.editor.open("code.txt");
            expectReason(WorkspaceEditorException.Reason.UNSUPPORTED_TEXT,
                () -> fixture.editor.save(opened, "broken\ud800"));
            TestSupport.assertEquals("valid", new String(Files.readAllBytes(fixture.file),
                StandardCharsets.UTF_8), "invalid surrogate never corrupts the stored text");
        }
    }

    private static void enforcesByteLimitsWithoutTruncating() throws Exception {
        byte[] original = new byte[WorkspaceEditorLimits.MAXIMUM_FILE_BYTES];
        Arrays.fill(original, (byte) 'a');
        RecordingStore store = new RecordingStore(original);
        WorkspaceFileEditor editor = new WorkspaceFileEditor(store);
        WorkspaceEditorDocument opened = editor.open("limit.txt");
        editor.save(opened, opened.getText());
        TestSupport.assertTrue(Arrays.equals(original, store.bytes), "exact 1 MiB limit remains intact");
        int writes = store.writes;
        expectReason(WorkspaceEditorException.Reason.TOO_LARGE,
            () -> editor.save(opened, opened.getText() + "b"));
        char[] multibyte = new char[WorkspaceEditorLimits.MAXIMUM_FILE_BYTES / 2];
        Arrays.fill(multibyte, '界');
        expectReason(WorkspaceEditorException.Reason.TOO_LARGE,
            () -> editor.save(opened, new String(multibyte)));
        TestSupport.assertEquals(Integer.valueOf(writes), Integer.valueOf(store.writes),
            "oversized UTF-8 is rejected before storage sees a write");
        store.bytes = new byte[WorkspaceEditorLimits.MAXIMUM_FILE_BYTES + 1];
        expectReason(WorkspaceEditorException.Reason.TOO_LARGE, () -> editor.open("limit.txt"));
    }

    private static void preservesDraftAfterFailureOrNewerEdits() throws Exception {
        RecordingStore store = new RecordingStore(utf8("saved"));
        WorkspaceFileEditor editor = new WorkspaceFileEditor(store);
        WorkspaceEditorSession session = new WorkspaceEditorSession(editor.open("code.txt"));
        session.setText("draft");
        store.fail = true;
        TestSupport.expectThrows(IOException.class,
            () -> editor.save(session.getDocument(), session.getText()), "write failure propagates");
        TestSupport.assertTrue(session.isDirty(), "failed save keeps the dirty state");
        TestSupport.assertEquals("draft", session.getText(), "failed save keeps the draft");
        store.fail = false;
        WorkspaceEditorDocument saved = editor.save(session.getDocument(), session.getText());
        session.setText("newer draft");
        session.acceptSaved(saved);
        TestSupport.assertTrue(session.isDirty(), "late save cannot mark a newer edit as saved");
        TestSupport.assertEquals("newer draft", session.getText(), "late save retains newer text");
        session.setText(saved.getText());
        TestSupport.assertFalse(session.isDirty(), "undoing edits returns to the saved state");
    }

    private static void rejectsUnsafePathsAndLinks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, utf8("inside"));
            Path outside = fixture.root.resolveSibling(fixture.root.getFileName() + "-outside.txt");
            try {
                Files.write(outside, utf8("outside"));
                for (String path : new String[] {"../" + outside.getFileName(),
                    fixture.file.toString(), "./code.txt", "nested/../code.txt", "code.txt/", "a//b"}) {
                    TestSupport.expectThrows(IOException.class, () -> fixture.editor.open(path),
                        "unsafe editor path: " + path);
                }
                Files.createSymbolicLink(fixture.root.resolve("link.txt"), outside);
                Files.createSymbolicLink(fixture.root.resolve("linked-parent"), outside.getParent());
                Files.createLink(fixture.root.resolve("hard.txt"), outside);
                for (String path : new String[] {"link.txt", "hard.txt",
                    "linked-parent/" + outside.getFileName()}) {
                    TestSupport.expectThrows(IOException.class, () -> fixture.editor.open(path),
                        "linked editor source: " + path);
                    TestSupport.expectThrows(IOException.class,
                        () -> fixture.store.replace(path, utf8("outside"), utf8("replacement")),
                        "linked edit destination: " + path);
                }
                TestSupport.assertEquals("outside", new String(Files.readAllBytes(outside),
                    StandardCharsets.UTF_8), "linked files outside the workspace stay intact");
            } finally {
                Files.deleteIfExists(outside);
            }
        }
    }

    private static void replacesAtomicallyAndPreservesExecutablePermissions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, utf8("original"));
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwxr-x---");
            Files.setPosixFilePermissions(fixture.file, permissions);
            try (InputStream original = Files.newInputStream(fixture.file)) {
                fixture.editor.save(fixture.editor.open("code.txt"), "replacement");
                byte[] bytes = new byte[8];
                TestSupport.assertEquals(Integer.valueOf(8), Integer.valueOf(original.read(bytes)),
                    "reader retains the complete original inode");
                TestSupport.assertEquals("original", new String(bytes, StandardCharsets.UTF_8),
                    "save uses replacement, not in-place truncation");
            }
            TestSupport.assertEquals(permissions, Files.getPosixFilePermissions(fixture.file),
                "script executable permissions survive saving");
            assertNoTemporaryFiles(fixture.root);
        }
    }

    private static void failedSaveKeepsOriginalAndCleansTemporaryFiles() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, utf8("original"));
            WorkspaceEditorDocument opened = fixture.editor.open("code.txt");
            // A missing workspace fails for ordinary users and root APK builders alike.
            Path moved = fixture.root.resolveSibling(fixture.root.getFileName() + "-moved");
            Files.move(fixture.root, moved);
            try {
                TestSupport.expectThrows(IOException.class,
                    () -> fixture.editor.save(opened, "replacement"), "unavailable workspace rejects saving");
            } finally {
                Files.move(moved, fixture.root);
            }
            TestSupport.assertEquals("original", new String(Files.readAllBytes(fixture.file),
                StandardCharsets.UTF_8), "failed save does not truncate the original");
            assertNoTemporaryFiles(fixture.root);
        }
    }

    private static void rejectsMutationBeforeReplacement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, utf8("original"));
            try (WorkspaceFileAccess.Source source = WorkspaceFileAccess.secureNioOpener().open(
                fixture.root.toFile(), "code.txt", WorkspaceEditorLimits.MAXIMUM_FILE_BYTES
            )) {
                Files.write(fixture.file, utf8("external"));
                TestSupport.expectThrows(IOException.class, () -> source.replace(utf8("draft")),
                    "source rechecks its snapshot at commit time");
            }
            TestSupport.assertEquals("external", new String(Files.readAllBytes(fixture.file),
                StandardCharsets.UTF_8), "concurrent change survives");
            assertNoTemporaryFiles(fixture.root);
        }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static void newDraftWritesOnlyOnSaveAndCanBeEditedAgain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.createDirectory(fixture.root.resolve("project"));
            String name = "新文件 🚀.py";
            WorkspaceEditorDocument draft = fixture.editor.newDocument("project", name);
            Path target = fixture.root.resolve("project").resolve(name);
            TestSupport.assertTrue(draft.isNewFile(), "new file begins as a draft");
            TestSupport.assertFalse(Files.exists(target), "opening a draft does not touch the filesystem");
            WorkspaceEditorDocument saved = fixture.editor.save(draft, "print('Grüße 世界')\n");
            TestSupport.assertFalse(saved.isNewFile(), "first save transitions to an existing revision");
            TestSupport.assertEquals("project/" + name, saved.getRelativePath(), "selected folder and Unicode filename");
            TestSupport.assertEquals("print('Grüße 世界')\n", new String(Files.readAllBytes(target),
                StandardCharsets.UTF_8), "complete UTF-8 text is saved");
            TestSupport.assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(target), "new files are private and non-executable");
            fixture.editor.save(saved, "second revision\n");
            TestSupport.assertEquals("second revision\n", fixture.editor.open(saved.getRelativePath()).getText(),
                "subsequent saves use the normal replacement path");
        }
    }

    private static void newEmptyFileIsUnsavedUntilFirstSave() throws Exception {
        try (Fixture fixture = new Fixture()) {
            WorkspaceEditorSession session = new WorkspaceEditorSession(fixture.editor.newDocument("", "empty.txt"));
            TestSupport.assertTrue(session.isDirty(), "an empty new file still needs saving");
            session.setText("temporary");
            session.setText("");
            TestSupport.assertTrue(session.isDirty(), "undoing all text does not discard the new file");
            session.acceptSaved(fixture.editor.save(session.getDocument(), session.getText()));
            TestSupport.assertFalse(session.isDirty(), "a successfully saved empty file is clean");
            TestSupport.assertEquals(Long.valueOf(0), Long.valueOf(Files.size(fixture.root.resolve("empty.txt"))),
                "empty new file is created");
        }
    }

    private static void rejectsInvalidNamesBeforeAccessingStorage() throws Exception {
        RecordingStore store = new RecordingStore(null);
        WorkspaceFileEditor editor = new WorkspaceFileEditor(store);
        String wide = new String(new char[86]).replace('\0', '界');
        String longName = new String(new char[256]).replace('\0', 'a');
        for (String name : new String[] {null, "", " ", ".", "..", "../escape", "a/b", "a\\b",
            "a:b", "a\nb", "a\u007fb", "a\ud800", " leading", "trailing ", wide, longName}) {
            expectReason(WorkspaceEditorException.Reason.INVALID_NAME, () -> editor.newDocument("", name));
        }
        for (String directory : new String[] {"..", "/absolute", "a/../b", "a//b", "a/"}) {
            expectReason(WorkspaceEditorException.Reason.INVALID_NAME, () -> editor.newDocument(directory, "safe.txt"));
        }
        TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(store.writes),
            "invalid names cannot write to storage");
        TestSupport.assertEquals(".gitignore", editor.newDocument("", ".gitignore").getRelativePath(),
            "ordinary hidden filenames are allowed");
        String limit = new String(new char[255]).replace('\0', 'a');
        TestSupport.assertEquals(limit, editor.newDocument("", limit).getRelativePath(),
            "exact filesystem byte limit is accepted");
    }

    private static void refusesExistingAndLaterAppearingDestinations() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.write(fixture.file, utf8("existing"));
            expectReason(WorkspaceEditorException.Reason.FILE_EXISTS,
                () -> fixture.editor.newDocument("", "code.txt"));
            Files.createDirectory(fixture.root.resolve("folder"));
            expectReason(WorkspaceEditorException.Reason.FILE_EXISTS,
                () -> fixture.editor.newDocument("", "folder"));
            WorkspaceEditorSession session = new WorkspaceEditorSession(fixture.editor.newDocument("", "later.txt"));
            session.setText("draft");
            Path target = fixture.root.resolve("later.txt");
            Files.write(target, new byte[0]);
            expectReason(WorkspaceEditorException.Reason.FILE_EXISTS,
                () -> fixture.editor.save(session.getDocument(), session.getText()));
            TestSupport.assertEquals(Long.valueOf(0), Long.valueOf(Files.size(target)),
                "a file appearing after preflight is not overwritten, even if empty");
            TestSupport.assertTrue(session.isDirty() && session.getDocument().isNewFile(),
                "collision keeps the new draft unsaved");
            TestSupport.assertEquals("draft", session.getText(), "collision keeps draft text");
            assertNoTemporaryFiles(fixture.root);
        }
    }

    private static void newFilesCannotEscapeThroughUnsafePathsOrLinks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path outside = Files.createTempDirectory("agentcodi-create-outside-");
            try {
                Files.createSymbolicLink(fixture.root.resolve("linked"), outside);
                Files.createSymbolicLink(fixture.root.resolve("dangling.txt"), outside.resolve("missing.txt"));
                TestSupport.expectThrows(IOException.class,
                    () -> fixture.editor.newDocument("linked", "escape.txt"), "preflight rejects linked parents");
                expectReason(WorkspaceEditorException.Reason.FILE_EXISTS,
                    () -> fixture.editor.newDocument("", "dangling.txt"));
                for (String path : new String[] {"../escape.txt", "/absolute.txt", "./new.txt",
                    "nested/../new.txt", "linked/escape.txt", "missing/new.txt", "dangling.txt"}) {
                    TestSupport.expectThrows(IOException.class, () -> fixture.store.create(path, utf8("draft")),
                        "creation refuses unsafe destination: " + path);
                }
                TestSupport.assertFalse(Files.exists(outside.resolve("escape.txt")), "no file is created outside the workspace");
                TestSupport.assertFalse(Files.exists(outside.resolve("missing.txt")), "dangling link is not followed");
                TestSupport.assertFalse(Files.exists(fixture.root.resolve("missing")), "missing folders are not implicitly created");
            } finally { Files.deleteIfExists(outside); }
        }
    }

    private static void failedNewFileSaveKeepsDraftAndDoesNotCreateAFile() throws Exception {
        try (Fixture fixture = new Fixture()) {
            WorkspaceEditorSession session = new WorkspaceEditorSession(fixture.editor.newDocument("", "new.txt"));
            session.setText("keep me");
            Path moved = fixture.root.resolveSibling(fixture.root.getFileName() + "-moved");
            Files.move(fixture.root, moved);
            try {
                TestSupport.expectThrows(IOException.class,
                    () -> fixture.editor.save(session.getDocument(), session.getText()), "failed first save propagates");
            } finally { Files.move(moved, fixture.root); }
            TestSupport.assertFalse(Files.exists(fixture.root.resolve("new.txt")), "failed first save leaves no file");
            TestSupport.assertTrue(session.isDirty() && session.getDocument().isNewFile(), "new draft remains unsaved");
            TestSupport.assertEquals("keep me", session.getText(), "failed first save retains text");
            assertNoTemporaryFiles(fixture.root);
        }
    }

    private static void oversizedNewFileDoesNotWritePartialContents() throws Exception {
        try (Fixture fixture = new Fixture()) {
            WorkspaceEditorDocument draft = fixture.editor.newDocument("", "large.txt");
            char[] chars = new char[WorkspaceEditorLimits.MAXIMUM_FILE_BYTES / 2];
            Arrays.fill(chars, '界');
            expectReason(WorkspaceEditorException.Reason.TOO_LARGE, () -> fixture.editor.save(draft, new String(chars)));
            TestSupport.assertFalse(Files.exists(fixture.root.resolve("large.txt")), "oversized UTF-8 creates no partial file");
        }
    }

    private static void assertNoTemporaryFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            TestSupport.assertFalse(files.anyMatch(path -> path.getFileName().toString()
                .startsWith(".agentcodi-edit-")), "no temporary edit file remains");
        }
    }

    private static void expectReason(WorkspaceEditorException.Reason reason,
        TestSupport.ThrowingRunnable action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected editor failure: " + reason);
        } catch (WorkspaceEditorException error) {
            TestSupport.assertEquals(reason, error.getReason(), "typed editor failure");
        }
    }

    private static final class RecordingStore implements WorkspaceEditorStore {
        byte[] bytes;
        boolean fail;
        int writes;

        RecordingStore(byte[] bytes) { this.bytes = bytes; }
        @Override public byte[] read(String path) { return bytes.clone(); }
        @Override public void checkCreate(String path) throws IOException {
            if (bytes != null) { throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS); }
        }
        @Override public void create(String path, byte[] contents) throws IOException {
            if (fail) { throw new IOException("fixture creation failure"); }
            checkCreate(path);
            bytes = contents.clone();
            writes++;
        }
        @Override public void replace(String path, byte[] expected, byte[] replacement) throws IOException {
            if (fail) { throw new IOException("fixture write failure"); }
            bytes = replacement.clone();
            writes++;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path root = Files.createTempDirectory("agentcodi-editor-").toRealPath();
        final Path file = root.resolve("code.txt");
        final WorkspaceEditorStore store = new WorkspaceEditorStore() {
            @Override public void checkCreate(String path) throws IOException {
                try {
                    WorkspaceFileEdit.checkCreate(root.toFile(), path);
                } catch (FileAlreadyExistsException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS);
                }
            }
            @Override public void create(String path, byte[] contents) throws IOException {
                try {
                    WorkspaceFileEdit.create(root.toFile(), path, contents,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, WorkspaceFileEdit.secureNioCreator());
                } catch (FileAlreadyExistsException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_EXISTS);
                }
            }
            @Override public byte[] read(String path) throws IOException {
                return WorkspaceFileEdit.read(root.toFile(), path, WorkspaceEditorLimits.MAXIMUM_FILE_BYTES,
                    WorkspaceFileAccess.secureNioOpener());
            }
            @Override public void replace(String path, byte[] expected, byte[] replacement) throws IOException {
                try {
                    WorkspaceFileEdit.replace(root.toFile(), path, expected, replacement,
                        WorkspaceEditorLimits.MAXIMUM_FILE_BYTES, WorkspaceFileAccess.secureNioOpener());
                } catch (WorkspaceFileEdit.FileChangedException error) {
                    throw new WorkspaceEditorException(WorkspaceEditorException.Reason.FILE_CHANGED);
                }
            }
        };
        final WorkspaceFileEditor editor = new WorkspaceFileEditor(store);

        Fixture() throws IOException { }
        @Override public void close() throws IOException {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); }
                });
            }
        }
    }
}
