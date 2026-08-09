package de.agentcodi.core;

import java.io.IOException;

public final class CodexRpcException extends IOException {
    private final int code;

    public CodexRpcException(int code, String message) {
        super(message == null || message.trim().isEmpty() ? "Codex RPC failed" : message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
