package de.agentcodi.core;

public final class ToolchainCommand {
    private ToolchainCommand() {
    }

    public static boolean requestsNodeInstallation(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String normalized = normalizeWhitespace(command);
        return containsCommand(normalized, "agentcodi-toolchain install node")
            || containsDirectBridgeCommand(normalized);
    }

    private static String normalizeWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean whitespace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)) {
                whitespace = normalized.length() > 0;
            } else {
                if (whitespace) {
                    normalized.append(' ');
                    whitespace = false;
                }
                normalized.append(character);
            }
        }
        return normalized.toString();
    }

    private static boolean containsCommand(String value, String expected) {
        int offset = value.indexOf(expected);
        while (offset >= 0) {
            int end = offset + expected.length();
            boolean startBoundary = isCommandStart(value, offset);
            boolean endBoundary = isCommandEnd(value, end);
            if (startBoundary && endBoundary) {
                return true;
            }
            offset = value.indexOf(expected, offset + 1);
        }
        return false;
    }

    private static boolean containsDirectBridgeCommand(String value) {
        String expected = "--toolchain install node";
        int offset = value.indexOf(expected);
        while (offset >= 0) {
            int executableEnd = offset;
            while (executableEnd > 0
                && Character.isWhitespace(value.charAt(executableEnd - 1))) {
                executableEnd--;
            }
            int executableStart = executableEnd;
            while (executableStart > 0
                && !Character.isWhitespace(value.charAt(executableStart - 1))) {
                executableStart--;
            }
            String executable = value.substring(executableStart, executableEnd);
            boolean packagedBridge = executable.endsWith(
                "/" + BuildIdentity.TERMINAL_SHELL_LIBRARY
            );
            if (packagedBridge
                && isCommandStart(value, executableStart)
                && isCommandEnd(value, offset + expected.length())) {
                return true;
            }
            offset = value.indexOf(expected, offset + 1);
        }
        return false;
    }

    private static boolean isCommandStart(String value, int offset) {
        int cursor = offset - 1;
        while (cursor >= 0 && Character.isWhitespace(value.charAt(cursor))) {
            cursor--;
        }
        return cursor < 0 || isCommandSeparator(value.charAt(cursor))
            || value.charAt(cursor) == '(';
    }

    private static boolean isCommandEnd(String value, int offset) {
        int cursor = offset;
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
            cursor++;
        }
        if (cursor == value.length()) {
            return true;
        }
        char next = value.charAt(cursor);
        return isCommandSeparator(next)
            || next == ')'
            || next == '<'
            || next == '>'
            || next == '#';
    }

    private static boolean isCommandSeparator(char value) {
        return value == ';'
            || value == '&'
            || value == '|';
    }
}
