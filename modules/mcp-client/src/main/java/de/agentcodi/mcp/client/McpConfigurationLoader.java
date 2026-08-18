package de.agentcodi.mcp.client;

import de.agentcodi.core.CodexMcpConfigurationRpc;
import de.agentcodi.core.CredentialGuard;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.mcp.McpConfigurationNotice;
import de.agentcodi.mcp.McpConfigurationPhase;
import de.agentcodi.mcp.McpConfigurationSnapshot;
import de.agentcodi.mcp.McpServerConfiguration;
import de.agentcodi.mcp.McpServerDraft;
import de.agentcodi.mcp.McpServerOrigin;
import de.agentcodi.mcp.McpTransport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Decodes only the bounded, secret-free MCP subset of config/read. */
public final class McpConfigurationLoader {
    static final int MAX_SERVERS = 64;
    static final int MAX_ORIGINS = 4096;
    static final int MAX_PROJECTED_CHARACTERS = 64 * 1024;
    static final long REQUEST_TIMEOUT_MS = 20_000L;

    private final CodexMcpConfigurationRpc rpc;

    public McpConfigurationLoader(CodexMcpConfigurationRpc rpc) {
        if (rpc == null) {
            throw new IllegalArgumentException("MCP configuration RPC gateway is required");
        }
        this.rpc = rpc;
    }

