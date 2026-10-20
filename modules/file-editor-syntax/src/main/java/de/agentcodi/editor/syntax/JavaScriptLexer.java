package de.agentcodi.editor.syntax;

import java.util.ArrayDeque;
import java.util.Set;

import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

final class JavaScriptLexer {
    private static final Set<String> KEYWORDS = SyntaxScanner.words(
        "async await break case catch class const continue debugger default delete do else export extends "
        + "false finally for function if import in instanceof let new null of return static super switch "
        + "this throw true try typeof var void while with yield");
    private static final Set<String> BUILTINS = SyntaxScanner.words(
        "Array BigInt Boolean Date Error Infinity JSON Map Math NaN Number Object Promise RegExp Set "
        + "String Symbol WeakMap WeakSet console document globalThis undefined window");
    private static final Set<String> CONTROL_CONDITIONS = SyntaxScanner.words("if while for with switch catch");

    static void scan(SyntaxScanner source, int start, int end) { code(source, start, end, false, 0); }

    private static int code(SyntaxScanner source, int start, int end, boolean interpolation, int depth) {
        boolean expression = true;
        boolean member = false;
        boolean controlCondition = false;
        ArrayDeque<Boolean> parentheses = new ArrayDeque<>();
        int braces = 0;
        int cursor = start;
        while (cursor < end) {
            source.checkCancelled(cursor);
            int next = cursor + 1;
            char value = source.text.charAt(cursor);
            if (Character.isWhitespace(value)) { cursor++; continue; }
            if (source.at(cursor, end, "//") || (cursor == 0 && source.at(cursor, end, "#!"))) {
                next = source.lineEnd(cursor, end);
                source.add(cursor, next, COMMENT);
            } else if (source.at(cursor, end, "/*")) {
                next = source.until(cursor + 2, end, "*/");
                source.add(cursor, next, COMMENT);
            } else if (value == '\'' || value == '"') {
                next = source.quoted(cursor, end, 1, false, false);
                source.add(cursor, next, STRING);
                expression = false;
                member = controlCondition = false;
            } else if (value == '`') {
                next = template(source, cursor, end, depth);
                expression = false;
                member = controlCondition = false;
            } else if (value == '/' && expression && (next = regexEnd(source, cursor, end)) > cursor + 1) {
                source.add(cursor, next, STRING);
                expression = false;
                member = controlCondition = false;
            } else if (Character.isDigit(value) || (value == '.' && next < end
                && Character.isDigit(source.text.charAt(next)))) {
                next = source.number(cursor, end);
                if (next < end && source.text.charAt(next) == 'n') { next++; }
                source.add(cursor, next, NUMBER);
                expression = false;
                member = controlCondition = false;
            } else if (SyntaxScanner.isIdentifierStart(value)) {
                next = source.identifier(next, end);
                String word = source.text.substring(cursor, next);
                if (!member && KEYWORDS.contains(word)) {
                    source.add(cursor, next, KEYWORD);
                    expression = !(word.equals("true") || word.equals("false") || word.equals("null")
                        || word.equals("this") || word.equals("super"));
                    controlCondition = CONTROL_CONDITIONS.contains(word)
                        || (controlCondition && word.equals("await"));
                } else {
                    int following = source.space(next, end);
                    if (!member && BUILTINS.contains(word)) { source.add(cursor, next, BUILTIN); }
                    else if (following < end && source.text.charAt(following) == '(') {
                        source.add(cursor, next, FUNCTION);
                    }
                    expression = false;
                    controlCondition = false;
                }
                member = false;
            } else {
                if (value == '}' && interpolation && braces == 0) { return cursor; }
                if (value == '{') { braces++; }
                if (value == '}') { braces--; }
                source.add(cursor, cursor + 1, OPERATOR);
                expression = value != ')' && value != ']' && value != '}';
                // A control condition ends before a statement, where / can start a regex.
                // Calls and grouped values still end an expression and permit division.
                if (value == '(') { parentheses.addLast(controlCondition); }
                if (value == ')' && !parentheses.isEmpty()) { expression = parentheses.removeLast(); }
                member = value == '.';
                controlCondition = false;
                // Postfix ++/-- must not turn a following division into a regex.
                if (source.at(cursor, end, "...")) {
                    source.add(cursor + 1, cursor + 3, OPERATOR);
                    next = cursor + 3;
                    member = false;
                } else if ((value == '+' || value == '-') && source.at(cursor, end, "" + value + value)) {
                    source.add(cursor + 1, cursor + 2, OPERATOR);
                    next = cursor + 2;
                    expression = false;
                } else { next = cursor + 1; }
            }
            cursor = next;
        }
        return cursor;
    }

    private static int template(SyntaxScanner source, int start, int end, int depth) {
        int chunk = start;
        int cursor = start + 1;
        while (cursor < end) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            if (value == '\\') { cursor = Math.min(end, cursor + 2); continue; }
            if (value == '`') { source.add(chunk, cursor + 1, STRING); return cursor + 1; }
            if (source.at(cursor, end, "${") && depth < 64) {
                source.add(chunk, cursor, STRING);
                source.add(cursor, cursor + 2, OPERATOR);
                cursor = code(source, cursor + 2, end, true, depth + 1);
                if (cursor == end) { return end; }
                source.add(cursor, cursor + 1, OPERATOR);
                chunk = ++cursor;
            } else { cursor++; }
        }
        source.add(chunk, end, STRING);
        return end;
    }

    private static int regexEnd(SyntaxScanner source, int start, int end) {
        boolean characterClass = false;
        for (int cursor = start + 1; cursor < end; cursor++) {
            source.checkCancelled(cursor);
            char value = source.text.charAt(cursor);
            if (value == '\n' || value == '\r') { break; }
            if (value == '\\') { cursor++; continue; }
            if (value == '[') { characterClass = true; }
            if (value == ']') { characterClass = false; }
            if (value == '/' && !characterClass) {
                return source.identifier(cursor + 1, end);
            }
        }
        return start + 1;
    }
}
