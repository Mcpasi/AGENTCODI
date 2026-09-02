package de.agentcodi.mcp.client;

import de.agentcodi.core.CodexCatalogRpc;
import de.agentcodi.core.CrashReportFormatter;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.mcp.McpAppInfo;
import de.agentcodi.mcp.McpCatalogPhase;
import de.agentcodi.mcp.McpCatalogSnapshot;
import de.agentcodi.mcp.McpCatalogWarning;
import de.agentcodi.mcp.McpFeatureInfo;
import de.agentcodi.mcp.McpMarketplaceInfo;
import de.agentcodi.mcp.McpPluginInfo;
import de.agentcodi.mcp.McpServerInfo;
import de.agentcodi.mcp.McpSkillInfo;
import de.agentcodi.mcp.McpToolInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a bounded, display-only capability projection from the active Codex app-server.
 * Filesystem paths, schemas, configuration values, and authentication material are discarded.
 */
public final class McpCatalogLoader {
    static final int MAX_FEATURES = 64;
    static final int MAX_SKILLS = 128;
    static final int MAX_SERVERS = 64;
    static final int MAX_SERVER_TOOLS = 256;
    static final int MAX_APPS = 100;
    static final int MAX_APP_TOOLS = 256;
    static final int MAX_MARKETPLACES = 32;
    static final int MAX_PLUGINS = 192;
    static final int MAX_CAPABILITIES_PER_PLUGIN = 16;
    static final int MAX_PAGES = 4;
    static final int MAX_CURSOR_CHARACTERS = 1024;
    static final int MAX_PROJECTED_CHARACTERS = 128 * 1024;
    static final long REQUEST_TIMEOUT_MS = 20_000L;

    private final CodexCatalogRpc rpc;
    private final String workspacePath;

    public McpCatalogLoader(CodexCatalogRpc rpc, String workspacePath) {
        if (rpc == null) {
            throw new IllegalArgumentException("Catalog RPC gateway is required");
        }
        if (workspacePath == null || workspacePath.isEmpty()
            || workspacePath.length() > 4096 || !workspacePath.startsWith("/")) {
            throw new IllegalArgumentException("Canonical workspace path is required");
        }
        this.rpc = rpc;
        this.workspacePath = workspacePath;
    }

    public McpCatalogSnapshot load(long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException("Catalog revision must not be negative");
        }
        LoadContext context = new LoadContext();
        String threadId = validatedThreadId(rpc.catalogThreadId());
        int successfulSections = 0;

        try {
            loadFeatures(context, threadId);
            successfulSections++;
        } catch (Exception error) {
            context.warn(McpCatalogWarning.FEATURES_UNAVAILABLE);
        }
        try {
            loadSkills(context);
            successfulSections++;
        } catch (Exception error) {
            context.warn(McpCatalogWarning.SKILLS_UNAVAILABLE);
        }
        try {
            loadMcpServers(context, threadId);
            successfulSections++;
        } catch (Exception error) {
            context.warn(McpCatalogWarning.MCP_SERVERS_UNAVAILABLE);
        }
        try {
            loadApps(context, threadId);
            successfulSections++;
        } catch (Exception error) {
            context.warn(McpCatalogWarning.APPS_UNAVAILABLE);
        }
        try {
            loadPlugins(context);
            successfulSections++;
        } catch (Exception error) {
            context.warn(McpCatalogWarning.PLUGINS_UNAVAILABLE);
        }

