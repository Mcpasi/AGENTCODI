package de.agentcodi.imports.client;

import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportLimits;
import de.agentcodi.storage.WorkspaceLayout;
import de.agentcodi.tests.TestSupport;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/** Focused host regressions for import ownership, commit, and recovery. */
public final class WorkspaceImportLifecycleTest {
    private static final String ABANDONED_PENDING_NAME =
        ".pending-0123456789abcdef0123456789abcdef";

    private WorkspaceImportLifecycleTest() {
    }

    public static int run() throws Exception {
        returnsCommittedImportWhenSourceCloseFails();
        returnsCommittedImportWhenDirectoryCloseFails();
        recoveryRemovesOnlyReservedPendingFiles();
        importRecoversPendingFileBeforeMaterialization();
        recoveryDoesNotFollowReservedSymlink();
        preservesPrimaryFailureWhenSourceCloseAlsoFails();
        return 6;
    }

    private static void returnsCommittedImportWhenSourceCloseFails() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-source-close-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            ThrowingCloseInputStream source = new ThrowingCloseInputStream(
                new byte[] {1, 2, 3, 4}
            );
            ImportedWorkspaceFile imported = newImporter().importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "source-close.bin",
                "application/octet-stream",
                4L,
                source
            );

            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(source.closeAttempts),
                "the transferred provider stream is closed exactly once"
            );
            TestSupport.assertTrue(
                Arrays.equals(
                    new byte[] {1, 2, 3, 4},
                    Files.readAllBytes(
                        layout.getWorkspace().toPath().resolve(imported.getRelativePath())
                    )
                ),
                "a source-close failure cannot hide a committed import"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void returnsCommittedImportWhenDirectoryCloseFails() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-directory-close-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            ThrowingDirectoryOpener opener = new ThrowingDirectoryOpener();
            WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter(
                WorkspaceImportLimits.MAXIMUM_FILE_BYTES,
                TEST_INSTALLER,
                opener
            );
            ImportedWorkspaceFile imported = importer.importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "directory-close.bin",
                "application/octet-stream",
                4L,
                new ByteArrayInputStream(new byte[] {5, 6, 7, 8})
            );

            TestSupport.assertEquals(
                Integer.valueOf(2),
                Integer.valueOf(opener.closeAttempts),
                "both committed import directory handles attempt to close"
            );
            TestSupport.assertTrue(
                Arrays.equals(
                    new byte[] {5, 6, 7, 8},
                    Files.readAllBytes(
                        layout.getWorkspace().toPath().resolve(imported.getRelativePath())
                    )
                ),
                "outer directory-close failures cannot hide a committed import"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void recoveryRemovesOnlyReservedPendingFiles() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-recovery-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path imports = layout.getImports().toPath();
            Path abandoned = imports.resolve(ABANDONED_PENDING_NAME);
            Path unrelated = imports.resolve(".pending-user-file");
            Path committed = imports.resolve(
                "fedcba9876543210fedcba9876543210.bin"
            );
            Files.write(abandoned, new byte[] {9, 9});
            Files.write(unrelated, new byte[] {8, 8});
            Files.write(committed, new byte[] {7, 7});

            newImporter().recoverPendingImports(
                layout.getWorkspace(),
                layout.getImports()
            );

            TestSupport.assertFalse(
                Files.exists(abandoned, LinkOption.NOFOLLOW_LINKS),
                "startup recovery removes an exact abandoned pending import"
            );
            TestSupport.assertTrue(
                Arrays.equals(new byte[] {8, 8}, Files.readAllBytes(unrelated)),
                "recovery preserves non-reserved hidden workspace files"
            );
            TestSupport.assertTrue(
                Arrays.equals(new byte[] {7, 7}, Files.readAllBytes(committed)),
                "recovery preserves committed imports"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void importRecoversPendingFileBeforeMaterialization() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-inline-recovery-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path abandoned = layout.getImports().toPath().resolve(
                ABANDONED_PENDING_NAME
            );
            Files.write(abandoned, new byte[] {3, 3, 3});

            ImportedWorkspaceFile imported = newImporter().importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "fresh.bin",
                "application/octet-stream",
                3L,
                new ByteArrayInputStream(new byte[] {4, 4, 4})
            );

            TestSupport.assertFalse(
                Files.exists(abandoned, LinkOption.NOFOLLOW_LINKS),
                "a new import recovers a pending file even before runtime restart"
            );
            TestSupport.assertTrue(
                Files.isRegularFile(
                    layout.getWorkspace().toPath().resolve(imported.getRelativePath()),
                    LinkOption.NOFOLLOW_LINKS
                ),
                "recovery does not prevent the requested materialization"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void recoveryDoesNotFollowReservedSymlink() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-import-recovery-link-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path target = base.resolve("outside-target.bin");
            Files.write(target, new byte[] {1, 9, 1, 9});
            final Path pendingLink = layout.getImports().toPath().resolve(
                ABANDONED_PENDING_NAME
            );
            Files.createSymbolicLink(pendingLink, target);

            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        newImporter().recoverPendingImports(
                            layout.getWorkspace(),
                            layout.getImports()
                        );
                    }
                },
                "recovery rejects a symbolic entry using the reserved pending shape"
            );
            TestSupport.assertTrue(
                Files.isSymbolicLink(pendingLink),
                "recovery does not follow or reinterpret the reserved symlink"
            );
            TestSupport.assertTrue(
                Arrays.equals(new byte[] {1, 9, 1, 9}, Files.readAllBytes(target)),
                "recovery cannot alter the symlink target"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void preservesPrimaryFailureWhenSourceCloseAlsoFails()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-double-failure-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            FailingReadAndCloseInputStream source =
                new FailingReadAndCloseInputStream();
            IOException failure = null;
            try {
                newImporter().importDocument(
                    layout.getWorkspace(),
                    layout.getImports(),
                    "failed.bin",
                    "application/octet-stream",
                    -1L,
                    source
                );
            } catch (IOException error) {
                failure = error;
            }
            TestSupport.assertTrue(failure != null, "copy failure remains visible");
            TestSupport.assertContains(
                failure.getMessage(),
                "intentional read failure",
                "source close cannot replace the materialization failure"
            );
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(failure.getSuppressed().length),
                "source-close failure is retained as suppressed context"
            );
            TestSupport.assertContains(
                failure.getSuppressed()[0].getMessage(),
                "intentional source close failure",
                "suppressed context identifies the close failure"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(importEntryCount(layout)),
                "a failed materialization still removes its pending file"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static final WorkspaceDocumentInstaller TEST_INSTALLER =
        new WorkspaceDocumentInstaller() {
            @Override
            public void installNoReplace(
                File workspaceDirectory,
                String pendingName,
                String finalName,
                long expectedByteCount
            ) throws IOException {
                Path imports = workspaceDirectory.toPath().resolve(
                    WorkspaceImportLimits.IMPORT_DIRECTORY_NAME
                );
                Path pending = imports.resolve(pendingName);
                Path target = imports.resolve(finalName);
                Files.createLink(target, pending);
                Files.delete(pending);
            }
        };

    private static WorkspaceDocumentImporter newImporter() {
        return new WorkspaceDocumentImporter(TEST_INSTALLER);
    }

    private static final class ThrowingCloseInputStream
        extends ByteArrayInputStream {
        private int closeAttempts;

        private ThrowingCloseInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closeAttempts++;
            throw new IOException("intentional source close failure");
        }
    }

    private static final class FailingReadAndCloseInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("intentional read failure");
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("intentional read failure");
        }

        @Override
        public void close() throws IOException {
            throw new IOException("intentional source close failure");
        }
    }

    private static final class ThrowingDirectoryOpener
        implements WorkspaceDocumentImporter.DirectoryOpener {
        private int closeAttempts;

        @Override
        public DirectoryStream<Path> open(Path directory) throws IOException {
            DirectoryStream<Path> opened = Files.newDirectoryStream(directory);
            if (!(opened instanceof SecureDirectoryStream<?>)) {
                opened.close();
                throw new IOException("test filesystem lacks secure directory streams");
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> secure =
                (SecureDirectoryStream<Path>) opened;
            return new ThrowingCloseSecureDirectoryStream(this, secure);
        }
    }

    private static final class ThrowingCloseSecureDirectoryStream
        implements SecureDirectoryStream<Path> {
        private final ThrowingDirectoryOpener owner;
        private final SecureDirectoryStream<Path> delegate;
        private boolean closed;

        private ThrowingCloseSecureDirectoryStream(
            ThrowingDirectoryOpener testOwner,
            SecureDirectoryStream<Path> secureDelegate
        ) {
            owner = testOwner;
            delegate = secureDelegate;
        }

        @Override
        public Iterator<Path> iterator() {
            return delegate.iterator();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                delegate.close();
            } catch (IOException error) {
                failure = error;
            }
            owner.closeAttempts++;
            IOException injected = new IOException(
                "intentional directory close failure " + owner.closeAttempts
            );
            if (failure != null) {
                failure.addSuppressed(injected);
                throw failure;
            }
            throw injected;
        }

        @Override
        public SecureDirectoryStream<Path> newDirectoryStream(
            Path path,
            LinkOption... options
        ) throws IOException {
            return new ThrowingCloseSecureDirectoryStream(
                owner,
                delegate.newDirectoryStream(path, options)
            );
        }

        @Override
        public SeekableByteChannel newByteChannel(
            Path path,
            Set<? extends OpenOption> options,
            FileAttribute<?>... attributes
        ) throws IOException {
            return delegate.newByteChannel(path, options, attributes);
        }

        @Override
        public void deleteFile(Path path) throws IOException {
            delegate.deleteFile(path);
        }

        @Override
        public void deleteDirectory(Path path) throws IOException {
            delegate.deleteDirectory(path);
        }

        @Override
        public void move(
            Path source,
            SecureDirectoryStream<Path> targetDirectory,
            Path target
        ) throws IOException {
            SecureDirectoryStream<Path> targetDelegate = targetDirectory;
            if (targetDirectory instanceof ThrowingCloseSecureDirectoryStream) {
                targetDelegate = ((ThrowingCloseSecureDirectoryStream) targetDirectory)
                    .delegate;
            }
            delegate.move(source, targetDelegate, target);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(
            Class<V> type
        ) {
            return delegate.getFileAttributeView(type);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(
            Path path,
            Class<V> type,
            LinkOption... options
        ) {
            return delegate.getFileAttributeView(path, type, options);
        }
    }

    private static int importEntryCount(WorkspaceLayout layout) {
        File[] entries = layout.getImports().listFiles();
        return entries == null ? 0 : entries.length;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(path)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        File[] children = path.toFile().listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child.toPath());
            }
        }
        Files.deleteIfExists(path);
    }
}
