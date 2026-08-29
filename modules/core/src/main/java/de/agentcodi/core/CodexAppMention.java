package de.agentcodi.core;

/** Bounded app-server mention for one already callable hosted connector. */
public final class CodexAppMention {
    public static final int MAXIMUM_MENTIONS = 4;
    public static final int MAXIMUM_ID_CHARACTERS = 256;
    public static final int MAXIMUM_NAME_CHARACTERS = 160;

    private final String id;
    private final String name;

    private CodexAppMention(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static CodexAppMention create(String id, String name) {
        if (!isSafeId(id) || !isSafeName(name)) {
            throw new IllegalArgumentException("Codex app mention is invalid");
        }
        return new CodexAppMention(id, name);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return "app://" + id;
    }

    private static boolean isSafeId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAXIMUM_ID_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_' && character != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeName(String value) {
        if (value == null || value.trim().isEmpty()
            || value.length() > MAXIMUM_NAME_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
