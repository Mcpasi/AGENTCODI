package de.agentcodi.editor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Shared linear scans. No regex backtracking, Android dependencies or source rewrites. */
final class SyntaxScanner {
    final String text;
    final List<SyntaxToken> tokens = new ArrayList<>();

    SyntaxScanner(String text) { this.text = text; }

    void add(int start, int end, SyntaxToken.Kind kind) {
        if (end > start) { tokens.add(new SyntaxToken(start, end, kind)); }
    }

    void checkCancelled(int offset) {
        if ((offset & 1023) == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    boolean at(int offset, int end, String value) {
        return offset + value.length() <= end && text.startsWith(value, offset);
    }

    int until(int start, int end, String terminator) {
        int found = text.indexOf(terminator, start);
        return found < 0 || found + terminator.length() > end ? end : found + terminator.length();
    }

    int lineEnd(int start, int end) {
        while (start < end && text.charAt(start) != '\n' && text.charAt(start) != '\r') {
            checkCancelled(start++);
        }
        return start;
    }

    int space(int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) { checkCancelled(start++); }
        return start;
    }

    int identifier(int start, int end) {
        while (start < end && isIdentifierPart(text.charAt(start))) { checkCancelled(start++); }
        return start;
    }

    static boolean isIdentifierStart(char value) {
        return value == '_' || value == '$' || Character.isUnicodeIdentifierStart(value);
    }

    static boolean isIdentifierPart(char value) {
        return value == '$' || Character.isUnicodeIdentifierPart(value);
    }

    int quoted(int start, int end, int delimiterLength, boolean multiline, boolean raw) {
        char quote = text.charAt(start);
        int cursor = start + delimiterLength;
        while (cursor < end) {
            checkCancelled(cursor);
            char value = text.charAt(cursor);
            if (!multiline && (value == '\n' || value == '\r')) { return cursor; }
            // Even raw Python strings escape their closing quote lexically.
            if (value == '\\' && cursor + 1 < end) {
                cursor += 2;
                if (cursor < end && text.charAt(cursor - 1) == '\r'
                    && text.charAt(cursor) == '\n') { cursor++; }
                continue;
            }
            if (value == quote) {
                int closing = 1;
                while (closing < delimiterLength && cursor + closing < end
                    && text.charAt(cursor + closing) == quote) { closing++; }
                if (closing == delimiterLength) { return cursor + delimiterLength; }
            }
            cursor++;
        }
        return end;
    }

    int number(int start, int end) {
        int cursor = start;
        if (at(cursor, end, "0x") || at(cursor, end, "0X") || at(cursor, end, "0b")
            || at(cursor, end, "0B") || at(cursor, end, "0o") || at(cursor, end, "0O")) {
            char radix = Character.toLowerCase(text.charAt(cursor + 1));
            int base = radix == 'x' ? 16 : radix == 'b' ? 2 : 8;
            cursor += 2;
            while (cursor < end && (text.charAt(cursor) == '_'
                || Character.digit(text.charAt(cursor), base) >= 0)) { checkCancelled(cursor++); }
        } else {
            while (cursor < end && (Character.isDigit(text.charAt(cursor))
                || text.charAt(cursor) == '_')) { checkCancelled(cursor++); }
            if (cursor < end && text.charAt(cursor) == '.') {
                cursor++;
                while (cursor < end && (Character.isDigit(text.charAt(cursor))
                    || text.charAt(cursor) == '_')) { checkCancelled(cursor++); }
            }
            if (cursor < end && (text.charAt(cursor) == 'e' || text.charAt(cursor) == 'E')) {
                int exponent = cursor + 1;
                if (exponent < end && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                    exponent++;
                }
                if (exponent < end && Character.isDigit(text.charAt(exponent))) {
                    cursor = exponent + 1;
                    while (cursor < end && (Character.isDigit(text.charAt(cursor))
                        || text.charAt(cursor) == '_')) { checkCancelled(cursor++); }
                }
            }
        }
        return cursor;
    }

    static Set<String> words(String values) { return new HashSet<>(Arrays.asList(values.split(" "))); }
}
