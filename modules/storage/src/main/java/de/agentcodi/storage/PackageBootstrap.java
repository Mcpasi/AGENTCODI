package de.agentcodi.storage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** First-install transaction. A configured prefix is never replaced on APK update. */
public final class PackageBootstrap {
    private static final String UNPACKED = ".agentcodi-bootstrap-unpacked";
    private static final String READY = ".agentcodi-bootstrap-ready";
    private static final String TRUST_KEY = "etc/apt/keyrings/agentcodi-package.gpg";
    private static final long MAX_TOTAL = 256L * 1024 * 1024;

    public interface Configurator {
        void configure(File prefix) throws IOException;
    }

    private PackageBootstrap() { }

    public static synchronized void prepare(
        WorkspaceLayout layout, InputStream archive, InputStream manifest,
        Configurator configurator
    ) throws IOException {
        Path target = layout.getPackagePrefix().toPath();
        Path parent = target.getParent();
        Path stage = parent.resolve(".agentcodi-bootstrap-stage");
        Path backup = parent.resolve(".agentcodi-bootstrap-backup");
        Path lock = layout.getState().toPath().resolve("package-bootstrap.lock");
        requireRegularOrAbsent(lock);
        try (FileChannel channel = FileChannel.open(lock,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock held = channel.lock()) {
            requireDirectoryOrAbsent(target);
            requireDirectoryOrAbsent(stage);
            requireDirectoryOrAbsent(backup);
            // A crash between the two renames restores the original prefix.
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.exists(backup)) {
                Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
            }
            if (marker(target, READY)) {
                installMissingTrustKey(target, archive, manifest);
                removeTree(stage);
                removeTree(backup);
                return;
            }
            if (!marker(target, UNPACKED)) {
                if (Files.exists(backup)) {
                    throw new IOException("Bootstrap recovery conflicts with an existing prefix");
                }
                removeTree(stage);
                Files.createDirectory(stage);
                privateMode(stage, true);
                if (Files.exists(target)) {
                    copyTree(target, stage);
                }
                Map<String, Entry> entries = parse(manifest);
                extract(stage, archive, entries);
                writeMarker(stage.resolve(UNPACKED));
                Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            }
            // An interrupted or failed dpkg configuration is retried on the next start.
            configurator.configure(target.toFile());
            writeMarker(target.resolve(READY));
            removeTree(backup);
            removeTree(stage);
        }
    }

