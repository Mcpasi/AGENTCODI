package de.agentcodi.mode.compatibility;

import de.agentcodi.core.CodexExecutionMode;

/**
 * Experimental Android compatibility mode backed by the app-server's built-in
 * full-access permission profile. Callers can obtain it only after recording
 * the immediately preceding native warning acknowledgement.
 */
public final class CompatibilityExecutionMode implements CodexExecutionMode {
    private static final CompatibilityExecutionMode INSTANCE =
        new CompatibilityExecutionMode();

    private CompatibilityExecutionMode() {
    }

    public static CompatibilityExecutionMode afterWarningAcknowledged(
        boolean warningAcknowledged
    ) {
        if (!warningAcknowledged) {
            throw new SecurityException(
                "Compatibility mode requires an explicit warning acknowledgement"
            );
        }
        return INSTANCE;
    }

    @Override
    public String getId() {
        return COMPATIBILITY_ID;
    }

    @Override
    public String getPermissionProfileId() {
        return COMPATIBILITY_PERMISSION_PROFILE_ID;
    }

    @Override
    public boolean isDangerous() {
        return true;
    }
}
