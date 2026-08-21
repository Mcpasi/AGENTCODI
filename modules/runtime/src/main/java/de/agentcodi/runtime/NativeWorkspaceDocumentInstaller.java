package de.agentcodi.runtime;

import de.agentcodi.imports.client.WorkspaceDocumentInstaller;

import java.io.File;
import java.io.IOException;

/** Uses the existing JNI gateway for one descriptor-relative no-replace move. */
final class NativeWorkspaceDocumentInstaller implements WorkspaceDocumentInstaller {
    private static final WorkspaceDocumentInstaller INSTANCE =
        new NativeWorkspaceDocumentInstaller();

    private NativeWorkspaceDocumentInstaller() {
    }

    static WorkspaceDocumentInstaller instance() {
        return INSTANCE;
    }

    @Override
    public void installNoReplace(
        File workspaceDirectory,
        String pendingName,
        String finalName,
        long expectedByteCount
    ) throws IOException {
        if (workspaceDirectory == null) {
            throw new IllegalArgumentException("workspaceDirectory must not be null");
        }
        NativeEngine.installWorkspaceImportNoReplace(
            workspaceDirectory.getCanonicalPath(),
            pendingName,
            finalName,
            expectedByteCount
        );
    }
}
