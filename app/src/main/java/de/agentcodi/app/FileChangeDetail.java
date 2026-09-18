package de.agentcodi.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure projection of a bounded file-change card detail into one section per changed file so
 * the chat can render every change as its own card with a readable diff instead of a single
 * long text block. The projection never adds, reorders, or drops reported content: sections
 * it does not recognise stay verbatim in the remainder.
 */
final class FileChangeDetail {
    enum Kind {
        ADD,
        DELETE,
        UPDATE
    }

    enum LineKind {
        ADDED,
        REMOVED,
        HUNK,
        META,
        CONTEXT
    }

    static final String ADD_HEADING = "HINZUFÜGEN";
    static final String DELETE_HEADING = "LÖSCHEN";
    static final String UPDATE_HEADING = "ÄNDERN";

    /** The card builder writes this instead of an empty body; the view localises it. */
    private static final String EMPTY_DIFF_PLACEHOLDER = "Kein Text-Diff vorhanden.";
    private static final String HEADING_SEPARATOR = " · ";
    /** The card builder closes every section heading, including a path, with a colon. */
    private static final String HEADING_TERMINATOR = ":";
    private static final String MOVE_SEPARATOR = " → ";
    private static final int MAX_CHANGES = 24;
    private static final int MAX_FIELD_LABEL_CHARACTERS = 64;
    private static final Projection EMPTY = new Projection(
        Collections.<FileChange>emptyList(), ""
    );

    private FileChangeDetail() {
    }

    static final class FileChange {
        private final Kind kind;
        private final String path;
        private final String movePath;
        private final String diff;
        private final int addedLines;
        private final int removedLines;

        private FileChange(Kind kind, String path, String movePath, String diff) {
            this.kind = kind;
            this.path = path;
            this.movePath = movePath;
            this.diff = diff;
            int added = 0;
            int removed = 0;
            for (String line : splitLines(diff)) {
                LineKind lineKind = lineKind(line);
                if (lineKind == LineKind.ADDED) {
                    added++;
                } else if (lineKind == LineKind.REMOVED) {
                    removed++;
                }
            }
            addedLines = added;
            removedLines = removed;
        }

        Kind getKind() {
            return kind;
        }

        String getPath() {
            return path;
        }

        String getMovePath() {
            return movePath;
        }

        String getDiff() {
            return diff;
        }

        int getAddedLines() {
            return addedLines;
        }

        int getRemovedLines() {
            return removedLines;
        }

        String getFileName() {
            return fileName(path);
        }

        String getDirectory() {
            return directory(path);
        }
    }

    static final class Projection {
        private final List<FileChange> changes;
        private final String remainder;
        private final int addedLines;
        private final int removedLines;

        private Projection(List<FileChange> changes, String remainder) {
            this.changes = Collections.unmodifiableList(changes);
            this.remainder = remainder;
            int added = 0;
            int removed = 0;
            for (FileChange change : changes) {
                added += change.getAddedLines();
                removed += change.getRemovedLines();
            }
            addedLines = added;
            removedLines = removed;
        }

        List<FileChange> getChanges() {
            return changes;
        }

        /** Everything the projection did not recognise as a file section, verbatim. */
        String getRemainder() {
            return remainder;
        }

        boolean hasChanges() {
            return !changes.isEmpty();
        }

        int getAddedLines() {
            return addedLines;
        }

        int getRemovedLines() {
            return removedLines;
        }
    }

    static Projection parse(String rawDetail) {
        if (rawDetail == null || rawDetail.isEmpty()) {
            return EMPTY;
        }
        String[] lines = splitLines(rawDetail);
        List<FileChange> changes = new ArrayList<FileChange>();
        StringBuilder remainder = new StringBuilder();
        int sectionStart = 0;
        for (int index = 1; index <= lines.length; index++) {
            if (index != lines.length && !startsSection(lines, index)) {
                continue;
            }
            appendSection(lines, sectionStart, index, changes, remainder);
            sectionStart = index;
        }
        return changes.isEmpty()
            ? new Projection(Collections.<FileChange>emptyList(), rawDetail)
            : new Projection(changes, remainder.toString());
    }

    /** Projects an already structured change, so approvals and cards share one renderer. */
    static FileChange of(String kind, String path, String movePath, String diff) {
        Kind projected = Kind.UPDATE;
        if ("add".equalsIgnoreCase(kind)) {
            projected = Kind.ADD;
        } else if ("delete".equalsIgnoreCase(kind)) {
            projected = Kind.DELETE;
        }
        return new FileChange(
            projected,
            path == null ? "" : path,
            movePath == null ? "" : movePath,
            diff == null ? "" : diff
        );
    }

