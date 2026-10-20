package de.agentcodi.storage;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.FileChannel;
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
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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

        /** Unix status-change time captured with the opened file's identity. */
        FileTime getChangeTime();

        Object getFileKey();

        int read(byte[] buffer, int offset, int length) throws IOException;

        default void position(long absoluteOffset) throws IOException {
            throw new UnsupportedOperationException(
                "Workspace source does not support direct positioning"
            );
        }

        void verifyUnchanged() throws IOException;

        /** Atomically replace this revision; a successful replacement consumes the snapshot. */
        default void replace(byte[] contents) throws IOException {
            throw new IOException("Workspace source does not support editing");
        }
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
                    opened.attributes,
                    opened.changeTime
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
        private final FileTime changeTime;
        private boolean closed;

        private SecureNioSource(
            SecureDirectoryStream<Path> root,
            SeekableByteChannel channel,
            Path workspace,
            Path relative,
            long maximumBytes,
            BasicFileAttributes attributes,
            FileTime changeTime
        ) {
            this.root = root;
            this.channel = channel;
            this.workspace = workspace;
            this.relative = relative;
            this.maximumBytes = maximumBytes;
            this.attributes = attributes;
            this.changeTime = changeTime;
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
        public FileTime getChangeTime() {
            return changeTime;
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
        public void position(long absoluteOffset) throws IOException {
            if (closed) {
                throw new IOException("Workspace file source is closed");
            }
            if (absoluteOffset < 0L || absoluteOffset > attributes.size()) {
                throw new IOException("Workspace file preview position is invalid");
            }
            channel.position(absoluteOffset);
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
                if (!sameSnapshot(attributes, current.attributes)
                    || !changeTime.equals(current.changeTime)) {
                    throw new IOException("Workspace file changed during export");
                }
            } finally {
                current.channel.close();
            }
        }

        @Override
        public void replace(byte[] contents) throws IOException {
            if (contents == null || contents.length > maximumBytes) {
                throw new IOException("Workspace replacement is outside the edit limit");
            }
            verifyUnchanged();
            SecureDirectoryStream<Path> parent = root;
            Path temporary = relative.getFileSystem().getPath(
                ".agentcodi-edit-" + UUID.randomUUID().toString()
            );
            boolean created = false;
            try {
                for (int index = 0; index < relative.getNameCount() - 1; index++) {
                    SecureDirectoryStream<Path> child = parent.newDirectoryStream(
                        relative.getName(index), LinkOption.NOFOLLOW_LINKS
                    );
                    if (parent != root) {
                        parent.close();
                    }
                    parent = child;
                }
                Path name = relative.getFileName();
                PosixFileAttributeView original = parent.getFileAttributeView(
                    name, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
                );
                if (original == null) {
                    throw new IOException("Filesystem cannot preserve workspace permissions");
                }
                Set<PosixFilePermission> permissions = original.readAttributes().permissions();
                Set<OpenOption> options = new HashSet<OpenOption>();
                options.add(StandardOpenOption.WRITE);
                options.add(StandardOpenOption.CREATE_NEW);
                options.add(LinkOption.NOFOLLOW_LINKS);
                try (SeekableByteChannel target = parent.newByteChannel(
                    temporary, options, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                    )
                )) {
                    created = true;
                    ByteBuffer bytes = ByteBuffer.wrap(contents);
                    while (bytes.hasRemaining()) {
                        if (target.write(bytes) <= 0) {
                            throw new IOException("Workspace replacement made no progress");
                        }
                    }
                    parent.getFileAttributeView(
                        temporary, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS
                    ).setPermissions(permissions);
                    if (!(target instanceof FileChannel)) {
                        throw new IOException("Filesystem cannot sync workspace edits");
                    }
                    ((FileChannel) target).force(true);
                }
                verifyUnchanged();
                // Also validate the destination in the exact parent used by the rename.
                BasicFileAttributes destination = readRegularAttributes(parent, name, maximumBytes);
                if (!sameSnapshot(attributes, destination)) {
                    throw new IOException("Workspace file changed before saving");
                }
                parent.move(temporary, parent, name);
                created = false;
            } finally {
                try {
                    if (created) {
                        parent.deleteFile(temporary);
                    }
                } finally {
                    if (parent != root) {
                        parent.close();
                    }
                }
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
            FileTime beforeChangeTime = WorkspaceFileBoundary.requireSingleLink(
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
            FileTime afterChangeTime = WorkspaceFileBoundary.requireSingleLink(
                workspace.resolve(relative),
                after.fileKey()
            );
            if (channel.size() != after.size() || !sameSnapshot(before, after)
                || !beforeChangeTime.equals(afterChangeTime)) {
                throw new IOException("Workspace file changed while it was opened");
            }
            if (parent != root) {
                parent.close();
                parent = root;
            }
            OpenSnapshot opened = new OpenSnapshot(channel, after, afterChangeTime);
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
        private final FileTime changeTime;

        private OpenSnapshot(
            SeekableByteChannel channel,
            BasicFileAttributes attributes,
            FileTime changeTime
        ) {
            this.channel = channel;
            this.attributes = attributes;
            this.changeTime = changeTime;
        }
    }
}
