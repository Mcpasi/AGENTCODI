package de.agentcodi.editor.syntax;

import java.util.Set;

import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

final class PythonLexer {
    private static final Set<String> KEYWORDS = SyntaxScanner.words(
        "False None True and as assert async await break case class continue def del elif else except "
        + "finally for from global if import in is lambda match nonlocal not or pass raise return try while with yield");
    private static final Set<String> BUILTINS = SyntaxScanner.words(
        "abs all any bool bytes callable chr dict enumerate filter float format frozenset getattr hasattr "
        + "int isinstance issubclass iter len list map max min next object open ord print range repr reversed "
        + "round self set slice sorted str sum super tuple type vars zip Exception ValueError TypeError");
    private static final Set<String> PREFIXES = SyntaxScanner.words("r u b f br rb fr rf");

    static void scan(SyntaxScanner source, int start, int end) {
        code(source, start, end, false, 0);
    }

    private static int code(SyntaxScanner source, int start, int end, boolean interpolation, int depth) {
        int braces = 0;
        int cursor = start;
        for (; cursor < end;) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            int next = cursor + 1;
            if (Character.isWhitespace(value)) { cursor++; continue; }
            if (value == '#') {
                next = source.lineEnd(cursor, end);
                source.add(cursor, next, COMMENT);
            } else if (value == '\'' || value == '"') {
                next = string(source, cursor, end, false);
                source.add(cursor, next, STRING);
            } else if (Character.isDigit(value) || (value == '.' && next < end
                && Character.isDigit(source.text.charAt(next)))) {
                next = source.number(cursor, end);
                if (next < end && (source.text.charAt(next) == 'j' || source.text.charAt(next) == 'J')) { next++; }
                source.add(cursor, next, NUMBER);
            } else if (SyntaxScanner.isIdentifierStart(value)) {
                next = source.identifier(next, end);
                String word = source.text.substring(cursor, next);
                String prefix = word.toLowerCase(java.util.Locale.ROOT);
                if (PREFIXES.contains(prefix) && next < end
                    && (source.text.charAt(next) == '\'' || source.text.charAt(next) == '"')) {
                    if (prefix.indexOf('f') >= 0 && depth < 64) {
                        next = formatted(source, cursor, next, end, depth);
                    } else {
                        next = string(source, next, end, prefix.indexOf('r') >= 0);
                        source.add(cursor, next, STRING);
                    }
                } else if (KEYWORDS.contains(word)) { source.add(cursor, next, KEYWORD); }
                else if (BUILTINS.contains(word)) { source.add(cursor, next, BUILTIN); }
                else {
                    int following = source.space(next, end);
                    if (following < end && source.text.charAt(following) == '(') {
                        source.add(cursor, next, FUNCTION);
                    }
                }
            } else if (value == '@' && next < end && SyntaxScanner.isIdentifierStart(source.text.charAt(next))) {
                while (next < end && (SyntaxScanner.isIdentifierPart(source.text.charAt(next))
                    || source.text.charAt(next) == '.')) { next++; }
                source.add(cursor, next, FUNCTION);
            } else {
                if (value == '}' && interpolation && braces == 0) { return cursor; }
                if (value == '{') { braces++; }
                if (value == '}') { braces--; }
                source.add(cursor, next, OPERATOR);
            }
            cursor = next;
        }
        return cursor;
    }

    private static int string(SyntaxScanner source, int start, int end, boolean raw) {
        char quote = source.text.charAt(start);
        boolean triple = start + 2 < end && source.text.charAt(start + 1) == quote
            && source.text.charAt(start + 2) == quote;
        return source.quoted(start, end, triple ? 3 : 1, triple, raw);
    }

    private static int formatted(SyntaxScanner source, int start, int quoteStart, int end, int depth) {
        char quote = source.text.charAt(quoteStart);
        int delimiter = quoteStart + 2 < end && source.text.charAt(quoteStart + 1) == quote
            && source.text.charAt(quoteStart + 2) == quote ? 3 : 1;
        String closing = delimiter == 3 ? "" + quote + quote + quote : "" + quote;
        int chunk = start;
        int cursor = quoteStart + delimiter;
        while (cursor < end) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            if (source.at(cursor, end, closing)) {
                source.add(chunk, cursor + delimiter, STRING);
                return cursor + delimiter;
            }
            if (delimiter == 1 && (value == '\n' || value == '\r')) { break; }
            if (value == '\\' && cursor + 1 < end && source.text.charAt(cursor + 1) != '{') {
                cursor += 2;
                if (cursor < end && source.text.charAt(cursor - 1) == '\r'
                    && source.text.charAt(cursor) == '\n') { cursor++; }
            } else if (source.at(cursor, end, "{{") || source.at(cursor, end, "}}")) {
                cursor += 2;
            } else if (value == '{') {
                source.add(chunk, cursor, STRING);
                source.add(cursor, cursor + 1, OPERATOR);
                cursor = code(source, cursor + 1, end, true, depth + 1);
                if (cursor == end) { return end; }
                source.add(cursor, cursor + 1, OPERATOR);
                chunk = ++cursor;
            } else { cursor++; }
        }
        source.add(chunk, cursor, STRING);
        return cursor;
    }
}
