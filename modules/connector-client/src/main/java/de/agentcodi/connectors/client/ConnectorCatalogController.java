package de.agentcodi.connectors.client;

import de.agentcodi.connectors.ConnectorCatalogSnapshot;
import de.agentcodi.connectors.ConnectorInfo;
import de.agentcodi.connectors.ConnectorPhase;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.CodexCatalogRpc;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

public final class ConnectorCatalogController implements AutoCloseable {
    private final ConnectorCatalogLoader loader;
    private final ExecutorService executor;
    private ConnectorCatalogSnapshot snapshot = ConnectorCatalogSnapshot.stopped();
    private boolean refreshActive;
    private boolean closed;

    public ConnectorCatalogController(CodexCatalogRpc rpc) {
        loader = new ConnectorCatalogLoader(rpc);
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "agentcodi-connector-catalog");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public synchronized ConnectorCatalogSnapshot snapshot() {
        return snapshot;
    }

    public synchronized boolean refresh(boolean forceRefetch) {
        if (closed || refreshActive) {
            return false;
        }
        refreshActive = true;
        final boolean force = forceRefetch;
        final long revision = snapshot.getRevision() + 1L;
        final String threadId;
        try {
            threadId = loader.currentThreadId();
        } catch (RuntimeException error) {
            refreshActive = false;
            snapshot = ConnectorCatalogSnapshot.empty(
                revision,
                ConnectorPhase.FAILED,
                ""
            );
            return false;
        }
        snapshot = ConnectorCatalogSnapshot.loading(revision, threadId, snapshot);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    ConnectorCatalogSnapshot loaded;
                    try {
                        loaded = loader.load(revision, force, threadId);
                    } catch (RuntimeException error) {
                        loaded = ConnectorCatalogSnapshot.empty(
                            revision,
                            ConnectorPhase.FAILED,
                            threadId
                        );
                    }
                    boolean refreshChangedThread = false;
                    synchronized (ConnectorCatalogController.this) {
                        if (!closed && snapshot.getRevision() == revision) {
                            String currentThreadId;
                            try {
                                currentThreadId = loader.currentThreadId();
                            } catch (RuntimeException error) {
                                currentThreadId = "";
                            }
                            if (threadId.equals(currentThreadId)) {
                                snapshot = loaded;
                            } else {
                                snapshot = ConnectorCatalogSnapshot.empty(
                                    revision,
                                    ConnectorPhase.FAILED,
                                    currentThreadId
                                );
                                refreshChangedThread = true;
                            }
                        }
                        refreshActive = false;
                    }
                    if (refreshChangedThread) {
                        refresh(true);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            refreshActive = false;
            snapshot = ConnectorCatalogSnapshot.empty(
                revision,
                ConnectorPhase.FAILED,
                threadId
            );
            return false;
        }
    }

    public synchronized boolean areCallable(List<ConnectorSelection> selections) {
        List<ConnectorSelection> safe;
        try {
            safe = ConnectorSelection.copyOf(selections);
        } catch (RuntimeException error) {
            return false;
        }
        if (snapshot.getPhase() != ConnectorPhase.READY
            && snapshot.getPhase() != ConnectorPhase.PARTIAL) {
            return false;
        }
        final String currentThreadId;
        try {
            currentThreadId = loader.currentThreadId();
        } catch (RuntimeException error) {
            return false;
        }
        if (!snapshot.getThreadId().equals(currentThreadId)) {
            return false;
        }
        for (ConnectorSelection selection : safe) {
            ConnectorInfo current = snapshot.find(selection.getProvider());
            if (!current.isCallable()
                || !current.getId().equals(selection.getId())
                || !current.getName().equals(selection.getName())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        refreshActive = false;
        snapshot = ConnectorCatalogSnapshot.empty(
            snapshot.getRevision() + 1L,
            ConnectorPhase.STOPPED,
            ""
        );
        executor.shutdownNow();
    }
}
