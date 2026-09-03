package de.agentcodi.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class TerminalOutputBuffer {
    public static final int MAXIMUM_CHARACTERS = 128 * 1024;
    private static final String OMITTED_PREFIX = "[earlier terminal output omitted]\n";

    private static final int ANSI_NORMAL = 0;
    private static final int ANSI_ESCAPE = 1;
    private static final int ANSI_CSI = 2;
    private static final int ANSI_OSC = 3;
    private static final int ANSI_OSC_ESCAPE = 4;
    private static final int MAXIMUM_ANSI_SEQUENCE_CHARACTERS = 4096;

    private final StringBuilder output = new StringBuilder();
    private byte[] pendingUtf8 = new byte[0];
    private int ansiState;
    private int ansiSequenceCharacters;
    private boolean carriageReturn;
    private boolean omitted;

    public void append(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        byte[] combined = new byte[pendingUtf8.length + bytes.length];
        System.arraycopy(pendingUtf8, 0, combined, 0, pendingUtf8.length);
        System.arraycopy(bytes, 0, combined, pendingUtf8.length, bytes.length);
        Arrays.fill(pendingUtf8, (byte) 0);
        pendingUtf8 = new byte[0];
        try {
            int pendingLength = incompleteUtf8SuffixLength(combined);
            int decodedLength = combined.length - pendingLength;
            if (decodedLength > 0) {
                appendDecoded(new String(combined, 0, decodedLength, StandardCharsets.UTF_8));
            }
            if (pendingLength > 0) {
                pendingUtf8 = Arrays.copyOfRange(
                    combined,
                    decodedLength,
                    combined.length
                );
            }
        } finally {
            Arrays.fill(combined, (byte) 0);
        }
    }

    public void finish() {
        if (pendingUtf8.length > 0) {
            Arrays.fill(pendingUtf8, (byte) 0);
            pendingUtf8 = new byte[0];
            appendDecoded("\uFFFD");
        }
        if (carriageReturn) {
            output.append('\n');
            carriageReturn = false;
            trimToLimit();
        }
        ansiState = ANSI_NORMAL;
        ansiSequenceCharacters = 0;
    }

    public void clear() {
        for (int index = 0; index < output.length(); index++) {
            output.setCharAt(index, '\0');
        }
        output.setLength(0);
        Arrays.fill(pendingUtf8, (byte) 0);
        pendingUtf8 = new byte[0];
        ansiState = ANSI_NORMAL;
        ansiSequenceCharacters = 0;
        carriageReturn = false;
        omitted = false;
    }

    public String snapshot() {
        return CrashReportFormatter.redactVisibleText(
            output.toString(),
            MAXIMUM_CHARACTERS
        );
    }

    private void appendDecoded(String decoded) {
        for (int index = 0; index < decoded.length(); index++) {
            char character = decoded.charAt(index);
            if (ansiState == ANSI_ESCAPE) {
                if (character == '[') {
                    ansiState = ANSI_CSI;
                } else if (character == ']') {
                    ansiState = ANSI_OSC;
                } else {
                    ansiState = ANSI_NORMAL;
                }
                ansiSequenceCharacters = 0;
                continue;
            }
            if (ansiState == ANSI_CSI) {
                ansiSequenceCharacters++;
                if (character >= 0x40 && character <= 0x7e) {
                    ansiState = ANSI_NORMAL;
                    ansiSequenceCharacters = 0;
                } else if (ansiSequenceCharacters >= MAXIMUM_ANSI_SEQUENCE_CHARACTERS) {
                    ansiState = ANSI_NORMAL;
                    ansiSequenceCharacters = 0;
                }
                continue;
            }
            if (ansiState == ANSI_OSC) {
                ansiSequenceCharacters++;
                if (character == 0x07) {
                    ansiState = ANSI_NORMAL;
                    ansiSequenceCharacters = 0;
                } else if (character == 0x1b) {
                    ansiState = ANSI_OSC_ESCAPE;
                } else if (ansiSequenceCharacters >= MAXIMUM_ANSI_SEQUENCE_CHARACTERS) {
                    ansiState = ANSI_NORMAL;
                    ansiSequenceCharacters = 0;
                }
                continue;
            }
            if (ansiState == ANSI_OSC_ESCAPE) {
                ansiSequenceCharacters++;
                if (character == '\\'
                    || ansiSequenceCharacters >= MAXIMUM_ANSI_SEQUENCE_CHARACTERS) {
                    ansiState = ANSI_NORMAL;
                    ansiSequenceCharacters = 0;
                } else {
                    ansiState = ANSI_OSC;
                }
                continue;
            }
            if (character == 0x1b) {
                ansiState = ANSI_ESCAPE;
                ansiSequenceCharacters = 0;
                continue;
            }
            if (character == '\r') {
                carriageReturn = true;
                continue;
            }
            if (character == '\n') {
                output.append('\n');
                carriageReturn = false;
                continue;
            }
            if (carriageReturn) {
                output.append('\n');
                carriageReturn = false;
            }
            if (character == '\b') {
                int length = output.length();
                if (length > 0 && output.charAt(length - 1) != '\n') {
                    int removed = length - 1;
                    if (removed > 0
                        && Character.isLowSurrogate(output.charAt(removed))
                        && Character.isHighSurrogate(output.charAt(removed - 1))) {
                        removed--;
                    }
                    output.setLength(removed);
                }
            } else if (character == '\t'
                || (character >= 0x20
                    && character != 0x7f
                    && (character < 0x80 || character > 0x9f))) {
                output.append(character);
            }
        }
        trimToLimit();
    }

    private void trimToLimit() {
        if (output.length() <= MAXIMUM_CHARACTERS) {
            return;
        }
        int target = output.length() - MAXIMUM_CHARACTERS + OMITTED_PREFIX.length();
        int newline = output.indexOf("\n", Math.max(0, target));
        int remove = newline < 0 ? target : newline + 1;
        if (remove > 0 && remove < output.length()
            && Character.isLowSurrogate(output.charAt(remove))) {
            remove++;
        }
        output.delete(0, Math.min(remove, output.length()));
        if (!omitted) {
            output.insert(0, OMITTED_PREFIX);
            omitted = true;
        }
        if (output.length() > MAXIMUM_CHARACTERS) {
            output.delete(
                OMITTED_PREFIX.length(),
                OMITTED_PREFIX.length() + output.length() - MAXIMUM_CHARACTERS
            );
        }
    }

    private static int incompleteUtf8SuffixLength(byte[] value) {
        if (value.length == 0) {
            return 0;
        }
        int start = value.length - 1;
        int continuations = 0;
        while (start >= 0 && continuations < 3
            && (value[start] & 0xc0) == 0x80) {
            continuations++;
            start--;
        }
        if (start < 0) {
            return 0;
        }
        int lead = value[start] & 0xff;
        int expected;
        if ((lead & 0xe0) == 0xc0) {
            expected = 2;
        } else if ((lead & 0xf0) == 0xe0) {
            expected = 3;
        } else if ((lead & 0xf8) == 0xf0) {
            expected = 4;
        } else {
            return 0;
        }
        int available = value.length - start;
        return available < expected ? available : 0;
    }
}
