package de.agentcodi.app;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure presentation projection: the redacted transcript source is never modified. */
final class ChatMarkdown {
    enum Style { BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, HEADING, QUOTE, LINK, TABLE, RULE }

    static final class Span {
        final Style style;
        final int start;
        final int end;
        final int level;
        final String destination;

        Span(Style style, int start, int end, int level, String destination) {
            this.style = style;
            this.start = start;
            this.end = end;
            this.level = level;
            this.destination = destination;
        }
    }

    static final class Document {
        final String text;
        final List<Span> spans;

        Document(String text, List<Span> spans) {
            this.text = text;
            this.spans = Collections.unmodifiableList(new ArrayList<Span>(spans));
        }
    }

    private static final int MAX_INLINE_DEPTH = 32;

    static Document parse(String source) {
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        Builder output = new Builder();
        StringBuilder paragraph = new StringBuilder();
        char fence = 0;
        int fenceLength = 0;
        int codeStart = 0;
        boolean inList = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String newline = index + 1 < lines.length ? "\n" : "";
            String trimmed = line.trim();
            int indent = indentation(line);
            int run = trimmed.isEmpty() ? 0 : runLength(trimmed, 0);
            if (fence != 0) {
                if (indent <= 3 && !trimmed.isEmpty() && trimmed.charAt(0) == fence
                    && run >= fenceLength && trimmed.substring(run).isEmpty()) {
                    output.span(Style.CODE_BLOCK, codeStart, output.length());
                    fence = 0;
                } else {
                    output.text.append(line).append(newline);
                }
                continue;
            }
            if (indent <= 3 && run >= 3 && (trimmed.charAt(0) == '`'
                || trimmed.charAt(0) == '~')
                && (trimmed.charAt(0) != '`' || trimmed.substring(run).indexOf('`') < 0)) {
                flush(paragraph, output);
                fence = trimmed.charAt(0);
                fenceLength = run;
                codeStart = output.length();
                inList = false;
                continue;
            }
            if (index + 1 < lines.length && !trimmed.isEmpty() && indent <= 3
                && line.indexOf('|') >= 0 && tableSeparator(lines[index + 1])
                && cells(line).size() == cells(lines[index + 1]).size()) {
                flush(paragraph, output);
                index = table(lines, index, output);
                inList = false;
                continue;
            }
            if (index + 1 < lines.length && !trimmed.isEmpty() && indent <= 3
                && headingLevel(trimmed) == 0 && listPrefix(trimmed) == 0
                && trimmed.charAt(0) != '>' && setextLevel(lines[index + 1]) != 0) {
                // The underline applies to the entire preceding paragraph.
                paragraph.append(line);
                int start = output.length();
                flush(paragraph, output);
                output.spans.add(new Span(Style.HEADING, start, output.length(),
                    setextLevel(lines[index + 1]), ""));
                index++;
                if (index + 1 < lines.length) {
                    output.text.append('\n');
                }
                inList = false;
                continue;
            }
            if (trimmed.isEmpty()) {
                flush(paragraph, output);
                output.text.append(newline);
                inList = false;
                continue;
            }
            int level = indent <= 3 ? headingLevel(trimmed) : 0;
            if (level != 0) {
                flush(paragraph, output);
                String body = trimmed.substring(level).trim();
                int end = body.length();
                while (end > 0 && body.charAt(end - 1) == '#') {
                    end--;
                }
                if (end < body.length() && (end == 0 || Character.isWhitespace(body.charAt(end - 1)))) {
                    body = body.substring(0, end).trim();
                }
                int start = output.length();
                output.append(inline(body, 0));
                output.spans.add(new Span(Style.HEADING, start, output.length(), level, ""));
                output.text.append(newline);
                inList = false;
            } else if (indent <= 3 && isRule(trimmed)) {
                flush(paragraph, output);
                int start = output.length();
                output.text.append("────────");
                output.span(Style.RULE, start, output.length());
                output.text.append(newline);
                inList = false;
            } else if (indent <= 3 && trimmed.charAt(0) == '>') {
                flush(paragraph, output);
                int position = 0;
                while (position < trimmed.length() && trimmed.charAt(position) == '>') {
                    position++;
                    if (position < trimmed.length() && trimmed.charAt(position) == ' ') {
                        position++;
                    }
                }
                int start = output.length();
                output.append(inline(trimmed.substring(position), 0));
                output.span(Style.QUOTE, start, output.length());
                output.text.append(newline);
                inList = false;
            } else if ((indent <= 3 || inList) && listPrefix(trimmed) != 0) {
                flush(paragraph, output);
                int prefix = listPrefix(trimmed);
                String body = trimmed.substring(prefix).trim();
                output.text.append(line, 0, indent);
                char marker = trimmed.charAt(0);
                output.text.append(Character.isDigit(marker)
                    ? trimmed.substring(0, prefix).trim() + " " : "• ");
                if (body.startsWith("[ ] ") || body.startsWith("[x] ") || body.startsWith("[X] ")) {
                    output.text.append(body.charAt(1) == ' ' ? "☐ " : "☑ ");
                    body = body.substring(4);
                }
                output.append(inline(body, 0));
                output.text.append(newline);
                inList = true;
            } else if (indent >= 4 && !inList && paragraph.length() == 0) {
                int start = output.length();
                output.text.append(line.substring(4)).append(newline);
                output.span(Style.CODE_BLOCK, start, output.length());
            } else {
                paragraph.append(line).append(newline);
            }
        }
        flush(paragraph, output);
        if (fence != 0) {
            output.span(Style.CODE_BLOCK, codeStart, output.length());
        }
        return output.document();
    }

    private static void flush(StringBuilder paragraph, Builder output) {
        if (paragraph.length() != 0) {
            output.append(inline(paragraph.toString(), 0));
            paragraph.setLength(0);
        }
    }

    private static Document inline(String source, int depth) {
        Builder output = new Builder();
        if (depth >= MAX_INLINE_DEPTH || !inlineSyntax(source)) {
            return new Document(source, Collections.<Span>emptyList());
        }
        // Index exact code runs and balanced brackets once. Unclosed streamed markup
        // must not repeatedly scan the remaining suffix for every delimiter.
        int[] codeEnds = codeEnds(source);
        int[] brackets = pairs(source, '[', ']', codeEnds);
        int[] parentheses = pairs(source, '(', ')', codeEnds);
        List<Delimiter> delimiters = new ArrayList<Delimiter>();
        List<int[]> removals = new ArrayList<int[]>();
        for (int index = 0; index < source.length();) {
            char character = source.charAt(index);
            if (character == '\\' && index + 1 < source.length()
                && punctuation(source.charAt(index + 1))) {
                output.text.append(source.charAt(index + 1));
                index += 2;
            } else if (character == '`') {
                int count = runLength(source, index);
                int end = codeEnds[index];
                if (end >= 0) {
                    String code = source.substring(index + count, end).replace('\n', ' ');
                    if (code.startsWith(" ") && code.endsWith(" ") && !code.trim().isEmpty()) {
                        code = code.substring(1, code.length() - 1);
                    }
                    int start = output.length();
                    output.text.append(code);
                    output.span(Style.CODE, start, output.length());
                    index = end + count;
                } else {
                    output.text.append(source, index, index + count);
                    index += count;
                }
            } else if ((character == '[' || (character == '!' && index + 1 < source.length()
                && source.charAt(index + 1) == '['))) {
                boolean image = character == '!';
                int opening = image ? index + 1 : index;
                int closing = brackets[opening];
                int destinationEnd = closing >= 0 && closing + 1 < source.length()
                    && source.charAt(closing + 1) == '(' ? parentheses[closing + 1] : -1;
                if (destinationEnd >= 0) {
                    int start = output.length();
                    output.append(inline(source.substring(opening + 1, closing), depth + 1));
                    String destination = destination(source.substring(closing + 2, destinationEnd));
                    if (!image && safeDestination(destination)) {
                        output.spans.add(new Span(Style.LINK, start, output.length(), 0, destination));
                    }
                    index = destinationEnd + 1;
                } else {
                    output.text.append(character);
                    index++;
                }
            } else if (character == '<') {
                int end = index + 1;
                while (end < source.length() && source.charAt(end) != '>'
                    && source.charAt(end) != '<'
                    && !Character.isWhitespace(source.charAt(end))) {
                    end++;
                }
                String address = source.substring(index + 1, end);
                if (end < source.length() && source.charAt(end) == '>' && safeDestination(address)) {
                    int start = output.length();
                    output.text.append(address);
                    output.spans.add(new Span(Style.LINK, start, output.length(), 0, address));
                    index = end + 1;
                } else {
                    output.text.append(character);
                    index++;
                }
            } else if (character == '*' || character == '_' || character == '~') {
                int count = runLength(source, index);
                boolean supported = character == '~' ? count == 2 : count <= 3;
                char before = index == 0 ? ' ' : source.charAt(index - 1);
                char after = index + count == source.length() ? ' ' : source.charAt(index + count);
                boolean inWord = character == '_' && Character.isLetterOrDigit(before)
                    && Character.isLetterOrDigit(after);
                int start = output.length();
                output.text.append(source, index, index + count);
                if (supported && !inWord) {
                    int matching = -1;
                    if (!Character.isWhitespace(before)) {
                        for (int slot = delimiters.size() - 1; slot >= 0; slot--) {
                            Delimiter candidate = delimiters.get(slot);
                            if (candidate.character == character && candidate.count == count
                                && candidate.start + count < start) {
                                matching = slot;
                                break;
                            }
                        }
                    }
                    if (matching < 0 && count == 3 && character != '~'
                        && !Character.isWhitespace(before) && delimiters.size() >= 2) {
                        Delimiter inner = delimiters.get(delimiters.size() - 1);
                        Delimiter outer = delimiters.get(delimiters.size() - 2);
                        if (inner.character == character && outer.character == character
                            && inner.count + outer.count == count
                            && inner.start + inner.count < start) {
                            removals.add(new int[] { inner.start, inner.start + inner.count });
                            removals.add(new int[] { outer.start, outer.start + outer.count });
                            removals.add(new int[] { start, start + count });
                            output.span(inner.count == 1 ? Style.ITALIC : Style.BOLD,
                                inner.start + inner.count, start);
                            output.span(outer.count == 1 ? Style.ITALIC : Style.BOLD,
                                outer.start + outer.count, start);
                            delimiters.subList(delimiters.size() - 2, delimiters.size()).clear();
                            index += count;
                            continue;
                        }
                    }
                    if (matching >= 0) {
                        Delimiter opening = delimiters.get(matching);
                        removals.add(new int[] { opening.start, opening.start + count });
                        removals.add(new int[] { start, start + count });
                        int contentStart = opening.start + count;
                        if (character == '~') {
                            output.span(Style.STRIKE, contentStart, start);
                        } else {
                            if (count >= 2) { output.span(Style.BOLD, contentStart, start); }
                            if (count == 1 || count == 3) { output.span(Style.ITALIC, contentStart, start); }
                        }
                        delimiters.subList(matching, delimiters.size()).clear();
                    } else if (!Character.isWhitespace(after) && delimiters.size() < MAX_INLINE_DEPTH) {
                        delimiters.add(new Delimiter(character, count, start));
                    }
                }
                index += count;
            } else if (character == '&') {
                int end = index + 1;
                while (end < source.length() && end - index <= 16 && source.charAt(end) != ';'
                    && !Character.isWhitespace(source.charAt(end))) { end++; }
                String value = end < source.length() && source.charAt(end) == ';'
                    ? entity(source.substring(index + 1, end)) : "";
                if (!value.isEmpty()) {
                    output.text.append(value);
                    index = end + 1;
                } else {
                    output.text.append(character);
                    index++;
                }
            } else {
                output.text.append(character);
                index++;
            }
        }
        return withoutDelimiters(output, removals);
    }

    private static Document withoutDelimiters(Builder raw, List<int[]> removals) {
        if (removals.isEmpty()) { return raw.document(); }
        boolean[] removed = new boolean[raw.length()];
        for (int[] range : removals) { Arrays.fill(removed, range[0], range[1], true); }
        int[] offsets = new int[raw.length() + 1];
        Builder output = new Builder();
        for (int index = 0; index < raw.length(); index++) {
            offsets[index] = output.length();
            if (!removed[index]) { output.text.append(raw.text.charAt(index)); }
        }
        offsets[raw.length()] = output.length();
        for (Span span : raw.spans) {
            output.spans.add(new Span(span.style, offsets[span.start], offsets[span.end],
                span.level, span.destination));
        }
        return output.document();
    }

    private static int[] codeEnds(String source) {
        int[] ends = new int[source.length()];
        Arrays.fill(ends, -1);
        Map<Integer, Integer> previous = new HashMap<Integer, Integer>();
        for (int index = 0; index < source.length();) {
            if (source.charAt(index) == '`') {
                int count = runLength(source, index);
                Integer last = previous.put(Integer.valueOf(count), Integer.valueOf(index));
                if (last != null) { ends[last.intValue()] = index; }
                index += count;
            } else { index++; }
        }
        return ends;
    }

    private static int[] pairs(String source, char opening, char closing, int[] codeEnds) {
        int[] pairs = new int[source.length()];
        Arrays.fill(pairs, -1);
        int[] stack = new int[MAX_INLINE_DEPTH];
        int depth = 0;
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '\\' && index + 1 < source.length()) { index++; }
            else if (character == '`' && codeEnds[index] >= 0) {
                index = codeEnds[index] + runLength(source, index) - 1;
            } else if (character == opening) {
                if (depth < stack.length) { stack[depth] = index; }
                depth++;
            } else if (character == closing && depth > 0) {
                depth--;
                if (depth < stack.length) { pairs[stack[depth]] = index; }
            }
        }
        return pairs;
    }

    private static String destination(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("<")) {
            int end = trimmed.indexOf('>');
            return end < 0 ? "" : decodeDestination(trimmed.substring(1, end));
        }
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) { end++; }
        return decodeDestination(trimmed.substring(0, end));
    }

    private static String decodeDestination(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < value.length()
                && punctuation(value.charAt(index + 1))) {
                result.append(value.charAt(++index));
                continue;
            }
            if (character == '&') {
                int end = index + 1;
                while (end < value.length() && end - index <= 16 && value.charAt(end) != ';'
                    && !Character.isWhitespace(value.charAt(end))) { end++; }
                String decoded = end < value.length() && value.charAt(end) == ';'
                    ? entity(value.substring(index + 1, end)) : "";
                if (!decoded.isEmpty()) {
                    result.append(decoded);
                    index = end;
                    continue;
                }
            }
            result.append(character);
        }
        return result.toString();
    }

    static boolean safeDestination(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null) { return false; }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                return uri.getHost() != null && !uri.getHost().isEmpty();
            }
            return "mailto".equals(scheme) && !uri.getRawSchemeSpecificPart().isEmpty();
        } catch (URISyntaxException invalid) { return false; }
    }

    private static String entity(String value) {
        switch (value) {
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "nbsp": return "\u00a0";
            default:
                if (value.startsWith("#")) {
                    try {
                        boolean hex = value.startsWith("#x") || value.startsWith("#X");
                        int point = Integer.parseInt(value.substring(hex ? 2 : 1), hex ? 16 : 10);
                        if (point > 0 && Character.isValidCodePoint(point)
                            && (point < 0xd800 || point > 0xdfff)) {
                            return new String(Character.toChars(point));
                        }
                    } catch (IllegalArgumentException invalid) { /* Keep literal source. */ }
                }
                return "";
        }
    }

    private static int table(String[] lines, int index, Builder output) {
        List<List<Document>> rows = new ArrayList<List<Document>>();
        List<String> header = cells(lines[index]);
        int columns = header.size();
        int end = index + 1;
        rows.add(cellDocuments(header));
        while (end + 1 < lines.length && !lines[end + 1].trim().isEmpty()
            && lines[end + 1].indexOf('|') >= 0) {
            List<String> values = cells(lines[end + 1]);
            if (values.size() != columns) { break; }
            rows.add(cellDocuments(values));
            end++;
        }
        int[] widths = new int[columns];
        for (List<Document> row : rows) {
            for (int column = 0; column < columns; column++) {
                // Bound padding for narrow phone screens and very unequal cells.
                widths[column] = Math.max(widths[column], Math.min(24, row.get(column).text.length()));
            }
        }
        int tableStart = output.length();
        for (int row = 0; row < rows.size(); row++) {
            int start = output.length();
            for (int column = 0; column < columns; column++) {
                Document cell = rows.get(row).get(column);
                output.append(cell);
                if (column + 1 < columns) {
                    for (int pad = cell.text.length(); pad < widths[column]; pad++) {
                        output.text.append(' ');
                    }
                    output.text.append(" │ ");
                }
            }
            if (row == 0) { output.span(Style.BOLD, start, output.length()); }
            if (row + 1 < rows.size() || end + 1 < lines.length) { output.text.append('\n'); }
        }
        output.span(Style.TABLE, tableStart, output.length());
        return end;
    }

    private static List<Document> cellDocuments(List<String> cells) {
        List<Document> result = new ArrayList<Document>();
        for (String cell : cells) { result.add(inline(cell, 0)); }
        return result;
    }

    private static boolean tableSeparator(String line) {
        if (line.indexOf('|') < 0) { return false; }
        List<String> cells = cells(line);
        for (String cell : cells) {
            if (!cell.matches(":?-{3,}:?")) { return false; }
        }
        return !cells.isEmpty();
    }

    private static List<String> cells(String line) {
        String value = line.trim();
        int start = value.startsWith("|") ? 1 : 0;
        int end = value.length();
        if (end > start && value.charAt(end - 1) == '|' && value.charAt(end - 2) != '\\') { end--; }
        List<String> result = new ArrayList<String>();
        int[] codeEnds = codeEnds(value);
        int cellStart = start;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < end) { index++; }
            else if (character == '`' && codeEnds[index] >= 0) {
                index = codeEnds[index] + runLength(value, index) - 1;
            } else if (character == '|') {
                result.add(value.substring(cellStart, index).trim());
                cellStart = index + 1;
            }
        }
        result.add(value.substring(cellStart, end).trim());
        return result;
    }

    private static int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') { count++; }
        return count > 0 && count <= 6 && (count == line.length()
            || Character.isWhitespace(line.charAt(count))) ? count : 0;
    }

    private static int setextLevel(String line) {
        if (indentation(line) > 3) { return 0; }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) { return 0; }
        char character = trimmed.charAt(0);
        return (character == '=' || character == '-') && runLength(trimmed, 0) == trimmed.length()
            ? (character == '=' ? 1 : 2) : 0;
    }

    private static boolean isRule(String line) {
        char marker = line.charAt(0);
        if (marker != '*' && marker != '-' && marker != '_') { return false; }
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == marker) { count++; }
            else if (!Character.isWhitespace(character)) { return false; }
        }
        return count >= 3;
    }

    private static int listPrefix(String line) {
        if (line.length() < 2) { return 0; }
        char marker = line.charAt(0);
        if ((marker == '-' || marker == '+' || marker == '*')
            && Character.isWhitespace(line.charAt(1))) { return 2; }
        int count = 0;
        while (count < line.length() && count < 9 && Character.isDigit(line.charAt(count))) { count++; }
        return count > 0 && count + 1 < line.length()
            && (line.charAt(count) == '.' || line.charAt(count) == ')')
            && Character.isWhitespace(line.charAt(count + 1)) ? count + 2 : 0;
    }

    private static int indentation(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') { index++; }
        return index;
    }

    private static int runLength(String value, int index) {
        char marker = value.charAt(index);
        int end = index + 1;
        while (end < value.length() && value.charAt(end) == marker) { end++; }
        return end - index;
    }

    private static boolean punctuation(char character) {
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(character) >= 0;
    }

    private static boolean inlineSyntax(String source) {
        for (int index = 0; index < source.length(); index++) {
            if ("\\`*_[<~&".indexOf(source.charAt(index)) >= 0) { return true; }
        }
        return false;
    }

    private static final class Delimiter {
        final char character;
        final int count;
        final int start;

        Delimiter(char character, int count, int start) {
            this.character = character;
            this.count = count;
            this.start = start;
        }
    }

    private static final class Builder {
        final StringBuilder text = new StringBuilder();
        final List<Span> spans = new ArrayList<Span>();

        int length() { return text.length(); }

        void span(Style style, int start, int end) {
            if (end > start) { spans.add(new Span(style, start, end, 0, "")); }
        }

        void append(Document document) {
            int offset = length();
            text.append(document.text);
            for (Span span : document.spans) {
                if (span.end > span.start) {
                    spans.add(new Span(span.style, span.start + offset, span.end + offset,
                        span.level, span.destination));
                }
            }
        }

        Document document() { return new Document(text.toString(), spans); }
    }

    private ChatMarkdown() { }
}
