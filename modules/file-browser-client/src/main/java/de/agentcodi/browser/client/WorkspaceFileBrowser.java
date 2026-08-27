package de.agentcodi.browser.client;

import de.agentcodi.browser.WorkspaceBreadcrumb;
import de.agentcodi.browser.WorkspaceBrowserEntry;
import de.agentcodi.browser.WorkspaceBrowserLimits;
import de.agentcodi.browser.WorkspaceBrowserPage;
import de.agentcodi.browser.WorkspaceFilePreview;
import de.agentcodi.storage.WorkspaceDirectoryCatalog;
import de.agentcodi.storage.WorkspaceFileAccess;
import de.agentcodi.storage.WorkspaceImageFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure, bounded workspace navigation and preview orchestration. */
public final class WorkspaceFileBrowser {
    private final File workspaceDirectory;
    private final WorkspaceDirectoryCatalog.Reader directoryReader;
    private final WorkspaceFileAccess.Opener fileOpener;

    public WorkspaceFileBrowser(
        File workspaceDirectory,
        WorkspaceDirectoryCatalog.Reader directoryReader,
        WorkspaceFileAccess.Opener fileOpener
    ) {
        if (workspaceDirectory == null || directoryReader == null || fileOpener == null) {
            throw new IllegalArgumentException("Workspace browser dependencies are required");
        }
        this.workspaceDirectory = workspaceDirectory;
        this.directoryReader = directoryReader;
        this.fileOpener = fileOpener;
    }

    public WorkspaceBrowserPage list(String relativeDirectory, int requestedPage)
        throws IOException {
        return list(
            relativeDirectory,
            requestedPage,
            WorkspaceBrowserLimits.DEFAULT_DIRECTORY_PAGE_SIZE
        );
    }

    public WorkspaceBrowserPage list(
        String relativeDirectory,
        int requestedPage,
        int pageSize
    ) throws IOException {
        if (requestedPage < 0) {
            throw new IllegalArgumentException("Directory page must not be negative");
        }
        if (pageSize <= 0 || pageSize > WorkspaceBrowserLimits.MAXIMUM_DIRECTORY_PAGE_SIZE) {
            throw new IllegalArgumentException("Directory page size is outside the browser limit");
        }
        String directory = normalizeDirectory(relativeDirectory);
        WorkspaceDirectoryCatalog.Snapshot snapshot = directoryReader.list(
            workspaceDirectory,
            directory,
            WorkspaceBrowserLimits.MAXIMUM_SCANNED_DIRECTORY_ENTRIES,
            WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS,
            WorkspaceBrowserLimits.MAXIMUM_DIRECTORY_DEPTH
        );
        ArrayList<WorkspaceBrowserEntry> all = new ArrayList<WorkspaceBrowserEntry>();
        for (WorkspaceDirectoryCatalog.Entry entry : snapshot.getEntries()) {
            all.add(toBrowserEntry(entry));
        }
        Collections.sort(all, ENTRY_ORDER);
        int pageCount = Math.max(1, divideRoundedUp(all.size(), pageSize));
        int pageIndex = Math.min(requestedPage, pageCount - 1);
        int first = Math.min(all.size(), pageIndex * pageSize);
        int last = Math.min(all.size(), first + pageSize);
        return new WorkspaceBrowserPage(
            directory,
            parentOf(directory),
            breadcrumbs(directory),
            new ArrayList<WorkspaceBrowserEntry>(all.subList(first, last)),
            pageIndex,
            pageCount,
            all.size(),
            snapshot.isTruncated()
        );
    }

