package de.agentcodi.tests;

import de.agentcodi.storage.WorkspaceArchive;
import de.agentcodi.storage.WorkspaceExportFile;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        acceptsCanonicalWorkspaceAlias();
        rejectsFileOutsideWorkspace();
        rejectsSymbolicCatalogEntry();
        rejectsHardLinkToPrivateSibling();
        archivesCompleteWorkspaceWithoutCodexHome();
        createsValidEmptyWorkspaceArchive();
        rejectsArchiveFileAboveLimit();
        rejectsArchiveTotalAboveLimit();
        rejectsArchiveFileCountAboveLimit();
        rejectsUnsafePortableArchiveName();
        rejectsPortableArchiveNameCollision();
        rejectsWorkspaceMutationDuringArchive();
        return 14;
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

    private static void rejectsSymbolicCatalogEntry() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-symlink-");
        Path outside = Files.createTempFile("agentcodi-export-link-target-", ".txt");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.createSymbolicLink(
                layout.getWorkspace().toPath().resolve("linked.txt"),
                outside
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.list(layout.getWorkspace(), 10, 256, 8);
                    }
                },
                "catalog must reject symbolic entries"
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

    private static void rejectsUnsafePortableArchiveName() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-name-boundary-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(
                layout.getWorkspace().toPath().resolve("C:\\outside.bin"),
                new byte[] {1}
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(layout.getWorkspace(), 10, 10L, 10L, 256, 8);
                    }
                },
                "portable archive names reject drive and separator ambiguity"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsPortableArchiveNameCollision() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-export-name-collision-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getWorkspace().toPath().resolve("Report.bin"), new byte[] {1});
            Files.write(layout.getWorkspace().toPath().resolve("report.bin"), new byte[] {2});
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.inspect(layout.getWorkspace(), 10, 10L, 10L, 256, 8);
                    }
                },
                "case-insensitive archive collisions are rejected"
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
            final ByteArrayOutputStream destination = new ByteArrayOutputStream();
            OutputStream mutatingDestination = new OutputStream() {
                private boolean mutated;

                @Override
                public void write(int value) throws IOException {
                    mutateOnce();
                    destination.write(value);
                }

                @Override
                public void write(byte[] value, int offset, int length) throws IOException {
                    mutateOnce();
                    destination.write(value, offset, length);
                }

                private void mutateOnce() throws IOException {
                    if (mutated) {
                        return;
                    }
                    mutated = true;
                    Files.move(source, source.resolveSibling("source-before-export.bin"));
                    Files.write(source, new byte[] {9, 8, 7, 6});
                }
            };
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceArchive.write(
                            layout.getWorkspace(),
                            mutatingDestination,
                            10,
                            1024L,
                            4096L,
                            256,
                            8
                        );
                    }
                },
                "workspace mutation during archive must be detected"
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
