package de.agentcodi.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Race-resistant access to regular files below an already validated workspace.
 *
 * <p>The Android runtime supplies an {@link Opener} backed by {@code openat} and
 * {@code fstat}. The default implementation exists for Android-independent host
 * use and requires a filesystem provider with {@link SecureDirectoryStream}
 * support. Neither implementation follows a workspace path component while a
 * file is being opened.</p>
 */
public final class WorkspaceFileAccess {
    private static final Opener SECURE_NIO_OPENER = new SecureNioOpener();

    private WorkspaceFileAccess() {
    }

    public static Opener secureNioOpener() {
        return SECURE_NIO_OPENER;
    }

    public interface Opener {
        Source open(
            File workspaceDirectory,
            String relativePath,
            long maximumBytes
        ) throws IOException;
    }

    public interface Source extends Closeable {
        long getByteCount();

        FileTime getLastModifiedTime();

        Object getFileKey();

        int read(byte[] buffer, int offset, int length) throws IOException;

        void verifyUnchanged() throws IOException;
    }

    private static final class SecureNioOpener implements Opener {
        @Override
        public Source open(
            File workspaceDirectory,
            String relativePath,
            long maximumBytes
        ) throws IOException {
            if (workspaceDirectory == null) {
                throw new IllegalArgumentException("workspaceDirectory must not be null");
            }
            if (maximumBytes < 0L) {
                throw new IllegalArgumentException("maximumBytes must not be negative");
            }
            Path workspace = workspaceDirectory.toPath();
            Path relative = requireRelativePath(workspace, relativePath);
            DirectoryStream<Path> rawRoot = Files.newDirectoryStream(workspace);
            if (!(rawRoot instanceof SecureDirectoryStream<?>)) {
                rawRoot.close();
                throw new IOException(
                    "Filesystem cannot open workspace files without path races"
                );
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> root =
                (SecureDirectoryStream<Path>) rawRoot;
            SeekableByteChannel channel = null;
            try {
                OpenSnapshot opened = openRelative(root, workspace, relative, maximumBytes);
                channel = opened.channel;
                return new SecureNioSource(
                    root,
                    channel,
                    workspace,
                    relative,
                    maximumBytes,
                    opened.attributes
                );
            } catch (IOException | RuntimeException | Error error) {
                closeAfterFailure(channel, root, error);
                throw error;
            }
        }
    }

    private static final class SecureNioSource implements Source {
        private final SecureDirectoryStream<Path> root;
        private final SeekableByteChannel channel;
        private final Path workspace;
        private final Path relative;
        private final long maximumBytes;
        private final BasicFileAttributes attributes;
        private boolean closed;

        private SecureNioSource(
            SecureDirectoryStream<Path> root,
            SeekableByteChannel channel,
            Path workspace,
            Path relative,
            long maximumBytes,
            BasicFileAttributes attributes
        ) {
            this.root = root;
            this.channel = channel;
            this.workspace = workspace;
            this.relative = relative;
            this.maximumBytes = maximumBytes;
            this.attributes = attributes;
        }

        @Override
        public long getByteCount() {
            return attributes.size();
        }

        @Override
        public FileTime getLastModifiedTime() {
            return attributes.lastModifiedTime();
        }

        @Override
        public Object getFileKey() {
            return attributes.fileKey();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) {
                throw new NullPointerException("buffer");
            }
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (closed) {
                throw new IOException("Workspace file source is closed");
            }
            if (length == 0) {
                return 0;
            }
            return channel.read(ByteBuffer.wrap(buffer, offset, length));
        }

