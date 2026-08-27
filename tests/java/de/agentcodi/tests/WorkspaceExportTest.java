package de.agentcodi.tests;

import de.agentcodi.storage.WorkspaceArchive;
import de.agentcodi.storage.WorkspaceExportFile;
import de.agentcodi.storage.WorkspaceExportTransaction;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class WorkspaceExportTest {
    private WorkspaceExportTest() {
    }

    public static int run() throws Exception {
        catalogsAllRegularFileTypes();
        copiesUnknownBinaryTypeExactly();
        rollsBackDestinationAfterFileMutationDuringExport();
        rollsBackDestinationAfterCloseFailure();
        acceptsCanonicalWorkspaceAlias();
        rejectsFileOutsideWorkspace();
        skipsSymbolicCatalogEntriesWithoutFollowingTargets();
        doesNotChargeSkippedEntriesAgainstRegularFileLimit();
        keepsSkippedEntriesBoundedBySeparateScanLimit();
        rejectsDirectSymbolicFileExportWithoutWritingBytes();
        rejectsHardLinkToPrivateSibling();
        rejectsFileSymlinkSwapBeforeOpenWithoutWritingBytes();
        rejectsFileHardLinkSwapBeforeOpenWithoutWritingBytes();
        archivesCompleteWorkspaceWithoutCodexHome();
        archivesOnlyTheSelectedFolderContents();
        rejectsUnsafeSelectedFolderPaths();
        createsValidEmptyWorkspaceArchive();
        rejectsArchiveFileAboveLimit();
        rejectsArchiveTotalAboveLimit();
        rejectsArchiveFileCountAboveLimit();
        omitsUnsafePortableArchiveNameWithoutBlockingSibling();
        omitsPortableArchiveNameCollisionWithoutBlockingSibling();
        archivesAcrossProviderTimestampPrecision();
        rejectsWorkspaceMutationDuringArchive();
        rejectsArchiveSymlinkSwapBeforeOpen();
        archivesRegularFilesWhileOmittingSymbolicEntries();
        archivesRegularFilesWhileOmittingHardLinks();
        return 27;
    }

    private static void catalogsAllRegularFileTypes() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-catalog-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            Files.createDirectories(workspace.resolve("nested"));
            Files.write(workspace.resolve("notes.md"), "text".getBytes("UTF-8"));
            Files.write(workspace.resolve("nested/raw.unknown"), new byte[] {0, 1, 2, 3});
            Files.write(workspace.resolve("empty.bin"), new byte[0]);

            List<WorkspaceExportFile> files = WorkspaceExportFile.list(
                layout.getWorkspace(),
                10,
                256,
                8
            );
            TestSupport.assertEquals(Integer.valueOf(3), Integer.valueOf(files.size()), "file count");
            TestSupport.assertEquals("empty.bin", files.get(0).getRelativePath(), "sorted empty file");
            TestSupport.assertEquals(
                "nested/raw.unknown",
                files.get(1).getRelativePath(),
                "nested unknown file"
            );
            TestSupport.assertEquals("notes.md", files.get(2).getRelativePath(), "sorted text file");
            TestSupport.assertEquals(
                Long.valueOf(0L),
                Long.valueOf(files.get(0).getByteCount()),
                "empty regular file remains exportable"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void copiesUnknownBinaryTypeExactly() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-binary-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path source = layout.getWorkspace().toPath().resolve("model.weights.custom");
            byte[] expected = new byte[] {0, (byte) 0xff, 17, 0, 42, 9};
            Files.write(source, expected);
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceExportFile exported = WorkspaceExportFile.copyTo(
                layout.getWorkspace(),
                source.toString(),
                1024L,
                destination
            );
            TestSupport.assertEquals(
                "model.weights.custom",
                exported.getDisplayName(),
                "unknown extension is preserved"
            );
            TestSupport.assertTrue(
                java.util.Arrays.equals(expected, destination.toByteArray()),
                "unknown binary bytes are unchanged"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rollsBackDestinationAfterFileMutationDuringExport()
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-file-rollback-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path source = layout.getWorkspace().toPath().resolve("source.bin");
            Files.write(source, new byte[] {1, 2, 3, 4});
            final ResettingDestination destination = new ResettingDestination(
                new FirstWriteAction() {
                    @Override
                    public void run() throws IOException {
                        Files.move(source, source.resolveSibling("source-before-export.bin"));
                        Files.write(source, new byte[] {9, 8, 7, 6});
                    }
                }
            );

            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportTransaction.execute(
                            destination,
                            new WorkspaceExportTransaction.Preparation<WorkspaceLayout>() {
                                @Override
                                public WorkspaceLayout prepare() throws IOException {
                                    WorkspaceExportFile.inspect(
                                        layout.getWorkspace(),
                                        source.toString(),
                                        1024L
                                    );
                                    return layout;
                                }
                            },
                            new WorkspaceExportTransaction.Writer<
                                WorkspaceLayout,
                                WorkspaceExportFile
                            >() {
                                @Override
                                public WorkspaceExportFile write(
                                    WorkspaceLayout preparedLayout,
                                    OutputStream output
                                ) throws IOException {
                                    return WorkspaceExportFile.copyTo(
                                        preparedLayout.getWorkspace(),
                                        source.toString(),
                                        1024L,
                                        output
                                    );
                                }
                            }
                        );
                    }
                },
                "workspace mutation during file export must fail"
            );
            TestSupport.assertTrue(
                destination.getMaximumWrittenBytes() > 0,
                "the provider fixture reproduces bytes written before mutation detection"
            );
            TestSupport.assertTrue(
                destination.wasRolledBack(),
                "failed file export rolls back its selected document"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(destination.getByteCount()),
                "failed file export leaves no target bytes"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rollsBackDestinationAfterCloseFailure() throws Exception {
        final ResettingDestination destination = new ResettingDestination(
            new FirstWriteAction() {
                @Override
                public void run() {
                }
            },
            true
        );
        TestSupport.expectThrows(
            IOException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    WorkspaceExportTransaction.execute(
                        destination,
                        new WorkspaceExportTransaction.Preparation<String>() {
                            @Override
                            public String prepare() {
                                return "prepared";
                            }
                        },
                        new WorkspaceExportTransaction.Writer<String, String>() {
                            @Override
                            public String write(String prepared, OutputStream output)
                                throws IOException {
                                output.write(new byte[] {1, 2, 3, 4});
                                return prepared;
                            }
                        }
                    );
                }
            },
            "destination close failure must fail the export"
        );
        TestSupport.assertTrue(
            destination.getMaximumWrittenBytes() > 0,
            "close-failure fixture writes bytes before failing"
        );
        TestSupport.assertTrue(
            destination.wasRolledBack(),
            "destination close failure triggers rollback"
        );
        TestSupport.assertEquals(
            Integer.valueOf(0),
            Integer.valueOf(destination.getByteCount()),
            "destination close failure leaves no target bytes"
        );
    }

    private static void acceptsCanonicalWorkspaceAlias() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-alias-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path source = layout.getWorkspace().toPath().resolve("alias.txt");
            Files.write(source, "alias".getBytes("UTF-8"));
            Path alias = base.resolve("android-files-alias");
            Files.createSymbolicLink(alias, layout.getWorkspace().toPath());
            WorkspaceExportFile inspected = WorkspaceExportFile.inspect(
                layout.getWorkspace(),
                alias.resolve("alias.txt").toString(),
                1024L
            );
            TestSupport.assertEquals(
                source.toFile().getCanonicalPath(),
                inspected.getFile().getCanonicalPath(),
                "canonical platform alias reaches the same workspace"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsFileOutsideWorkspace() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-outside-");
        final Path outside = Files.createTempFile("agentcodi-export-foreign-", ".bin");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.inspect(
                            layout.getWorkspace(),
                            outside.toString(),
                            1024L
                        );
                    }
                },
                "outside file must not be exportable"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void skipsSymbolicCatalogEntriesWithoutFollowingTargets()
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-symlink-");
        Path outside = Files.createTempFile("agentcodi-export-link-target-", ".txt");
        Path outsideDirectory = Files.createTempDirectory(
            "agentcodi-export-link-directory-target-"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            Files.write(workspace.resolve("regular.txt"), "workspace".getBytes("UTF-8"));
            Files.write(
                outsideDirectory.resolve("foreign.txt"),
                "foreign".getBytes("UTF-8")
            );
            Files.createSymbolicLink(
                workspace.resolve("linked.txt"),
                outside
            );
            Files.createSymbolicLink(
                workspace.resolve("linked-directory"),
                outsideDirectory
            );

            List<WorkspaceExportFile> files = WorkspaceExportFile.list(
                layout.getWorkspace(),
                10,
                256,
                8
            );
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(files.size()),
                "symbolic entries do not block the regular-file catalog"
            );
            TestSupport.assertEquals(
                "regular.txt",
                files.get(0).getRelativePath(),
                "catalog omits symbolic files and never traverses symbolic directories"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
            deleteRecursively(outsideDirectory);
        }
    }

    private static void rejectsDirectSymbolicFileExportWithoutWritingBytes()
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-direct-symlink-");
        final Path outside = Files.createTempFile(
            "agentcodi-export-direct-symlink-target-",
            ".txt"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path link = layout.getWorkspace().toPath().resolve("linked.txt");
            Files.write(outside, "foreign".getBytes("UTF-8"));
            Files.createSymbolicLink(link, outside);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.inspect(
                            layout.getWorkspace(),
                            link.toString(),
                            1024L
                        );
                    }
                },
                "a symbolic path must remain unavailable for direct export"
            );
            final ByteArrayOutputStream destination = new ByteArrayOutputStream();
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.copyTo(
                            layout.getWorkspace(),
                            link.toString(),
                            1024L,
                            destination
                        );
                    }
                },
                "a symbolic path must never be copied"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(destination.size()),
                "direct symbolic export must not write target bytes"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void doesNotChargeSkippedEntriesAgainstRegularFileLimit()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-separate-counts-");
        Path outside = Files.createTempFile(
            "agentcodi-export-separate-counts-target-",
            ".txt"
        );
        Path outsideDirectory = Files.createTempDirectory(
            "agentcodi-export-separate-counts-directory-target-"
        );
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            byte[] expected = "regular".getBytes("UTF-8");
            Files.write(workspace.resolve("regular.txt"), expected);
            Files.createDirectories(workspace.resolve("empty-directory"));
            Files.createSymbolicLink(workspace.resolve("linked.txt"), outside);
            Files.createSymbolicLink(
                workspace.resolve("linked-directory"),
                outsideDirectory
            );

            List<WorkspaceExportFile> files = WorkspaceExportFile.list(
                layout.getWorkspace(),
                1,
                256,
                8
            );
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(files.size()),
                "only regular files consume the regular-file export limit"
            );
            TestSupport.assertEquals(
                "regular.txt",
                files.get(0).getRelativePath(),
                "the regular file remains catalogued at the exact file limit"
            );

            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                1,
                6,
                1024L,
                4096L,
                256,
                8
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getFileCount()),
                "ZIP file limit is based on exported regular files"
            );
            TestSupport.assertTrue(
                java.util.Arrays.equals(expected, entries.get("regular.txt")),
                "ZIP retains the regular file when skipped entries are present"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
            deleteRecursively(outsideDirectory);
        }
    }

    private static void keepsSkippedEntriesBoundedBySeparateScanLimit()
        throws Exception {
        final Path base = Files.createTempDirectory(
            "agentcodi-export-separate-scan-limit-"
        );
        Path outside = Files.createTempFile(
            "agentcodi-export-separate-scan-limit-target-",
            ".txt"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            Files.createSymbolicLink(workspace.resolve("linked-1"), outside);
            Files.createSymbolicLink(workspace.resolve("linked-2"), outside);
            Files.createSymbolicLink(workspace.resolve("linked-3"), outside);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.list(
                            layout.getWorkspace(),
                            1,
                            2,
                            256,
                            8
                        );
                    }
                },
                "skipped entries remain bounded by the independent scan limit"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void rejectsHardLinkToPrivateSibling() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-hardlink-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path credential = layout.getCodexHome().toPath().resolve("auth.json");
            Files.write(credential, "private".getBytes("UTF-8"));
            Files.createLink(
                layout.getWorkspace().toPath().resolve("ordinary-looking.json"),
                credential
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.list(layout.getWorkspace(), 10, 256, 8);
                    }
                },
                "hard link to a private sibling must not be exportable"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsFileSymlinkSwapBeforeOpenWithoutWritingBytes()
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-open-symlink-race-");
        final Path outside = Files.createTempFile(
            "agentcodi-export-open-symlink-outside-",
            ".bin"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path source = layout.getWorkspace().toPath().resolve("source.bin");
            Files.write(source, "workspace".getBytes("UTF-8"));
            Files.write(outside, "outside".getBytes("UTF-8"));
            final WorkspaceFileAccess.Opener swapping = new WorkspaceFileAccess.Opener() {
                private boolean swapped;

                @Override
                public WorkspaceFileAccess.Source open(
                    File workspaceDirectory,
                    String relativePath,
                    long maximumBytes
                ) throws IOException {
                    if (!swapped) {
                        swapped = true;
                        Files.move(source, source.resolveSibling("source-before-swap.bin"));
                        Files.createSymbolicLink(source, outside);
                    }
                    return WorkspaceFileAccess.secureNioOpener().open(
                        workspaceDirectory,
                        relativePath,
                        maximumBytes
                    );
                }
            };
            final ByteArrayOutputStream destination = new ByteArrayOutputStream();
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.copyTo(
                            layout.getWorkspace(),
                            source.toString(),
                            1024L,
                            destination,
                            swapping
                        );
                    }
                },
                "symlink exchange before descriptor open must fail"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(destination.size()),
                "symlink exchange must not write foreign bytes"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void rejectsFileHardLinkSwapBeforeOpenWithoutWritingBytes()
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-open-hardlink-race-");
        final Path outside = Files.createTempFile(
            "agentcodi-export-open-hardlink-outside-",
            ".bin"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path source = layout.getWorkspace().toPath().resolve("source.bin");
            Files.write(source, "workspace".getBytes("UTF-8"));
            Files.write(outside, "outside".getBytes("UTF-8"));
            final WorkspaceFileAccess.Opener swapping = new WorkspaceFileAccess.Opener() {
                private boolean swapped;

                @Override
                public WorkspaceFileAccess.Source open(
                    File workspaceDirectory,
                    String relativePath,
                    long maximumBytes
                ) throws IOException {
                    if (!swapped) {
                        swapped = true;
                        Files.move(source, source.resolveSibling("source-before-swap.bin"));
                        Files.createLink(source, outside);
                    }
                    return WorkspaceFileAccess.secureNioOpener().open(
                        workspaceDirectory,
                        relativePath,
                        maximumBytes
                    );
                }
            };
            final ByteArrayOutputStream destination = new ByteArrayOutputStream();
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.copyTo(
                            layout.getWorkspace(),
                            source.toString(),
                            1024L,
                            destination,
                            swapping
                        );
                    }
                },
                "hard-link exchange before descriptor open must fail"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(destination.size()),
                "hard-link exchange must not write foreign bytes"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void archivesCompleteWorkspaceWithoutCodexHome() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-archive-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            Files.createDirectories(workspace.resolve("nested"));
            byte[] text = "hello".getBytes("UTF-8");
            byte[] binary = new byte[] {0, 7, (byte) 0xfe, 3};
            Files.write(workspace.resolve("readme.txt"), text);
            Files.write(workspace.resolve("nested/payload.data"), binary);
            Files.write(workspace.resolve("empty"), new byte[0]);
            Files.write(
                layout.getCodexHome().toPath().resolve("auth.json"),
                "must-not-leave".getBytes("UTF-8")
            );

            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                1024L,
                4096L,
                256,
                8
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(Integer.valueOf(3), Integer.valueOf(summary.getFileCount()), "zip count");
            TestSupport.assertEquals(Integer.valueOf(3), Integer.valueOf(entries.size()), "zip entries");
            TestSupport.assertTrue(
                java.util.Arrays.equals(text, entries.get("readme.txt")),
                "text content in zip"
            );
            TestSupport.assertTrue(
                java.util.Arrays.equals(binary, entries.get("nested/payload.data")),
                "unknown binary content in zip"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(entries.get("empty").length),
                "empty file in zip"
            );
            TestSupport.assertFalse(
                entries.containsKey("auth.json") || entries.containsKey("codex-home/auth.json"),
                "CODEX_HOME credential is outside archive root"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void createsValidEmptyWorkspaceArchive() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-empty-archive-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                1024L,
                4096L,
                256,
                8
            );
            TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(summary.getFileCount()), "empty zip count");
            TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(unzip(destination.toByteArray()).size()), "valid empty zip");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void archivesOnlyTheSelectedFolderContents() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-selected-folder-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            Files.createDirectories(workspace.resolve("projects/demo/nested"));
            Files.write(
                workspace.resolve("projects/demo/readme.txt"),
                "selected".getBytes("UTF-8")
            );
            Files.write(
                workspace.resolve("projects/demo/nested/data.bin"),
                new byte[] {1, 2, 3}
            );
            Files.write(
                workspace.resolve("projects/sibling.txt"),
                "sibling".getBytes("UTF-8")
            );
            Files.write(workspace.resolve("root.txt"), "root".getBytes("UTF-8"));

            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                "projects/demo",
                destination,
                10,
                64,
                1024L,
                4096L,
                256,
                8,
                de.agentcodi.storage.WorkspaceDirectoryCatalog.secureNioReader(),
                WorkspaceFileAccess.secureNioOpener()
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                "projects/demo",
                summary.getRelativeDirectory(),
                "summary binds the selected folder"
            );
            TestSupport.assertEquals(
                Integer.valueOf(2),
                Integer.valueOf(summary.getFileCount()),
                "selected folder file count"
            );
            TestSupport.assertTrue(entries.containsKey("readme.txt"), "selected root file");
            TestSupport.assertTrue(
                entries.containsKey("nested/data.bin"),
                "selected nested file"
            );
            TestSupport.assertFalse(
                entries.containsKey("projects/demo/readme.txt")
                    || entries.containsKey("../sibling.txt")
                    || entries.containsKey("root.txt"),
                "folder ZIP contains only paths relative to the selected folder"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsUnsafeSelectedFolderPaths() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-selected-boundary-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(
                layout.getCodexHome().toPath().resolve("auth.json"),
                "private".getBytes("UTF-8")
            );
            Files.createSymbolicLink(
                layout.getWorkspace().toPath().resolve("linked-home"),
                layout.getCodexHome().toPath()
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(
                            layout.getWorkspace(),
                            "../codex-home",
                            10,
                            64,
                            1024L,
                            4096L,
                            256,
                            8,
                            de.agentcodi.storage.WorkspaceDirectoryCatalog.secureNioReader(),
                            WorkspaceFileAccess.secureNioOpener()
                        );
                    }
                },
                "selected folder traversal must be rejected"
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(
                            layout.getWorkspace(),
                            "linked-home",
                            10,
                            64,
                            1024L,
                            4096L,
                            256,
                            8,
                            de.agentcodi.storage.WorkspaceDirectoryCatalog.secureNioReader(),
                            WorkspaceFileAccess.secureNioOpener()
                        );
                    }
                },
                "a symbolic folder must never become an archive root"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsArchiveFileAboveLimit() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-file-limit-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getWorkspace().toPath().resolve("large.bin"), new byte[9]);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(layout.getWorkspace(), 10, 8L, 100L, 256, 8);
                    }
                },
                "archive per-file bound"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsArchiveTotalAboveLimit() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-total-limit-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getWorkspace().toPath().resolve("one.bin"), new byte[6]);
            Files.write(layout.getWorkspace().toPath().resolve("two.bin"), new byte[6]);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(layout.getWorkspace(), 10, 10L, 11L, 256, 8);
                    }
                },
                "archive total bound"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsArchiveFileCountAboveLimit() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-count-limit-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getWorkspace().toPath().resolve("one"), new byte[0]);
            Files.write(layout.getWorkspace().toPath().resolve("two"), new byte[0]);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(layout.getWorkspace(), 1, 10L, 10L, 256, 8);
                    }
                },
                "archive file-count bound"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void omitsUnsafePortableArchiveNameWithoutBlockingSibling()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-name-boundary-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(
                layout.getWorkspace().toPath().resolve("C:\\outside.bin"),
                new byte[] {1}
            );
            Files.write(
                layout.getWorkspace().toPath().resolve("safe.bin"),
                new byte[] {2}
            );
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                10L,
                10L,
                256,
                8
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getOmittedEntryCount()),
                "unsafe portable name is reported as omitted"
            );
            TestSupport.assertTrue(entries.containsKey("safe.bin"), "safe sibling exports");
            TestSupport.assertFalse(
                entries.containsKey("C:\\outside.bin"),
                "drive and separator ambiguity never enters the ZIP"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void omitsPortableArchiveNameCollisionWithoutBlockingSibling()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-name-collision-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getWorkspace().toPath().resolve("Report.bin"), new byte[] {1});
            Files.write(layout.getWorkspace().toPath().resolve("report.bin"), new byte[] {2});
            Files.write(layout.getWorkspace().toPath().resolve("safe.bin"), new byte[] {3});
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                10L,
                10L,
                256,
                8
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getOmittedEntryCount()),
                "one case-insensitive collision is omitted"
            );
            TestSupport.assertEquals(
                Integer.valueOf(2),
                Integer.valueOf(entries.size()),
                "collision does not block unrelated files"
            );
            TestSupport.assertTrue(entries.containsKey("Report.bin"), "first stable name wins");
            TestSupport.assertFalse(entries.containsKey("report.bin"), "colliding name omitted");
            TestSupport.assertTrue(entries.containsKey("safe.bin"), "safe sibling remains");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void archivesAcrossProviderTimestampPrecision() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-time-precision-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path source = layout.getWorkspace().toPath().resolve("stable.bin");
            byte[] expected = new byte[] {4, 3, 2, 1};
            Files.write(source, expected);
            final WorkspaceFileAccess.Opener differentNanosecondView =
                new WorkspaceFileAccess.Opener() {
                    @Override
                    public WorkspaceFileAccess.Source open(
                        File workspaceDirectory,
                        String relativePath,
                        long maximumBytes
                    ) throws IOException {
                        final WorkspaceFileAccess.Source delegate =
                            WorkspaceFileAccess.secureNioOpener().open(
                                workspaceDirectory,
                                relativePath,
                                maximumBytes
                            );
                        return new WorkspaceFileAccess.Source() {
                            @Override
                            public long getByteCount() {
                                return delegate.getByteCount();
                            }

                            @Override
                            public FileTime getLastModifiedTime() {
                                Instant original = delegate.getLastModifiedTime().toInstant();
                                int remainder = original.getNano() % 1000;
                                int differentRemainder = remainder == 999
                                    ? 998
                                    : remainder + 1;
                                int adjustedNanos = original.getNano() - remainder
                                    + differentRemainder;
                                return FileTime.from(Instant.ofEpochSecond(
                                    original.getEpochSecond(),
                                    adjustedNanos
                                ));
                            }

                            @Override
                            public Object getFileKey() {
                                return delegate.getFileKey();
                            }

                            @Override
                            public int read(byte[] buffer, int offset, int length)
                                throws IOException {
                                return delegate.read(buffer, offset, length);
                            }

                            @Override
                            public void verifyUnchanged() throws IOException {
                                delegate.verifyUnchanged();
                            }

                            @Override
                            public void close() throws IOException {
                                delegate.close();
                            }
                        };
                    }
                };
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                1024L,
                4096L,
                256,
                8,
                differentNanosecondView
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getFileCount()),
                "provider timestamp precision does not invent a mutation"
            );
            TestSupport.assertTrue(
                java.util.Arrays.equals(expected, entries.get("stable.bin")),
                "provider timestamp precision preserves ZIP bytes"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsWorkspaceMutationDuringArchive() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-archive-race-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path source = layout.getWorkspace().toPath().resolve("source.bin");
            Files.write(source, new byte[] {1, 2, 3, 4});
            final ResettingDestination destination = new ResettingDestination(
                new FirstWriteAction() {
                    @Override
                    public void run() throws IOException {
                        Files.move(source, source.resolveSibling("source-before-export.bin"));
                        Files.write(source, new byte[] {9, 8, 7, 6});
                    }
                }
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportTransaction.execute(
                            destination,
                            new WorkspaceExportTransaction.Preparation<WorkspaceLayout>() {
                                @Override
                                public WorkspaceLayout prepare() throws IOException {
                                    WorkspaceArchive.inspect(
                                        layout.getWorkspace(),
                                        10,
                                        1024L,
                                        4096L,
                                        256,
                                        8
                                    );
                                    return layout;
                                }
                            },
                            new WorkspaceExportTransaction.Writer<
                                WorkspaceLayout,
                                WorkspaceArchive.Summary
                            >() {
                                @Override
                                public WorkspaceArchive.Summary write(
                                    WorkspaceLayout preparedLayout,
                                    OutputStream output
                                ) throws IOException {
                                    return WorkspaceArchive.write(
                                        preparedLayout.getWorkspace(),
                                        output,
                                        10,
                                        1024L,
                                        4096L,
                                        256,
                                        8
                                    );
                                }
                            }
                        );
                    }
                },
                "workspace mutation during archive must be detected"
            );
            TestSupport.assertTrue(
                destination.getMaximumWrittenBytes() > 0,
                "the provider fixture reproduces ZIP bytes written before mutation detection"
            );
            TestSupport.assertTrue(
                destination.wasRolledBack(),
                "failed archive export rolls back its selected document"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(destination.getByteCount()),
                "failed archive export leaves no target bytes"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsArchiveSymlinkSwapBeforeOpen() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-zip-open-race-");
        final Path outside = Files.createTempFile(
            "agentcodi-export-zip-open-outside-",
            ".bin"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path source = layout.getWorkspace().toPath().resolve("source.bin");
            Files.write(source, "workspace".getBytes("UTF-8"));
            Files.write(outside, "outside".getBytes("UTF-8"));
            final WorkspaceFileAccess.Opener swapping = new WorkspaceFileAccess.Opener() {
                private boolean swapped;

                @Override
                public WorkspaceFileAccess.Source open(
                    File workspaceDirectory,
                    String relativePath,
                    long maximumBytes
                ) throws IOException {
                    if (!swapped) {
                        swapped = true;
                        Files.move(source, source.resolveSibling("source-before-swap.bin"));
                        Files.createSymbolicLink(source, outside);
                    }
                    return WorkspaceFileAccess.secureNioOpener().open(
                        workspaceDirectory,
                        relativePath,
                        maximumBytes
                    );
                }
            };
            final ByteArrayOutputStream destination = new ByteArrayOutputStream();
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.write(
                            layout.getWorkspace(),
                            destination,
                            10,
                            1024L,
                            4096L,
                            256,
                            8,
                            swapping
                        );
                    }
                },
                "ZIP symlink exchange before descriptor open must fail"
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            byte[] exported = entries.get("source.bin");
            TestSupport.assertTrue(
                exported == null || exported.length == 0,
                "ZIP exchange must not include foreign bytes"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void archivesRegularFilesWhileOmittingSymbolicEntries()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-zip-symlink-");
        Path outside = Files.createTempFile(
            "agentcodi-export-zip-symlink-target-",
            ".bin"
        );
        Path outsideDirectory = Files.createTempDirectory(
            "agentcodi-export-zip-symlink-directory-target-"
        );
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            byte[] expected = "workspace".getBytes("UTF-8");
            Files.write(workspace.resolve("regular.bin"), expected);
            Files.write(outside, "foreign-file".getBytes("UTF-8"));
            Files.write(
                outsideDirectory.resolve("foreign-directory-file.bin"),
                "foreign-directory".getBytes("UTF-8")
            );
            Files.createSymbolicLink(workspace.resolve("linked.bin"), outside);
            Files.createSymbolicLink(
                workspace.resolve("linked-directory"),
                outsideDirectory
            );

            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                1024L,
                4096L,
                256,
                8
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getFileCount()),
                "ZIP summary includes only regular workspace files"
            );
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(entries.size()),
                "ZIP omits symbolic file and directory entries"
            );
            TestSupport.assertTrue(
                java.util.Arrays.equals(expected, entries.get("regular.bin")),
                "ZIP preserves the regular workspace file"
            );
            TestSupport.assertFalse(
                entries.containsKey("linked.bin")
                    || entries.containsKey("linked-directory/foreign-directory-file.bin"),
                "ZIP never includes symbolic targets"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
            deleteRecursively(outsideDirectory);
        }
    }

    private static void archivesRegularFilesWhileOmittingHardLinks()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-export-zip-hardlink-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath();
            Path credential = layout.getCodexHome().toPath().resolve("auth.json");
            byte[] expected = "workspace".getBytes("UTF-8");
            Files.write(workspace.resolve("regular.bin"), expected);
            Files.write(credential, "private".getBytes("UTF-8"));
            Files.createLink(workspace.resolve("ordinary-looking.json"), credential);

            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceArchive.Summary summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destination,
                10,
                1024L,
                4096L,
                256,
                8
            );
            Map<String, byte[]> entries = unzip(destination.toByteArray());
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getFileCount()),
                "hard link does not block the regular-file archive"
            );
            TestSupport.assertEquals(
                Integer.valueOf(1),
                Integer.valueOf(summary.getOmittedEntryCount()),
                "hard link is reported as omitted"
            );
            TestSupport.assertTrue(
                java.util.Arrays.equals(expected, entries.get("regular.bin")),
                "safe sibling bytes remain exportable"
            );
            TestSupport.assertFalse(
                entries.containsKey("ordinary-looking.json"),
                "hard-linked private bytes never enter the ZIP"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            while (true) {
                ZipEntry entry = input.getNextEntry();
                if (entry == null) {
                    break;
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[256];
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    if (count > 0) {
                        content.write(buffer, 0, count);
                    }
                }
                entries.put(entry.getName(), content.toByteArray());
                input.closeEntry();
            }
        }
        return entries;
    }

    private interface FirstWriteAction {
        void run() throws IOException;
    }

    private static final class ResettingDestination
        implements WorkspaceExportTransaction.Destination {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final FirstWriteAction firstWriteAction;
        private final boolean failOnClose;
        private boolean opened;
        private boolean firstWrite = true;
        private boolean rolledBack;
        private int maximumWrittenBytes;

        private ResettingDestination(FirstWriteAction firstWriteAction) {
            this(firstWriteAction, false);
        }

        private ResettingDestination(
            FirstWriteAction firstWriteAction,
            boolean failOnClose
        ) {
            this.firstWriteAction = firstWriteAction;
            this.failOnClose = failOnClose;
        }

        @Override
        public OutputStream open() throws IOException {
            if (opened) {
                throw new IOException("Test destination was opened more than once");
            }
            opened = true;
            bytes.reset();
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    beforeWrite();
                    bytes.write(value);
                    recordWrittenBytes();
                }

                @Override
                public void write(byte[] value, int offset, int length)
                    throws IOException {
                    beforeWrite();
                    bytes.write(value, offset, length);
                    recordWrittenBytes();
                }

                @Override
                public void close() throws IOException {
                    if (failOnClose) {
                        throw new IOException("Test destination close failed");
                    }
                }
            };
        }

        @Override
        public void rollback() {
            rolledBack = true;
            bytes.reset();
        }

        private void beforeWrite() throws IOException {
            if (!firstWrite) {
                return;
            }
            firstWrite = false;
            firstWriteAction.run();
        }

        private void recordWrittenBytes() {
            maximumWrittenBytes = Math.max(maximumWrittenBytes, bytes.size());
        }

        private boolean wasRolledBack() {
            return rolledBack;
        }

        private int getByteCount() {
            return bytes.size();
        }

        private int getMaximumWrittenBytes() {
            return maximumWrittenBytes;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) {
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
