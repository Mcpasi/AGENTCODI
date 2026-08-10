package de.agentcodi.tests;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexApprovalDecision;
import de.agentcodi.core.CodexInteractiveRequest;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        handlesCommandAndFileApprovals();
        acceptsFileCreationApproval();
        enrichesFileApprovalAfterReorderedPatchUpdate();
        rejectsIncompleteFileChangePreviews();
        handlesUserInputResolutionAndTimeout();
        rejectsUnavailableWorkspacePermissionProfile();
        return 12;
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

    private static void handlesCommandAndFileApprovals() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.requestFromServer(
            700L,
            "item/commandExecution/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "command_fixture",
                "startedAtMs", Long.valueOf(1L),
                "reason", "Tests ausführen",
                "command", "./scripts/test.sh",
                "cwd", "/private/workspace",
                "proposedExecpolicyAmendment", JsonCodec.array("./scripts/test.sh")
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "command approval projected");
        CodexInteractiveRequest command = controller.snapshot().getInteractiveRequests().get(0);
        TestSupport.assertEquals(
            CodexInteractiveRequest.Kind.COMMAND_APPROVAL,
            command.getKind(),
            "command approval kind"
        );
        TestSupport.assertEquals("./scripts/test.sh", command.getCommand(), "command preview");
        controller.resolveApproval(
            700L,
            CodexApprovalDecision.ACCEPT_WITH_EXEC_POLICY_AMENDMENT,
            -1
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.responseFor(700L) != null;
            }
        }, "command approval response");
        Map<String, Object> commandDecision = JsonCodec.requireObject(
            server.responseFor(700L).get("decision"),
            "command decision"
        );
        Map<String, Object> commandAmendment = JsonCodec.requireObject(
            commandDecision.get("acceptWithExecpolicyAmendment"),
            "command amendment"
        );
        TestSupport.assertEquals(
            "./scripts/test.sh",
            JsonCodec.requireArray(
                commandAmendment.get("execpolicy_amendment"),
                "exec policy amendment"
            ).get(0),
            "exact proposed command rule returned"
        );

        server.notifyMessage("item/started", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "item", JsonCodec.object(
                "id", "file_fixture",
                "type", "fileChange",
                "status", "inProgress",
                "changes", JsonCodec.array(JsonCodec.object(
                    "path", "/private/workspace/README.md",
                    "kind", JsonCodec.object("type", "update", "move_path", null),
                    "diff", "@@ -1 +1 @@"
                ))
            )
        ));
        server.requestFromServer(
            701L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "file_fixture",
                "startedAtMs", Long.valueOf(2L),
                "reason", "README aktualisieren"
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1
                    && controller.snapshot().getInteractiveRequests().get(0)
                        .getFileChanges().size() == 1;
            }
        }, "file approval details projected");
        TestSupport.assertEquals(
            "/private/workspace/README.md",
            controller.snapshot().getInteractiveRequests().get(0)
                .getFileChanges().get(0).getPath(),
            "file approval path"
        );
        controller.resolveApproval(701L, CodexApprovalDecision.DECLINE, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(701L);
                return response != null && "decline".equals(response.get("decision"));
            }
        }, "file decline response");

        server.requestFromServer(
            702L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "malformed_fixture"
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> error = server.errorFor(702L);
                return error != null
                    && JsonCodec.longValue(error.get("code"), 0L) == -32602L;
            }
        }, "malformed approval rejected");
        controller.close();
    }

    private static void acceptsFileCreationApproval() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.notifyMessage("item/started", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "item", JsonCodec.object(
                "id", "file_create_fixture",
                "type", "fileChange",
                "status", "inProgress",
                "changes", JsonCodec.array()
            )
        ));
        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "file_create_fixture",
            "changes", JsonCodec.array(
                fileChange(
                    "/private/workspace/generated/result.txt",
                    "add",
                    "",
                    "+created by fixture"
                ),
                fileChange(
                    "/private/workspace/generated/nested/second.txt",
                    "add",
                    "",
                    "+second file"
                )
            )
        ));
        server.requestFromServer(
            703L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "file_create_fixture",
                "startedAtMs", Long.valueOf(3L),
                "reason", "Ordner und Datei anlegen"
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1
                    && controller.snapshot().getInteractiveRequests().get(0)
                        .getFileChanges().size() == 2;
            }
        }, "file creation approval projected");
        CodexInteractiveRequest creation =
            controller.snapshot().getInteractiveRequests().get(0);
        TestSupport.assertEquals(
            "add",
            creation.getFileChanges().get(0).getKind(),
            "schema object change kind parsed"
        );
        TestSupport.assertEquals(
            "+created by fixture",
            creation.getFileChanges().get(0).getDiff(),
            "patchUpdated diff projected"
        );

        controller.resolveApproval(703L, CodexApprovalDecision.ACCEPT, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(703L);
                return response != null
                    && "accept".equals(response.get("decision"))
                    && controller.snapshot().getInteractiveRequests().isEmpty();
            }
        }, "file creation accept response");
        controller.close();
    }

    private static void enrichesFileApprovalAfterReorderedPatchUpdate() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.requestFromServer(
            704L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "late_patch_fixture",
                "startedAtMs", Long.valueOf(4L),
                "reason", "Datei verschieben"
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "file approval awaits late patch");
        TestSupport.assertTrue(
            controller.snapshot().getInteractiveRequests().get(0).getFileChanges().isEmpty(),
            "approval remains fail-closed before patch details"
        );

        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "",
            "turnId", "turn_fixture",
            "itemId", "late_patch_fixture",
            "changes", JsonCodec.array(fileChange(
                "/private/workspace/generated/unscoped.txt",
                "add",
                "",
                "+must be ignored"
            ))
        ));
        server.notifyMessage("error", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "error", JsonCodec.object("message", "invalid-patch-scope-marker")
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getErrorMessage()
                    .contains("invalid-patch-scope-marker");
            }
        }, "invalid patch scope processed");
        TestSupport.assertTrue(
            controller.snapshot().getInteractiveRequests().get(0).getFileChanges().isEmpty(),
            "patch without required thread scope is ignored"
        );

        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "late_patch_fixture",
            "changes", JsonCodec.array(fileChange(
                "/private/workspace/generated/result.txt",
                "update",
                "/private/workspace/generated/renamed.txt",
                "*** move fixture"
            ))
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexInteractiveRequest request =
                    controller.snapshot().getInteractiveRequests().get(0);
                return request.getFileChanges().size() == 1
                    && !request.getFileChanges().get(0).getMovePath().isEmpty();
            }
        }, "late patch enriches visible approval");
        TestSupport.assertEquals(
            "/private/workspace/generated/renamed.txt",
            controller.snapshot().getInteractiveRequests().get(0)
                .getFileChanges().get(0).getMovePath(),
            "move destination projected"
        );
        server.notifyMessage("item/started", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "item", JsonCodec.object(
                "id", "late_patch_fixture",
                "type", "fileChange",
                "status", "inProgress",
                "changes", JsonCodec.array()
            )
        ));
        server.notifyMessage("error", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "error", JsonCodec.object("message", "fixture-order-marker")
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getErrorMessage().contains("fixture-order-marker");
            }
        }, "late item start processed after patch details");
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(controller.snapshot().getInteractiveRequests().get(0)
                .getFileChanges().size()),
            "late empty item start cannot erase patch preview"
        );
        controller.resolveApproval(704L, CodexApprovalDecision.ACCEPT, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(704L);
                return response != null && "accept".equals(response.get("decision"));
            }
        }, "late patch approval accepted");

        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "unsafe_move_fixture",
            "changes", JsonCodec.array(fileChange(
                "/private/workspace/generated/result.txt",
                "update",
                "/outside/private-workspace/result.txt",
                "*** unsafe move fixture"
            ))
        ));
        server.requestFromServer(
            705L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "unsafe_move_fixture",
                "startedAtMs", Long.valueOf(5L),
                "reason", "Unsicheres Ziel testen"
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1
                    && controller.snapshot().getInteractiveRequests().get(0)
                        .getFileChanges().size() == 1;
            }
        }, "unsafe move approval projected");
        controller.resolveApproval(705L, CodexApprovalDecision.ACCEPT, -1);
        TestSupport.assertEquals(null, server.responseFor(705L), "unsafe move not approved");
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(controller.snapshot().getInteractiveRequests().size()),
            "unsafe move remains pending for a safe rejection"
        );
        TestSupport.assertContains(
            controller.snapshot().getErrorMessage(),
            "außerhalb",
            "unsafe move explains rejected approval"
        );
        controller.resolveApproval(705L, CodexApprovalDecision.CANCEL, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(705L);
                return response != null && "cancel".equals(response.get("decision"));
            }
        }, "unsafe move safely cancelled");
        controller.close();
    }

    private static Map<String, Object> fileChange(
        String path,
        String type,
        String movePath,
        String diff
    ) {
        Map<String, Object> kind = JsonCodec.object("type", type);
        if (movePath != null && !movePath.isEmpty()) {
            kind.put("move_path", movePath);
        }
        return JsonCodec.object(
            "path", path,
            "kind", kind,
            "diff", diff
        );
    }

    private static void rejectsIncompleteFileChangePreviews() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "malformed_patch_fixture",
            "changes", JsonCodec.array(
                fileChange(
                    "/private/workspace/generated/visible.txt",
                    "add",
                    "",
                    "+visible"
                ),
                JsonCodec.object(
                    "path", "/outside/private-workspace/hidden.txt",
                    "kind", "add",
                    "diff", "+must not be omitted"
                )
            )
        ));
        server.requestFromServer(
            706L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "malformed_patch_fixture",
                "startedAtMs", Long.valueOf(6L)
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "malformed patch approval projected fail-closed");
        TestSupport.assertTrue(
            controller.snapshot().getInteractiveRequests().get(0).getFileChanges().isEmpty(),
            "partially malformed patch exposes no positive approval"
        );
        controller.resolveApproval(706L, CodexApprovalDecision.ACCEPT, -1);
        TestSupport.assertEquals(null, server.responseFor(706L), "malformed patch not approved");
        controller.resolveApproval(706L, CodexApprovalDecision.CANCEL, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(706L);
                return response != null && "cancel".equals(response.get("decision"));
            }
        }, "malformed patch cancelled");

        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "invalid_diff_fixture",
            "changes", JsonCodec.array(JsonCodec.object(
                "path", "/private/workspace/generated/invalid-diff.txt",
                "kind", JsonCodec.object("type", "add"),
                "diff", Long.valueOf(42L)
            ))
        ));
        server.requestFromServer(
            708L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "invalid_diff_fixture",
                "startedAtMs", Long.valueOf(8L)
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "invalid diff approval projected fail-closed");
        TestSupport.assertTrue(
            controller.snapshot().getInteractiveRequests().get(0).getFileChanges().isEmpty(),
            "non-string required diff exposes no positive approval"
        );
        controller.resolveApproval(708L, CodexApprovalDecision.DECLINE, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(708L);
                return response != null && "decline".equals(response.get("decision"));
            }
        }, "invalid diff patch declined");

        List<Object> tooManyChanges = new ArrayList<Object>();
        for (int index = 0; index < 25; index++) {
            tooManyChanges.add(fileChange(
                "/private/workspace/generated/file-" + index + ".txt",
                "add",
                "",
                "+bounded fixture"
            ));
        }
        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "oversized_patch_fixture",
            "changes", tooManyChanges
        ));
        server.requestFromServer(
            707L,
            "item/fileChange/requestApproval",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "oversized_patch_fixture",
                "startedAtMs", Long.valueOf(7L)
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "oversized patch approval projected fail-closed");
        TestSupport.assertTrue(
            controller.snapshot().getInteractiveRequests().get(0).getFileChanges().isEmpty(),
            "oversized path set exposes no positive approval"
        );
        controller.resolveApproval(707L, CodexApprovalDecision.DECLINE, -1);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(707L);
                return response != null && "decline".equals(response.get("decision"));
            }
        }, "oversized patch declined");
        controller.close();
    }

    private static void handlesUserInputResolutionAndTimeout() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.requestFromServer(
            800L,
            "item/tool/requestUserInput",
            JsonCodec.object(
                "threadId", "thr_existing",
                "turnId", "turn_fixture",
                "itemId", "input_fixture",
                "isBlocking", Boolean.TRUE,
                "autoResolutionMs", null,
                "questions", JsonCodec.array(
                    JsonCodec.object(
                        "id", "scope",
                        "header", "Umfang",
                        "question", "Welcher Umfang?",
                        "options", JsonCodec.array(
                            JsonCodec.object("label", "Klein", "description", "Nur Kernpfad"),
                            JsonCodec.object("label", "Voll", "description", "Alle Pfade")
                        ),
                        "isOther", Boolean.TRUE
                    ),
                    JsonCodec.object(
                        "id", "secret",
                        "header", "Geheimnis",
                        "question", "Temporärer Wert?",
                        "options", null,
                        "isSecret", Boolean.TRUE
                    )
                )
            )
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "user input projected");
        CodexInteractiveRequest input = controller.snapshot().getInteractiveRequests().get(0);
        TestSupport.assertEquals(
            CodexInteractiveRequest.Kind.USER_INPUT,
            input.getKind(),
            "user input kind"
        );
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(input.getQuestions().size()),
            "question count"
        );
        TestSupport.assertTrue(input.getQuestions().get(1).isSecret(), "secret input flag");

        final char[] selected = "Voll".toCharArray();
        final char[] secret = "temporary-secret".toCharArray();
        Map<String, char[]> answers = new LinkedHashMap<String, char[]>();
        answers.put("scope", selected);
        answers.put("secret", secret);
        controller.answerUserInput(800L, answers);
        for (char character : selected) {
            TestSupport.assertEquals(Character.valueOf('\0'), Character.valueOf(character), "choice wipe");
        }
        for (char character : secret) {
            TestSupport.assertEquals(Character.valueOf('\0'), Character.valueOf(character), "secret wipe");
        }
        TestSupport.assertTrue(answers.isEmpty(), "answer map cleared");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.responseFor(800L) != null;
            }
        }, "user input response");
        Map<String, Object> responseAnswers = JsonCodec.requireObject(
            server.responseFor(800L).get("answers"),
            "response answers"
        );
        TestSupport.assertEquals(
            "Voll",
            JsonCodec.requireArray(
                JsonCodec.requireObject(responseAnswers.get("scope"), "scope answer")
                    .get("answers"),
                "scope answer values"
            ).get(0),
            "selected answer returned"
        );

        server.requestFromServer(
            801L,
            "item/tool/requestUserInput",
            singleQuestionRequest("resolved_fixture", null, true)
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "resolvable user input projected");
        server.notifyMessage("serverRequest/resolved", JsonCodec.object(
            "threadId", "thr_existing",
            "requestId", Long.valueOf(801L)
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().isEmpty();
            }
        }, "server-resolved input removed");

        server.requestFromServer(
            802L,
            "item/tool/requestUserInput",
            singleQuestionRequest("timeout_fixture", Long.valueOf(40L), false)
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                Map<String, Object> response = server.responseFor(802L);
                if (response == null) {
                    return false;
                }
                Map<String, Object> values = JsonCodec.optionalObject(response.get("answers"));
                return values != null && values.isEmpty();
            }
        }, "nonblocking user input safely auto-resolved");
        controller.close();
    }

    private static void startHeldTurn(
        FixtureServer server,
        final CodexSessionController controller
    ) throws Exception {
        controller.start();
        controller.openThread("thr_existing");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_existing".equals(controller.snapshot().getActiveThreadId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "thread ready for interactive request");
        controller.sendMessage("Interaktive Anfrage testen");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().isTurnActive()
                    && "turn_fixture".equals(controller.snapshot().getActiveTurnId())
                    && !controller.snapshot().isOperationActive();
            }
        }, "held turn started");
        TestSupport.assertEquals(
            "on-request",
            server.lastTurnStartParams.get("approvalPolicy"),
            "turn approval policy"
        );
    }

    private static Map<String, Object> singleQuestionRequest(
        String itemId,
        Long autoResolutionMs,
        boolean blocking
    ) {
        return JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", itemId,
            "isBlocking", Boolean.valueOf(blocking),
            "autoResolutionMs", autoResolutionMs,
            "questions", JsonCodec.array(JsonCodec.object(
                "id", "choice",
                "header", "Wahl",
                "question", "Fortfahren?",
                "options", JsonCodec.array(
                    JsonCodec.object("label", "Ja", "description", "Fortfahren"),
                    JsonCodec.object("label", "Nein", "description", "Stoppen")
                )
            ))
        );
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
        TestSupport.assertEquals(
            "on-request",
            params.get("approvalPolicy"),
            method + " native approval policy"
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
        private final Map<Long, Map<String, Object>> serverResponses =
            new ConcurrentHashMap<Long, Map<String, Object>>();
        private final Map<Long, Map<String, Object>> serverErrors =
            new ConcurrentHashMap<Long, Map<String, Object>>();
        private final boolean permissionAllowed;
        private volatile boolean signedIn;
        private volatile boolean closed;
        private volatile Map<String, Object> initializeParams;
        private volatile Map<String, Object> lastThreadResumeParams;
        private volatile Map<String, Object> lastThreadStartParams;
        private volatile Map<String, Object> lastTurnStartParams;
        private volatile boolean reorderStreamingEvents;
        private volatile boolean holdTurnOpen;

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
            if (method.isEmpty()) {
                long responseId = JsonCodec.longValue(request.get("id"), -1L);
                Map<String, Object> result = JsonCodec.optionalObject(request.get("result"));
                Map<String, Object> error = JsonCodec.optionalObject(request.get("error"));
                if (result != null) {
                    serverResponses.put(Long.valueOf(responseId), result);
                } else if (error != null) {
                    serverErrors.put(Long.valueOf(responseId), error);
                }
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
                if (holdTurnOpen) {
                    return;
                }
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

        private void requestFromServer(long id, String method, Map<String, Object> params) {
            incoming.offer(JsonCodec.stringify(JsonCodec.object(
                "id", Long.valueOf(id),
                "method", method,
                "params", params
            )));
        }

        private Map<String, Object> responseFor(long id) {
            return serverResponses.get(Long.valueOf(id));
        }

        private Map<String, Object> errorFor(long id) {
            return serverErrors.get(Long.valueOf(id));
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
