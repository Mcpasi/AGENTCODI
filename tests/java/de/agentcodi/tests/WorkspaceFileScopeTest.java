package de.agentcodi.tests;

import de.agentcodi.browser.WorkspaceBrowserEntry;
import de.agentcodi.browser.WorkspaceFilePreview;
import de.agentcodi.browser.client.WorkspaceFileBrowser;
import de.agentcodi.storage.WorkspaceArchive;
import de.agentcodi.storage.WorkspaceDirectoryCatalog;
import de.agentcodi.storage.WorkspaceExportFile;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceFileScope;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class WorkspaceFileScopeTest {
    private WorkspaceFileScopeTest() {
    }

    public static int run() throws Exception {
        selectsOnlyExplicitPackageRoots();
        previewsAndCopiesEachPackagePrefix();
        excludesAccountLinksAndCredentialPathsFromArchives();
        rejectsTraversalAndDirectCredentialAccess();
        rejectsReplacedPackageRoots();
        return 5;
    }

    private static void selectsOnlyExplicitPackageRoots() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-file-scope-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            TestSupport.assertEquals(layout.getWorkspace(),
                WorkspaceFileScope.WORKSPACE.root(layout), "workspace remains the default area");
            TestSupport.assertEquals(layout.getPackagePrefix(),
                WorkspaceFileScope.MANAGED_PACKAGES.root(layout), "managed APT prefix");
            TestSupport.assertEquals(new File(layout.getHome(), ".local"),
                WorkspaceFileScope.USER_PACKAGES.root(layout), "only the user prefix, not HOME");
            for (WorkspaceFileScope scope : WorkspaceFileScope.values()) {
                Path root = scope.root(layout).toPath();
                TestSupport.assertFalse(layout.getCodexHome().toPath().startsWith(root),
                    "account data cannot enter any file area");
            }
            Files.write(layout.getPackagePrefix().toPath().resolve("bin/tool"), new byte[] {42});
            TestSupport.assertTrue(browser(layout, WorkspaceFileScope.WORKSPACE)
                .list("", 0).getEntries().size() == 1, "workspace contains only imports, without a package mount or retired toolchain");
            TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(
                archive(layout, WorkspaceFileScope.WORKSPACE, new ByteArrayOutputStream())
                    .getFileCount()), "workspace ZIP excludes installed package files");
        } finally {
            delete(base);
        }
    }

    private static void previewsAndCopiesEachPackagePrefix() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-package-preview-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            for (WorkspaceFileScope scope : new WorkspaceFileScope[] {
                    WorkspaceFileScope.MANAGED_PACKAGES, WorkspaceFileScope.USER_PACKAGES}) {
                File root = scope.root(layout);
                String expected = "file from " + scope.name();
                Path file = root.toPath().resolve("bin/example");
                Files.write(file, expected.getBytes("UTF-8"));
                WorkspaceFilePreview preview = browser(layout, scope).preview("bin/example", 0);
                TestSupport.assertEquals(expected, preview.getRenderedContent(),
                    "preview reads the selected package prefix");
                ByteArrayOutputStream copied = new ByteArrayOutputStream();
                WorkspaceExportFile.copyTo(root, file.toString(), 1024L, copied,
                    scope.opener(WorkspaceFileAccess.secureNioOpener()));
                TestSupport.assertEquals(expected, copied.toString("UTF-8"),
                    "explicit file export copies the selected bytes");
            }
        } finally {
            delete(base);
        }
    }

    private static void excludesAccountLinksAndCredentialPathsFromArchives() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-package-account-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path account = layout.getCodexHome().toPath().resolve("auth.json");
            Files.write(account, "synthetic-account-fixture".getBytes("UTF-8"));
            for (WorkspaceFileScope scope : new WorkspaceFileScope[] {
                    WorkspaceFileScope.MANAGED_PACKAGES, WorkspaceFileScope.USER_PACKAGES}) {
                Path root = scope.root(layout).toPath();
                Files.write(root.resolve("bin/safe"), new byte[] {7, 8});
                Files.createSymbolicLink(root.resolve("account-link"), account);
                Files.createSymbolicLink(root.resolve("account-directory"), account.getParent());
                Files.createLink(root.resolve("account-hardlink"), account);
                Files.write(root.resolve("auth.json"), new byte[] {1});
                Files.createDirectories(root.resolve("etc/apt/auth.conf.d"));
                Files.write(root.resolve("etc/apt/auth.conf.d/private"), new byte[] {2});
                Files.write(root.resolve("etc/apt/auth.conf"), new byte[] {3});
                Files.write(root.resolve(".npmrc"), new byte[] {4});
                boolean credentialSeen = false;
                for (WorkspaceBrowserEntry entry : browser(layout, scope).list("", 0).getEntries()) {
                    if ("auth.json".equals(entry.getDisplayName())) {
                        credentialSeen = true;
                        TestSupport.assertFalse(entry.isOpenable(), "credential is unavailable");
                        TestSupport.assertEquals(WorkspaceFileScope.REASON_CREDENTIAL,
                            entry.getUnavailableReason(), "credential reason");
                    }
                }
                TestSupport.assertTrue(credentialSeen, "excluded credential is explained in catalog");
                ByteArrayOutputStream zip = new ByteArrayOutputStream();
                WorkspaceArchive.Summary summary = archive(layout, scope, zip);
                TestSupport.assertEquals(Integer.valueOf(1),
                    Integer.valueOf(summary.getFileCount()), "only safe file enters package ZIP");
                TestSupport.assertTrue(summary.getOmittedEntryCount() >= 6,
                    "omitted links and credential paths are counted");
                Set<String> names = new HashSet<String>();
                try (ZipInputStream input = new ZipInputStream(
                        new ByteArrayInputStream(zip.toByteArray()))) {
                    ZipEntry entry;
                    while ((entry = input.getNextEntry()) != null) {
                        names.add(entry.getName());
                    }
                }
                TestSupport.assertEquals(java.util.Collections.singleton("bin/safe"), names,
                    "ZIP has no account link or credential member");
            }
        } finally {
            delete(base);
        }
    }

    private static void rejectsTraversalAndDirectCredentialAccess() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-package-paths-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            for (WorkspaceFileScope scope : new WorkspaceFileScope[] {
                    WorkspaceFileScope.MANAGED_PACKAGES, WorkspaceFileScope.USER_PACKAGES}) {
                final WorkspaceFileBrowser browser = browser(layout, scope);
                for (final String path : new String[] {
                        "../agentcodi/codex-home/auth.json", "/auth.json",
                        "etc/apt/auth.conf", "etc/apt/auth.conf.d/private",
                        "AUTH.JSON", ".codex/auth.json", ".ssh/id_rsa", ".pypirc", ".netrc"}) {
                    TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
                        @Override
                        public void run() throws Exception {
                            browser.preview(path, 0);
                        }
                    }, "unsafe or credential preview rejected");
                }
                final File root = scope.root(layout);
                final WorkspaceFileAccess.Opener opener =
                    scope.opener(WorkspaceFileAccess.secureNioOpener());
                Files.write(root.toPath().resolve("auth.json"), new byte[] {1});
                TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceExportFile.inspect(root, new File(root, "auth.json").getPath(),
                            1024L, opener);
                    }
                }, "direct single-file export rejects credentials");
                TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        browser.list("etc/apt/auth.conf.d", 0);
                    }
                }, "credential directory cannot be selected as ZIP root");
            }
        } finally {
            delete(base);
        }
    }

    private static void rejectsReplacedPackageRoots() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-package-root-link-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            for (final WorkspaceFileScope scope : new WorkspaceFileScope[] {
                    WorkspaceFileScope.MANAGED_PACKAGES, WorkspaceFileScope.USER_PACKAGES}) {
                final WorkspaceFileBrowser browser = browser(layout, scope);
                Path root = scope.root(layout).toPath();
                Path saved = root.resolveSibling(root.getFileName() + "-saved");
                Files.move(root, saved);
                Files.createSymbolicLink(root, layout.getCodexHome().toPath());
                TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        scope.root(layout);
                    }
                }, "replaced scope root is rejected");
                TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        browser.list("", 0);
                    }
                }, "existing browser does not follow a replacement root");
                Files.delete(root);
                Files.move(saved, root);
            }
        } finally {
            delete(base);
        }
    }

    private static WorkspaceFileBrowser browser(WorkspaceLayout layout, WorkspaceFileScope scope)
        throws IOException {
        return new WorkspaceFileBrowser(scope.root(layout),
            scope.reader(WorkspaceDirectoryCatalog.secureNioReader()),
            scope.opener(WorkspaceFileAccess.secureNioOpener()));
    }

    private static WorkspaceArchive.Summary archive(
        WorkspaceLayout layout, WorkspaceFileScope scope, ByteArrayOutputStream destination
    ) throws IOException {
        return WorkspaceArchive.write(scope.root(layout), "", destination,
            2048, 65536, 512L * 1024L * 1024L, 1024L * 1024L * 1024L, 2048, 64,
            scope.reader(WorkspaceDirectoryCatalog.secureNioReader()),
            scope.opener(WorkspaceFileAccess.secureNioOpener()));
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    delete(child);
                }
            }
        }
        Files.delete(path);
    }
}