    /** Add first trust to an earlier configured prefix without replacing its data. */
    private static void installMissingTrustKey(Path target, InputStream archive, InputStream manifest)
        throws IOException {
        Path key = target.resolve(TRUST_KEY);
        requireRegularOrAbsent(key);
        if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Entry entry = parse(manifest).get(TRUST_KEY);
        // Older APK assets and test fixtures can legitimately predate repository trust.
        if (entry == null) {
            return;
        }
        if (entry.link != null || entry.executable || entry.size > 1024 * 1024) {
            throw new IOException("Invalid bootstrap trust key");
        }
        destination(target, TRUST_KEY);
        Path temporary = Files.createTempFile(key.getParent(), ".agentcodi-keyring-", ".tmp");
        try {
            boolean found = false;
            MessageDigest digest = sha256();
            long size = 0;
            try (ZipInputStream zip = new ZipInputStream(archive);
                 OutputStream output = Files.newOutputStream(temporary,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ZipEntry zipped;
                while ((zipped = zip.getNextEntry()) != null) {
                    if (!TRUST_KEY.equals(zipped.getName())) {
                        continue;
                    }
                    if (zipped.isDirectory() || found) {
                        throw new IOException("Invalid bootstrap trust ZIP entry");
                    }
                    found = true;
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        size += count;
                        if (size > entry.size) {
                            throw new IOException("Bootstrap trust key exceeds manifest size");
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                }
            }
            if (!found || size != entry.size || !hex(digest.digest()).equals(entry.digest)) {
                throw new IOException("Bootstrap trust key checksum mismatch");
            }
            privateMode(temporary, false);
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                file.force(true);
            }
            requireRegularOrAbsent(key);
            if (Files.exists(key, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Existing repository trust key must be preserved");
            }
            Files.move(temporary, key, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static synchronized void recoverPrefix(File base, File state) throws IOException {
        Path lock = state.toPath().resolve("package-bootstrap.lock");
        requireRegularOrAbsent(lock);
        try (FileChannel channel = FileChannel.open(lock,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock held = channel.lock()) {
            Path target = base.toPath().resolve("usr");
            Path backup = base.toPath().resolve(".agentcodi-bootstrap-backup");
            requireDirectoryOrAbsent(target);
            requireDirectoryOrAbsent(backup);
            if (!Files.exists(target) && Files.exists(backup)) {
                Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
            }
        }
    }

    private static Map<String, Entry> parse(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 4 * 1024 * 1024) {
                throw new IOException("Bootstrap manifest too large");
            }
            bytes.write(buffer, 0, count);
        }
        String[] lines = new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n");
        if (lines.length < 2 || !"AGENTCODI_BOOTSTRAP_V1".equals(lines[0])
            || lines.length > 32768) {
            throw new IOException("Invalid bootstrap manifest");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        long total = 0;
        for (int index = 1; index < lines.length; index++) {
            String[] parts = lines[index].split("\t", -1);
            Entry entry = new Entry();
            if (parts.length == 5 && "F".equals(parts[0])
                && ("600".equals(parts[1]) || "700".equals(parts[1]))
                && parts[2].matches("[0-9]{1,9}") && parts[3].matches("[0-9a-f]{64}")) {
                entry.name = parts[4];
                entry.size = Long.parseLong(parts[2]);
                entry.digest = parts[3];
                entry.executable = "700".equals(parts[1]);
                total += entry.size;
                if (entry.size > 64L * 1024 * 1024 || total > MAX_TOTAL) {
                    throw new IOException("Bootstrap exceeds extraction bounds");
                }
            } else if (parts.length == 3 && "L".equals(parts[0])) {
                entry.name = parts[2];
                entry.link = parts[1];
            } else {
                throw new IOException("Invalid bootstrap manifest record");
            }
            validateName(entry.name);
            if (entry.link != null) {
                Path target = java.nio.file.Paths.get(entry.link);
                Path resolved = java.nio.file.Paths.get(entry.name).getParent();
                if (resolved == null) {
                    resolved = java.nio.file.Paths.get("");
                }
                resolved = resolved.resolve(target).normalize();
                if (target.isAbsolute() || entry.link.isEmpty()
                    || resolved.startsWith("..") || resolved.toString().isEmpty()) {
                    throw new IOException("Bootstrap symlink leaves prefix");
                }
            }
            if (entries.put(entry.name, entry) != null) {
                throw new IOException("Duplicate bootstrap manifest path");
            }
        }
        for (String required : new String[] {
            "bin/dash", "bin/sh", "bin/apt", "bin/dpkg",
            "etc/tls/cert.pem", "var/lib/dpkg/status"
        }) {
            if (!entries.containsKey(required)) {
                throw new IOException("Incomplete package bootstrap: " + required);
            }
        }
        return entries;
    }

    private static void extract(Path stage, InputStream input, Map<String, Entry> entries)
        throws IOException {
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry zipped;
            while ((zipped = zip.getNextEntry()) != null) {
                Entry entry = entries.get(zipped.getName());
                if (entry == null || entry.link != null || zipped.isDirectory()
                    || !seen.add(entry.name)) {
                    throw new IOException("Unexpected bootstrap ZIP entry");
                }
                Path path = destination(stage, entry.name);
                MessageDigest digest = sha256();
                long size = 0;
                try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        size += count;
                        if (size > entry.size) {
                            throw new IOException("Bootstrap file exceeds manifest size");
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                }
                if (size != entry.size || !hex(digest.digest()).equals(entry.digest)) {
                    throw new IOException("Bootstrap checksum mismatch: " + entry.name);
                }
                privateMode(path, entry.executable);
            }
        }
        for (Entry entry : entries.values()) {
            if (entry.link == null) {
                if (!seen.contains(entry.name)) {
                    throw new IOException("Missing bootstrap payload: " + entry.name);
                }
            } else {
                Path path = destination(stage, entry.name);
                Files.createSymbolicLink(path, java.nio.file.Paths.get(entry.link));
            }
        }
        // These empty mutable directories are intentionally absent from the ZIP.
        for (String directory : new String[] {
            "tmp", "var/lib/dpkg/updates", "var/lib/dpkg/triggers",
            "var/lib/dpkg/alternatives", "var/log/apt", "etc/apt/keyrings",
            "etc/apt/preferences.d",
            "var/cache/apt/archives/partial", "var/lib/apt/lists/partial"
        }) {
            destination(stage, directory + "/.directory");
        }
    }

    private static Path destination(Path root, String name) throws IOException {
        validateName(name);
        Path path = root.resolve(name);
        Path current = root;
        for (Path component : root.relativize(path.getParent())) {
            current = current.resolve(component);
            requireDirectoryOrAbsent(current);
            if (!Files.exists(current)) {
                Files.createDirectory(current);
                privateMode(current, true);
            }
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bootstrap would overwrite an existing file: " + name);
        }
        return path;
    }

    private static void validateName(String name) throws IOException {
        if (name.isEmpty() || name.length() > 1024 || name.startsWith("/")
            || name.contains("\\") || name.contains("\t") || name.contains("\r")
            || name.contains("\n")) {
            throw new IOException("Invalid bootstrap path");
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                || part.startsWith(".agentcodi-bootstrap-")) {
                throw new IOException("Invalid bootstrap path component");
            }
        }
    }

    private static boolean marker(Path root, String name) throws IOException {
        Path path = root.resolve(name);
        requireRegularOrAbsent(path);
        return Files.exists(path) && java.util.Arrays.equals(
            Files.readAllBytes(path), "1\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeMarker(Path path) throws IOException {
        requireRegularOrAbsent(path);
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE,
                 StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                 LinkOption.NOFOLLOW_LINKS)) {
            file.write(java.nio.ByteBuffer.wrap("1\n".getBytes(StandardCharsets.US_ASCII)));
            file.force(true);
        }
        privateMode(path, false);
    }

    private static void requireRegularOrAbsent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected regular bootstrap state file");
        }
    }

    private static void requireDirectoryOrAbsent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected bootstrap directory");
        }
    }

    private static void privateMode(Path path, boolean executable) throws IOException {
        Set<PosixFilePermission> mode = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if (executable) {
            mode.add(PosixFilePermission.OWNER_EXECUTE);
        }
        Files.setPosixFilePermissions(path, mode);
    }

    private static void copyTree(final Path source, final Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException {
                Path dest = target.resolve(source.relativize(dir));
                Files.createDirectories(dest);
                privateMode(dest, true);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
                if (!attrs.isRegularFile() && !attrs.isSymbolicLink()) {
                    throw new IOException("Unsupported existing prefix entry");
                }
                Files.copy(file, target.resolve(source.relativize(file)),
                    LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectoryOrAbsent(root);
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error)
                throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) {
            text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        }
        return text.toString();
    }

    private static final class Entry {
        String name, digest, link;
        long size;
        boolean executable;
    }
}
