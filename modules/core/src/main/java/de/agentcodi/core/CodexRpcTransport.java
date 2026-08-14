package de.agentcodi.core;

import java.io.Closeable;
import java.io.IOException;

public interface CodexRpcTransport extends Closeable {
    String readLine(int maximumBytes) throws IOException;

    void writeLine(String line, int maximumBytes) throws IOException;

    /**
     * Writes one JSONL payload without retaining it. Implementations must clear the supplied
     * mutable buffer before returning or throwing.
     */
    void writeBytes(byte[] line, int length, int maximumBytes) throws IOException;

    @Override
    void close() throws IOException;
}
