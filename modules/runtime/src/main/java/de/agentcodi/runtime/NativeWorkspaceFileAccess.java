package de.agentcodi.runtime;

import de.agentcodi.storage.WorkspaceFileAccess;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;

final class NativeWorkspaceFileAccess {
    private static final int METADATA_LENGTH = 7;
    private static final WorkspaceFileAccess.Opener OPENER = new NativeOpener();

    private NativeWorkspaceFileAccess() {
    }

    static WorkspaceFileAccess.Opener opener() {
        return OPENER;
    }

    private static final class NativeOpener implements WorkspaceFileAccess.Opener {
        @Override
        public WorkspaceFileAccess.Source open(
            File workspaceDirectory,
            String relativePath,
            long maximumBytes
        ) throws IOException {
            if (workspaceDirectory == null) {
                throw new IllegalArgumentException("workspaceDirectory must not be null");
            }
            long handle = NativeEngine.openWorkspaceFile(
                workspaceDirectory.getCanonicalPath(),
                relativePath,
                maximumBytes
            );
            boolean accepted = false;
            try {
                long[] metadata = NativeEngine.workspaceFileMetadata(handle);
                NativeSource source = new NativeSource(handle, metadata, maximumBytes);
                accepted = true;
                return source;
            } finally {
                if (!accepted) {
                    NativeEngine.closeWorkspaceFile(handle);
                }
            }
        }
    }

    private static final class NativeSource implements WorkspaceFileAccess.Source {
        private long handle;
        private final long byteCount;
        private final FileTime lastModifiedTime;
        private final String fileKey;

        private NativeSource(long handle, long[] metadata, long maximumBytes)
            throws IOException {
            if (handle <= 0L || metadata == null || metadata.length != METADATA_LENGTH) {
                throw new IOException("Native workspace file metadata is invalid");
            }
            if (metadata[0] < 0L || metadata[0] > maximumBytes
                || metadata[2] < 0L || metadata[2] > 999999999L
                || metadata[4] < 0L || metadata[4] > 999999999L) {
                throw new IOException("Native workspace file metadata is outside its bounds");
            }
            final FileTime modified;
            try {
                modified = FileTime.from(
                    Instant.ofEpochSecond(metadata[1], metadata[2])
                );
                Instant.ofEpochSecond(metadata[3], metadata[4]);
            } catch (DateTimeException error) {
                throw new IOException("Native workspace file timestamp is invalid", error);
            }
            this.handle = handle;
            this.byteCount = metadata[0];
            this.lastModifiedTime = modified;
            this.fileKey = "(dev=" + Long.toHexString(metadata[5])
                + ",ino=" + Long.toUnsignedString(metadata[6]) + ")";
        }

        @Override
        public long getByteCount() {
            return byteCount;
        }

        @Override
        public FileTime getLastModifiedTime() {
            return lastModifiedTime;
        }

        @Override
        public Object getFileKey() {
            return fileKey;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length)
            throws IOException {
            requireOpen();
            if (buffer == null) {
                throw new NullPointerException("buffer");
            }
            if (offset < 0 || length < 0 || offset > buffer.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            return NativeEngine.readWorkspaceFile(handle, buffer, offset, length);
        }

        @Override
        public synchronized void position(long absoluteOffset) throws IOException {
            requireOpen();
            if (absoluteOffset < 0L || absoluteOffset > byteCount) {
                throw new IOException("Workspace file preview position is invalid");
            }
            NativeEngine.positionWorkspaceFile(handle, absoluteOffset);
        }

        @Override
        public synchronized void verifyUnchanged() throws IOException {
            requireOpen();
            NativeEngine.verifyWorkspaceFile(handle);
        }

        @Override
        public synchronized void close() {
            if (handle == 0L) {
                return;
            }
            long closing = handle;
            handle = 0L;
            NativeEngine.closeWorkspaceFile(closing);
        }

        private void requireOpen() throws IOException {
            if (handle == 0L) {
                throw new IOException("Workspace file source is closed");
            }
        }
    }
}
