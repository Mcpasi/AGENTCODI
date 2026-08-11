package de.agentcodi.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class WorkspaceExportFile {
    private final File file;
    private final String relativePath;
    private final String displayName;
    private final long byteCount;
    private final FileTime lastModifiedTime;
    private final Object fileKey;

    private WorkspaceExportFile(
        File file,
        String relativePath,
        long byteCount,
        FileTime lastModifiedTime,
        Object fileKey
    ) {
        this.file = file;
        this.relativePath = relativePath;
        this.displayName = safeDisplayName(file.getName());
        this.byteCount = byteCount;
        this.lastModifiedTime = lastModifiedTime;
        this.fileKey = fileKey;
    }

    public static WorkspaceExportFile inspect(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes
    ) throws IOException {
        WorkspaceFileBoundary.ResolvedFile resolved =
            WorkspaceFileBoundary.resolveRegularFile(
                workspaceDirectory,
                requestedPath,
                maximumBytes
            );
        return new WorkspaceExportFile(
            resolved.file,
            resolved.relativePath,
            resolved.byteCount,
            resolved.lastModifiedTime,
            resolved.fileKey
        );
    }

    public static List<WorkspaceExportFile> list(
        File workspaceDirectory,
        int maximumFiles,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        if (maximumFiles <= 0 || maximumRelativePathCharacters <= 0 || maximumDepth <= 0) {
            throw new IllegalArgumentException("Workspace catalog limits must be positive");
        }
        Path workspace = WorkspaceFileBoundary.requireWorkspace(workspaceDirectory);
        List<WorkspaceExportFile> files = new ArrayList<WorkspaceExportFile>();
        int[] entryCount = new int[] {0};
        collect(
            workspace,
            workspace,
            0,
            maximumFiles,
            maximumRelativePathCharacters,
            maximumDepth,
            files,
            entryCount
        );
        Collections.sort(files, new Comparator<WorkspaceExportFile>() {
            @Override
            public int compare(WorkspaceExportFile left, WorkspaceExportFile right) {
                return left.relativePath.compareTo(right.relativePath);
            }
        });
        return Collections.unmodifiableList(files);
    }

    public static WorkspaceExportFile copyTo(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        OutputStream destination
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        WorkspaceExportFile source = inspect(
            workspaceDirectory,
            requestedPath,
            maximumBytes
        );
        try (FileInputStream input = new FileInputStream(source.file)) {
            byte[] buffer = new byte[8192];
            long copied = 0L;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace file export was cancelled");
                }
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                copied = checkedAdd(copied, count);
                if (copied > maximumBytes || copied > source.byteCount) {
                    throw new IOException("Workspace file changed during export");
                }
                destination.write(buffer, 0, count);
            }
            if (copied != source.byteCount) {
                throw new IOException("Workspace file changed during export");
            }
            destination.flush();
        }
        WorkspaceExportFile afterCopy = inspect(
            workspaceDirectory,
            requestedPath,
            maximumBytes
        );
        if (afterCopy.byteCount != source.byteCount
            || !afterCopy.file.equals(source.file)
            || !sameValue(afterCopy.lastModifiedTime, source.lastModifiedTime)
            || !sameValue(afterCopy.fileKey, source.fileKey)) {
            throw new IOException("Workspace file changed during export");
        }
        return source;
    }

    public File getFile() {
        return file;
    }

    public String getAbsolutePath() {
        return file.getAbsolutePath();
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getByteCount() {
        return byteCount;
    }

    boolean hasSameSnapshot(WorkspaceExportFile other) {
        return other != null
            && relativePath.equals(other.relativePath)
            && byteCount == other.byteCount
            && sameValue(lastModifiedTime, other.lastModifiedTime)
            && sameValue(fileKey, other.fileKey);
    }

    private static void collect(
        Path workspace,
        Path directory,
        int depth,
        int maximumFiles,
        int maximumRelativePathCharacters,
        int maximumDepth,
        List<WorkspaceExportFile> files,
        int[] entryCount
    ) throws IOException {
        if (depth > maximumDepth) {
            throw new IOException("Workspace directory depth exceeds the export limit");
        }
        List<Path> children = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (entryCount[0] >= maximumFiles) {
                    throw new IOException("Workspace entry count exceeds the export limit");
                }
                entryCount[0]++;
                children.add(child);
            }
        }
        Collections.sort(children, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return left.getFileName().toString().compareTo(right.getFileName().toString());
            }
        });
        for (Path child : children) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Workspace catalog scan was cancelled");
            }
            BasicFileAttributes attributes = Files.readAttributes(
                child,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()) {
                throw new IOException("Symbolic workspace entries are not exportable");
            }
            Path canonicalChild = child.toFile().getCanonicalFile().toPath();
            if (!canonicalChild.startsWith(workspace) || canonicalChild.equals(workspace)) {
                throw new IOException("Workspace entry escaped the private workspace");
            }
            String relativePath = WorkspaceFileBoundary.validateRelativePath(
                workspace.relativize(canonicalChild).toString(),
                maximumRelativePathCharacters
            );
            if (attributes.isDirectory()) {
                collect(
                    workspace,
                    canonicalChild,
                    depth + 1,
                    maximumFiles,
                    maximumRelativePathCharacters,
                    maximumDepth,
                    files,
                    entryCount
                );
            } else if (attributes.isRegularFile()) {
                WorkspaceFileBoundary.requireSingleLink(canonicalChild);
                files.add(new WorkspaceExportFile(
                    canonicalChild.toFile(),
                    relativePath,
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.fileKey()
                ));
            } else {
                throw new IOException("Workspace contains an unsupported filesystem entry");
            }
        }
    }

    private static String safeDisplayName(String name) {
        String value = name == null ? "" : name;
        StringBuilder safe = new StringBuilder();
        for (int index = 0; index < value.length() && safe.length() < 180; index++) {
            char character = value.charAt(index);
            safe.append(character < 0x20 || character == 0x7f ? '_' : character);
        }
        return safe.length() == 0 ? "agentcodi-file" : safe.toString();
    }

    private static long checkedAdd(long value, long increment) throws IOException {
        if (increment < 0L || value > Long.MAX_VALUE - increment) {
            throw new IOException("Workspace file size overflow");
        }
        return value + increment;
    }

    private static boolean sameValue(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
