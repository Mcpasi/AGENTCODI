package de.agentcodi.core;

import java.util.Map;

/**
 * Narrow gateway for bounded, read-only capability discovery through the active app-server.
 */
public interface CodexCatalogRpc {
    String catalogThreadId();

    Map<String, Object> requestCatalog(
        String method,
        Map<String, Object> params,
        long timeoutMilliseconds
    ) throws Exception;
}
