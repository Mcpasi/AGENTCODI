package de.agentcodi.connectors.client;

import de.agentcodi.connectors.ConnectorCatalogSnapshot;
import de.agentcodi.connectors.ConnectorInfo;
import de.agentcodi.connectors.ConnectorInstallUrl;
import de.agentcodi.connectors.ConnectorPhase;
import de.agentcodi.connectors.ConnectorProvider;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.CodexCatalogRpc;
import de.agentcodi.core.JsonCodec;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects Gmail and GitHub from the app-server-owned Apps/MCP catalog.
 * Connector implementations, schemas, credentials, response paths and remote logos are discarded.
 */
public final class ConnectorCatalogLoader {
    static final int MAXIMUM_PAGES = 4;
    static final int PAGE_SIZE = 50;
    static final int MAXIMUM_SCANNED_APPS = 200;
    static final int MAXIMUM_CURSOR_CHARACTERS = 1024;
    static final int MAXIMUM_PROJECTED_CHARACTERS = 64 * 1024;
    static final long REQUEST_TIMEOUT_MS = 20_000L;

    private final CodexCatalogRpc rpc;

    public ConnectorCatalogLoader(CodexCatalogRpc rpc) {
        if (rpc == null) {
            throw new IllegalArgumentException("Connector catalog RPC is required");
        }
        this.rpc = rpc;
    }

    public ConnectorCatalogSnapshot load(long revision, boolean forceRefetch) {
        return load(revision, forceRefetch, currentThreadId());
    }

    String currentThreadId() {
        return validatedThreadId(rpc.catalogThreadId());
    }

    ConnectorCatalogSnapshot load(
        long revision,
        boolean forceRefetch,
        String requestedThreadId
    ) {
        if (revision < 0L) {
            throw new IllegalArgumentException("Connector revision must not be negative");
        }
        String threadId = validatedThreadId(requestedThreadId);
        Projection projection = new Projection();
        try {
            loadDirectory(projection, threadId, forceRefetch);
        } catch (Exception error) {
            return ConnectorCatalogSnapshot.empty(
                revision,
                ConnectorPhase.FAILED,
                threadId
            );
        }
        try {
            loadInstalled(projection, threadId, forceRefetch);
        } catch (Exception error) {
            projection.partial = true;
        }
        try {
            loadDetails(projection);
        } catch (Exception error) {
            projection.partial = true;
        }

        List<ConnectorInfo> connectors = new ArrayList<ConnectorInfo>();
        for (ConnectorProvider provider : ConnectorProvider.values()) {
            Candidate candidate = projection.candidates.get(provider);
            if (candidate == null) {
                connectors.add(ConnectorInfo.unavailable(provider));
                continue;
            }
            Installed installed = projection.installed.get(candidate.id);
            boolean installedPresent = installed != null;
            boolean enabled = installedPresent ? installed.enabled : candidate.enabled;
            boolean callable = candidate.accessible && enabled
                && installedPresent && installed.callable;
            connectors.add(new ConnectorInfo(
                provider,
                candidate.id,
                candidate.name,
                candidate.description,
                candidate.installUrl,
                true,
                candidate.accessible,
                enabled,
                installedPresent,
                callable,
                candidate.toolCount
            ));
        }
        return new ConnectorCatalogSnapshot(
            revision,
            projection.partial ? ConnectorPhase.PARTIAL : ConnectorPhase.READY,
            threadId,
            connectors,
            projection.truncated
        );
    }

    private void loadDirectory(
        Projection projection,
        String threadId,
        boolean forceRefetch
    ) throws Exception {
        String cursor = "";
        Set<String> seenCursors = new HashSet<String>();
        int scanned = 0;
        for (int page = 0; page < MAXIMUM_PAGES && scanned < MAXIMUM_SCANNED_APPS; page++) {
            Map<String, Object> params = JsonCodec.object(
                "limit", Long.valueOf(PAGE_SIZE),
                "forceRefetch", Boolean.valueOf(forceRefetch)
            );
            putThreadId(params, threadId);
            if (!cursor.isEmpty()) {
                params.put("cursor", cursor);
            }
            Map<String, Object> response = rpc.requestCatalog(
                "app/list",
                params,
                REQUEST_TIMEOUT_MS
            );
            List<Object> data = JsonCodec.requireArray(response.get("data"), "app list data");
            for (Object value : data) {
                if (scanned >= MAXIMUM_SCANNED_APPS || !projection.hasBudget()) {
                    projection.truncated = true;
                    break;
                }
                scanned++;
                Map<String, Object> app = JsonCodec.requireObject(value, "app list entry");
                String id = projection.required(app.get("id"), "app id", 256);
                String name = projection.required(app.get("name"), "app name", 160);
                if (!ConnectorSelection.isSafeId(id)) {
                    continue;
                }
                Match match = matchProvider(app, id, name);
                if (match == null) {
                    continue;
                }
                boolean accessible = JsonCodec.booleanValue(app.get("isAccessible"), false);
                boolean enabled = JsonCodec.booleanValue(app.get("isEnabled"), true);
                int score = match.score + (accessible ? 8 : 0) + (enabled ? 4 : 0);
                Candidate previous = projection.candidates.get(match.provider);
                if (previous != null && previous.score >= score) {
                    continue;
                }
                String installUrl = trustedUrl(
                    projection.optional(app.get("installUrl"), "app installUrl", 8192)
                );
                projection.candidates.put(match.provider, new Candidate(
                    id,
                    name,
                    projection.optional(app.get("description"), "app description", 600),
                    installUrl,
                    accessible,
                    enabled,
                    score
                ));
            }
            String next = nextCursor(response.get("nextCursor"));
            if (next.isEmpty()) {
                return;
            }
            if (!seenCursors.add(next)) {
                projection.truncated = true;
                return;
            }
            cursor = next;
        }
        if (!cursor.isEmpty()) {
            projection.truncated = true;
        }
    }

