package de.agentcodi.core;

import java.io.Closeable;
import java.io.IOException;

public interface CodexRpcTransport extends Closeable {
    String readLine(int maximumBytes) throws IOException;

    void writeLine(String line, int maximumBytes) throws IOException;

    @Override
    void close() throws IOException;
}
