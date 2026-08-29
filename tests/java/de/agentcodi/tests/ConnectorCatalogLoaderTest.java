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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ConnectorCatalogLoaderTest {
    private ConnectorCatalogLoaderTest() {
    }

    public static int run() throws Exception {
        projectsOnlyHostedGmailAndGitHub();
        rejectsUntrustedInstallTargets();
        validatesBoundedTransientSelections();
        selectsOnlyARefreshedCallableConnection();
        refreshesAndChecksCallableSelections();
        publishesSignInBeforeRuntimeVerification();
        preservesEssentialStateWhenOptionalDetailsFail();
        preservesTrustedSignInWhenRuntimeVerificationFails();
        optionalDetailsNeverBlockRuntimeRefresh();
        refreshesOnlyRuntimeAvailabilityAfterDiscovery();
        failedDirectoryCannotBeLaunderedByRuntimeRefresh();
        return 11;
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
        for (int index = 0; index < rpc.methods.size(); index++) {
            String method = rpc.methods.get(index);
            long timeout = rpc.timeouts.get(index).longValue();
            if ("app/list".equals(method)) {
                TestSupport.assertTrue(
                    timeout <= 8_000L,
                    "directory pages share one short global timeout budget"
                );
            } else if ("app/installed".equals(method)) {
                TestSupport.assertEquals(
                    Long.valueOf(6_000L),
                    Long.valueOf(timeout),
                    "runtime availability uses its focused timeout"
                );
            } else if ("app/read".equals(method)) {
                TestSupport.assertEquals(
                    Long.valueOf(3_000L),
                    Long.valueOf(timeout),
                    "optional display details use the shortest timeout"
                );
            }
        }
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

    private static void selectsOnlyARefreshedCallableConnection() {
        ConnectorSelection gmail = new ConnectorSelection(
            ConnectorProvider.GMAIL,
            "gmail",
            "Gmail"
        );
        ConnectorInfo callableGithub = new ConnectorInfo(
            ConnectorProvider.GITHUB,
            "github",
            "GitHub",
            "Repository tools.",
            "https://chatgpt.com/apps/github/github",
            true,
            true,
            true,
            true,
            true,
            3
        );
        List<ConnectorSelection> selected = ConnectorSelection.afterSuccessfulConnection(
            Collections.singletonList(gmail),
            ConnectorProvider.GITHUB,
            callableGithub
        );
        TestSupport.assertEquals(
            Arrays.asList(gmail, callableGithub.selection()),
            selected,
            "a freshly callable browser connection is selected in stable provider order"
        );
        TestSupport.expectThrows(
            UnsupportedOperationException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    selected.clear();
                }
            },
            "automatic connector selection remains immutable"
        );

        ConnectorInfo unavailableGithub = new ConnectorInfo(
            ConnectorProvider.GITHUB,
            "github",
            "GitHub",
            "Repository tools.",
            "https://chatgpt.com/apps/github/github",
            true,
            false,
            true,
            false,
            false,
            3
        );
        TestSupport.assertEquals(
            Collections.singletonList(gmail),
            ConnectorSelection.afterSuccessfulConnection(
                Collections.singletonList(gmail),
                ConnectorProvider.GITHUB,
                unavailableGithub
            ),
            "returning from the browser cannot select an unconfirmed connection"
        );
        TestSupport.assertEquals(
            Collections.singletonList(gmail),
            ConnectorSelection.afterSuccessfulConnection(
                Collections.singletonList(gmail),
                ConnectorProvider.GMAIL,
                callableGithub
            ),
            "a refreshed connector cannot satisfy another provider's connection attempt"
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

    private static void publishesSignInBeforeRuntimeVerification() throws Exception {
        FixtureRpc rpc = new FixtureRpc(false);
        rpc.blockInstalled = true;
        ConnectorCatalogController controller = new ConnectorCatalogController(rpc);
        TestSupport.assertTrue(
            controller.refresh(false, false),
            "staged connector refresh starts"
        );
        TestSupport.assertTrue(
            rpc.installedStarted.await(2L, TimeUnit.SECONDS),
            "runtime verification starts in parallel with directory discovery"
        );
        waitForOffered(controller, ConnectorProvider.GMAIL, 2_000L);
        ConnectorCatalogSnapshot directory = controller.snapshot();
        ConnectorInfo gmail = directory.find(ConnectorProvider.GMAIL);
        TestSupport.assertEquals(
            ConnectorPhase.LOADING,
            directory.getPhase(),
            "runtime verification remains visibly in progress"
        );
        TestSupport.assertTrue(gmail.isOffered(), "Gmail is published from the directory early");
        TestSupport.assertTrue(
            gmail.hasTrustedInstallUrl(),
            "secure sign-in is available before runtime verification finishes"
        );
        TestSupport.assertFalse(
            gmail.isCallable(),
            "directory metadata alone never authorizes Codex use"
        );
        rpc.releaseInstalled.countDown();
        waitForSettled(controller, 3_000L);
        TestSupport.assertTrue(
            controller.snapshot().find(ConnectorProvider.GMAIL).isCallable(),
            "runtime verification still authorizes the exact callable connector"
        );
        controller.close();
    }

    private static void preservesEssentialStateWhenOptionalDetailsFail() {
        FixtureRpc rpc = new FixtureRpc(false);
        rpc.failDetails = true;
        ConnectorCatalogSnapshot snapshot = new ConnectorCatalogLoader(rpc).load(9L, false);
        ConnectorInfo gmail = snapshot.find(ConnectorProvider.GMAIL);
        TestSupport.assertEquals(
            ConnectorPhase.READY,
            snapshot.getPhase(),
            "optional display-detail failure does not degrade essential state"
        );
        TestSupport.assertTrue(gmail.isCallable(), "callability survives display-detail failure");
        TestSupport.assertTrue(
            gmail.hasTrustedInstallUrl(),
            "directory sign-in URL survives display-detail failure"
        );
        TestSupport.assertEquals(
            Integer.valueOf(0),
            Integer.valueOf(gmail.getToolCount()),
            "failed optional details do not synthesize tool metadata"
        );
    }

    private static void preservesTrustedSignInWhenRuntimeVerificationFails() {
        FixtureRpc rpc = new FixtureRpc(false);
        rpc.failInstalled = true;
        ConnectorCatalogSnapshot snapshot = new ConnectorCatalogLoader(rpc).load(10L, false);
        ConnectorInfo gmail = snapshot.find(ConnectorProvider.GMAIL);
        TestSupport.assertEquals(
            ConnectorPhase.PARTIAL,
            snapshot.getPhase(),
            "runtime verification failure is explicit"
        );
        TestSupport.assertTrue(gmail.isOffered(), "public Gmail directory data is retained");
        TestSupport.assertTrue(
            gmail.hasTrustedInstallUrl(),
            "runtime verification failure does not remove secure sign-in"
        );
        TestSupport.assertFalse(
            gmail.isCallable(),
            "failed runtime verification remains fail-closed for Codex use"
        );
    }

    private static void refreshesOnlyRuntimeAvailabilityAfterDiscovery() throws Exception {
        FixtureRpc rpc = new FixtureRpc(false);
        ConnectorCatalogController controller = new ConnectorCatalogController(rpc);
        TestSupport.assertTrue(controller.refresh(false, false), "initial discovery starts");
        waitForSettled(controller, 3_000L);
        waitForToolDetails(controller, 2_000L);
        rpc.clearCalls();
        rpc.gmailCallable = false;
        long previousRevision = controller.snapshot().getRevision();
        TestSupport.assertTrue(
            controller.refreshInstalled(true),
            "focused runtime refresh starts from cached directory data"
        );
        waitForRevision(controller, previousRevision, 3_000L);
        List<String> methods = rpc.methodsSnapshot();
        TestSupport.assertEquals(
            Collections.singletonList("app/installed"),
            methods,
            "post-browser checks skip directory paging and optional details"
        );
        TestSupport.assertEquals(
            Boolean.TRUE,
            rpc.parametersSnapshot().get(0).get("forceRefresh"),
            "post-browser runtime state bypasses its cache"
        );
        ConnectorInfo gmail = controller.snapshot().find(ConnectorProvider.GMAIL);
        TestSupport.assertTrue(
            gmail.hasTrustedInstallUrl(),
            "focused runtime refresh preserves the sign-in action"
        );
        TestSupport.assertFalse(
            gmail.isCallable(),
            "focused runtime refresh applies the latest callable state"
        );
        controller.close();
    }

    private static void optionalDetailsNeverBlockRuntimeRefresh() throws Exception {
        FixtureRpc rpc = new FixtureRpc(false);
        rpc.blockDetails = true;
        ConnectorCatalogController controller = new ConnectorCatalogController(rpc);
        TestSupport.assertTrue(controller.refresh(false, false), "initial discovery starts");
        waitForSettled(controller, 3_000L);
        TestSupport.assertTrue(
            rpc.detailsStarted.await(2L, TimeUnit.SECONDS),
            "optional display details start independently"
        );
        rpc.clearCalls();
        rpc.gmailCallable = false;
        long previousRevision = controller.snapshot().getRevision();
        TestSupport.assertTrue(
            controller.refreshInstalled(true),
            "runtime refresh starts while optional details are blocked"
        );
        waitForRevision(controller, previousRevision, 1_000L);
        TestSupport.assertEquals(
            Collections.singletonList("app/installed"),
            rpc.methodsSnapshot(),
            "blocked optional details do not delay or duplicate runtime refresh"
        );
        TestSupport.assertFalse(
            controller.snapshot().find(ConnectorProvider.GMAIL).isCallable(),
            "independent runtime refresh publishes before optional details finish"
        );
        rpc.releaseDetails.countDown();
        controller.close();
    }

    private static void failedDirectoryCannotBeLaunderedByRuntimeRefresh()
        throws Exception {
        FixtureRpc rpc = new FixtureRpc(false);
        ConnectorCatalogController controller = new ConnectorCatalogController(rpc);
        TestSupport.assertTrue(
            controller.refresh(false, false),
            "initial verified connector discovery starts"
        );
        waitForSettled(controller, 3_000L);
        ConnectorInfo verifiedGmail = controller.snapshot().find(ConnectorProvider.GMAIL);
        TestSupport.assertTrue(
            controller.snapshot().hasReusableDirectoryState(),
            "successful directory state is reusable"
        );
        TestSupport.assertTrue(verifiedGmail.isCallable(), "initial Gmail state is callable");

        rpc.failDirectory = true;
        long verifiedRevision = controller.snapshot().getRevision();
        TestSupport.assertTrue(
            controller.refresh(true, true),
            "failing forced directory verification starts"
        );
        waitForRevision(controller, verifiedRevision, 3_000L);
        ConnectorCatalogSnapshot failed = controller.snapshot();
        ConnectorInfo retainedGmail = failed.find(ConnectorProvider.GMAIL);
        TestSupport.assertEquals(
            ConnectorPhase.FAILED,
            failed.getPhase(),
            "failed app/list remains an explicit failed catalog"
        );
        TestSupport.assertTrue(
            retainedGmail.isOffered() && retainedGmail.hasTrustedInstallUrl(),
            "failed catalog may retain bounded public display metadata"
        );
        TestSupport.assertFalse(
            failed.hasReusableDirectoryState(),
            "failed display metadata is not reusable directory proof"
        );
        TestSupport.assertFalse(
            retainedGmail.isCallable(),
            "failed directory verification clears callability"
        );

        rpc.clearCalls();
        long failedRevision = failed.getRevision();
        TestSupport.assertFalse(
            controller.refreshInstalled(true),
            "app/installed alone cannot reuse a failed directory snapshot"
        );
        TestSupport.assertEquals(
            Collections.emptyList(),
            rpc.methodsSnapshot(),
            "rejected runtime-only refresh sends no catalog RPC"
        );
        TestSupport.assertEquals(
            Long.valueOf(failedRevision),
            Long.valueOf(controller.snapshot().getRevision()),
            "rejected runtime-only refresh cannot create a READY revision"
        );
        TestSupport.assertEquals(
            ConnectorPhase.FAILED,
            controller.snapshot().getPhase(),
            "failed catalog cannot be laundered to READY"
        );

        rpc.failDirectory = false;
        TestSupport.assertTrue(
            controller.refresh(true, true),
            "full directory and runtime revalidation can recover"
        );
        waitForRevision(controller, failedRevision, 3_000L);
        TestSupport.assertEquals(
            ConnectorPhase.READY,
            controller.snapshot().getPhase(),
            "successful app/list and app/installed restore READY"
        );
        TestSupport.assertTrue(
            controller.snapshot().find(ConnectorProvider.GMAIL).isCallable(),
            "successful full revalidation preserves connector functionality"
        );
        controller.close();
    }

    private static void waitForSettled(
        ConnectorCatalogController controller,
        long timeoutMilliseconds
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMilliseconds;
        while (controller.snapshot().getPhase() == ConnectorPhase.LOADING
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertFalse(
            controller.snapshot().getPhase() == ConnectorPhase.LOADING,
            "connector refresh settles within the test timeout"
        );
    }

    private static void waitForOffered(
        ConnectorCatalogController controller,
        ConnectorProvider provider,
        long timeoutMilliseconds
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMilliseconds;
        while (!controller.snapshot().find(provider).isOffered()
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertTrue(
            controller.snapshot().find(provider).isOffered(),
            "connector directory state publishes before runtime verification settles"
        );
    }

    private static void waitForRevision(
        ConnectorCatalogController controller,
        long previousRevision,
        long timeoutMilliseconds
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMilliseconds;
        while ((controller.snapshot().getRevision() <= previousRevision
                || controller.snapshot().getPhase() == ConnectorPhase.LOADING)
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertTrue(
            controller.snapshot().getRevision() > previousRevision
                && controller.snapshot().getPhase() != ConnectorPhase.LOADING,
            "focused connector refresh settles on a newer revision"
        );
    }

    private static void waitForToolDetails(
        ConnectorCatalogController controller,
        long timeoutMilliseconds
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMilliseconds;
        while (controller.snapshot().find(ConnectorProvider.GMAIL).getToolCount() != 2
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(controller.snapshot()
                .find(ConnectorProvider.GMAIL).getToolCount()),
            "optional connector details eventually publish"
        );
    }

    private static final class FixtureRpc implements CodexCatalogRpc {
        private final boolean untrustedGithubUrl;
        private String threadId = "thr_fixture";
        private volatile boolean blockInstalled;
        private volatile boolean failDirectory;
        private volatile boolean failInstalled;
        private volatile boolean failDetails;
        private volatile boolean blockDetails;
        private volatile boolean gmailCallable = true;
        private final CountDownLatch installedStarted = new CountDownLatch(1);
        private final CountDownLatch releaseInstalled = new CountDownLatch(1);
        private final CountDownLatch detailsStarted = new CountDownLatch(1);
        private final CountDownLatch releaseDetails = new CountDownLatch(1);
        private final List<String> methods = Collections.synchronizedList(
            new ArrayList<String>()
        );
        private final List<Map<String, Object>> parameters =
            Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        private final List<Long> timeouts = Collections.synchronizedList(
            new ArrayList<Long>()
        );

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
        ) throws Exception {
            methods.add(method);
            parameters.add(params);
            timeouts.add(Long.valueOf(timeoutMilliseconds));
            TestSupport.assertTrue(timeoutMilliseconds > 0L, "finite connector RPC timeout");
            if ("app/list".equals(method)) {
                if (failDirectory) {
                    throw new IllegalStateException("Directory fixture failure");
                }
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
                installedStarted.countDown();
                if (blockInstalled) {
                    if (!releaseInstalled.await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for installed fixture");
                    }
                }
                if (failInstalled) {
                    throw new IllegalStateException("Installed fixture failure");
                }
                return JsonCodec.object("apps", JsonCodec.array(
                    JsonCodec.object(
                        "id", "gmail",
                        "runtimeName", "Gmail",
                        "enabled", Boolean.TRUE,
                        "callable", Boolean.valueOf(gmailCallable)
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
                detailsStarted.countDown();
                if (blockDetails) {
                    if (!releaseDetails.await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for details fixture");
                    }
                }
                if (failDetails) {
                    throw new IllegalStateException("Details fixture failure");
                }
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

        private void clearCalls() {
            methods.clear();
            parameters.clear();
            timeouts.clear();
        }

        private List<String> methodsSnapshot() {
            synchronized (methods) {
                return new ArrayList<String>(methods);
            }
        }

        private List<Map<String, Object>> parametersSnapshot() {
            synchronized (parameters) {
                return new ArrayList<Map<String, Object>>(parameters);
            }
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
