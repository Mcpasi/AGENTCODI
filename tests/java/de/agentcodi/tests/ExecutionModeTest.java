package de.agentcodi.tests;

import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.mode.compatibility.FullAccessExecutionMode;

public final class ExecutionModeTest {
    private ExecutionModeTest() { }

    public static int run() {
        packageEditionAlwaysUsesFullAccess();
        return 1;
    }

    private static void packageEditionAlwaysUsesFullAccess() {
        CodexExecutionMode mode = FullAccessExecutionMode.get();
        TestSupport.assertTrue(mode == FullAccessExecutionMode.get(), "stable edition mode");
        TestSupport.assertEquals(CodexExecutionMode.COMPATIBILITY_ID,
            mode.getId(), "historical session mode id");
        TestSupport.assertEquals(":danger-full-access",
            mode.getPermissionProfileId(), "edition always uses full access");
        TestSupport.assertTrue(mode.isDangerous(), "full access remains visibly marked");
        TestSupport.assertFalse(CodexExecutionMode.supportsJustInTimeApprovals(
            mode.getId(), mode.getPermissionProfileId()), "legacy JIT is unavailable");
    }
}
