package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class WorkspaceArchive {
    private WorkspaceArchive() {
    }

    public static Summary inspect(
        File workspaceDirectory,
        int maximumFiles,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        if (maximumFileBytes < 0L || maximumTotalBytes < 0L) {
            throw new IllegalArgumentException("Workspace archive byte limits must not be negative");
        }
        List<WorkspaceExportFile> files = WorkspaceExportFile.list(
            workspaceDirectory,
            maximumFiles,
            maximumRelativePathCharacters,
            maximumDepth
        );
        validatePortablePaths(files);
        long total = 0L;
        for (WorkspaceExportFile file : files) {
            if (file.getByteCount() > maximumFileBytes) {
                throw new IOException("Workspace contains a file above the archive limit");
            }
            total = checkedAdd(total, file.getByteCount());
            if (total > maximumTotalBytes) {
                throw new IOException("Workspace total size exceeds the archive limit");
            }
        }
        return new Summary(files, total);
    }

    public static Summary write(
        File workspaceDirectory,
        OutputStream destination,
        int maximumFiles,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        Summary before = inspect(
            workspaceDirectory,
            maximumFiles,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth
        );
        CountingLimit counter = new CountingLimit(maximumTotalBytes);
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            for (WorkspaceExportFile file : before.files) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace archive export was cancelled");
                }
                ZipEntry entry = new ZipEntry(file.getRelativePath());
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try {
                    WorkspaceExportFile copied = WorkspaceExportFile.copyTo(
                        workspaceDirectory,
                        file.getAbsolutePath(),
                        maximumFileBytes,
                        new CountingOutputStream(zip, counter)
                    );
                    if (!copied.hasSameSnapshot(file)) {
                        throw new IOException("Workspace changed while the archive was created");
                    }
                } finally {
                    zip.closeEntry();
                }
            }
            zip.finish();
        }
        if (counter.count != before.totalBytes) {
            throw new IOException("Workspace changed while the archive was created");
        }
        Summary after = inspect(
            workspaceDirectory,
            maximumFiles,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth
        );
        if (!before.hasSameSnapshot(after)) {
            throw new IOException("Workspace changed while the archive was created");
        }
        return before;
    }

    public static final class Summary {
        private final List<WorkspaceExportFile> files;
        private final long totalBytes;

        private Summary(List<WorkspaceExportFile> files, long totalBytes) {
            this.files = files;
            this.totalBytes = totalBytes;
        }

        public int getFileCount() {
            return files.size();
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        private boolean hasSameSnapshot(Summary other) {
            if (other == null || totalBytes != other.totalBytes
                || files.size() != other.files.size()) {
                return false;
            }
            for (int index = 0; index < files.size(); index++) {
                if (!files.get(index).hasSameSnapshot(other.files.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class CountingLimit {
        private final long maximum;
        private long count;

        private CountingLimit(long maximum) {
            this.maximum = maximum;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final CountingLimit counter;

        private CountingOutputStream(OutputStream delegate, CountingLimit counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (offset < 0 || length < 0 || offset > value.length - length) {
                throw new IndexOutOfBoundsException();
            }
            reserve(length);
            delegate.write(value, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void reserve(int length) throws IOException {
            if (length < 0 || counter.count > counter.maximum - length) {
                throw new IOException("Workspace total size exceeds the archive limit");
            }
            counter.count += length;
        }
    }

    private static long checkedAdd(long value, long increment) throws IOException {
        if (increment < 0L || value > Long.MAX_VALUE - increment) {
            throw new IOException("Workspace total size overflow");
        }
        return value + increment;
    }

    private static void validatePortablePaths(List<WorkspaceExportFile> files)
        throws IOException {
        Set<String> fileKeys = new HashSet<String>();
        Set<String> directoryKeys = new HashSet<String>();
        for (WorkspaceExportFile file : files) {
            String[] components = file.getRelativePath().split("/", -1);
            StringBuilder pathKey = new StringBuilder();
            for (int index = 0; index < components.length; index++) {
                String component = components[index];
                if (component.isEmpty() || component.endsWith(" ") || component.endsWith(".")) {
                    throw new IOException("Workspace archive name is not portable");
                }
                String normalized = Normalizer.normalize(
                    Normalizer.normalize(component, Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT),
                    Normalizer.Form.NFC
                );
                if (isReservedPortableName(normalized)) {
                    throw new IOException("Workspace archive name is not portable");
                }
                if (pathKey.length() > 0) {
                    pathKey.append('/');
                }
                pathKey.append(normalized);
                String key = pathKey.toString();
                boolean isFile = index == components.length - 1;
                if (isFile) {
                    if (directoryKeys.contains(key) || !fileKeys.add(key)) {
                        throw new IOException("Workspace archive names collide portably");
                    }
                } else {
                    if (fileKeys.contains(key)) {
                        throw new IOException("Workspace archive names collide portably");
                    }
                    directoryKeys.add(key);
                }
            }
        }
    }

    private static boolean isReservedPortableName(String component) {
        int dot = component.indexOf('.');
        String base = dot < 0 ? component : component.substring(0, dot);
        if (base.equals("con") || base.equals("prn") || base.equals("aux")
            || base.equals("nul")) {
            return true;
        }
        if (base.length() == 4) {
            String prefix = base.substring(0, 3);
            char suffix = base.charAt(3);
            return (prefix.equals("com") || prefix.equals("lpt"))
                && suffix >= '1' && suffix <= '9';
        }
        return false;
    }
}
