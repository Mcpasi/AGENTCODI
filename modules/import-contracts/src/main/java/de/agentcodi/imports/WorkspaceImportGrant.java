package de.agentcodi.imports;

/**
 * Immutable, URI-free projection of the transient read permission returned by
 * Android's document picker. This value is only valid for the in-memory import
 * operation that received the corresponding result intent and must not be
 * persisted.
 */
public final class WorkspaceImportGrant {
    private final boolean transientReadPermission;

    private WorkspaceImportGrant(boolean transientReadPermission) {
        this.transientReadPermission = transientReadPermission;
    }

    public static WorkspaceImportGrant fromResultIntentFlags(
        int resultIntentFlags,
        int readPermissionFlag
    ) {
        if (readPermissionFlag <= 0
            || Integer.bitCount(readPermissionFlag) != 1) {
            throw new IllegalArgumentException(
                "Read-permission flag must contain exactly one positive bit"
            );
        }
        return new WorkspaceImportGrant(
            (resultIntentFlags & readPermissionFlag) == readPermissionFlag
        );
    }

    public boolean hasTransientReadPermission() {
        return transientReadPermission;
    }
}
