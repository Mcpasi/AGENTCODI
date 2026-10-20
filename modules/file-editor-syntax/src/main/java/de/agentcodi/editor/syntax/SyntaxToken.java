package de.agentcodi.editor.syntax;

/** Immutable, non-overlapping UTF-16 range; never contains or changes source text. */
public final class SyntaxToken {
    public enum Kind { COMMENT, KEYWORD, STRING, NUMBER, OPERATOR, FUNCTION, BUILTIN, TAG, ATTRIBUTE }

    private final int start;
    private final int end;
    private final Kind kind;

    SyntaxToken(int start, int end, Kind kind) {
        this.start = start;
        this.end = end;
        this.kind = kind;
    }

    public int getStart() { return start; }
    public int getEnd() { return end; }
    public Kind getKind() { return kind; }
}
