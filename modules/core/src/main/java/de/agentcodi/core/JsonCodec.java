package de.agentcodi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonCodec {
    private static final int MAX_INPUT_CHARACTERS = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_CONTAINER_ENTRIES = 10_000;
    private static final int MAX_STRING_CHARACTERS = 16 * 1024 * 1024;

    private JsonCodec() {
    }

    public static Object parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("JSON must not be null");
        }
        if (json.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("JSON exceeds the input limit");
        }
        return new Parser(json).parse();
    }

    public static Map<String, Object> parseObject(String json) {
        return requireObject(parse(json), "JSON root");
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        appendValue(output, value, 0);
        if (output.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("JSON exceeds the output limit");
        }
        return output.toString();
    }

    public static Map<String, Object> object(Object... entries) {
        if (entries == null || entries.length % 2 != 0) {
            throw new IllegalArgumentException("Object entries must be key/value pairs");
        }
        Map<String, Object> object = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            Object key = entries[index];
            if (!(key instanceof String) || ((String) key).isEmpty()) {
                throw new IllegalArgumentException("JSON object keys must be non-empty strings");
            }
            object.put((String) key, entries[index + 1]);
        }
        return object;
    }

    public static List<Object> array(Object... values) {
        List<Object> array = new ArrayList<Object>();
        if (values != null) {
            Collections.addAll(array, values);
        }
        return array;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireObject(Object value, String field) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return (Map<String, Object>) value;
    }

    public static Map<String, Object> optionalObject(Object value) {
        if (value == null) {
            return null;
        }
        return requireObject(value, "value");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> requireArray(Object value, String field) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return (List<Object>) value;
    }

    public static List<Object> optionalArray(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        return requireArray(value, "value");
    }

    public static String requireString(Object value, String field) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            throw new IllegalArgumentException(field + " must be a non-empty string");
        }
        return (String) value;
    }

    public static String optionalString(Object value) {
        return value instanceof String ? (String) value : "";
    }

    public static boolean booleanValue(Object value, boolean fallback) {
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    public static long longValue(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static void appendValue(StringBuilder output, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds the limit");
        }
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            appendString(output, (String) value);
        } else if (value instanceof Boolean) {
            output.append(value.toString());
        } else if (value instanceof Number) {
            appendNumber(output, (Number) value);
        } else if (value instanceof Map) {
            appendObject(output, (Map<?, ?>) value, depth + 1);
        } else if (value instanceof List) {
            appendArray(output, (List<?>) value, depth + 1);
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void appendObject(StringBuilder output, Map<?, ?> value, int depth) {
        if (value.size() > MAX_CONTAINER_ENTRIES) {
            throw new IllegalArgumentException("JSON object exceeds the entry limit");
        }
        output.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            if (!first) {
                output.append(',');
            }
            first = false;
            appendString(output, (String) entry.getKey());
            output.append(':');
            appendValue(output, entry.getValue(), depth);
            ensureOutputLimit(output);
        }
        output.append('}');
    }

    private static void appendArray(StringBuilder output, List<?> value, int depth) {
        if (value.size() > MAX_CONTAINER_ENTRIES) {
            throw new IllegalArgumentException("JSON array exceeds the entry limit");
        }
        output.append('[');
        for (int index = 0; index < value.size(); index++) {
            if (index != 0) {
                output.append(',');
            }
            appendValue(output, value.get(index), depth);
            ensureOutputLimit(output);
        }
        output.append(']');
    }

    private static void appendNumber(StringBuilder output, Number value) {
        if (value instanceof Double || value instanceof Float) {
            double number = value.doubleValue();
            if (Double.isInfinite(number) || Double.isNaN(number)) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
        }
        output.append(value.toString());
    }

    private static void appendString(StringBuilder output, String value) {
        if (value.length() > MAX_STRING_CHARACTERS) {
            throw new IllegalArgumentException("JSON string exceeds the limit");
        }
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        appendUnicodeEscape(output, character);
                    } else if (Character.isHighSurrogate(character)) {
                        if (index + 1 >= value.length()
                            || !Character.isLowSurrogate(value.charAt(index + 1))) {
                            throw new IllegalArgumentException("Unpaired high surrogate in JSON string");
                        }
                        output.append(character).append(value.charAt(++index));
                    } else if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("Unpaired low surrogate in JSON string");
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static void appendUnicodeEscape(StringBuilder output, char character) {
        final char[] hex = "0123456789abcdef".toCharArray();
        output.append("\\u");
        output.append(hex[(character >>> 12) & 0x0f]);
        output.append(hex[(character >>> 8) & 0x0f]);
        output.append(hex[(character >>> 4) & 0x0f]);
        output.append(hex[character & 0x0f]);
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object parse() {
            skipWhitespace();
            Object value = parseValue(0);
            skipWhitespace();
            if (index != input.length()) {
                fail("Trailing JSON content");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                fail("JSON nesting exceeds the limit");
            }
            if (index >= input.length()) {
                fail("Unexpected end of JSON");
            }
            char character = input.charAt(index);
            switch (character) {
                case '{':
                    return parseObject(depth + 1);
                case '[':
                    return parseArray(depth + 1);
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (character == '-' || isDigit(character)) {
                        return parseNumber();
                    }
                    fail("Unexpected JSON token");
                    return null;
            }
        }

        private Map<String, Object> parseObject(int depth) {
            index++;
            Map<String, Object> object = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                if (object.size() >= MAX_CONTAINER_ENTRIES) {
                    fail("JSON object exceeds the entry limit");
                }
                if (index >= input.length() || input.charAt(index) != '"') {
                    fail("JSON object key must be a string");
                }
                String key = parseString();
                if (object.containsKey(key)) {
                    fail("Duplicate JSON object key");
                }
                skipWhitespace();
                require(':');
                skipWhitespace();
                object.put(key, parseValue(depth));
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                require(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArray(int depth) {
            index++;
            List<Object> array = new ArrayList<Object>();
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            while (true) {
                if (array.size() >= MAX_CONTAINER_ENTRIES) {
                    fail("JSON array exceeds the entry limit");
                }
                array.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                require(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    String result = value.toString();
                    validateSurrogates(result);
                    return result;
                }
                if (character == '\\') {
                    if (index >= input.length()) {
                        fail("Incomplete JSON escape");
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            value.append(escaped);
                            break;
                        case 'b':
                            value.append('\b');
                            break;
                        case 'f':
                            value.append('\f');
                            break;
                        case 'n':
                            value.append('\n');
                            break;
                        case 'r':
                            value.append('\r');
                            break;
                        case 't':
                            value.append('\t');
                            break;
                        case 'u':
                            value.append(parseUnicodeEscape());
                            break;
                        default:
                            fail("Invalid JSON escape");
                    }
                } else {
                    if (character < 0x20) {
                        fail("Control character in JSON string");
                    }
                    value.append(character);
                }
                if (value.length() > MAX_STRING_CHARACTERS) {
                    fail("JSON string exceeds the limit");
                }
            }
            fail("Unterminated JSON string");
            return "";
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                fail("Incomplete Unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) {
                    fail("Invalid Unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Number parseNumber() {
            int start = index;
            consume('-');
            if (consume('0')) {
                if (index < input.length() && isDigit(input.charAt(index))) {
                    fail("Leading zero in JSON number");
                }
            } else {
                requireDigits();
            }
            boolean decimal = false;
            if (consume('.')) {
                decimal = true;
                requireDigits();
            }
            if (index < input.length()
                && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (index < input.length()
                    && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                requireDigits();
            }
            String token = input.substring(start, index);
            try {
                if (!decimal) {
                    return Long.valueOf(token);
                }
                double value = Double.parseDouble(token);
                if (Double.isInfinite(value) || Double.isNaN(value)) {
                    fail("Non-finite JSON number");
                }
                return Double.valueOf(value);
            } catch (NumberFormatException error) {
                fail("Invalid JSON number");
                return Long.valueOf(0L);
            }
        }

        private void requireDigits() {
            int start = index;
            while (index < input.length() && isDigit(input.charAt(index))) {
                index++;
            }
            if (index == start) {
                fail("Expected digit in JSON number");
            }
        }

        private void expectLiteral(String literal) {
            if (!input.regionMatches(index, literal, 0, literal.length())) {
                fail("Invalid JSON literal");
            }
            index += literal.length();
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char character = input.charAt(index);
                if (character != ' ' && character != '\t'
                    && character != '\n' && character != '\r') {
                    return;
                }
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                fail("Expected '" + expected + "'");
            }
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + " at character " + index);
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }
    }

    private static void ensureOutputLimit(StringBuilder output) {
        if (output.length() > MAX_INPUT_CHARACTERS) {
            throw new IllegalArgumentException("JSON exceeds the output limit");
        }
    }

    private static void validateSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Unpaired high surrogate in JSON string");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Unpaired low surrogate in JSON string");
            }
        }
    }
}