    static LineKind lineKind(String line) {
        if (line == null || line.isEmpty()) {
            return LineKind.CONTEXT;
        }
        if (line.startsWith("@@")) {
            return LineKind.HUNK;
        }
        if (line.startsWith("+++") || line.startsWith("---")
            || line.startsWith("diff ")
            || line.startsWith("index ")
            || line.startsWith("new file")
            || line.startsWith("deleted file")
            || line.startsWith("rename ")
            || line.startsWith("similarity ")
            || line.startsWith("\\")) {
            return LineKind.META;
        }
        if (line.charAt(0) == '+') {
            return LineKind.ADDED;
        }
        if (line.charAt(0) == '-') {
            return LineKind.REMOVED;
        }
        return LineKind.CONTEXT;
    }

    static String fileName(String path) {
        String value = path == null ? "" : path;
        int separator = value.lastIndexOf('/');
        String name = separator < 0 ? value : value.substring(separator + 1);
        return name.isEmpty() ? value : name;
    }

    static String directory(String path) {
        String value = path == null ? "" : path;
        int separator = value.lastIndexOf('/');
        return separator <= 0 ? "" : value.substring(0, separator);
    }

    private static void appendSection(
        String[] lines,
        int start,
        int end,
        List<FileChange> changes,
        StringBuilder remainder
    ) {
        if (start >= end) {
            return;
        }
        Kind kind = changes.size() < MAX_CHANGES ? headingKind(lines[start]) : null;
        if (kind == null) {
            appendRemainder(remainder, join(lines, start, end));
            return;
        }
        String heading = lines[start].substring(headingLabel(kind).length()
            + HEADING_SEPARATOR.length());
        if (heading.endsWith(HEADING_TERMINATOR)) {
            heading = heading.substring(0, heading.length() - HEADING_TERMINATOR.length());
        }
        String path = heading;
        String movePath = "";
        int move = heading.indexOf(MOVE_SEPARATOR);
        if (move >= 0) {
            path = heading.substring(0, move);
            movePath = heading.substring(move + MOVE_SEPARATOR.length());
        }
        String diff = join(lines, start + 1, end);
        changes.add(new FileChange(
            kind,
            path,
            movePath,
            EMPTY_DIFF_PLACEHOLDER.equals(diff.trim()) ? "" : diff
        ));
    }

    private static void appendRemainder(StringBuilder remainder, String section) {
        if (section.isEmpty()) {
            return;
        }
        if (remainder.length() != 0) {
            remainder.append("\n\n");
        }
        remainder.append(section);
    }

    /** The first line always opens a section, so only later lines are examined here. */
    private static boolean startsSection(String[] lines, int index) {
        if (!lines[index - 1].isEmpty()) {
            return false;
        }
        String line = lines[index];
        return headingKind(line) != null || isFieldLabel(line);
    }

    /**
     * Recognises the bounded field headings the card builder appends after the changes.
     * Diff content always carries a marker character, so a labelled line can never be one.
     */
    private static boolean isFieldLabel(String line) {
        return line.length() >= 2
            && line.length() <= MAX_FIELD_LABEL_CHARACTERS
            && line.endsWith(":")
            && Character.isLetter(line.charAt(0))
            && line.indexOf(' ') < 0;
    }

    private static Kind headingKind(String line) {
        for (Kind kind : Kind.values()) {
            if (line.startsWith(headingLabel(kind) + HEADING_SEPARATOR)) {
                return kind;
            }
        }
        return null;
    }

    private static String headingLabel(Kind kind) {
        if (kind == Kind.ADD) {
            return ADD_HEADING;
        }
        return kind == Kind.DELETE ? DELETE_HEADING : UPDATE_HEADING;
    }

    /** Joins a section body and drops the blank line that separated it from the next one. */
    private static String join(String[] lines, int start, int end) {
        int last = end;
        while (last > start && lines[last - 1].isEmpty()) {
            last--;
        }
        StringBuilder value = new StringBuilder();
        for (int index = start; index < last; index++) {
            if (index != start) {
                value.append('\n');
            }
            value.append(lines[index]);
        }
        return value.toString();
    }

    private static String[] splitLines(String value) {
        return value == null || value.isEmpty() ? new String[0] : value.split("\n", -1);
    }
}
