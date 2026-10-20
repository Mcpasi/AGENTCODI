package de.agentcodi.editor.syntax;

import java.util.Arrays;

/** Logical source lines, independent of visual wrapping; CRLF is one line break. */
public final class SourceLines {
    private final int[] starts;
    private final int length;

    public SourceLines(CharSequence text) {
        length = text.length();
        int[] offsets = new int[Math.min(length + 1, 128)];
        int count = 1;
        for (int index = 0; index < length; index++) {
            char value = text.charAt(index);
            if (value != '\r' && value != '\n') { continue; }
            if (value == '\r' && index + 1 < length && text.charAt(index + 1) == '\n') { index++; }
            if (count == offsets.length) { offsets = Arrays.copyOf(offsets, offsets.length * 2); }
            offsets[count++] = index + 1;
        }
        starts = Arrays.copyOf(offsets, count);
    }

    public int getLineCount() { return starts.length; }
    public int getStart(int line) { return starts[line]; }

    /** Zero-based logical line containing an insertion/cursor offset. */
    public int getLineForOffset(int offset) {
        if (offset < 0 || offset > length) { throw new IndexOutOfBoundsException(); }
        int found = Arrays.binarySearch(starts, offset);
        return found >= 0 ? found : -found - 2;
    }
}
