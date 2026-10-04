package de.agentcodi.tests;

import de.agentcodi.storage.PackageBootstrap;
import de.agentcodi.storage.WorkspaceLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageBootstrapTest {
    private PackageBootstrapTest() { }

    public static int run() throws Exception {
        preservesPrefixAcrossRestartAndApkUpdate();
        retriesInterruptedConfiguration();
        recoversBetweenPrefixRenames();
        rejectsCorruptAndUnexpectedPayload();
        rejectsEscapingSymlink();
        preservesConflictingExistingFile();
        return 6;
    }

    private static void preservesPrefixAcrossRestartAndApkUpdate() throws Exception {
        Path base = Files.createTempDirectory("bootstrap-preserve");
        WorkspaceLayout layout = WorkspaceLayout.create(base.toFile());
        Path custom = layout.getPackagePrefix().toPath().resolve("bin/custom");
        Files.write(custom, new byte[] {42});
        install(layout, fixture(false), new PackageBootstrap.Configurator() {
            @Override public void configure(File prefix) throws IOException {
                if (!Files.isSymbolicLink(prefix.toPath().resolve("bin/sh"))) {
                    throw new IOException("Missing shell link");
                }
            }
        });
        Path status = layout.getPackagePrefix().toPath().resolve("var/lib/dpkg/status");
        Files.write(status, "user-installed-package\n".getBytes(StandardCharsets.UTF_8));
        PackageBootstrap.prepare(WorkspaceLayout.create(base.toFile()),
            new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0]),
            prefix -> { throw new IOException("A ready bootstrap must not be reconfigured"); });
        if (Files.readAllBytes(custom)[0] != 42
            || !new String(Files.readAllBytes(status), StandardCharsets.UTF_8).startsWith("user")) {
            throw new AssertionError("APK update replaced package data");
        }
    }

    private static void retriesInterruptedConfiguration() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.create(Files.createTempDirectory("bootstrap-retry").toFile());
        try {
            install(layout, fixture(false), prefix -> { throw new IOException("interrupted"); });
            throw new AssertionError("Configuration failure accepted");
        } catch (IOException expected) { }
        if (Files.exists(layout.getPackagePrefix().toPath().resolve(".agentcodi-bootstrap-ready"))) {
            throw new AssertionError("Failed configuration marked ready");
        }
        int[] calls = {0};
        PackageBootstrap.prepare(layout, new ByteArrayInputStream(new byte[0]),
            new ByteArrayInputStream(new byte[0]), prefix -> calls[0]++);
        if (calls[0] != 1 || Files.exists(layout.getPackagePrefix().toPath().getParent()
            .resolve(".agentcodi-bootstrap-backup"))) {
            throw new AssertionError("Configuration retry did not finish recovery");
        }
    }

    private static void recoversBetweenPrefixRenames() throws Exception {
        Path base = Files.createTempDirectory("bootstrap-rename");
        WorkspaceLayout first = WorkspaceLayout.create(base.toFile());
        Path custom = first.getPackagePrefix().toPath().resolve("bin/custom");
        Files.write(custom, new byte[] {7});
        Files.move(first.getPackagePrefix().toPath(), base.resolve(".agentcodi-bootstrap-backup"));
        WorkspaceLayout recovered = WorkspaceLayout.create(base.toFile());
        if (Files.readAllBytes(recovered.getPackagePrefix().toPath().resolve("bin/custom"))[0] != 7) {
            throw new AssertionError("Prefix rename recovery lost data");
        }
    }

    private static void rejectsCorruptAndUnexpectedPayload() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.create(Files.createTempDirectory("bootstrap-corrupt").toFile());
        byte[][] payload = fixture(false);
        String manifest = new String(payload[1], StandardCharsets.UTF_8);
        payload[1] = manifest.replace(sha("dash".getBytes(StandardCharsets.UTF_8)),
            sha("wrong".getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        expectFailure(layout, payload);
        expectFailure(layout, fixture(true));
        install(layout, fixture(false), prefix -> { });
    }

    private static void rejectsEscapingSymlink() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.create(Files.createTempDirectory("bootstrap-link").toFile());
        byte[][] payload = fixture(false);
        payload[1] = new String(payload[1], StandardCharsets.UTF_8)
            .replace("L\tdash\tbin/sh", "L\t../../outside\tbin/sh").getBytes(StandardCharsets.UTF_8);
        expectFailure(layout, payload);
    }

    private static void preservesConflictingExistingFile() throws Exception {
        WorkspaceLayout layout = WorkspaceLayout.create(Files.createTempDirectory("bootstrap-conflict").toFile());
        Path dash = layout.getPackagePrefix().toPath().resolve("bin/dash");
        Files.write(dash, new byte[] {99});
        expectFailure(layout, fixture(false));
        if (Files.readAllBytes(dash)[0] != 99) {
            throw new AssertionError("Existing prefix file overwritten");
        }
    }

    private static void expectFailure(WorkspaceLayout layout, byte[][] fixture) throws Exception {
        try {
            install(layout, fixture, prefix -> { throw new AssertionError("Invalid payload published"); });
            throw new AssertionError("Invalid bootstrap accepted");
        } catch (IOException expected) { }
    }

    private static void install(WorkspaceLayout layout, byte[][] fixture,
        PackageBootstrap.Configurator configure) throws IOException {
        PackageBootstrap.prepare(layout, new ByteArrayInputStream(fixture[0]),
            new ByteArrayInputStream(fixture[1]), configure);
    }

    private static byte[][] fixture(boolean extra) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("bin/dash", "dash");
        files.put("bin/apt", "apt");
        files.put("bin/dpkg", "dpkg");
        files.put("etc/tls/cert.pem", "certificates");
        files.put("var/lib/dpkg/status", "Package: fixture\nStatus: install ok unpacked\n");
        StringBuilder manifest = new StringBuilder("AGENTCODI_BOOTSTRAP_V1\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
                manifest.append("F\t700\t").append(content.length).append("\t")
                    .append(sha(content)).append("\t").append(file.getKey()).append("\n");
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(content);
                zip.closeEntry();
            }
            if (extra) {
                zip.putNextEntry(new ZipEntry("../outside"));
                zip.write(1);
                zip.closeEntry();
            }
        }
        manifest.append("L\tdash\tbin/sh\n");
        return new byte[][] {bytes.toByteArray(), manifest.toString().getBytes(StandardCharsets.UTF_8)};
    }

    private static String sha(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
            result.append(String.format("%02x", value & 255));
        }
        return result.toString();
    }
}
