package de.agentcodi.tests;

import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;
import de.agentcodi.core.RuntimeStateMachine;

public final class RuntimeStateMachineTest {
    private RuntimeStateMachineTest() {
    }

    public static int run() {
        initialStateIsIdle();
        successfulStartBecomesReady();
        staleCompletionsAreIgnored();
        invalidTransitionsFailClosed();
        stopInvalidatesInFlightCompletion();
        return 5;
    }

    private static void initialStateIsIdle() {
        RuntimeSnapshot snapshot = new RuntimeStateMachine().snapshot();
        TestSupport.assertEquals(RuntimePhase.IDLE, snapshot.getPhase(), "initial phase");
        TestSupport.assertEquals(Long.valueOf(0L), Long.valueOf(snapshot.getGeneration()), "generation");
    }

    private static void successfulStartBecomesReady() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        long generation = machine.beginStart();
        TestSupport.assertTrue(
            machine.markReady(generation, "native/1", "abi=arm64", "/private/workspace"),
            "current completion should be accepted"
        );
        RuntimeSnapshot ready = machine.snapshot();
        TestSupport.assertEquals(RuntimePhase.READY, ready.getPhase(), "ready phase");
        TestSupport.assertEquals("native/1", ready.getEngineVersion(), "engine version");
        TestSupport.assertEquals("/private/workspace", ready.getWorkspacePath(), "workspace");
    }

    private static void staleCompletionsAreIgnored() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        long first = machine.beginStart();
        TestSupport.assertFalse(
            machine.markFailed(first + 1L, "stale"),
            "unknown generation must be ignored"
        );
        TestSupport.assertEquals(
            RuntimePhase.STARTING,
            machine.snapshot().getPhase(),
            "stale failure must not mutate state"
        );
        TestSupport.assertTrue(machine.markFailed(first, "expected failure"), "failure accepted");
        long second = machine.beginStart();
        TestSupport.assertFalse(
            machine.markReady(first, "old", "old", "/old"),
            "previous generation must not win"
        );
        TestSupport.assertTrue(
            machine.markReady(second, "native/2", "ok", "/new"),
            "latest generation should win"
        );
    }

    private static void invalidTransitionsFailClosed() {
        final RuntimeStateMachine machine = new RuntimeStateMachine();
        final long generation = machine.beginStart();
        TestSupport.expectThrows(
            IllegalStateException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    machine.beginStart();
                }
            },
            "duplicate start"
        );
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    machine.markReady(generation, "", "ok", "/workspace");
                }
            },
            "blank version"
        );
    }

    private static void stopInvalidatesInFlightCompletion() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        long generation = machine.beginStart();
        machine.stop();
        TestSupport.assertFalse(
            machine.markReady(generation, "late", "late", "/late"),
            "completion after stop must be ignored"
        );
        TestSupport.assertEquals(
            RuntimePhase.STOPPED,
            machine.snapshot().getPhase(),
            "stop must remain authoritative"
        );
    }
}
