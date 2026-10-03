package de.agentcodi.mode.compatibility;

import de.agentcodi.core.CodexExecutionMode;

/**
 * The only application execution mode in Package Edition.
 * Keeps the historical compatibility id for existing session metadata.
 */
public final class FullAccessExecutionMode implements CodexExecutionMode {
    private static final FullAccessExecutionMode INSTANCE = new FullAccessExecutionMode();

    private FullAccessExecutionMode() {
    }

    public static FullAccessExecutionMode get() {
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
