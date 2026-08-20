package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.attribute.FileTime;

public final class WorkspaceImageFile {
    private static final int HEADER_BYTES = 12;

    private final File file;
    private final String displayName;
    private final String mimeType;
    private final long byteCount;
    private final FileTime lastModifiedTime;
    private final Object fileKey;

    private WorkspaceImageFile(
        File file,
        String displayName,
        String mimeType,
        long byteCount,
        FileTime lastModifiedTime,
        Object fileKey
    ) {
        this.file = file;
        this.displayName = displayName;
        this.mimeType = mimeType;
        this.byteCount = byteCount;
        this.lastModifiedTime = lastModifiedTime;
        this.fileKey = fileKey;
    }

    public static WorkspaceImageFile inspect(
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

    public static WorkspaceImageFile inspect(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        requireRequest(requestedPath, maximumBytes);
        try (WorkspaceFileBoundary.OpenedFile opened =
                WorkspaceFileBoundary.openRegularFile(
                    workspaceDirectory,
                    requestedPath,
                    maximumBytes,
                    opener
                )) {
            long byteCount = opened.getByteCount();
            if (byteCount <= 0L) {
                throw new IOException("Workspace image size is outside the export limit");
            }
            byte[] header = readHeader(opened.source);
            String mimeType = detectMimeType(header);
            if (mimeType.isEmpty()) {
                throw new IOException("Workspace file is not a supported image");
            }
            if ("image/png".equals(mimeType)) {
                PngImageValidator.validate(
                    opened.source,
                    header,
                    header.length,
                    byteCount,
                    null
                );
            }
            opened.source.verifyUnchanged();
            return fromOpened(opened, mimeType);
        }
    }

    private static void requireRequest(String requestedPath, long maximumBytes) {
        if (requestedPath == null || requestedPath.trim().isEmpty()) {
            throw new IllegalArgumentException("requestedPath must not be blank");
        }
        if (maximumBytes <= 0L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
    }

    public static WorkspaceImageFile copyTo(
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

    public static WorkspaceImageFile copyTo(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        OutputStream destination,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        requireRequest(requestedPath, maximumBytes);
        try (WorkspaceFileBoundary.OpenedFile opened =
                WorkspaceFileBoundary.openRegularFile(
                    workspaceDirectory,
                    requestedPath,
                    maximumBytes,
                    opener
                )) {
            if (opened.getByteCount() <= 0L) {
                throw new IOException("Workspace image size is outside the export limit");
            }
            byte[] buffer = new byte[8192];
            int firstCount = readPrefix(opened.source, buffer, HEADER_BYTES);
            String mimeType = detectMimeType(buffer, firstCount);
            if (firstCount <= 0 || mimeType.isEmpty()) {
                throw new IOException("Workspace file is not a supported image");
            }
            WorkspaceImageFile image = fromOpened(opened, mimeType);
            if ("image/png".equals(mimeType)) {
                PngImageValidator.validate(
                    opened.source,
                    buffer,
                    firstCount,
                    image.byteCount,
                    destination
                );
            } else {
                destination.write(buffer, 0, firstCount);
                long copied = firstCount;
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Workspace image export was cancelled");
                    }
                    int count = opened.source.read(buffer, 0, buffer.length);
                    if (count < 0) {
                        break;
                    }
                    if (count == 0) {
                        continue;
                    }
                    if (copied > Long.MAX_VALUE - count) {
                        throw new IOException("Workspace image size overflow");
                    }
                    copied += count;
                    if (copied > maximumBytes || copied > image.byteCount) {
                        throw new IOException("Workspace image changed during export");
                    }
                    destination.write(buffer, 0, count);
                }
                if (copied != image.byteCount) {
                    throw new IOException("Workspace image changed during export");
                }
            }
            opened.source.verifyUnchanged();
            destination.flush();
            return image;
        }
    }

    public File getFile() {
        return file;
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

    private static byte[] readHeader(WorkspaceFileAccess.Source source) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        int total = readPrefix(source, header, header.length);
        if (total == header.length) {
            return header;
        }
        byte[] exact = new byte[total];
        System.arraycopy(header, 0, exact, 0, total);
        return exact;
    }

    private static int readPrefix(
        WorkspaceFileAccess.Source source,
        byte[] buffer,
        int maximum
    )
        throws IOException {
        int total = 0;
        while (total < maximum) {
            int count = source.read(buffer, total, maximum - total);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                continue;
            } else {
                total += count;
            }
        }
        return total;
    }

    private static WorkspaceImageFile fromOpened(
        WorkspaceFileBoundary.OpenedFile opened,
        String mimeType
    ) {
        return new WorkspaceImageFile(
            opened.file,
            safeDisplayName(opened.file.getName(), mimeType),
            mimeType,
            opened.getByteCount(),
            opened.getLastModifiedTime(),
            opened.getFileKey()
        );
    }

    private static String detectMimeType(byte[] header) {
        return detectMimeType(header, header == null ? 0 : header.length);
    }

    private static String detectMimeType(byte[] header, int length) {
        if (header == null || length < 3 || length > header.length) {
            return "";
        }
        if (length >= 8
            && unsigned(header[0]) == 0x89
            && header[1] == 'P'
            && header[2] == 'N'
            && header[3] == 'G'
            && unsigned(header[4]) == 0x0d
            && unsigned(header[5]) == 0x0a
            && unsigned(header[6]) == 0x1a
            && unsigned(header[7]) == 0x0a) {
            return "image/png";
        }
        if (unsigned(header[0]) == 0xff
            && unsigned(header[1]) == 0xd8
            && unsigned(header[2]) == 0xff) {
            return "image/jpeg";
        }
        if (length >= 6
            && header[0] == 'G'
            && header[1] == 'I'
            && header[2] == 'F'
            && header[3] == '8'
            && (header[4] == '7' || header[4] == '9')
            && header[5] == 'a') {
            return "image/gif";
        }
        if (length >= 12
            && header[0] == 'R'
            && header[1] == 'I'
            && header[2] == 'F'
            && header[3] == 'F'
            && header[8] == 'W'
            && header[9] == 'E'
            && header[10] == 'B'
            && header[11] == 'P') {
            return "image/webp";
        }
        return "";
    }

    private static String safeDisplayName(String name, String mimeType) {
        StringBuilder safe = new StringBuilder();
        String value = name == null ? "" : name;
        for (int index = 0; index < value.length() && safe.length() < 120; index++) {
            char character = value.charAt(index);
            safe.append(character < 0x20 || character == 0x7f ? '_' : character);
        }
        String extension = extensionForMimeType(mimeType);
        if (safe.length() == 0) {
            return "agentcodi-image" + extension;
        }
        String lower = safe.toString().toLowerCase(java.util.Locale.ROOT);
        if (!hasCompatibleExtension(lower, mimeType)) {
            safe.append(extension);
        }
        return safe.toString();
    }

    private static boolean hasCompatibleExtension(String name, String mimeType) {
        if ("image/png".equals(mimeType)) {
            return name.endsWith(".png");
        }
        if ("image/jpeg".equals(mimeType)) {
            return name.endsWith(".jpg") || name.endsWith(".jpeg");
        }
        if ("image/gif".equals(mimeType)) {
            return name.endsWith(".gif");
        }
        return "image/webp".equals(mimeType) && name.endsWith(".webp");
    }

    private static String extensionForMimeType(String mimeType) {
        if ("image/png".equals(mimeType)) {
            return ".png";
        }
        if ("image/jpeg".equals(mimeType)) {
            return ".jpg";
        }
        if ("image/gif".equals(mimeType)) {
            return ".gif";
        }
        return ".webp";
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

}
