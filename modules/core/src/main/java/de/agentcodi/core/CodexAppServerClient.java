package de.agentcodi.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CodexAppServerClient implements AutoCloseable {
    public static final int MAX_INCOMING_BYTES = 1024 * 1024;
    public static final int MAX_OUTGOING_BYTES = 256 * 1024;
    public static final long MAX_REQUEST_ID = Integer.MAX_VALUE;

    public interface Listener {
        void onNotification(String method, Map<String, Object> params);

        void onTransportClosed(Throwable error);
    }

    private final CodexRpcTransport transport;
    private final Listener listener;
    private final AtomicLong nextRequestId = new AtomicLong(1L);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object pendingLock = new Object();
    private final Object writeLock = new Object();
    private final Map<Long, PendingResponse> pending = new HashMap<Long, PendingResponse>();
    private volatile boolean initialized;
    private volatile Thread readerThread;

    public CodexAppServerClient(CodexRpcTransport transport, Listener listener) {
        if (transport == null || listener == null) {
            throw new IllegalArgumentException("transport and listener are required");
        }
        this.transport = transport;
        this.listener = listener;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Codex app-server client already started");
        }
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "agentcodi-rpc-reader");
        reader.setDaemon(true);
        readerThread = reader;
        reader.start();
    }

    public Map<String, Object> initialize(
        Map<String, Object> params,
        long timeoutMilliseconds
    ) throws Exception {
        if (!started.get() || closed.get()) {
            throw new IllegalStateException("Codex app-server client is not running");
        }
        if (initialized) {
            throw new IllegalStateException("Codex app-server client is already initialized");
        }
        Map<String, Object> result = requestInternal(
            "initialize",
            params,
            timeoutMilliseconds,
            true
        );
        sendNotification("initialized", Collections.<String, Object>emptyMap(), true);
        initialized = true;
        return result;
    }

    public Map<String, Object> request(
        String method,
        Map<String, Object> params,
        long timeoutMilliseconds
    ) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Codex app-server connection is not initialized");
        }
        return requestInternal(method, params, timeoutMilliseconds, false);
    }

    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("Codex app-server connection is not initialized");
        }
        sendNotification(method, params, false);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            transport.close();
        } catch (IOException ignored) {
            // Closing is best effort; pending requests are failed below.
        }
        failPending(new EOFException("Codex app-server connection closed"));
        Thread reader = readerThread;
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
    }

    private Map<String, Object> requestInternal(
        String method,
        Map<String, Object> params,
        long timeoutMilliseconds,
        boolean initializationRequest
    ) throws Exception {
        validateMethodAndTimeout(method, timeoutMilliseconds);
        if (closed.get()) {
            throw new EOFException("Codex app-server connection is closed");
        }
        if (!initializationRequest && !initialized) {
            throw new IllegalStateException("Codex app-server connection is not initialized");
        }

        long id = reserveRequestId();
        PendingResponse response = new PendingResponse();
        synchronized (pendingLock) {
            if (closed.get()) {
                throw new EOFException("Codex app-server connection is closed");
            }
            pending.put(Long.valueOf(id), response);
        }

        Map<String, Object> message = JsonCodec.object("method", method, "id", Long.valueOf(id));
        if (params != null) {
            message.put("params", params);
        }
        try {
            writeMessage(message);
        } catch (Throwable error) {
            synchronized (pendingLock) {
                pending.remove(Long.valueOf(id));
            }
            throw error;
        }

        boolean received;
        try {
            received = response.latch.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (pendingLock) {
                pending.remove(Long.valueOf(id));
            }
            throw error;
        }
        if (!received) {
            synchronized (pendingLock) {
                pending.remove(Long.valueOf(id));
            }
            throw new TimeoutException("Codex RPC timed out: " + method);
        }
        if (response.failure != null) {
            if (response.failure instanceof Exception) {
                throw (Exception) response.failure;
            }
            throw new IOException("Codex RPC failed", response.failure);
        }
        if (response.error != null) {
            int code = (int) JsonCodec.longValue(response.error.get("code"), -32000L);
            String messageText = JsonCodec.optionalString(response.error.get("message"));
            throw new CodexRpcException(code, CrashReportFormatter.redact(messageText));
        }
        return response.result == null
            ? Collections.<String, Object>emptyMap()
            : response.result;
    }

    private void sendNotification(
        String method,
        Map<String, Object> params,
        boolean initializationNotification
    ) throws IOException {
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("RPC method must not be blank");
        }
        if (closed.get()) {
            throw new EOFException("Codex app-server connection is closed");
        }
        if (!initializationNotification && !initialized) {
            throw new IllegalStateException("Codex app-server connection is not initialized");
        }
        Map<String, Object> message = JsonCodec.object("method", method);
        if (params != null) {
            message.put("params", params);
        }
        writeMessage(message);
    }

    private void writeMessage(Map<String, Object> message) throws IOException {
        String line = JsonCodec.stringify(message);
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_OUTGOING_BYTES) {
            throw new IOException("Outgoing Codex RPC message exceeds the byte limit");
        }
        synchronized (writeLock) {
            transport.writeLine(line, MAX_OUTGOING_BYTES);
        }
    }

    private void readLoop() {
        Throwable failure = null;
        try {
            while (!closed.get()) {
                String line = transport.readLine(MAX_INCOMING_BYTES);
                if (line == null) {
                    throw new EOFException("Codex app-server closed stdout");
                }
                handleIncoming(JsonCodec.parseObject(line));
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            boolean unexpected = !closed.get();
            closed.set(true);
            try {
                transport.close();
            } catch (IOException ignored) {
                // Preserve the first transport failure.
            }
            Throwable cause = failure == null
                ? new EOFException("Codex app-server connection closed")
                : failure;
            failPending(cause);
            if (unexpected) {
                try {
                    listener.onTransportClosed(cause);
                } catch (Throwable ignored) {
                    // Listener failures must never revive the transport loop.
                }
            }
        }
    }

    private void handleIncoming(Map<String, Object> message) throws IOException {
        Object idValue = message.get("id");
        String method = JsonCodec.optionalString(message.get("method"));
        Long id = validatedIncomingId(idValue);
        if (id != null && !method.isEmpty()) {
            rejectServerRequest(id.longValue());
            return;
        }
        if (id != null) {
            completePending(id.longValue(), message);
            return;
        }
        if (!method.isEmpty()) {
            Map<String, Object> params = message.get("params") == null
                ? Collections.<String, Object>emptyMap()
                : JsonCodec.requireObject(message.get("params"), "notification params");
            try {
                listener.onNotification(method, params);
            } catch (Throwable ignored) {
                // A malformed optional event must not break request correlation.
            }
            return;
        }
        throw new IOException("Malformed Codex RPC message");
    }

    private long reserveRequestId() throws IOException {
        while (true) {
            long candidate = nextRequestId.get();
            if (candidate <= 0L || candidate > MAX_REQUEST_ID) {
                throw new IOException("Codex RPC request ID limit exhausted");
            }
            if (nextRequestId.compareAndSet(candidate, candidate + 1L)) {
                return candidate;
            }
        }
    }

    private static Long validatedIncomingId(Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Long)) {
            throw new IOException("Codex RPC ID must be an integer");
        }
        long id = ((Long) value).longValue();
        if (id < 0L || id > MAX_REQUEST_ID) {
            throw new IOException("Codex RPC ID is outside the allowed range");
        }
        return Long.valueOf(id);
    }

    private void completePending(long id, Map<String, Object> message) {
        PendingResponse response;
        synchronized (pendingLock) {
            response = pending.remove(Long.valueOf(id));
        }
        if (response == null) {
            return;
        }
        try {
            if (message.containsKey("error")) {
                response.error = JsonCodec.requireObject(message.get("error"), "RPC error");
            } else if (message.containsKey("result")) {
                Object result = message.get("result");
                response.result = result == null
                    ? Collections.<String, Object>emptyMap()
                    : JsonCodec.requireObject(result, "RPC result");
            } else {
                response.failure = new IOException("RPC response has no result or error");
            }
        } catch (Throwable error) {
            response.failure = error;
        } finally {
            response.latch.countDown();
        }
    }

    private void rejectServerRequest(long id) throws IOException {
        Map<String, Object> error = JsonCodec.object(
            "code", Long.valueOf(-32601L),
            "message", "Client request is not supported"
        );
        writeMessage(JsonCodec.object("id", Long.valueOf(id), "error", error));
    }

    private void failPending(Throwable error) {
        Map<Long, PendingResponse> failures;
        synchronized (pendingLock) {
            failures = new HashMap<Long, PendingResponse>(pending);
            pending.clear();
        }
        for (PendingResponse response : failures.values()) {
            response.failure = error;
            response.latch.countDown();
        }
    }

    private static void validateMethodAndTimeout(String method, long timeoutMilliseconds) {
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("RPC method must not be blank");
        }
        if (timeoutMilliseconds <= 0L || timeoutMilliseconds > 120_000L) {
            throw new IllegalArgumentException("RPC timeout is outside the allowed range");
        }
    }

    private static final class PendingResponse {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Map<String, Object> result;
        private volatile Map<String, Object> error;
        private volatile Throwable failure;
    }
}
