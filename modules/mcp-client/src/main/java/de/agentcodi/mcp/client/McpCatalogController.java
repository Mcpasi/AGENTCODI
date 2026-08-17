package de.agentcodi.mcp.client;

import de.agentcodi.core.CodexCatalogRpc;
import de.agentcodi.mcp.McpCatalogPhase;
import de.agentcodi.mcp.McpCatalogSnapshot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

public final class McpCatalogController implements AutoCloseable {
    private final McpCatalogLoader loader;
    private final ExecutorService executor;
    private McpCatalogSnapshot snapshot = McpCatalogSnapshot.stopped();
    private boolean refreshActive;
    private boolean closed;

    public McpCatalogController(CodexCatalogRpc rpc, String workspacePath) {
        loader = new McpCatalogLoader(rpc, workspacePath);
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "agentcodi-mcp-catalog");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public synchronized McpCatalogSnapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean refresh() {
        if (closed || refreshActive) {
            return false;
        }
        refreshActive = true;
        final long revision = snapshot.getRevision() + 1L;
        snapshot = McpCatalogSnapshot.loading(revision, snapshot);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    McpCatalogSnapshot loaded;
                    try {
                        loaded = loader.load(revision);
                    } catch (RuntimeException error) {
                        loaded = McpCatalogSnapshot.failed(revision);
                    }
                    synchronized (McpCatalogController.this) {
                        if (!closed && snapshot.getRevision() == revision) {
                            snapshot = loaded;
                        }
                        refreshActive = false;
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            refreshActive = false;
            snapshot = McpCatalogSnapshot.empty(revision, McpCatalogPhase.FAILED);
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        refreshActive = false;
        snapshot = McpCatalogSnapshot.empty(
            snapshot.getRevision() + 1L,
            McpCatalogPhase.STOPPED
        );
        executor.shutdownNow();
    }
}
