package de.agentcodi.storage;

import java.io.File;
import java.io.FileInputStream;
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
        if (requestedPath == null || requestedPath.trim().isEmpty()) {
            throw new IllegalArgumentException("requestedPath must not be blank");
        }
        if (maximumBytes <= 0L) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }

        WorkspaceFileBoundary.ResolvedFile resolved =
            WorkspaceFileBoundary.resolveRegularFile(
                workspaceDirectory,
                requestedPath,
                maximumBytes
            );
        File candidate = resolved.file;
        long byteCount = resolved.byteCount;
        if (byteCount <= 0L) {
            throw new IOException("Workspace image size is outside the export limit");
        }

        byte[] header = readHeader(candidate);
        String mimeType = detectMimeType(header);
        if (mimeType.isEmpty()) {
            throw new IOException("Workspace file is not a supported image");
        }
        return new WorkspaceImageFile(
            candidate,
            safeDisplayName(candidate.getName(), mimeType),
            mimeType,
            byteCount,
            resolved.lastModifiedTime,
            resolved.fileKey
        );
    }

    public static WorkspaceImageFile copyTo(
        File workspaceDirectory,
        String requestedPath,
        long maximumBytes,
        OutputStream destination
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        WorkspaceImageFile image = inspect(
            workspaceDirectory,
            requestedPath,
            maximumBytes
        );
        try (FileInputStream input = new FileInputStream(image.file)) {
            byte[] buffer = new byte[8192];
            int firstCount = readPrefix(input, buffer, HEADER_BYTES);
            if (firstCount <= 0
                || !image.mimeType.equals(detectMimeType(buffer, firstCount))) {
                throw new IOException("Workspace image changed before export");
            }
            destination.write(buffer, 0, firstCount);
            long copied = firstCount;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace image export was cancelled");
                }
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
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
            destination.flush();
        }
        WorkspaceImageFile afterCopy = inspect(
            workspaceDirectory,
            requestedPath,
            maximumBytes
        );
        if (!image.hasSameSnapshot(afterCopy)) {
            throw new IOException("Workspace image changed during export");
        }
        return image;
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

    private boolean hasSameSnapshot(WorkspaceImageFile other) {
        return other != null
            && file.equals(other.file)
            && byteCount == other.byteCount
            && mimeType.equals(other.mimeType)
            && sameValue(lastModifiedTime, other.lastModifiedTime)
            && sameValue(fileKey, other.fileKey);
    }

    private static byte[] readHeader(File file) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        int total;
        try (FileInputStream input = new FileInputStream(file)) {
            total = readPrefix(input, header, header.length);
        }
        if (total == header.length) {
            return header;
        }
        byte[] exact = new byte[total];
        System.arraycopy(header, 0, exact, 0, total);
        return exact;
    }

    private static int readPrefix(FileInputStream input, byte[] buffer, int maximum)
        throws IOException {
        int total = 0;
        while (total < maximum) {
            int count = input.read(buffer, total, maximum - total);
            if (count < 0) {
                break;
            }
            if (count == 0) {
                int single = input.read();
                if (single < 0) {
                    break;
                }
                buffer[total] = (byte) single;
                total++;
            } else {
                total += count;
            }
        }
        return total;
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

    private static boolean sameValue(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