    public McpConfigurationSnapshot load(long revision, McpConfigurationNotice notice)
        throws Exception {
        if (revision < 0L || notice == null) {
            throw new IllegalArgumentException("MCP configuration load state is invalid");
        }
        Map<String, Object> response = rpc.readMcpConfiguration(REQUEST_TIMEOUT_MS);
        Map<String, Object> config = JsonCodec.requireObject(
            response.get("config"),
            "effective Codex configuration"
        );
        Map<String, Object> origins = optionalObject(response.get("origins"), "config origins");
        if (origins.size() > MAX_ORIGINS) {
            throw new IllegalArgumentException("MCP configuration origins exceed the limit");
        }
        Map<String, Object> serverValues = optionalObject(
            config.get("mcp_servers"),
            "MCP server configuration"
        );
        if (serverValues.size() > MAX_SERVERS) {
            throw new IllegalArgumentException("MCP server configuration exceeds the limit");
        }

        ProjectionBudget budget = new ProjectionBudget();
        OriginIndex originIndex = new OriginIndex(origins);
        List<McpServerConfiguration> servers = new ArrayList<McpServerConfiguration>();
        for (Map.Entry<String, Object> entry : serverValues.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                throw new IllegalArgumentException("MCP server entry must be an object");
            }
            String name = budget.project(entry.getKey(), 160, true);
            OriginSummary origin = originIndex.forServer(entry.getKey());
            servers.add(decodeServer(
                name,
                JsonCodec.requireObject(entry.getValue(), "MCP server entry"),
                origin,
                budget
            ));
        }
        Collections.sort(servers, new Comparator<McpServerConfiguration>() {
            @Override
            public int compare(McpServerConfiguration left, McpServerConfiguration right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        return new McpConfigurationSnapshot(
            revision,
            McpConfigurationPhase.READY,
            notice,
            servers,
            originIndex.getBaseUserVersion()
        );
    }

    private static McpServerConfiguration decodeServer(
        String name,
        Map<String, Object> server,
        OriginSummary origin,
        ProjectionBudget budget
    ) {
        boolean hasCommand = server.containsKey("command");
        boolean hasUrl = server.containsKey("url");
        boolean advanced = false;
        boolean sensitive = false;

        String command = "";
        if (hasCommand) {
            command = safeConfiguredText(server.get("command"), 1024, budget);
            if (command.isEmpty()) {
                sensitive = true;
            }
        }
        String url = "";
        if (hasUrl) {
            url = safeConfiguredUrl(server.get("url"), budget);
            if (url.isEmpty()) {
                sensitive = true;
            }
        }

        ListResult arguments = safeStringList(server.get("args"), 64, 2048, budget);
        ListResult enabledTools = safeStringList(server.get("enabled_tools"), 128, 160, budget);
        ListResult disabledTools = safeStringList(
            server.get("disabled_tools"),
            128,
            160,
            budget
        );
        advanced |= arguments.invalid || enabledTools.invalid || disabledTools.invalid;
        sensitive |= arguments.sensitive || enabledTools.sensitive || disabledTools.sensitive;

        Object rawEnabled = server.get("enabled");
        Object rawRequired = server.get("required");
        boolean enabled = optionalBoolean(rawEnabled, true);
        boolean required = optionalBoolean(rawRequired, false);
        advanced |= rawEnabled != null && !(rawEnabled instanceof Boolean);
        advanced |= rawRequired != null && !(rawRequired instanceof Boolean);
        int startupTimeout = boundedInteger(
            server.get("startup_timeout_sec"),
            McpServerDraft.DEFAULT_STARTUP_TIMEOUT_SECONDS
        );
        int toolTimeout = boundedInteger(
            server.get("tool_timeout_sec"),
            McpServerDraft.DEFAULT_TOOL_TIMEOUT_SECONDS
        );
        if (startupTimeout < 0) {
            startupTimeout = McpServerDraft.DEFAULT_STARTUP_TIMEOUT_SECONDS;
            advanced = true;
        }
        if (toolTimeout < 0) {
            toolTimeout = McpServerDraft.DEFAULT_TOOL_TIMEOUT_SECONDS;
            advanced = true;
        }

        String approvalMode = "";
        Object rawApprovalMode = server.get("default_tools_approval_mode");
        if (rawApprovalMode != null) {
            if (rawApprovalMode instanceof String && isApprovalMode((String) rawApprovalMode)) {
                approvalMode = (String) rawApprovalMode;
            } else {
                advanced = true;
            }
        }
        boolean toolApprovalOverrides = hasToolApprovalOverrides(server.get("tools"));

        for (String key : server.keySet()) {
            if (!isProjectedField(key)) {
                advanced = true;
                if ("env".equals(key) || "http_headers".equals(key)
                    || "bearer_token_env_var".equals(key)
                    || CredentialGuard.isLikelyCredentialName(key)) {
                    sensitive = true;
                }
            }
        }

        McpTransport transport;
        if (hasCommand && !hasUrl) {
            transport = McpTransport.STDIO;
        } else if (hasUrl && !hasCommand) {
            transport = McpTransport.STREAMABLE_HTTP;
        } else {
            transport = McpTransport.UNSUPPORTED;
            advanced = true;
        }
        boolean editable = origin.origin == McpServerOrigin.USER
            && !origin.version.isEmpty()
            && McpServerDraft.isSafeName(name)
            && transport != McpTransport.UNSUPPORTED
            && !sensitive
            && ((transport == McpTransport.STDIO && !command.isEmpty())
                || (transport == McpTransport.STREAMABLE_HTTP && !url.isEmpty()));

        return new McpServerConfiguration(
            name,
            transport,
            command,
            arguments.values,
            url,
            enabled,
            required,
            startupTimeout,
            toolTimeout,
            approvalMode,
            enabledTools.values,
            disabledTools.values,
            origin.origin,
            editable,
            toolApprovalOverrides,
            advanced,
            sensitive
        );
    }

    private static boolean isProjectedField(String key) {
        return "command".equals(key) || "url".equals(key) || "args".equals(key)
            || "enabled".equals(key) || "required".equals(key)
            || "startup_timeout_sec".equals(key) || "tool_timeout_sec".equals(key)
            || "enabled_tools".equals(key) || "disabled_tools".equals(key)
            || "default_tools_approval_mode".equals(key) || "tools".equals(key);
    }

    private static boolean hasToolApprovalOverrides(Object value) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Map)) {
            return true;
        }
        return !((Map<?, ?>) value).isEmpty();
    }

    private static String safeConfiguredText(
        Object value,
        int maximumCharacters,
        ProjectionBudget budget
    ) {
        if (!(value instanceof String)) {
            return "";
        }
        String text = (String) value;
        if (!text.equals(text.trim()) || text.isEmpty() || text.length() > maximumCharacters
            || CredentialGuard.containsLikelyCredential(text) || hasControl(text)) {
            return "";
        }
        String projected = budget.project(text, maximumCharacters, true);
        return projected.equals(text) ? projected : "";
    }

    private static String safeConfiguredUrl(Object value, ProjectionBudget budget) {
        String text = safeConfiguredText(value, 2048, budget);
        if (text.isEmpty()) {
            return "";
        }
        try {
            URI uri = new URI(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return "";
            }
            return text;
        } catch (URISyntaxException error) {
            return "";
        }
    }

    private static ListResult safeStringList(
        Object value,
        int maximumEntries,
        int maximumCharacters,
        ProjectionBudget budget
    ) {
        if (value == null) {
            return new ListResult(Collections.<String>emptyList(), false, false);
        }
        if (!(value instanceof List) || ((List<?>) value).size() > maximumEntries) {
            return new ListResult(Collections.<String>emptyList(), true, false);
        }
        List<?> values = (List<?>) value;
        if (CredentialGuard.containsLikelyCredential(values)) {
            return new ListResult(Collections.<String>emptyList(), false, true);
        }
        List<String> projected = new ArrayList<String>();
        for (Object entry : values) {
            if (!(entry instanceof String)) {
                return new ListResult(Collections.<String>emptyList(), true, false);
            }
            String text = (String) entry;
            if (!text.equals(text.trim()) || text.isEmpty()
                || text.length() > maximumCharacters || hasControl(text)) {
                return new ListResult(Collections.<String>emptyList(), true, false);
            }
            String safe = budget.project(text, maximumCharacters, true);
            if (!safe.equals(text)) {
                return new ListResult(Collections.<String>emptyList(), false, true);
            }
            projected.add(safe);
        }
        return new ListResult(projected, false, false);
    }

    private static boolean optionalBoolean(Object value, boolean fallback) {
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    private static int boundedInteger(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number)) {
            return -1;
        }
        Number number = (Number) value;
        double floating = number.doubleValue();
        long integer = number.longValue();
        if (Double.isInfinite(floating) || Double.isNaN(floating)
            || floating != (double) integer || integer < 1L || integer > 3600L) {
            return -1;
        }
        return (int) integer;
    }

    private static boolean isApprovalMode(String value) {
        return "auto".equals(value) || "prompt".equals(value)
            || "writes".equals(value) || "approve".equals(value);
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> optionalObject(Object value, String field) {
        return value == null
            ? Collections.<String, Object>emptyMap()
            : JsonCodec.requireObject(value, field);
    }

    private static boolean isConfigurationVersion(String value) {
        if (value == null || value.length() != 71 || !value.startsWith("sha256:")) {
            return false;
        }
        for (int index = 7; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static final class ListResult {
        private final List<String> values;
        private final boolean invalid;
        private final boolean sensitive;

        private ListResult(List<String> values, boolean invalid, boolean sensitive) {
            this.values = values;
            this.invalid = invalid;
            this.sensitive = sensitive;
        }
    }

    private static final class OriginSummary {
        private final McpServerOrigin origin;
        private final String version;

        private OriginSummary(McpServerOrigin origin, String version) {
            this.origin = origin;
            this.version = version;
        }
    }

    private static final class OriginIndex {
        private final Map<String, Object> origins;
        private final String baseUserVersion;

        private OriginIndex(Map<String, Object> origins) {
            this.origins = origins;
            String discoveredVersion = "";
            boolean conflictingVersion = false;
            for (Object value : origins.values()) {
                OriginMetadata metadata = OriginMetadata.decode(value);
                if (metadata.baseUser && !metadata.version.isEmpty()) {
                    if (discoveredVersion.isEmpty()) {
                        discoveredVersion = metadata.version;
                    } else if (!discoveredVersion.equals(metadata.version)) {
                        conflictingVersion = true;
                    }
                }
            }
            baseUserVersion = conflictingVersion ? "" : discoveredVersion;
        }

        private String getBaseUserVersion() {
            return baseUserVersion;
        }

        private OriginSummary forServer(String serverName) {
            String prefix = "mcp_servers." + serverName;
            boolean user = false;
            boolean project = false;
            boolean managed = false;
            boolean session = false;
            boolean unknown = false;
            String version = "";
            for (Map.Entry<String, Object> entry : origins.entrySet()) {
                String key = entry.getKey();
                if (!key.equals(prefix) && !key.startsWith(prefix + ".")) {
                    continue;
                }
                OriginMetadata metadata = OriginMetadata.decode(entry.getValue());
                if (metadata.baseUser) {
                    user = true;
                    if (version.isEmpty()) {
                        version = metadata.version;
                    } else if (!version.equals(metadata.version)) {
                        unknown = true;
                    }
                } else if ("project".equals(metadata.type)) {
                    project = true;
                } else if ("sessionFlags".equals(metadata.type)) {
                    session = true;
                } else if (isManagedType(metadata.type)) {
                    managed = true;
                } else {
                    unknown = true;
                }
            }
            int categories = (user ? 1 : 0) + (project ? 1 : 0) + (managed ? 1 : 0)
                + (session ? 1 : 0) + (unknown ? 1 : 0);
            McpServerOrigin origin;
            if (categories != 1) {
                origin = categories == 0 ? McpServerOrigin.UNKNOWN : McpServerOrigin.MIXED;
            } else if (user) {
                origin = McpServerOrigin.USER;
            } else if (project) {
                origin = McpServerOrigin.PROJECT;
            } else if (managed) {
                origin = McpServerOrigin.MANAGED;
            } else if (session) {
                origin = McpServerOrigin.SESSION;
            } else {
                origin = McpServerOrigin.UNKNOWN;
            }
            if (origin != McpServerOrigin.USER || !version.equals(baseUserVersion)) {
                version = "";
            }
            return new OriginSummary(origin, version);
        }

        private static boolean isManagedType(String type) {
            return "system".equals(type) || "mdm".equals(type)
                || "enterpriseManaged".equals(type)
                || "legacyManagedConfigTomlFromFile".equals(type)
                || "legacyManagedConfigTomlFromMdm".equals(type);
        }
    }

    private static final class OriginMetadata {
        private final String type;
        private final String version;
        private final boolean baseUser;

        private OriginMetadata(String type, String version, boolean baseUser) {
            this.type = type;
            this.version = version;
            this.baseUser = baseUser;
        }

        private static OriginMetadata decode(Object value) {
            if (!(value instanceof Map)) {
                return new OriginMetadata("", "", false);
            }
            Map<String, Object> metadata = JsonCodec.requireObject(value, "config origin");
            String version = JsonCodec.optionalString(metadata.get("version"));
            if (!isConfigurationVersion(version)) {
                version = "";
            }
            Object nameValue = metadata.get("name");
            if (!(nameValue instanceof Map)) {
                return new OriginMetadata("", version, false);
            }
            Map<String, Object> name = JsonCodec.requireObject(nameValue, "config origin name");
            String type = JsonCodec.optionalString(name.get("type"));
            Object profile = name.get("profile");
            boolean baseUser = "user".equals(type) && profile == null;
            return new OriginMetadata(type, version, baseUser);
        }
    }

    private static final class ProjectionBudget {
        private int remaining = MAX_PROJECTED_CHARACTERS;

        private String project(String value, int maximumCharacters, boolean required) {
            String raw = value == null ? "" : value;
            String redacted = CrashReportFormatter.redact(raw);
            if (redacted.length() > maximumCharacters || redacted.length() > remaining) {
                throw new IllegalArgumentException("MCP configuration projection exceeds the limit");
            }
            if (hasControl(redacted) || !redacted.equals(redacted.trim())
                || (required && redacted.isEmpty())) {
                throw new IllegalArgumentException("MCP configuration text is required");
            }
            remaining -= redacted.length();
            return redacted;
        }
    }
}
