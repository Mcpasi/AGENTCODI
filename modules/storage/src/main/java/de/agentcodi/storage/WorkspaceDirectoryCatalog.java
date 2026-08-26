package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Descriptor-relative, no-follow catalog access for one workspace directory. */
public final class WorkspaceDirectoryCatalog {
    public static final String REASON_SYMBOLIC_LINK = "symbolic-link";
    public static final String REASON_HARD_LINK = "hard-link";
    public static final String REASON_SPECIAL_ENTRY = "special-entry";
    public static final String REASON_UNSAFE_NAME = "unsafe-name";
    public static final String REASON_UNREADABLE = "unreadable";

    private static final Reader SECURE_NIO_READER = new SecureNioReader();

    private WorkspaceDirectoryCatalog() {
    }

    public static Reader secureNioReader() {
        return SECURE_NIO_READER;
    }

    public interface Reader {
        Snapshot list(
            File workspaceDirectory,
            String relativeDirectory,
            int maximumEntries,
            int maximumRelativePathCharacters,
            int maximumDepth
        ) throws IOException;
    }

    public static final class Snapshot {
        private final List<Entry> entries;
        private final boolean truncated;

        private Snapshot(List<Entry> entries, boolean truncated) {
            if (entries == null) {
                throw new IllegalArgumentException("Directory entries are required");
            }
            ArrayList<Entry> copy = new ArrayList<Entry>(entries.size());
            for (Entry entry : entries) {
                if (entry == null) {
                    throw new IllegalArgumentException("Directory entry is required");
                }
                copy.add(entry);
            }
            this.entries = Collections.unmodifiableList(copy);
            this.truncated = truncated;
        }

        public static Snapshot of(List<Entry> entries, boolean truncated) {
            return new Snapshot(entries, truncated);
        }

        public List<Entry> getEntries() {
            return entries;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    public static final class Entry {
        public enum Kind {
            DIRECTORY,
            REGULAR_FILE,
            UNAVAILABLE
        }

        private final String displayName;
        private final String relativePath;
        private final Kind kind;
        private final long byteCount;
        private final long lastModifiedMillis;
        private final String reason;

        private Entry(
            String displayName,
            String relativePath,
            Kind kind,
            long byteCount,
            long lastModifiedMillis,
            String reason
        ) {
            if (displayName == null || displayName.trim().isEmpty()
                || relativePath == null || kind == null || byteCount < -1L
                || reason == null) {
                throw new IllegalArgumentException("Directory catalog entry is invalid");
            }
            this.displayName = displayName;
            this.relativePath = relativePath;
            this.kind = kind;
            this.byteCount = byteCount;
            this.lastModifiedMillis = Math.max(0L, lastModifiedMillis);
            this.reason = reason;
        }

        public static Entry directory(
            String displayName,
            String relativePath,
            long lastModifiedMillis
        ) {
            return new Entry(
                displayName,
                relativePath,
                Kind.DIRECTORY,
                -1L,
                lastModifiedMillis,
                ""
            );
        }

        public static Entry regularFile(
            String displayName,
            String relativePath,
            long byteCount,
            long lastModifiedMillis
        ) {
            if (byteCount < 0L) {
                throw new IllegalArgumentException("Regular file byte count is invalid");
            }
            return new Entry(
                displayName,
                relativePath,
                Kind.REGULAR_FILE,
                byteCount,
                lastModifiedMillis,
                ""
            );
        }

        public static Entry unavailable(
            String displayName,
            String relativePath,
            long lastModifiedMillis,
            String reason
        ) {
            if (reason == null || reason.isEmpty()) {
                throw new IllegalArgumentException("Unavailable reason is required");
            }
            return new Entry(
                displayName,
                relativePath,
                Kind.UNAVAILABLE,
                -1L,
                lastModifiedMillis,
                reason
            );
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public Kind getKind() {
            return kind;
        }

        public long getByteCount() {
            return byteCount;
        }

        public long getLastModifiedMillis() {
            return lastModifiedMillis;
        }

        public String getReason() {
            return reason;
        }
    }

    private static final class SecureNioReader implements Reader {
        @Override
        public Snapshot list(
            File workspaceDirectory,
            String relativeDirectory,
            int maximumEntries,
            int maximumRelativePathCharacters,
            int maximumDepth
        ) throws IOException {
            if (maximumEntries <= 0 || maximumRelativePathCharacters <= 0
                || maximumDepth <= 0) {
                throw new IllegalArgumentException("Directory catalog limits must be positive");
            }
            Path workspace = WorkspaceFileBoundary.requireWorkspace(workspaceDirectory);
            List<String> components = directoryComponents(
                relativeDirectory,
                maximumRelativePathCharacters,
                maximumDepth
            );
            DirectoryStream<Path> rawRoot = Files.newDirectoryStream(workspace);
            if (!(rawRoot instanceof SecureDirectoryStream<?>)) {
                rawRoot.close();
                throw new IOException(
                    "Filesystem cannot catalog workspace directories without path races"
                );
            }
            @SuppressWarnings("unchecked")
            SecureDirectoryStream<Path> root = (SecureDirectoryStream<Path>) rawRoot;
            SecureDirectoryStream<Path> directory = root;
            try {
                for (String component : components) {
                    SecureDirectoryStream<Path> child = directory.newDirectoryStream(
                        workspace.getFileSystem().getPath(component),
                        LinkOption.NOFOLLOW_LINKS
                    );
                    if (directory != root) {
                        directory.close();
                    }
                    directory = child;
                }
                return readEntries(
                    workspace,
                    canonicalDirectory(components),
                    directory,
                    maximumEntries,
                    maximumRelativePathCharacters
                );
            } finally {
                if (directory != root) {
                    directory.close();
                }
                root.close();
            }
        }
    }

    private static Snapshot readEntries(
        Path workspace,
        String relativeDirectory,
        SecureDirectoryStream<Path> directory,
        int maximumEntries,
        int maximumRelativePathCharacters
    ) throws IOException {
        ArrayList<Entry> entries = new ArrayList<Entry>();
        boolean truncated = false;
        for (Path child : directory) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Workspace directory catalog was cancelled");
            }
            if (entries.size() >= maximumEntries) {
                truncated = true;
                break;
            }
            Path namePath = child.getFileName();
            String rawName = namePath == null ? "" : namePath.toString();
            String displayName = safeDisplayName(rawName);
            String relativePath = safeChildPath(
                relativeDirectory,
                rawName,
                maximumRelativePathCharacters
            );
            if (relativePath.isEmpty()) {
                entries.add(Entry.unavailable(
                    displayName,
                    "",
                    0L,
                    REASON_UNSAFE_NAME
                ));
                continue;
            }
            BasicFileAttributes attributes;
            try {
                BasicFileAttributeView view = directory.getFileAttributeView(
                    namePath,
                    BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS
                );
                if (view == null) {
                    throw new IOException("Workspace directory entry attributes are unavailable");
                }
                attributes = view.readAttributes();
            } catch (IOException error) {
                entries.add(Entry.unavailable(
                    displayName,
                    relativePath,
                    0L,
                    REASON_UNREADABLE
                ));
                continue;
            }
            long modified = safeMillis(attributes);
            if (attributes.isSymbolicLink()) {
                entries.add(Entry.unavailable(
                    displayName,
                    relativePath,
                    modified,
                    REASON_SYMBOLIC_LINK
                ));
            } else if (attributes.isDirectory()) {
                entries.add(Entry.directory(displayName, relativePath, modified));
            } else if (attributes.isRegularFile()) {
                try {
                    WorkspaceFileBoundary.requireSingleLink(
                        workspace.resolve(relativePath),
                        attributes.fileKey()
                    );
                    entries.add(Entry.regularFile(
                        displayName,
                        relativePath,
                        attributes.size(),
                        modified
                    ));
                } catch (IOException error) {
                    entries.add(Entry.unavailable(
                        displayName,
                        relativePath,
                        modified,
                        REASON_HARD_LINK
                    ));
                }
            } else {
                entries.add(Entry.unavailable(
                    displayName,
                    relativePath,
                    modified,
                    REASON_SPECIAL_ENTRY
                ));
            }
        }
        return Snapshot.of(entries, truncated);
    }

