package de.agentcodi.tests;

import de.agentcodi.storage.WorkspaceLayout;
import de.agentcodi.storage.WorkspaceImageFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public final class WorkspaceLayoutTest {
    private WorkspaceLayoutTest() {
    }

    public static int run() throws Exception {
        createsStablePrivateLayout();
        rejectsFileAsBaseDirectory();
        rejectsSymbolicWorkspaceRoot();
        keepsCodexHomeSeparateAndPrivate();
        rejectsSymbolicCanonicalCredential();
        preservesExistingCanonicalCredential();
        acceptsSupportedWorkspaceImage();
        copiesValidatedWorkspaceImage();
        rejectsWorkspaceImageMutationDuringCopy();
        rejectsImageOutsideWorkspace();
        rejectsMissingWorkspaceImage();
        acceptsAliasOfWorkspaceRoot();
        rejectsSymbolicWorkspaceImage();
        rejectsUnsupportedWorkspaceFile();
        rejectsOversizedWorkspaceImage();
        return 15;
    }

    private static void createsStablePrivateLayout() throws Exception {
        Path temporary = Files.createTempDirectory("agentcodi-workspace-test-");
        try {
            WorkspaceLayout first = WorkspaceLayout.create(temporary.toFile());
            WorkspaceLayout second = WorkspaceLayout.create(temporary.toFile());
            TestSupport.assertTrue(first.getRoot().isDirectory(), "root directory");
            TestSupport.assertTrue(first.getWorkspace().isDirectory(), "workspace directory");
            TestSupport.assertTrue(first.getState().isDirectory(), "state directory");
            TestSupport.assertTrue(first.getLogs().isDirectory(), "logs directory");
            TestSupport.assertEquals(
                first.getWorkspace().getCanonicalPath(),
                second.getWorkspace().getCanonicalPath(),
                "layout should be idempotent"
            );
            TestSupport.assertTrue(
                first.getWorkspace().getCanonicalPath().startsWith(
                    temporary.toFile().getCanonicalPath() + File.separator
                ),
                "workspace must remain below app files"
            );
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void rejectsFileAsBaseDirectory() throws Exception {
        final Path file = Files.createTempFile("agentcodi-not-directory-", ".tmp");
        try {
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceLayout.create(file.toFile());
                    }
                },
                "file base should be rejected"
            );
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void rejectsSymbolicWorkspaceRoot() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-symlink-base-");
        Path target = Files.createTempDirectory("agentcodi-symlink-target-");
        try {
            Files.createSymbolicLink(base.resolve("agentcodi"), target);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceLayout.create(base.toFile());
                    }
                },
                "symbolic workspace root should be rejected"
            );
        } finally {
            deleteRecursively(base);
            deleteRecursively(target);
        }
    }

    private static void keepsCodexHomeSeparateAndPrivate() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-codex-home-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            String workspace = layout.getWorkspace().getCanonicalPath();
            String codexHome = layout.getCodexHome().getCanonicalPath();
            TestSupport.assertFalse(
                codexHome.startsWith(workspace + File.separator),
                "Codex home outside workspace"
            );
            TestSupport.assertFalse(
                workspace.startsWith(codexHome + File.separator),
                "workspace outside Codex home"
            );
            TestSupport.assertTrue(layout.getHome().isDirectory(), "private HOME exists");
            TestSupport.assertTrue(layout.getCodexHome().isDirectory(), "private CODEX_HOME exists");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsSymbolicCanonicalCredential() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-auth-symlink-");
        Path outside = Files.createTempFile("agentcodi-auth-outside-", ".json");
        try {
            Path codexHome = base.resolve("agentcodi").resolve("codex-home");
            Files.createDirectories(codexHome);
            Files.createSymbolicLink(codexHome.resolve("auth.json"), outside);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceLayout.create(base.toFile());
                    }
                },
                "symbolic canonical credential"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void preservesExistingCanonicalCredential() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-auth-preserve-");
        try {
            Path codexHome = base.resolve("agentcodi").resolve("codex-home");
            Files.createDirectories(codexHome);
            Path auth = codexHome.resolve("auth.json");
            byte[] marker = "{\"fixture\":true}".getBytes("UTF-8");
            Files.write(auth, marker);
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            TestSupport.assertEquals(
                "{\"fixture\":true}",
                new String(Files.readAllBytes(auth), "UTF-8"),
                "credential content remains untouched"
            );
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(auth);
            TestSupport.assertFalse(
                permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE),
                "credential is owner-only"
            );
            TestSupport.assertEquals(
                layout.getCodexHome().getCanonicalPath(),
                auth.getParent().toFile().getCanonicalPath(),
                "single canonical credential location"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void acceptsSupportedWorkspaceImage() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-image-valid-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path image = layout.getWorkspace().toPath().resolve("generated-image");
            Files.write(image, pngFixture());
            WorkspaceImageFile inspected = WorkspaceImageFile.inspect(
                layout.getWorkspace(),
                image.toString(),
                1024L
            );
            TestSupport.assertEquals("image/png", inspected.getMimeType(), "PNG MIME type");
            TestSupport.assertEquals(
                "generated-image.png",
                inspected.getDisplayName(),
                "safe export extension"
            );
            TestSupport.assertEquals(
                Long.valueOf(pngFixture().length),
                Long.valueOf(inspected.getByteCount()),
                "image byte count"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void copiesValidatedWorkspaceImage() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-image-copy-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path image = layout.getWorkspace().toPath().resolve("copy.png");
            byte[] expected = pngFixture();
            Files.write(image, expected);
            ByteArrayOutputStream destination = new ByteArrayOutputStream();
            WorkspaceImageFile copied = WorkspaceImageFile.copyTo(
                layout.getWorkspace(),
                image.toString(),
                1024L,
                destination
            );
            TestSupport.assertEquals("copy.png", copied.getDisplayName(), "copy display name");
            TestSupport.assertTrue(
                java.util.Arrays.equals(expected, destination.toByteArray()),
                "validated image copied exactly"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsWorkspaceImageMutationDuringCopy() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-copy-race-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path image = layout.getWorkspace().toPath().resolve("changing.png");
            Files.write(image, pngFixture());
            final ByteArrayOutputStream copiedBytes = new ByteArrayOutputStream();
            final OutputStream mutatingDestination = new OutputStream() {
                private boolean mutated;

                @Override
                public void write(int value) throws IOException {
                    mutateOnce();
                    copiedBytes.write(value);
                }

                @Override
                public void write(byte[] value, int offset, int length) throws IOException {
                    mutateOnce();
                    copiedBytes.write(value, offset, length);
                }

                private void mutateOnce() throws IOException {
                    if (mutated) {
                        return;
                    }
                    mutated = true;
                    Files.move(image, image.resolveSibling("changing-before-export.png"));
                    byte[] replacement = pngFixture();
                    replacement[replacement.length - 1] = 'B';
                    Files.write(image, replacement);
                }
            };
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.copyTo(
                            layout.getWorkspace(),
                            image.toString(),
                            1024L,
                            mutatingDestination
                        );
                    }
                },
                "image replacement during copy must be detected"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsImageOutsideWorkspace() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-outside-");
        final Path outside = Files.createTempFile("agentcodi-outside-image-", ".png");
        try {
            Files.write(outside, pngFixture());
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.inspect(
                            layout.getWorkspace(),
                            outside.toString(),
                            1024L
                        );
                    }
                },
                "outside workspace image"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void rejectsMissingWorkspaceImage() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-missing-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path missing = layout.getWorkspace().toPath().resolve("missing.png");
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.inspect(
                            layout.getWorkspace(),
                            missing.toString(),
                            1024L
                        );
                    }
                },
                "missing workspace image"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsSymbolicWorkspaceImage() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-symlink-");
        Path outside = Files.createTempFile("agentcodi-image-target-", ".png");
        try {
            Files.write(outside, pngFixture());
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path link = layout.getWorkspace().toPath().resolve("linked.png");
            Files.createSymbolicLink(link, outside);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.inspect(
                            layout.getWorkspace(),
                            link.toString(),
                            1024L
                        );
                    }
                },
                "symbolic workspace image"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void acceptsAliasOfWorkspaceRoot() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-image-root-alias-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path image = layout.getWorkspace().toPath().resolve("aliased.png");
            Files.write(image, pngFixture());
            Path alias = base.resolve("android-files-alias");
            Files.createSymbolicLink(alias, layout.getWorkspace().toPath());
            WorkspaceImageFile inspected = WorkspaceImageFile.inspect(
                layout.getWorkspace(),
                alias.resolve("aliased.png").toString(),
                1024L
            );
            TestSupport.assertEquals(
                image.toFile().getCanonicalPath(),
                inspected.getFile().getCanonicalPath(),
                "platform alias resolves to canonical workspace image"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsUnsupportedWorkspaceFile() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-invalid-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path text = layout.getWorkspace().toPath().resolve("not-image.png");
            Files.write(text, "not an image".getBytes("UTF-8"));
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.inspect(
                            layout.getWorkspace(),
                            text.toString(),
                            1024L
                        );
                    }
                },
                "unsupported workspace image"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsOversizedWorkspaceImage() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-large-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path image = layout.getWorkspace().toPath().resolve("large.png");
            Files.write(image, pngFixture());
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.inspect(
                            layout.getWorkspace(),
                            image.toString(),
                            8L
                        );
                    }
                },
                "oversized workspace image"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static byte[] pngFixture() {
        return new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00, 'D', 'A', 'T', 'A'
        };
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        File file = path.toFile();
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child.toPath());
            }
        }
        Files.deleteIfExists(path);
    }
}
