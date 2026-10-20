package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded complete-file access for edits, using the existing no-follow source boundary. */
public final class WorkspaceFileEdit {
    public interface Creator {
        void create(File workspace, String relativePath, byte[] contents) throws IOException;
    }

    private WorkspaceFileEdit() {
    }

    public static byte[] read(
        File workspace, String relativePath, int maximumBytes, WorkspaceFileAccess.Opener opener
    ) throws IOException {
        try (WorkspaceFileBoundary.OpenedFile opened = open(
            workspace, relativePath, maximumBytes, opener
        )) {
            return readAll(opened.source);
        }
    }

    public static void replace(
        File workspace, String relativePath, byte[] expected, byte[] replacement,
        int maximumBytes, WorkspaceFileAccess.Opener opener
    ) throws IOException {
        if (expected == null || replacement == null || replacement.length > maximumBytes
            || expected.length > maximumBytes) {
            throw new IllegalArgumentException("Editor byte limits are invalid");
        }
        try (WorkspaceFileBoundary.OpenedFile opened = open(
            workspace, relativePath, maximumBytes, opener
        )) {
            if (!Arrays.equals(expected, readAll(opened.source))) {
                throw new FileChangedException();
            }
            opened.source.replace(replacement);
        }
    }

    /** Preflight for a new draft. The actual creator must check absence atomically. */
    public static void checkCreate(File workspace, String relativePath) throws IOException {
        String checked = requireRelativePath(relativePath);
        Path parent = WorkspaceFileBoundary.requireWorkspace(workspace);
        String[] components = checked.split("/", -1);
        for (int index = 0; index < components.length - 1; index++) {
            parent = parent.resolve(components[index]);
            BasicFileAttributes attributes = Files.readAttributes(
                parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("New editor file parent is not a regular directory");
            }
        }
        if (Files.exists(parent.resolve(components[components.length - 1]), LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(relativePath);
        }
    }

    public static void create(File workspace, String relativePath, byte[] contents,
        int maximumBytes, Creator creator) throws IOException {
        if (contents == null || contents.length > maximumBytes || creator == null) {
            throw new IllegalArgumentException("New editor file byte limits are invalid");
        }
        String checked = requireRelativePath(relativePath);
        WorkspaceFileBoundary.requireWorkspace(workspace);
        creator.create(workspace, checked, contents);
    }

    /** Host-only creator. Android supplies its atomic no-replace native installer. */
    public static Creator secureNioCreator() {
        return new Creator() {
            @Override public void create(File workspace, String relativePath, byte[] contents)
                throws IOException {
                Path relative = workspace.toPath().getFileSystem().getPath(requireRelativePath(relativePath));
                WorkspaceFileBoundary.requireWorkspace(workspace);
                try (DirectoryStream<Path> rawRoot = Files.newDirectoryStream(workspace.toPath())) {
                    if (!(rawRoot instanceof SecureDirectoryStream<?>)) {
                        throw new IOException("Filesystem cannot create workspace files without path races");
                    }
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> root = (SecureDirectoryStream<Path>) rawRoot;
                    SecureDirectoryStream<Path> parent = root;
                    Path name = relative.getFileName();
                    boolean complete = false;
                    Object createdKey = null;
                    try {
                        for (int index = 0; index < relative.getNameCount() - 1; index++) {
                            SecureDirectoryStream<Path> child = parent.newDirectoryStream(
                                relative.getName(index), LinkOption.NOFOLLOW_LINKS
                            );
                            if (parent != root) { parent.close(); }
                            parent = child;
                        }
                        Set<OpenOption> options = new HashSet<OpenOption>();
                        options.add(StandardOpenOption.WRITE);
                        options.add(StandardOpenOption.CREATE_NEW);
                        options.add(LinkOption.NOFOLLOW_LINKS);
                        try (SeekableByteChannel target = parent.newByteChannel(name, options,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                        )) {
                            createdKey = parent.getFileAttributeView(name, BasicFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey();
                            ByteBuffer bytes = ByteBuffer.wrap(contents);
                            while (bytes.hasRemaining()) {
                                if (target.write(bytes) <= 0) {
                                    throw new IOException("New editor file write made no progress");
                                }
                            }
                            if (!(target instanceof FileChannel)) {
                                throw new IOException("Filesystem cannot sync new editor files");
                            }
                            ((FileChannel) target).force(true);
                        }
                        complete = true;
                    } finally {
                        try {
                            if (!complete && createdKey != null) {
                                BasicFileAttributes current = parent.getFileAttributeView(name,
                                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                                if (createdKey.equals(current.fileKey())) { parent.deleteFile(name); }
                            }
                        } finally {
                            if (parent != root) { parent.close(); }
                        }
                    }
                }
            }
        };
    }

    private static WorkspaceFileBoundary.OpenedFile open(
        File workspace, String relativePath, int maximumBytes, WorkspaceFileAccess.Opener opener
    ) throws IOException {
        String checked = requireRelativePath(relativePath);
        return WorkspaceFileBoundary.openRegularFile(
            workspace, new File(workspace, checked).getAbsolutePath(), maximumBytes, opener
        );
    }

    private static String requireRelativePath(String relativePath) throws IOException {
        String checked = WorkspaceFileBoundary.validateRelativePath(relativePath, 2048);
        if (!checked.equals(relativePath) || relativePath.endsWith("/")
            || relativePath.split("/", -1).length > 64) {
            throw new IOException("Editor path is unsafe");
        }
        return checked;
    }

    private static byte[] readAll(WorkspaceFileAccess.Source source) throws IOException {
        byte[] bytes = new byte[(int) source.getByteCount()];
        int offset = 0;
        while (offset < bytes.length) {
            int count = source.read(bytes, offset, Math.min(64 * 1024, bytes.length - offset));
            if (count <= 0) {
                throw new IOException("Editor source changed during reading");
            }
            offset += count;
        }
        source.verifyUnchanged();
        return bytes;
    }

    public static final class FileChangedException extends IOException {
        public FileChangedException() { super("Editor source has changed"); }
    }
}
