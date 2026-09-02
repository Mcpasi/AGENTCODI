package de.agentcodi.tests;

import de.agentcodi.core.CodexCatalogRpc;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.mcp.McpCatalogPhase;
import de.agentcodi.mcp.McpCatalogSnapshot;
import de.agentcodi.mcp.McpCatalogWarning;
import de.agentcodi.mcp.McpMarketplaceInfo;
import de.agentcodi.mcp.McpServerInfo;
import de.agentcodi.mcp.McpSkillInfo;
import de.agentcodi.mcp.client.McpCatalogController;
import de.agentcodi.mcp.client.McpCatalogLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpCatalogLoaderTest {
    private McpCatalogLoaderTest() {
    }

    /** Mirrors McpCatalogLoader.MAX_PROJECTED_CHARACTERS, which is not visible from here. */
    private static final int DISPLAY_BUDGET = 128 * 1024;

    public static int run() throws Exception {
        loadsBoundedCatalogWithoutProjectingPaths();
        keepsExperimentalFailurePartialAndOpaque();
        truncatesOversizedSkillInventory();
        keepsAppIdentityExactWhenTheDisplayBudgetIsExhausted();
        dropsAppsWhoseIdentityCannotSurviveTheProjection();
        enforcesReadOnlyRpcAllowlistAndControllerLifecycle();
        return 6;
    }

    private static void loadsBoundedCatalogWithoutProjectingPaths() {
        FixtureRpc rpc = new FixtureRpc(false, 1);
        McpCatalogSnapshot snapshot = new McpCatalogLoader(
            rpc,
            "/private/workspace"
        ).load(7L);

        TestSupport.assertEquals(McpCatalogPhase.READY, snapshot.getPhase(), "catalog phase");
        TestSupport.assertEquals(Long.valueOf(7L), Long.valueOf(snapshot.getRevision()), "revision");
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(snapshot.getFeatures().size()), "features");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(snapshot.getSkills().size()), "skills");
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(snapshot.getServers().size()), "servers");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(snapshot.getApps().size()), "apps");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(snapshot.getMarketplaces().size()), "marketplaces");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(snapshot.getPluginCount()), "plugins");
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(snapshot.getToolCount()), "tools");
        TestSupport.assertTrue(snapshot.getWarnings().isEmpty(), "no fixture warnings");

        McpSkillInfo skill = snapshot.getSkills().get(0);
        TestSupport.assertEquals("fixture-skill", skill.getName(), "skill name");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(skill.getToolDependencyCount()), "dependencies");
        McpServerInfo server = snapshot.getServers().get(0);
        TestSupport.assertEquals("oAuth", server.getAuthStatus(), "MCP auth state");
        TestSupport.assertEquals("Search", server.getTools().get(0).getTitle(), "MCP tool title");
        McpMarketplaceInfo marketplace = snapshot.getMarketplaces().get(0);
        TestSupport.assertEquals("Fixture Marketplace", marketplace.getDisplayName(), "marketplace name");
        TestSupport.assertEquals("local", marketplace.getPlugins().get(0).getSourceType(), "source type");

        String projected = flatten(snapshot);
        TestSupport.assertFalse(projected.contains("/private/"), "filesystem paths omitted");
        TestSupport.assertFalse(projected.contains("inputSchema"), "tool schemas omitted");
        TestSupport.assertFalse(projected.contains("marketplace.json"), "marketplace file omitted");
        TestSupport.assertTrue(rpc.methods.contains("experimentalFeature/list"), "feature RPC used");
        TestSupport.assertTrue(rpc.methods.contains("skills/list"), "skills RPC used");
        TestSupport.assertTrue(rpc.methods.contains("mcpServerStatus/list"), "MCP RPC used");
        TestSupport.assertTrue(rpc.methods.contains("app/installed"), "installed app RPC used");
        TestSupport.assertTrue(rpc.methods.contains("app/read"), "app details RPC used");
        TestSupport.assertTrue(rpc.methods.contains("plugin/list"), "plugin RPC used");
        TestSupport.assertFalse(rpc.containsMethodPrefix("config/"), "config RPC never used");
        TestSupport.assertFalse(rpc.methods.contains("mcpServer/tool/call"), "tools never invoked");
        TestSupport.assertEquals(
            "/private/workspace",
            rpc.skillWorkspace,
            "canonical workspace passed through"
        );
        TestSupport.assertEquals(
            "/private/workspace",
            rpc.pluginWorkspace,
            "plugin discovery uses same workspace"
        );
    }

    private static void keepsExperimentalFailurePartialAndOpaque() {
        FixtureRpc rpc = new FixtureRpc(true, 1);
        McpCatalogSnapshot snapshot = new McpCatalogLoader(
            rpc,
            "/private/workspace"
        ).load(2L);
        TestSupport.assertEquals(McpCatalogPhase.PARTIAL, snapshot.getPhase(), "partial catalog");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(snapshot.getSkills().size()), "core inventory retained");
        TestSupport.assertTrue(
            snapshot.getWarnings().contains(McpCatalogWarning.PLUGINS_UNAVAILABLE),
            "plugin warning"
        );
        TestSupport.assertFalse(
            flatten(snapshot).contains("private-marketplace-path"),
            "raw RPC failure not retained"
        );
    }

    private static void truncatesOversizedSkillInventory() {
        FixtureRpc rpc = new FixtureRpc(false, 140);
        McpCatalogSnapshot snapshot = new McpCatalogLoader(
            rpc,
            "/private/workspace"
        ).load(3L);
        TestSupport.assertEquals(
            Integer.valueOf(128),
            Integer.valueOf(snapshot.getSkills().size()),
            "skill cap"
        );
        TestSupport.assertTrue(
            snapshot.getWarnings().contains(McpCatalogWarning.CATALOG_TRUNCATED),
            "truncation warning"
        );
        TestSupport.assertEquals(McpCatalogPhase.PARTIAL, snapshot.getPhase(), "truncated phase");
        TestSupport.expectThrows(
            UnsupportedOperationException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    snapshot.getSkills().clear();
                }
            },
            "snapshot list immutable"
        );
    }

    private static void keepsAppIdentityExactWhenTheDisplayBudgetIsExhausted() {
        // A workspace with a couple of hundred documented MCP tools spends the display budget
        // before the installed apps are read. The remaining budget must never shorten an app
        // id: it addresses app/read and indexes the detail response that carries name and tools.
        AppFixtureRpc rpc = new AppFixtureRpc(
            DISPLAY_BUDGET - 5,
            Collections.singletonList("connector_fixture")
        );
        McpCatalogSnapshot snapshot = new McpCatalogLoader(
            rpc,
            "/private/workspace"
        ).load(11L);

        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(snapshot.getApps().size()),
            "installed app retained under an exhausted budget"
        );
        TestSupport.assertEquals(
            "connector_fixture",
            snapshot.getApps().get(0).getId(),
            "app id is never shortened for display"
        );
        TestSupport.assertEquals(
            Collections.singletonList("connector_fixture"),
            rpc.requestedAppIds,
            "app/read is addressed with the exact installed id"
        );
        TestSupport.assertTrue(
            snapshot.getWarnings().contains(McpCatalogWarning.CATALOG_TRUNCATED),
            "exhausted display budget stays disclosed"
        );
    }

    private static void dropsAppsWhoseIdentityCannotSurviveTheProjection() {
        List<String> installed = new ArrayList<String>();
        installed.add("connector_fixture");
        installed.add("sk-fixtureidvalue");
        installed.add("connector_spaced ");
        AppFixtureRpc rpc = new AppFixtureRpc(0, installed);
        McpCatalogSnapshot snapshot = new McpCatalogLoader(
            rpc,
            "/private/workspace"
        ).load(12L);

        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(snapshot.getApps().size()),
            "only apps with a carryable identity are listed"
        );
        TestSupport.assertEquals(
            "connector_fixture",
            snapshot.getApps().get(0).getId(),
            "carryable app identity retained"
        );
        TestSupport.assertEquals(
            Collections.singletonList("connector_fixture"),
            rpc.requestedAppIds,
            "no rewritten identity reaches app/read"
        );
        TestSupport.assertTrue(
            snapshot.getWarnings().contains(McpCatalogWarning.CATALOG_TRUNCATED),
            "dropped apps stay disclosed"
        );
        String projected = flatten(snapshot);
        TestSupport.assertFalse(projected.contains("sk-fixtureid"), "token-shaped id omitted");
        TestSupport.assertFalse(projected.contains("redacted"), "no redaction placeholder shown");
    }

    private static void enforcesReadOnlyRpcAllowlistAndControllerLifecycle() throws Exception {
        TestSupport.assertTrue(
            CodexSessionController.isReadOnlyCatalogMethod("mcpServerStatus/list"),
            "MCP list allowed"
        );
        TestSupport.assertTrue(
            CodexSessionController.isReadOnlyCatalogMethod("plugin/list"),
            "plugin list allowed"
        );
        TestSupport.assertFalse(
            CodexSessionController.isReadOnlyCatalogMethod("config/read"),
            "raw config read denied"
        );
        TestSupport.assertFalse(
            CodexSessionController.isReadOnlyCatalogMethod("mcpServer/tool/call"),
            "direct tool call denied"
        );
        TestSupport.assertFalse(
            CodexSessionController.isReadOnlyCatalogMethod("plugin/install"),
            "plugin install denied"
        );
        TestSupport.assertFalse(
            CodexSessionController.isReadOnlyCatalogMethod("mcpServer/oauth/login"),
            "MCP OAuth denied"
        );

        McpCatalogController controller = new McpCatalogController(
            new FixtureRpc(false, 1),
            "/private/workspace"
        );
        TestSupport.assertTrue(controller.refresh(), "first refresh accepted");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getPhase() != McpCatalogPhase.LOADING;
            }
        }, "asynchronous catalog refresh");
        TestSupport.assertEquals(McpCatalogPhase.READY, controller.snapshot().getPhase(), "async ready");
        controller.close();
        TestSupport.assertEquals(McpCatalogPhase.STOPPED, controller.snapshot().getPhase(), "closed state");
        TestSupport.assertFalse(controller.refresh(), "closed refresh rejected");
    }

    private static String flatten(McpCatalogSnapshot snapshot) {
        StringBuilder output = new StringBuilder();
        snapshot.getFeatures().forEach(feature -> output.append(feature.getName())
            .append(feature.getDisplayName()).append(feature.getDescription()));
        snapshot.getSkills().forEach(skill -> output.append(skill.getName())
            .append(skill.getDisplayName()).append(skill.getDescription()).append(skill.getScope()));
        snapshot.getServers().forEach(server -> {
            output.append(server.getName()).append(server.getTitle()).append(server.getDescription());
            server.getTools().forEach(tool -> output.append(tool.getName())
                .append(tool.getTitle()).append(tool.getDescription()));
        });
        snapshot.getApps().forEach(app -> {
            output.append(app.getId()).append(app.getName());
            app.getTools().forEach(tool -> output.append(tool.getName()).append(tool.getDescription()));
        });
        snapshot.getMarketplaces().forEach(marketplace -> {
            output.append(marketplace.getName()).append(marketplace.getDisplayName());
            marketplace.getPlugins().forEach(plugin -> output.append(plugin.getId())
                .append(plugin.getName()).append(plugin.getDescription()).append(plugin.getSourceType()));
        });
        return output.toString();
    }

    private static void waitFor(Condition condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.isTrue() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertTrue(condition.isTrue(), message);
    }

    private interface Condition {
        boolean isTrue();
    }

    private static final class FixtureRpc implements CodexCatalogRpc {
        private final boolean failPlugins;
        private final int skillCount;
        private final List<String> methods = new ArrayList<String>();
        private String skillWorkspace = "";
        private String pluginWorkspace = "";

        private FixtureRpc(boolean failPlugins, int skillCount) {
            this.failPlugins = failPlugins;
            this.skillCount = skillCount;
        }

        @Override
        public String catalogThreadId() {
            return "thr_fixture";
        }

        @Override
        public synchronized Map<String, Object> requestCatalog(
            String method,
            Map<String, Object> params,
            long timeoutMilliseconds
        ) throws Exception {
            methods.add(method);
            TestSupport.assertEquals(Long.valueOf(20_000L), Long.valueOf(timeoutMilliseconds), "timeout");
            if ("experimentalFeature/list".equals(method)) {
                TestSupport.assertEquals("thr_fixture", params.get("threadId"), "feature thread");
                String cursor = JsonCodec.optionalString(params.get("cursor"));
                if (cursor.isEmpty()) {
                    return JsonCodec.object(
                        "data", JsonCodec.array(feature("apps", "Apps", true)),
                        "nextCursor", "features-page-2"
                    );
                }
                TestSupport.assertEquals("features-page-2", cursor, "feature cursor");
                return JsonCodec.object(
                    "data", JsonCodec.array(feature("plugins", "Plugins", true)),
                    "nextCursor", null
                );
            }
            if ("skills/list".equals(method)) {
                skillWorkspace = firstCwd(params);
                List<Object> skills = new ArrayList<Object>();
                for (int index = 0; index < skillCount; index++) {
                    skills.add(skill(index));
                }
                return JsonCodec.object("data", JsonCodec.array(JsonCodec.object(
                    "cwd", "/private/workspace",
                    "skills", skills,
                    "errors", JsonCodec.array()
                )));
            }
            if ("mcpServerStatus/list".equals(method)) {
                TestSupport.assertEquals("toolsAndAuthOnly", params.get("detail"), "MCP detail");
                TestSupport.assertEquals("thr_fixture", params.get("threadId"), "MCP thread");
                String cursor = JsonCodec.optionalString(params.get("cursor"));
                if (cursor.isEmpty()) {
                    return JsonCodec.object(
                        "data", JsonCodec.array(server("search", true)),
                        "nextCursor", "mcp-page-2"
                    );
                }
                return JsonCodec.object(
                    "data", JsonCodec.array(server("empty", false)),
                    "nextCursor", null
                );
            }
            if ("app/installed".equals(method)) {
                TestSupport.assertEquals(Boolean.FALSE, params.get("forceRefresh"), "no app refresh");
                TestSupport.assertEquals("thr_fixture", params.get("threadId"), "app thread");
                return JsonCodec.object("apps", JsonCodec.array(JsonCodec.object(
                    "id", "connector_fixture",
                    "runtimeName", "Fixture Connector",
                    "enabled", Boolean.TRUE,
                    "callable", Boolean.TRUE
                )));
            }
            if ("app/read".equals(method)) {
                TestSupport.assertEquals(Boolean.TRUE, params.get("includeTools"), "tool details");
                return JsonCodec.object(
                    "apps", JsonCodec.array(JsonCodec.object(
                        "id", "connector_fixture",
                        "name", "Fixture Connector",
                        "toolSummaries", JsonCodec.array(JsonCodec.object(
                            "name", "connector_lookup",
                            "title", "Connector lookup",
                            "description", "Looks up fixture records.",
                            "isEnabled", Boolean.TRUE,
                            "isReadOnly", Boolean.TRUE,
                            "disabledReason", null
                        ))
                    )),
                    "missingAppIds", JsonCodec.array()
                );
            }
            if ("plugin/list".equals(method)) {
                pluginWorkspace = firstCwd(params);
                TestSupport.assertEquals(Boolean.FALSE, params.get("forceRefetch"), "no remote refresh");
                TestSupport.assertEquals(
                    "local",
                    JsonCodec.requireArray(params.get("marketplaceKinds"), "marketplace kinds").get(0),
                    "local marketplace inventory"
                );
                if (failPlugins) {
                    throw new IllegalStateException(
                        "private-marketplace-path /private/home/.agents/plugins/marketplace.json"
                    );
                }
                return pluginResponse();
            }
            throw new IllegalArgumentException("Unexpected catalog method: " + method);
        }

        private boolean containsMethodPrefix(String prefix) {
            for (String method : methods) {
                if (method.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private static String firstCwd(Map<String, Object> params) {
            List<Object> values = JsonCodec.requireArray(params.get("cwds"), "cwds");
            return JsonCodec.requireString(values.get(0), "cwd");
        }

        private static Map<String, Object> feature(
            String name,
            String displayName,
            boolean enabled
        ) {
            return JsonCodec.object(
                "name", name,
                "displayName", displayName,
                "description", "Fixture feature",
                "stage", "underDevelopment",
                "enabled", Boolean.valueOf(enabled),
                "defaultEnabled", Boolean.FALSE
            );
        }

        private static Map<String, Object> skill(int index) {
            String name = index == 0 ? "fixture-skill" : "fixture-skill-" + index;
            return JsonCodec.object(
                "name", name,
                "description", "Fixture skill description",
                "shortDescription", "Short fixture skill",
                "enabled", Boolean.TRUE,
                "scope", "system",
                "path", "/private/codex-home/skills/" + name + "/SKILL.md",
                "interface", JsonCodec.object(
                    "displayName", index == 0 ? "Fixture Skill" : name,
                    "shortDescription", "Safe skill summary"
                ),
                "dependencies", JsonCodec.object("tools", JsonCodec.array(JsonCodec.object(
                    "type", "mcp",
                    "value", "fixture"
                )))
            );
        }

        private static Map<String, Object> server(String name, boolean includeTool) {
            Map<String, Object> tools = new LinkedHashMap<String, Object>();
            if (includeTool) {
                tools.put("search", JsonCodec.object(
                    "name", "search",
                    "title", "Search",
                    "description", "Searches fixture data.",
                    "inputSchema", JsonCodec.object("type", "object")
                ));
            }
            return JsonCodec.object(
                "name", name,
                "authStatus", includeTool ? "oAuth" : "unsupported",
                "serverInfo", JsonCodec.object(
                    "name", name,
                    "title", includeTool ? "Fixture Search" : "Empty Fixture",
                    "version", "1.0",
                    "description", "Fixture MCP server"
                ),
                "tools", tools,
                "resources", JsonCodec.array(),
                "resourceTemplates", JsonCodec.array()
            );
        }

        private static Map<String, Object> pluginResponse() {
            return JsonCodec.object(
                "marketplaces", JsonCodec.array(JsonCodec.object(
                    "name", "fixture-marketplace",
                    "path", "/private/home/.agents/plugins/marketplace.json",
                    "interface", JsonCodec.object("displayName", "Fixture Marketplace"),
                    "plugins", JsonCodec.array(JsonCodec.object(
                        "id", "fixture-marketplace@example",
                        "name", "example",
                        "authPolicy", "ON_USE",
                        "enabled", Boolean.TRUE,
                        "installed", Boolean.TRUE,
                        "installPolicy", "AVAILABLE",
                        "availability", "AVAILABLE",
                        "version", "1.2.3",
                        "localVersion", "1.2.3",
                        "source", JsonCodec.object(
                            "type", "local",
                            "path", "/private/codex-home/plugins/example"
                        ),
                        "interface", JsonCodec.object(
                            "displayName", "Example Plugin",
                            "shortDescription", "Fixture plugin",
                            "capabilities", JsonCodec.array("skills", "mcp"),
                            "screenshots", JsonCodec.array(),
                            "screenshotUrls", JsonCodec.array()
                        )
                    ))
                )),
                "marketplaceLoadErrors", JsonCodec.array()
            );
        }
    }

    /** Serves a catalog whose earlier sections spend a chosen share of the display budget. */
    private static final class AppFixtureRpc implements CodexCatalogRpc {
        private final int burnedCharacters;
        private final List<String> installedIds;
        private final List<String> requestedAppIds = new ArrayList<String>();

        private AppFixtureRpc(int burnedCharacters, List<String> installedIds) {
            this.burnedCharacters = burnedCharacters;
            this.installedIds = installedIds;
        }

        @Override
        public String catalogThreadId() {
            return "thrfixture";
        }

        @Override
        public Map<String, Object> requestCatalog(
            String method,
            Map<String, Object> params,
            long timeoutMilliseconds
        ) {
            if ("experimentalFeature/list".equals(method)) {
                return JsonCodec.object("data", JsonCodec.array(), "nextCursor", null);
            }
            if ("skills/list".equals(method)) {
                return JsonCodec.object("data", JsonCodec.array());
            }
            if ("mcpServerStatus/list".equals(method)) {
                return JsonCodec.object(
                    "data",
                    burnedCharacters <= 0 ? JsonCodec.array() : JsonCodec.array(burningServer()),
                    "nextCursor",
                    null
                );
            }
            if ("app/installed".equals(method)) {
                List<Object> apps = new ArrayList<Object>();
                for (String id : installedIds) {
                    apps.add(JsonCodec.object(
                        "id", id,
                        "runtimeName", "Installed Connector",
                        "enabled", Boolean.TRUE,
                        "callable", Boolean.TRUE
                    ));
                }
                return JsonCodec.object("apps", apps);
            }
            if ("app/read".equals(method)) {
                List<Object> apps = new ArrayList<Object>();
                for (Object id : JsonCodec.requireArray(params.get("appIds"), "app ids")) {
                    String requested = JsonCodec.requireString(id, "app id");
                    requestedAppIds.add(requested);
                    apps.add(JsonCodec.object(
                        "id", requested,
                        "name", "Fixture Connector",
                        "toolSummaries", JsonCodec.array(JsonCodec.object(
                            "name", "connector_lookup",
                            "description", "Looks up fixture records.",
                            "isEnabled", Boolean.TRUE,
                            "isReadOnly", Boolean.TRUE
                        ))
                    ));
                }
                return JsonCodec.object("apps", apps, "missingAppIds", JsonCodec.array());
            }
            if ("plugin/list".equals(method)) {
                return JsonCodec.object(
                    "marketplaces", JsonCodec.array(),
                    "marketplaceLoadErrors", JsonCodec.array()
                );
            }
            throw new IllegalArgumentException("Unexpected catalog method: " + method);
        }

        /** One server whose name, auth status and tool texts consume exactly the burn target. */
        private Map<String, Object> burningServer() {
            int remaining = burnedCharacters - 2;
            Map<String, Object> tools = new LinkedHashMap<String, Object>();
            int index = 0;
            while (remaining > 0) {
                int name = Math.min(160, remaining);
                remaining -= name;
                int title = Math.min(160, remaining);
                remaining -= title;
                int description = Math.min(320, remaining);
                remaining -= description;
                Map<String, Object> tool = JsonCodec.object("name", repeated('n', name));
                if (title > 0) {
                    tool.put("title", repeated('t', title));
                }
                if (description > 0) {
                    tool.put("description", repeated('d', description));
                }
                tools.put("tool-" + index, tool);
                index++;
            }
            return JsonCodec.object(
                "name", "s",
                "authStatus", "a",
                "tools", tools,
                "resources", JsonCodec.array(),
                "resourceTemplates", JsonCodec.array()
            );
        }

        private static String repeated(char character, int count) {
            StringBuilder value = new StringBuilder(count);
            for (int index = 0; index < count; index++) {
                value.append(character);
            }
            return value.toString();
        }
    }
}