        @Override
        public void verifyUnchanged() throws IOException {
            if (closed) {
                throw new IOException("Workspace file source is closed");
            }
            if (channel.size() != attributes.size()) {
                throw new IOException("Workspace file changed during export");
            }
            OpenSnapshot current = openRelative(root, workspace, relative, maximumBytes);
            try {
                if (!sameSnapshot(attributes, current.attributes)) {
                    throw new IOException("Workspace file changed during export");
                }
            } finally {
                current.channel.close();
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                channel.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                root.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static OpenSnapshot openRelative(
        SecureDirectoryStream<Path> root,
        Path workspace,
        Path relative,
        long maximumBytes
    ) throws IOException {
        SecureDirectoryStream<Path> parent = root;
        SeekableByteChannel channel = null;
        try {
            for (int index = 0; index < relative.getNameCount() - 1; index++) {
                SecureDirectoryStream<Path> child = parent.newDirectoryStream(
                    relative.getName(index),
                    LinkOption.NOFOLLOW_LINKS
                );
                if (parent != root) {
                    parent.close();
                }
                parent = child;
            }
            Path name = relative.getName(relative.getNameCount() - 1);
            BasicFileAttributes before = readRegularAttributes(parent, name, maximumBytes);
            WorkspaceFileBoundary.requireSingleLink(
                workspace.resolve(relative),
                before.fileKey()
            );
            Set<OpenOption> options = new HashSet<OpenOption>();
            options.add(StandardOpenOption.READ);
            options.add(LinkOption.NOFOLLOW_LINKS);
            channel = parent.newByteChannel(
                name,
                Collections.unmodifiableSet(options)
            );
            BasicFileAttributes after = readRegularAttributes(
                parent,
                name,
                maximumBytes
            );
            WorkspaceFileBoundary.requireSingleLink(
                workspace.resolve(relative),
                after.fileKey()
            );
            if (channel.size() != after.size() || !sameSnapshot(before, after)) {
                throw new IOException("Workspace file changed while it was opened");
            }
            if (parent != root) {
                parent.close();
                parent = root;
            }
            OpenSnapshot opened = new OpenSnapshot(channel, after);
            channel = null;
            return opened;
        } catch (IOException | RuntimeException | Error error) {
            closeAfterFailure(channel, parent == root ? null : parent, error);
            throw error;
        }
    }

    private static BasicFileAttributes readRegularAttributes(
        SecureDirectoryStream<Path> parent,
        Path name,
        long maximumBytes
    ) throws IOException {
        BasicFileAttributeView view = parent.getFileAttributeView(
            name,
            BasicFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            throw new IOException("Filesystem cannot validate workspace file attributes");
        }
        BasicFileAttributes attributes = view.readAttributes();
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
            throw new IOException("Workspace file does not exist as a regular file");
        }
        if (attributes.size() < 0L || attributes.size() > maximumBytes) {
            throw new IOException("Workspace file size is outside the export limit");
        }
        return attributes;
    }

    private static Path requireRelativePath(Path workspace, String relativePath)
        throws IOException {
        String portable = WorkspaceFileBoundary.validateRelativePath(
            relativePath,
            Integer.MAX_VALUE
        );
        Path relative = workspace.getFileSystem().getPath(portable).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0) {
            throw new IOException("Workspace relative path is unsafe");
        }
        return relative;
    }

    private static boolean sameSnapshot(
        BasicFileAttributes left,
        BasicFileAttributes right
    ) {
        return left.size() == right.size()
            && sameValue(left.lastModifiedTime(), right.lastModifiedTime())
            && sameValue(left.fileKey(), right.fileKey());
    }

    private static boolean sameValue(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static void closeAfterFailure(
        Closeable first,
        Closeable second,
        Throwable failure
    ) {
        closeOneAfterFailure(first, failure);
        closeOneAfterFailure(second, failure);
    }

    private static void closeOneAfterFailure(Closeable closeable, Throwable failure) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable closeError) {
            failure.addSuppressed(closeError);
        }
    }

    private static final class OpenSnapshot {
        private final SeekableByteChannel channel;
        private final BasicFileAttributes attributes;

        private OpenSnapshot(
            SeekableByteChannel channel,
            BasicFileAttributes attributes
        ) {
            this.channel = channel;
            this.attributes = attributes;
        }
    }
}
