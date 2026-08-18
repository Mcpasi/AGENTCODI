package de.agentcodi.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CodexMcpConfigurationRequestValidator {
    private static final int MAX_EDITS = 16;
    private static final int MAX_SERVER_NAME_CHARACTERS = 64;
    private static final int MAX_COMMAND_CHARACTERS = 1024;
    private static final int MAX_URL_CHARACTERS = 2048;
    private static final int MAX_LIST_ENTRIES = 128;
    private static final int MAX_LIST_VALUE_CHARACTERS = 2048;
    private static final long MAX_TIMEOUT_SECONDS = 3600L;
    private static final String PREFIX = "mcp_servers.";

    private CodexMcpConfigurationRequestValidator() {
    }

    static boolean isValidWrite(Map<String, Object> parameters) {
        if (parameters == null || parameters.size() < 2 || parameters.size() > 3
            || !parameters.containsKey("edits")
            || !Boolean.FALSE.equals(parameters.get("reloadUserConfig"))
            || parameters.containsKey("filePath")) {
            return false;
        }
        for (String key : parameters.keySet()) {
            if (!"edits".equals(key) && !"expectedVersion".equals(key)
                && !"reloadUserConfig".equals(key)) {
                return false;
            }
        }
        Object expectedVersion = parameters.get("expectedVersion");
        if (expectedVersion != null && !isConfigurationVersion(expectedVersion)) {
            return false;
        }
        if (!(parameters.get("edits") instanceof List)) {
            return false;
        }
        List<?> edits = (List<?>) parameters.get("edits");
        if (edits.isEmpty() || edits.size() > MAX_EDITS) {
            return false;
        }
        Set<String> keyPaths = new HashSet<String>();
        Set<String> promptServers = new HashSet<String>();
        Set<String> clearedToolApprovalOverrides = new HashSet<String>();
        Set<String> enabledServers = new HashSet<String>();
        for (Object value : edits) {
            if (!(value instanceof Map) || !isValidEdit((Map<?, ?>) value, keyPaths)) {
                return false;
            }
            Map<?, ?> edit = (Map<?, ?>) value;
            String keyPath = (String) edit.get("keyPath");
            String remainder = keyPath.substring(PREFIX.length());
            int separator = remainder.indexOf('.');
            if (separator >= 0) {
                String serverName = remainder.substring(0, separator);
                String field = remainder.substring(separator + 1);
                if ("default_tools_approval_mode".equals(field)
                    && "prompt".equals(edit.get("value"))) {
                    promptServers.add(serverName);
                } else if ("tools".equals(field) && edit.get("value") == null) {
                    clearedToolApprovalOverrides.add(serverName);
                } else if ("enabled".equals(field)
                    && Boolean.TRUE.equals(edit.get("value"))) {
                    enabledServers.add(serverName);
                }
            }
        }
        return promptServers.containsAll(enabledServers)
            && clearedToolApprovalOverrides.containsAll(enabledServers);
    }

    private static boolean isValidEdit(Map<?, ?> edit, Set<String> keyPaths) {
        if (edit.size() != 3 || !"replace".equals(edit.get("mergeStrategy"))
            || !(edit.get("keyPath") instanceof String)
            || !edit.containsKey("value")) {
            return false;
        }
        for (Object key : edit.keySet()) {
            if (!(key instanceof String)
                || (!"keyPath".equals(key) && !"mergeStrategy".equals(key)
                    && !"value".equals(key))) {
                return false;
            }
        }
        String keyPath = (String) edit.get("keyPath");
        if (!keyPaths.add(keyPath) || !keyPath.startsWith(PREFIX)) {
            return false;
        }
        String remainder = keyPath.substring(PREFIX.length());
        int separator = remainder.indexOf('.');
        String serverName = separator < 0 ? remainder : remainder.substring(0, separator);
        if (!isSafeServerName(serverName)) {
            return false;
        }
        if (separator < 0) {
            Object server = edit.get("value");
            return server == null || (server instanceof Map && isValidServer((Map<?, ?>) server));
        }
        String field = remainder.substring(separator + 1);
        if (field.isEmpty() || field.indexOf('.') >= 0) {
            return false;
        }
        if ("tools".equals(field)) {
            return edit.get("value") == null;
        }
        return isValidField(field, edit.get("value"));
    }

    private static boolean isValidServer(Map<?, ?> server) {
        if (server.isEmpty() || server.size() > 10) {
            return false;
        }
        for (Object key : server.keySet()) {
            if (!(key instanceof String) || !isSupportedField((String) key)) {
                return false;
            }
        }
        boolean hasCommand = server.get("command") instanceof String;
        boolean hasUrl = server.get("url") instanceof String;
        if (hasCommand == hasUrl || !Boolean.FALSE.equals(server.get("enabled"))
            || !Boolean.FALSE.equals(server.get("required"))
            || !"prompt".equals(server.get("default_tools_approval_mode"))) {
            return false;
        }
        for (Map.Entry<?, ?> entry : server.entrySet()) {
            if (!isValidField((String) entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return !hasUrl || !server.containsKey("args");
    }

    private static boolean isValidField(String field, Object value) {
        if (!isSupportedField(field)) {
            return false;
        }
        if ("command".equals(field)) {
            return isSafeText(value, MAX_COMMAND_CHARACTERS);
        }
        if ("url".equals(field)) {
            return isSafeHttpsUrl(value);
        }
        if ("args".equals(field)) {
            return value == null || isSafeStringList(value, 64, MAX_LIST_VALUE_CHARACTERS);
        }
        if ("enabled".equals(field) || "required".equals(field)) {
            return value instanceof Boolean;
        }
        if ("startup_timeout_sec".equals(field) || "tool_timeout_sec".equals(field)) {
            return isBoundedInteger(value, 1L, MAX_TIMEOUT_SECONDS);
        }
        if ("enabled_tools".equals(field) || "disabled_tools".equals(field)) {
            return value == null
                || isSafeStringList(value, MAX_LIST_ENTRIES, 160);
        }
        if ("default_tools_approval_mode".equals(field)) {
            return "prompt".equals(value);
        }
        return false;
    }

    private static boolean isSupportedField(String field) {
        return "command".equals(field) || "url".equals(field) || "args".equals(field)
            || "enabled".equals(field) || "required".equals(field)
            || "startup_timeout_sec".equals(field) || "tool_timeout_sec".equals(field)
            || "enabled_tools".equals(field) || "disabled_tools".equals(field)
            || "default_tools_approval_mode".equals(field);
    }

    static boolean isSafeServerName(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_SERVER_NAME_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '_' && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isConfigurationVersion(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String version = (String) value;
        if (version.length() != 71 || !version.startsWith("sha256:")) {
            return false;
        }
        for (int index = 7; index < version.length(); index++) {
            char character = version.charAt(index);
            if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeHttpsUrl(Object value) {
        if (!isSafeText(value, MAX_URL_CHARACTERS)) {
            return false;
        }
        String text = (String) value;
        if (CredentialGuard.containsLikelyCredential(text)) {
            return false;
        }
        try {
            URI uri = new URI(text);
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static boolean isSafeStringList(Object value, int maximumEntries, int maximumLength) {
        if (!(value instanceof List)) {
            return false;
        }
        List<?> values = (List<?>) value;
        if (values.size() > maximumEntries) {
            return false;
        }
        for (Object entry : values) {
            if (!isSafeText(entry, maximumLength)) {
                return false;
            }
        }
        return !CredentialGuard.containsLikelyCredential(values);
    }

    private static boolean isSafeText(Object value, int maximumLength) {
        if (!(value instanceof String)) {
            return false;
        }
        String text = (String) value;
        if (text.isEmpty() || text.length() > maximumLength || !text.equals(text.trim())
            || CredentialGuard.containsLikelyCredential(text)) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (Character.isISOControl(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBoundedInteger(Object value, long minimum, long maximum) {
        if (!(value instanceof Number)) {
            return false;
        }
        Number number = (Number) value;
        double floating = number.doubleValue();
        long integer = number.longValue();
        return !Double.isInfinite(floating) && !Double.isNaN(floating)
            && floating == (double) integer && integer >= minimum && integer <= maximum;
    }
}
