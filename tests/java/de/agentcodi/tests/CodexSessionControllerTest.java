package de.agentcodi.tests;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexApprovalDecision;
import de.agentcodi.core.CodexFileMention;
import de.agentcodi.core.CodexInteractiveRequest;
import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexRateLimitWindow;
import de.agentcodi.core.CodexRateLimitsSnapshot;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.CodexSessionController;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.core.JsonCodec;
import de.agentcodi.core.TerminalSessionSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class CodexSessionControllerTest {
    private CodexSessionControllerTest() {
    }

    public static int run() throws Exception {
        loadsAccountThreadsAndHistory();
        loadsAndRefreshesRateLimits();
        mergesStreamingDeltasAndFinalItem();
        keepsCompletedItemAuthoritativeAcrossReordering();
        projectsReasoningAndPlanCardsAuthoritatively();
        releasesCardStreamCapacityAfterTurnCompletion();
        projectsCompleteToolCardSet();
        keepsScrubbedResumeImagePathNonExportable();
        restoresCardsFromThreadHistory();
        reportsTransportFailureOnceAndReleasesTurn();
        sendsImportedFilesWithModelReadableContext();
        rejectsUnsafeImportedMentions();
        steersActiveTurnWithoutStartingAnotherTurn();
        rejectsUncorrelatedSteering();
        keepsApiKeyOutOfSnapshotsAndWipesCallerBuffer();
        blocksCredentialsInChatMessages();
        acceptsOnlyTrustedBrowserLoginUrl();
        usesAdvertisedModelEffortAndPermissionProfile();
        handlesCommandAndFileApprovals();
        acceptsFileCreationApproval();
        enrichesFileApprovalAfterReorderedPatchUpdate();
        rejectsIncompleteFileChangePreviews();
        handlesUserInputResolutionAndTimeout();
        rejectsUnavailableWorkspacePermissionProfile();
        startsTerminalThroughSandboxedCommandExec();
        streamsTerminalInputResizeAndTermination();
        terminatesTerminalWhenOutputCapIsReached();
        rejectsTerminalCredentialsAndMalformedOutput();
        usesVettedMcpConfigurationRpcs();
        return 29;
    }

    private static void sendsImportedFilesWithModelReadableContext() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
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
        }, "thread ready for imported mentions");

        List<CodexFileMention> mentions = Arrays.asList(
            CodexFileMention.create(
                "specification.pdf",
                "/private/workspace/imports/0123456789abcdef0123456789abcdef.pdf"
            ),
            CodexFileMention.create(
                "measurements.csv",
                "/private/workspace/imports/fedcba9876543210fedcba9876543210.csv"
            )
        );
        TestSupport.assertTrue(
            controller.sendMessage("  Analysiere beide Dateien.  ", mentions),
            "bounded imported mentions are accepted"
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTurnStartParams != null
                    && controller.snapshot().isTurnActive()
                    && !controller.snapshot().isOperationActive();
            }
        }, "turn/start carries imported mentions");

        Map<String, Object> start = server.lastTurnStartParams;
        TestSupport.assertEquals(
            Integer.valueOf(10),
            Integer.valueOf(start.size()),
            "turn/start adds only native attachment context to its existing fields"
        );
        List<Object> input = JsonCodec.requireArray(start.get("input"), "import turn input");
        TestSupport.assertEquals(
            Integer.valueOf(3),
            Integer.valueOf(input.size()),
            "text and two native mention inputs"
        );
        Map<String, Object> text = JsonCodec.requireObject(input.get(0), "import text");
        TestSupport.assertEquals("text", text.get("type"), "text remains first input");
        TestSupport.assertEquals(
            "Analysiere beide Dateien.",
            text.get("text"),
            "import turn text is trimmed"
        );
        for (int index = 0; index < mentions.size(); index++) {
            Map<String, Object> mention = JsonCodec.requireObject(
                input.get(index + 1),
                "native mention"
            );
            TestSupport.assertEquals("mention", mention.get("type"), "native mention type");
            TestSupport.assertEquals(
                mentions.get(index).getName(),
                mention.get("name"),
                "native mention name"
            );
            TestSupport.assertEquals(
                mentions.get(index).getPath(),
                mention.get("path"),
                "native mention path"
            );
            TestSupport.assertEquals(
                Integer.valueOf(3),
                Integer.valueOf(mention.size()),
                "mention has only the pinned app-server schema fields"
            );
        }
        Map<String, Object> attachmentContext = JsonCodec.requireObject(
            start.get("additionalContext"),
            "import attachment context"
        );
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(attachmentContext.size()),
            "each verified import receives one native context fragment"
        );
        for (int index = 0; index < mentions.size(); index++) {
            Map<String, Object> contextEntry = JsonCodec.requireObject(
                attachmentContext.get("agentcodi-import-" + (index + 1)),
                "import context entry"
            );
            TestSupport.assertEquals(
                "application",
                contextEntry.get("kind"),
                "verified attachment path is application context"
            );
            String contextValue = JsonCodec.requireString(
                contextEntry.get("value"),
                "import context value"
            );
            TestSupport.assertTrue(
                contextValue.contains(mentions.get(index).getPath())
                    && contextValue.contains("actual bytes")
                    && contextValue.contains("workspace tools"),
                "Codex receives the exact path and an actual-byte read requirement"
            );
            TestSupport.assertFalse(
                contextValue.contains("content://") || contextValue.contains("sha256"),
                "provider URIs and import digests do not enter model context"
            );
        }
        TestSupport.assertEquals(
            "/private/workspace",
            start.get("cwd"),
            "import does not replace the canonical turn cwd"
        );
        TestSupport.assertEquals(
            Collections.<Object>singletonList("/private/workspace"),
            JsonCodec.requireArray(start.get("runtimeWorkspaceRoots"), "runtime roots"),
            "external providers never become runtime workspace roots"
        );
        TestSupport.assertFalse(
            start.containsKey("sandboxPolicy") || start.containsKey("readOnlyAccess"),
            "import does not expand the workspace sandbox"
        );
        assertWorkspacePermissionRequest(start, "import turn/start");

        server.notifyMessage("item/completed", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "item", JsonCodec.object(
                "id", "import_user_fixture",
                "type", "userMessage",
                "content", input
            )
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return hasMessage(
                    controller.snapshot(),
                    "import_user_fixture",
                    "Analysiere beide Dateien.\n@specification.pdf\n@measurements.csv"
                );
            }
        }, "authoritative imported user item replaces local projection");

        CodexFileMention steeringMention = CodexFileMention.create(
            "correction.txt",
            "/private/workspace/imports/aabbccddeeff0011aabbccddeeff0011.txt"
        );
        TestSupport.assertTrue(
            controller.steerTurn("", Collections.singletonList(steeringMention)),
            "attachment-only active-turn steering is accepted"
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTurnSteerParams != null
                    && !controller.snapshot().isOperationActive();
            }
        }, "turn/steer carries an attachment-only mention");
        Map<String, Object> steer = server.lastTurnSteerParams;
        TestSupport.assertEquals(
            Integer.valueOf(4),
            Integer.valueOf(steer.size()),
            "attachment steering adds only native attachment context"
        );
        List<Object> steerInput = JsonCodec.requireArray(
            steer.get("input"),
            "attachment-only steer input"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(steerInput.size()),
            "attachment-only steer has one input"
        );
        Map<String, Object> steerMention = JsonCodec.requireObject(
            steerInput.get(0),
            "steer mention"
        );
        TestSupport.assertEquals("mention", steerMention.get("type"), "steer mention type");
        TestSupport.assertEquals(
            steeringMention.getPath(),
            steerMention.get("path"),
            "steer mention remains in workspace imports"
        );
        Map<String, Object> steerContext = JsonCodec.requireObject(
            steer.get("additionalContext"),
            "steer attachment context"
        );
        Map<String, Object> steerContextEntry = JsonCodec.requireObject(
            steerContext.get("agentcodi-import-1"),
            "steer attachment context entry"
        );
        TestSupport.assertTrue(
            JsonCodec.requireString(steerContextEntry.get("value"), "steer context value")
                .contains(steeringMention.getPath()),
            "attachment-only steering gives Codex the verified readable path"
        );
        TestSupport.assertEquals(
            "turn_fixture",
            steer.get("expectedTurnId"),
            "attachment-only steer remains correlated"
        );
        TestSupport.assertTrue(
            hasMessage(controller.snapshot(), "", "@correction.txt"),
            "attachment-only local projection remains visible"
        );
        controller.close();
    }

    private static void rejectsUnsafeImportedMentions() throws Exception {
        FixtureServer server = new FixtureServer(true);
        CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        CodexFileMention outside = CodexFileMention.create(
            "outside.txt",
            "/private/outside/outside.txt"
        );
        TestSupport.assertFalse(
            controller.sendMessage("Nicht senden", Collections.singletonList(outside)),
            "outside mention is rejected synchronously"
        );
        TestSupport.assertEquals(
            null,
            server.lastTurnStartParams,
            "outside mention never reaches transport"
        );

        CodexFileMention valid = CodexFileMention.create(
            "inside.txt",
            "/private/workspace/imports/0123456789abcdef0123456789abcdef.txt"
        );
        TestSupport.assertFalse(
            controller.sendMessage("Nicht doppelt", Arrays.asList(valid, valid)),
            "duplicate mention path is rejected"
        );
        CodexFileMention predictable = CodexFileMention.create(
            "predictable.txt",
            "/private/workspace/imports/predictable.txt"
        );
        TestSupport.assertFalse(
            controller.sendMessage(
                "Nicht mit nutzergesteuertem Speicherpfad",
                Collections.singletonList(predictable)
            ),
            "non-random import storage name is rejected"
        );
        final List<CodexFileMention> tooMany = new ArrayList<CodexFileMention>();
        for (int index = 0; index <= CodexFileMention.MAXIMUM_MENTIONS; index++) {
            String token = "00000000000000000000000000000000"
                + Integer.toHexString(index + 1);
            tooMany.add(CodexFileMention.create(
                "file-" + index + ".txt",
                "/private/workspace/imports/"
                    + token.substring(token.length() - 32) + ".txt"
            ));
        }
        TestSupport.assertFalse(
            controller.sendMessage("Nicht zu viele", tooMany),
            "mention-count limit is rejected"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CodexFileMention.create(
                        "auth.json",
                        "/private/workspace/imports/token-auth.json"
                    );
                }
            },
            "credential filename cannot become a Codex mention"
        );
        TestSupport.assertEquals(
            null,
            server.lastTurnStartParams,
            "invalid mention variants never reach transport"
        );
        controller.close();
    }

    private static void steersActiveTurnWithoutStartingAnotherTurn() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        server.emitSteerUserItemBeforeResponse = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        controller.steerTurn("  Zuerst die fehlschlagenden Tests prüfen.  ");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTurnSteerParams != null
                    && !controller.snapshot().isOperationActive();
            }
        }, "active turn steering completed");

        Map<String, Object> params = server.lastTurnSteerParams;
        TestSupport.assertEquals(
            Integer.valueOf(3),
            Integer.valueOf(params.size()),
            "turn/steer has only its supported fields"
        );
        TestSupport.assertEquals("thr_existing", params.get("threadId"), "steer thread id");
        TestSupport.assertEquals(
            "turn_fixture",
            params.get("expectedTurnId"),
            "steer expected turn id"
        );
        List<Object> input = JsonCodec.requireArray(params.get("input"), "steer input");
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(input.size()),
            "single steering input"
        );
        Map<String, Object> text = JsonCodec.requireObject(input.get(0), "steer text");
        TestSupport.assertEquals("text", text.get("type"), "steer input type");
        TestSupport.assertEquals(
            "Zuerst die fehlschlagenden Tests prüfen.",
            text.get("text"),
            "trimmed steering text"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(server.turnStartRequestCount.get()),
            "steering does not start another turn"
        );
        TestSupport.assertTrue(controller.snapshot().isTurnActive(), "steered turn stays active");
        TestSupport.assertEquals(
            "turn_fixture",
            controller.snapshot().getActiveTurnId(),
            "steered active turn remains correlated"
        );
        TestSupport.assertTrue(
            hasMessage(
                controller.snapshot(),
                "steer_user_fixture",
                "Zuerst die fehlschlagenden Tests prüfen."
            ),
            "authoritative steering user item replaces the local projection"
        );

        controller.interruptTurn();
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTurnInterruptParams != null
                    && !controller.snapshot().isOperationActive();
            }
        }, "interrupt remains available after steering");
        TestSupport.assertEquals(
            "turn_fixture",
            server.lastTurnInterruptParams.get("turnId"),
            "interrupt targets the steered turn"
        );
        controller.close();
    }

    private static void rejectsUncorrelatedSteering() throws Exception {
        FixtureServer idleServer = new FixtureServer(true);
        final CodexSessionController idleController = new CodexSessionController(
            idleServer,
            "/private/workspace"
        );
        idleController.start();
        idleController.openThread("thr_existing");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return "thr_existing".equals(idleController.snapshot().getActiveThreadId())
                    && !idleController.snapshot().isOperationActive();
            }
        }, "idle thread ready");
        idleController.steerTurn("Diese Eingabe darf keinen neuen Turn starten.");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return !idleController.snapshot().isOperationActive()
                    && "Kein laufender Turn kann ergänzt werden.".equals(
                        idleController.snapshot().getErrorMessage()
                    );
            }
        }, "idle steering rejected");
        TestSupport.assertTrue(
            idleServer.lastTurnSteerParams == null,
            "idle steering never reaches transport"
        );
        TestSupport.assertEquals(
            Integer.valueOf(0),
            Integer.valueOf(idleServer.turnStartRequestCount.get()),
            "idle steering is never replayed as turn/start"
        );
        idleController.close();

        FixtureServer mismatchServer = new FixtureServer(true);
        mismatchServer.holdTurnOpen = true;
        mismatchServer.steerResponseTurnId = "turn_other";
        final CodexSessionController mismatchController = new CodexSessionController(
            mismatchServer,
            "/private/workspace"
        );
        startHeldTurn(mismatchServer, mismatchController);
        mismatchController.steerTurn("apiKey=sk-steeringfixture12345");
        TestSupport.assertContains(
            mismatchController.snapshot().getErrorMessage(),
            "Kontobereich",
            "steering credential rejection"
        );
        TestSupport.assertTrue(
            mismatchServer.lastTurnSteerParams == null,
            "steering credential never reaches transport"
        );
        TestSupport.assertTrue(
            mismatchController.snapshot().isTurnActive(),
            "credential rejection leaves the running turn available"
        );
        mismatchController.steerTurn("Nicht als bestätigt anzeigen");
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return !mismatchController.snapshot().isOperationActive()
                    && "Der App-Server hat einen anderen Turn bestätigt.".equals(
                        mismatchController.snapshot().getErrorMessage()
                    );
            }
        }, "mismatched steer response rejected");
        TestSupport.assertFalse(
            hasMessage(mismatchController.snapshot(), "", "Nicht als bestätigt anzeigen"),
            "rejected steering projection removed"
        );
        TestSupport.assertTrue(
            mismatchController.snapshot().isTurnActive(),
            "a malformed steer response does not invent turn completion"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(mismatchServer.turnStartRequestCount.get()),
            "mismatched steering is not silently retried"
        );
        mismatchController.close();
    }

    private static void usesVettedMcpConfigurationRpcs() throws Exception {
        FixtureServer server = new FixtureServer(true);
        CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();

        Map<String, Object> read = controller.readMcpConfiguration(20_000L);
        TestSupport.assertTrue(read.containsKey("config"), "config/read response returned");
        TestSupport.assertEquals(
            "/private/workspace",
            server.lastMcpConfigurationReadParams.get("cwd"),
            "config/read canonical workspace"
        );
        TestSupport.assertEquals(
            Boolean.FALSE,
            server.lastMcpConfigurationReadParams.get("includeLayers"),
            "config/read omits raw layer bodies"
        );

        Map<String, Object> write = JsonCodec.object(
            "edits", JsonCodec.array(JsonCodec.object(
                "keyPath", "mcp_servers.protocol-fixture.enabled",
                "value", Boolean.FALSE,
                "mergeStrategy", "replace"
            )),
            "expectedVersion", configurationVersion('a'),
            "reloadUserConfig", Boolean.FALSE
        );
        Map<String, Object> response = controller.writeMcpConfiguration(write, 20_000L);
        TestSupport.assertEquals("ok", response.get("status"), "config/batchWrite response");
        TestSupport.assertEquals(
            write,
            server.lastMcpConfigurationWriteParams,
            "validated batch request forwarded unchanged"
        );
        TestSupport.assertFalse(
            server.lastMcpConfigurationWriteParams.containsKey("filePath"),
            "no configuration file path forwarded"
        );

        Map<String, Object> reload = controller.reloadMcpConfiguration(20_000L);
        TestSupport.assertTrue(reload.isEmpty(), "config reload response");
        TestSupport.assertFalse(
            server.lastMcpConfigurationReloadHadParams,
            "config reload request omits params"
        );

        final Map<String, Object> unsafe = new LinkedHashMap<String, Object>(write);
        unsafe.put("filePath", "/private/codex-home/config.toml");
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    controller.writeMcpConfiguration(unsafe, 20_000L);
                }
            },
            "caller-selected config path rejected before transport"
        );
        controller.close();
    }

    private static String configurationVersion(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        while (result.length() < 71) {
            result.append(value);
        }
        return result.toString();
    }

    private static void startsTerminalThroughSandboxedCommandExec() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        final CodexSessionController controller = terminalController(server);
        controller.start();
        controller.startTerminal(31, 101);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                TerminalSessionSnapshot terminal = controller.terminalSnapshot();
                return terminal.isRunning()
                    && terminal.getOutput().contains("terminal-ready ✓");
            }
        }, "sandboxed terminal starts and streams output");

        Map<String, Object> params = server.lastCommandExecParams;
        TestSupport.assertTrue(params != null, "command/exec request captured");
        List<Object> command = JsonCodec.requireArray(params.get("command"), "terminal command");
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(command.size()), "terminal argv");
        TestSupport.assertEquals(
            "/private/lib/libagentcodi-shell.so",
            command.get(0),
            "packaged shell path"
        );
        TestSupport.assertEquals("--interactive", command.get(1), "interactive shell mode");
        TestSupport.assertEquals("/private/workspace", params.get("cwd"), "terminal cwd");
        TestSupport.assertEquals(
            "agentcodi-workspace",
            params.get("permissionProfile"),
            "terminal permission profile"
        );
        TestSupport.assertEquals(Boolean.TRUE, params.get("tty"), "PTY enabled");
        TestSupport.assertFalse(params.containsKey("sandboxPolicy"), "no policy override");
        TestSupport.assertFalse(params.containsKey("env"), "no terminal environment override");
        TestSupport.assertFalse(params.containsKey("disableTimeout"), "finite timeout retained");
        TestSupport.assertFalse(params.containsKey("disableOutputCap"), "output cap retained");
        TestSupport.assertEquals(
            Long.valueOf(30L * 60L * 1000L),
            params.get("timeoutMs"),
            "terminal timeout"
        );
        TestSupport.assertEquals(
            Long.valueOf(8L * 1024L * 1024L),
            params.get("outputBytesCap"),
            "terminal output cap"
        );
        Map<String, Object> size = JsonCodec.requireObject(params.get("size"), "terminal size");
        TestSupport.assertEquals(Long.valueOf(31L), size.get("rows"), "terminal rows");
        TestSupport.assertEquals(Long.valueOf(101L), size.get("cols"), "terminal columns");
        TestSupport.assertFalse(
            controller.terminalSnapshot().getOutput().contains("\u001b"),
            "terminal ANSI controls removed"
        );
        controller.stopTerminal();
        waitForTerminalExit(controller);
        controller.close();
    }

    private static void streamsTerminalInputResizeAndTermination() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        final CodexSessionController controller = terminalController(server);
        controller.start();
        controller.startTerminal(24, 80);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.terminalSnapshot().isRunning();
            }
        }, "terminal running before input");

        final char[] input = "printf Grüß\\n\n".toCharArray();
        controller.sendTerminalInput(input);
        for (char character : input) {
            TestSupport.assertEquals(
                Character.valueOf('\0'),
                Character.valueOf(character),
                "terminal caller input wipe"
            );
        }
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTerminalInput != null
                    && server.terminalInputWireBufferWiped;
            }
        }, "terminal input reaches command/exec/write");
        TestSupport.assertEquals(
            "printf Grüß\\n\n",
            new String(server.lastTerminalInput, StandardCharsets.UTF_8),
            "terminal input bytes"
        );
        TestSupport.assertTrue(server.terminalInputWireBuffer != null, "mutable input buffer used");
        for (byte value : server.terminalInputWireBuffer) {
            TestSupport.assertEquals(Byte.valueOf((byte) 0), Byte.valueOf(value), "wire input wipe");
        }

        controller.resizeTerminal(40, 120);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.lastTerminalResizeParams != null;
            }
        }, "terminal resize request");
        Map<String, Object> resized = JsonCodec.requireObject(
            server.lastTerminalResizeParams.get("size"),
            "resized terminal size"
        );
        TestSupport.assertEquals(Long.valueOf(40L), resized.get("rows"), "resized rows");
        TestSupport.assertEquals(Long.valueOf(120L), resized.get("cols"), "resized columns");

        controller.stopTerminal();
        waitForTerminalExit(controller);
        TestSupport.assertTrue(server.lastTerminalTerminateParams != null, "terminal terminated");
        TestSupport.assertEquals(
            Integer.valueOf(130),
            Integer.valueOf(controller.terminalSnapshot().getExitCode()),
            "terminal exit code"
        );
        controller.clearTerminalOutput();
        TestSupport.assertEquals("", controller.terminalSnapshot().getOutput(), "terminal clear");
        controller.close();
    }

    private static void rejectsTerminalCredentialsAndMalformedOutput() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        final CodexSessionController controller = terminalController(server);
        controller.start();
        controller.startTerminal(24, 80);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.terminalSnapshot().isRunning();
            }
        }, "terminal running before rejection tests");

        final char[] credential = "sk-fixture123456789\n".toCharArray();
        TestSupport.expectThrows(
            IOException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    controller.sendTerminalInput(credential);
                }
            },
            "credential-shaped terminal input"
        );
        for (char character : credential) {
            TestSupport.assertEquals(
                Character.valueOf('\0'),
                Character.valueOf(character),
                "rejected terminal input wipe"
            );
        }
        TestSupport.assertEquals(null, server.lastTerminalInput, "credential not sent");

        String processId = JsonCodec.optionalString(server.lastCommandExecParams.get("processId"));
        server.notifyMessage("command/exec/outputDelta", JsonCodec.object(
            "processId", processId,
            "stream", "stdout",
            "deltaBase64", "***not-base64***",
            "capReached", Boolean.FALSE
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return !controller.terminalSnapshot().getFailure().isEmpty();
            }
        }, "malformed terminal output fails session");
        TestSupport.assertFalse(controller.terminalSnapshot().isRunning(), "failed terminal stopped");
        waitForTerminalExit(controller);
        controller.close();
    }

    private static void terminatesTerminalWhenOutputCapIsReached() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        final CodexSessionController controller = terminalController(server);
        controller.start();
        controller.startTerminal(24, 80);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.terminalSnapshot().isRunning();
            }
        }, "terminal running before output cap");
        String processId = JsonCodec.optionalString(server.lastCommandExecParams.get("processId"));
        server.notifyMessage("command/exec/outputDelta", JsonCodec.object(
            "processId", processId,
            "stream", "stdout",
            "deltaBase64", "",
            "capReached", Boolean.TRUE
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.terminalSnapshot().getFailure().contains("capture limit");
            }
        }, "terminal output cap fails closed");
        waitForTerminalExit(controller);
        TestSupport.assertTrue(server.lastTerminalTerminateParams != null, "capped terminal killed");
        controller.close();
    }

    private static CodexSessionController terminalController(FixtureServer server) {
        return new CodexSessionController(
            server,
            "/private/workspace",
            null,
            "/private/lib/libagentcodi-shell.so"
        );
    }

    private static void waitForTerminalExit(final CodexSessionController controller)
        throws Exception {
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                TerminalSessionSnapshot terminal = controller.terminalSnapshot();
                return !terminal.isRunning()
                    && !terminal.isStarting()
                    && terminal.getExitCode() != Integer.MIN_VALUE;
            }
        }, "terminal completion response");
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

    private static void loadsAndRefreshesRateLimits() throws Exception {
        final FixtureServer server = new FixtureServer(true);
        server.accountType = "chatgpt";
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();

        CodexRateLimitsSnapshot initial = controller.snapshot().getRateLimits();
        TestSupport.assertTrue(initial.isAvailable(), "ChatGPT rate limits loaded");
        TestSupport.assertEquals(
            Integer.valueOf(25),
            Integer.valueOf(initial.getPrimary().getUsedPercent()),
            "primary quota usage inherited"
        );
        TestSupport.assertEquals(
            Long.valueOf(300L),
            Long.valueOf(initial.getPrimary().getWindowDurationMinutes()),
            "primary quota duration inherited"
        );
        TestSupport.assertEquals(
            Long.valueOf(1_800_000_000L),
            Long.valueOf(initial.getPrimary().getResetsAtSeconds()),
            "primary quota reset inherited"
        );
        TestSupport.assertEquals(
            Integer.valueOf(40),
            Integer.valueOf(initial.getSecondary().getUsedPercent()),
            "secondary quota usage inherited"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(server.rateLimitsReadCount.get()),
            "one initial rate-limit read"
        );
        TestSupport.assertFalse(
            server.lastRateLimitsReadHadParams,
            "rate-limit read omits params"
        );

        server.primaryRateLimitUsedPercent = 31L;
        server.notifyMessage("account/rateLimits/updated", JsonCodec.object(
            "rateLimits", JsonCodec.object(
                "primary", JsonCodec.object("usedPercent", Long.valueOf(99L))
            )
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexRateLimitWindow primary = controller.snapshot()
                    .getRateLimits()
                    .getPrimary();
                return server.rateLimitsReadCount.get() == 2
                    && primary != null
                    && primary.getUsedPercent() == 31;
            }
        }, "sparse rate-limit update triggers authoritative reread");

        server.primaryRateLimitUsedPercent = 101L;
        server.notifyMessage("account/rateLimits/updated", JsonCodec.object(
            "rateLimits", JsonCodec.object(
                "primary", JsonCodec.object("usedPercent", Long.valueOf(101L))
            )
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.rateLimitsReadCount.get() == 3
                    && controller.snapshot().getErrorMessage().contains("allowed range");
            }
        }, "invalid rate-limit response fails closed");
        TestSupport.assertEquals(
            Integer.valueOf(31),
            Integer.valueOf(
                controller.snapshot().getRateLimits().getPrimary().getUsedPercent()
            ),
            "invalid refresh does not replace last valid quota"
        );

        controller.logout();
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getAuthMode().isEmpty()
                    && !controller.snapshot().isOperationActive();
            }
        }, "logout clears rate limits");
        TestSupport.assertFalse(
            controller.snapshot().getRateLimits().isAvailable(),
            "logout clears inherited quota"
        );
        TestSupport.assertEquals(
            Integer.valueOf(3),
            Integer.valueOf(server.rateLimitsReadCount.get()),
            "logout does not read ChatGPT quota without a ChatGPT account"
        );
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
        char[] rejectedAfterClose = "temporary-rejected-value".toCharArray();
        controller.startApiKeyLogin(rejectedAfterClose);
        for (char character : rejectedAfterClose) {
            TestSupport.assertEquals(
                Character.valueOf('\0'),
                Character.valueOf(character),
                "rejected key wipe"
            );
        }
    }

    private static void blocksCredentialsInChatMessages() throws Exception {
        FixtureServer server = new FixtureServer(true);
        CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        controller.start();
        controller.sendMessage("Bitte nutze sk-fixture123456789");
        CodexSessionSnapshot snapshot = controller.snapshot();
        TestSupport.assertContains(
            snapshot.getErrorMessage(),
            "Kontobereich",
            "chat credential rejection"
        );
        TestSupport.assertTrue(snapshot.getMessages().isEmpty(), "credential not projected");
        TestSupport.assertEquals(null, server.lastTurnStartParams, "credential not sent");
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

    private static void projectsReasoningAndPlanCardsAuthoritatively() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.notifyMessage("item/started", itemNotification(JsonCodec.object(
            "id", "reasoning_fixture",
            "type", "reasoning",
            "summary", JsonCodec.array(),
            "content", JsonCodec.array()
        )));
        server.notifyMessage("item/reasoning/summaryPartAdded", streamParams(
            "reasoning_fixture",
            "summaryIndex", Long.valueOf(1L),
            null
        ));
        server.notifyMessage("item/reasoning/summaryTextDelta", streamParams(
            "reasoning_fixture",
            "summaryIndex", Long.valueOf(1L),
            "Zweiter Schritt"
        ));
        server.notifyMessage("item/reasoning/summaryTextDelta", streamParams(
            "reasoning_fixture",
            "summaryIndex", Long.valueOf(0L),
            "Erster Schritt"
        ));
        server.notifyMessage("item/reasoning/textDelta", streamParams(
            "reasoning_fixture",
            "contentIndex", Long.valueOf(0L),
            "Interne Details"
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexTranscriptItem item = cardById(controller.snapshot(), "reasoning_fixture");
                return item != null
                    && item.isStreaming()
                    && "Erster Schritt\nZweiter Schritt".equals(item.getSummary())
                    && "Interne Details".equals(item.getDetail());
            }
        }, "reasoning deltas projected by index");

        server.notifyMessage("item/completed", itemNotification(JsonCodec.object(
            "id", "reasoning_fixture",
            "type", "reasoning",
            "summary", JsonCodec.array("Autoritative Zusammenfassung"),
            "content", JsonCodec.array("Autoritative Reasoning-Details")
        )));
        server.notifyMessage("item/reasoning/summaryTextDelta", streamParams(
            "reasoning_fixture",
            "summaryIndex", Long.valueOf(0L),
            " VERALTET"
        ));
        server.notifyMessage("item/plan/delta", streamParams(
            "plan_fixture",
            null,
            null,
            "Plan-Entwurf"
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexTranscriptItem plan = cardById(controller.snapshot(), "plan_fixture");
                return plan != null && "Plan-Entwurf".equals(plan.getDetail());
            }
        }, "plan delta projected");

        server.notifyMessage("turn/completed", JsonCodec.object(
            "threadId", "thr_existing",
            "turn", JsonCodec.object(
                "id", "turn_fixture",
                "status", "completed",
                "items", JsonCodec.array(),
                "error", null
            )
        ));
        server.notifyMessage("item/completed", itemNotification(JsonCodec.object(
            "id", "plan_fixture",
            "type", "plan",
            "text", "Autoritativer Plan"
        )));
        server.notifyMessage("item/plan/delta", streamParams(
            "plan_fixture",
            null,
            null,
            " VERALTET"
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexTranscriptItem plan = cardById(controller.snapshot(), "plan_fixture");
                return !controller.snapshot().isTurnActive()
                    && plan != null
                    && !plan.isStreaming()
                    && "Autoritativer Plan".equals(plan.getDetail());
            }
        }, "authoritative plan after reordered turn completion");
        CodexTranscriptItem reasoning = cardById(
            controller.snapshot(),
            "reasoning_fixture"
        );
        TestSupport.assertEquals(
            "Autoritative Zusammenfassung",
            reasoning.getSummary(),
            "completed reasoning replaces deltas"
        );
        TestSupport.assertEquals(
            "Autoritative Reasoning-Details",
            reasoning.getDetail(),
            "completed reasoning details"
        );
        TestSupport.assertFalse(reasoning.isStreaming(), "reasoning cannot be revived");
        controller.close();
    }

    private static void releasesCardStreamCapacityAfterTurnCompletion() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        for (int index = 0; index < 32; index++) {
            server.notifyMessage("item/plan/delta", streamParams(
                "unfinished_plan_" + index,
                null,
                null,
                "Plan " + index
            ));
        }
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return cardById(controller.snapshot(), "unfinished_plan_31") != null;
            }
        }, "card stream capacity reached");

        server.notifyMessage("turn/completed", JsonCodec.object(
            "threadId", "thr_existing",
            "turn", JsonCodec.object(
                "id", "turn_fixture",
                "status", "completed",
                "items", JsonCodec.array(),
                "error", null
            )
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return !controller.snapshot().isTurnActive();
            }
        }, "turn with unfinished card streams completed");

        server.notifyMessage("turn/started", JsonCodec.object(
            "threadId", "thr_existing",
            "turn", JsonCodec.object(
                "id", "turn_after_stream_cleanup",
                "status", "inProgress",
                "items", JsonCodec.array(),
                "error", null
            )
        ));
        server.notifyMessage("item/plan/delta", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_after_stream_cleanup",
            "itemId", "fresh_plan_after_cleanup",
            "delta", "Neuer Plan"
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexTranscriptItem item = cardById(
                    controller.snapshot(),
                    "fresh_plan_after_cleanup"
                );
                return item != null && item.isStreaming();
            }
        }, "card stream capacity released after turn completion");
        controller.close();
    }

    private static void projectsCompleteToolCardSet() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        server.notifyMessage("item/started", itemNotification(JsonCodec.object(
            "id", "command_card",
            "type", "commandExecution",
            "command", "./scripts/test.sh",
            "commandActions", JsonCodec.array(),
            "cwd", "/private/workspace",
            "status", "inProgress",
            "aggregatedOutput", null
        )));
        server.notifyMessage("item/commandExecution/outputDelta", streamParams(
            "command_card",
            null,
            null,
            "laufende Ausgabe apiKey=sk-outputfixture12345"
        ));
        server.notifyMessage("item/commandExecution/terminalInteraction", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "command_card",
            "processId", "process_fixture",
            "stdin", "yes\n"
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                CodexTranscriptItem item = cardById(controller.snapshot(), "command_card");
                return item != null && item.getDetail().contains("[stdin process_fixture]");
            }
        }, "terminal interaction projected in command card");
        String streamingCommandDetail = cardById(
            controller.snapshot(),
            "command_card"
        ).getDetail();
        TestSupport.assertContains(
            streamingCommandDetail,
            "<redacted>",
            "streamed tool credential redacted"
        );
        TestSupport.assertFalse(
            streamingCommandDetail.contains("sk-outputfixture12345"),
            "streamed tool credential absent from snapshot"
        );
        server.notifyMessage("item/completed", itemNotification(JsonCodec.object(
            "id", "command_card",
            "type", "commandExecution",
            "command", "./scripts/test.sh",
            "commandActions", JsonCodec.array(),
            "cwd", "/private/workspace",
            "status", "completed",
            "aggregatedOutput", "finale Ausgabe",
            "exitCode", Long.valueOf(0L),
            "durationMs", Long.valueOf(25L)
        )));

        List<Object> changes = JsonCodec.array(
            JsonCodec.object(
                "path", "/private/workspace/neu.txt",
                "kind", JsonCodec.object("type", "add"),
                "diff", "+Inhalt"
            ),
            JsonCodec.object(
                "path", "/private/workspace/leer.txt",
                "kind", JsonCodec.object("type", "update", "move_path", null),
                "diff", ""
            )
        );
        server.notifyMessage("item/fileChange/patchUpdated", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "file_card",
            "changes", changes
        ));
        server.notifyMessage("item/fileChange/outputDelta", streamParams(
            "file_card",
            null,
            null,
            "Patch angewendet"
        ));
        server.notifyMessage("item/completed", itemNotification(JsonCodec.object(
            "id", "file_card",
            "type", "fileChange",
            "changes", changes,
            "status", "completed"
        )));

        server.notifyMessage("item/started", itemNotification(JsonCodec.object(
            "id", "mcp_card",
            "type", "mcpToolCall",
            "server", "fixture-server",
            "tool", "lookup",
            "arguments", JsonCodec.object("query", "AGENTCODI"),
            "status", "inProgress"
        )));
        server.notifyMessage("item/mcpToolCall/progress", JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "itemId", "mcp_card",
            "message", "Suche läuft"
        ));
        server.notifyMessage("item/completed", itemNotification(JsonCodec.object(
            "id", "mcp_card",
            "type", "mcpToolCall",
            "server", "fixture-server",
            "tool", "lookup",
            "arguments", JsonCodec.object("query", "AGENTCODI"),
            "status", "completed",
            "result", JsonCodec.object("content", JsonCodec.array(JsonCodec.object(
                "type", "text",
                "text", "Treffer"
            )))
        )));

        notifyCompletedTool(server, JsonCodec.object(
            "id", "dynamic_card",
            "type", "dynamicToolCall",
            "namespace", "fixture",
            "tool", "render",
            "arguments", JsonCodec.object("apiKey", "sk-secretfixture12345"),
            "status", "completed",
            "success", Boolean.TRUE,
            "contentItems", JsonCodec.array(JsonCodec.object(
                "type", "inputText",
                "text", "Ausgabe"
            ))
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "collab_card",
            "type", "collabAgentToolCall",
            "tool", "spawnAgent",
            "status", "completed",
            "senderThreadId", "thr_existing",
            "receiverThreadIds", JsonCodec.array("thr_child"),
            "agentsStates", JsonCodec.object(
                "thr_child", JsonCodec.object("status", "completed", "message", "Fertig")
            ),
            "prompt", "Teilaufgabe",
            "model", "gpt-5.6-terra",
            "reasoningEffort", "high"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "subagent_card",
            "type", "subAgentActivity",
            "agentPath", "/root/child",
            "agentThreadId", "thr_child",
            "kind", "interacted"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "web_card",
            "type", "webSearch",
            "query", "Codex Android",
            "action", JsonCodec.object("type", "search", "query", "Codex Android"),
            "results", JsonCodec.array(JsonCodec.object("title", "Treffer"))
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "image_view_card",
            "type", "imageView",
            "path", "/private/workspace/bild.png"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "sleep_card",
            "type", "sleep",
            "durationMs", Long.valueOf(500L)
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "image_generation_card",
            "type", "imageGeneration",
            "status", "completed",
            "result", "<generated-image-data-omitted>",
            "revisedPrompt", "Ein Testbild",
            "savedPath", "/private/workspace/generated_images/image_generation_card.png"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "outside_image_card",
            "type", "imageGeneration",
            "status", "completed",
            "result", "metadata only",
            "savedPath", "/private/other/generated.png"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "alias_image_card",
            "type", "imageGeneration",
            "status", "completed",
            "result", "metadata only",
            "savedPath", "/data/data/de.agentcodi.app/files/agentcodi/workspace/generated.png"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "hook_card",
            "type", "hookPrompt",
            "fragments", JsonCodec.array(JsonCodec.object(
                "hookRunId", "hook_run",
                "text", "Hook-Hinweis"
            ))
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "review_card",
            "type", "enteredReviewMode",
            "review", "Änderungen prüfen"
        ));
        notifyCompletedTool(server, JsonCodec.object(
            "id", "compaction_card",
            "type", "contextCompaction"
        ));

        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return cardById(controller.snapshot(), "compaction_card") != null;
            }
        }, "all tool cards projected");
        CodexTranscriptItem command = cardById(controller.snapshot(), "command_card");
        TestSupport.assertContains(command.getSummary(), "test.sh", "command card command");
        TestSupport.assertContains(command.getDetail(), "finale Ausgabe", "command final output");
        TestSupport.assertFalse(command.isStreaming(), "command card completed");
        TestSupport.assertContains(
            cardById(controller.snapshot(), "file_card").getDetail(),
            "/private/workspace/neu.txt",
            "file card path and diff"
        );
        TestSupport.assertContains(
            cardById(controller.snapshot(), "file_card").getDetail(),
            "/private/workspace/leer.txt",
            "file card retains path when text diff is empty"
        );
        TestSupport.assertContains(
            cardById(controller.snapshot(), "file_card").getDetail(),
            "Kein Text-Diff vorhanden.",
            "file card explains empty text diff"
        );
        TestSupport.assertContains(
            cardById(controller.snapshot(), "mcp_card").getDetail(),
            "Treffer",
            "MCP result"
        );
        TestSupport.assertContains(
            cardById(controller.snapshot(), "image_generation_card").getDetail(),
            "nicht in den UI-Zustand übernommen",
            "compacted image result remains understandable"
        );
        TestSupport.assertEquals(
            "/private/workspace/generated_images/image_generation_card.png",
            cardById(controller.snapshot(), "image_generation_card").getReportedImagePath(),
            "native workspace-materialized image path reaches runtime validation"
        );
        TestSupport.assertEquals(
            "/private/workspace/bild.png",
            cardById(controller.snapshot(), "image_view_card").getReportedImagePath(),
            "viewed image path is retained for runtime validation"
        );
        TestSupport.assertEquals(
            "/private/other/generated.png",
            cardById(controller.snapshot(), "outside_image_card").getReportedImagePath(),
            "outside image path remains unverified until canonical runtime validation"
        );
        TestSupport.assertContains(
            cardById(controller.snapshot(), "outside_image_card").getDetail(),
            "kanonisch",
            "canonical runtime validation is explained"
        );
        TestSupport.assertEquals(
            "/data/data/de.agentcodi.app/files/agentcodi/workspace/generated.png",
            cardById(controller.snapshot(), "alias_image_card").getReportedImagePath(),
            "Android path alias reaches canonical runtime validation"
        );
        String dynamicDetail = cardById(controller.snapshot(), "dynamic_card").getDetail();
        TestSupport.assertContains(dynamicDetail, "<redacted>", "tool credentials redacted");
        TestSupport.assertFalse(
            dynamicDetail.contains("sk-secretfixture12345"),
            "tool credential absent from snapshot"
        );
        String[] expectedCards = {
            "command_card", "file_card", "mcp_card", "dynamic_card", "collab_card",
            "subagent_card", "web_card", "image_view_card", "sleep_card",
            "image_generation_card", "outside_image_card", "alias_image_card", "hook_card",
            "review_card", "compaction_card"
        };
        for (String id : expectedCards) {
            CodexTranscriptItem item = cardById(controller.snapshot(), id);
            TestSupport.assertTrue(item != null, "tool card visible: " + id);
            TestSupport.assertEquals(
                CodexTranscriptItem.Kind.TOOL,
                item.getKind(),
                "tool card kind: " + id
            );
        }
        controller.close();
    }

    private static void keepsScrubbedResumeImagePathNonExportable() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace"
        );
        startHeldTurn(server, controller);

        notifyCompletedTool(server, JsonCodec.object(
            "id", "unproven_resume_image",
            "type", "imageGeneration",
            "status", "completed",
            "result", "<generated-image-data-omitted>",
            "savedPath", null
        ));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return cardById(
                    controller.snapshot(),
                    "unproven_resume_image"
                ) != null;
            }
        }, "native-scrubbed resumed image projected");
        CodexTranscriptItem image = cardById(
            controller.snapshot(),
            "unproven_resume_image"
        );
        TestSupport.assertEquals(
            "",
            image.getReportedImagePath(),
            "missing native materialization proof exposes no export candidate"
        );
        TestSupport.assertFalse(
            image.getDetail().contains("Export"),
            "scrubbed app-server savedPath does not advertise export"
        );
        controller.close();
    }

    private static void restoresCardsFromThreadHistory() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.richHistory = true;
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
        }, "rich history resume");
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(controller.snapshot().getMessages().size()),
            "message compatibility view excludes cards"
        );
        TestSupport.assertEquals(
            CodexTranscriptItem.Kind.REASONING,
            cardById(controller.snapshot(), "reasoning_history").getKind(),
            "reasoning history restored"
        );
        TestSupport.assertEquals(
            CodexTranscriptItem.Kind.PLAN,
            cardById(controller.snapshot(), "plan_history").getKind(),
            "plan history restored"
        );
        TestSupport.assertContains(
            cardById(controller.snapshot(), "command_history").getDetail(),
            "Historische Ausgabe",
            "tool history restored"
        );
        controller.close();
    }

    private static void reportsTransportFailureOnceAndReleasesTurn() throws Exception {
        FixtureServer server = new FixtureServer(true);
        server.holdTurnOpen = true;
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<CodexSessionController> failedController =
            new AtomicReference<CodexSessionController>();
        final CodexSessionController controller = new CodexSessionController(
            server,
            "/private/workspace",
            new CodexSessionController.ConnectionFailureListener() {
                @Override
                public void onConnectionFailed(
                    CodexSessionController value,
                    Throwable error
                ) {
                    failedController.set(value);
                    failures.incrementAndGet();
                }
            },
            "/private/lib/libagentcodi-shell.so"
        );
        startHeldTurn(server, controller);
        controller.startTerminal(24, 80);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.terminalSnapshot().isRunning();
            }
        }, "terminal active before transport failure");
        server.close();
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return failures.get() == 1 && !controller.snapshot().isReady();
            }
        }, "transport failure callback");
        TestSupport.assertEquals(controller, failedController.get(), "failed controller identity");
        TestSupport.assertFalse(
            controller.snapshot().isTurnActive(),
            "transport failure releases active turn"
        );
        TestSupport.assertFalse(
            controller.snapshot().isOperationActive(),
            "transport failure releases active operation"
        );
        TestSupport.assertFalse(
            controller.terminalSnapshot().isRunning(),
            "transport failure releases active terminal"
        );
        TestSupport.assertFalse(
            controller.terminalSnapshot().getFailure().isEmpty(),
            "transport failure is visible in terminal state"
        );
        controller.close();
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(failures.get()),
            "intentional close does not duplicate failure callback"
        );
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
        List<Object> notificationOptOut = JsonCodec.requireArray(
            capabilities.get("optOutNotificationMethods"),
            "notification opt-out"
        );
        TestSupport.assertTrue(
            notificationOptOut.contains("rawResponseItem/completed"),
            "unused raw response items disabled"
        );
        TestSupport.assertTrue(
            notificationOptOut.contains("rawResponse/completed"),
            "unused raw response summaries disabled"
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
        TestSupport.assertEquals(
            "auto",
            server.lastTurnStartParams.get("summary"),
            "reasoning summaries requested"
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
            803L,
            "item/tool/requestUserInput",
            singleQuestionRequest("credential_fixture", null, true)
        );
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return controller.snapshot().getInteractiveRequests().size() == 1;
            }
        }, "credential input projected");
        final char[] credential = "sk-fixture123456789".toCharArray();
        Map<String, char[]> credentialAnswer = new LinkedHashMap<String, char[]>();
        credentialAnswer.put("choice", credential);
        controller.answerUserInput(803L, credentialAnswer);
        for (char character : credential) {
            TestSupport.assertEquals(
                Character.valueOf('\0'),
                Character.valueOf(character),
                "credential answer wipe"
            );
        }
        TestSupport.assertEquals(null, server.responseFor(803L), "credential answer not sent");
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(controller.snapshot().getInteractiveRequests().size()),
            "blocked credential request remains active"
        );
        controller.dismissUserInput(803L);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return server.responseFor(803L) != null;
            }
        }, "blocked credential input dismissed empty");

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

    private static Map<String, Object> itemNotification(Map<String, Object> item) {
        return JsonCodec.object(
            "threadId", "thr_existing",
            "turnId", "turn_fixture",
            "item", item
        );
    }

    private static Map<String, Object> streamParams(
        String itemId,
        String indexField,
        Long index,
        String delta
    ) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("threadId", "thr_existing");
        params.put("turnId", "turn_fixture");
        params.put("itemId", itemId);
        if (indexField != null && index != null) {
            params.put(indexField, index);
        }
        if (delta != null) {
            params.put("delta", delta);
        }
        return params;
    }

    private static void notifyCompletedTool(FixtureServer server, Map<String, Object> item) {
        server.notifyMessage("item/completed", itemNotification(item));
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

    private static boolean hasMessage(
        CodexSessionSnapshot snapshot,
        String id,
        String text
    ) {
        for (ChatMessage message : snapshot.getMessages()) {
            if ((id.isEmpty() || message.getId().equals(id))
                && message.getText().equals(text)) {
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

    private static CodexTranscriptItem cardById(CodexSessionSnapshot snapshot, String id) {
        for (CodexTranscriptItem item : snapshot.getTranscriptItems()) {
            if (!item.isMessage() && item.getId().equals(id)) {
                return item;
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
        private volatile String accountType;
        private volatile boolean closed;
        private volatile Map<String, Object> initializeParams;
        private volatile Map<String, Object> lastThreadResumeParams;
        private volatile Map<String, Object> lastThreadStartParams;
        private volatile Map<String, Object> lastTurnStartParams;
        private volatile Map<String, Object> lastTurnSteerParams;
        private volatile Map<String, Object> lastTurnInterruptParams;
        private volatile Map<String, Object> lastCommandExecParams;
        private volatile Map<String, Object> lastTerminalResizeParams;
        private volatile Map<String, Object> lastTerminalTerminateParams;
        private volatile Map<String, Object> lastMcpConfigurationReadParams;
        private volatile Map<String, Object> lastMcpConfigurationWriteParams;
        private volatile boolean lastMcpConfigurationReloadHadParams;
        private volatile Map<String, Object> pendingTerminalRequest;
        private volatile byte[] lastTerminalInput;
        private volatile byte[] terminalInputWireBuffer;
        private volatile boolean terminalInputWireBufferWiped;
        private volatile boolean reorderStreamingEvents;
        private volatile boolean holdTurnOpen;
        private volatile boolean emitSteerUserItemBeforeResponse;
        private volatile String steerResponseTurnId = "turn_fixture";
        private volatile boolean richHistory;
        private final AtomicInteger turnStartRequestCount = new AtomicInteger();
        private final AtomicInteger rateLimitsReadCount = new AtomicInteger();
        private volatile boolean lastRateLimitsReadHadParams;
        private volatile long primaryRateLimitUsedPercent = 25L;

        private FixtureServer(boolean signedIn) {
            this(signedIn, true);
        }

        private FixtureServer(boolean signedIn, boolean permissionAllowed) {
            this.accountType = signedIn ? "apiKey" : "";
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
                    "account", accountType.isEmpty()
                        ? null
                        : "chatgpt".equals(accountType)
                            ? JsonCodec.object(
                                "type", "chatgpt",
                                "email", "fixture@example.invalid",
                                "planType", "plus"
                            )
                            : JsonCodec.object("type", "apiKey"),
                    "requiresOpenaiAuth", Boolean.TRUE
                ));
            } else if ("account/rateLimits/read".equals(method)) {
                rateLimitsReadCount.incrementAndGet();
                lastRateLimitsReadHadParams = request.containsKey("params");
                respond(request, JsonCodec.object(
                    "rateLimits", JsonCodec.object(
                        "limitId", "codex",
                        "primary", JsonCodec.object(
                            "usedPercent", Long.valueOf(primaryRateLimitUsedPercent),
                            "windowDurationMins", Long.valueOf(300L),
                            "resetsAt", Long.valueOf(1_800_000_000L)
                        ),
                        "secondary", JsonCodec.object(
                            "usedPercent", Long.valueOf(40L),
                            "windowDurationMins", Long.valueOf(10_080L),
                            "resetsAt", Long.valueOf(1_800_600_000L)
                        )
                    )
                ));
            } else if ("account/login/start".equals(method)) {
                Map<String, Object> params = JsonCodec.requireObject(request.get("params"), "params");
                if ("apiKey".equals(params.get("type"))) {
                    TestSupport.assertTrue(
                        JsonCodec.optionalString(params.get("apiKey")).length() >= 8,
                        "transient API key reached Codex"
                    );
                    accountType = "apiKey";
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
                turnStartRequestCount.incrementAndGet();
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
            } else if ("turn/steer".equals(method)) {
                lastTurnSteerParams = JsonCodec.requireObject(request.get("params"), "params");
                if (emitSteerUserItemBeforeResponse) {
                    Map<String, Object> steerInput = JsonCodec.requireObject(
                        JsonCodec.requireArray(
                            lastTurnSteerParams.get("input"),
                            "steer input"
                        ).get(0),
                        "steer text input"
                    );
                    notifyMessage("item/completed", JsonCodec.object(
                        "threadId", "thr_existing",
                        "turnId", "turn_fixture",
                        "item", JsonCodec.object(
                            "id", "steer_user_fixture",
                            "type", "userMessage",
                            "content", JsonCodec.array(JsonCodec.object(
                                "type", "text",
                                "text", steerInput.get("text")
                            ))
                        )
                    ));
                }
                respond(request, JsonCodec.object("turnId", steerResponseTurnId));
            } else if ("command/exec".equals(method)) {
                lastCommandExecParams = JsonCodec.requireObject(request.get("params"), "params");
                pendingTerminalRequest = request;
                notifyMessage("command/exec/outputDelta", JsonCodec.object(
                    "processId", lastCommandExecParams.get("processId"),
                    "stream", "stdout",
                    "deltaBase64", Base64.getEncoder().encodeToString(
                        "\u001b[32mterminal-ready ✓\u001b[0m\r\n"
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    "capReached", Boolean.FALSE
                ));
            } else if ("command/exec/write".equals(method)) {
                Map<String, Object> params = JsonCodec.requireObject(request.get("params"), "params");
                lastTerminalInput = Base64.getDecoder().decode(
                    JsonCodec.requireString(params.get("deltaBase64"), "terminal input")
                );
                respond(request, JsonCodec.object());
            } else if ("command/exec/resize".equals(method)) {
                lastTerminalResizeParams = JsonCodec.requireObject(request.get("params"), "params");
                respond(request, JsonCodec.object());
            } else if ("command/exec/terminate".equals(method)) {
                lastTerminalTerminateParams = JsonCodec.requireObject(
                    request.get("params"),
                    "params"
                );
                respond(request, JsonCodec.object());
                Map<String, Object> pending = pendingTerminalRequest;
                pendingTerminalRequest = null;
                if (pending != null) {
                    respond(pending, JsonCodec.object(
                        "exitCode", Long.valueOf(130L),
                        "stdout", "",
                        "stderr", ""
                    ));
                }
            } else if ("config/read".equals(method)) {
                lastMcpConfigurationReadParams = JsonCodec.requireObject(
                    request.get("params"),
                    "config read params"
                );
                respond(request, JsonCodec.object(
                    "config", JsonCodec.object("mcp_servers", JsonCodec.object()),
                    "origins", JsonCodec.object(),
                    "layers", null
                ));
            } else if ("config/batchWrite".equals(method)) {
                lastMcpConfigurationWriteParams = JsonCodec.requireObject(
                    request.get("params"),
                    "config batch-write params"
                );
                respond(request, JsonCodec.object(
                    "status", "ok",
                    "version", configurationVersion('b'),
                    "filePath", "/private/codex-home/config.toml",
                    "overriddenMetadata", null
                ));
            } else if ("config/mcpServer/reload".equals(method)) {
                lastMcpConfigurationReloadHadParams = request.containsKey("params");
                respond(request, JsonCodec.object());
            } else if ("account/logout".equals(method)) {
                accountType = "";
                respond(request, JsonCodec.object());
            } else if ("turn/interrupt".equals(method)) {
                lastTurnInterruptParams = JsonCodec.requireObject(
                    request.get("params"),
                    "turn interrupt params"
                );
                respond(request, JsonCodec.object());
            }
        }

        @Override
        public void writeBytes(byte[] line, int length, int maximumBytes) throws IOException {
            if (line == null || length <= 0 || length > line.length || length > maximumBytes) {
                throw new IOException("fixture outgoing bytes too large");
            }
            byte[] copy = Arrays.copyOf(line, length);
            try {
                String json = new String(copy, StandardCharsets.UTF_8);
                Map<String, Object> message = JsonCodec.parseObject(json);
                if ("command/exec/write".equals(
                        JsonCodec.optionalString(message.get("method")))) {
                    terminalInputWireBuffer = line;
                    terminalInputWireBufferWiped = false;
                }
                writeLine(json, maximumBytes);
            } finally {
                Arrays.fill(copy, (byte) 0);
                Arrays.fill(line, (byte) 0);
                if (terminalInputWireBuffer == line) {
                    terminalInputWireBufferWiped = true;
                }
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

        private Map<String, Object> thread(String id, boolean includeTurns) {
            List<Object> turns = new ArrayList<Object>();
            if (includeTurns) {
                List<Object> items = new ArrayList<Object>();
                items.add(JsonCodec.object(
                    "id", "user_history",
                    "type", "userMessage",
                    "content", JsonCodec.array(JsonCodec.object(
                        "type", "text",
                        "text", "Historische Aufgabe"
                    ))
                ));
                if (richHistory) {
                    items.add(JsonCodec.object(
                        "id", "reasoning_history",
                        "type", "reasoning",
                        "summary", JsonCodec.array("Historische Überlegung"),
                        "content", JsonCodec.array("Historische Details")
                    ));
                    items.add(JsonCodec.object(
                        "id", "plan_history",
                        "type", "plan",
                        "text", "Historischer Plan"
                    ));
                    items.add(JsonCodec.object(
                        "id", "command_history",
                        "type", "commandExecution",
                        "command", "pwd",
                        "commandActions", JsonCodec.array(),
                        "cwd", "/private/workspace",
                        "status", "completed",
                        "aggregatedOutput", "Historische Ausgabe",
                        "exitCode", Long.valueOf(0L)
                    ));
                }
                items.add(JsonCodec.object(
                    "id", "assistant_history",
                    "type", "agentMessage",
                    "text", "Historische Antwort"
                ));
                turns.add(JsonCodec.object(
                    "id", "turn_history",
                    "status", "completed",
                    "items", items
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
