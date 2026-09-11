package de.agentcodi.tests;

import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeStateMachine;

public final class RuntimeStopTest {
    private RuntimeStopTest() {
    }

    public static void main(String[] arguments) throws Exception {
        System.out.println("Runtime stop Java tests passed: " + run());
    }

    public static int run() throws Exception {
        buttonsFollowRuntimeLifecycle();
        stopDuringStartupRejectsLateCallbacks();
        restartWaitsForCompletedShutdown();
        repeatedStopDoesNotOwnAnotherShutdown();
        staleStopCannotAffectExplicitRestart();
        failedRuntimeCanBeStopped();
        CodexSessionControllerTest.stopsRuntimeWithActiveWork();
        CodexSessionControllerTest.stopsRuntimeDuringInitialization();
        return 8;
    }

    private static void buttonsFollowRuntimeLifecycle() {
        for (RuntimePhase phase : RuntimePhase.values()) {
            TestSupport.assertEquals(
                Boolean.valueOf(phase == RuntimePhase.IDLE || phase == RuntimePhase.STOPPED
                    || phase == RuntimePhase.FAILED),
                Boolean.valueOf(phase.canStart()),
                "start button in " + phase
            );
            TestSupport.assertEquals(
                Boolean.valueOf(phase == RuntimePhase.STARTING || phase == RuntimePhase.READY
                    || phase == RuntimePhase.FAILED),
                Boolean.valueOf(phase.canStop()),
                "stop button in " + phase
            );
        }
    }

    private static void stopDuringStartupRejectsLateCallbacks() {
        RuntimeStateMachine state = new RuntimeStateMachine();
        long generation = state.beginStart();
        TestSupport.assertTrue(state.beginStop(), "stop cancels startup immediately");
        TestSupport.assertFalse(state.markReady(generation, "native/1", "", "/workspace"),
            "late bootstrap cannot publish a runtime after stop");
        TestSupport.assertFalse(state.markFailed(generation, "interrupted"),
            "intentional startup cancellation must not become a runtime failure");
        TestSupport.assertEquals(RuntimePhase.STOPPING, state.snapshot().getPhase(),
            "shutdown stays active until the bootstrap has released its child");
        TestSupport.assertTrue(state.finishStop(generation), "bootstrap cleanup completes stop");
    }

    private static void restartWaitsForCompletedShutdown() {
        final RuntimeStateMachine state = new RuntimeStateMachine();
        long generation = state.beginStart();
        state.markReady(generation, "native/1", "", "/workspace");
        TestSupport.assertTrue(state.beginStop(), "ready runtime can stop");
        TestSupport.expectThrows(IllegalStateException.class, new TestSupport.ThrowingRunnable() {
            @Override
            public void run() {
                state.beginStart();
            }
        }, "a second child cannot start while the first is shutting down");
        TestSupport.assertFalse(state.markFailed(generation, "closed transport"),
            "intentional transport closure cannot overwrite stop");
        TestSupport.assertTrue(state.finishStop(generation), "native cleanup finishes stop");
        TestSupport.assertEquals(RuntimePhase.STOPPED, state.snapshot().getPhase(),
            "runtime stays stopped until another explicit start");
        TestSupport.assertTrue(state.beginStart() > generation, "explicit restart is available");
    }

    private static void repeatedStopDoesNotOwnAnotherShutdown() {
        RuntimeStateMachine state = new RuntimeStateMachine();
        TestSupport.assertFalse(state.beginStop(), "idle runtime has nothing to stop");
        long generation = state.beginStart();
        TestSupport.assertTrue(state.beginStop(), "first tap owns shutdown");
        TestSupport.assertFalse(state.beginStop(), "second tap cannot run another shutdown");
        TestSupport.assertFalse(state.finishStop(generation + 1L),
            "unrelated shutdown cannot enable the start button");
        TestSupport.assertTrue(state.finishStop(generation), "owning shutdown completes");
        TestSupport.assertFalse(state.beginStop(), "stopped runtime has nothing to stop");
        TestSupport.assertFalse(state.finishStop(generation), "completion is idempotent");
    }

    private static void staleStopCannotAffectExplicitRestart() {
        RuntimeStateMachine state = new RuntimeStateMachine();
        long previous = state.beginStart(CodexExecutionMode.COMPATIBILITY_ID,
            CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID, true);
        state.beginStop();
        state.finishStop(previous);
        long current = state.beginStart();
        TestSupport.assertEquals(CodexExecutionMode.PROTECTED_ID,
            state.snapshot().getExecutionModeId(), "restart defaults to protected mode");
        TestSupport.assertFalse(state.finishStop(previous), "stale cleanup cannot stop restart");
        TestSupport.assertTrue(state.markReady(current, "native/2", "", "/workspace"),
            "explicit restart can become ready");
        TestSupport.assertFalse(state.markReady(previous, "native/1", "", "/workspace"),
            "old bootstrap cannot replace the new runtime");
    }

    private static void failedRuntimeCanBeStopped() {
        RuntimeStateMachine state = new RuntimeStateMachine();
        long generation = state.beginStart();
        state.markFailed(generation, "connection failed");
        TestSupport.assertTrue(state.beginStop(), "failed foreground service can be stopped");
        state.finishStop(generation);
        TestSupport.assertEquals(RuntimePhase.STOPPED, state.snapshot().getPhase(),
            "manual stop dismisses the failed runtime");
    }
}
