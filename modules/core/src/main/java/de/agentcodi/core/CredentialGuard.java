package de.agentcodi.core;

import java.util.Locale;

public final class CredentialGuard {
    private static final int MINIMUM_OPAQUE_VALUE_CHARACTERS = 8;
    private static final int MINIMUM_PASSWORD_OR_CLIENT_SECRET_VALUE_CHARACTERS = 1;
    private static final String[] NAMED_CREDENTIALS = {
        "openai_api_key",
        "openai-api-key",
        "openai api key",
        "api_key",
        "api-key",
        "api key",
        "apikey",
        "access_token",
        "access-token",
        "access token",
        "refresh_token",
        "refresh-token",
        "refresh token",
        "id_token",
        "id-token",
        "id token",
        "client_secret",
        "client-secret",
        "client secret",
        "clientsecret",
        "password",
        "authorization"
    };

    private CredentialGuard() {
    }

    public static boolean containsLikelyCredential(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return containsLikelyCredential(new Characters() {
            @Override
            public int length() {
                return value.length();
            }

            @Override
            public char charAt(int index) {
                return value.charAt(index);
            }
        });
    }

    public static boolean containsLikelyCredential(final CharSequence value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        return containsLikelyCredential(new Characters() {
            @Override
            public int length() {
                return value.length();
            }

            @Override
            public char charAt(int index) {
                return value.charAt(index);
            }
        });
    }

    public static boolean containsLikelyCredential(final char[] value) {
        if (value == null || value.length == 0) {
            return false;
        }
        return containsLikelyCredential(new Characters() {
            @Override
            public int length() {
                return value.length;
            }

            @Override
            public char charAt(int index) {
                return value[index];
            }
        });
    }

    public static boolean containsLikelyCredential(Iterable<?> values) {
        if (values == null) {
            return false;
        }
        int pendingValueCharacters = 0;
        for (Object entry : values) {
            if (!(entry instanceof CharSequence)) {
                pendingValueCharacters = 0;
                continue;
            }
            CharSequence text = (CharSequence) entry;
            if (containsLikelyCredential(text)) {
                return true;
            }
            if (pendingValueCharacters > 0
                && hasArgumentCredentialValue(text, pendingValueCharacters)) {
                return true;
            }
            pendingValueCharacters = standaloneCredentialOptionMinimum(text);
        }
        return false;
    }

