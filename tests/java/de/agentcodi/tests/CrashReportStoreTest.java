package de.agentcodi.tests;

import de.agentcodi.storage.CrashReportStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CrashReportStoreTest {
    private CrashReportStoreTest() {
    }

    public static int run() throws Exception {
        writesReadsAndClearsReport();
        boundsLargeReports();
        rejectsSymbolicReportPath();
        remainsAvailableWhenRuntimeLayoutIsInvalid();
        return 4;
    }

    private static void writesReadsAndClearsReport() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-crash-store-");
        try {
            CrashReportStore store = CrashReportStore.open(base.toFile());
            store.write("diagnostic report");
            TestSupport.assertEquals("diagnostic report", store.read(), "round trip");
            store.clear();
            TestSupport.assertEquals("", store.read(), "clear");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void boundsLargeReports() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-crash-bound-");
        try {
            CrashReportStore store = CrashReportStore.open(base.toFile());
            StringBuilder large = new StringBuilder();
            for (int index = 0; index < 100000; index++) {
                large.append('x');
            }
            store.write(large.toString());
            String result = store.read();
            TestSupport.assertTrue(
                result.getBytes("UTF-8").length <= 24 * 1024,
                "stored report byte limit"
            );
            TestSupport.assertContains(result, "report truncated", "truncation marker");
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsSymbolicReportPath() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-crash-symlink-");
        Path outside = Files.createTempFile("agentcodi-crash-outside-", ".txt");
        try {
            Path state = base.resolve("agentcodi").resolve("state");
            Files.createDirectories(state);
            Files.createSymbolicLink(state.resolve("last-crash.txt"), outside);
            final CrashReportStore store = CrashReportStore.open(base.toFile());
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        store.write("must not follow");
                    }
                },
                "symbolic report path"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
        }
    }

    private static void remainsAvailableWhenRuntimeLayoutIsInvalid() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-crash-independent-");
        try {
            Path root = base.resolve("agentcodi");
            Files.createDirectories(root);
            Files.write(root.resolve("workspace"), new byte[] {'x'});
            CrashReportStore store = CrashReportStore.open(base.toFile());
            store.write("recoverable diagnostic");
            TestSupport.assertEquals(
                "recoverable diagnostic",
                store.read(),
                "diagnostics remain readable with invalid runtime siblings"
            );
            store.clear();
            TestSupport.assertEquals(
                "",
                store.read(),
                "diagnostics remain clearable with invalid runtime siblings"
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
        File[] children = path.toFile().listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child.toPath());
            }
        }
        Files.deleteIfExists(path);
    }
}
