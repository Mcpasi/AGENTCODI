package de.agentcodi.tests;

import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.RuntimeStateMachine;

public final class EditionPermissionContractTest {
    private EditionPermissionContractTest() { }

    public static int run() {
        RuntimeStateMachine runtime = new RuntimeStateMachine();
        runtime.beginStart();
        TestSupport.assertEquals(":danger-full-access",
            runtime.snapshot().getPermissionProfileId(), "default startup uses Full access");
        runtime.stop();
        runtime.beginStart();
        TestSupport.assertEquals(CodexExecutionMode.COMPATIBILITY_ID,
            runtime.snapshot().getExecutionModeId(), "service restart preserves edition mode");
        runtime.stop();
        Object before = runtime.snapshot();
        TestSupport.expectThrows(IllegalArgumentException.class,
            () -> runtime.beginStart(CodexExecutionMode.COMPATIBILITY_ID,
                CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID, false, true),
            "legacy JIT startup is rejected");
        TestSupport.assertTrue(before == runtime.snapshot(),
            "rejected legacy configuration leaves runtime state unchanged");
        return 3;
    }
}
