package de.agentcodi.tests;

import de.agentcodi.core.CodexReviewRequest;
import de.agentcodi.core.CodexReviewState;
import de.agentcodi.review.CustomReviewMode;

import java.util.Arrays;

public final class CustomReviewModeTest {
    private CustomReviewModeTest() {
    }

    public static int run() {
        exposesOnlyBoundedCustomInlineRequests();
        rejectsInvalidInstructionsAndIdentifiers();
        correlatesReorderedLifecycleWithoutRevival();
        correlatesBoundedSplitTurnIds();
        rejectsMismatchedResponsesAndItems();
        return 5;
    }

    private static void exposesOnlyBoundedCustomInlineRequests() {
        CodexReviewRequest request = CustomReviewMode.get().prepare(
            "thr_review",
            "  Prüfe Fehlerbehandlung und Tests.  "
        );
        TestSupport.assertEquals("thr_review", request.getThreadId(), "review thread");
        TestSupport.assertEquals("inline", request.getDelivery(), "inline-only review");
        TestSupport.assertEquals("custom", request.getTargetType(), "custom-only target");
        TestSupport.assertEquals(
            "Prüfe Fehlerbehandlung und Tests.",
            request.getInstructions(),
            "review instructions are trimmed"
        );
        TestSupport.assertEquals(
            Integer.valueOf(32 * 1024),
            Integer.valueOf(CodexReviewRequest.MAXIMUM_INSTRUCTIONS_CHARACTERS),
            "review instruction limit"
        );
    }

