package de.agentcodi.connectors.client;

import de.agentcodi.connectors.ConnectorCatalogSnapshot;
import de.agentcodi.connectors.ConnectorInfo;
import de.agentcodi.connectors.ConnectorPhase;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.CodexCatalogRpc;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ConnectorCatalogController implements AutoCloseable {
    private static final long PARALLEL_ESSENTIAL_TIMEOUT_MS = 9_000L;

    private final ConnectorCatalogLoader loader;
    private final ExecutorService executor;
    private final ExecutorService runtimeExecutor;
    private final ExecutorService detailsExecutor;
    private ConnectorCatalogSnapshot snapshot = ConnectorCatalogSnapshot.stopped();
    private boolean refreshActive;
    private boolean closed;

    public ConnectorCatalogController(CodexCatalogRpc rpc) {
        loader = new ConnectorCatalogLoader(rpc);
        executor = newExecutor("agentcodi-connector-catalog");
        runtimeExecutor = newExecutor("agentcodi-connector-runtime");
        detailsExecutor = newExecutor("agentcodi-connector-details");
    }

    public synchronized ConnectorCatalogSnapshot snapshot() {
        return snapshot;
    }

    public boolean refresh(boolean forceRefetch) {
        return refresh(forceRefetch, forceRefetch);
    }

    public synchronized boolean refresh(
        boolean forceDirectoryRefetch,
        boolean forceInstalledRefresh
    ) {
        if (closed || refreshActive) {
            return false;
        }
        refreshActive = true;
        final boolean forceDirectory = forceDirectoryRefetch;
        final boolean forceInstalled = forceInstalledRefresh;
        final long revision = snapshot.getRevision() + 1L;
        final ConnectorCatalogSnapshot previous = snapshot;
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
                    ConnectorCatalogLoader.LoadState state = null;
                    ConnectorCatalogSnapshot essential;
                    Future<ConnectorCatalogLoader.InstalledState> installedFuture = null;
                    long deadlineNanos = System.nanoTime()
                        + PARALLEL_ESSENTIAL_TIMEOUT_MS * 1_000_000L;
                    try {
                        installedFuture = queryInstalledAsync(
                            threadId,
                            forceInstalled
                        );
                        state = loader.loadDirectoryState(
                            revision,
                            forceDirectory,
                            threadId
                        );
                        ConnectorCatalogSnapshot directory = loader.directorySnapshot(state);
                        publishIntermediate(revision, threadId, directory);
                        try {
                            ConnectorCatalogLoader.InstalledState installed = awaitInstalled(
                                installedFuture,
                                deadlineNanos
                            );
                            essential = loader.applyInstalledState(state, installed);
                        } catch (Exception error) {
                            essential = loader.degradedSnapshot(
                                revision,
                                ConnectorPhase.PARTIAL,
                                threadId,
                                directory
                            );
                        }
                    } catch (Exception error) {
                        if (installedFuture != null) {
                            installedFuture.cancel(true);
                        }
                        essential = loader.degradedSnapshot(
                            revision,
                            ConnectorPhase.FAILED,
                            threadId,
                            previous
                        );
                    }
                    boolean refreshChangedThread = finishRefresh(
                        revision,
                        threadId,
                        essential
                    );
                    if (refreshChangedThread) {
                        refresh(false, false);
                        return;
                    }
                    if (state != null
                        && (essential.getPhase() == ConnectorPhase.READY
                            || essential.getPhase() == ConnectorPhase.PARTIAL)) {
                        scheduleOptionalDetails(
                            revision,
                            threadId,
                            state,
                            essential.getPhase()
                        );
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            refreshActive = false;
            snapshot = loader.degradedSnapshot(
                revision,
                ConnectorPhase.FAILED,
                threadId,
                previous
            );
            return false;
        }
    }

    public synchronized boolean refreshInstalled(boolean forceRefresh) {
        if (closed || refreshActive) {
            return false;
        }
        final String threadId;
        try {
            threadId = loader.currentThreadId();
        } catch (RuntimeException error) {
            return false;
        }
        final ConnectorCatalogSnapshot previous = snapshot;
        if (!threadId.equals(previous.getThreadId()) || !hasDirectoryState(previous)) {
            return false;
        }
        refreshActive = true;
        final long revision = previous.getRevision() + 1L;
        final boolean force = forceRefresh;
        snapshot = ConnectorCatalogSnapshot.loading(revision, threadId, previous);
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    ConnectorCatalogSnapshot loaded;
                    try {
                        loaded = loader.refreshInstalled(
                            revision,
                            force,
                            threadId,
                            previous
                        );
                    } catch (Exception error) {
                        loaded = loader.degradedSnapshot(
                            revision,
                            ConnectorPhase.PARTIAL,
                            threadId,
                            previous
                        );
                    }
                    if (finishRefresh(revision, threadId, loaded)) {
                        refresh(false, false);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            refreshActive = false;
            snapshot = loader.degradedSnapshot(
                revision,
                ConnectorPhase.PARTIAL,
                threadId,
                previous
            );
            return false;
        }
    }

    private void publishIntermediate(
        long revision,
        String threadId,
        ConnectorCatalogSnapshot value
    ) {
        synchronized (this) {
            if (!closed && snapshot.getRevision() == revision
                && threadId.equals(safeCurrentThreadId())) {
                snapshot = value;
            }
        }
    }

    private synchronized boolean finishRefresh(
        long revision,
        String threadId,
        ConnectorCatalogSnapshot value
    ) {
        boolean changedThread = false;
        if (!closed && snapshot.getRevision() == revision) {
            String currentThreadId = safeCurrentThreadId();
            if (threadId.equals(currentThreadId)) {
                snapshot = value;
            } else {
                snapshot = ConnectorCatalogSnapshot.empty(
                    revision,
                    ConnectorPhase.FAILED,
                    currentThreadId
                );
                changedThread = true;
            }
        }
        refreshActive = false;
        return changedThread;
    }

    private synchronized void publishOptional(
        long revision,
        String threadId,
        ConnectorCatalogSnapshot value
    ) {
        if (!closed && snapshot.getRevision() == revision
            && threadId.equals(safeCurrentThreadId())) {
            snapshot = value;
        }
    }

    private void scheduleOptionalDetails(
        final long revision,
        final String threadId,
        final ConnectorCatalogLoader.LoadState state,
        final ConnectorPhase phase
    ) {
        try {
            detailsExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        ConnectorCatalogSnapshot enriched =
                            loader.enrichOptionalDetails(state, phase);
                        publishOptional(revision, threadId, enriched);
                    } catch (Exception ignored) {
                        // Display-only metadata never blocks sign-in or callability.
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Essential connector state has already been published.
        }
    }

    private Future<ConnectorCatalogLoader.InstalledState> queryInstalledAsync(
        final String threadId,
        final boolean forceRefresh
    ) {
        return runtimeExecutor.submit(new Callable<ConnectorCatalogLoader.InstalledState>() {
            @Override
            public ConnectorCatalogLoader.InstalledState call() throws Exception {
                return loader.queryInstalledState(threadId, forceRefresh);
            }
        });
    }

    private static ConnectorCatalogLoader.InstalledState awaitInstalled(
        Future<ConnectorCatalogLoader.InstalledState> future,
        long deadlineNanos
    ) throws Exception {
        if (future == null) {
            throw new IllegalStateException("Installed connector refresh did not start");
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            future.cancel(true);
            throw new TimeoutException("Installed connector refresh exceeded the budget");
        }
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        } catch (TimeoutException error) {
            future.cancel(true);
            throw error;
        }
    }

    private static ExecutorService newExecutor(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, name);
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private String safeCurrentThreadId() {
        try {
            return loader.currentThreadId();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static boolean hasDirectoryState(ConnectorCatalogSnapshot value) {
        for (ConnectorInfo connector : value.getConnectors()) {
            if (connector.isOffered()) {
                return true;
            }
        }
        return false;
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
        runtimeExecutor.shutdownNow();
        detailsExecutor.shutdownNow();
    }
}
