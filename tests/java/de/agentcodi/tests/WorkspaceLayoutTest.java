package de.agentcodi.tests;

import de.agentcodi.storage.WorkspaceLayout;

import java.io.File;
import java.io.IOException;
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
        return 6;
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