    private static void rejectsInvalidInstructionsAndIdentifiers() {
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CustomReviewMode.get().prepare("thr_review", "   ");
                }
            },
            "blank review instructions"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CustomReviewMode.get().prepare(
                        "thr_review",
                        "Prüfe\0alles"
                    );
                }
            },
            "NUL review instructions"
        );
        final char[] oversized = new char[
            CodexReviewRequest.MAXIMUM_INSTRUCTIONS_CHARACTERS + 1
        ];
        Arrays.fill(oversized, 'x');
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CustomReviewMode.get().prepare(
                        "thr_review",
                        new String(oversized)
                    );
                }
            },
            "oversized review instructions"
        );
        Arrays.fill(oversized, '\0');
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CustomReviewMode.get().prepare(
                        "thr_review",
                        "Use sk-reviewfixture1234567890 while checking the code"
                    );
                }
            },
            "credential-shaped review instructions"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CustomReviewMode.get().prepare("../thread", "Prüfen");
                }
            },
            "unsafe review thread id"
        );
    }

    private static void correlatesReorderedLifecycleWithoutRevival() {
        CustomReviewMode mode = CustomReviewMode.get();
        CodexReviewRequest request = mode.prepare("thr_review", "Prüfe alles.");
        CodexReviewState state = mode.begin(CodexReviewState.idle(), request);
        TestSupport.assertEquals(
            CodexReviewState.Phase.STARTING,
            state.getPhase(),
            "review begins pending"
        );
        TestSupport.assertTrue(
            mode.acceptsItem(
                state,
                "thr_review",
                "turn_review",
                "enteredReviewMode",
                false
            ),
            "entered item may precede start response"
        );
        state = mode.correlateItem(
            state,
            "thr_review",
            "turn_review",
            "enteredReviewMode",
            false
        );
        TestSupport.assertTrue(state.isReviewModeActive(), "review enter is active");
        state = mode.correlateStartResponse(
            state,
            request,
            "thr_review",
            "turn_review",
            false
        );
        TestSupport.assertTrue(
            state.isReviewModeActive(),
            "late response cannot regress entered review"
        );
        state = mode.correlateItem(
            state,
            "thr_review",
            "turn_review",
            "exitedReviewMode",
            false
        );
        TestSupport.assertTrue(
            state.isReviewModeActive(),
            "started exit is not authoritative"
        );
        state = mode.correlateItem(
            state,
            "thr_review",
            "turn_review",
            "exitedReviewMode",
            true
        );
        TestSupport.assertEquals(
            CodexReviewState.Phase.EXITED,
            state.getPhase(),
            "completed exit is authoritative"
        );
        state = mode.correlateTurnCompleted(state, "thr_review", "turn_review");
        TestSupport.assertEquals(
            CodexReviewState.Phase.COMPLETED,
            state.getPhase(),
            "review turn completion retains bounded late-item correlation"
        );
        state = mode.correlateItem(
            state,
            "thr_review",
            "turn_review",
            "enteredReviewMode",
            true
        );
        TestSupport.assertEquals(
            CodexReviewState.Phase.COMPLETED,
            state.getPhase(),
            "late authoritative item cannot revive review mode"
        );
        state = mode.begin(state, request);
        TestSupport.assertEquals(
            CodexReviewState.Phase.STARTING,
            state.getPhase(),
            "a completed review does not block an explicit new review"
        );

        state = mode.correlateTurnStarted(state, "thr_review", "turn_early");
        state = mode.correlateTurnCompleted(state, "thr_review", "turn_early");
        state = mode.correlateStartResponse(
            state,
            request,
            "thr_review",
            "turn_early",
            true
        );
        TestSupport.assertEquals(
            CodexReviewState.Phase.COMPLETED,
            state.getPhase(),
            "completion before response stays completed"
        );
    }

    private static void rejectsMismatchedResponsesAndItems() {
        final CustomReviewMode mode = CustomReviewMode.get();
        final CodexReviewRequest request = mode.prepare("thr_review", "Prüfe alles.");
        final CodexReviewState state = mode.begin(CodexReviewState.idle(), request);
        TestSupport.assertFalse(
            mode.acceptsItem(
                state,
                "thr_other",
                "turn_review",
                "enteredReviewMode",
                false
            ),
            "wrong-thread review item"
        );
        TestSupport.assertFalse(
            mode.acceptsItem(
                state,
                "thr_review",
                "turn review",
                "enteredReviewMode",
                false
            ),
            "malformed review turn id"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    mode.correlateStartResponse(
                        state,
                        request,
                        "thr_other",
                        "turn_review",
                        false
                    );
                }
            },
            "detached or wrong-thread response"
        );
        CodexReviewState correlated = mode.correlateStartResponse(
            state,
            request,
            "thr_review",
            "turn_response",
            false
        );
        correlated = mode.correlateTurnStarted(
            correlated,
            "thr_review",
            "turn_live"
        );
        TestSupport.assertFalse(
            mode.acceptsItem(
                correlated,
                "thr_review",
                "turn_third",
                "exitedReviewMode",
                true
            ),
            "third review turn id is rejected"
        );
        TestSupport.assertFalse(
            mode.acceptsTurnCompletion(
                correlated,
                "thr_review",
                "turn_third"
            ),
            "third completion turn id is rejected"
        );
        final CodexReviewState finalCorrelated = correlated;
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    mode.correlateStartResponse(
                        finalCorrelated,
                        request,
                        "thr_review",
                        "turn_third",
                        false
                    );
                }
            },
            "a second review/start response cannot replace its turn id"
        );
    }

    private static void correlatesBoundedSplitTurnIds() {
        CustomReviewMode mode = CustomReviewMode.get();
        CodexReviewRequest request = mode.prepare("thr_review", "Prüfe alles.");
        CodexReviewState state = mode.begin(CodexReviewState.idle(), request);

        state = mode.correlateTurnStarted(
            state,
            "thr_review",
            "turn_review_live"
        );
        state = mode.correlateItem(
            state,
            "thr_review",
            "turn_review_live",
            "enteredReviewMode",
            false
        );
        state = mode.correlateStartResponse(
            state,
            request,
            "thr_review",
            "turn_review_response",
            false
        );
        TestSupport.assertEquals(
            "turn_review_response",
            state.getResponseTurnId(),
            "review/start response id remains authoritative"
        );
        TestSupport.assertEquals(
            "turn_review_live",
            state.getNotificationTurnId(),
            "turn/started id remains bounded separately"
        );
        TestSupport.assertEquals(
            "turn_review_live",
            state.getControlTurnId(),
            "interrupt uses the live turn/started id"
        );
        TestSupport.assertTrue(
            mode.acceptsItem(
                state,
                "thr_review",
                "turn_review_response",
                "exitedReviewMode",
                true
            ),
            "terminal review item may use the response id"
        );
        state = mode.correlateItem(
            state,
            "thr_review",
            "turn_review_response",
            "exitedReviewMode",
            true
        );
        TestSupport.assertTrue(
            mode.acceptsTurnCompletion(
                state,
                "thr_review",
                "turn_review_response"
            ),
            "terminal turn may use the response id"
        );
        state = mode.correlateTurnCompleted(
            state,
            "thr_review",
            "turn_review_response"
        );
        TestSupport.assertEquals(
            CodexReviewState.Phase.COMPLETED,
            state.getPhase(),
            "split-id review reaches a terminal state"
        );

        state = mode.begin(state, request);
        state = mode.correlateStartResponse(
            state,
            request,
            "thr_review",
            "turn_response_first",
            false
        );
        state = mode.correlateTurnStarted(
            state,
            "thr_review",
            "turn_live_second"
        );
        TestSupport.assertEquals(
            "turn_live_second",
            state.getControlTurnId(),
            "split ids also correlate when the response arrives first"
        );
        TestSupport.assertTrue(
            mode.acceptsTurnCompletion(
                state,
                "thr_review",
                "turn_response_first"
            ),
            "response-first review accepts its terminal response id"
        );
    }
}
