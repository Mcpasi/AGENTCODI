package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class WorkspaceExportFile {
    private static final int DEFAULT_SCANNED_ENTRIES_PER_FILE = 32;

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
        this.fileKey = fileKey == null ? null : fileKey.toString();
    }

    public static WorkspaceExportFile inspect(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes
    ) throws IOException {
        return inspect(
            workspaceDirectory,
            requestedPath,
            maximumBytes,
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public static WorkspaceExportFile inspect(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        try (WorkspaceFileBoundary.OpenedFile opened =
                WorkspaceFileBoundary.openRegularFile(
                    workspaceDirectory,
                    requestedPath,
                    maximumBytes,
                    opener
                )) {
            opened.source.verifyUnchanged();
            return fromOpened(opened);
        }
    }

    public static List<WorkspaceExportFile> list(
        File workspaceDirectory,
        int maximumFiles,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        return list(
            workspaceDirectory,
            maximumFiles,
            defaultMaximumScannedEntries(maximumFiles),
            maximumRelativePathCharacters,
            maximumDepth
        );
    }

    public static List<WorkspaceExportFile> list(
        File workspaceDirectory,
        int maximumFiles,
        int maximumScannedEntries,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        if (maximumFiles <= 0 || maximumScannedEntries < maximumFiles
            || maximumRelativePathCharacters <= 0 || maximumDepth <= 0) {
            throw new IllegalArgumentException("Workspace catalog limits must be positive");
        }
        Path workspace = WorkspaceFileBoundary.requireWorkspace(workspaceDirectory);
        List<WorkspaceExportFile> files = new ArrayList<WorkspaceExportFile>();
        int[] scannedEntryCount = new int[] {0};
        collect(
            workspace,
            workspace,
            0,
            maximumFiles,
            maximumScannedEntries,
            maximumRelativePathCharacters,
            maximumDepth,
            files,
            scannedEntryCount
        );
        Collections.sort(files, new Comparator<WorkspaceExportFile>() {
            @Override
            public int compare(WorkspaceExportFile left, WorkspaceExportFile right) {
                return left.relativePath.compareTo(right.relativePath);
            }
        });
        return Collections.unmodifiableList(files);
    }

    static int defaultMaximumScannedEntries(int maximumFiles) {
        if (maximumFiles <= 0) {
            throw new IllegalArgumentException("maximumFiles must be positive");
        }
        if (maximumFiles > Integer.MAX_VALUE / DEFAULT_SCANNED_ENTRIES_PER_FILE) {
            return Integer.MAX_VALUE;
        }
        return maximumFiles * DEFAULT_SCANNED_ENTRIES_PER_FILE;
    }

    public static WorkspaceExportFile copyTo(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        OutputStream destination
    ) throws IOException {
        return copyTo(
            workspaceDirectory,
            requestedPath,
            maximumBytes,
            destination,
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public static WorkspaceExportFile copyTo(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        OutputStream destination,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        try (WorkspaceFileBoundary.OpenedFile opened =
                WorkspaceFileBoundary.openRegularFile(
                    workspaceDirectory,
                    requestedPath,
                    maximumBytes,
                    opener
                )) {
            WorkspaceExportFile source = fromOpened(opened);
            byte[] buffer = new byte[8192];
            long copied = 0L;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace file export was cancelled");
                }
                int count = opened.source.read(buffer, 0, buffer.length);
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
            opened.source.verifyUnchanged();
            destination.flush();
            return source;
        }
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

    boolean hasSameOpenedSnapshot(WorkspaceExportFile other) {
        return other != null
            && relativePath.equals(other.relativePath)
            && byteCount == other.byteCount
            && sameFileTimeAtMicrosecondPrecision(
                lastModifiedTime,
                other.lastModifiedTime
            )
            && sameValue(fileKey, other.fileKey);
    }

    private static void collect(
        Path workspace,
        Path directory,
        int depth,
        int maximumFiles,
        int maximumScannedEntries,
        int maximumRelativePathCharacters,
        int maximumDepth,
        List<WorkspaceExportFile> files,
        int[] scannedEntryCount
    ) throws IOException {
        if (depth > maximumDepth) {
            throw new IOException("Workspace directory depth exceeds the export limit");
        }
        List<Path> children = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (scannedEntryCount[0] >= maximumScannedEntries) {
                    throw new IOException(
                        "Workspace scan entry count exceeds the export limit"
                    );
                }
                scannedEntryCount[0]++;
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
                // The entry remains non-exportable, but its presence must not
                // suppress unrelated regular files. Do not canonicalize or
                // traverse the target.
                continue;
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
                    maximumScannedEntries,
                    maximumRelativePathCharacters,
                    maximumDepth,
                    files,
                    scannedEntryCount
                );
            } else if (attributes.isRegularFile()) {
                if (files.size() >= maximumFiles) {
                    throw new IOException(
                        "Workspace regular-file count exceeds the export limit"
                    );
                }
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

    private static WorkspaceExportFile fromOpened(
        WorkspaceFileBoundary.OpenedFile opened
    ) {
        return new WorkspaceExportFile(
            opened.file,
            opened.relativePath,
            opened.getByteCount(),
            opened.getLastModifiedTime(),
            opened.getFileKey()
        );
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

    private static boolean sameFileTimeAtMicrosecondPrecision(
        FileTime left,
        FileTime right
    ) {
        if (left == null || right == null) {
            return left == right;
        }
        Instant leftInstant = left.toInstant();
        Instant rightInstant = right.toInstant();
        return leftInstant.getEpochSecond() == rightInstant.getEpochSecond()
            && leftInstant.getNano() / 1000 == rightInstant.getNano() / 1000;
    }
}
