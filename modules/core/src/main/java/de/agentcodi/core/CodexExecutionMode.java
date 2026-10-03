package de.agentcodi.core;

/**
 * Pure execution-mode contract for selecting an app-server permission profile.
 * Mode implementations intentionally carry no model instructions.
 */
public interface CodexExecutionMode {
    String COMPATIBILITY_ID = "compatibility";
    String COMPATIBILITY_PERMISSION_PROFILE_ID = ":danger-full-access";

    static boolean supportsJustInTimeApprovals(String modeId, String permissionProfileId) {
        return false; // Retained only to reject legacy JIT selections.
    }

    static void requireJustInTimeApprovalSupport(
        String modeId,
        String permissionProfileId,
        boolean enabled
    ) {
        if (enabled && !supportsJustInTimeApprovals(modeId, permissionProfileId)) {
            throw new IllegalArgumentException(
                "Package Edition does not support just-in-time permissions"
            );
        }
    }

    String getId();

    String getPermissionProfileId();

    boolean isDangerous();
}