        if (context.truncated) {
            context.warn(McpCatalogWarning.CATALOG_TRUNCATED);
        }
        McpCatalogPhase phase = successfulSections == 0
            ? McpCatalogPhase.FAILED
            : context.warnings.isEmpty()
                ? McpCatalogPhase.READY
                : McpCatalogPhase.PARTIAL;
        return new McpCatalogSnapshot(
            revision,
            phase,
            context.features,
            context.skills,
            context.servers,
            context.apps,
            context.marketplaces,
            new ArrayList<McpCatalogWarning>(context.warnings)
        );
    }

    private void loadFeatures(LoadContext context, String threadId) throws Exception {
        String cursor = "";
        Set<String> seenCursors = new HashSet<String>();
        for (int page = 0; page < MAX_PAGES && context.features.size() < MAX_FEATURES; page++) {
            Map<String, Object> params = JsonCodec.object("limit", Long.valueOf(50L));
            putThreadId(params, threadId);
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            Map<String, Object> response = rpc.requestCatalog(
                "experimentalFeature/list",
                params,
                REQUEST_TIMEOUT_MS
            );
            List<Object> entries = JsonCodec.requireArray(response.get("data"), "feature data");
            for (Object value : entries) {
                if (context.features.size() >= MAX_FEATURES || !context.hasBudget()) {
                    context.truncated = true;
                    break;
                }
                Map<String, Object> feature = JsonCodec.requireObject(value, "feature");
                context.features.add(new McpFeatureInfo(
                    context.required(feature.get("name"), "feature name", 128),
                    context.optional(feature.get("displayName"), "feature displayName", 160),
                    context.optional(feature.get("description"), "feature description", 320),
                    context.required(feature.get("stage"), "feature stage", 32),
                    requireBoolean(feature.get("enabled"), "feature enabled"),
                    requireBoolean(feature.get("defaultEnabled"), "feature defaultEnabled")
                ));
            }
            String next = cursor(response.get("nextCursor"));
            if (next.isEmpty()) {
                return;
            }
            if (!seenCursors.add(next)) {
                context.truncated = true;
                return;
            }
            cursor = next;
        }
        if (!cursor.isEmpty()) {
            context.truncated = true;
        }
    }

    private void loadSkills(LoadContext context) throws Exception {
        Map<String, Object> response = rpc.requestCatalog(
            "skills/list",
            JsonCodec.object(
                "cwds", JsonCodec.array(workspacePath),
                "forceReload", Boolean.FALSE
            ),
            REQUEST_TIMEOUT_MS
        );
        List<Object> groups = JsonCodec.requireArray(response.get("data"), "skills data");
        for (Object groupValue : groups) {
            Map<String, Object> group = JsonCodec.requireObject(groupValue, "skills entry");
            List<Object> errors = JsonCodec.requireArray(group.get("errors"), "skill errors");
            if (!errors.isEmpty()) {
                context.warn(McpCatalogWarning.SKILL_LOAD_ERRORS);
            }
            List<Object> skills = JsonCodec.requireArray(group.get("skills"), "skills");
            for (Object skillValue : skills) {
                if (context.skills.size() >= MAX_SKILLS || !context.hasBudget()) {
                    context.truncated = true;
                    return;
                }
                Map<String, Object> skill = JsonCodec.requireObject(skillValue, "skill");
                Map<String, Object> skillInterface = optionalObject(
                    skill.get("interface"),
                    "skill interface"
                );
                String displayName = skillInterface == null
                    ? ""
                    : context.optional(
                        skillInterface.get("displayName"),
                        "skill displayName",
                        160
                    );
                String description = skillInterface == null
                    ? ""
                    : context.optional(
                        skillInterface.get("shortDescription"),
                        "skill shortDescription",
                        360
                    );
                if (description.isEmpty()) {
                    description = context.optional(
                        skill.get("shortDescription"),
                        "skill legacy shortDescription",
                        360
                    );
                }
                if (description.isEmpty()) {
                    description = context.required(
                        skill.get("description"),
                        "skill description",
                        360
                    );
                }
                int dependencyCount = 0;
                Map<String, Object> dependencies = optionalObject(
                    skill.get("dependencies"),
                    "skill dependencies"
                );
                if (dependencies != null) {
                    dependencyCount = Math.min(
                        JsonCodec.requireArray(
                            dependencies.get("tools"),
                            "skill tool dependencies"
                        ).size(),
                        1000
                    );
                }
                context.skills.add(new McpSkillInfo(
                    context.required(skill.get("name"), "skill name", 128),
                    displayName,
                    description,
                    context.required(skill.get("scope"), "skill scope", 32),
                    requireBoolean(skill.get("enabled"), "skill enabled"),
                    dependencyCount
                ));
            }
        }
    }

    private void loadMcpServers(LoadContext context, String threadId) throws Exception {
        String cursor = "";
        Set<String> seenCursors = new HashSet<String>();
        int toolCount = 0;
        for (int page = 0; page < MAX_PAGES && context.servers.size() < MAX_SERVERS; page++) {
            Map<String, Object> params = JsonCodec.object(
                "detail", "toolsAndAuthOnly",
                "limit", Long.valueOf(32L)
            );
            putThreadId(params, threadId);
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            Map<String, Object> response = rpc.requestCatalog(
                "mcpServerStatus/list",
                params,
                REQUEST_TIMEOUT_MS
            );
            List<Object> entries = JsonCodec.requireArray(response.get("data"), "MCP data");
            for (Object value : entries) {
                if (context.servers.size() >= MAX_SERVERS || !context.hasBudget()) {
                    context.truncated = true;
                    break;
                }
                Map<String, Object> server = JsonCodec.requireObject(value, "MCP server");
                Map<String, Object> serverInfo = optionalObject(
                    server.get("serverInfo"),
                    "MCP server info"
                );
                String title = serverInfo == null
                    ? ""
                    : context.optional(serverInfo.get("title"), "MCP title", 160);
                String version = serverInfo == null
                    ? ""
                    : context.required(serverInfo.get("version"), "MCP version", 80);
                String description = serverInfo == null
                    ? ""
                    : context.optional(
                        serverInfo.get("description"),
                        "MCP description",
                        360
                    );
                Map<String, Object> tools = JsonCodec.requireObject(server.get("tools"), "MCP tools");
                List<McpToolInfo> projectedTools = new ArrayList<McpToolInfo>();
                for (Map.Entry<String, Object> entry : tools.entrySet()) {
                    if (toolCount >= MAX_SERVER_TOOLS || !context.hasBudget()) {
                        context.truncated = true;
                        break;
                    }
                    Map<String, Object> tool = JsonCodec.requireObject(entry.getValue(), "MCP tool");
                    String toolName = context.required(tool.get("name"), "MCP tool name", 160);
                    projectedTools.add(new McpToolInfo(
                        toolName,
                        context.optional(tool.get("title"), "MCP tool title", 160),
                        context.optional(
                            tool.get("description"),
                            "MCP tool description",
                            320
                        ),
                        true,
                        false,
                        ""
                    ));
                    toolCount++;
                }
                int resources = JsonCodec.requireArray(
                    server.get("resources"),
                    "MCP resources"
                ).size();
                int templates = JsonCodec.requireArray(
                    server.get("resourceTemplates"),
                    "MCP resource templates"
                ).size();
                context.servers.add(new McpServerInfo(
                    context.required(server.get("name"), "MCP server name", 160),
                    title,
                    version,
                    description,
                    context.required(server.get("authStatus"), "MCP auth status", 32),
                    projectedTools,
                    Math.min(resources, 10_000),
                    Math.min(templates, 10_000)
                ));
            }
            String next = cursor(response.get("nextCursor"));
            if (next.isEmpty()) {
                return;
            }
            if (!seenCursors.add(next)) {
                context.truncated = true;
                return;
            }
            cursor = next;
        }
        if (!cursor.isEmpty()) {
            context.truncated = true;
        }
    }

    private void loadApps(LoadContext context, String threadId) throws Exception {
        Map<String, Object> installedParams = JsonCodec.object("forceRefresh", Boolean.FALSE);
        putThreadId(installedParams, threadId);
        Map<String, Object> response = rpc.requestCatalog(
            "app/installed",
            installedParams,
            REQUEST_TIMEOUT_MS
        );
        List<Object> values = JsonCodec.requireArray(response.get("apps"), "installed apps");
        LinkedHashMap<String, AppRecord> records = new LinkedHashMap<String, AppRecord>();
        for (Object value : values) {
            if (records.size() >= MAX_APPS || !context.hasBudget()) {
                context.truncated = true;
                break;
            }
            Map<String, Object> app = JsonCodec.requireObject(value, "installed app");
            String id = context.identity(app.get("id"), "app id", 256);
            if (id.isEmpty()) {
                // The identifier addresses app/read and indexes the detail response, so an
                // entry that cannot be carried through unchanged is dropped, never reshaped.
                context.truncated = true;
                continue;
            }
            if (records.containsKey(id)) {
                continue;
            }
            String runtimeName = context.optional(app.get("runtimeName"), "app runtimeName", 160);
            records.put(id, new AppRecord(
                id,
                runtimeName,
                requireBoolean(app.get("enabled"), "app enabled"),
                requireBoolean(app.get("callable"), "app callable")
            ));
        }
        if (!records.isEmpty()) {
            try {
                List<Object> ids = new ArrayList<Object>();
                ids.addAll(records.keySet());
                Map<String, Object> details = rpc.requestCatalog(
                    "app/read",
                    JsonCodec.object("appIds", ids, "includeTools", Boolean.TRUE),
                    REQUEST_TIMEOUT_MS
                );
                applyAppDetails(context, records, details);
            } catch (Exception error) {
                context.warn(McpCatalogWarning.APP_DETAILS_INCOMPLETE);
            }
        }
        for (AppRecord record : records.values()) {
            context.apps.add(new McpAppInfo(
                record.id,
                record.name,
                record.enabled,
                record.callable,
                record.tools
            ));
        }
    }

    private void applyAppDetails(
        LoadContext context,
        Map<String, AppRecord> records,
        Map<String, Object> response
    ) {
        int toolCount = 0;
        List<Object> apps = JsonCodec.requireArray(response.get("apps"), "app details");
        for (Object value : apps) {
            Map<String, Object> app = JsonCodec.requireObject(value, "app details entry");
            String rawId = JsonCodec.requireString(app.get("id"), "app detail id");
            AppRecord record = records.get(rawId);
            if (record == null) {
                continue;
            }
            record.name = context.required(app.get("name"), "app name", 160);
            List<Object> tools = JsonCodec.optionalArray(app.get("toolSummaries"));
            for (Object toolValue : tools) {
                if (toolCount >= MAX_APP_TOOLS || !context.hasBudget()) {
                    context.truncated = true;
                    break;
                }
                Map<String, Object> tool = JsonCodec.requireObject(toolValue, "app tool");
                record.tools.add(new McpToolInfo(
                    context.required(tool.get("name"), "app tool name", 160),
                    context.optional(tool.get("title"), "app tool title", 160),
                    context.required(tool.get("description"), "app tool description", 320),
                    optionalBoolean(tool.get("isEnabled"), true, "app tool enabled"),
                    optionalBoolean(tool.get("isReadOnly"), false, "app tool readOnly"),
                    context.optional(
                        tool.get("disabledReason"),
                        "app tool disabledReason",
                        200
                    )
                ));
                toolCount++;
            }
        }
        List<Object> missing = JsonCodec.requireArray(
            response.get("missingAppIds"),
            "missing app ids"
        );
        if (!missing.isEmpty()) {
            context.warn(McpCatalogWarning.APP_DETAILS_INCOMPLETE);
        }
    }

    private void loadPlugins(LoadContext context) throws Exception {
        Map<String, Object> response = rpc.requestCatalog(
            "plugin/list",
            JsonCodec.object(
                "cwds", JsonCodec.array(workspacePath),
                "forceRefetch", Boolean.FALSE,
                "marketplaceKinds", JsonCodec.array("local")
            ),
            REQUEST_TIMEOUT_MS
        );
        List<Object> loadErrors = JsonCodec.optionalArray(response.get("marketplaceLoadErrors"));
        if (!loadErrors.isEmpty()) {
            context.warn(McpCatalogWarning.MARKETPLACE_LOAD_ERRORS);
        }
        List<Object> marketplaces = JsonCodec.requireArray(
            response.get("marketplaces"),
            "marketplaces"
        );
        int pluginCount = 0;
        for (Object value : marketplaces) {
            if (context.marketplaces.size() >= MAX_MARKETPLACES || !context.hasBudget()) {
                context.truncated = true;
                break;
            }
            Map<String, Object> marketplace = JsonCodec.requireObject(value, "marketplace");
            Map<String, Object> marketplaceInterface = optionalObject(
                marketplace.get("interface"),
                "marketplace interface"
            );
            String displayName = marketplaceInterface == null
                ? ""
                : context.optional(
                    marketplaceInterface.get("displayName"),
                    "marketplace displayName",
                    160
                );
            List<McpPluginInfo> plugins = new ArrayList<McpPluginInfo>();
            for (Object pluginValue : JsonCodec.requireArray(
                    marketplace.get("plugins"),
                    "marketplace plugins")) {
                if (pluginCount >= MAX_PLUGINS || !context.hasBudget()) {
                    context.truncated = true;
                    break;
                }
                Map<String, Object> plugin = JsonCodec.requireObject(pluginValue, "plugin");
                Map<String, Object> pluginInterface = optionalObject(
                    plugin.get("interface"),
                    "plugin interface"
                );
                String pluginDisplayName = pluginInterface == null
                    ? ""
                    : context.optional(
                        pluginInterface.get("displayName"),
                        "plugin displayName",
                        160
                    );
                String pluginDescription = pluginInterface == null
                    ? ""
                    : context.optional(
                        pluginInterface.get("shortDescription"),
                        "plugin description",
                        320
                    );
                List<String> capabilities = new ArrayList<String>();
                if (pluginInterface != null) {
                    for (Object capability : JsonCodec.requireArray(
                            pluginInterface.get("capabilities"),
                            "plugin capabilities")) {
                        if (capabilities.size() >= MAX_CAPABILITIES_PER_PLUGIN) {
                            context.truncated = true;
                            break;
                        }
                        capabilities.add(context.required(
                            capability,
                            "plugin capability",
                            80
                        ));
                    }
                }
                Map<String, Object> source = JsonCodec.requireObject(
                    plugin.get("source"),
                    "plugin source"
                );
                String version = context.optional(
                    plugin.get("localVersion"),
                    "plugin localVersion",
                    80
                );
                if (version.isEmpty()) {
                    version = context.optional(plugin.get("version"), "plugin version", 80);
                }
                plugins.add(new McpPluginInfo(
                    context.required(plugin.get("id"), "plugin id", 256),
                    context.required(plugin.get("name"), "plugin name", 160),
                    pluginDisplayName,
                    pluginDescription,
                    version,
                    context.required(source.get("type"), "plugin source type", 32),
                    context.optional(plugin.get("availability"), "plugin availability", 40),
                    context.required(plugin.get("installPolicy"), "plugin install policy", 40),
                    requireBoolean(plugin.get("installed"), "plugin installed"),
                    requireBoolean(plugin.get("enabled"), "plugin enabled"),
                    capabilities
                ));
                pluginCount++;
            }
            context.marketplaces.add(new McpMarketplaceInfo(
                context.required(marketplace.get("name"), "marketplace name", 160),
                displayName,
                plugins
            ));
        }
    }

    private static Map<String, Object> optionalObject(Object value, String field) {
        if (value == null) {
            return null;
        }
        return JsonCodec.requireObject(value, field);
    }

    private static boolean requireBoolean(Object value, String field) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    private static boolean optionalBoolean(Object value, boolean fallback, String field) {
        if (value == null) {
            return fallback;
        }
        return requireBoolean(value, field);
    }

    private static String cursor(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Catalog cursor must be a string or null");
        }
        String cursor = (String) value;
        if (cursor.length() > MAX_CURSOR_CHARACTERS) {
            throw new IllegalArgumentException("Catalog cursor exceeds the limit");
        }
        return cursor;
    }

    private static String validatedThreadId(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() > 256) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '_' && character != '-') {
                return "";
            }
        }
        return value;
    }

    private static void putThreadId(Map<String, Object> params, String threadId) {
        if (!threadId.isEmpty()) {
            params.put("threadId", threadId);
        }
    }

    private static final class AppRecord {
        private final String id;
        private String name;
        private final boolean enabled;
        private final boolean callable;
        private final List<McpToolInfo> tools = new ArrayList<McpToolInfo>();

        private AppRecord(
            String id,
            String name,
            boolean enabled,
            boolean callable
        ) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.callable = callable;
        }
    }

    private static final class LoadContext {
        private final List<McpFeatureInfo> features = new ArrayList<McpFeatureInfo>();
        private final List<McpSkillInfo> skills = new ArrayList<McpSkillInfo>();
        private final List<McpServerInfo> servers = new ArrayList<McpServerInfo>();
        private final List<McpAppInfo> apps = new ArrayList<McpAppInfo>();
        private final List<McpMarketplaceInfo> marketplaces =
            new ArrayList<McpMarketplaceInfo>();
        private final LinkedHashSet<McpCatalogWarning> warnings =
            new LinkedHashSet<McpCatalogWarning>();
        private int remainingCharacters = MAX_PROJECTED_CHARACTERS;
        private boolean truncated;

        private boolean hasBudget() {
            return remainingCharacters > 0;
        }

        private void warn(McpCatalogWarning warning) {
            warnings.add(warning);
        }

        private String required(Object value, String field, int maximumCharacters) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IllegalArgumentException(field + " must be a non-empty string");
            }
            String projected = project((String) value, maximumCharacters);
            if (projected.isEmpty()) {
                throw new IllegalArgumentException(field + " exceeds the catalog budget");
            }
            return projected;
        }

        private String optional(Object value, String field, int maximumCharacters) {
            if (value == null) {
                return "";
            }
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(field + " must be a string or null");
            }
            return project((String) value, maximumCharacters);
        }

        /**
         * Projects an identifier that is used as more than display text. Identity must stay
         * byte-exact, so a value the display projection would shorten or rewrite is reported
         * as unusable instead of being returned in a mangled form.
         */
        private String identity(Object value, String field, int maximumCharacters) {
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw new IllegalArgumentException(field + " must be a non-empty string");
            }
            String raw = (String) value;
            if (raw.length() > maximumCharacters || !raw.equals(raw.trim())
                || hasControlCharacter(raw)
                || !CrashReportFormatter.redact(raw).equals(raw)) {
                return "";
            }
            remainingCharacters -= Math.min(raw.length(), remainingCharacters);
            return raw;
        }

        private static boolean hasControlCharacter(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    return true;
                }
            }
            return false;
        }

        private String project(String value, int maximumCharacters) {
            String redacted = CrashReportFormatter.redact(value == null ? "" : value).trim();
            StringBuilder cleaned = new StringBuilder(Math.min(redacted.length(), maximumCharacters));
            for (int index = 0; index < redacted.length(); index++) {
                char character = redacted.charAt(index);
                if (Character.isISOControl(character)
                    && character != '\n' && character != '\t') {
                    character = ' ';
                }
                cleaned.append(character);
                if (cleaned.length() >= maximumCharacters) {
                    if (index + 1 < redacted.length()) {
                        truncated = true;
                    }
                    break;
                }
            }
            int allowed = Math.min(cleaned.length(), remainingCharacters);
            if (allowed < cleaned.length()) {
                truncated = true;
            }
            if (allowed <= 0) {
                return "";
            }
            remainingCharacters -= allowed;
            return cleaned.substring(0, allowed).trim();
        }
    }
}
