package de.agentcodi.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import de.agentcodi.storage.WorkspaceArchive;
import de.agentcodi.storage.WorkspaceExportFile;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class WorkspaceFileExporter {
    public static final int MAXIMUM_FILES = 2048;
    public static final int MAXIMUM_SCANNED_ENTRIES = 65536;
    public static final int MAXIMUM_RELATIVE_PATH_CHARACTERS = 2048;
    public static final int MAXIMUM_DIRECTORY_DEPTH = 64;
    public static final long MAXIMUM_FILE_BYTES = 512L * 1024L * 1024L;
    public static final long MAXIMUM_ARCHIVE_BYTES = 1024L * 1024L * 1024L;
    public static final String ARCHIVE_DISPLAY_NAME = "AGENTCODI-workspace.zip";

    private WorkspaceFileExporter() {
    }

    public static List<FileExport> list(Context context) throws IOException {
        WorkspaceLayout layout = layout(context);
        List<WorkspaceExportFile> sources = WorkspaceExportFile.list(
            layout.getWorkspace(),
            MAXIMUM_FILES,
            MAXIMUM_SCANNED_ENTRIES,
            MAXIMUM_RELATIVE_PATH_CHARACTERS,
            MAXIMUM_DIRECTORY_DEPTH
        );
        List<FileExport> exports = new ArrayList<FileExport>(sources.size());
        for (WorkspaceExportFile source : sources) {
            exports.add(toFileExport(source));
        }
        return Collections.unmodifiableList(exports);
    }

    public static FileExport inspect(Context context, String sourcePath) throws IOException {
        WorkspaceLayout layout = layout(context);
        return toFileExport(WorkspaceExportFile.inspect(
            layout.getWorkspace(),
            sourcePath,
            MAXIMUM_FILE_BYTES,
            NativeWorkspaceFileAccess.opener()
        ));
    }

    public static FileExport export(
        Context context,
        String sourcePath,
        Uri destination
    ) throws IOException {
        requireContentDestination(destination);
        WorkspaceLayout layout = layout(context);
        WorkspaceExportFile.inspect(
            layout.getWorkspace(),
            sourcePath,
            MAXIMUM_FILE_BYTES,
            NativeWorkspaceFileAccess.opener()
        );
        OutputStream output = openDestination(context, destination);
        WorkspaceExportFile exported;
        try (OutputStream destinationStream = output) {
            exported = WorkspaceExportFile.copyTo(
                layout.getWorkspace(),
                sourcePath,
                MAXIMUM_FILE_BYTES,
                destinationStream,
                NativeWorkspaceFileAccess.opener()
            );
        }
        return toFileExport(exported);
    }

    public static ArchiveExport inspectArchive(Context context) throws IOException {
        WorkspaceArchive.Summary summary = WorkspaceArchive.inspect(
            layout(context).getWorkspace(),
            MAXIMUM_FILES,
            MAXIMUM_SCANNED_ENTRIES,
            MAXIMUM_FILE_BYTES,
            MAXIMUM_ARCHIVE_BYTES,
            MAXIMUM_RELATIVE_PATH_CHARACTERS,
            MAXIMUM_DIRECTORY_DEPTH
        );
        return new ArchiveExport(
            ARCHIVE_DISPLAY_NAME,
            summary.getFileCount(),
            summary.getTotalBytes()
        );
    }

    public static ArchiveExport exportArchive(Context context, Uri destination)
        throws IOException {
        requireContentDestination(destination);
        WorkspaceLayout layout = layout(context);
        WorkspaceArchive.inspect(
            layout.getWorkspace(),
            MAXIMUM_FILES,
            MAXIMUM_SCANNED_ENTRIES,
            MAXIMUM_FILE_BYTES,
            MAXIMUM_ARCHIVE_BYTES,
            MAXIMUM_RELATIVE_PATH_CHARACTERS,
            MAXIMUM_DIRECTORY_DEPTH
        );
        OutputStream output = openDestination(context, destination);
        WorkspaceArchive.Summary summary;
        try (OutputStream destinationStream = output) {
            summary = WorkspaceArchive.write(
                layout.getWorkspace(),
                destinationStream,
                MAXIMUM_FILES,
                MAXIMUM_SCANNED_ENTRIES,
                MAXIMUM_FILE_BYTES,
                MAXIMUM_ARCHIVE_BYTES,
                MAXIMUM_RELATIVE_PATH_CHARACTERS,
                MAXIMUM_DIRECTORY_DEPTH,
                NativeWorkspaceFileAccess.opener()
            );
        }
        return new ArchiveExport(
            ARCHIVE_DISPLAY_NAME,
            summary.getFileCount(),
            summary.getTotalBytes()
        );
    }

    private static FileExport toFileExport(WorkspaceExportFile source) {
        return new FileExport(
            source.getAbsolutePath(),
            source.getRelativePath(),
            source.getDisplayName(),
            mimeTypeForName(source.getDisplayName()),
            source.getByteCount(),
            source.getByteCount() <= MAXIMUM_FILE_BYTES
        );
    }

    private static String mimeTypeForName(String name) {
        String value = name == null ? "" : name;
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return "application/octet-stream";
        }
        String extension = value.substring(dot + 1).toLowerCase(Locale.ROOT);
        String detected = URLConnection.guessContentTypeFromName("file." + extension);
        return detected == null || detected.trim().isEmpty()
            ? "application/octet-stream"
            : detected;
    }

    private static OutputStream openDestination(Context context, Uri destination)
        throws IOException {
        ContentResolver resolver = context.getContentResolver();
        OutputStream output = resolver.openOutputStream(destination, "w");
        if (output == null) {
            throw new IOException("Android did not open the selected export destination");
        }
        return output;
    }

    private static void requireContentDestination(Uri destination) {
        if (destination == null || !"content".equalsIgnoreCase(destination.getScheme())) {
            throw new IllegalArgumentException("destination must be an Android content URI");
        }
    }

    private static WorkspaceLayout layout(Context context) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return WorkspaceLayout.create(context.getFilesDir());
    }

    public static final class FileExport {
        private final String sourcePath;
        private final String relativePath;
        private final String displayName;
        private final String mimeType;
        private final long byteCount;
        private final boolean withinExportLimit;

        private FileExport(
            String sourcePath,
            String relativePath,
            String displayName,
            String mimeType,
            long byteCount,
            boolean withinExportLimit
        ) {
            this.sourcePath = sourcePath;
            this.relativePath = relativePath;
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.byteCount = byteCount;
            this.withinExportLimit = withinExportLimit;
        }

        public String getSourcePath() {
            return sourcePath;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getByteCount() {
            return byteCount;
        }

        public boolean isWithinExportLimit() {
            return withinExportLimit;
        }
    }

    public static final class ArchiveExport {
        private final String displayName;
        private final int fileCount;
        private final long byteCount;

        private ArchiveExport(String displayName, int fileCount, long byteCount) {
            this.displayName = displayName;
            this.fileCount = fileCount;
            this.byteCount = byteCount;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getFileCount() {
            return fileCount;
        }

        public long getByteCount() {
            return byteCount;
        }
    }
}
