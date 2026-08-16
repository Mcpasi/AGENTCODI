package de.agentcodi.runtime;

import de.agentcodi.core.CodexRpcTransport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

final class NativeAppServerTransport implements CodexRpcTransport {
    private static final int STOP_TIMEOUT_MILLISECONDS = 2_000;

    private final NativeEngine engine;
    private final AtomicLong handle;

    NativeAppServerTransport(
        NativeEngine engine,
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
        String temporaryDirectory,
        String nativeLibraryDirectory
    ) throws IOException {
        if (engine == null) {
            throw new IllegalArgumentException("Native engine is required");
        }
        this.engine = engine;
        long startedHandle = engine.startAppServer(
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
            temporaryDirectory,
            nativeLibraryDirectory
        );
        if (startedHandle <= 0L) {
            throw new IOException("Native app-server supervisor returned an invalid handle");
        }
        handle = new AtomicLong(startedHandle);
    }

    @Override
    public String readLine(int maximumBytes) throws IOException {
        long current = handle.get();
        if (current <= 0L) {
            return null;
        }
        byte[] bytes = engine.readAppServerLine(current, maximumBytes);
        if (bytes == null) {
            return null;
        }
        if (bytes.length > maximumBytes) {
            throw new IOException("Native app-server line exceeded the Java byte limit");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("App-server emitted invalid UTF-8", error);
        }
    }

    @Override
    public void writeLine(String line, int maximumBytes) throws IOException {
        if (line == null) {
            throw new IllegalArgumentException("App-server line must not be null");
        }
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maximumBytes) {
            throw new IOException("Outgoing app-server line exceeds the Java byte limit");
        }
        try {
            writeBytes(bytes, bytes.length, maximumBytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void writeBytes(byte[] line, int length, int maximumBytes) throws IOException {
        if (line == null || length <= 0 || length > line.length || length > maximumBytes) {
            throw new IOException("Outgoing app-server bytes exceed the Java byte limit");
        }
        try {
            long current = handle.get();
            if (current <= 0L) {
                throw new IOException("App-server transport is closed");
            }
            engine.writeAppServerLine(current, line, length, maximumBytes);
        } finally {
            Arrays.fill(line, (byte) 0);
        }
    }

    @Override
    public void close() {
        long current = handle.getAndSet(0L);
        if (current > 0L) {
            engine.stopAppServer(current, STOP_TIMEOUT_MILLISECONDS);
        }
    }
}
