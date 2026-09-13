package de.agentcodi.core;

/**
 * Pure execution-mode contract for selecting an app-server permission profile.
 * Mode implementations intentionally carry no model instructions.
 */
public interface CodexExecutionMode {
    String PROTECTED_ID = "protected";
    String PROTECTED_PERMISSION_PROFILE_ID = "agentcodi-workspace";
    String COMPATIBILITY_ID = "compatibility";
    String COMPATIBILITY_PERMISSION_PROFILE_ID = ":danger-full-access";

    static boolean supportsJustInTimeApprovals(String modeId, String permissionProfileId) {
        return PROTECTED_ID.equals(modeId)
            && PROTECTED_PERMISSION_PROFILE_ID.equals(permissionProfileId);
    }

    static void requireJustInTimeApprovalSupport(
        String modeId,
        String permissionProfileId,
        boolean enabled
    ) {
        if (enabled && !supportsJustInTimeApprovals(modeId, permissionProfileId)) {
            throw new IllegalArgumentException(
                "Just-in-time permissions require the protected execution mode"
            );
        }
    }

    String getId();

    String getPermissionProfileId();

    boolean isDangerous();
}
