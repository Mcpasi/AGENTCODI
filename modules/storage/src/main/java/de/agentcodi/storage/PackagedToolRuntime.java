package de.agentcodi.storage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class PackagedToolRuntime {
    private static final String MANIFEST_HEADER = "AGENTCODI_TOOL_RUNTIME_V1";
    private static final int MAXIMUM_MANIFEST_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_ENTRIES = 8_192;
    private static final long MAXIMUM_FILE_BYTES = 16L * 1024L * 1024L;
    private static final long MAXIMUM_TOTAL_BYTES = 64L * 1024L * 1024L;
    private static final int MAXIMUM_PATH_CHARACTERS = 1_024;
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        );
    private static final Set<PosixFilePermission> WRITABLE_DIRECTORY_PERMISSIONS =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        );
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );

    private PackagedToolRuntime() {
    }

    static File prepare(
        File runtimeRoot,
        String runtimeName,
        InputStream archive,
        InputStream manifest,
        File nativeLibraryDirectory
    ) throws IOException {
        if (runtimeRoot == null || archive == null || manifest == null
            || nativeLibraryDirectory == null) {
            throw new IllegalArgumentException("Packaged tool runtime inputs are required");
        }
        if (!validRuntimeName(runtimeName)) {
            throw new IllegalArgumentException("Packaged tool runtime name is invalid");
        }

        try {
            Path root = requirePrivateRoot(runtimeRoot.toPath());
            Path nativeRoot = requireNativeRoot(nativeLibraryDirectory.toPath());
            Manifest parsed = parseManifest(manifest, nativeRoot);
            Path target = root.resolve(runtimeName);
            Path staging = root.resolve(runtimeName + ".preparing");

            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
                try {
                    validateTree(target, parsed, nativeRoot);
                    return target.toFile().getCanonicalFile();
                } catch (IOException invalidRuntime) {
                    deleteTree(target);
                }
            }

            deleteTree(staging);
            Files.createDirectory(staging);
            Files.setPosixFilePermissions(staging, WRITABLE_DIRECTORY_PERMISSIONS);
            boolean installed = false;
            try {
                extractArchive(archive, staging, parsed);
                createNativeLinks(staging, parsed, nativeRoot);
                restrictDirectories(staging);
                validateTree(staging, parsed, nativeRoot);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException error) {
                    Files.move(staging, target);
                }
                validateTree(target, parsed, nativeRoot);
                installed = true;
                return target.toFile().getCanonicalFile();
            } finally {
                if (!installed) {
                    deleteTree(staging);
                }
            }
        } finally {
            closeQuietly(archive);
            closeQuietly(manifest);
        }
    }

    private static boolean validRuntimeName(String value) {
        if (value == null || value.length() < 1 || value.length() > 96) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static Path requirePrivateRoot(Path supplied) throws IOException {
        if (Files.isSymbolicLink(supplied)
            || !Files.isDirectory(supplied, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Packaged tool runtime root is not a canonical directory");
        }
        Path canonical = supplied.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
            canonical,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!permissions.equals(WRITABLE_DIRECTORY_PERMISSIONS)) {
            throw new IOException("Packaged tool runtime root is not owner-only");
        }
        return canonical;
    }

    private static Path requireNativeRoot(Path supplied) throws IOException {
        if (Files.isSymbolicLink(supplied)
            || !Files.isDirectory(supplied, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Native library directory is invalid");
        }
        return supplied.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static Manifest parseManifest(InputStream input, Path nativeRoot)
        throws IOException {
        byte[] bytes = readBounded(input, MAXIMUM_MANIFEST_BYTES);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Packaged tool runtime manifest is not UTF-8", error);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        String[] lines = text.split("\\n", -1);
        if (lines.length < 2 || !MANIFEST_HEADER.equals(lines[0])) {
            throw new IOException("Packaged tool runtime manifest header is invalid");
        }
        TreeMap<String, ManifestEntry> entries = new TreeMap<String, ManifestEntry>();
        long totalBytes = 0L;
        String previousPath = null;
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            if (line.isEmpty() || line.length() > 1_400) {
                throw new IOException("Packaged tool runtime manifest line is invalid");
            }
            String[] fields = line.split("\\t", -1);
            ManifestEntry entry;
            if (fields.length == 4 && "F".equals(fields[0])) {
                long size = parseSize(fields[1]);
                requireSha256(fields[2]);
                requireRelativePath(fields[3]);
                totalBytes += size;
                if (totalBytes > MAXIMUM_TOTAL_BYTES) {
                    throw new IOException("Packaged tool runtime exceeds its total size limit");
                }
                entry = ManifestEntry.file(fields[3], size, fields[2]);
            } else if (fields.length == 4 && "L".equals(fields[0])) {
                requireSha256(fields[1]);
                requireNativeName(fields[2]);
                requireRelativePath(fields[3]);
                Path nativeTarget = nativeRoot.resolve(fields[2]);
                validateNativeTarget(nativeRoot, nativeTarget, fields[1]);
                entry = ManifestEntry.link(fields[3], fields[2], fields[1]);
            } else {
                throw new IOException("Packaged tool runtime manifest record is invalid");
            }
            if (previousPath != null && previousPath.compareTo(entry.path) >= 0) {
                throw new IOException("Packaged tool runtime manifest is not strictly sorted");
            }
            if (entries.put(entry.path, entry) != null) {
                throw new IOException("Packaged tool runtime manifest repeats a path");
            }
            previousPath = entry.path;
            if (entries.size() > MAXIMUM_ENTRIES) {
                throw new IOException("Packaged tool runtime has too many entries");
            }
        }
        if (entries.isEmpty()) {
            throw new IOException("Packaged tool runtime manifest is empty");
        }
        return new Manifest(entries, expectedDirectories(entries));
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                total += count;
                if (total > maximumBytes) {
                    throw new IOException("Packaged tool runtime manifest exceeds its limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static long parseSize(String value) throws IOException {
        if (value.isEmpty() || value.length() > 12) {
            throw new IOException("Packaged tool runtime file size is invalid");
        }
        long result = 0L;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IOException("Packaged tool runtime file size is invalid");
            }
            result = result * 10L + character - '0';
            if (result > MAXIMUM_FILE_BYTES) {
                throw new IOException("Packaged tool runtime file exceeds its limit");
            }
        }
        return result;
    }

    private static void requireSha256(String value) throws IOException {
        if (value.length() != 64) {
            throw new IOException("Packaged tool runtime SHA-256 is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                || (character >= 'a' && character <= 'f'))) {
                throw new IOException("Packaged tool runtime SHA-256 is invalid");
            }
        }
    }

    private static void requireNativeName(String value) throws IOException {
        if (value.length() < 7 || value.length() > 180
            || !value.startsWith("lib") || !value.endsWith(".so")) {
            throw new IOException("Packaged native tool name is invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '.' || character == '-' || character == '_')) {
                throw new IOException("Packaged native tool name is invalid");
            }
        }
    }

    private static void requireRelativePath(String value) throws IOException {
        if (value.isEmpty() || value.length() > MAXIMUM_PATH_CHARACTERS
            || value.startsWith("/") || value.endsWith("/")
            || value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0) {
            throw new IOException("Packaged tool runtime path is invalid");
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Packaged tool runtime path is unsafe");
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character < 0x21 || character == 0x7f) {
                    throw new IOException("Packaged tool runtime path contains control text");
                }
            }
        }
    }

    private static Set<String> expectedDirectories(
        TreeMap<String, ManifestEntry> entries
    ) {
        Set<String> directories = new HashSet<String>();
        directories.add("");
        for (String path : entries.keySet()) {
            int separator = path.indexOf('/');
            while (separator >= 0) {
                directories.add(path.substring(0, separator));
                separator = path.indexOf('/', separator + 1);
            }
        }
        return directories;
    }

    private static void extractArchive(
        InputStream archive,
        Path staging,
        Manifest manifest
    ) throws IOException {
        Set<String> extracted = new HashSet<String>();
        byte[] buffer = new byte[16_384];
        try (ZipInputStream zip = new ZipInputStream(archive, StandardCharsets.UTF_8)) {
            while (true) {
                ZipEntry zipEntry = zip.getNextEntry();
                if (zipEntry == null) {
                    break;
                }
                String name = zipEntry.getName();
                requireRelativePath(name.endsWith("/")
                    ? name.substring(0, name.length() - 1)
                    : name);
                if (zipEntry.isDirectory()) {
                    String directoryName = name.substring(0, name.length() - 1);
                    if (!manifest.directories.contains(directoryName)) {
                        throw new IOException("Packaged tool archive contains an unexpected directory");
                    }
                    zip.closeEntry();
                    continue;
                }
                ManifestEntry expected = manifest.entries.get(name);
                if (expected == null || expected.link || !extracted.add(name)) {
                    throw new IOException("Packaged tool archive contains an unexpected entry");
                }
                Path destination = resolveContained(staging, name);
                createParentDirectories(staging, destination.getParent());
                MessageDigest digest = sha256Digest();
                long written = 0L;
                try (OutputStream output = Files.newOutputStream(
                    destination,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )) {
                    while (true) {
                        int count = zip.read(buffer);
                        if (count < 0) {
                            break;
                        }
                        written += count;
                        if (written > expected.size || written > MAXIMUM_FILE_BYTES) {
                            throw new IOException("Packaged tool archive entry exceeds its manifest");
                        }
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                }
                if (written != expected.size
                    || !expected.sha256.equals(hex(digest.digest()))) {
                    throw new IOException("Packaged tool archive entry failed verification");
                }
                Files.setPosixFilePermissions(destination, PRIVATE_FILE_PERMISSIONS);
                zip.closeEntry();
            }
        }
        int expectedFiles = 0;
        for (ManifestEntry entry : manifest.entries.values()) {
            if (!entry.link) {
                expectedFiles++;
            }
        }
        if (extracted.size() != expectedFiles) {
            throw new IOException("Packaged tool archive omitted a manifest entry");
        }
    }

    private static Path resolveContained(Path root, String relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (resolved.equals(root) || !resolved.startsWith(root)) {
            throw new IOException("Packaged tool runtime path escaped its root");
        }
        return resolved;
    }

    private static void createParentDirectories(Path root, Path parent) throws IOException {
        if (parent == null || parent.equals(root)) {
            return;
        }
        Path relative = root.relativize(parent);
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Packaged tool runtime parent is unsafe");
                }
            } else {
                Files.createDirectory(current);
                Files.setPosixFilePermissions(current, WRITABLE_DIRECTORY_PERMISSIONS);
            }
        }
    }

    private static void createNativeLinks(
        Path staging,
        Manifest manifest,
        Path nativeRoot
    ) throws IOException {
        for (ManifestEntry entry : manifest.entries.values()) {
            if (!entry.link) {
                continue;
            }
            Path target = nativeRoot.resolve(entry.nativeName);
            validateNativeTarget(nativeRoot, target, entry.sha256);
            Path link = resolveContained(staging, entry.path);
            createParentDirectories(staging, link.getParent());
            Files.createSymbolicLink(link, target);
        }
    }

    private static void validateNativeTarget(
        Path nativeRoot,
        Path suppliedTarget,
        String expectedSha256
    ) throws IOException {
        if (Files.isSymbolicLink(suppliedTarget)
            || !Files.isRegularFile(suppliedTarget, LinkOption.NOFOLLOW_LINKS)
            || !Files.isExecutable(suppliedTarget)) {
            throw new IOException("Packaged native tool target is invalid");
        }
        Path canonical = suppliedTarget.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!canonical.getParent().equals(nativeRoot)
            || !expectedSha256.equals(sha256(canonical))) {
            throw new IOException("Packaged native tool target failed verification");
        }
        WorkspaceFileBoundary.requireSingleLink(canonical);
    }

    private static void restrictDirectories(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error)
                throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
                return FileVisitResult.CONTINUE;
            }
        });
        Files.setPosixFilePermissions(root, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private static void validateTree(
        final Path root,
        final Manifest manifest,
        final Path nativeRoot
    ) throws IOException {
        if (Files.isSymbolicLink(root)
            || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Packaged tool runtime directory is invalid");
        }
        final Set<String> seenEntries = new HashSet<String>();
        final Set<String> seenDirectories = new HashSet<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
                String relative = relativeName(root, directory);
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                    directory,
                    LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isSymbolicLink() || !attributes.isDirectory()
                    || !manifest.directories.contains(relative)
                    || !permissions.equals(PRIVATE_DIRECTORY_PERMISSIONS)) {
                    throw new IOException(
                        "Packaged tool runtime contains an unsafe directory: " + relative
                    );
                }
                seenDirectories.add(relative);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
                String relative = relativeName(root, file);
                ManifestEntry expected = manifest.entries.get(relative);
                if (expected == null || !seenEntries.add(relative)) {
                    throw new IOException("Packaged tool runtime contains an unexpected file");
                }
                if (expected.link) {
                    if (!Files.isSymbolicLink(file)) {
                        throw new IOException("Packaged native tool alias is not a symbolic link");
                    }
                    Path expectedTarget = nativeRoot.resolve(expected.nativeName);
                    validateNativeTarget(nativeRoot, expectedTarget, expected.sha256);
                    Path linkTarget = Files.readSymbolicLink(file);
                    Path resolved = linkTarget.isAbsolute()
                        ? linkTarget.normalize()
                        : file.getParent().resolve(linkTarget).normalize();
                    if (!resolved.equals(expectedTarget)) {
                        throw new IOException("Packaged native tool alias target is invalid");
                    }
                } else {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile()
                        || attributes.size() != expected.size
                        || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                            .equals(PRIVATE_FILE_PERMISSIONS)) {
                        throw new IOException("Packaged tool runtime file metadata is invalid");
                    }
                    WorkspaceFileBoundary.requireSingleLink(file);
                    if (!expected.sha256.equals(sha256(file))) {
                        throw new IOException("Packaged tool runtime file hash is invalid");
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!seenEntries.equals(manifest.entries.keySet())
            || !seenDirectories.equals(manifest.directories)) {
            throw new IOException("Packaged tool runtime tree is incomplete");
        }
    }

    private static String relativeName(Path root, Path path) {
        if (root.equals(path)) {
            return "";
        }
        return root.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[16_384];
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String hex(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            encoded[index * 2] = alphabet[value >>> 4];
            encoded[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(encoded);
    }

    private static void deleteTree(final Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            return;
        }
        if (Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
                if (attributes.isSymbolicLink()) {
                    throw new IOException("Refusing to traverse a symbolic runtime directory");
                }
                Files.setPosixFilePermissions(directory, WRITABLE_DIRECTORY_PERMISSIONS);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error)
                throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // The verified runtime has already been selected; close failure is non-material.
        }
    }

    private static final class Manifest {
        private final TreeMap<String, ManifestEntry> entries;
        private final Set<String> directories;

        private Manifest(
            TreeMap<String, ManifestEntry> entries,
            Set<String> directories
        ) {
            this.entries = entries;
            this.directories = directories;
        }
    }

    private static final class ManifestEntry {
        private final String path;
        private final long size;
        private final String sha256;
        private final String nativeName;
        private final boolean link;

        private ManifestEntry(
            String path,
            long size,
            String sha256,
            String nativeName,
            boolean link
        ) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.nativeName = nativeName;
            this.link = link;
        }

        private static ManifestEntry file(String path, long size, String sha256) {
            return new ManifestEntry(path, size, sha256, "", false);
        }

        private static ManifestEntry link(
            String path,
            String nativeName,
            String sha256
        ) {
            return new ManifestEntry(path, 0L, sha256, nativeName, true);
        }
    }
}
