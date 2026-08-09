package de.agentcodi.tests;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public final class CodexSessionControllerTest {
    private CodexSessionControllerTest() {
    }

    public static int run() throws Exception {
        loadsAccountThreadsAndHistory();
        mergesStreamingDeltasAndFinalItem();
        keepsCompletedItemAuthoritativeAcrossReordering();
        keepsApiKeyOutOfSnapshotsAndWipesCallerBuffer();
        acceptsOnlyTrustedBrowserLoginUrl();
        usesAdvertisedModelEffortAndPermissionProfile();
        rejectsUnavailableWorkspacePermissionProfile();
        return 7;
    }

    private static void loadsAccountThreadsAndHistory() throws Exception {
        FixtureServer server = new FixtureServer(true);
        CodexSessionController controller = new CodexSessionController(server, "/private/workspace");
        controller.start();
        CodexSessionSnapshot started = controller.snapshot();
        TestSupport.assertTrue(started.isReady(), "controller ready");
        TestSupport.assertTrue(started.isSignedIn(), "account loaded");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(started.getThreads().size()), "threads");

        controller.openThread("thr_existing");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_existing".equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "thread resume");
        List<ChatMessage> history = controller.snapshot().getMessages();
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(history.size()), "history size");
        TestSupport.assertEquals(ChatMessage.Role.USER, history.get(0).getRole(), "user history");
        TestSupport.assertEquals("Historische Antwort", history.get(1).getText(), "assistant history");
        assertHttpModelProvider(server.lastThreadResumeParams, "thread/resume");

        controller.startNewThread();
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_new".equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "new HTTPS-provider thread");
        assertHttpModelProvider(server.lastThreadStartParams, "thread/start");
        controller.close();
    }

    private static void mergesStreamingDeltasAndFinalItem() throws Exception {
        FixtureServer server = new FixtureServer(true);
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        controller.openThread("thr_existing");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_existing".equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "thread ready for streaming");
        controller.sendMessage("Neue Aufgabe");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexSessionSnapshot snapshot = controller.snapshot();
                return !snapshot.isOperationActive()
                    && !snapshot.isTurnActive()
                    && containsMessage(snapshot, "Hallo Welt 🌍");
            }
        }, "stream completion");
        TestSupport.assertTrue(
            containsMessage(controller.snapshot(), "Neue Aufgabe"),
            "optimistic user message retained"
        );
        controller.close();
    }

    private static void keepsApiKeyOutOfSnapshotsAndWipesCallerBuffer() throws Exception {
        FixtureServer server = new FixtureServer(false);
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        final char[] transientValue = "temporary-test-value".toCharArray();
        controller.startApiKeyLogin(transientValue);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().isSignedIn()
                    && !controller.snapshot().isOperationActive();
            }
        }, "API key login");
        for (char character : transientValue) {
            TestSupport.assertEquals(Character.valueOf('\0'), Character.valueOf(character), "key wipe");
        }
        CodexSessionSnapshot snapshot = controller.snapshot();
        String visible = snapshot.getConnectionMessage()
            + snapshot.getOperationMessage()
            + snapshot.getErrorMessage()
            + snapshot.getAccountEmail();
        TestSupport.assertFalse(visible.contains("temporary-test-value"), "key absent from state");
        TestSupport.assertEquals("apiKey", snapshot.getAuthMode(), "API key auth mode");
        controller.close();
    }

    private static void keepsCompletedItemAuthoritativeAcrossReordering() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.reorderStreamingEvents = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        controller.openThread("thr_existing");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_existing".equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "thread ready for reordered streaming");
        controller.sendMessage("Reihenfolge testen");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexSessionSnapshot snapshot = controller.snapshot();
                ChatMessage message = messageById(snapshot, "assistant_fixture");
                return !snapshot.isOperationActive()
                    && !snapshot.isTurnActive()
                    && message != null
                    && "Autoritative Antwort".equals(message.getText());
            }
        }, "reordered stream completion");
        ChatMessage assistant = messageById(
            controller.snapshot(),
            "assistant_fixture"
        );
        TestSupport.assertEquals(
            "Autoritative Antwort",
            assistant.getText(),
            "late delta cannot modify completed item"
        );
        TestSupport.assertFalse(
            assistant.isStreaming(),
            "completed item cannot be revived by late item/started"
        );
        TestSupport.assertEquals(
            null,
            messageById(controller.snapshot(), "late_assistant"),
            "late event without turn ID cannot create a new streaming item"
        );
        controller.close();
    }

    private static void acceptsOnlyTrustedBrowserLoginUrl() throws Exception {
        FixtureServer server = new FixtureServer(false);
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        controller.startChatGptLogin();
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return !controller.snapshot().getLoginUrl().isEmpty()
                    && !controller.snapshot().isOperationActive();
            }
        }, "browser login URL");
        TestSupport.assertContains(
            controller.snapshot().getLoginUrl(),
            "https://auth.openai.com/",
            "trusted login host"
        );
        controller.close();
    }

    private static void usesAdvertisedModelEffortAndPermissionProfile() throws Exception {
        FixtureServer server = new FixtureServer(true);
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        CodexSessionSnapshot initial = controller.snapshot();
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(initial.getModels().size()),
            "advertised model count"
        );
        TestSupport.assertEquals("gpt-5.6-sol", initial.getSelectedModelId(), "default model");
        TestSupport.assertEquals("low", initial.getSelectedReasoningEffort(), "default effort");
        CodexModelOption terra = initial.getModels().get(1);
        TestSupport.assertEquals("gpt-5.6-terra", terra.getId(), "second model");
        TestSupport.assertEquals(
            Integer.valueOf(6),
            Integer.valueOf(terra.getReasoningOptions().size()),
            "GPT-5.6 reasoning levels include xhigh, max and ultra"
        );

        controller.openThread("thr_existing");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_existing".equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "thread ready for model selection");
        controller.selectModel("gpt-5.6-terra");
        controller.selectReasoningEffort("ultra");
        TestSupport.assertEquals(
            "gpt-5.6-terra",
            controller.snapshot().getSelectedModelId(),
            "selected model"
        );
        TestSupport.assertEquals(
            "ultra",
            controller.snapshot().getSelectedReasoningEffort(),
            "selected effort"
        );
        controller.sendMessage("Mit ausgewähltem Modell");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTurnStartParams != null
                    && !controller.snapshot().isOperationActive();
            }
        }, "turn request captured");

        Map<String, Object> initialize = server.initializeParams;
        Map<String, Object> capabilities = JsonCodec.requireObject(
            initialize.get("capabilities"),
            "initialize capabilities"
        );
        TestSupport.assertTrue(
            JsonCodec.booleanValue(capabilities.get("experimentalApi"), false),
            "permission profiles enabled during initialize"
        );
        assertWorkspacePermissionRequest(server.lastThreadResumeParams, "thread/resume");
        assertWorkspacePermissionRequest(server.lastTurnStartParams, "turn/start");
        TestSupport.assertEquals(
            "gpt-5.6-terra",
            server.lastTurnStartParams.get("model"),
            "turn model"
        );
        TestSupport.assertEquals(
            "ultra",
            server.lastTurnStartParams.get("effort"),
            "turn effort"
        );
        TestSupport.assertFalse(
            JsonCodec.stringify(server.lastTurnStartParams).contains("readOnlyAccess"),
            "legacy read-only access omitted"
        );
        controller.close();
    }

    private static void rejectsUnavailableWorkspacePermissionProfile() throws Exception {
        final FixtureServer server = new FixtureServer(true, false);
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        TestSupport.expectThrows(
            IllegalStateException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    controller.start();
                }
            },
            "unavailable permission profile fails closed"
        );
        TestSupport.assertFalse(controller.snapshot().isReady(), "failed controller not ready");
        controller.close();
    }

    private static void assertWorkspacePermissionRequest(
        Map<String, Object> params,
        String method
    ) {
        TestSupport.assertTrue(params != null, method + " params captured");
        TestSupport.assertEquals(
            "agentcodi-workspace",
            params.get("permissions"),
            method + " permission profile"
        );
        TestSupport.assertEquals(
            "/private/workspace",
            JsonCodec.optionalArray(params.get("runtimeWorkspaceRoots")).get(0),
            method + " runtime workspace root"
        );
        TestSupport.assertFalse(params.containsKey("sandbox"), method + " legacy sandbox omitted");
        TestSupport.assertFalse(
            params.containsKey("sandboxPolicy"),
            method + " legacy sandbox policy omitted"
        );
    }

    private static void assertHttpModelProvider(Map<String, Object> params, String method) {
        TestSupport.assertTrue(params != null, method + " params captured");
        TestSupport.assertEquals(
            "agentcodi-openai-http",
            params.get("modelProvider"),
            method + " HTTPS model provider"
        );
    }

    private static boolean containsMessage(CodexSessionSnapshot snapshot, String text) {
        for (ChatMessage message : snapshot.getMessages()) {
            if (message.getText().equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static ChatMessage messageById(CodexSessionSnapshot snapshot, String id) {
        for (ChatMessage message : snapshot.getMessages()) {
            if (message.getId().equals(id)) {
                return message;
            }
        }
        return null;
    }

    private static void waitFor(Condition condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!condition.isTrue() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertTrue(condition.isTrue(), message);
    }

    private interface Condition {
        boolean isTrue();
    }

    private static final class FixtureServer implements CodexRpcTransport {
        private static final Object CLOSED = new Object();
        private final LinkedBlockingQueue<Object> incoming = new LinkedBlockingQueue<Object>();
        private final boolean permissionAllowed;
        private volatile boolean signedIn;
        private volatile boolean closed;
        private volatile Map<String, Object> initializeParams;
        private volatile Map<String, Object> lastThreadResumeParams;
        private volatile Map<String, Object> lastThreadStartParams;
        private volatile Map<String, Object> lastTurnStartParams;
        private volatile boolean reorderStreamingEvents;

        private FixtureServer(boolean signedIn) {
            this(signedIn, true);
        }

        private FixtureServer(boolean signedIn, boolean permissionAllowed) {
            this.signedIn = signedIn;
            this.permissionAllowed = permissionAllowed;
        }

        @Override
        public String readLine(int maximumBytes) throws IOException {
            try {
                Object value = incoming.take();
                if (value == CLOSED) {
                    return null;
                }
                String line = (String) value;
                if (line.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
                    throw new IOException("fixture line too large");
                }
                return line;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("fixture interrupted", error);
            }
        }

        @Override
        public void writeLine(String line, int maximumBytes) throws IOException {
            if (closed) {
                throw new IOException("fixture closed");
            }
            Map<String, Object> request = JsonCodec.parseObject(line);
            String method = JsonCodec.optionalString(request.get("method"));
            if (request.get("id") == null) {
                return;
            }
            if ("initialize".equals(method)) {
                initializeParams = JsonCodec.requireObject(request.get("params"), "initialize params");
                respond(request, JsonCodec.object("userAgent", "fixture/1"));
            } else if ("permissionProfile/list".equals(method)) {
                respond(request, JsonCodec.object(
                    "data", JsonCodec.array(JsonCodec.object(
                        "id", "agentcodi-workspace",
                        "description", "Private workspace",
                        "allowed", Boolean.valueOf(permissionAllowed)
                    )),
                    "nextCursor", null
                ));
            } else if ("model/list".equals(method)) {
                respond(request, JsonCodec.object(
                    "data", JsonCodec.array(
                        model("gpt-5.6-sol", "GPT-5.6-Sol", "low", true),
                        model("gpt-5.6-terra", "GPT-5.6-Terra", "medium", false)
                    ),
                    "nextCursor", null
                ));
            } else if ("account/read".equals(method)) {
                respond(request, JsonCodec.object(
                    "account", signedIn
                        ? JsonCodec.object(
                            "type", "apiKey",
                            "email", "",
                            "planType", ""
                        )
                        : null,
                    "requiresOpenaiAuth", Boolean.TRUE
                ));
            } else if ("account/login/start".equals(method)) {
                Map<String, Object> params = JsonCodec.requireObject(request.get("params"), "params");
                if ("apiKey".equals(params.get("type"))) {
                    TestSupport.assertTrue(
                        JsonCodec.optionalString(params.get("apiKey")).length() >= 8,
                        "transient API key reached Codex"
                    );
                    signedIn = true;
                    respond(request, JsonCodec.object("type", "apiKey"));
                } else {
                    respond(request, JsonCodec.object(
                        "type", "chatgpt",
                        "loginId", "login_fixture",
                        "authUrl", "https://auth.openai.com/authorize?fixture=1"
                    ));
                }
            } else if ("thread/list".equals(method)) {
                respond(request, JsonCodec.object(
                    "data", JsonCodec.array(thread("thr_existing", false)),
                    "nextCursor", null
                ));
            } else if ("thread/resume".equals(method)) {
                lastThreadResumeParams = JsonCodec.requireObject(request.get("params"), "params");
                assertHttpModelProvider(lastThreadResumeParams, "fixture thread/resume");
                respond(request, JsonCodec.object(
                    "thread", thread("thr_existing", true),
                    "model", "gpt-5.6-sol",
                    "reasoningEffort", "low",
                    "activePermissionProfile", JsonCodec.object(
                        "id", "agentcodi-workspace",
                        "extends", null
                    )
                ));
            } else if ("thread/start".equals(method)) {
                Map<String, Object> params = JsonCodec.requireObject(request.get("params"), "params");
                lastThreadStartParams = params;
                assertWorkspacePermissionRequest(params, "fixture thread/start");
                assertHttpModelProvider(params, "fixture thread/start");
                respond(request, JsonCodec.object(
                    "thread", thread("thr_new", false),
                    "model", params.get("model"),
                    "reasoningEffort", null,
                    "activePermissionProfile", JsonCodec.object(
                        "id", "agentcodi-workspace",
                        "extends", null
                    )
                ));
            } else if ("turn/start".equals(method)) {
                lastTurnStartParams = JsonCodec.requireObject(request.get("params"), "params");
                respond(request, JsonCodec.object(
                    "turn", JsonCodec.object(
                        "id", "turn_fixture",
                        "status", "inProgress",
                        "items", JsonCodec.array(),
                        "error", null
                    )
                ));
                notifyMessage("turn/started", JsonCodec.object(
                    "threadId", "thr_existing",
                    "turn", JsonCodec.object(
                        "id", "turn_fixture",
                        "status", "inProgress",
                        "items", JsonCodec.array()
                    )
                ));
                if (reorderStreamingEvents) {
                    notifyMessage("item/agentMessage/delta", delta("Entwurf"));
                    notifyMessage("turn/completed", JsonCodec.object(
                        "threadId", "thr_existing",
                        "turn", JsonCodec.object(
                            "id", "turn_fixture",
                            "status", "completed",
                            "items", JsonCodec.array(),
                            "error", null
                        )
                    ));
                    notifyMessage("item/completed", JsonCodec.object(
                        "threadId", "thr_existing",
                        "turnId", "turn_fixture",
                        "item", JsonCodec.object(
                            "id", "assistant_fixture",
                            "type", "agentMessage",
                            "text", "Autoritative Antwort"
                        )
                    ));
                    notifyMessage("item/agentMessage/delta", delta(" VERALTET"));
                    notifyMessage("item/started", JsonCodec.object(
                        "threadId", "thr_existing",
                        "turnId", "turn_fixture",
                        "item", JsonCodec.object(
                            "id", "assistant_fixture",
                            "type", "agentMessage",
                            "text", ""
                        )
                    ));
                    notifyMessage("item/agentMessage/delta", JsonCodec.object(
                        "threadId", "thr_existing",
                        "itemId", "late_assistant",
                        "delta", "Verspätet"
                    ));
                    notifyMessage("item/started", JsonCodec.object(
                        "threadId", "thr_existing",
                        "item", JsonCodec.object(
                            "id", "late_assistant",
                            "type", "agentMessage",
                            "text", "Verspätet"
                        )
                    ));
                    return;
                }
                notifyMessage("item/agentMessage/delta", delta("Hallo "));
                notifyMessage("item/agentMessage/delta", delta("Welt 🌍"));
                notifyMessage("item/completed", JsonCodec.object(
                    "threadId", "thr_existing",
                    "turnId", "turn_fixture",
                    "item", JsonCodec.object(
                        "id", "assistant_fixture",
                        "type", "agentMessage",
                        "text", "Hallo Welt 🌍"
                    )
                ));
                notifyMessage("turn/completed", JsonCodec.object(
                    "threadId", "thr_existing",
                    "turn", JsonCodec.object(
                        "id", "turn_fixture",
                        "status", "completed",
                        "items", JsonCodec.array(),
                        "error", null
                    )
                ));
            } else if ("account/logout".equals(method)
                || "turn/interrupt".equals(method)) {
                respond(request, JsonCodec.object());
            }
        }

        @Override
        public void close() {
            closed = true;
            incoming.offer(CLOSED);
        }

        private void respond(Map<String, Object> request, Map<String, Object> result) {
            incoming.offer(JsonCodec.stringify(JsonCodec.object(
                "id", request.get("id"),
                "result", result
            )));
        }

        private void notifyMessage(String method, Map<String, Object> params) {
            incoming.offer(JsonCodec.stringify(JsonCodec.object("method", method, "params", params)));
        }

        private static Map<String, Object> delta(String value) {
            return JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "assistant_fixture",
                "delta", value
            );
        }

        private static Map<String, Object> thread(String id, boolean includeTurns) {
            List<Object> turns = new ArrayList<Object>();
            if (includeTurns) {
                turns.add(JsonCodec.object(
                    "id", "turn_history",
                    "status", "completed",
                    "items", JsonCodec.array(
                        JsonCodec.object(
                            "id", "user_history",
                            "type", "userMessage",
                            "content", JsonCodec.array(JsonCodec.object(
                                "type", "text",
                                "text", "Historische Aufgabe"
                            ))
                        ),
                        JsonCodec.object(
                            "id", "assistant_history",
                            "type", "agentMessage",
                            "text", "Historische Antwort"
                        )
                    )
                ));
            }
            return JsonCodec.object(
                "id", id,
                "modelProvider", "agentcodi-openai-http",
                "preview", "Historische Aufgabe",
                "updatedAt", Long.valueOf(100L),
                "turns", turns
            );
        }

        private static Map<String, Object> model(
            String id,
            String displayName,
            String defaultEffort,
            boolean isDefault
        ) {
            return JsonCodec.object(
                "id", id,
                "model", id,
                "displayName", displayName,
                "description", "Fixture model",
                "hidden", Boolean.FALSE,
                "isDefault", Boolean.valueOf(isDefault),
                "defaultReasoningEffort", defaultEffort,
                "supportedReasoningEfforts", JsonCodec.array(
                    JsonCodec.object(
                        "reasoningEffort", "low",
                        "description", "Schnell"
                    ),
                    JsonCodec.object(
                        "reasoningEffort", "medium",
                        "description", "Ausgewogen"
                    ),
                    JsonCodec.object(
                        "reasoningEffort", "high",
                        "description", "Tief"
                    ),
                    JsonCodec.object(
                        "reasoningEffort", "xhigh",
                        "description", "Sehr tief"
                    ),
                    JsonCodec.object(
                        "reasoningEffort", "max",
                        "description", "Maximum"
                    ),
                    JsonCodec.object(
                        "reasoningEffort", "ultra",
                        "description", "Ultra mit Delegation"
                    )
                )
            );
        }
    }
}
