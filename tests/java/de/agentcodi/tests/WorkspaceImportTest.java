package de.agentcodi.tests;

import de.agentcodi.core.CodexFileMention;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportLimits;
import de.agentcodi.imports.WorkspaceImportSelection;
import de.agentcodi.imports.client.WorkspaceDocumentImporter;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class WorkspaceImportTest {
    private WorkspaceImportTest() {
    }

    public static int run() throws Exception {
        importsAndVerifiesArbitraryBytes();
        sanitizesUntrustedMetadata();
        createsDistinctCopiesWithoutOverwriting();
        rejectsCredentialShapedNamesBeforeCopy();
        enforcesDeclaredAndObservedSizeLimits();
        cleansPartialFileAfterSourceFailure();
        rejectsSymbolicImportsRoot();
        rejectsChangedAndHardLinkedImportsBeforeCodex();
        validatesBoundedImmutableSelections();
        return 9;
    }

    private static void importsAndVerifiesArbitraryBytes() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-basic-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            byte[] expected = new byte[] {0, (byte) 0xff, 7, 0, 42, 13};
            WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter();
            ImportedWorkspaceFile imported = importer.importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "model.weights.custom",
                "Application/Octet-Stream; charset=binary",
                expected.length,
                new ByteArrayInputStream(expected)
            );

            TestSupport.assertTrue(
                imported.getRelativePath().startsWith("imports/"),
                "imported file remains below the dedicated workspace directory"
            );
            TestSupport.assertEquals(
                "model.weights.custom",
                imported.getDisplayName(),
                "display name remains visible"
            );
            TestSupport.assertEquals(
                "application/octet-stream",
                imported.getMediaType(),
                "media type is normalized without parameters"
            );
            Path target = layout.getWorkspace().toPath().resolve(imported.getRelativePath());
            TestSupport.assertTrue(
                Arrays.equals(expected, Files.readAllBytes(target)),
                "arbitrary document bytes are copied exactly"
            );
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                target,
                LinkOption.NOFOLLOW_LINKS
            );
            TestSupport.assertEquals(
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                ),
                permissions,
                "imported file is owner-only"
            );
            CodexFileMention mention = importer.verifyForCodex(
                layout.getWorkspace(),
                imported,
                WorkspaceFileAccess.secureNioOpener()
            );
            TestSupport.assertEquals(
                imported.getDisplayName(),
                mention.getName(),
                "Codex mention preserves the bounded display name"
            );
            TestSupport.assertEquals(
                target.toFile().getCanonicalPath(),
                mention.getPath(),
                "Codex mention uses only the verified canonical workspace copy"
            );
            TestSupport.assertEquals(
                Integer.valueOf(64),
                Integer.valueOf(imported.getSha256().length()),
                "imported content carries a bounded SHA-256 binding"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void sanitizesUntrustedMetadata() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-metadata-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            ImportedWorkspaceFile imported = new WorkspaceDocumentImporter().importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "  ../folder\\report:\u0001.txt.  ",
                "text/plain; charset=utf-8",
                -1L,
                new ByteArrayInputStream("safe".getBytes("UTF-8"))
            );
            TestSupport.assertFalse(
                imported.getDisplayName().contains("/")
                    || imported.getDisplayName().contains("\\")
                    || imported.getDisplayName().contains(":"),
                "untrusted display-name separators are replaced"
            );
            String fileName = imported.getRelativePath().substring("imports/".length());
            TestSupport.assertFalse(
                fileName.startsWith(".") || fileName.contains("/") || fileName.contains(":"),
                "storage name cannot become hidden or escape imports"
            );
            TestSupport.assertTrue(
                fileName.matches("[0-9a-f]{32}\\.txt")
                    && !fileName.contains("folder")
                    && !fileName.contains("report"),
                "model-readable storage path contains only randomness and a safe extension"
            );
            TestSupport.assertEquals(
                "text/plain",
                imported.getMediaType(),
                "valid media type parameters are discarded"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void createsDistinctCopiesWithoutOverwriting() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-import-distinct-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter();
            ImportedWorkspaceFile first = importer.importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "same.txt",
                "text/plain",
                3L,
                new ByteArrayInputStream("one".getBytes("UTF-8"))
            );
            ImportedWorkspaceFile second = importer.importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "same.txt",
                "text/plain",
                3L,
                new ByteArrayInputStream("two".getBytes("UTF-8"))
            );
            TestSupport.assertFalse(
                first.getRelativePath().equals(second.getRelativePath()),
                "repeated display names receive distinct random workspace names"
            );
            TestSupport.assertEquals(
                "one",
                new String(Files.readAllBytes(
                    layout.getWorkspace().toPath().resolve(first.getRelativePath())
                ), "UTF-8"),
                "first imported file is not overwritten"
            );
            TestSupport.assertEquals(
                "two",
                new String(Files.readAllBytes(
                    layout.getWorkspace().toPath().resolve(second.getRelativePath())
                ), "UTF-8"),
                "second imported file has its own content"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsCredentialShapedNamesBeforeCopy() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-import-secret-name-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter();
            for (final String name : Arrays.asList(
                "auth.json",
                ".env.production",
                "password.txt",
                "sk-fixture12345678.txt"
            )) {
                TestSupport.expectThrows(
                    IOException.class,
                    new TestSupport.ThrowingRunnable() {
                        @Override
                        public void run() throws Exception {
                            importer.importDocument(
                                layout.getWorkspace(),
                                layout.getImports(),
                                name,
                                "application/octet-stream",
                                4L,
                                new ByteArrayInputStream(new byte[] {1, 2, 3, 4})
                            );
                        }
                    },
                    "credential-shaped import name must fail closed: " + name
                );
            }
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(importEntryCount(layout)),
                "rejected credential names leave no workspace copy"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void enforcesDeclaredAndObservedSizeLimits() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-import-size-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter(4L);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        importer.importDocument(
                            layout.getWorkspace(),
                            layout.getImports(),
                            "declared.bin",
                            "application/octet-stream",
                            5L,
                            new ByteArrayInputStream(new byte[0])
                        );
                    }
                },
                "declared document size above the limit is rejected"
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        importer.importDocument(
                            layout.getWorkspace(),
                            layout.getImports(),
                            "observed.bin",
                            "application/octet-stream",
                            -1L,
                            new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5})
                        );
                    }
                },
                "observed document size above the limit is rejected"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(importEntryCount(layout)),
                "size failures clean pending and final import entries"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void cleansPartialFileAfterSourceFailure() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-import-source-failure-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final InputStream failing = new InputStream() {
                private int reads;

                @Override
                public int read() throws IOException {
                    if (reads++ < 2) {
                        return 65;
                    }
                    throw new IOException("fixture source failed");
                }
            };
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        new WorkspaceDocumentImporter(32L).importDocument(
                            layout.getWorkspace(),
                            layout.getImports(),
                            "partial.bin",
                            "application/octet-stream",
                            -1L,
                            failing
                        );
                    }
                },
                "source failure aborts import"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(importEntryCount(layout)),
                "source failure removes the private pending file"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsSymbolicImportsRoot() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-import-root-link-");
        final Path outside = Files.createTempDirectory("agentcodi-import-root-target-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.delete(layout.getImports().toPath());
            Files.createSymbolicLink(layout.getImports().toPath(), outside);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        new WorkspaceDocumentImporter().importDocument(
                            layout.getWorkspace(),
                            layout.getImports(),
                            "outside.bin",
                            "application/octet-stream",
                            1L,
                            new ByteArrayInputStream(new byte[] {9})
                        );
                    }
                },
                "symbolic imports directory must fail closed"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(outside.toFile().list().length),
                "symbolic target receives no imported bytes"
            );
        } finally {
            deleteRecursively(base);
            deleteRecursively(outside);
        }
    }

    private static void rejectsChangedAndHardLinkedImportsBeforeCodex() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-import-recheck-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter();
            final ImportedWorkspaceFile imported = importer.importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                "source.bin",
                "application/octet-stream",
                4L,
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4})
            );
            final Path target = layout.getWorkspace().toPath().resolve(
                imported.getRelativePath()
            );
            Path secondLink = layout.getImports().toPath().resolve("second-link.bin");
            Files.createLink(secondLink, target);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        importer.verifyForCodex(
                            layout.getWorkspace(),
                            imported,
                            WorkspaceFileAccess.secureNioOpener()
                        );
                    }
                },
                "hard-linked imported file cannot enter a Codex turn"
            );
            Files.delete(secondLink);
            Files.write(target, new byte[] {4, 3, 2, 1});
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        importer.verifyForCodex(
                            layout.getWorkspace(),
                            imported,
                            WorkspaceFileAccess.secureNioOpener()
                        );
                    }
                },
                "same-size content replacement cannot enter a Codex turn"
            );
            Files.write(target, new byte[] {1, 2, 3, 4, 5});
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        importer.verifyForCodex(
                            layout.getWorkspace(),
                            imported,
                            WorkspaceFileAccess.secureNioOpener()
                        );
                    }
                },
                "size-changed imported file cannot enter a Codex turn"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void validatesBoundedImmutableSelections() throws Exception {
        ImportedWorkspaceFile first = importedMetadata("imports/first.bin", 7L);
        ImportedWorkspaceFile second = importedMetadata("imports/second.bin", 9L);
        List<ImportedWorkspaceFile> mutable = new ArrayList<ImportedWorkspaceFile>();
        mutable.add(first);
        List<ImportedWorkspaceFile> selected = WorkspaceImportSelection.copyOf(mutable);
        mutable.clear();
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(selected.size()),
            "selection snapshot is defensive"
        );
        TestSupport.expectThrows(
            UnsupportedOperationException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    selected.clear();
                }
            },
            "selection snapshot is immutable"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    WorkspaceImportSelection.copyOf(Arrays.asList(first, first));
                }
            },
            "duplicate imported paths are rejected"
        );

        List<ImportedWorkspaceFile> tooMany = new ArrayList<ImportedWorkspaceFile>();
        for (int index = 0; index <= WorkspaceImportLimits.MAXIMUM_FILES_PER_MESSAGE;
             index++) {
            tooMany.add(importedMetadata("imports/file-" + index + ".bin", 0L));
        }
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    WorkspaceImportSelection.copyOf(tooMany);
                }
            },
            "file-count selection limit is enforced"
        );

        final List<ImportedWorkspaceFile> tooLarge = Arrays.asList(
            importedMetadata("imports/large-a.bin", WorkspaceImportLimits.MAXIMUM_FILE_BYTES),
            importedMetadata("imports/large-b.bin", WorkspaceImportLimits.MAXIMUM_FILE_BYTES),
            importedMetadata("imports/large-c.bin", 1L)
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    WorkspaceImportSelection.copyOf(tooLarge);
                }
            },
            "total selection byte limit is enforced"
        );
        TestSupport.assertEquals(
            Long.valueOf(16L),
            Long.valueOf(WorkspaceImportSelection.totalBytes(Arrays.asList(first, second))),
            "selection byte accounting is exact"
        );
    }

    private static ImportedWorkspaceFile importedMetadata(String path, long byteCount) {
        return ImportedWorkspaceFile.create(
            path,
            path.substring("imports/".length()),
            "application/octet-stream",
            byteCount,
            "0000000000000000000000000000000000000000000000000000000000000000"
        );
    }

    private static int importEntryCount(WorkspaceLayout layout) {
        File[] entries = layout.getImports().listFiles();
        return entries == null ? 0 : entries.length;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
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
