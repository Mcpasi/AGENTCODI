package de.agentcodi.runtime;

import de.agentcodi.browser.WorkspaceBrowserLimits;
import de.agentcodi.storage.WorkspaceDirectoryCatalog;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class NativeWorkspaceDirectoryCatalog {
    private static final int FRAME_VERSION = 1;
    private static final int HEADER_BYTES = 2;
    private static final int ENTRY_FIXED_BYTES = 18;
    private static final WorkspaceDirectoryCatalog.Reader READER = new NativeReader();

    private NativeWorkspaceDirectoryCatalog() {
    }

    static WorkspaceDirectoryCatalog.Reader reader() {
        return READER;
    }

    private static final class NativeReader implements WorkspaceDirectoryCatalog.Reader {
        @Override
        public WorkspaceDirectoryCatalog.Snapshot list(
            File workspaceDirectory,
            String relativeDirectory,
            int maximumEntries,
            int maximumRelativePathCharacters,
            int maximumDepth
        ) throws IOException {
            if (workspaceDirectory == null) {
                throw new IllegalArgumentException("workspaceDirectory must not be null");
            }
            if (maximumEntries <= 0
                || maximumEntries > WorkspaceBrowserLimits.MAXIMUM_SCANNED_DIRECTORY_ENTRIES
                || maximumRelativePathCharacters <= 0
                || maximumRelativePathCharacters
                    > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS
                || maximumDepth <= 0
                || maximumDepth > WorkspaceBrowserLimits.MAXIMUM_DIRECTORY_DEPTH) {
                throw new IllegalArgumentException(
                    "Native workspace directory limits are invalid"
                );
            }
            int maximumRelativePathBytes = Math.multiplyExact(
                maximumRelativePathCharacters,
                4
            );
            byte[][] frames = NativeEngine.listWorkspaceDirectory(
                workspaceDirectory.getCanonicalPath(),
                relativeDirectory,
                maximumEntries,
                maximumRelativePathBytes,
                maximumDepth
            );
            if (frames == null || frames.length == 0
                || (long) frames.length > (long) maximumEntries + 1L
                || frames[0] == null || frames[0].length != HEADER_BYTES
                || unsigned(frames[0][0]) != FRAME_VERSION
                || unsigned(frames[0][1]) > 1) {
                throw new IOException("Native workspace directory header is invalid");
            }
            boolean truncated = frames[0][1] != 0;
            ArrayList<WorkspaceDirectoryCatalog.Entry> entries =
                new ArrayList<WorkspaceDirectoryCatalog.Entry>(frames.length - 1);
            for (int index = 1; index < frames.length; index++) {
                entries.add(decodeEntry(
                    relativeDirectory,
                    frames[index],
                    maximumRelativePathCharacters
                ));
            }
            return WorkspaceDirectoryCatalog.Snapshot.of(entries, truncated);
        }
    }

    private static WorkspaceDirectoryCatalog.Entry decodeEntry(
        String relativeDirectory,
        byte[] frame,
        int maximumRelativePathCharacters
    ) throws IOException {
        if (frame == null || frame.length <= ENTRY_FIXED_BYTES) {
            throw new IOException("Native workspace directory entry is invalid");
        }
        int kind = unsigned(frame[0]);
        int reason = unsigned(frame[1]);
        ByteBuffer values = ByteBuffer.wrap(frame, 2, 16).order(ByteOrder.BIG_ENDIAN);
        long byteCount = values.getLong();
        long modifiedMillis = values.getLong();
        String name;
        try {
            name = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(frame, ENTRY_FIXED_BYTES, frame.length - ENTRY_FIXED_BYTES))
                .toString();
        } catch (CharacterCodingException error) {
            return WorkspaceDirectoryCatalog.Entry.unavailable(
                "[invalid name]",
                "",
                Math.max(0L, modifiedMillis),
                WorkspaceDirectoryCatalog.REASON_UNSAFE_NAME
            );
        }
        String relativePath = relativeDirectory == null || relativeDirectory.isEmpty()
            ? name
            : relativeDirectory + "/" + name;
        String displayName = safeDisplayName(name);
        if (!safeName(name) || relativePath.length() > maximumRelativePathCharacters) {
            return WorkspaceDirectoryCatalog.Entry.unavailable(
                displayName,
                "",
                Math.max(0L, modifiedMillis),
                WorkspaceDirectoryCatalog.REASON_UNSAFE_NAME
            );
        }
        if (kind == 1 && reason == 0 && byteCount == -1L) {
            return WorkspaceDirectoryCatalog.Entry.directory(
                displayName,
                relativePath,
                modifiedMillis
            );
        }
        if (kind == 2 && reason == 0 && byteCount >= 0L) {
            return WorkspaceDirectoryCatalog.Entry.regularFile(
                displayName,
                relativePath,
                byteCount,
                modifiedMillis
            );
        }
        if (kind != 3 || byteCount != -1L) {
            throw new IOException("Native workspace directory entry kind is invalid");
        }
        return WorkspaceDirectoryCatalog.Entry.unavailable(
            displayName,
            relativePath,
            modifiedMillis,
            reasonName(reason)
        );
    }

    private static String reasonName(int reason) throws IOException {
        if (reason == 1) {
            return WorkspaceDirectoryCatalog.REASON_SYMBOLIC_LINK;
        }
        if (reason == 2) {
            return WorkspaceDirectoryCatalog.REASON_HARD_LINK;
        }
        if (reason == 3) {
            return WorkspaceDirectoryCatalog.REASON_SPECIAL_ENTRY;
        }
        if (reason == 4) {
            return WorkspaceDirectoryCatalog.REASON_UNSAFE_NAME;
        }
        if (reason == 5) {
            return WorkspaceDirectoryCatalog.REASON_UNREADABLE;
        }
        throw new IOException("Native workspace directory entry reason is invalid");
    }

    private static boolean safeName(String value) {
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

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
