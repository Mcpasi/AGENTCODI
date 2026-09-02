package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
        return inspect(
            workspaceDirectory,
            "",
            maximumFiles,
            WorkspaceExportFile.defaultMaximumScannedEntries(maximumFiles),
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            WorkspaceDirectoryCatalog.secureNioReader(),
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public static Summary inspect(
        File workspaceDirectory,
        int maximumFiles,
        int maximumScannedEntries,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        return inspect(
            workspaceDirectory,
            "",
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            WorkspaceDirectoryCatalog.secureNioReader(),
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public static Summary inspect(
        File workspaceDirectory,
        String relativeDirectory,
        int maximumFiles,
        int maximumScannedEntries,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth,
        WorkspaceDirectoryCatalog.Reader directoryReader,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        validateLimits(
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth
        );
        if (directoryReader == null) {
            throw new IllegalArgumentException("directoryReader must not be null");
        }
        if (opener == null) {
            throw new IllegalArgumentException("opener must not be null");
        }
        Path workspace = WorkspaceFileBoundary.requireWorkspace(workspaceDirectory);
        String directory = normalizeDirectory(
            relativeDirectory,
            maximumRelativePathCharacters,
            maximumDepth
        );
        CatalogState state = new CatalogState(
            workspaceDirectory,
            workspace,
            directory,
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            directoryReader,
            opener
        );
        state.collectSelectedDirectory();
        Collections.sort(state.members, MEMBER_ORDER);
        return new Summary(
            directory,
            state.members,
            state.totalBytes,
            state.omittedEntryCount
        );
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
        return write(
            workspaceDirectory,
            "",
            destination,
            maximumFiles,
            WorkspaceExportFile.defaultMaximumScannedEntries(maximumFiles),
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            WorkspaceDirectoryCatalog.secureNioReader(),
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public static Summary write(
        File workspaceDirectory,
        OutputStream destination,
        int maximumFiles,
        int maximumScannedEntries,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) throws IOException {
        return write(
            workspaceDirectory,
            "",
            destination,
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            WorkspaceDirectoryCatalog.secureNioReader(),
            WorkspaceFileAccess.secureNioOpener()
        );
    }

    public static Summary write(
        File workspaceDirectory,
        OutputStream destination,
        int maximumFiles,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        return write(
            workspaceDirectory,
            "",
            destination,
            maximumFiles,
            WorkspaceExportFile.defaultMaximumScannedEntries(maximumFiles),
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            WorkspaceDirectoryCatalog.secureNioReader(),
            opener
        );
    }

    public static Summary write(
        File workspaceDirectory,
        OutputStream destination,
        int maximumFiles,
        int maximumScannedEntries,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        return write(
            workspaceDirectory,
            "",
            destination,
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            WorkspaceDirectoryCatalog.secureNioReader(),
            opener
        );
    }

    public static Summary write(
        File workspaceDirectory,
        String relativeDirectory,
        OutputStream destination,
        int maximumFiles,
        int maximumScannedEntries,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth,
        WorkspaceDirectoryCatalog.Reader directoryReader,
        WorkspaceFileAccess.Opener opener
    ) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        Summary before = inspect(
            workspaceDirectory,
            relativeDirectory,
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            directoryReader,
            opener
        );
        CountingLimit counter = new CountingLimit(maximumTotalBytes);
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            for (ArchiveMember member : before.members) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace archive export was cancelled");
                }
                ZipEntry entry = new ZipEntry(member.archivePath);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try {
                    WorkspaceExportFile copied = WorkspaceExportFile.copyTo(
                        workspaceDirectory,
                        member.source.getAbsolutePath(),
                        maximumFileBytes,
                        new CountingOutputStream(zip, counter),
                        opener
                    );
                    // Java and native Unix providers can expose sub-microsecond
                    // timestamps differently. The handle still verifies its full
                    // native mtime/ctime snapshot before this common-precision check.
                    if (!copied.hasSameOpenedSnapshot(member.source)) {
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
            relativeDirectory,
            maximumFiles,
            maximumScannedEntries,
            maximumFileBytes,
            maximumTotalBytes,
            maximumRelativePathCharacters,
            maximumDepth,
            directoryReader,
            opener
        );
        if (!before.hasSameSnapshot(after)) {
            throw new IOException("Workspace changed while the archive was created");
        }
        return before;
    }

    public static final class Summary {
        private final String relativeDirectory;
        private final List<ArchiveMember> members;
        private final long totalBytes;
        private final int omittedEntryCount;

        private Summary(
            String relativeDirectory,
            List<ArchiveMember> members,
            long totalBytes,
            int omittedEntryCount
        ) {
            this.relativeDirectory = relativeDirectory;
            this.members = Collections.unmodifiableList(
                new ArrayList<ArchiveMember>(members)
            );
            this.totalBytes = totalBytes;
            this.omittedEntryCount = omittedEntryCount;
        }

        public String getRelativeDirectory() {
            return relativeDirectory;
        }

        public int getFileCount() {
            return members.size();
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getOmittedEntryCount() {
            return omittedEntryCount;
        }

        private boolean hasSameSnapshot(Summary other) {
            if (other == null || !relativeDirectory.equals(other.relativeDirectory)
                || totalBytes != other.totalBytes
                || omittedEntryCount != other.omittedEntryCount
                || members.size() != other.members.size()) {
                return false;
            }
            for (int index = 0; index < members.size(); index++) {
                ArchiveMember left = members.get(index);
                ArchiveMember right = other.members.get(index);
                if (!left.archivePath.equals(right.archivePath)
                    || !left.source.hasSameSnapshot(right.source)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ArchiveMember {
        private final WorkspaceExportFile source;
        private final String archivePath;

        private ArchiveMember(WorkspaceExportFile source, String archivePath) {
            this.source = source;
            this.archivePath = archivePath;
        }
    }

    private static final class CatalogState {
        private final File workspaceDirectory;
        private final Path workspace;
        private final String selectedDirectory;
        private final int maximumFiles;
        private final int maximumScannedEntries;
        private final long maximumFileBytes;
        private final long maximumTotalBytes;
        private final int maximumRelativePathCharacters;
        private final int maximumDepth;
        private final WorkspaceDirectoryCatalog.Reader directoryReader;
        private final WorkspaceFileAccess.Opener opener;
        private final PortablePathIndex portablePaths = new PortablePathIndex();
        private final List<ArchiveMember> members = new ArrayList<ArchiveMember>();
        private int scannedEntryCount;
        private int omittedEntryCount;
        private long totalBytes;

        private CatalogState(
            File workspaceDirectory,
            Path workspace,
            String selectedDirectory,
            int maximumFiles,
            int maximumScannedEntries,
            long maximumFileBytes,
            long maximumTotalBytes,
            int maximumRelativePathCharacters,
            int maximumDepth,
            WorkspaceDirectoryCatalog.Reader directoryReader,
            WorkspaceFileAccess.Opener opener
        ) {
            this.workspaceDirectory = workspaceDirectory;
            this.workspace = workspace;
            this.selectedDirectory = selectedDirectory;
            this.maximumFiles = maximumFiles;
            this.maximumScannedEntries = maximumScannedEntries;
            this.maximumFileBytes = maximumFileBytes;
            this.maximumTotalBytes = maximumTotalBytes;
            this.maximumRelativePathCharacters = maximumRelativePathCharacters;
            this.maximumDepth = maximumDepth;
            this.directoryReader = directoryReader;
            this.opener = opener;
        }

        private void collectSelectedDirectory() throws IOException {
            collectDirectory(selectedDirectory, true);
        }

        private List<WorkspaceDirectoryCatalog.Entry> readDirectory(
            String relativeDirectory,
            boolean rethrowFailure
        ) throws IOException {
            final WorkspaceDirectoryCatalog.Snapshot snapshot;
            try {
                snapshot = directoryReader.list(
                    workspaceDirectory,
                    relativeDirectory,
                    Math.max(1, maximumScannedEntries - scannedEntryCount),
                    maximumRelativePathCharacters,
                    maximumDepth
                );
            } catch (IOException error) {
                if (rethrowFailure || Thread.currentThread().isInterrupted()) {
                    throw error;
                }
                return null;
            }
            if (snapshot == null || snapshot.getEntries() == null) {
                throw new IOException("Workspace archive directory catalog is invalid");
            }
            List<WorkspaceDirectoryCatalog.Entry> entries =
                new ArrayList<WorkspaceDirectoryCatalog.Entry>(snapshot.getEntries());
            if (snapshot.isTruncated()
                || entries.size() > maximumScannedEntries - scannedEntryCount) {
                throw new ArchiveBoundaryException(
                    "Workspace scan entry count exceeds the export limit"
                );
            }
            scannedEntryCount += entries.size();
            Collections.sort(entries, ENTRY_ORDER);
            return entries;
        }

        private boolean collectDirectory(String relativeDirectory, boolean selectedRoot)
            throws IOException {
            List<WorkspaceDirectoryCatalog.Entry> entries =
                readDirectory(relativeDirectory, selectedRoot);
            if (entries == null) {
                omitEntry();
                return false;
            }
            Set<String> rawChildren = new HashSet<String>();
            for (WorkspaceDirectoryCatalog.Entry entry : entries) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace archive catalog was cancelled");
                }
                String childPath = directChildPath(relativeDirectory, entry);
                if (childPath == null || !rawChildren.add(childPath)) {
                    omitEntry();
                    continue;
                }
                if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.UNAVAILABLE) {
                    omitEntry();
                    continue;
                }
                String archivePath = archivePath(childPath);
                if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.DIRECTORY) {
                    if (!portablePaths.reserveDirectory(archivePath)) {
                        omitEntry();
                        omitDirectorySubtree(childPath);
                        continue;
                    }
                    int memberCountBeforeDirectory = members.size();
                    if (!collectDirectory(childPath, false)
                        || members.size() == memberCountBeforeDirectory) {
                        portablePaths.releaseDirectory(archivePath);
                    }
                    continue;
                }
                if (entry.getKind()
                    != WorkspaceDirectoryCatalog.Entry.Kind.REGULAR_FILE
                    || !PortablePathIndex.isPortablePath(archivePath)) {
                    omitEntry();
                    continue;
                }
                if (entry.getByteCount() > maximumFileBytes) {
                    throw new ArchiveBoundaryException(
                        "Workspace contains a file above the archive limit"
                    );
                }
                if (members.size() >= maximumFiles) {
                    throw new ArchiveBoundaryException(
                        "Workspace regular-file count exceeds the export limit"
                    );
                }
                final WorkspaceExportFile source;
                try {
                    source = WorkspaceExportFile.inspect(
                        workspaceDirectory,
                        workspace.resolve(childPath).toFile().getAbsolutePath(),
                        maximumFileBytes,
                        opener
                    );
                } catch (IOException error) {
                    omitEntry();
                    continue;
                }
                if (!childPath.equals(source.getRelativePath())) {
                    omitEntry();
                    continue;
                }
                if (!portablePaths.reserveFile(archivePath)) {
                    omitEntry();
                    continue;
                }
                long nextTotal = checkedAdd(totalBytes, source.getByteCount());
                if (nextTotal > maximumTotalBytes) {
                    throw new ArchiveBoundaryException(
                        "Workspace total size exceeds the archive limit"
                    );
                }
                totalBytes = nextTotal;
                members.add(new ArchiveMember(source, archivePath));
            }
            return true;
        }

        // A directory that cannot receive a safe archive name hides every entry
        // below it from the ZIP. Those entries are still scanned and counted so
        // the reported omission count matches what the archive actually leaves
        // out; nothing below such a directory is opened, reserved or exported.
        private void omitDirectorySubtree(String relativeDirectory) throws IOException {
            List<WorkspaceDirectoryCatalog.Entry> entries =
                readDirectory(relativeDirectory, false);
            if (entries == null) {
                return;
            }
            Set<String> rawChildren = new HashSet<String>();
            for (WorkspaceDirectoryCatalog.Entry entry : entries) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Workspace archive catalog was cancelled");
                }
                omitEntry();
                String childPath = directChildPath(relativeDirectory, entry);
                if (childPath == null || !rawChildren.add(childPath)) {
                    continue;
                }
                if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.DIRECTORY) {
                    omitDirectorySubtree(childPath);
                }
            }
        }

        private String archivePath(String workspaceRelativePath) throws IOException {
            String path;
            if (selectedDirectory.isEmpty()) {
                path = workspaceRelativePath;
            } else {
                String prefix = selectedDirectory + "/";
                if (!workspaceRelativePath.startsWith(prefix)) {
                    throw new IOException("Workspace archive path escaped its selected folder");
                }
                path = workspaceRelativePath.substring(prefix.length());
            }
            return WorkspaceFileBoundary.validateRelativePath(
                path,
                maximumRelativePathCharacters
            );
        }

        private void omitEntry() throws ArchiveBoundaryException {
            if (omittedEntryCount == Integer.MAX_VALUE) {
                throw new ArchiveBoundaryException("Workspace omitted-entry count overflow");
            }
            omittedEntryCount++;
        }
    }

    private static final class PortablePathIndex {
        private final Set<String> fileKeys = new HashSet<String>();
        private final Set<String> directoryKeys = new HashSet<String>();

        private boolean reserveDirectory(String path) {
            String key = portableKey(path);
            return key != null && !fileKeys.contains(key) && directoryKeys.add(key);
        }

        private boolean reserveFile(String path) {
            String key = portableKey(path);
            return key != null && !directoryKeys.contains(key) && fileKeys.add(key);
        }

        private void releaseDirectory(String path) {
            String key = portableKey(path);
            if (key != null) {
                directoryKeys.remove(key);
            }
        }

        private static boolean isPortablePath(String path) {
            return portableKey(path) != null;
        }

        private static String portableKey(String path) {
            String[] components = path.split("/", -1);
            StringBuilder key = new StringBuilder();
            for (String component : components) {
                if (!isPortableComponent(component)) {
                    return null;
                }
                if (key.length() > 0) {
                    key.append('/');
                }
                key.append(Normalizer.normalize(
                    Normalizer.normalize(component, Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT),
                    Normalizer.Form.NFC
                ));
            }
            return key.length() == 0 ? null : key.toString();
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

    private static final class ArchiveBoundaryException extends IOException {
        private ArchiveBoundaryException(String message) {
            super(message);
        }
    }

    private static final Comparator<WorkspaceDirectoryCatalog.Entry> ENTRY_ORDER =
        new Comparator<WorkspaceDirectoryCatalog.Entry>() {
            @Override
            public int compare(
                WorkspaceDirectoryCatalog.Entry left,
                WorkspaceDirectoryCatalog.Entry right
            ) {
                int kind = Integer.compare(entryRank(left), entryRank(right));
                if (kind != 0) {
                    return kind;
                }
                int path = left.getRelativePath().compareTo(right.getRelativePath());
                return path != 0
                    ? path
                    : left.getDisplayName().compareTo(right.getDisplayName());
            }
        };

    private static final Comparator<ArchiveMember> MEMBER_ORDER =
        new Comparator<ArchiveMember>() {
            @Override
            public int compare(ArchiveMember left, ArchiveMember right) {
                return left.archivePath.compareTo(right.archivePath);
            }
        };

    private static int entryRank(WorkspaceDirectoryCatalog.Entry entry) {
        if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.DIRECTORY) {
            return 0;
        }
        if (entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.REGULAR_FILE) {
            return 1;
        }
        return 2;
    }

    private static String directChildPath(
        String relativeDirectory,
        WorkspaceDirectoryCatalog.Entry entry
    ) {
        if (entry == null || entry.getRelativePath() == null) {
            return null;
        }
        String path = entry.getRelativePath();
        if (path.isEmpty()) {
            return entry.getKind() == WorkspaceDirectoryCatalog.Entry.Kind.UNAVAILABLE
                ? "[unavailable]:" + entry.getDisplayName()
                : null;
        }
        String prefix = relativeDirectory.isEmpty() ? "" : relativeDirectory + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String name = path.substring(prefix.length());
        if (name.isEmpty() || name.indexOf('/') >= 0 || !safeComponent(name)) {
            return null;
        }
        return path;
    }

    private static String normalizeDirectory(
        String relativeDirectory,
        int maximumCharacters,
        int maximumDepth
    ) throws IOException {
        if (relativeDirectory == null || relativeDirectory.length() > maximumCharacters) {
            throw new IOException("Workspace directory path is outside the export limit");
        }
        if (relativeDirectory.isEmpty()) {
            return "";
        }
        if (relativeDirectory.startsWith("/") || relativeDirectory.endsWith("/")
            || relativeDirectory.contains("//")) {
            throw new IOException("Workspace directory path is unsafe");
        }
        String[] components = relativeDirectory.split("/", -1);
        if (components.length > maximumDepth) {
            throw new IOException("Workspace directory depth exceeds the export limit");
        }
        StringBuilder normalized = new StringBuilder();
        for (String component : components) {
            if (!safeComponent(component)) {
                throw new IOException("Workspace directory path is unsafe");
            }
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(component);
        }
        return normalized.toString();
    }

    private static boolean safeComponent(String value) {
        if (value == null || value.isEmpty() || ".".equals(value) || "..".equals(value)
            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
            || value.indexOf(':') >= 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPortableComponent(String component) {
        if (!safeComponent(component) || component.endsWith(" ") || component.endsWith(".")) {
            return false;
        }
        String normalized = Normalizer.normalize(
            Normalizer.normalize(component, Normalizer.Form.NFC).toLowerCase(Locale.ROOT),
            Normalizer.Form.NFC
        );
        return !isReservedPortableName(normalized);
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

    private static void validateLimits(
        int maximumFiles,
        int maximumScannedEntries,
        long maximumFileBytes,
        long maximumTotalBytes,
        int maximumRelativePathCharacters,
        int maximumDepth
    ) {
        if (maximumFiles <= 0 || maximumScannedEntries < maximumFiles
            || maximumFileBytes < 0L || maximumTotalBytes < 0L
            || maximumRelativePathCharacters <= 0 || maximumDepth <= 0) {
            throw new IllegalArgumentException("Workspace archive limits must be positive");
        }
    }

    private static long checkedAdd(long value, long increment) throws IOException {
        if (increment < 0L || value > Long.MAX_VALUE - increment) {
            throw new IOException("Workspace total size overflow");
        }
        return value + increment;
    }
}
