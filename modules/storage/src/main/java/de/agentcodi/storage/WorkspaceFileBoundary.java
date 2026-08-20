package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;

final class WorkspaceFileBoundary {
    private WorkspaceFileBoundary() {
    }

    static ResolvedFile resolveRegularFile(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes
    ) throws IOException {
        if (requestedPath == null || requestedPath.trim().isEmpty()) {
            throw new IllegalArgumentException("requestedPath must not be blank");
        }
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("maximumBytes must not be negative");
        }
        Path workspace = requireWorkspace(workspaceDirectory);
        File requested = new File(requestedPath);
        if (!requested.isAbsolute()) {
            throw new IOException("Workspace file path must be absolute");
        }
        Path normalized = requested.toPath().toAbsolutePath().normalize();
        File candidate = normalized.toFile().getCanonicalFile();
        Path candidatePath = candidate.toPath();
        if (candidatePath.equals(workspace) || !candidatePath.startsWith(workspace)) {
            throw new IOException("Workspace file path escaped the private workspace");
        }
        rejectSymbolicComponents(workspace, normalized);
        BasicFileAttributes attributes = Files.readAttributes(
            candidatePath,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile()) {
            throw new IOException("Workspace file does not exist as a regular file");
        }
        requireSingleLink(candidatePath);
        long byteCount = attributes.size();
        if (byteCount > maximumBytes) {
            throw new IOException("Workspace file size is outside the export limit");
        }
        String relativePath = validateRelativePath(
            workspace.relativize(candidatePath).toString(),
            Integer.MAX_VALUE
        );
        return new ResolvedFile(
            candidate,
            relativePath,
            byteCount,
            attributes.lastModifiedTime(),
            attributes.fileKey()
        );
    }

    static OpenedFile openRegularFile(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        if (opener == null) {
            throw new IllegalArgumentException("opener must not be null");
        }
        ResolvedFile resolved = resolveRegularFile(
            workspaceDirectory,
            requestedPath,
            maximumBytes
        );
        Path workspace = requireWorkspace(workspaceDirectory);
        WorkspaceFileAccess.Source source = opener.open(
            workspace.toFile(),
            resolved.relativePath,
            maximumBytes
        );
        boolean accepted = false;
        try {
            long byteCount = source.getByteCount();
            if (byteCount < 0L || byteCount > maximumBytes) {
                throw new IOException("Workspace file size is outside the export limit");
            }
            if (source.getLastModifiedTime() == null) {
                throw new IOException("Workspace file timestamp is unavailable");
            }
            OpenedFile opened = new OpenedFile(
                resolved.file,
                resolved.relativePath,
                source
            );
            accepted = true;
            return opened;
        } finally {
            if (!accepted) {
                source.close();
            }
        }
    }

    static Path requireWorkspace(File workspaceDirectory) throws IOException {
        if (workspaceDirectory == null) {
            throw new IllegalArgumentException("workspaceDirectory must not be null");
        }
        File workspace = workspaceDirectory.getCanonicalFile();
        Path workspacePath = workspace.toPath();
        if (Files.isSymbolicLink(workspacePath)
            || !Files.isDirectory(workspacePath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Workspace root is not a canonical directory");
        }
        return workspacePath;
    }

    static String validateRelativePath(String relativePath, int maximumCharacters)
        throws IOException {
        if (relativePath == null || relativePath.isEmpty()
            || relativePath.length() > maximumCharacters) {
            throw new IOException("Workspace relative path is outside the export limit");
        }
        for (int index = 0; index < relativePath.length(); index++) {
            char character = relativePath.charAt(index);
            if (character < 0x20 || character == 0x7f
                || character == '\\' || character == ':') {
                throw new IOException("Workspace relative path is unsafe");
            }
        }
        Path relative = new File(relativePath).toPath().normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0
            || "..".equals(relative.getName(0).toString())) {
            throw new IOException("Workspace relative path is unsafe");
        }
        String portable = relative.toString().replace(File.separatorChar, '/');
        if (portable.startsWith("/") || portable.contains("/../")
            || portable.equals("..")) {
            throw new IOException("Workspace relative path is unsafe");
        }
        return portable;
    }

    static void requireSingleLink(Path path) throws IOException {
        requireSingleLink(path, null);
    }

    static void requireSingleLink(Path path, Object expectedFileKey) throws IOException {
        final Map<String, Object> attributes;
        try {
            attributes = Files.readAttributes(
                path,
                "unix:nlink,fileKey",
                LinkOption.NOFOLLOW_LINKS
            );
        } catch (UnsupportedOperationException error) {
            throw new IOException("Filesystem cannot verify the workspace hard-link boundary", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Filesystem cannot verify the workspace hard-link boundary", error);
        }
        Object value = attributes.get("nlink");
        if (!(value instanceof Number) || ((Number) value).longValue() != 1L) {
            throw new IOException("Hard-linked workspace files are not exportable");
        }
        Object fileKey = attributes.get("fileKey");
        if (expectedFileKey != null && !expectedFileKey.equals(fileKey)) {
            throw new IOException("Workspace file changed while its link count was checked");
        }
    }

    private static void rejectSymbolicComponents(Path workspace, Path requested)
        throws IOException {
        Path current = requested.getRoot();
        boolean workspaceReached = false;
        for (Path component : requested) {
            current = current.resolve(component);
            if (!workspaceReached) {
                Path canonicalCurrent = current.toFile().getCanonicalFile().toPath();
                if (canonicalCurrent.equals(workspace)) {
                    workspaceReached = true;
                } else if (canonicalCurrent.startsWith(workspace)) {
                    throw new IOException(
                        "Workspace path entered below the canonical workspace root"
                    );
                }
            } else if (Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic workspace file paths are not accepted");
            }
        }
        if (!workspaceReached) {
            throw new IOException("Workspace path did not enter the canonical workspace");
        }
    }

    static final class ResolvedFile {
        final File file;
        final String relativePath;
        final long byteCount;
        final FileTime lastModifiedTime;
        final Object fileKey;

        ResolvedFile(
            File file,
            String relativePath,
            long byteCount,
            FileTime lastModifiedTime,
            Object fileKey
        ) {
            this.file = file;
            this.relativePath = relativePath;
            this.byteCount = byteCount;
            this.lastModifiedTime = lastModifiedTime;
            this.fileKey = fileKey;
        }
    }

    static final class OpenedFile implements AutoCloseable {
        final File file;
        final String relativePath;
        final WorkspaceFileAccess.Source source;

        OpenedFile(
            File file,
            String relativePath,
            WorkspaceFileAccess.Source source
        ) {
            this.file = file;
            this.relativePath = relativePath;
            this.source = source;
        }

        long getByteCount() {
            return source.getByteCount();
        }

        FileTime getLastModifiedTime() {
            return source.getLastModifiedTime();
        }

        Object getFileKey() {
            return source.getFileKey();
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }
}
