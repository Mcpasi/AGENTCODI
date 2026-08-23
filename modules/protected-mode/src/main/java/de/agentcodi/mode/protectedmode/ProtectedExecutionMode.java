package de.agentcodi.mode.protectedmode;

import de.agentcodi.core.CodexExecutionMode;

/** Default workspace-confined execution mode. */
public final class ProtectedExecutionMode implements CodexExecutionMode {
    private static final ProtectedExecutionMode INSTANCE = new ProtectedExecutionMode();

    private ProtectedExecutionMode() {
    }

    public static ProtectedExecutionMode get() {
        return INSTANCE;
    }

    @Override
    public String getId() {
        return PROTECTED_ID;
    }

    @Override
    public String getPermissionProfileId() {
        return PROTECTED_PERMISSION_PROFILE_ID;
    }

    @Override
    public boolean isDangerous() {
        return false;
    }
}
