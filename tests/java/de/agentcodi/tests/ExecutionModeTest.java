package de.agentcodi.tests;

import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.mode.compatibility.CompatibilityExecutionMode;
import de.agentcodi.mode.protectedmode.ProtectedExecutionMode;

public final class ExecutionModeTest {
    private ExecutionModeTest() {
    }

    public static int run() throws Exception {
        protectedModeUsesWorkspaceProfile();
        compatibilityModeRequiresWarningAcknowledgement();
        compatibilityModeUsesBuiltInFullAccessProfile();
        return 3;
    }

    private static void protectedModeUsesWorkspaceProfile() {
        CodexExecutionMode mode = ProtectedExecutionMode.get();
        TestSupport.assertEquals(
            CodexExecutionMode.PROTECTED_ID,
            mode.getId(),
            "protected mode id"
        );
        TestSupport.assertEquals(
            "agentcodi-workspace",
            mode.getPermissionProfileId(),
            "protected permission profile"
        );
        TestSupport.assertFalse(mode.isDangerous(), "protected mode danger marker");
    }

    private static void compatibilityModeRequiresWarningAcknowledgement() {
        TestSupport.expectThrows(
            SecurityException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CompatibilityExecutionMode.afterWarningAcknowledged(false);
                }
            },
            "compatibility mode without warning acknowledgement"
        );
    }

    private static void compatibilityModeUsesBuiltInFullAccessProfile() {
        CodexExecutionMode mode =
            CompatibilityExecutionMode.afterWarningAcknowledged(true);
        TestSupport.assertEquals(
            CodexExecutionMode.COMPATIBILITY_ID,
            mode.getId(),
            "compatibility mode id"
        );
        TestSupport.assertEquals(
            ":danger-full-access",
            mode.getPermissionProfileId(),
            "compatibility permission profile"
        );
        TestSupport.assertTrue(mode.isDangerous(), "compatibility danger marker");
    }
}
