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
        readyRuntimeCanFailAndRestart();
        retainsSelectedExecutionModeAcrossRuntimeStates();
        return 7;
    }

    private static void initialStateIsIdle() {
        RuntimeSnapshot snapshot = new RuntimeStateMachine().snapshot();
        TestSupport.assertEquals(RuntimePhase.IDLE, snapshot.getPhase(), "initial phase");
        TestSupport.assertEquals(Long.valueOf(0L), Long.valueOf(snapshot.getGeneration()), "generation");
        TestSupport.assertFalse(
            snapshot.isCompatibilityApprovalsEnabled(),
            "compatibility approvals default off"
        );
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

    private static void readyRuntimeCanFailAndRestart() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        long first = machine.beginStart();
        TestSupport.assertTrue(
            machine.markReady(first, "native/1", "transport=stdio", "/private/workspace"),
            "runtime becomes ready before transport failure"
        );
        TestSupport.assertTrue(
            machine.markFailed(first, "App-server transport failed"),
            "active runtime failure accepted"
        );
        RuntimeSnapshot failed = machine.snapshot();
        TestSupport.assertEquals(RuntimePhase.FAILED, failed.getPhase(), "active failure phase");
        TestSupport.assertEquals(
            "/private/workspace",
            failed.getWorkspacePath(),
            "failure retains safe workspace diagnostics"
        );
        long second = machine.beginStart();
        TestSupport.assertTrue(second > first, "failed runtime can restart explicitly");
    }

    private static void retainsSelectedExecutionModeAcrossRuntimeStates() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        long generation = machine.beginStart(
            "compatibility",
            ":danger-full-access",
            true
        );
        RuntimeSnapshot starting = machine.snapshot();
        TestSupport.assertEquals(
            "compatibility",
            starting.getExecutionModeId(),
            "starting execution mode"
        );
        TestSupport.assertEquals(
            ":danger-full-access",
            starting.getPermissionProfileId(),
            "starting permission profile"
        );
        TestSupport.assertTrue(
            starting.isCompatibilityApprovalsEnabled(),
            "starting state retains compatibility approval choice"
        );
        TestSupport.assertTrue(
            machine.markReady(
                generation,
                "native/1",
                "transport=stdio",
                "/private/workspace"
            ),
            "compatibility runtime becomes ready"
        );
        machine.markFailed(generation, "transport failed");
        TestSupport.assertEquals(
            ":danger-full-access",
            machine.snapshot().getPermissionProfileId(),
            "failed state retains selected profile for diagnosis"
        );
        TestSupport.assertTrue(
            machine.snapshot().isCompatibilityApprovalsEnabled(),
            "failed state retains transient compatibility approval choice"
        );
        RuntimeSnapshot projected = machine.snapshot().withExecutionMode(
            "protected",
            "agentcodi-workspace",
            false
        );
        TestSupport.assertEquals(
            RuntimePhase.FAILED,
            projected.getPhase(),
            "mode projection preserves runtime phase"
        );
        TestSupport.assertEquals(
            "agentcodi-workspace",
            projected.getPermissionProfileId(),
            "mode projection can reflect a live session switch"
        );
        TestSupport.assertEquals(
            "transport=stdio",
            projected.getDiagnostics(),
            "mode projection preserves diagnostics"
        );
        TestSupport.assertFalse(
            projected.isCompatibilityApprovalsEnabled(),
            "protected projection clears compatibility approval choice"
        );
        machine.beginStart();
        TestSupport.assertEquals(
            "protected",
            machine.snapshot().getExecutionModeId(),
            "unconfirmed restart returns to protected mode"
        );
        TestSupport.assertFalse(
            machine.snapshot().isCompatibilityApprovalsEnabled(),
            "unconfirmed restart clears compatibility approval choice"
        );
    }
}
