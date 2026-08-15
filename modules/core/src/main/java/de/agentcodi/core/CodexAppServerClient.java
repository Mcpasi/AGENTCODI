package de.agentcodi.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
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
    public static final int MAX_PENDING_SERVER_REQUESTS = 16;
    static final long MAX_STREAMING_REQUEST_TIMEOUT_MS = 31L * 60L * 1000L;

    public interface Listener {
        boolean onServerRequest(
            long requestId,
            String method,
            Map<String, Object> params
        );

        void onNotification(String method, Map<String, Object> params);

        void onTransportClosed(Throwable error);
    }

    private final CodexRpcTransport transport;
    private final Listener listener;
    private final AtomicLong nextRequestId = new AtomicLong(1L);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object pendingLock = new Object();
    private final Object serverRequestLock = new Object();
    private final Object writeLock = new Object();
    private final Map<Long, PendingResponse> pending = new HashMap<Long, PendingResponse>();
    private final Map<Long, String> pendingServerRequests = new HashMap<Long, String>();
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

    Map<String, Object> requestStreaming(
        final String method,
        final Map<String, Object> params,
        long timeoutMilliseconds,
        Runnable afterWrite
    ) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Codex app-server connection is not initialized");
        }
        return requestPrepared(
            method,
            timeoutMilliseconds,
            false,
            new RequestWriter() {
                @Override
                public void write(long id) throws IOException {
                    Map<String, Object> message = JsonCodec.object(
                        "method", method,
                        "id", Long.valueOf(id)
                    );
                    if (params != null) {
                        message.put("params", params);
                    }
                    writeMessage(message);
                }
            },
            MAX_STREAMING_REQUEST_TIMEOUT_MS,
            afterWrite
        );
    }

    Map<String, Object> requestTerminalInput(
        final String processId,
        final byte[] input,
        long timeoutMilliseconds
    ) throws Exception {
        try {
            validateTerminalProcessId(processId);
            if (input == null || input.length == 0 || input.length > 16 * 1024) {
                throw new IllegalArgumentException("Terminal input is outside the byte limit");
            }
            if (!initialized) {
                throw new IllegalStateException("Codex app-server connection is not initialized");
            }
            return requestPrepared(
                "command/exec/write",
                timeoutMilliseconds,
                false,
                new RequestWriter() {
                    @Override
                    public void write(long id) throws IOException {
                        writeTerminalInputMessage(id, processId, input);
                    }
                }
            );
        } finally {
            if (input != null) {
                Arrays.fill(input, (byte) 0);
            }
        }
    }

    public Map<String, Object> requestApiKeyLogin(
        final char[] apiKey,
        long timeoutMilliseconds
    ) throws Exception {
        try {
            if (apiKey == null || apiKey.length < 8 || apiKey.length > 16 * 1024) {
                throw new IllegalArgumentException("API key length is outside the allowed range");
            }
            if (!initialized) {
                throw new IllegalStateException("Codex app-server connection is not initialized");
            }
            return requestPrepared(
                "account/login/start",
                timeoutMilliseconds,
                false,
                new RequestWriter() {
                    @Override
                    public void write(long id) throws IOException {
                        writeApiKeyLoginMessage(id, apiKey);
                    }
                }
            );
        } finally {
            wipe(apiKey);
        }
    }

    public void sendNotification(String method, Map<String, Object> params) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("Codex app-server connection is not initialized");
        }
        sendNotification(method, params, false);
    }

    public boolean respondToServerRequest(long requestId, Map<String, Object> result)
        throws IOException {
        if (!takeServerRequest(requestId)) {
            return false;
        }
        try {
            writeMessage(JsonCodec.object(
                "id", Long.valueOf(requestId),
                "result", result == null
                    ? Collections.<String, Object>emptyMap()
                    : result
            ));
            return true;
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    public boolean respondToServerRequestError(long requestId, int code, String message)
        throws IOException {
        if (!takeServerRequest(requestId)) {
            return false;
        }
        try {
            Map<String, Object> error = JsonCodec.object(
                "code", Long.valueOf(code),
                "message", message == null || message.trim().isEmpty()
                    ? "Client could not handle the request"
                    : message
            );
            writeMessage(JsonCodec.object("id", Long.valueOf(requestId), "error", error));
            return true;
        } catch (IOException writeError) {
            close();
            throw writeError;
        }
    }

    public boolean abandonServerRequest(long requestId) {
        synchronized (serverRequestLock) {
            return pendingServerRequests.remove(Long.valueOf(requestId)) != null;
        }
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
        clearServerRequests();
        Thread reader = readerThread;
        if (reader != null && reader != Thread.currentThread()) {
            reader.interrupt();
        }
    }

    private Map<String, Object> requestInternal(
        final String method,
        final Map<String, Object> params,
        long timeoutMilliseconds,
        boolean initializationRequest
    ) throws Exception {
        return requestPrepared(
            method,
            timeoutMilliseconds,
            initializationRequest,
            new RequestWriter() {
                @Override
                public void write(long id) throws IOException {
                    Map<String, Object> message = JsonCodec.object(
                        "method", method,
                        "id", Long.valueOf(id)
                    );
                    if (params != null) {
                        message.put("params", params);
                    }
                    writeMessage(message);
                }
            }
        );
    }

    private Map<String, Object> requestPrepared(
        String method,
        long timeoutMilliseconds,
        boolean initializationRequest,
        RequestWriter writer
    ) throws Exception {
        return requestPrepared(
            method,
            timeoutMilliseconds,
            initializationRequest,
            writer,
            120_000L,
            null
        );
    }

    private Map<String, Object> requestPrepared(
        String method,
        long timeoutMilliseconds,
        boolean initializationRequest,
        RequestWriter writer,
        long maximumTimeoutMilliseconds,
        Runnable afterWrite
    ) throws Exception {
        validateMethodAndTimeout(
            method,
            timeoutMilliseconds,
            maximumTimeoutMilliseconds
        );
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

        try {
            writer.write(id);
            if (afterWrite != null) {
                afterWrite.run();
            }
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
        byte[] encoded = line.getBytes(StandardCharsets.UTF_8);
        try {
            if (encoded.length > MAX_OUTGOING_BYTES) {
                throw new IOException("Outgoing Codex RPC message exceeds the byte limit");
            }
            synchronized (writeLock) {
                transport.writeBytes(encoded, encoded.length, MAX_OUTGOING_BYTES);
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private void writeApiKeyLoginMessage(long id, char[] apiKey) throws IOException {
        byte[] prefix = (
            "{\"method\":\"account/login/start\",\"id\":" + id
                + ",\"params\":{\"type\":\"apiKey\",\"apiKey\":\""
        ).getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\"}}".getBytes(StandardCharsets.US_ASCII);
        byte[] encoded = new byte[prefix.length + apiKey.length * 6 + suffix.length];
        int length = 0;
        try {
            System.arraycopy(prefix, 0, encoded, length, prefix.length);
            length += prefix.length;
            length = appendJsonStringUtf8(encoded, length, apiKey);
            System.arraycopy(suffix, 0, encoded, length, suffix.length);
            length += suffix.length;
            if (length > MAX_OUTGOING_BYTES) {
                throw new IOException("Outgoing Codex credential message exceeds the byte limit");
            }
            synchronized (writeLock) {
                transport.writeBytes(encoded, length, MAX_OUTGOING_BYTES);
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
            wipe(apiKey);
        }
    }

    private void writeTerminalInputMessage(long id, String processId, byte[] input)
        throws IOException {
        byte[] encodedInput = Base64.getEncoder().encode(input);
        byte[] prefix = (
            "{\"method\":\"command/exec/write\",\"id\":" + id
                + ",\"params\":{\"processId\":\"" + processId
                + "\",\"deltaBase64\":\""
        ).getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\"}}".getBytes(StandardCharsets.US_ASCII);
        byte[] message = new byte[prefix.length + encodedInput.length + suffix.length];
        try {
            System.arraycopy(prefix, 0, message, 0, prefix.length);
            System.arraycopy(encodedInput, 0, message, prefix.length, encodedInput.length);
            System.arraycopy(
                suffix,
                0,
                message,
                prefix.length + encodedInput.length,
                suffix.length
            );
            if (message.length > MAX_OUTGOING_BYTES) {
                throw new IOException("Outgoing terminal input exceeds the message limit");
            }
            synchronized (writeLock) {
                transport.writeBytes(message, message.length, MAX_OUTGOING_BYTES);
            }
        } finally {
            Arrays.fill(encodedInput, (byte) 0);
            Arrays.fill(message, (byte) 0);
            Arrays.fill(input, (byte) 0);
        }
    }

    private static int appendJsonStringUtf8(byte[] output, int offset, char[] value)
        throws IOException {
        final char[] hexadecimal = "0123456789abcdef".toCharArray();
        int cursor = offset;
        for (int index = 0; index < value.length; index++) {
            char character = value[index];
            if (character == '"' || character == '\\') {
                output[cursor++] = (byte) '\\';
                output[cursor++] = (byte) character;
            } else if (character <= 0x1f) {
                output[cursor++] = (byte) '\\';
                output[cursor++] = (byte) 'u';
                output[cursor++] = (byte) '0';
                output[cursor++] = (byte) '0';
                output[cursor++] = (byte) hexadecimal[(character >>> 4) & 0x0f];
                output[cursor++] = (byte) hexadecimal[character & 0x0f];
            } else if (character <= 0x7f) {
                output[cursor++] = (byte) character;
            } else if (character <= 0x7ff) {
                output[cursor++] = (byte) (0xc0 | (character >>> 6));
                output[cursor++] = (byte) (0x80 | (character & 0x3f));
            } else if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw new IOException("API key contains invalid UTF-16");
                }
                int codePoint = Character.toCodePoint(character, value[++index]);
                output[cursor++] = (byte) (0xf0 | (codePoint >>> 18));
                output[cursor++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3f));
                output[cursor++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3f));
                output[cursor++] = (byte) (0x80 | (codePoint & 0x3f));
            } else if (Character.isLowSurrogate(character)) {
                throw new IOException("API key contains invalid UTF-16");
            } else {
                output[cursor++] = (byte) (0xe0 | (character >>> 12));
                output[cursor++] = (byte) (0x80 | ((character >>> 6) & 0x3f));
                output[cursor++] = (byte) (0x80 | (character & 0x3f));
            }
        }
        return cursor;
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
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
            clearServerRequests();
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
            handleServerRequest(id.longValue(), method, message.get("params"));
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

    private void handleServerRequest(long id, String method, Object paramsValue)
        throws IOException {
        Map<String, Object> params = paramsValue == null
            ? Collections.<String, Object>emptyMap()
            : JsonCodec.requireObject(paramsValue, "server request params");
        boolean overloaded = false;
        synchronized (serverRequestLock) {
            Long key = Long.valueOf(id);
            if (pendingServerRequests.containsKey(key)) {
                throw new IOException("Duplicate Codex server request ID");
            }
            if (pendingServerRequests.size() >= MAX_PENDING_SERVER_REQUESTS) {
                overloaded = true;
            } else {
                pendingServerRequests.put(key, method);
            }
        }
        if (overloaded) {
            writeServerRequestError(
                id,
                -32000,
                "Client has too many pending server requests"
            );
            return;
        }

        boolean handled = false;
        try {
            handled = listener.onServerRequest(id, method, params);
        } catch (Throwable ignored) {
            respondToServerRequestError(id, -32603, "Client request handler failed");
            return;
        }
        if (!handled) {
            respondToServerRequestError(id, -32601, "Client request is not supported");
        }
    }

    private void writeServerRequestError(long id, int code, String message) throws IOException {
        Map<String, Object> error = JsonCodec.object(
            "code", Long.valueOf(code),
            "message", message
        );
        writeMessage(JsonCodec.object("id", Long.valueOf(id), "error", error));
    }

    private boolean takeServerRequest(long id) {
        if (id < 0L || id > MAX_REQUEST_ID) {
            throw new IllegalArgumentException("Server request ID is outside the allowed range");
        }
        synchronized (serverRequestLock) {
            return pendingServerRequests.remove(Long.valueOf(id)) != null;
        }
    }

    private void clearServerRequests() {
        synchronized (serverRequestLock) {
            pendingServerRequests.clear();
        }
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

    private static void validateTerminalProcessId(String processId) {
        if (processId == null || processId.isEmpty() || processId.length() > 128) {
            throw new IllegalArgumentException("Terminal process ID is invalid");
        }
        for (int index = 0; index < processId.length(); index++) {
            char character = processId.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_'
                || character == '.';
            if (!allowed) {
                throw new IllegalArgumentException("Terminal process ID is invalid");
            }
        }
    }

    private static void validateMethodAndTimeout(
        String method,
        long timeoutMilliseconds,
        long maximumTimeoutMilliseconds
    ) {
        if (method == null || method.trim().isEmpty()) {
            throw new IllegalArgumentException("RPC method must not be blank");
        }
        if (timeoutMilliseconds <= 0L
            || timeoutMilliseconds > maximumTimeoutMilliseconds) {
            throw new IllegalArgumentException("RPC timeout is outside the allowed range");
        }
    }

    private static final class PendingResponse {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Map<String, Object> result;
        private volatile Map<String, Object> error;
        private volatile Throwable failure;
    }

    private interface RequestWriter {
        void write(long id) throws IOException;
    }
}