    public WorkspaceFilePreview preview(String relativePath, int requestedPage)
        throws IOException {
        if (requestedPage < 0) {
            throw new IllegalArgumentException("Preview page must not be negative");
        }
        String path = normalizeFile(relativePath);
        WorkspaceFileAccess.Source source = fileOpener.open(
            workspaceDirectory,
            path,
            WorkspaceBrowserLimits.MAXIMUM_FILE_BYTES
        );
        String imageMime = "";
        try {
            long byteCount = source.getByteCount();
            byte[] probe = readAtMost(
                source,
                (int) Math.min(
                    byteCount,
                    (long) WorkspaceBrowserLimits.TEXT_PROBE_BYTES
                )
            );
            imageMime = detectImageMimeType(probe);
            if (!imageMime.isEmpty()
                && byteCount <= WorkspaceBrowserLimits.MAXIMUM_IMAGE_PREVIEW_BYTES) {
                source.verifyUnchanged();
            } else {
                return previewNonImage(
                    source,
                    probe,
                    path,
                    imageMime,
                    byteCount,
                    requestedPage
                );
            }
        } finally {
            source.close();
        }

        ByteArrayOutputStream image = new ByteArrayOutputStream();
        WorkspaceImageFile copied = WorkspaceImageFile.copyTo(
            workspaceDirectory,
            new File(workspaceDirectory, path).getAbsolutePath(),
            WorkspaceBrowserLimits.MAXIMUM_IMAGE_PREVIEW_BYTES,
            image,
            fileOpener
        );
        byte[] bytes = image.toByteArray();
        return WorkspaceFilePreview.image(
            displayName(path),
            path,
            copied.getMimeType(),
            copied.getByteCount(),
            bytes
        );
    }

    public static String parentOf(String relativeDirectory) {
        if (relativeDirectory == null || relativeDirectory.isEmpty()) {
            return "";
        }
        int separator = relativeDirectory.lastIndexOf('/');
        return separator < 0 ? "" : relativeDirectory.substring(0, separator);
    }

