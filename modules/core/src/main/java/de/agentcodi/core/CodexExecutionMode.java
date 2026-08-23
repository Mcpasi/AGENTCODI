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

    String getId();

    String getPermissionProfileId();

    boolean isDangerous();
}
