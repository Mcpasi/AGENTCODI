package de.agentcodi.mcp.client;

import de.agentcodi.core.CodexMcpConfigurationRpc;
import de.agentcodi.mcp.McpConfigurationNotice;
import de.agentcodi.mcp.McpConfigurationPhase;
import de.agentcodi.mcp.McpConfigurationSnapshot;
import de.agentcodi.mcp.McpServerConfiguration;
import de.agentcodi.mcp.McpServerDraft;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/** Serializes MCP configuration reads, atomic writes and explicit reloads. */
public final class McpConfigurationController implements AutoCloseable {
    private final CodexMcpConfigurationRpc rpc;
    private final McpConfigurationLoader loader;
    private final ExecutorService executor;
    private McpConfigurationSnapshot snapshot = McpConfigurationSnapshot.stopped();
    private boolean operationActive;
    private boolean closed;

    public McpConfigurationController(CodexMcpConfigurationRpc rpc) {
        if (rpc == null) {
            throw new IllegalArgumentException("MCP configuration RPC gateway is required");
        }
        this.rpc = rpc;
        loader = new McpConfigurationLoader(rpc);
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "agentcodi-mcp-configuration");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public synchronized McpConfigurationSnapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean refresh() {
        if (!begin(McpConfigurationPhase.LOADING)) {
            return false;
        }
        final long revision = snapshot.getRevision();
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    publishLoaded(loader.load(revision, McpConfigurationNotice.NONE));
                } catch (Exception error) {
                    publishFailure(revision, McpConfigurationNotice.READ_FAILED);
                }
            }
        }, revision, McpConfigurationNotice.READ_FAILED);
    }

    public synchronized boolean save(McpServerDraft draft) {
        if (draft == null || closed || operationActive
            || snapshot.getPhase() != McpConfigurationPhase.READY) {
            return false;
        }
        McpServerConfiguration existing = find(draft.getName());
        McpConfigurationMutations.Mutation mutation;
        try {
            if (existing == null) {
                mutation = McpConfigurationMutations.add(
                    draft,
                    snapshot.getExpectedVersion()
                );
            } else {
                if (!existing.isEditable()
                    || existing.getTransport() != draft.getTransport()) {
                    return false;
                }
                mutation = McpConfigurationMutations.update(
                    draft,
                    snapshot.getExpectedVersion()
                );
            }
        } catch (RuntimeException error) {
            return false;
        }
        return scheduleMutation(mutation);
    }

    public synchronized boolean setEnabled(String name, boolean enabled) {
        if (closed || operationActive || snapshot.getPhase() != McpConfigurationPhase.READY) {
            return false;
        }
        McpServerConfiguration existing = find(name);
        if (existing == null || !existing.isUserOwned()
            || !McpServerDraft.isSafeName(name) || existing.isEnabled() == enabled
            || (enabled && !"prompt".equals(existing.getApprovalMode()))) {
            return false;
        }
        try {
            return scheduleMutation(McpConfigurationMutations.setEnabled(
                name,
                enabled,
                snapshot.getExpectedVersion()
            ));
        } catch (RuntimeException error) {
            return false;
        }
    }

    public synchronized boolean delete(String name) {
        if (closed || operationActive || snapshot.getPhase() != McpConfigurationPhase.READY) {
            return false;
        }
        McpServerConfiguration existing = find(name);
        if (existing == null || !existing.isUserOwned() || !McpServerDraft.isSafeName(name)) {
            return false;
        }
        try {
            return scheduleMutation(McpConfigurationMutations.delete(
                name,
                snapshot.getExpectedVersion()
            ));
        } catch (RuntimeException error) {
            return false;
        }
    }

    public synchronized boolean reload() {
        if (!begin(McpConfigurationPhase.SAVING)) {
            return false;
        }
        final long revision = snapshot.getRevision();
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, Object> response = rpc.reloadMcpConfiguration(
                        McpConfigurationLoader.REQUEST_TIMEOUT_MS
                    );
                    if (!response.isEmpty()) {
                        throw new IllegalArgumentException("Unexpected MCP reload response");
                    }
                    publishLoaded(loader.load(revision, McpConfigurationNotice.RELOADED));
                } catch (Exception error) {
                    publishFailure(revision, McpConfigurationNotice.RELOAD_REQUIRED);
                }
            }
        }, revision, McpConfigurationNotice.RELOAD_REQUIRED);
    }

    private synchronized boolean scheduleMutation(
        final McpConfigurationMutations.Mutation mutation
    ) {
        if (!begin(McpConfigurationPhase.SAVING)) {
            return false;
        }
        final long revision = snapshot.getRevision();
        return submit(new Runnable() {
            @Override
            public void run() {
                boolean wrote = false;
                boolean reloaded = false;
                try {
                    Map<String, Object> response = rpc.writeMcpConfiguration(
                        mutation.parameters,
                        McpConfigurationLoader.REQUEST_TIMEOUT_MS
                    );
                    String status = response.get("status") instanceof String
                        ? (String) response.get("status") : "";
                    if (!"ok".equals(status) && !"okOverridden".equals(status)) {
                        throw new IllegalArgumentException("Unexpected MCP write response");
                    }
                    wrote = true;
                    Map<String, Object> reload = rpc.reloadMcpConfiguration(
                        McpConfigurationLoader.REQUEST_TIMEOUT_MS
                    );
                    if (!reload.isEmpty()) {
                        throw new IllegalArgumentException("Unexpected MCP reload response");
                    }
                    reloaded = true;
                    publishLoaded(loader.load(
                        revision,
                        "okOverridden".equals(status)
                            ? McpConfigurationNotice.APPLIED_OVERRIDDEN
                            : mutation.notice
                    ));
                } catch (Exception error) {
                    McpConfigurationNotice failure = !wrote
                        ? McpConfigurationNotice.WRITE_FAILED
                        : reloaded
                            ? McpConfigurationNotice.APPLIED_REFRESH_FAILED
                            : McpConfigurationNotice.RELOAD_REQUIRED;
                    try {
                        publishLoaded(loader.load(revision, failure));
                    } catch (Exception refreshError) {
                        publishFailure(
                            revision,
                            failure
                        );
                    }
                }
            }
        }, revision, McpConfigurationNotice.WRITE_FAILED);
    }

    private synchronized boolean begin(McpConfigurationPhase phase) {
        if (closed || operationActive) {
            return false;
        }
        operationActive = true;
        long revision = snapshot.getRevision() + 1L;
        snapshot = McpConfigurationSnapshot.carrying(revision, phase, snapshot);
        return true;
    }

    private boolean submit(
        Runnable task,
        long revision,
        McpConfigurationNotice failure
    ) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException error) {
            publishFailure(revision, failure);
            return false;
        }
    }

    private synchronized void publishLoaded(McpConfigurationSnapshot loaded) {
        if (!closed && snapshot.getRevision() == loaded.getRevision()) {
            snapshot = loaded;
        }
        operationActive = false;
    }

    private synchronized void publishFailure(long revision, McpConfigurationNotice notice) {
        if (!closed && snapshot.getRevision() == revision) {
            snapshot = McpConfigurationSnapshot.failed(revision, snapshot, notice);
        }
        operationActive = false;
    }

    private McpServerConfiguration find(String name) {
        if (name == null) {
            return null;
        }
        for (McpServerConfiguration server : snapshot.getServers()) {
            if (name.equals(server.getName())) {
                return server;
            }
        }
        return null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        operationActive = false;
        snapshot = new McpConfigurationSnapshot(
            snapshot.getRevision() + 1L,
            McpConfigurationPhase.STOPPED,
            McpConfigurationNotice.NONE,
            null,
            ""
        );
        executor.shutdownNow();
    }
}