    private void loadInstalled(
        Projection projection,
        String threadId,
        boolean forceRefresh
    ) throws Exception {
        Map<String, Object> params = JsonCodec.object(
            "forceRefresh", Boolean.valueOf(forceRefresh)
        );
        putThreadId(params, threadId);
        Map<String, Object> response = rpc.requestCatalog(
            "app/installed",
            params,
            REQUEST_TIMEOUT_MS
        );
        List<Object> values = JsonCodec.requireArray(response.get("apps"), "installed apps");
        int scanned = 0;
        for (Object value : values) {
            if (scanned >= MAXIMUM_SCANNED_APPS || !projection.hasBudget()) {
                projection.truncated = true;
                return;
            }
            scanned++;
            Map<String, Object> app = JsonCodec.requireObject(value, "installed app");
            String id = projection.required(app.get("id"), "installed app id", 256);
            if (!ConnectorSelection.isSafeId(id)) {
                continue;
            }
            projection.installed.put(id, new Installed(
                JsonCodec.booleanValue(app.get("enabled"), false),
                JsonCodec.booleanValue(app.get("callable"), false)
            ));
        }
    }

    private void loadDetails(Projection projection) throws Exception {
        List<Object> ids = new ArrayList<Object>();
        for (ConnectorProvider provider : ConnectorProvider.values()) {
            Candidate candidate = projection.candidates.get(provider);
            if (candidate != null) {
                ids.add(candidate.id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, Object> params = JsonCodec.object(
            "appIds", ids,
            "includeTools", Boolean.TRUE
        );
        Map<String, Object> response = rpc.requestCatalog(
            "app/read",
            params,
            REQUEST_TIMEOUT_MS
        );
        Map<String, Candidate> byId = new HashMap<String, Candidate>();
        for (Candidate candidate : projection.candidates.values()) {
            byId.put(candidate.id, candidate);
        }
        int scanned = 0;
        for (Object value : JsonCodec.requireArray(response.get("apps"), "app details")) {
            if (scanned >= MAXIMUM_SCANNED_APPS || !projection.hasBudget()) {
                projection.truncated = true;
                break;
            }
            scanned++;
            Map<String, Object> app = JsonCodec.requireObject(value, "app details entry");
            String id = JsonCodec.requireString(app.get("id"), "app detail id");
            Candidate candidate = byId.get(id);
            if (candidate == null) {
                continue;
            }
            String name = projection.required(app.get("name"), "app detail name", 160);
            String description = projection.optional(
                app.get("description"),
                "app detail description",
                600
            );
            String installUrl = trustedUrl(
                projection.optional(app.get("installUrl"), "app detail installUrl", 8192)
            );
            candidate.name = name;
            if (!description.isEmpty()) {
                candidate.description = description;
            }
            if (!installUrl.isEmpty()) {
                candidate.installUrl = installUrl;
            }
            int toolCount = 0;
            for (Object tool : JsonCodec.optionalArray(app.get("toolSummaries"))) {
                if (toolCount >= ConnectorInfo.MAXIMUM_TOOL_COUNT || !projection.hasBudget()) {
                    projection.truncated = true;
                    break;
                }
                Map<String, Object> summary = JsonCodec.requireObject(tool, "app tool summary");
                projection.required(summary.get("name"), "app tool name", 160);
                projection.required(summary.get("description"), "app tool description", 320);
                toolCount++;
            }
            candidate.toolCount = toolCount;
        }
        if (!JsonCodec.requireArray(
                response.get("missingAppIds"),
                "missing app ids"
            ).isEmpty()) {
            projection.partial = true;
        }
    }

    private static Match matchProvider(Map<String, Object> app, String id, String name) {
        Match best = matchText(id, name, 100, 90);
        for (Object value : JsonCodec.optionalArray(app.get("pluginDisplayNames"))) {
            String pluginName = JsonCodec.optionalString(value);
            Match pluginMatch = matchText("", pluginName, 80, 70);
            best = stronger(best, pluginMatch);
        }
        Object labelsValue = app.get("labels");
        if (labelsValue instanceof Map) {
            Map<String, Object> labels = JsonCodec.requireObject(labelsValue, "app labels");
            for (Object value : labels.values()) {
                Match labelMatch = matchText("", JsonCodec.optionalString(value), 60, 50);
                best = stronger(best, labelMatch);
            }
        }
        return best;
    }

    private static Match matchText(
        String id,
        String displayName,
        int exactNameScore,
        int idScore
    ) {
        String normalizedName = normalized(displayName);
        for (ConnectorProvider provider : ConnectorProvider.values()) {
            String target = provider == ConnectorProvider.GMAIL ? "gmail" : "github";
            int score = 0;
            if (normalizedName.equals(target)
                || (provider == ConnectorProvider.GMAIL
                    && normalizedName.equals("googlemail"))) {
                score += exactNameScore;
            }
            if (matchesProviderId(id, target)) {
                score += idScore;
            }
            if (score > 0) {
                return new Match(provider, score);
            }
        }
        return null;
    }

    private static boolean matchesProviderId(String value, String target) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.equals(target)
            || lower.endsWith("." + target)
            || lower.endsWith("_" + target)
            || lower.endsWith("-" + target);
    }

    private static Match stronger(Match first, Match second) {
        if (first == null) {
            return second;
        }
        return second != null && second.score > first.score ? second : first;
    }

    private static String normalized(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (character >= 'a' && character <= 'z') {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String trustedUrl(String value) {
        return ConnectorInstallUrl.isTrusted(value) ? value : "";
    }

    private static String validatedThreadId(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() > 256) {
            throw new IllegalArgumentException("Connector thread id exceeds the limit");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("Connector thread id is invalid");
            }
        }
        return value;
    }

    private static void putThreadId(Map<String, Object> params, String threadId) {
        if (!threadId.isEmpty()) {
            params.put("threadId", threadId);
        }
    }

    private static String nextCursor(Object value) {
        if (value == null) {
            return "";
        }
        String cursor = JsonCodec.requireString(value, "connector cursor");
        if (cursor.length() > MAXIMUM_CURSOR_CHARACTERS) {
            throw new IllegalArgumentException("Connector cursor exceeds the limit");
        }
        return cursor;
    }

    private static final class Projection {
        private final Map<ConnectorProvider, Candidate> candidates =
            new EnumMap<ConnectorProvider, Candidate>(ConnectorProvider.class);
        private final Map<String, Installed> installed =
            new LinkedHashMap<String, Installed>();
        private int characters;
        private boolean partial;
        private boolean truncated;

        private boolean hasBudget() {
            return characters <= MAXIMUM_PROJECTED_CHARACTERS;
        }

        private String required(Object value, String label, int maximumCharacters) {
            String text = JsonCodec.requireString(value, label);
            return retain(text, label, maximumCharacters);
        }

        private String optional(Object value, String label, int maximumCharacters) {
            String text = JsonCodec.optionalString(value);
            return text.isEmpty() ? "" : retain(text, label, maximumCharacters);
        }

        private String retain(String text, String label, int maximumCharacters) {
            if (text.length() > maximumCharacters) {
                throw new IllegalArgumentException(label + " exceeds the limit");
            }
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t') {
                    throw new IllegalArgumentException(label + " contains control data");
                }
            }
            characters += text.length();
            if (!hasBudget()) {
                truncated = true;
            }
            return text;
        }
    }

    private static final class Candidate {
        private final String id;
        private String name;
        private String description;
        private String installUrl;
        private final boolean accessible;
        private final boolean enabled;
        private final int score;
        private int toolCount;

        private Candidate(
            String id,
            String name,
            String description,
            String installUrl,
            boolean accessible,
            boolean enabled,
            int score
        ) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.installUrl = installUrl;
            this.accessible = accessible;
            this.enabled = enabled;
            this.score = score;
        }
    }

    private static final class Installed {
        private final boolean enabled;
        private final boolean callable;

        private Installed(boolean enabled, boolean callable) {
            this.enabled = enabled;
            this.callable = callable;
        }
    }

    private static final class Match {
        private final ConnectorProvider provider;
        private final int score;

        private Match(ConnectorProvider provider, int score) {
            this.provider = provider;
            this.score = score;
        }
    }
}
