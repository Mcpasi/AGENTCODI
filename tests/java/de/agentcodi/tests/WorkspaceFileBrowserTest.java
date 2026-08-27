package de.agentcodi.tests;

import de.agentcodi.browser.WorkspaceBrowserEntry;
import de.agentcodi.browser.WorkspaceBrowserLimits;
import de.agentcodi.browser.WorkspaceBrowserPage;
import de.agentcodi.browser.WorkspaceFilePreview;
import de.agentcodi.browser.client.WorkspaceFileBrowser;
import de.agentcodi.storage.WorkspaceDirectoryCatalog;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public final class WorkspaceFileBrowserTest {
    private WorkspaceFileBrowserTest() {
    }

    public static int run() throws Exception {
        pagesDirectoriesBeforeFilesDeterministically();
        buildsNestedBreadcrumbNavigation();
        clampsAStaleDirectoryPageAfterChanges();
        pagesUtf8TextContent();
        classifiesBinaryBytesBeyondTheInitialTextProbe();
        rendersBinaryContentAsBoundedHex();
        previewsAnEmptyFile();
        validatesAndReturnsImageBytes();
        rejectsMalformedPngPreview();
        keepsSymbolicEntriesVisibleWithoutBlockingRegularFiles();
        keepsHardLinksVisibleButUnavailable();
        reportsCatalogTruncationWithoutDiscardingEntries();
        rejectsUnsafeNavigationPaths();
        keepsPreviewAndPageContractsImmutable();
        return 14;
    }

    private static void pagesDirectoriesBeforeFilesDeterministically() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-pages-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath().resolve("catalog");
            Files.createDirectory(workspace);
            Files.createDirectory(workspace.resolve("z-folder"));
            Files.createDirectory(workspace.resolve("A-folder"));
            Files.write(workspace.resolve("z.txt"), "z".getBytes("UTF-8"));
            Files.write(workspace.resolve("a.txt"), "a".getBytes("UTF-8"));
            WorkspaceFileBrowser browser = browser(layout);

            WorkspaceBrowserPage first = browser.list("catalog", 0, 2);
            TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(first.getPageCount()), "page count");
            TestSupport.assertEquals("A-folder", first.getEntries().get(0).getDisplayName(), "folded folder order");
            TestSupport.assertEquals("z-folder", first.getEntries().get(1).getDisplayName(), "folders precede files");
            TestSupport.assertEquals(
                WorkspaceBrowserEntry.Kind.DIRECTORY,
                first.getEntries().get(0).getKind(),
                "directory kind"
            );

            WorkspaceBrowserPage second = browser.list("catalog", 1, 2);
            TestSupport.assertEquals("a.txt", second.getEntries().get(0).getDisplayName(), "file order");
            TestSupport.assertEquals("z.txt", second.getEntries().get(1).getDisplayName(), "second file order");
            TestSupport.assertTrue(second.hasPreviousPage(), "second page has previous");
            TestSupport.assertTrue(!second.hasNextPage(), "second page is terminal");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void buildsNestedBreadcrumbNavigation() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-breadcrumbs-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path nested = layout.getWorkspace().toPath().resolve("alpha/beta");
            Files.createDirectories(nested);
            Files.write(nested.resolve("note.md"), "note".getBytes("UTF-8"));
            WorkspaceBrowserPage page = browser(layout).list("alpha/beta", 0);
            TestSupport.assertEquals("alpha", page.getParentRelativeDirectory(), "parent path");
            TestSupport.assertEquals(Integer.valueOf(3), Integer.valueOf(page.getBreadcrumbs().size()), "breadcrumb count");
            TestSupport.assertEquals("", page.getBreadcrumbs().get(0).getRelativePath(), "root crumb");
            TestSupport.assertEquals("alpha/beta", page.getBreadcrumbs().get(2).getRelativePath(), "leaf crumb");
            TestSupport.assertEquals("alpha/beta/note.md", page.getEntries().get(0).getRelativePath(), "nested file path");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void clampsAStaleDirectoryPageAfterChanges() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-clamp-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path catalog = layout.getWorkspace().toPath().resolve("catalog");
            Files.createDirectory(catalog);
            Files.write(catalog.resolve("only.txt"), new byte[] {'x'});
            WorkspaceBrowserPage page = browser(layout).list("catalog", 500, 2);
            TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(page.getPageIndex()), "stale page clamps");
            TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(page.getEntries().size()), "entry remains visible");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void pagesUtf8TextContent() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-text-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            byte[] boundary = "🚀second-content-page".getBytes("UTF-8");
            byte[] text = new byte[32 * 1024 - 1 + boundary.length];
            Arrays.fill(text, 0, 32 * 1024 - 1, (byte) 'a');
            System.arraycopy(boundary, 0, text, 32 * 1024 - 1, boundary.length);
            Path source = layout.getWorkspace().toPath().resolve("long.txt");
            Files.write(source, text);

            WorkspaceFilePreview first = browser(layout).preview("long.txt", 0);
            WorkspaceFilePreview second = browser(layout).preview("long.txt", 1);
            TestSupport.assertEquals(WorkspaceFilePreview.Kind.TEXT, first.getKind(), "text kind");
            TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(first.getPageCount()), "text pages");
            TestSupport.assertEquals(Long.valueOf(32L * 1024L), Long.valueOf(second.getByteOffset()), "second byte offset");
            TestSupport.assertTrue(second.getRenderedContent().startsWith("🚀second-content-page"), "UTF-8 boundary is lossless");
            TestSupport.assertTrue(second.hasPreviousPage(), "second content has previous");

        } finally {
            deleteRecursively(base);
        }
    }

    private static void classifiesBinaryBytesBeyondTheInitialTextProbe()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-late-binary-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            byte[] invalidTail = new byte[WorkspaceBrowserLimits.TEXT_PROBE_BYTES + 1];
            Arrays.fill(
                invalidTail,
                0,
                WorkspaceBrowserLimits.TEXT_PROBE_BYTES,
                (byte) 'x'
            );
            invalidTail[invalidTail.length - 1] = (byte) 0xc2;
            Files.write(
                layout.getWorkspace().toPath().resolve("invalid-tail.bin"),
                invalidTail
            );

            WorkspaceFilePreview first = browser(layout).preview("invalid-tail.bin", 0);
            WorkspaceFilePreview tail = browser(layout).preview("invalid-tail.bin", 2);
            TestSupport.assertEquals(
                WorkspaceFilePreview.Kind.BINARY,
                first.getKind(),
                "invalid UTF-8 beyond the probe is binary"
            );
            TestSupport.assertEquals(
                WorkspaceFilePreview.Kind.BINARY,
                tail.getKind(),
                "binary classification is stable across pages"
            );
            TestSupport.assertEquals(
                Long.valueOf(2L * WorkspaceBrowserLimits.BINARY_PAGE_BYTES),
                Long.valueOf(tail.getByteOffset()),
                "binary tail page uses binary paging"
            );
            TestSupport.assertTrue(
                tail.getRenderedContent().contains("c2"),
                "invalid UTF-8 tail remains viewable as hex"
            );

            byte[] delayedNul = new byte[WorkspaceBrowserLimits.TEXT_PAGE_BYTES + 32];
            Arrays.fill(delayedNul, (byte) 'a');
            delayedNul[WorkspaceBrowserLimits.TEXT_PAGE_BYTES + 17] = 0;
            Files.write(
                layout.getWorkspace().toPath().resolve("delayed-nul.bin"),
                delayedNul
            );
            WorkspaceFilePreview delayed = browser(layout).preview(
                "delayed-nul.bin",
                0
            );
            TestSupport.assertEquals(
                WorkspaceFilePreview.Kind.BINARY,
                delayed.getKind(),
                "NUL beyond the first text page is binary"
            );
            TestSupport.assertEquals(
                Integer.valueOf(
                    1 + (delayedNul.length - 1)
                        / WorkspaceBrowserLimits.BINARY_PAGE_BYTES
                ),
                Integer.valueOf(delayed.getPageCount()),
                "late binary content keeps binary page count"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rendersBinaryContentAsBoundedHex() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-binary-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(
                layout.getWorkspace().toPath().resolve("payload.bin"),
                new byte[] {0, 1, 2, 0x41, (byte) 0xff}
            );
            WorkspaceFilePreview preview = browser(layout).preview("payload.bin", 0);
            TestSupport.assertEquals(WorkspaceFilePreview.Kind.BINARY, preview.getKind(), "binary kind");
            TestSupport.assertTrue(preview.getRenderedContent().contains("00 01 02 41 ff"), "hex bytes");
            TestSupport.assertTrue(preview.getRenderedContent().contains("...A."), "hex ASCII rail");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void previewsAnEmptyFile() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-empty-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getWorkspace().toPath().resolve("empty.txt"), new byte[0]);
            WorkspaceFilePreview preview = browser(layout).preview("empty.txt", 99);
            TestSupport.assertEquals(WorkspaceFilePreview.Kind.TEXT, preview.getKind(), "empty file kind");
            TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(preview.getPageCount()), "empty page count");
            TestSupport.assertEquals("", preview.getRenderedContent(), "empty preview content");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void validatesAndReturnsImageBytes() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-image-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            byte[] expected = pngFixture();
            Files.write(layout.getWorkspace().toPath().resolve("image.data"), expected);
            WorkspaceFilePreview preview = browser(layout).preview("image.data", 0);
            TestSupport.assertEquals(WorkspaceFilePreview.Kind.IMAGE, preview.getKind(), "image kind");
            TestSupport.assertEquals("image/png", preview.getMimeType(), "image sniffing ignores extension");
            TestSupport.assertTrue(Arrays.equals(expected, preview.getImageBytes()), "validated image bytes");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsMalformedPngPreview() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-browser-bad-png-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(
                layout.getWorkspace().toPath().resolve("bad.png"),
                new byte[] {
                    (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a,
                    0, 0, 0, 0, 'B', 'A', 'D'
                }
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        browser(layout).preview("bad.png", 0);
                    }
                },
                "malformed PNG preview"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void keepsSymbolicEntriesVisibleWithoutBlockingRegularFiles()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-link-");
        Path outside = Files.createTempFile("agentcodi-browser-outside-", ".txt");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path workspace = layout.getWorkspace().toPath().resolve("catalog");
            Files.createDirectory(workspace);
            Files.write(workspace.resolve("regular.txt"), "regular".getBytes("UTF-8"));
            Files.write(workspace.resolve("   "), "blank-name".getBytes("UTF-8"));
            Files.write(workspace.resolve("emoji-🚀.txt"), "unicode-name".getBytes("UTF-8"));
            Files.createSymbolicLink(workspace.resolve("linked.txt"), outside);
            WorkspaceBrowserPage page = browser(layout).list("catalog", 0);
            TestSupport.assertEquals(Integer.valueOf(4), Integer.valueOf(page.getEntries().size()), "link and safe files visible");
            WorkspaceBrowserEntry unavailable = findEntry(page, "catalog/linked.txt");
            TestSupport.assertEquals(WorkspaceBrowserEntry.Kind.UNAVAILABLE, unavailable.getKind(), "link unavailable kind");
            TestSupport.assertEquals("symbolic-link", unavailable.getUnavailableReason(), "link reason");
            TestSupport.assertTrue(findEntry(page, "catalog/regular.txt").isOpenable(), "regular neighbor stays openable");
            WorkspaceBrowserEntry blank = findEntry(page, "catalog/   ");
            TestSupport.assertTrue(blank.isOpenable(), "safe blank-looking filename stays openable");
            TestSupport.assertEquals("[blank name]", blank.getDisplayName(), "blank-looking name is visible");
            WorkspaceFilePreview blankPreview = browser(layout).preview("catalog/   ", 0);
            TestSupport.assertEquals("[blank name]", blankPreview.getDisplayName(), "blank-looking file previews");
            WorkspaceFilePreview unicodePreview = browser(layout).preview(
                "catalog/emoji-🚀.txt",
                0
            );
            TestSupport.assertEquals(
                "emoji-🚀.txt",
                unicodePreview.getDisplayName(),
                "supplementary Unicode file previews"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void keepsHardLinksVisibleButUnavailable() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-hardlink-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path catalog = layout.getWorkspace().toPath().resolve("catalog");
            Files.createDirectory(catalog);
            Path first = catalog.resolve("first.bin");
            Files.write(first, new byte[] {1});
            Files.createLink(catalog.resolve("second.bin"), first);
            WorkspaceBrowserPage page = browser(layout).list("catalog", 0);
            TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(page.getEntries().size()), "both hard links shown");
            for (WorkspaceBrowserEntry entry : page.getEntries()) {
                TestSupport.assertEquals(WorkspaceBrowserEntry.Kind.UNAVAILABLE, entry.getKind(), "hard link unavailable");
                TestSupport.assertEquals("hard-link", entry.getUnavailableReason(), "hard link reason");
            }
        } finally {
            deleteRecursively(base);
        }
    }

    private static void reportsCatalogTruncationWithoutDiscardingEntries()
        throws Exception {
        Path base = Files.createTempDirectory("agentcodi-browser-truncated-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            WorkspaceDirectoryCatalog.Reader reader = new WorkspaceDirectoryCatalog.Reader() {
                @Override
                public WorkspaceDirectoryCatalog.Snapshot list(
                    java.io.File workspaceDirectory,
                    String relativeDirectory,
                    int maximumEntries,
                    int maximumRelativePathCharacters,
                    int maximumDepth
                ) {
                    return WorkspaceDirectoryCatalog.Snapshot.of(Arrays.asList(
                        WorkspaceDirectoryCatalog.Entry.regularFile(
                            "visible.txt",
                            "visible.txt",
                            7L,
                            0L
                        )
                    ), true);
                }
            };
            WorkspaceFileBrowser browser = new WorkspaceFileBrowser(
                layout.getWorkspace(),
                reader,
                WorkspaceFileAccess.secureNioOpener()
            );
            WorkspaceBrowserPage page = browser.list("", 0);
            TestSupport.assertTrue(page.isScanTruncated(), "truncation is projected");
            TestSupport.assertEquals("visible.txt", page.getEntries().get(0).getDisplayName(), "safe prefix remains");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsUnsafeNavigationPaths() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-browser-path-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        browser(layout).list("../codex-home", 0);
                    }
                },
                "directory parent traversal"
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        browser(layout).preview("imports/../../codex-home/auth.json", 0);
                    }
                },
                "file parent traversal"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void keepsPreviewAndPageContractsImmutable() throws Exception {
        byte[] image = pngFixture();
        WorkspaceFilePreview preview = WorkspaceFilePreview.image(
            "image.png",
            "image.png",
            "image/png",
            image.length,
            image
        );
        image[0] = 0;
        byte[] returned = preview.getImageBytes();
        returned[1] = 0;
        TestSupport.assertEquals(Integer.valueOf(0x89), Integer.valueOf(preview.getImageBytes()[0] & 0xff), "constructor copies bytes");
        TestSupport.assertEquals(Integer.valueOf('P'), Integer.valueOf(preview.getImageBytes()[1] & 0xff), "getter copies bytes");

        List<WorkspaceBrowserEntry> mutable = new java.util.ArrayList<WorkspaceBrowserEntry>();
        mutable.add(WorkspaceBrowserEntry.file("one", "one", 1L, 0L));
        WorkspaceBrowserPage page = new WorkspaceBrowserPage(
            "",
            "",
            Arrays.asList(new de.agentcodi.browser.WorkspaceBreadcrumb("", "")),
            mutable,
            0,
            1,
            1,
            false
        );
        mutable.clear();
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(page.getEntries().size()), "page copies entries");

        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    WorkspaceFilePreview.text(
                        "bad.txt",
                        "../bad.txt",
                        1L,
                        0L,
                        0,
                        1,
                        "x"
                    );
                }
            },
            "preview rejects traversal"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    WorkspaceFilePreview.binary(
                        "bad.bin",
                        "bad.bin",
                        "application/octet-stream",
                        4096L,
                        1L,
                        0,
                        2,
                        "00"
                    );
                }
            },
            "preview rejects inconsistent paging"
        );
        final byte[] validImage = pngFixture();
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    WorkspaceFilePreview.image(
                        "bad.png",
                        "bad.png",
                        "image/png",
                        validImage.length + 1L,
                        validImage
                    );
                }
            },
            "preview binds image byte count"
        );
    }

    private static WorkspaceFileBrowser browser(WorkspaceLayout layout) {
        return new WorkspaceFileBrowser(
            layout.getWorkspace(),
            WorkspaceDirectoryCatalog.secureNioReader(),
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    private static WorkspaceBrowserEntry findEntry(
        WorkspaceBrowserPage page,
        String relativePath
    ) {
        for (WorkspaceBrowserEntry entry : page.getEntries()) {
            if (relativePath.equals(entry.getRelativePath())) {
                return entry;
            }
        }
        throw new AssertionError("Missing browser entry: " + relativePath);
    }

    private static byte[] pngFixture() {
        byte[] pixels = new byte[] {0, 0x11, 0x22, 0x33, (byte) 0xff};
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try {
            deflater.setInput(pixels);
            deflater.finish();
            byte[] buffer = new byte[64];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                if (count <= 0) {
                    throw new IllegalStateException("PNG fixture deflater made no progress");
                }
                compressed.write(buffer, 0, count);
            }
        } finally {
            deflater.end();
        }
        byte[] ihdr = new byte[13];
        ihdr[3] = 1;
        ihdr[7] = 1;
        ihdr[8] = 8;
        ihdr[9] = 6;
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write((byte) 0x89);
        png.write('P');
        png.write('N');
        png.write('G');
        png.write(0x0d);
        png.write(0x0a);
        png.write(0x1a);
        png.write(0x0a);
        appendPngChunk(png, "IHDR", ihdr);
        appendPngChunk(png, "IDAT", compressed.toByteArray());
        appendPngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void appendPngChunk(
        ByteArrayOutputStream png,
        String type,
        byte[] data
    ) {
        int length = data.length;
        png.write((byte) (length >>> 24));
        png.write((byte) (length >>> 16));
        png.write((byte) (length >>> 8));
        png.write((byte) length);
        byte[] typeBytes = new byte[] {
            (byte) type.charAt(0),
            (byte) type.charAt(1),
            (byte) type.charAt(2),
            (byte) type.charAt(3)
        };
        png.write(typeBytes, 0, typeBytes.length);
        png.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes, 0, typeBytes.length);
        crc.update(data, 0, data.length);
        long value = crc.getValue();
        png.write((byte) (value >>> 24));
        png.write((byte) (value >>> 16));
        png.write((byte) (value >>> 8));
        png.write((byte) value);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(root)) {
            try (java.nio.file.DirectoryStream<Path> children =
                    Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(root);
    }
}
