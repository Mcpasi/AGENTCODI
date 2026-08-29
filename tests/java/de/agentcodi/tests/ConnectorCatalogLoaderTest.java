package de.agentcodi.tests;

import de.agentcodi.connectors.ConnectorCatalogSnapshot;
import de.agentcodi.connectors.ConnectorInfo;
import de.agentcodi.connectors.ConnectorInstallUrl;
import de.agentcodi.connectors.ConnectorPhase;
import de.agentcodi.connectors.ConnectorProvider;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.connectors.client.ConnectorCatalogController;
import de.agentcodi.connectors.client.ConnectorCatalogLoader;
import de.agentcodi.core.CodexCatalogRpc;
import de.agentcodi.core.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ConnectorCatalogLoaderTest {
    private ConnectorCatalogLoaderTest() {
    }

    public static int run() throws Exception {
        projectsOnlyHostedGmailAndGitHub();
        rejectsUntrustedInstallTargets();
        validatesBoundedTransientSelections();
        refreshesAndChecksCallableSelections();
        return 4;
    }

    private static void projectsOnlyHostedGmailAndGitHub() {
        FixtureRpc rpc = new FixtureRpc(false);
        ConnectorCatalogSnapshot snapshot = new ConnectorCatalogLoader(rpc).load(7L, true);
        TestSupport.assertEquals(ConnectorPhase.READY, snapshot.getPhase(), "catalog phase");
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(snapshot.getConnectors().size()),
            "only requested connector providers are projected"
        );

        ConnectorInfo gmail = snapshot.find(ConnectorProvider.GMAIL);
        TestSupport.assertEquals("gmail", gmail.getId(), "Gmail id from app directory");
        TestSupport.assertEquals("Gmail", gmail.getName(), "Gmail display name");
        TestSupport.assertTrue(gmail.isAccessible(), "Gmail account access");
        TestSupport.assertTrue(gmail.isInstalled(), "Gmail runtime installed");
        TestSupport.assertTrue(gmail.isCallable(), "Gmail runtime callable");
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(gmail.getToolCount()), "Gmail tools");
        TestSupport.assertTrue(gmail.hasTrustedInstallUrl(), "Gmail management URL retained");

        ConnectorInfo github = snapshot.find(ConnectorProvider.GITHUB);
        TestSupport.assertEquals("github", github.getId(), "GitHub id from second page");
        TestSupport.assertFalse(github.isAccessible(), "unlinked GitHub is not accessible");
        TestSupport.assertTrue(github.isInstalled(), "GitHub can exist in runtime snapshot");
        TestSupport.assertFalse(github.isCallable(), "unlinked GitHub cannot be selected");
        TestSupport.assertTrue(github.hasTrustedInstallUrl(), "GitHub hosted install URL retained");

        TestSupport.assertEquals(
            Arrays.asList("app/list", "app/list", "app/installed", "app/read"),
            rpc.methods,
            "connector discovery uses only app-server Apps catalog RPCs"
        );
        for (String method : rpc.methods) {
            TestSupport.assertFalse(
                method.contains("oauth") || method.contains("tool/call")
                    || method.startsWith("config/") || method.startsWith("plugin/"),
                "connector projection does not own auth, tools, config, or plugins"
            );
        }
        for (int index = 0; index < 3; index++) {
            TestSupport.assertEquals(
                "thr_fixture",
                rpc.parameters.get(index).get("threadId"),
                "thread-scoped app catalog"
            );
        }
        TestSupport.assertFalse(
            rpc.parameters.get(3).containsKey("threadId"),
            "app/read remains within its pinned schema"
        );
        TestSupport.assertEquals(Boolean.TRUE, rpc.parameters.get(0).get("forceRefetch"), "forced directory refresh");
        TestSupport.assertEquals(Boolean.TRUE, rpc.parameters.get(2).get("forceRefresh"), "forced runtime refresh");
    }

    private static void rejectsUntrustedInstallTargets() {
        FixtureRpc rpc = new FixtureRpc(true);
        ConnectorCatalogSnapshot snapshot = new ConnectorCatalogLoader(rpc).load(8L, false);
        TestSupport.assertFalse(
            snapshot.find(ConnectorProvider.GITHUB).hasTrustedInstallUrl(),
            "lookalike ChatGPT host is discarded"
        );
        TestSupport.assertTrue(
            ConnectorInstallUrl.isTrusted("https://chatgpt.com/apps/gmail/gmail"),
            "ChatGPT HTTPS install page accepted"
        );
        TestSupport.assertFalse(
            ConnectorInstallUrl.isTrusted("https://chatgpt.com.evil.example/apps/gmail"),
            "suffix-confusion host rejected"
        );
        TestSupport.assertFalse(
            ConnectorInstallUrl.isTrusted("http://chatgpt.com/apps/gmail"),
            "cleartext install page rejected"
        );
    }

    private static void validatesBoundedTransientSelections() {
        final ConnectorSelection gmail = new ConnectorSelection(
            ConnectorProvider.GMAIL,
            "gmail",
            "Gmail"
        );
        ConnectorSelection github = new ConnectorSelection(
            ConnectorProvider.GITHUB,
            "github",
            "GitHub"
        );
        TestSupport.assertEquals(
            Arrays.asList(gmail, github),
            ConnectorSelection.copyOf(Arrays.asList(gmail, github)),
            "bounded selection order is preserved"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    ConnectorSelection.copyOf(Arrays.asList(gmail, gmail));
                }
            },
            "duplicate providers are rejected"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    new ConnectorSelection(ConnectorProvider.GMAIL, "../gmail", "Gmail");
                }
            },
            "connector ids cannot become arbitrary mention paths"
        );
    }

    private static void refreshesAndChecksCallableSelections() throws Exception {
        FixtureRpc rpc = new FixtureRpc(false);
        ConnectorCatalogController controller = new ConnectorCatalogController(rpc);
        TestSupport.assertTrue(controller.refresh(false), "connector refresh starts");
        long deadline = System.currentTimeMillis() + 3_000L;
        while (controller.snapshot().getPhase() == ConnectorPhase.LOADING
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        ConnectorInfo gmail = controller.snapshot().find(ConnectorProvider.GMAIL);
        ConnectorInfo github = controller.snapshot().find(ConnectorProvider.GITHUB);
        TestSupport.assertTrue(
            controller.areCallable(Collections.singletonList(gmail.selection())),
            "current callable selection is accepted"
        );
        TestSupport.assertFalse(
            github.isCallable(),
            "GitHub fixture remains unavailable until hosted connection completes"
        );
        TestSupport.assertFalse(
            controller.areCallable(Collections.singletonList(new ConnectorSelection(
                ConnectorProvider.GMAIL,
                "different-gmail",
                "Gmail"
            ))),
            "stale connector id is rejected before send"
        );
        rpc.threadId = "thr_other";
        TestSupport.assertFalse(
            controller.areCallable(Collections.singletonList(gmail.selection())),
            "a connector snapshot from another thread cannot authorize a send"
        );
        controller.close();
        TestSupport.assertEquals(ConnectorPhase.STOPPED, controller.snapshot().getPhase(), "controller closes");
        TestSupport.assertFalse(
            controller.areCallable(Collections.singletonList(gmail.selection())),
            "a stopped catalog cannot authorize a stale selection"
        );
    }

    private static final class FixtureRpc implements CodexCatalogRpc {
        private final boolean untrustedGithubUrl;
        private String threadId = "thr_fixture";
        private final List<String> methods = new ArrayList<String>();
        private final List<Map<String, Object>> parameters =
            new ArrayList<Map<String, Object>>();

        private FixtureRpc(boolean untrustedGithubUrl) {
            this.untrustedGithubUrl = untrustedGithubUrl;
        }

        @Override
        public String catalogThreadId() {
            return threadId;
        }

        @Override
        public Map<String, Object> requestCatalog(
            String method,
            Map<String, Object> params,
            long timeoutMilliseconds
        ) {
            methods.add(method);
            parameters.add(params);
            TestSupport.assertTrue(timeoutMilliseconds > 0L, "finite connector RPC timeout");
            if ("app/list".equals(method)) {
                if ("page-2".equals(JsonCodec.optionalString(params.get("cursor")))) {
                    return JsonCodec.object(
                        "data", JsonCodec.array(app(
                            "github",
                            "GitHub",
                            "Repository and issue tools.",
                            untrustedGithubUrl
                                ? "https://chatgpt.com.evil.example/apps/github"
                                : "https://chatgpt.com/apps/github/github",
                            false,
                            true
                        )),
                        "nextCursor", null
                    );
                }
                return JsonCodec.object(
                    "data", JsonCodec.array(
                        app(
                            "gmail",
                            "Gmail",
                            "Mail search and drafting tools.",
                            "https://chatgpt.com/apps/gmail/gmail?source=agentcodi",
                            true,
                            true
                        ),
                        app(
                            "google-drive",
                            "Google Drive",
                            "Unrelated connector.",
                            "https://chatgpt.com/apps/google-drive/google-drive",
                            true,
                            true
                        ),
                        app(
                            "notgithub",
                            "GitHub",
                            "A deceptive suffix match that must be ignored.",
                            "https://chatgpt.com/apps/example/notgithub",
                            true,
                            true
                        )
                    ),
                    "nextCursor", "page-2"
                );
            }
            if ("app/installed".equals(method)) {
                return JsonCodec.object("apps", JsonCodec.array(
                    JsonCodec.object(
                        "id", "gmail",
                        "runtimeName", "Gmail",
                        "enabled", Boolean.TRUE,
                        "callable", Boolean.TRUE
                    ),
                    JsonCodec.object(
                        "id", "github",
                        "runtimeName", "GitHub",
                        "enabled", Boolean.TRUE,
                        "callable", Boolean.TRUE
                    )
                ));
            }
            if ("app/read".equals(method)) {
                TestSupport.assertFalse(
                    params.containsKey("threadId"),
                    "app/read follows its pinned schema without a thread id"
                );
                return JsonCodec.object(
                    "apps", JsonCodec.array(
                        details("gmail", "Gmail", 2),
                        details(
                            "github",
                            "GitHub",
                            3,
                            untrustedGithubUrl
                                ? "https://chatgpt.com.evil.example/apps/github"
                                : "https://chatgpt.com/apps/github/github"
                        )
                    ),
                    "missingAppIds", JsonCodec.array()
                );
            }
            throw new AssertionError("Unexpected connector RPC: " + method);
        }

        private static Map<String, Object> app(
            String id,
            String name,
            String description,
            String installUrl,
            boolean accessible,
            boolean enabled
        ) {
            return JsonCodec.object(
                "id", id,
                "name", name,
                "description", description,
                "installUrl", installUrl,
                "isAccessible", Boolean.valueOf(accessible),
                "isEnabled", Boolean.valueOf(enabled),
                "pluginDisplayNames", JsonCodec.array(name),
                "labels", JsonCodec.object("provider", name)
            );
        }

        private static Map<String, Object> details(String id, String name, int tools) {
            return details(id, name, tools, "https://chatgpt.com/apps/" + id + "/" + id);
        }

        private static Map<String, Object> details(
            String id,
            String name,
            int tools,
            String installUrl
        ) {
            List<Object> summaries = new ArrayList<Object>();
            for (int index = 0; index < tools; index++) {
                summaries.add(JsonCodec.object(
                    "name", "tool_" + index,
                    "description", "Bounded connector tool " + index + ".",
                    "isEnabled", Boolean.TRUE,
                    "isReadOnly", Boolean.valueOf(index % 2 == 0)
                ));
            }
            return JsonCodec.object(
                "id", id,
                "name", name,
                "description", name + " connector metadata.",
                "installUrl", installUrl,
                "pluginDisplayNames", JsonCodec.array(name),
                "toolSummaries", summaries
            );
        }
    }
}