    public static boolean isLikelyCredentialName(CharSequence value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        Characters characters = characters(value);
        for (String label : NAMED_CREDENTIALS) {
            if (value.length() == label.length()
                && matches(characters, 0, label, true)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLikelyCredentialFileName(CharSequence value) {
        if (value == null || value.length() == 0 || value.length() > 1024) {
            return false;
        }
        String fileName = value.toString().trim().replace('\\', '/');
        int slash = fileName.lastIndexOf('/');
        if (slash >= 0) {
            fileName = fileName.substring(slash + 1);
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if ("auth.json".equals(lower) || ".env".equals(lower)
            || lower.startsWith(".env.")) {
            return true;
        }
        int extension = lower.indexOf('.');
        String stem = extension < 0 ? lower : lower.substring(0, extension);
        return isLikelyCredentialName(stem)
            || "credential".equals(stem) || "credentials".equals(stem)
            || "secret".equals(stem) || "secrets".equals(stem);
    }

    private static boolean containsLikelyCredential(Characters value) {
        for (int index = 0; index < value.length(); index++) {
            if (hasPrefixedToken(value, index, "sk-")
                || hasPrefixedToken(value, index, "cwx_")
                || hasBearerToken(value, index)
                || hasJsonWebToken(value, index)
                || hasNamedCredentialAssignment(value, index)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrefixedToken(Characters value, int index, String prefix) {
        if ((index > 0 && isTokenCharacter(value.charAt(index - 1)))
            || !matches(value, index, prefix, false)) {
            return false;
        }
        int start = index + prefix.length();
        return countTokenCharacters(value, start) >= MINIMUM_OPAQUE_VALUE_CHARACTERS;
    }

    private static boolean hasBearerToken(Characters value, int index) {
        String label = "bearer";
        if (!matches(value, index, label, true) || !hasWordBoundary(value, index, label.length())) {
            return false;
        }
        int cursor = index + label.length();
        if (cursor >= value.length() || !Character.isWhitespace(value.charAt(cursor))) {
            return false;
        }
        cursor = skipWhitespaceAndQuotes(value, cursor);
        return countOpaqueValueCharacters(value, cursor) >= MINIMUM_OPAQUE_VALUE_CHARACTERS;
    }

    private static boolean hasJsonWebToken(Characters value, int index) {
        if (!matches(value, index, "eyJ", false)
            || (index > 0 && isTokenCharacter(value.charAt(index - 1)))) {
            return false;
        }
        int firstEnd = tokenEnd(value, index);
        if (firstEnd - index < MINIMUM_OPAQUE_VALUE_CHARACTERS
            || firstEnd >= value.length()
            || value.charAt(firstEnd) != '.') {
            return false;
        }
        int secondStart = firstEnd + 1;
        int secondEnd = tokenEnd(value, secondStart);
        if (secondEnd - secondStart < MINIMUM_OPAQUE_VALUE_CHARACTERS) {
            return false;
        }
        if (secondEnd == value.length() || value.charAt(secondEnd) != '.') {
            return true;
        }
        int thirdStart = secondEnd + 1;
        return tokenEnd(value, thirdStart) - thirdStart >= MINIMUM_OPAQUE_VALUE_CHARACTERS;
    }

    private static boolean hasNamedCredentialAssignment(Characters value, int index) {
        for (String label : NAMED_CREDENTIALS) {
            if (!matches(value, index, label, true)
                || !hasWordBoundary(value, index, label.length())) {
                continue;
            }
            int cursor = index + label.length();
            if (isLongCredentialOption(value, index)
                && cursor < value.length()
                && Character.isWhitespace(value.charAt(cursor))) {
                if (hasNamedCredentialValue(value, cursor, label)) {
                    return true;
                }
            }
            while (cursor < value.length()
                && (Character.isWhitespace(value.charAt(cursor))
                    || value.charAt(cursor) == '"'
                    || value.charAt(cursor) == '\'')) {
                cursor++;
            }
            if (cursor >= value.length()
                || (value.charAt(cursor) != ':' && value.charAt(cursor) != '=')) {
                continue;
            }
            if (hasNamedCredentialValue(value, cursor + 1, label)) {
                return true;
            }
        }
        return false;
    }

    private static int standaloneCredentialOptionMinimum(CharSequence value) {
        if (value == null || value.length() < 3
            || value.charAt(0) != '-' || value.charAt(1) != '-') {
            return 0;
        }
        Characters characters = characters(value);
        for (String label : NAMED_CREDENTIALS) {
            if (label.indexOf(' ') < 0 && value.length() == label.length() + 2
                && matches(characters, 2, label, true)) {
                return minimumNamedCredentialValueCharacters(label);
            }
        }
        return 0;
    }

    private static boolean isLongCredentialOption(Characters value, int index) {
        return index >= 2 && value.charAt(index - 2) == '-' && value.charAt(index - 1) == '-'
            && (index == 2 || !isTokenCharacter(value.charAt(index - 3)));
    }

    private static int minimumNamedCredentialValueCharacters(String label) {
        return "password".equals(label) || label.startsWith("client")
            ? MINIMUM_PASSWORD_OR_CLIENT_SECRET_VALUE_CHARACTERS
            : MINIMUM_OPAQUE_VALUE_CHARACTERS;
    }

    private static boolean hasNamedCredentialValue(
        Characters value,
        int index,
        String label
    ) {
        int minimumCharacters = minimumNamedCredentialValueCharacters(label);
        if (minimumCharacters == MINIMUM_PASSWORD_OR_CLIENT_SECRET_VALUE_CHARACTERS) {
            int cursor = index;
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
                cursor++;
            }
            return cursor < value.length();
        }
        int cursor = skipWhitespaceAndQuotes(value, index);
        return countOpaqueValueCharacters(value, cursor) >= minimumCharacters;
    }

    private static boolean hasArgumentCredentialValue(
        CharSequence value,
        int minimumCharacters
    ) {
        return minimumCharacters == MINIMUM_PASSWORD_OR_CLIENT_SECRET_VALUE_CHARACTERS
            ? value.length() > 0
            : countOpaqueValueCharacters(value, 0) >= minimumCharacters;
    }

    private static Characters characters(final CharSequence value) {
        return new Characters() {
            @Override
            public int length() {
                return value.length();
            }

            @Override
            public char charAt(int index) {
                return value.charAt(index);
            }
        };
    }

    private static int countOpaqueValueCharacters(CharSequence value, int index) {
        Characters characters = characters(value);
        return countOpaqueValueCharacters(
            characters,
            skipWhitespaceAndQuotes(characters, index)
        );
    }

    private static int skipWhitespaceAndQuotes(Characters value, int index) {
        int cursor = index;
        while (cursor < value.length()) {
            char character = value.charAt(cursor);
            if (!Character.isWhitespace(character) && character != '"' && character != '\'') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static int countTokenCharacters(Characters value, int index) {
        return tokenEnd(value, index) - index;
    }

    private static int tokenEnd(Characters value, int index) {
        int cursor = index;
        while (cursor < value.length() && isTokenCharacter(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int countOpaqueValueCharacters(Characters value, int index) {
        int cursor = index;
        while (cursor < value.length()) {
            char character = value.charAt(cursor);
            if (Character.isWhitespace(character)
                || character == '"'
                || character == '\''
                || character == ','
                || character == ';'
                || character == '&'
                || character == '}') {
                break;
            }
            cursor++;
        }
        return cursor - index;
    }

    private static boolean isTokenCharacter(char value) {
        return (value >= 'a' && value <= 'z')
            || (value >= 'A' && value <= 'Z')
            || (value >= '0' && value <= '9')
            || value == '_'
            || value == '-';
    }

    private static boolean matches(
        Characters value,
        int index,
        String expected,
        boolean ignoreCase
    ) {
        if (index < 0 || index + expected.length() > value.length()) {
            return false;
        }
        for (int offset = 0; offset < expected.length(); offset++) {
            char actual = value.charAt(index + offset);
            char wanted = expected.charAt(offset);
            if (ignoreCase) {
                actual = Character.toLowerCase(actual);
                wanted = Character.toLowerCase(wanted);
            }
            if (actual != wanted) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasWordBoundary(Characters value, int index, int length) {
        boolean startBoundary = index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1));
        int end = index + length;
        boolean endBoundary = end >= value.length() || !Character.isLetterOrDigit(value.charAt(end));
        return startBoundary && endBoundary;
    }

    private interface Characters {
        int length();

        char charAt(int index);
    }
}
