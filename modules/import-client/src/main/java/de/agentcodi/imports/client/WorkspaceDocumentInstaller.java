package de.agentcodi.imports.client;

import java.io.File;
import java.io.IOException;

/**
 * Installs one completed private import without replacing an existing name.
 * Implementations must make the missing-target check and the move one atomic
 * filesystem operation. On failure, the pending entry must remain available
 * for caller-owned cleanup and an existing target must remain untouched.
 */
public interface WorkspaceDocumentInstaller {
    void installNoReplace(
        File workspaceDirectory,
        String pendingName,
        String finalName,
        long expectedByteCount
    ) throws IOException;
}