    private static WorkspaceBrowserEntry toBrowserEntry(
        WorkspaceDirectoryCatalog.Entry entry
    ) {
        if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.DIRECTORY) {
            return WorkspaceBrowserEntry.directory(
                entry.getDisplayName(),
                entry.getRelativePath(),
                entry.getLastModifiedMillis()
            );
        }
        if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.REGULAR_FILE) {
            return WorkspaceBrowserEntry.file(
                entry.getDisplayName(),
                entry.getRelativePath(),
                entry.getByteCount(),
                entry.getLastModifiedMillis()
            );
        }
        return WorkspaceBrowserEntry.unavailable(
            entry.getDisplayName(),
            entry.getRelativePath(),
            entry.getLastModifiedMillis(),
            entry.getReason()
        );
    }

    private static List<WorkspaceBreadcrumb> breadcrumbs(String directory) {
        ArrayList<WorkspaceBreadcrumb> values = new ArrayList<WorkspaceBreadcrumb>();
        values.add(new WorkspaceBreadcrumb("", ""));
        if (directory.isEmpty()) {
            return values;
        }
        StringBuilder path = new StringBuilder();
        for (String component : directory.split("/")) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(component);
            values.add(new WorkspaceBreadcrumb(component, path.toString()));
        }
        return values;
    }

    private static String normalizeDirectory(String value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Relative directory is required");
        }
        if (value.isEmpty()) {
            return "";
        }
        validatePortablePath(value);
        int depth = value.split("/", -1).length;
        if (depth > WorkspaceBrowserLimits.MAXIMUM_DIRECTORY_DEPTH) {
            throw new IOException("Workspace directory depth exceeds the browser limit");
        }
        return value;
    }

    private static String normalizeFile(String value) throws IOException {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Relative file path is required");
        }
        validatePortablePath(value);
        return value;
    }

    private static void validatePortablePath(String value) throws IOException {
        if (value.length() > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS
            || value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            throw new IOException("Workspace browser path is unsafe");
        }
        String[] components = value.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                || component.indexOf('\\') >= 0 || component.indexOf(':') >= 0) {
                throw new IOException("Workspace browser path is unsafe");
            }
            for (int index = 0; index < component.length(); index++) {
                char character = component.charAt(index);
                if (character < 0x20 || character == 0x7f) {
                    throw new IOException("Workspace browser path is unsafe");
                }
            }
        }
    }

    private static byte[] readAtMost(WorkspaceFileAccess.Source source, int maximum)
        throws IOException {
        byte[] bytes = new byte[maximum];
        int total = 0;
        while (total < maximum) {
            checkInterrupted();
            int count = source.read(bytes, total, maximum - total);
            if (count < 0) {
                break;
            }
            if (count > 0) {
                total += count;
            }
        }
        if (total == bytes.length) {
            return bytes;
        }
        byte[] exact = new byte[total];
        System.arraycopy(bytes, 0, exact, 0, total);
        return exact;
    }

    private static WorkspaceFilePreview previewNonImage(
        WorkspaceFileAccess.Source source,
        byte[] probe,
        String path,
        String detectedMimeType,
        long byteCount,
        int requestedPage
    ) throws IOException {
        Utf8TextValidator validator = new Utf8TextValidator();
        validator.accept(probe, 0, probe.length);
        if (!validator.isCandidate()) {
            PageWindow binaryPage = PageWindow.forFile(
                byteCount,
                requestedPage,
                WorkspaceBrowserLimits.BINARY_PAGE_BYTES
            );
            byte[] bytes = readRange(
                source,
                probe,
                binaryPage.byteOffset,
                binaryPage.byteCount
            );
            source.verifyUnchanged();
            return binaryPreview(
                path,
                detectedMimeType,
                byteCount,
                binaryPage,
                bytes
            );
        }

        PageWindow textPage = PageWindow.forFile(
            byteCount,
            requestedPage,
            WorkspaceBrowserLimits.TEXT_PAGE_BYTES
        );
        PageWindow binaryPage = PageWindow.forFile(
            byteCount,
            requestedPage,
            WorkspaceBrowserLimits.BINARY_PAGE_BYTES
        );
        int textPrefix = (int) Math.min(3L, textPage.byteOffset);
        PageCapture textBytes = new PageCapture(
            textPage.byteOffset - textPrefix,
            textPrefix + textPage.byteCount
        );
        PageCapture binaryBytes = new PageCapture(
            binaryPage.byteOffset,
            binaryPage.byteCount
        );
        textBytes.accept(probe, 0L, probe.length);
        binaryBytes.accept(probe, 0L, probe.length);

        long scanned = probe.length;
        byte[] buffer = new byte[8192];
        while (scanned < byteCount
            && (validator.isCandidate() || !binaryBytes.isComplete())) {
            checkInterrupted();
            int wanted = (int) Math.min((long) buffer.length, byteCount - scanned);
            int count = source.read(buffer, 0, wanted);
            if (count < 0) {
                throw new IOException("Workspace file changed during preview");
            }
            if (count == 0) {
                throw new IOException("Workspace file preview made no progress");
            }
            textBytes.accept(buffer, scanned, count);
            binaryBytes.accept(buffer, scanned, count);
            if (validator.isCandidate()) {
                validator.accept(buffer, 0, count);
            }
            scanned += count;
        }

        boolean text = scanned == byteCount && validator.isCompleteText();
        source.verifyUnchanged();
        if (!text) {
            return binaryPreview(
                path,
                detectedMimeType,
                byteCount,
                binaryPage,
                binaryBytes.toByteArray()
            );
        }
        String content = decodeUtf8Page(
            textBytes.toByteArray(),
            textPrefix,
            textPage.byteOffset,
            textPage.byteCount,
            textPage.byteOffset + textPage.byteCount == byteCount
        );
        return WorkspaceFilePreview.text(
            displayName(path),
            path,
            byteCount,
            textPage.byteOffset,
            textPage.pageIndex,
            textPage.pageCount,
            content
        );
    }

    private static WorkspaceFilePreview binaryPreview(
        String path,
        String detectedMimeType,
        long byteCount,
        PageWindow page,
        byte[] bytes
    ) {
        String mimeType = detectedMimeType.isEmpty()
            ? "application/octet-stream"
            : detectedMimeType;
        return WorkspaceFilePreview.binary(
            displayName(path),
            path,
            mimeType,
            byteCount,
            page.byteOffset,
            page.pageIndex,
            page.pageCount,
            renderHex(bytes, page.byteOffset)
        );
    }

    private static byte[] readRange(
        WorkspaceFileAccess.Source source,
        byte[] probe,
        long offset,
        int length
    ) throws IOException {
        byte[] result = new byte[length];
        int written = 0;
        if (offset < probe.length) {
            int available = (int) Math.min((long) probe.length - offset, (long) length);
            System.arraycopy(probe, (int) offset, result, 0, available);
            written = available;
        } else {
            try {
                source.position(offset);
            } catch (UnsupportedOperationException unsupported) {
                discard(source, offset - probe.length);
            }
        }
        while (written < result.length) {
            checkInterrupted();
            int count = source.read(result, written, result.length - written);
            if (count < 0) {
                throw new IOException("Workspace file changed during preview");
            }
            if (count > 0) {
                written += count;
            }
        }
        return result;
    }

    private static void discard(WorkspaceFileAccess.Source source, long byteCount)
        throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = byteCount;
        while (remaining > 0L) {
            checkInterrupted();
            int wanted = (int) Math.min((long) buffer.length, remaining);
            int count = source.read(buffer, 0, wanted);
            if (count < 0) {
                throw new IOException("Workspace file changed during preview navigation");
            }
            if (count > 0) {
                remaining -= count;
            }
        }
    }

    private static String decodeUtf8Page(
        byte[] bytes,
        int prefix,
        long byteOffset,
        int byteCount,
        boolean finalPage
    ) throws IOException {
        long readOffset = byteOffset - prefix;
        int cursor = 0;
        while (prefix > 0 && cursor < bytes.length
            && isUtf8Continuation(bytes[cursor])) {
            cursor++;
        }
        int includedStart = -1;
        int includedEnd = -1;
        long pageEnd = byteOffset + byteCount;
        while (cursor < bytes.length) {
            int sequenceLength = utf8SequenceLength(bytes, cursor);
            if (sequenceLength == 0) {
                if (finalPage) {
                    throw new IOException("Workspace text ends with invalid UTF-8 content");
                }
                break;
            }
            if (sequenceLength < 0) {
                throw new IOException("Workspace text contains invalid UTF-8 content");
            }
            long sequenceEnd = readOffset + cursor + sequenceLength;
            if (sequenceEnd > byteOffset && sequenceEnd <= pageEnd) {
                if (includedStart < 0) {
                    includedStart = cursor;
                }
                includedEnd = cursor + sequenceLength;
            }
            cursor += sequenceLength;
        }
        return includedStart < 0
            ? ""
            : new String(
                bytes,
                includedStart,
                includedEnd - includedStart,
                StandardCharsets.UTF_8
            );
    }

    private static boolean isUtf8Continuation(byte value) {
        return ((value & 0xff) & 0xc0) == 0x80;
    }

    private static int utf8SequenceLength(byte[] bytes, int index) {
        int first = bytes[index] & 0xff;
        if (first < 0x80) {
            return first == 0 || first == 0x7f || (first < 0x20
                && first != '\t' && first != '\n' && first != '\r'
                && first != '\f') ? -1 : 1;
        }
        int needed;
        int codePoint;
        if (first >= 0xc2 && first <= 0xdf) {
            needed = 1;
            codePoint = first & 0x1f;
        } else if (first >= 0xe0 && first <= 0xef) {
            needed = 2;
            codePoint = first & 0x0f;
        } else if (first >= 0xf0 && first <= 0xf4) {
            needed = 3;
            codePoint = first & 0x07;
        } else {
            return -1;
        }
        if (index + needed >= bytes.length) {
            return 0;
        }
        for (int offset = 1; offset <= needed; offset++) {
            int continuation = bytes[index + offset] & 0xff;
            if ((continuation & 0xc0) != 0x80) {
                return -1;
            }
            codePoint = (codePoint << 6) | (continuation & 0x3f);
        }
        if ((needed == 2 && codePoint < 0x800)
            || (needed == 3 && codePoint < 0x10000)
            || codePoint > 0x10ffff
            || (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
            return -1;
        }
        return needed + 1;
    }

    private static final class Utf8TextValidator {
        private boolean candidate = true;
        private int continuationBytes;
        private int codePoint;
        private int minimumCodePoint;

        private void accept(byte[] bytes, int offset, int length) {
            if (bytes == null || offset < 0 || length < 0
                || offset > bytes.length - length) {
                throw new IllegalArgumentException("UTF-8 validation range is invalid");
            }
            for (int index = offset; candidate && index < offset + length; index++) {
                int value = bytes[index] & 0xff;
                if (continuationBytes > 0) {
                    if ((value & 0xc0) != 0x80) {
                        candidate = false;
                        break;
                    }
                    codePoint = (codePoint << 6) | (value & 0x3f);
                    continuationBytes--;
                    if (continuationBytes == 0
                        && (codePoint < minimumCodePoint
                            || codePoint > 0x10ffff
                            || (codePoint >= 0xd800 && codePoint <= 0xdfff))) {
                        candidate = false;
                    }
                    continue;
                }
                if (value < 0x80) {
                    if (value == 0 || value == 0x7f || (value < 0x20
                        && value != '\t' && value != '\n' && value != '\r'
                        && value != '\f')) {
                        candidate = false;
                    }
                } else if (value >= 0xc2 && value <= 0xdf) {
                    continuationBytes = 1;
                    codePoint = value & 0x1f;
                    minimumCodePoint = 0x80;
                } else if (value >= 0xe0 && value <= 0xef) {
                    continuationBytes = 2;
                    codePoint = value & 0x0f;
                    minimumCodePoint = 0x800;
                } else if (value >= 0xf0 && value <= 0xf4) {
                    continuationBytes = 3;
                    codePoint = value & 0x07;
                    minimumCodePoint = 0x10000;
                } else {
                    candidate = false;
                }
            }
        }

        private boolean isCandidate() {
            return candidate;
        }

        private boolean isCompleteText() {
            return candidate && continuationBytes == 0;
        }
    }

    private static final class PageWindow {
        private final long byteOffset;
        private final int byteCount;
        private final int pageIndex;
        private final int pageCount;

        private PageWindow(
            long byteOffset,
            int byteCount,
            int pageIndex,
            int pageCount
        ) {
            this.byteOffset = byteOffset;
            this.byteCount = byteCount;
            this.pageIndex = pageIndex;
            this.pageCount = pageCount;
        }

        private static PageWindow forFile(
            long byteCount,
            int requestedPage,
            int pageBytes
        ) {
            int pageCount = Math.max(1, divideRoundedUp(byteCount, pageBytes));
            int pageIndex = Math.min(requestedPage, pageCount - 1);
            long byteOffset = (long) pageIndex * (long) pageBytes;
            int length = (int) Math.min(
                (long) pageBytes,
                byteCount - byteOffset
            );
            return new PageWindow(byteOffset, length, pageIndex, pageCount);
        }
    }

    private static final class PageCapture {
        private final long byteOffset;
        private final byte[] bytes;
        private int capturedBytes;

        private PageCapture(long byteOffset, int byteCount) {
            if (byteOffset < 0L || byteCount < 0) {
                throw new IllegalArgumentException("Preview capture range is invalid");
            }
            this.byteOffset = byteOffset;
            this.bytes = new byte[byteCount];
        }

        private void accept(byte[] source, long sourceOffset, int byteCount) {
            long sourceEnd = sourceOffset + byteCount;
            long captureEnd = byteOffset + bytes.length;
            long overlapStart = Math.max(sourceOffset, byteOffset);
            long overlapEnd = Math.min(sourceEnd, captureEnd);
            if (overlapStart >= overlapEnd) {
                return;
            }
            int length = (int) (overlapEnd - overlapStart);
            System.arraycopy(
                source,
                (int) (overlapStart - sourceOffset),
                bytes,
                (int) (overlapStart - byteOffset),
                length
            );
            capturedBytes += length;
        }

        private boolean isComplete() {
            return capturedBytes == bytes.length;
        }

        private byte[] toByteArray() throws IOException {
            if (!isComplete()) {
                throw new IOException("Workspace file changed during preview");
            }
            return bytes;
        }
    }

    private static String detectImageMimeType(byte[] header) {
        if (header.length >= 8
            && (header[0] & 0xff) == 0x89
            && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
            && (header[4] & 0xff) == 0x0d && (header[5] & 0xff) == 0x0a
            && (header[6] & 0xff) == 0x1a && (header[7] & 0xff) == 0x0a) {
            return "image/png";
        }
        if (header.length >= 3 && (header[0] & 0xff) == 0xff
            && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (header.length >= 6 && header[0] == 'G' && header[1] == 'I'
            && header[2] == 'F' && header[3] == '8'
            && (header[4] == '7' || header[4] == '9') && header[5] == 'a') {
            return "image/gif";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I'
            && header[2] == 'F' && header[3] == 'F'
            && header[8] == 'W' && header[9] == 'E'
            && header[10] == 'B' && header[11] == 'P') {
            return "image/webp";
        }
        return "";
    }

    private static String renderHex(byte[] bytes, long startOffset) {
        if (bytes.length == 0) {
            return "";
        }
        final char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder rendered = new StringBuilder(bytes.length * 5);
        for (int row = 0; row < bytes.length; row += 16) {
            appendOffset(rendered, startOffset + row, hex);
            rendered.append("  ");
            int rowLength = Math.min(16, bytes.length - row);
            for (int column = 0; column < 16; column++) {
                if (column < rowLength) {
                    int value = bytes[row + column] & 0xff;
                    rendered.append(hex[value >>> 4]).append(hex[value & 0x0f]);
                } else {
                    rendered.append("  ");
                }
                rendered.append(column == 7 ? "  " : " ");
            }
            rendered.append(" |");
            for (int column = 0; column < rowLength; column++) {
                int value = bytes[row + column] & 0xff;
                rendered.append(value >= 0x20 && value <= 0x7e ? (char) value : '.');
            }
            rendered.append('|');
            if (row + rowLength < bytes.length) {
                rendered.append('\n');
            }
        }
        return rendered.toString();
    }

    private static void appendOffset(StringBuilder target, long offset, char[] hex) {
        for (int shift = 60; shift >= 0; shift -= 4) {
            target.append(hex[(int) ((offset >>> shift) & 0x0f)]);
        }
    }

    private static String displayName(String path) {
        int separator = path.lastIndexOf('/');
        String name = separator < 0 ? path : path.substring(separator + 1);
        return name.trim().isEmpty() ? "[blank name]" : name;
    }

    private static int divideRoundedUp(int value, int divisor) {
        return value == 0 ? 0 : 1 + (value - 1) / divisor;
    }

    private static int divideRoundedUp(long value, int divisor) {
        if (value <= 0L) {
            return 0;
        }
        long result = 1L + (value - 1L) / divisor;
        if (result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Preview page count overflow");
        }
        return (int) result;
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Workspace browser operation was cancelled");
        }
    }

    private static final Comparator<WorkspaceBrowserEntry> ENTRY_ORDER =
        new Comparator<WorkspaceBrowserEntry>() {
            @Override
            public int compare(
                WorkspaceBrowserEntry left,
                WorkspaceBrowserEntry right
            ) {
                int kind = Integer.compare(rank(left.getKind()), rank(right.getKind()));
                if (kind != 0) {
                    return kind;
                }
                String leftFolded = left.getDisplayName().toLowerCase(Locale.ROOT);
                String rightFolded = right.getDisplayName().toLowerCase(Locale.ROOT);
                int folded = leftFolded.compareTo(rightFolded);
                return folded != 0
                    ? folded
                    : left.getDisplayName().compareTo(right.getDisplayName());
            }
        };

    private static int rank(WorkspaceBrowserEntry.Kind kind) {
        if (kind == WorkspaceBrowserEntry.Kind.DIRECTORY) {
            return 0;
        }
        if (kind == WorkspaceBrowserEntry.Kind.FILE) {
            return 1;
        }
        return 2;
    }
}
