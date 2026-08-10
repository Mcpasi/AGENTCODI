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
        String workspace,
        String codexHome,
        String home,
        String temporaryDirectory,
        String nativeLibraryDirectory
    ) throws IOException {
        return nativeStartAppServer(
            executable,
            codeModeHostExecutable,
            workspace,
            codexHome,
            home,
            temporaryDirectory,
            nativeLibraryDirectory
        );
    }

    byte[] readAppServerLine(long handle, int maximumBytes) throws IOException {
        return nativeReadAppServerLine(handle, maximumBytes);
    }

    void writeAppServerLine(long handle, byte[] line, int maximumBytes) throws IOException {
        nativeWriteAppServerLine(handle, line, maximumBytes);
    }

    int stopAppServer(long handle, int timeoutMilliseconds) {
        return nativeStopAppServer(handle, timeoutMilliseconds);
    }

    private static native String nativeVersion();

    private static native int nativeSelfTest();

    private static native String nativeDiagnostics();

    private static native long nativeStartAppServer(
        String executable,
        String codeModeHostExecutable,
        String workspace,
        String codexHome,
        String home,
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
        int maximumBytes
    ) throws IOException;

    private static native int nativeStopAppServer(long handle, int timeoutMilliseconds);
}
