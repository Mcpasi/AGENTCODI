package de.agentcodi.tests;

import de.agentcodi.storage.WorkspaceLayout;
import de.agentcodi.storage.WorkspaceImageFile;
import de.agentcodi.storage.WorkspaceFileAccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WorkspaceLayoutTest {
    private WorkspaceLayoutTest() {
    }

    public static int run() throws Exception {
        createsStablePrivateLayout();
        preparesPackagedToolAliases();
        rejectsUnexpectedPackagedToolEntries();
        preparesVerifiedPackagedToolRuntime();
        rejectsUnsafePackagedToolManifest();
        reportsValidatedNodeActivationState();
        rejectsFileAsBaseDirectory();
        rejectsSymbolicWorkspaceRoot();
        rejectsSymbolicToolchainRoot();
        keepsCodexHomeSeparateAndPrivate();
        rejectsSymbolicCanonicalCredential();
        preservesExistingCanonicalCredential();
        preservesPrivateCodexConfigurationFiles();
        rejectsSymbolicCodexConfigurationFile();
        acceptsSupportedWorkspaceImage();
        acceptsAdam7WorkspaceImage();
        copiesValidatedWorkspaceImage();
        rejectsWorkspaceImageMutationDuringCopy();
        rejectsImageSymlinkSwapBeforeOpenWithoutWritingBytes();
        rejectsImageOutsideWorkspace();
        rejectsMissingWorkspaceImage();
        acceptsAliasOfWorkspaceRoot();
        rejectsSymbolicWorkspaceImage();
        rejectsUnsupportedWorkspaceFile();
        rejectsPngSignatureFollowedByGarbage();
        rejectsPngWithInvalidDimensions();
        rejectsPngChunkOutsideFileBoundary();
        rejectsPngWithInvalidChunkCrc();
        rejectsPngWithCorruptImageData();
        rejectsPngWithIncompleteScanlineShape();
        rejectsPngWithInvalidScanlineFilter();
        rejectsPngWithoutIend();
        rejectsPngBytesAfterIend();
        rejectsMalformedPngDuringCopy();
        rejectsOversizedWorkspaceImage();
        return 35;
    }

    private static void createsStablePrivateLayout() throws Exception {
        Path temporary = Files.createTempDirectory("agentcodi-workspace-test-");
        try {
            WorkspaceLayout first = WorkspaceLayout.create(temporary.toFile());
            WorkspaceLayout second = WorkspaceLayout.create(temporary.toFile());
            TestSupport.assertTrue(first.getRoot().isDirectory(), "root directory");
            TestSupport.assertTrue(first.getWorkspace().isDirectory(), "workspace directory");
            TestSupport.assertTrue(first.getImports().isDirectory(), "imports directory");
            TestSupport.assertTrue(first.getToolchain().isDirectory(), "toolchain directory");
            TestSupport.assertTrue(first.getToolBin().isDirectory(), "tool binary directory");
            TestSupport.assertTrue(first.getToolRuntime().isDirectory(), "tool runtime directory");
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
            TestSupport.assertTrue(
                first.getImports().getCanonicalPath().startsWith(
                    first.getWorkspace().getCanonicalPath() + File.separator
                ),
                "imports must remain below workspace"
            );
            TestSupport.assertTrue(
                first.getToolchain().getCanonicalPath().startsWith(
                    first.getWorkspace().getCanonicalPath() + File.separator
                ),
                "toolchain must remain below workspace"
            );
            TestSupport.assertFalse(
                first.getToolBin().getCanonicalPath().startsWith(
                    first.getWorkspace().getCanonicalPath() + File.separator
                ),
                "tool aliases must remain outside the writable workspace"
            );
            TestSupport.assertFalse(
                first.getToolRuntime().getCanonicalPath().startsWith(
                    first.getWorkspace().getCanonicalPath() + File.separator
                ),
                "packaged tool runtime must remain outside the writable workspace"
            );
        } finally {
            deleteRecursively(temporary);
        }
    }

    private static void preparesPackagedToolAliases() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-tool-alias-base-");
        Path firstShell = Files.createTempFile("agentcodi-shell-first-", ".bin");
        Path secondShell = Files.createTempFile("agentcodi-shell-second-", ".bin");
        try {
            TestSupport.assertTrue(firstShell.toFile().setExecutable(true, true), "first shell mode");
            TestSupport.assertTrue(secondShell.toFile().setExecutable(true, true), "second shell mode");
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            layout.preparePackagedToolAliases(firstShell.toFile());
            Path node = layout.getToolBin().toPath().resolve(WorkspaceLayout.NODE_TOOL_ALIAS);
            Path npm = layout.getToolBin().toPath().resolve(WorkspaceLayout.NPM_TOOL_ALIAS);
            Path python = layout.getToolBin().toPath().resolve(
                WorkspaceLayout.PYTHON_TOOL_ALIAS
            );
            Path python3 = layout.getToolBin().toPath().resolve(
                WorkspaceLayout.PYTHON3_TOOL_ALIAS
            );
            Path ripgrep = layout.getToolBin().toPath().resolve(
                WorkspaceLayout.RIPGREP_TOOL_ALIAS
            );
            Path toolchain = layout.getToolBin().toPath().resolve(
                WorkspaceLayout.TOOLCHAIN_TOOL_ALIAS
            );
            TestSupport.assertTrue(Files.isSymbolicLink(node), "Node tool alias");
            TestSupport.assertTrue(Files.isSymbolicLink(npm), "npm tool alias");
            TestSupport.assertTrue(Files.isSymbolicLink(python), "Python tool alias");
            TestSupport.assertTrue(Files.isSymbolicLink(python3), "Python 3 tool alias");
            TestSupport.assertTrue(Files.isSymbolicLink(ripgrep), "ripgrep tool alias");
            TestSupport.assertTrue(Files.isSymbolicLink(toolchain), "toolchain command alias");
            TestSupport.assertEquals(
                firstShell.toRealPath(),
                node.toRealPath(),
                "Node alias target"
            );
            TestSupport.assertEquals(firstShell.toRealPath(), npm.toRealPath(), "npm alias target");
            TestSupport.assertEquals(
                firstShell.toRealPath(),
                python.toRealPath(),
                "Python alias target"
            );
            TestSupport.assertEquals(
                firstShell.toRealPath(),
                python3.toRealPath(),
                "Python 3 alias target"
            );
            TestSupport.assertEquals(
                firstShell.toRealPath(),
                ripgrep.toRealPath(),
                "ripgrep alias target"
            );
            layout.preparePackagedToolAliases(firstShell.toFile());
            layout.preparePackagedToolAliases(secondShell.toFile());
            TestSupport.assertEquals(
                secondShell.toRealPath(),
                node.toRealPath(),
                "stale aliases are replaced during a verified app update"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(firstShell);
            Files.deleteIfExists(secondShell);
        }
    }

    private static void preparesVerifiedPackagedToolRuntime() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-packaged-runtime-base-");
        Path nativeDirectory = Files.createTempDirectory("agentcodi-packaged-runtime-native-");
        try {
            Path nativeExtension = nativeDirectory.resolve("libpython_ext_000.so");
            byte[] nativeBytes = "verified-native-extension".getBytes("US-ASCII");
            Files.write(nativeExtension, nativeBytes);
            TestSupport.assertTrue(
                nativeExtension.toFile().setExecutable(true, true),
                "native extension executable mode"
            );
            byte[] npmBytes = "verified npm runtime".getBytes("UTF-8");
            byte[] pythonBytes = new byte[] {0x42, 0x0d, 0x0d, 0x0a};
            String manifest = "AGENTCODI_TOOL_RUNTIME_V1\n"
                + "F\t" + npmBytes.length + "\t" + sha256(npmBytes)
                + "\tnpm/node_modules/npm/bin/npm-cli.js\n"
                + "F\t" + pythonBytes.length + "\t" + sha256(pythonBytes)
                + "\tpython/lib/python3.14/encodings/__init__.pyc\n"
                + "L\t" + sha256(nativeBytes) + "\tlibpython_ext_000.so"
                + "\tpython/lib/python3.14/lib-dynload/"
                + "_ssl.cpython-314-aarch64-linux-android.so\n";
            byte[] archive = runtimeArchive(npmBytes, pythonBytes);
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            File runtime = layout.preparePackagedToolRuntime(
                "python-3.14.6-npm-11.19.0",
                new ByteArrayInputStream(archive),
                new ByteArrayInputStream(manifest.getBytes("UTF-8")),
                nativeDirectory.toFile()
            );
            Path npmCli = runtime.toPath().resolve("npm/node_modules/npm/bin/npm-cli.js");
            Path pythonEncoding = runtime.toPath().resolve(
                "python/lib/python3.14/encodings/__init__.pyc"
            );
            Path extension = runtime.toPath().resolve(
                "python/lib/python3.14/lib-dynload/"
                    + "_ssl.cpython-314-aarch64-linux-android.so"
            );
            TestSupport.assertTrue(Files.isRegularFile(npmCli), "verified npm runtime file");
            TestSupport.assertTrue(
                Files.getPosixFilePermissions(npmCli).equals(
                    EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                    )
                ),
                "runtime files are owner-only"
            );
            TestSupport.assertTrue(Files.isSymbolicLink(extension), "native extension alias");
            TestSupport.assertEquals(
                nativeExtension.toRealPath(),
                extension.toRealPath(),
                "native extension target"
            );
            Files.setPosixFilePermissions(
                pythonEncoding,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
            );
            Files.write(pythonEncoding, new byte[] {1, 2, 3});
            File repaired = layout.preparePackagedToolRuntime(
                "python-3.14.6-npm-11.19.0",
                new ByteArrayInputStream(archive),
                new ByteArrayInputStream(manifest.getBytes("UTF-8")),
                nativeDirectory.toFile()
            );
            TestSupport.assertEquals(
                sha256(pythonBytes),
                sha256(Files.readAllBytes(repaired.toPath().resolve(
                    "python/lib/python3.14/encodings/__init__.pyc"
                ))),
                "corrupt packaged runtime is replaced from the verified archive"
            );
        } finally {
            deleteRecursively(base);
            deleteRecursively(nativeDirectory);
        }
    }

    private static void rejectsUnsafePackagedToolManifest() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-runtime-manifest-base-");
        final Path nativeDirectory = Files.createTempDirectory(
            "agentcodi-runtime-manifest-native-"
        );
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final byte[] payload = new byte[] {1};
            final String manifest = "AGENTCODI_TOOL_RUNTIME_V1\nF\t1\t"
                + sha256(payload) + "\t../escaped\n";
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        layout.preparePackagedToolRuntime(
                            "unsafe-runtime",
                            new ByteArrayInputStream(payload),
                            new ByteArrayInputStream(manifest.getBytes("UTF-8")),
                            nativeDirectory.toFile()
                        );
                    }
                },
                "unsafe packaged runtime manifest path"
            );
        } finally {
            deleteRecursively(base);
            deleteRecursively(nativeDirectory);
        }
    }

    private static byte[] runtimeArchive(byte[] npmBytes, byte[] pythonBytes)
        throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("npm/node_modules/npm/bin/npm-cli.js"));
            zip.write(npmBytes);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(
                "python/lib/python3.14/encodings/__init__.pyc"
            ));
            zip.write(pythonBytes);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder encoded = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            encoded.append(String.format("%02x", Integer.valueOf(value & 0xff)));
        }
        return encoded.toString();
    }

    private static void rejectsUnexpectedPackagedToolEntries() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-tool-entry-base-");
        Path shell = Files.createTempFile("agentcodi-tool-entry-shell-", ".bin");
        try {
            TestSupport.assertTrue(shell.toFile().setExecutable(true, true), "tool entry shell mode");
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Files.write(layout.getToolBin().toPath().resolve("unexpected"), new byte[] {1});
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        layout.preparePackagedToolAliases(shell.toFile());
                    }
                },
                "unexpected packaged tool entry"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(shell);
        }
    }

    private static void reportsValidatedNodeActivationState() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-node-state-base-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            TestSupport.assertFalse(
                layout.isNodeRuntimeEnabled("24.18.0"),
                "Node is disabled without marker"
            );
            Path installed = layout.getToolchain().toPath().resolve("installed");
            Files.createDirectory(installed);
            installed.toFile().setReadable(false, false);
            installed.toFile().setWritable(false, false);
            installed.toFile().setExecutable(false, false);
            installed.toFile().setReadable(true, true);
            installed.toFile().setWritable(true, true);
            installed.toFile().setExecutable(true, true);
            Path marker = installed.resolve("node-24.18.0");
            Files.write(marker, "enabled 24.18.0\n".getBytes("US-ASCII"));
            Files.setPosixFilePermissions(marker, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ));
            TestSupport.assertTrue(
                layout.isNodeRuntimeEnabled("24.18.0"),
                "exact private Node marker enables UI state"
            );
            Files.write(marker, "not-enabled\n".getBytes("US-ASCII"));
            TestSupport.assertFalse(
                layout.isNodeRuntimeEnabled("24.18.0"),
                "marker content is authoritative"
            );
            Files.write(marker, "enabled 24.18.0\n".getBytes("US-ASCII"));
            Files.setPosixFilePermissions(marker, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
            ));
            TestSupport.assertFalse(
                layout.isNodeRuntimeEnabled("24.18.0"),
                "non-private Node marker is rejected"
            );
            Files.setPosixFilePermissions(marker, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ));
            TestSupport.assertFalse(
                layout.isNodeRuntimeEnabled("24.18.0"),
                "Node marker permissions must be exactly 0600"
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static void rejectsSymbolicToolchainRoot() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-toolchain-base-");
        Path target = Files.createTempDirectory("agentcodi-toolchain-target-");
        try {
            Path workspace = base.resolve("agentcodi").resolve("workspace");
            Files.createDirectories(workspace);
            Files.createSymbolicLink(workspace.resolve("toolchain"), target);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceLayout.create(base.toFile());
                    }
                },
                "symbolic toolchain root should be rejected"
            );
        } finally {
            deleteRecursively(base);
            deleteRecursively(target);
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

    private static void preservesPrivateCodexConfigurationFiles() throws Exception {
        String[] names = {"config.toml", "requirements.toml", "hooks.json"};
        for (String name : names) {
            Path base = Files.createTempDirectory("agentcodi-config-file-");
            try {
                Path codexHome = base.resolve("agentcodi").resolve("codex-home");
                Files.createDirectories(codexHome);
                Path configuration = codexHome.resolve(name);
                byte[] marker = ("fixture=" + name).getBytes("UTF-8");
                Files.write(configuration, marker);
                WorkspaceLayout.create(base.toFile());
                TestSupport.assertTrue(
                    java.util.Arrays.equals(marker, Files.readAllBytes(configuration)),
                    "Codex configuration content remains untouched: " + name
                );
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    configuration
                );
                TestSupport.assertFalse(
                    permissions.contains(PosixFilePermission.GROUP_READ)
                        || permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_READ)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE),
                    "Codex configuration is owner-only: " + name
                );
            } finally {
                deleteRecursively(base);
            }
        }
    }

    private static void rejectsSymbolicCodexConfigurationFile() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-config-symlink-");
        Path outside = Files.createTempFile("agentcodi-config-outside-", ".toml");
        try {
            Path codexHome = base.resolve("agentcodi").resolve("codex-home");
            Files.createDirectories(codexHome);
            Files.createSymbolicLink(codexHome.resolve("config.toml"), outside);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceLayout.create(base.toFile());
                    }
                },
                "symbolic Codex configuration should be rejected"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
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

    private static void acceptsAdam7WorkspaceImage() throws Exception {
        Path base = Files.createTempDirectory("agentcodi-image-adam7-");
        try {
            WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            Path image = layout.getWorkspace().toPath().resolve("interlaced.png");
            byte[] expected = pngFixture(
                new byte[] {0x00, 0x11, 0x22, 0x33, (byte) 0xff},
                1
            );
            Files.write(image, expected);
            WorkspaceImageFile inspected = WorkspaceImageFile.inspect(
                layout.getWorkspace(),
                image.toString(),
                1024L
            );
            TestSupport.assertEquals(
                Long.valueOf(expected.length),
                Long.valueOf(inspected.getByteCount()),
                "Adam7 PNG byte count"
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

    private static void rejectsImageSymlinkSwapBeforeOpenWithoutWritingBytes()
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-open-race-");
        final Path outside = Files.createTempFile("agentcodi-image-open-outside-", ".png");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path image = layout.getWorkspace().toPath().resolve("changing.png");
            Files.write(image, pngFixture());
            byte[] outsideImage = pngFixture();
            outsideImage[outsideImage.length - 1] = 'X';
            Files.write(outside, outsideImage);
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
                        Files.move(image, image.resolveSibling("changing-before-swap.png"));
                        Files.createSymbolicLink(image, outside);
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
                        WorkspaceImageFile.copyTo(
                            layout.getWorkspace(),
                            image.toString(),
                            1024L,
                            destination,
                            swapping
                        );
                    }
                },
                "image symlink exchange before descriptor open must fail"
            );
            TestSupport.assertEquals(
                Integer.valueOf(0),
                Integer.valueOf(destination.size()),
                "image symlink exchange must not write foreign bytes"
            );
        } finally {
            deleteRecursively(base);
            Files.deleteIfExists(outside);
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

    private static void rejectsPngSignatureFollowedByGarbage() throws Exception {
        expectRejectedPng(
            new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00, 'D', 'A', 'T', 'A'
            },
            "PNG signature followed by arbitrary bytes"
        );
    }

    private static void rejectsPngWithInvalidDimensions() throws Exception {
        byte[] malformed = pngFixture();
        Arrays.fill(malformed, 16, 20, (byte) 0x00);
        rewriteChunkCrc(malformed, 8);
        expectRejectedPng(malformed, "PNG with zero IHDR width");
    }

    private static void rejectsPngChunkOutsideFileBoundary() throws Exception {
        byte[] malformed = pngFixture();
        malformed[8] = 0x7f;
        malformed[9] = (byte) 0xff;
        malformed[10] = (byte) 0xff;
        malformed[11] = (byte) 0xff;
        expectRejectedPng(malformed, "PNG chunk crossing the file boundary");
    }

    private static void rejectsPngWithInvalidChunkCrc() throws Exception {
        byte[] malformed = pngFixture();
        malformed[29] ^= 0x01;
        expectRejectedPng(malformed, "PNG with an invalid chunk CRC");
    }

    private static void rejectsPngWithCorruptImageData() throws Exception {
        byte[] malformed = pngFixture();
        int idat = findChunk(malformed, "IDAT");
        TestSupport.assertTrue(idat >= 0, "valid fixture contains IDAT");
        malformed[idat + 8] ^= 0x01;
        rewriteChunkCrc(malformed, idat);
        expectRejectedPng(malformed, "PNG with CRC-correct corrupt IDAT data");
    }

    private static void rejectsPngWithIncompleteScanlineShape() throws Exception {
        byte[] malformed = pngFixture();
        malformed[19] = 0x02;
        rewriteChunkCrc(malformed, 8);
        expectRejectedPng(
            malformed,
            "PNG whose inflated scanline is shorter than its IHDR shape"
        );
    }

    private static void rejectsPngWithInvalidScanlineFilter() throws Exception {
        expectRejectedPng(
            pngFixture(
                new byte[] {0x05, 0x11, 0x22, 0x33, (byte) 0xff},
                0
            ),
            "PNG with invalid decompressed scanline filter"
        );
    }

    private static void rejectsPngWithoutIend() throws Exception {
        byte[] valid = pngFixture();
        expectRejectedPng(
            Arrays.copyOf(valid, valid.length - 12),
            "PNG without IEND"
        );
    }

    private static void rejectsPngBytesAfterIend() throws Exception {
        byte[] valid = pngFixture();
        byte[] malformed = Arrays.copyOf(valid, valid.length + 1);
        malformed[malformed.length - 1] = 'X';
        expectRejectedPng(malformed, "PNG with bytes after IEND");
    }

    private static void rejectsMalformedPngDuringCopy() throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-invalid-copy-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path image = layout.getWorkspace().toPath().resolve("invalid-copy.png");
            Files.write(
                image,
                new byte[] {
                    (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a,
                    0x00, 0x00, 0x00, 0x00, 'D', 'A', 'T', 'A'
                }
            );
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.copyTo(
                            layout.getWorkspace(),
                            image.toString(),
                            1024L,
                            new ByteArrayOutputStream()
                        );
                    }
                },
                "malformed PNG must be rejected again during copy"
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
        return pngFixture(
            new byte[] {0x00, 0x11, 0x22, 0x33, (byte) 0xff},
            0
        );
    }

    private static byte[] pngFixture(byte[] pixels, int interlace) {
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
        ihdr[3] = 0x01;
        ihdr[7] = 0x01;
        ihdr[8] = 0x08;
        ihdr[9] = 0x06;
        ihdr[12] = (byte) interlace;
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

    private static void expectRejectedPng(final byte[] contents, String message)
        throws Exception {
        final Path base = Files.createTempDirectory("agentcodi-image-malformed-");
        try {
            final WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
            final Path image = layout.getWorkspace().toPath().resolve("malformed.png");
            Files.write(image, contents);
            TestSupport.expectThrows(
                IOException.class,
                new TestSupport.ThrowingRunnable() {
                    @Override
                    public void run() throws Exception {
                        WorkspaceImageFile.inspect(
                            layout.getWorkspace(),
                            image.toString(),
                            1024L * 1024L
                        );
                    }
                },
                message
            );
        } finally {
            deleteRecursively(base);
        }
    }

    private static int findChunk(byte[] png, String type) {
        int offset = 8;
        while (offset <= png.length - 12) {
            long length = unsignedInt(png, offset);
            if (length > Integer.MAX_VALUE || length + 12L > png.length - offset) {
                return -1;
            }
            if (png[offset + 4] == type.charAt(0)
                && png[offset + 5] == type.charAt(1)
                && png[offset + 6] == type.charAt(2)
                && png[offset + 7] == type.charAt(3)) {
                return offset;
            }
            offset += (int) length + 12;
        }
        return -1;
    }

    private static void rewriteChunkCrc(byte[] png, int chunkOffset) {
        int length = (int) unsignedInt(png, chunkOffset);
        CRC32 crc = new CRC32();
        crc.update(png, chunkOffset + 4, length + 4);
        long value = crc.getValue();
        int crcOffset = chunkOffset + 8 + length;
        png[crcOffset] = (byte) (value >>> 24);
        png[crcOffset + 1] = (byte) (value >>> 16);
        png[crcOffset + 2] = (byte) (value >>> 8);
        png[crcOffset + 3] = (byte) value;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24)
            | ((long) (bytes[offset + 1] & 0xff) << 16)
            | ((long) (bytes[offset + 2] & 0xff) << 8)
            | (long) (bytes[offset + 3] & 0xff);
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
