package de.agentcodi.editor.syntax;

import java.util.Locale;

import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

final class HtmlLexer {
    static void scan(SyntaxScanner source, int start, int end) {
        for (int cursor = start; cursor < end;) {
            source.checkCancelled(cursor);
            if (source.at(cursor, end, "<!--")) {
                int next = source.until(cursor + 4, end, "-->");
                source.add(cursor, next, COMMENT);
                cursor = next;
            } else if (source.at(cursor, end, "<!")) {
                int next = source.until(cursor + 2, end, ">");
                source.add(cursor, next, KEYWORD);
                cursor = next;
            } else if (source.text.charAt(cursor) == '<' && cursor + 1 < end
                && (Character.isLetter(source.text.charAt(cursor + 1)) || source.text.charAt(cursor + 1) == '/')) {
                boolean closing = source.text.charAt(cursor + 1) == '/';
                int nameStart = cursor + (closing ? 2 : 1);
                int nameEnd = name(source, nameStart, end);
                String tag = source.text.substring(nameStart, nameEnd).toLowerCase(Locale.ROOT);
                source.add(cursor, nameStart, OPERATOR);
                source.add(nameStart, nameEnd, TAG);
                cursor = nameEnd;
                String type = "";
                boolean selfClosing = false;
                boolean finished = false;
                while (cursor < end) {
                    source.checkCancelled(cursor);
                    cursor = source.space(cursor, end);
                    if (cursor == end) { break; }
                    if (source.at(cursor, end, "/>" ) || source.text.charAt(cursor) == '>') {
                        selfClosing = source.text.charAt(cursor) == '/';
                        int next = cursor + (selfClosing ? 2 : 1);
                        source.add(cursor, next, OPERATOR);
                        cursor = next;
                        finished = true;
                        break;
                    }
                    int attributeEnd = name(source, cursor, end);
                    if (attributeEnd == cursor) { cursor++; continue; }
                    String attribute = source.text.substring(cursor, attributeEnd);
                    source.add(cursor, attributeEnd, ATTRIBUTE);
                    cursor = source.space(attributeEnd, end);
                    if (cursor < end && source.text.charAt(cursor) == '=') {
                        source.add(cursor, cursor + 1, OPERATOR);
                        cursor = source.space(cursor + 1, end);
                        if (cursor == end) { break; }
                        int valueStart = cursor;
                        char quote = source.text.charAt(cursor);
                        if (quote == '\'' || quote == '"') {
                            // HTML has no backslash quote escapes.
                            int closingQuote = source.text.indexOf(quote, cursor + 1);
                            cursor = closingQuote < 0 || closingQuote >= end ? end : closingQuote + 1;
                        } else {
                            while (cursor < end && !Character.isWhitespace(source.text.charAt(cursor))
                                && source.text.charAt(cursor) != '>') { source.checkCancelled(cursor++); }
                        }
                        source.add(valueStart, cursor, STRING);
                        if (attribute.equalsIgnoreCase("type")) {
                            type = source.text.substring(valueStart, cursor).replace("\"", "")
                                .replace("'", "").trim().toLowerCase(Locale.ROOT);
                        }
                    }
                }
                if (!closing && !selfClosing && finished && (tag.equals("script") || tag.equals("style"))) {
                    int close = closingTag(source, cursor, end, tag);
                    if (tag.equals("style") && (type.isEmpty() || type.equals("text/css"))) {
                        CssLexer.scan(source, cursor, close);
                    } else if (tag.equals("script") && (type.isEmpty() || type.equals("module")
                        || type.equals("text/javascript") || type.equals("application/javascript"))) {
                        JavaScriptLexer.scan(source, cursor, close);
                    }
                    cursor = close;
                } else if (!closing && finished && (tag.equals("textarea") || tag.equals("title"))) {
                    cursor = closingTag(source, cursor, end, tag);
                }
            } else if (source.text.charAt(cursor) == '&') {
                int next = cursor + 1;
                while (next < end && next - cursor <= 32 && (Character.isLetterOrDigit(source.text.charAt(next))
                    || source.text.charAt(next) == '#')) { next++; }
                if (next < end && source.text.charAt(next) == ';') {
                    source.add(cursor, next + 1, BUILTIN);
                    cursor = next + 1;
                } else { cursor++; }
            } else { cursor++; }
        }
    }

    private static int name(SyntaxScanner source, int cursor, int end) {
        while (cursor < end && (Character.isLetterOrDigit(source.text.charAt(cursor))
            || "-_:".indexOf(source.text.charAt(cursor)) >= 0)) { source.checkCancelled(cursor++); }
        return cursor;
    }

    private static int closingTag(SyntaxScanner source, int cursor, int end, String tag) {
        String closing = "</" + tag;
        for (; cursor + closing.length() <= end; cursor++) {
            source.checkCancelled(cursor);
            if (source.text.regionMatches(true, cursor, closing, 0, closing.length())) {
                int after = cursor + closing.length();
                if (after == end || Character.isWhitespace(source.text.charAt(after))
                    || source.text.charAt(after) == '>' || source.text.charAt(after) == '/') { return cursor; }
            }
        }
        return end;
    }
}