    private static List<String> directoryComponents(
        String relativeDirectory,
        int maximumCharacters,
        int maximumDepth
    ) throws IOException {
        if (relativeDirectory == null
            || relativeDirectory.length() > maximumCharacters) {
            throw new IOException("Workspace directory path is outside the browser limit");
        }
        if (relativeDirectory.isEmpty()) {
            return Collections.emptyList();
        }
        if (relativeDirectory.startsWith("/") || relativeDirectory.endsWith("/")
            || relativeDirectory.contains("//")) {
            throw new IOException("Workspace directory path is unsafe");
        }
        String[] values = relativeDirectory.split("/", -1);
        if (values.length > maximumDepth) {
            throw new IOException("Workspace directory depth exceeds the browser limit");
        }
        ArrayList<String> components = new ArrayList<String>(values.length);
        for (String value : values) {
            if (!safeComponent(value)) {
                throw new IOException("Workspace directory path is unsafe");
            }
            components.add(value);
        }
        return components;
    }

    private static String canonicalDirectory(List<String> components) {
        StringBuilder path = new StringBuilder();
        for (String component : components) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(component);
        }
        return path.toString();
    }

    private static String safeChildPath(
        String relativeDirectory,
        String name,
        int maximumCharacters
    ) {
        if (!safeComponent(name)) {
            return "";
        }
        String path = relativeDirectory.isEmpty()
            ? name
            : relativeDirectory + "/" + name;
        return path.length() <= maximumCharacters ? path : "";
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

    private static String safeDisplayName(String value) {
        StringBuilder safe = new StringBuilder();
        String source = value == null ? "" : value;
        for (int index = 0; index < source.length() && safe.length() < 180; index++) {
            char character = source.charAt(index);
            safe.append(character < 0x20 || character == 0x7f ? '_' : character);
        }
        if (safe.length() == 0) {
            return "[entry]";
        }
        return safe.toString().trim().isEmpty() ? "[blank name]" : safe.toString();
    }

    private static long safeMillis(BasicFileAttributes attributes) {
        try {
            return Math.max(0L, attributes.lastModifiedTime().toMillis());
        } catch (ArithmeticException error) {
            return 0L;
        }
    }
}
