package de.agentcodi.runtime;

import java.io.IOException;

public final class NativeEngine {
    static {
        System.loadLibrary("agentcodi");
    }

    public String version() {
        return nativeVersion();
    }

    public int selfTest() {
        return nativeSelfTest();
    }

    public String diagnostics() {
        return nativeDiagnostics();
    }

    long startAppServer(
        String executable,
        String codeModeHostExecutable,
        String shellExecutable,
        String nodeExecutable,
        String pythonExecutable,
        String workspace,
        String toolchain,
        String toolBinaryDirectory,
        String toolRuntimeDirectory,
        String codexHome,
        String home,
        String stateDirectory,
        String temporaryDirectory,
        String nativeLibraryDirectory
    ) throws IOException {
        return nativeStartAppServer(
            executable,
            codeModeHostExecutable,
            shellExecutable,
            nodeExecutable,
            pythonExecutable,
            workspace,
            toolchain,
            toolBinaryDirectory,
            toolRuntimeDirectory,
            codexHome,
            home,
            stateDirectory,
            temporaryDirectory,
            nativeLibraryDirectory
        );
    }

    byte[] readAppServerLine(long handle, int maximumBytes) throws IOException {
        return nativeReadAppServerLine(handle, maximumBytes);
    }

    void writeAppServerLine(
        long handle,
        byte[] line,
        int length,
        int maximumBytes
    ) throws IOException {
        nativeWriteAppServerLine(handle, line, length, maximumBytes);
    }

    int stopAppServer(long handle, int timeoutMilliseconds) {
        return nativeStopAppServer(handle, timeoutMilliseconds);
    }

    static long openWorkspaceFile(
        String workspace,
        String relativePath,
        long maximumBytes
    ) throws IOException {
        return nativeOpenWorkspaceFile(workspace, relativePath, maximumBytes);
    }

    static long[] workspaceFileMetadata(long handle) throws IOException {
        return nativeWorkspaceFileMetadata(handle);
    }

    static int readWorkspaceFile(
        long handle,
        byte[] destination,
        int offset,
        int length
    ) throws IOException {
        return nativeReadWorkspaceFile(handle, destination, offset, length);
    }

    static void verifyWorkspaceFile(long handle) throws IOException {
        nativeVerifyWorkspaceFile(handle);
    }

    static void closeWorkspaceFile(long handle) {
        nativeCloseWorkspaceFile(handle);
    }

    static void installWorkspaceImportNoReplace(
        String workspace,
        String pendingName,
        String finalName,
        long expectedByteCount
    ) throws IOException {
        nativeInstallWorkspaceImportNoReplace(
            workspace,
            pendingName,
            finalName,
            expectedByteCount
        );
    }

    private static native String nativeVersion();

    private static native int nativeSelfTest();

    private static native String nativeDiagnostics();

    private static native long nativeStartAppServer(
        String executable,
        String codeModeHostExecutable,
        String shellExecutable,
        String nodeExecutable,
        String pythonExecutable,
        String workspace,
        String toolchain,
        String toolBinaryDirectory,
        String toolRuntimeDirectory,
        String codexHome,
        String home,
        String stateDirectory,
        String temporaryDirectory,
        String nativeLibraryDirectory
    ) throws IOException;

    private static native byte[] nativeReadAppServerLine(
        long handle,
        int maximumBytes
    ) throws IOException;

    private static native void nativeWriteAppServerLine(
        long handle,
        byte[] line,
        int length,
        int maximumBytes
    ) throws IOException;

    private static native int nativeStopAppServer(long handle, int timeoutMilliseconds);

    private static native long nativeOpenWorkspaceFile(
        String workspace,
        String relativePath,
        long maximumBytes
    ) throws IOException;

    private static native long[] nativeWorkspaceFileMetadata(long handle)
        throws IOException;

    private static native int nativeReadWorkspaceFile(
        long handle,
        byte[] destination,
        int offset,
        int length
    ) throws IOException;

    private static native void nativeVerifyWorkspaceFile(long handle)
        throws IOException;

    private static native void nativeCloseWorkspaceFile(long handle);

    private static native void nativeInstallWorkspaceImportNoReplace(
        String workspace,
        String pendingName,
        String finalName,
        long expectedByteCount
    ) throws IOException;
}
