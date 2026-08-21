package de.agentcodi.imports.client;

import de.agentcodi.core.CodexFileMention;
import de.agentcodi.core.CodexFileMentionTransaction;
import de.agentcodi.core.CredentialGuard;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportLimits;
import de.agentcodi.imports.WorkspaceImportSelection;
import de.agentcodi.storage.WorkspaceFileAccess;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Android-independent document materialization below the storage-owned import
 * directory. All creation and moves are relative to secure directory handles.
 */
public final class WorkspaceDocumentImporter {
    private static final int BUFFER_BYTES = 8192;
    private static final int MAXIMUM_ZERO_PROGRESS_OPERATIONS = 1024;
    private static final int RANDOM_TOKEN_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS =
        Collections.unmodifiableSet(EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        ));

    private final long maximumFileBytes;

    public WorkspaceDocumentImporter() {
        this(WorkspaceImportLimits.MAXIMUM_FILE_BYTES);
    }

    /** Allows focused tests to exercise the exact production limit behavior. */
    public WorkspaceDocumentImporter(long maximumFileBytes) {
        if (maximumFileBytes <= 0L
            || maximumFileBytes > WorkspaceImportLimits.MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Document import limit is invalid");
        }
        this.maximumFileBytes = maximumFileBytes;
    }

    public ImportedWorkspaceFile importDocument(
        File workspaceDirectory,
        File importsDirectory,
        String proposedDisplayName,
        String proposedMediaType,
        long declaredByteCount,
        InputStream source
    ) throws IOException {
        return importDocument(
            workspaceDirectory,
            importsDirectory,
            proposedDisplayName,
            proposedMediaType,
            declaredByteCount,
            source,
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public ImportedWorkspaceFile importDocument(
        File workspaceDirectory,
        File importsDirectory,
        String proposedDisplayName,
        String proposedMediaType,
        long declaredByteCount,
        InputStream source,
        WorkspaceFileAccess.Opener verificationOpener
    ) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("Document source must not be null");
        }
        if (verificationOpener == null) {
            throw new IllegalArgumentException("Document verification opener is required");
        }
        if (declaredByteCount < -1L || declaredByteCount > maximumFileBytes) {
            throw new IOException("Selected document size exceeds the import limit");
        }
        String displayName = sanitizeDisplayName(proposedDisplayName);
        String storageExtension = safeStorageExtension(displayName);
        String mediaType = sanitizeMediaType(proposedMediaType);
        Path workspace = requireWorkspace(workspaceDirectory);
        Path imports = requireImportsDirectory(workspace, importsDirectory);

        DirectoryStream<Path> rawRoot = Files.newDirectoryStream(workspace);
        if (!(rawRoot instanceof SecureDirectoryStream<?>)) {
            rawRoot.close();
            throw new IOException(
                "Filesystem cannot create imported files without path races"
            );
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> root = (SecureDirectoryStream<Path>) rawRoot;
        try (DirectoryStream<Path> closingRoot = rawRoot;
             SecureDirectoryStream<Path> importRoot = root.newDirectoryStream(
                 workspace.getFileSystem().getPath(
                     WorkspaceImportLimits.IMPORT_DIRECTORY_NAME
                 ),
                 LinkOption.NOFOLLOW_LINKS
             )) {
            return materialize(
                workspace,
                imports,
                importRoot,
                displayName,
                storageExtension,
                mediaType,
                source,
                verificationOpener
            );
        }
    }

    public CodexFileMention verifyForCodex(
        File workspaceDirectory,
        ImportedWorkspaceFile imported,
        WorkspaceFileAccess.Opener verificationOpener
    ) throws IOException {
        if (imported == null) {
            throw new IllegalArgumentException("Imported document must not be null");
        }
        if (verificationOpener == null) {
            throw new IllegalArgumentException("Document verification opener is required");
        }
        VerifiedCodexFile verified = openVerifiedForCodex(
            requireWorkspace(workspaceDirectory),
            imported,
            verificationOpener
        );
        try {
            return verified.mention;
        } finally {
            verified.source.close();
        }
    }

    /**
     * Prepares a bounded, one-shot attachment transaction. Full SHA-256
     * verification is deliberately deferred until the transaction's
     * synchronous send scope so no UI or core queue separates hashing from the
     * request. Every verified source handle then remains open through the
     * correlated request, with a final whole-batch snapshot check at the
     * transport write.
     */
    public CodexFileMentionTransaction prepareForCodex(
        File workspaceDirectory,
        List<ImportedWorkspaceFile> importedFiles,
        WorkspaceFileAccess.Opener verificationOpener
    ) throws IOException {
        if (verificationOpener == null) {
            throw new IllegalArgumentException("Document verification opener is required");
        }
        List<ImportedWorkspaceFile> files = WorkspaceImportSelection.copyOf(
            importedFiles
        );
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Prepared document batch must not be empty");
        }
        return new PreparedCodexFiles(
            requireWorkspace(workspaceDirectory),
            files,
            verificationOpener
        );
    }

    private VerifiedCodexFile openVerifiedForCodex(
        Path workspace,
        ImportedWorkspaceFile imported,
        WorkspaceFileAccess.Opener verificationOpener
    ) throws IOException {
        String relativePath = imported.getRelativePath();
        Path expected = workspace.resolve(relativePath).normalize();
        if (!expected.getParent().equals(workspace.resolve(
            WorkspaceImportLimits.IMPORT_DIRECTORY_NAME
        ))) {
            throw new IOException("Imported workspace file escaped its private directory");
        }
        WorkspaceFileAccess.Source source = verificationOpener.open(
            workspace.toFile(),
            relativePath,
            maximumFileBytes
        );
        boolean accepted = false;
        try {
            verifyContent(source, imported);
            VerifiedCodexFile verified = new VerifiedCodexFile(
                source,
                CodexFileMention.create(
                    imported.getDisplayName(),
                    expected.toFile().getAbsolutePath()
                )
            );
            accepted = true;
            return verified;
        } finally {
            if (!accepted) {
                source.close();
            }
        }
    }

    private ImportedWorkspaceFile materialize(
        Path workspace,
        Path imports,
        SecureDirectoryStream<Path> importRoot,
        String displayName,
        String storageExtension,
        String mediaType,
        InputStream source,
        WorkspaceFileAccess.Opener verificationOpener
    ) throws IOException {
        String token = randomToken();
        Path pendingName = relativeName(imports, ".pending-" + token);
        Path finalName = relativeName(imports, token + storageExtension);
        boolean pendingCreated = false;
        boolean finalCreated = false;
        boolean accepted = false;
        try {
            long byteCount;
            String sha256;
            Set<OpenOption> options = new HashSet<OpenOption>();
            options.add(StandardOpenOption.CREATE_NEW);
            options.add(StandardOpenOption.WRITE);
            options.add(LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel destination = importRoot.newByteChannel(
                pendingName,
                Collections.unmodifiableSet(options)
            )) {
                pendingCreated = true;
                MessageDigest digest = newSha256();
                byteCount = copyBounded(source, destination, digest);
                sha256 = lowerHexAndWipe(digest.digest());
                if (destination instanceof FileChannel) {
                    ((FileChannel) destination).force(true);
                }
            }
            enforceOwnerFilePermissions(importRoot, pendingName);
            BasicFileAttributes pendingAttributes = readRegularAttributes(
                importRoot,
                pendingName
            );
            if (pendingAttributes.size() != byteCount) {
                throw new IOException("Imported workspace file changed while it was written");
            }
            requireMissing(importRoot, finalName);
            importRoot.move(pendingName, importRoot, finalName);
            pendingCreated = false;
            finalCreated = true;
            enforceOwnerFilePermissions(importRoot, finalName);
            BasicFileAttributes finalAttributes = readRegularAttributes(
                importRoot,
                finalName
            );
            if (finalAttributes.size() != byteCount) {
                throw new IOException("Imported workspace file changed while it was installed");
            }

            String relativePath = WorkspaceImportLimits.IMPORT_DIRECTORY_NAME
                + "/" + finalName.toString();
            ImportedWorkspaceFile result = ImportedWorkspaceFile.create(
                relativePath,
                displayName,
                mediaType,
                byteCount,
                sha256
            );
            verifyForCodex(workspace.toFile(), result, verificationOpener);
            accepted = true;
            return result;
        } finally {
            if (pendingCreated) {
                deleteFileIfPresent(importRoot, pendingName);
            }
            if (finalCreated && !accepted) {
                deleteFileIfPresent(importRoot, finalName);
            }
        }
    }

    private long copyBounded(
        InputStream source,
        SeekableByteChannel destination,
        MessageDigest digest
    )
        throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long copied = 0L;
        int zeroReads = 0;
        try {
            while (true) {
                requireNotInterrupted();
                int count = source.read(buffer, 0, buffer.length);
                if (count < 0) {
                    return copied;
                }
                if (count == 0) {
                    zeroReads++;
                    if (zeroReads > MAXIMUM_ZERO_PROGRESS_OPERATIONS) {
                        throw new IOException("Selected document source made no progress");
                    }
                    continue;
                }
                zeroReads = 0;
                if (copied > maximumFileBytes - count) {
                    throw new IOException("Selected document size exceeds the import limit");
                }
                digest.update(buffer, 0, count);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, count);
                int zeroWrites = 0;
                while (bytes.hasRemaining()) {
                    requireNotInterrupted();
                    int written = destination.write(bytes);
                    if (written < 0) {
                        throw new IOException("Imported workspace destination was closed");
                    }
                    if (written == 0) {
                        zeroWrites++;
                        if (zeroWrites > MAXIMUM_ZERO_PROGRESS_OPERATIONS) {
                            throw new IOException(
                                "Imported workspace destination made no progress"
                            );
                        }
                    } else {
                        zeroWrites = 0;
                    }
                }
                copied += count;
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static void verifyContent(
        WorkspaceFileAccess.Source source,
        ImportedWorkspaceFile imported
    ) throws IOException {
        if (source.getByteCount() != imported.getByteCount()) {
            throw new IOException("Imported workspace file changed before it was attached");
        }
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_BYTES];
        long readBytes = 0L;
        int zeroReads = 0;
        try {
            while (true) {
                requireNotInterrupted();
                int count = source.read(buffer, 0, buffer.length);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    zeroReads++;
                    if (zeroReads > MAXIMUM_ZERO_PROGRESS_OPERATIONS) {
                        throw new IOException("Imported workspace source made no progress");
                    }
                    continue;
                }
                zeroReads = 0;
                if (readBytes > imported.getByteCount() - count) {
                    throw new IOException(
                        "Imported workspace file changed before it was attached"
                    );
                }
                readBytes += count;
                digest.update(buffer, 0, count);
            }
            if (readBytes != imported.getByteCount()) {
                throw new IOException("Imported workspace file changed before it was attached");
            }
            source.verifyUnchanged();
            byte[] actual = digest.digest();
            byte[] expected = decodeSha256(imported.getSha256());
            try {
                if (!MessageDigest.isEqual(actual, expected)) {
                    throw new IOException(
                        "Imported workspace file content changed before it was attached"
                    );
                }
            } finally {
                Arrays.fill(actual, (byte) 0);
                Arrays.fill(expected, (byte) 0);
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable for document import", error);
        }
    }

    private static void closeVerifiedFiles(
        List<VerifiedCodexFile> files,
        IOException initialFailure
    ) throws IOException {
        IOException failure = initialFailure;
        for (int index = files.size() - 1; index >= 0; index--) {
            try {
                files.get(index).source.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class VerifiedCodexFile {
        private final WorkspaceFileAccess.Source source;
        private final CodexFileMention mention;

        private VerifiedCodexFile(
            WorkspaceFileAccess.Source source,
            CodexFileMention mention
        ) {
            this.source = source;
            this.mention = mention;
        }
    }

    private final class PreparedCodexFiles
        implements CodexFileMentionTransaction {
        private final Path workspace;
        private final List<ImportedWorkspaceFile> importedFiles;
        private final WorkspaceFileAccess.Opener verificationOpener;
        private final List<VerifiedCodexFile> openedFiles =
            new ArrayList<VerifiedCodexFile>();
        private boolean claimed;
        private boolean closed;

        private PreparedCodexFiles(
            Path verifiedWorkspace,
            List<ImportedWorkspaceFile> files,
            WorkspaceFileAccess.Opener opener
        ) {
            workspace = verifiedWorkspace;
            importedFiles = files;
            verificationOpener = opener;
        }

        @Override
        public int getFileCount() {
            return importedFiles.size();
        }

        @Override
        public synchronized void withVerifiedMentions(VerifiedSender sender)
            throws Exception {
            if (sender == null) {
                throw new IllegalArgumentException("Verified attachment sender is required");
            }
            if (closed || claimed) {
                throw new IOException("Prepared attachment batch is no longer available");
            }
            claimed = true;
            Throwable failure = null;
            try {
                List<CodexFileMention> values =
                    new ArrayList<CodexFileMention>(importedFiles.size());
                for (ImportedWorkspaceFile imported : importedFiles) {
                    VerifiedCodexFile verified = openVerifiedForCodex(
                        workspace,
                        imported,
                        verificationOpener
                    );
                    openedFiles.add(verified);
                    values.add(verified.mention);
                }
                final List<CodexFileMention> mentions =
                    Collections.unmodifiableList(values);
                verifyBatchUnchanged();
                final boolean[] guardInvoked = new boolean[] {false};
                sender.send(mentions, new SendGuard() {
                    @Override
                    public void verifyUnchanged() throws IOException {
                        if (guardInvoked[0]) {
                            throw new IOException(
                                "Prepared attachment send guard was already consumed"
                            );
                        }
                        guardInvoked[0] = true;
                        verifyBatchUnchanged();
                    }
                });
                if (!guardInvoked[0]) {
                    throw new IOException(
                        "Prepared attachment batch was not revalidated at RPC write"
                    );
                }
            } catch (Throwable error) {
                failure = error;
                if (error instanceof Exception) {
                    throw (Exception) error;
                }
                if (error instanceof Error) {
                    throw (Error) error;
                }
                throw new IllegalStateException("Verified attachment send failed", error);
            } finally {
                try {
                    close();
                } catch (IOException closeError) {
                    if (failure != null) {
                        failure.addSuppressed(closeError);
                    } else {
                        throw closeError;
                    }
                }
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            closeVerifiedFiles(openedFiles, null);
        }

        private void verifyBatchUnchanged() throws IOException {
            for (VerifiedCodexFile file : openedFiles) {
                file.source.verifyUnchanged();
            }
        }
    }

    private static String lowerHexAndWipe(byte[] value) {
        char[] hex = new char[value.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        try {
            for (int index = 0; index < value.length; index++) {
                int current = value[index] & 0xff;
                hex[index * 2] = alphabet[current >>> 4];
                hex[index * 2 + 1] = alphabet[current & 0x0f];
            }
            return new String(hex);
        } finally {
            Arrays.fill(value, (byte) 0);
            Arrays.fill(hex, '\0');
        }
    }

    private static byte[] decodeSha256(String value) {
        byte[] decoded = new byte[value.length() / 2];
        for (int index = 0; index < decoded.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static Path requireWorkspace(File workspaceDirectory) throws IOException {
        if (workspaceDirectory == null) {
            throw new IllegalArgumentException("Workspace directory must not be null");
        }
        File canonical = workspaceDirectory.getCanonicalFile();
        Path path = canonical.toPath();
        if (!canonical.equals(workspaceDirectory)
            || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workspace root is not a canonical directory");
        }
        return path;
    }

    private static Path requireImportsDirectory(Path workspace, File importsDirectory)
        throws IOException {
        if (importsDirectory == null) {
            throw new IllegalArgumentException("Imports directory must not be null");
        }
        File canonical = importsDirectory.getCanonicalFile();
        Path path = canonical.toPath();
        Path expected = workspace.resolve(
            WorkspaceImportLimits.IMPORT_DIRECTORY_NAME
        );
        if (!canonical.equals(importsDirectory)
            || !path.equals(expected)
            || Files.isSymbolicLink(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workspace imports root is not canonical");
        }
        return path;
    }

    private static BasicFileAttributes readRegularAttributes(
        SecureDirectoryStream<Path> directory,
        Path name
    ) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
            name,
            BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            throw new IOException("Filesystem cannot inspect imported workspace files");
        }
        BasicFileAttributes attributes = view.readAttributes();
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Imported workspace entry is not a regular file");
        }
        return attributes;
    }

    private static void enforceOwnerFilePermissions(
        SecureDirectoryStream<Path> directory,
        Path name
    ) throws IOException {
        PosixFileAttributeView view = directory.getFileAttributeView(
            name,
            PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            throw new IOException("Filesystem cannot enforce private import permissions");
        }
        view.setPermissions(OWNER_FILE_PERMISSIONS);
        PosixFileAttributes attributes = view.readAttributes();
        if (!attributes.permissions().equals(OWNER_FILE_PERMISSIONS)) {
            throw new IOException("Imported workspace file is not owner-only");
        }
    }

    private static void requireMissing(
        SecureDirectoryStream<Path> directory,
        Path name
    ) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
            name,
            BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            throw new IOException("Filesystem cannot reserve an imported workspace name");
        }
        try {
            view.readAttributes();
            throw new IOException("Random imported workspace name already exists");
        } catch (NoSuchFileException expected) {
            // The random final name is free within the held directory handle.
        }
    }

    private static void deleteFileIfPresent(
        SecureDirectoryStream<Path> directory,
        Path name
    ) throws IOException {
        try {
            directory.deleteFile(name);
        } catch (NoSuchFileException ignored) {
            // A failed import has no remaining entry to clean up.
        }
    }

    private static Path relativeName(Path imports, String value) {
        return imports.getFileSystem().getPath(value);
    }

    private static void requireNotInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Document import was cancelled");
        }
    }

    private static String sanitizeDisplayName(String proposed) throws IOException {
        if (proposed != null
            && proposed.length() > WorkspaceImportLimits.MAXIMUM_DISPLAY_NAME_CHARACTERS * 8) {
            throw new IOException("Selected document name exceeds the import limit");
        }
        String candidate = proposed == null ? "" : proposed.trim();
        if (!candidate.isEmpty()
            && (CredentialGuard.containsLikelyCredential(candidate)
                || CredentialGuard.isLikelyCredentialFileName(candidate))) {
            throw new IOException("Credential-shaped document names cannot be imported");
        }
        String safe = sanitizeName(
            candidate,
            WorkspaceImportLimits.MAXIMUM_DISPLAY_NAME_CHARACTERS
        );
        return safe.isEmpty() ? "imported-file" : safe;
    }

    private static String safeStorageExtension(String displayName) {
        int dot = displayName.lastIndexOf('.');
        if (dot <= 0 || dot == displayName.length() - 1
            || displayName.length() - dot - 1 > 12) {
            return "";
        }
        String extension = displayName.substring(dot + 1);
        for (int index = 0; index < extension.length(); index++) {
            char character = extension.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')) {
                return "";
            }
        }
        return "." + extension.toLowerCase(Locale.ROOT);
    }

    private static String sanitizeName(String value, int maximumCharacters) {
        StringBuilder safe = new StringBuilder();
        boolean previousSpace = false;
        for (int index = 0; index < value.length() && safe.length() < maximumCharacters;
             index++) {
            char character = value.charAt(index);
            int type = Character.getType(character);
            if (character == '/' || character == '\\' || character == ':'
                || Character.isISOControl(character)
                || type == Character.FORMAT || type == Character.SURROGATE) {
                character = '_';
            } else if (Character.isWhitespace(character)) {
                character = ' ';
            }
            if (character == ' ' && previousSpace) {
                continue;
            }
            safe.append(character);
            previousSpace = character == ' ';
        }
        while (safe.length() > 0
            && (safe.charAt(safe.length() - 1) == ' '
                || safe.charAt(safe.length() - 1) == '.')) {
            safe.setLength(safe.length() - 1);
        }
        int first = 0;
        while (first < safe.length() && safe.charAt(first) == ' ') {
            first++;
        }
        String result = safe.substring(first);
        return ".".equals(result) || "..".equals(result) ? "imported-file" : result;
    }

    private static String sanitizeMediaType(String proposed) {
        String value = proposed == null ? "" : proposed.trim().toLowerCase(Locale.ROOT);
        int parameters = value.indexOf(';');
        if (parameters >= 0) {
            value = value.substring(0, parameters).trim();
        }
        if (value.length() > WorkspaceImportLimits.MAXIMUM_MEDIA_TYPE_CHARACTERS
            || !isMediaType(value)) {
            return "application/octet-stream";
        }
        return value;
    }

    private static boolean isMediaType(String value) {
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/') || slash == value.length() - 1) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/') {
                continue;
            }
            if (!(character >= 'a' && character <= 'z')
                && !(character >= '0' && character <= '9')
                && character != '!' && character != '#' && character != '$'
                && character != '&' && character != '^' && character != '_'
                && character != '.' && character != '+' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static String randomToken() {
        byte[] bytes = new byte[RANDOM_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        char[] hex = new char[bytes.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            hex[index * 2] = alphabet[value >>> 4];
            hex[index * 2 + 1] = alphabet[value & 0x0f];
        }
        Arrays.fill(bytes, (byte) 0);
        return new String(hex);
    }
}
