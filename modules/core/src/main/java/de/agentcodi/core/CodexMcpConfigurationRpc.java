package de.agentcodi.core;

import java.util.Map;

/**
 * Narrow gateway for MCP configuration owned by the active Codex app-server.
 * Implementations choose the canonical user configuration target and never expose a file path
 * to callers.
 */
public interface CodexMcpConfigurationRpc {
    static boolean isValidWriteRequest(Map<String, Object> parameters) {
        return CodexMcpConfigurationRequestValidator.isValidWrite(parameters);
    }

    Map<String, Object> readMcpConfiguration(long timeoutMilliseconds) throws Exception;

    Map<String, Object> writeMcpConfiguration(
        Map<String, Object> parameters,
        long timeoutMilliseconds
    ) throws Exception;

    Map<String, Object> reloadMcpConfiguration(long timeoutMilliseconds) throws Exception;
}
