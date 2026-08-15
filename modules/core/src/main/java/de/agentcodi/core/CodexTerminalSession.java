package de.agentcodi.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

final class CodexTerminalSession implements AutoCloseable {
    static final String OUTPUT_DELTA_METHOD = "command/exec/outputDelta";
    static final String PERMISSION_PROFILE = "agentcodi-workspace";
    static final int MAXIMUM_INPUT_CHARACTERS = 4096;
    static final int MAXIMUM_INPUT_BYTES = 16 * 1024;
    static final int MAXIMUM_OUTPUT_CHUNK_BYTES = 64 * 1024;
    static final long OUTPUT_BYTES_CAP = 8L * 1024L * 1024L;
    static final long SERVER_TIMEOUT_MS = 30L * 60L * 1000L;
    static final long CLIENT_TIMEOUT_MS = SERVER_TIMEOUT_MS + 30_000L;
    static final long CONTROL_TIMEOUT_MS = 10_000L;

    private static final int STILL_RUNNING = Integer.MIN_VALUE;
    private static final long MAXIMUM_TOTAL_OUTPUT_BYTES = OUTPUT_BYTES_CAP * 2L;

    private final Object lock = new Object();
    private final CodexAppServerClient client;
    private final String workspacePath;
    private final String shellExecutable;
    private final TerminalOutputBuffer output = new TerminalOutputBuffer();
    private final ExecutorService commandOperations =
        Executors.newSingleThreadExecutor();
    private final ExecutorService controlOperations =
        Executors.newSingleThreadExecutor();

    private long generation;
    private long revision;
    private long receivedOutputBytes;
    private boolean starting;
    private boolean running;
    private boolean stopRequested;
    private boolean closed;
    private int rows = 24;
    private int columns = 80;
    private int exitCode = STILL_RUNNING;
    private String processId = "";
    private String failure = "";

    CodexTerminalSession(
        CodexAppServerClient client,
        String workspacePath,
        String shellExecutable
    ) {
        if (client == null) {
            throw new IllegalArgumentException("Codex app-server client is required");
        }
        this.client = client;
        this.workspacePath = requiredAbsolute(workspacePath, "Workspace path");
        this.shellExecutable = requiredAbsolute(shellExecutable, "Terminal shell path");
    }

    TerminalSessionSnapshot snapshot() {
        synchronized (lock) {
            return new TerminalSessionSnapshot(
                revision,
                running,
                starting,
                exitCode,
                output.snapshot(),
                safeFailure(failure)
            );
        }
    }

    void start(int requestedRows, int requestedColumns) {
        validateDimensions(requestedRows, requestedColumns);
        final long requestedGeneration;
        final String requestedProcessId;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Terminal session is closed");
            }
            rows = requestedRows;
            columns = requestedColumns;
            if (running) {
                scheduleResize(processId, requestedRows, requestedColumns, false);
                return;
            }
            if (starting) {
                return;
            }
            generation++;
            requestedGeneration = generation;
            requestedProcessId = "agentcodi-terminal-" + requestedGeneration;
            processId = requestedProcessId;
            receivedOutputBytes = 0L;
            starting = true;
            running = false;
            stopRequested = false;
            exitCode = STILL_RUNNING;
            failure = "";
            revision++;
        }
        try {
            commandOperations.execute(new Runnable() {
                @Override
                public void run() {
                    runTerminalCommand(requestedGeneration, requestedProcessId);
                }
            });
        } catch (RejectedExecutionException error) {
            failStart(requestedGeneration, error);
        }
    }

    void write(char[] input) throws IOException {
        byte[] encoded = null;
        ByteBuffer buffer = null;
        try {
            if (input == null || input.length == 0
                || input.length > MAXIMUM_INPUT_CHARACTERS) {
                throw new IOException("Terminal input exceeds the character limit");
            }
            if (CredentialGuard.containsLikelyCredential(input)) {
                throw new IOException("Credential-shaped terminal input was rejected");
            }
            try {
                buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(input));
            } catch (CharacterCodingException error) {
                throw new IOException("Terminal input is not valid UTF-16", error);
            }
            if (buffer.remaining() <= 0 || buffer.remaining() > MAXIMUM_INPUT_BYTES) {
                throw new IOException("Terminal input exceeds the byte limit");
            }
            encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
            final String currentProcessId;
            synchronized (lock) {
                if (closed || !running || processId.isEmpty()) {
                    throw new IOException("Terminal is not running");
                }
                currentProcessId = processId;
            }
            final byte[] outbound = encoded;
            encoded = null;
            try {
                controlOperations.execute(new Runnable() {
                    @Override
                    public void run() {
                        sendInput(currentProcessId, outbound);
                    }
                });
            } catch (RejectedExecutionException error) {
                Arrays.fill(outbound, (byte) 0);
                throw new IOException("Terminal input queue is unavailable", error);
            }
        } finally {
            wipe(buffer);
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
            if (input != null) {
                Arrays.fill(input, '\0');
            }
        }
    }

    void resize(int requestedRows, int requestedColumns) {
        validateDimensions(requestedRows, requestedColumns);
        final String currentProcessId;
        final boolean active;
        synchronized (lock) {
            rows = requestedRows;
            columns = requestedColumns;
            currentProcessId = processId;
            active = (running || starting) && !currentProcessId.isEmpty();
        }
        if (active) {
            scheduleResize(currentProcessId, requestedRows, requestedColumns, true);
        }
    }

    void stop() {
        final String currentProcessId;
        final boolean wasStarting;
        synchronized (lock) {
            if (closed || (!running && !starting)) {
                return;
            }
            stopRequested = true;
            wasStarting = starting;
            starting = false;
            running = false;
            currentProcessId = processId;
            revision++;
        }
        scheduleTerminate(currentProcessId, wasStarting);
    }

    void clearOutput() {
        synchronized (lock) {
            output.clear();
            revision++;
        }
    }

    boolean onNotification(String method, Map<String, Object> params) {
        if (!OUTPUT_DELTA_METHOD.equals(method)) {
            return false;
        }
        String notificationProcessId = JsonCodec.optionalString(params.get("processId"));
        synchronized (lock) {
            if (closed || processId.isEmpty()
                || !processId.equals(notificationProcessId)) {
                return true;
            }
        }
        byte[] encoded = null;
        byte[] decoded = null;
        byte[] canonical = null;
        try {
            Object streamValue = params.get("stream");
            Object deltaValue = params.get("deltaBase64");
            Object capValue = params.get("capReached");
            if (!(streamValue instanceof String)
                || (!"stdout".equals(streamValue) && !"stderr".equals(streamValue))
                || !(deltaValue instanceof String)
                || !(capValue instanceof Boolean)) {
                throw new IOException("Malformed terminal output notification");
            }
            String delta = (String) deltaValue;
            int maximumEncoded = ((MAXIMUM_OUTPUT_CHUNK_BYTES + 2) / 3) * 4;
            if (delta.length() > maximumEncoded) {
                throw new IOException("Terminal output chunk exceeds the limit");
            }
            encoded = delta.getBytes(StandardCharsets.US_ASCII);
            decoded = Base64.getDecoder().decode(encoded);
            canonical = Base64.getEncoder().encode(decoded);
            if (decoded.length > MAXIMUM_OUTPUT_CHUNK_BYTES
                || !Arrays.equals(encoded, canonical)) {
                throw new IOException("Terminal output is not canonical bounded Base64");
            }
            boolean capReached = ((Boolean) capValue).booleanValue();
            boolean terminate = false;
            synchronized (lock) {
                if (closed || !processId.equals(notificationProcessId)) {
                    return true;
                }
                receivedOutputBytes += decoded.length;
                if (receivedOutputBytes > MAXIMUM_TOTAL_OUTPUT_BYTES) {
                    throw new IOException("Terminal output exceeded the cumulative limit");
                }
                output.append(decoded);
                revision++;
                if (capReached) {
                    failure = "Terminal output reached the bounded capture limit";
                    stopRequested = true;
                    starting = false;
                    running = false;
                    revision++;
                    terminate = true;
                }
            }
            if (terminate) {
                scheduleTerminate(notificationProcessId);
            }
        } catch (Throwable error) {
            failProtocol(notificationProcessId, error);
        } finally {
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
            if (canonical != null) {
                Arrays.fill(canonical, (byte) 0);
            }
        }
        return true;
    }

    void onTransportClosed(Throwable error) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            starting = false;
            running = false;
            stopRequested = true;
            processId = "";
            output.finish();
            failure = safeFailure(error);
            revision++;
        }
        commandOperations.shutdownNow();
        controlOperations.shutdown();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            starting = false;
            running = false;
            stopRequested = true;
            processId = "";
            output.clear();
            failure = "";
            revision++;
        }
        commandOperations.shutdownNow();
        controlOperations.shutdown();
    }

    private void runTerminalCommand(
        final long requestedGeneration,
        final String requestedProcessId
    ) {
        try {
            final int initialRows;
            final int initialColumns;
            synchronized (lock) {
                initialRows = rows;
                initialColumns = columns;
            }
            Map<String, Object> result = client.requestStreaming(
                "command/exec",
                JsonCodec.object(
                    "command", JsonCodec.array(shellExecutable, "--interactive"),
                    "cwd", workspacePath,
                    "processId", requestedProcessId,
                    "permissionProfile", PERMISSION_PROFILE,
                    "tty", Boolean.TRUE,
                    "size", terminalSize(initialRows, initialColumns),
                    "outputBytesCap", Long.valueOf(OUTPUT_BYTES_CAP),
                    "timeoutMs", Long.valueOf(SERVER_TIMEOUT_MS)
                ),
                CLIENT_TIMEOUT_MS,
                new Runnable() {
                    @Override
                    public void run() {
                        markCommandWritten(
                            requestedGeneration,
                            requestedProcessId,
                            initialRows,
                            initialColumns
                        );
                    }
                }
            );
            completeCommand(requestedGeneration, requestedProcessId, result);
        } catch (Throwable error) {
            failCommand(requestedGeneration, requestedProcessId, error);
        }
    }

    private void markCommandWritten(
        long requestedGeneration,
        String requestedProcessId,
        int initialRows,
        int initialColumns
    ) {
        boolean terminate;
        int currentRows;
        int currentColumns;
        synchronized (lock) {
            if (closed || generation != requestedGeneration
                || !requestedProcessId.equals(processId)) {
                return;
            }
            starting = false;
            running = !stopRequested;
            terminate = stopRequested;
            currentRows = rows;
            currentColumns = columns;
            revision++;
        }
        if (terminate) {
            scheduleTerminate(requestedProcessId, false);
        } else if (currentRows != initialRows || currentColumns != initialColumns) {
            scheduleResize(requestedProcessId, currentRows, currentColumns, false);
        }
    }

    private void completeCommand(
        long requestedGeneration,
        String requestedProcessId,
        Map<String, Object> result
    ) throws IOException {
        Object exitValue = result.get("exitCode");
        Object stdout = result.get("stdout");
        Object stderr = result.get("stderr");
        if (!(exitValue instanceof Long)
            || !(stdout instanceof String)
            || !(stderr instanceof String)
            || !((String) stdout).isEmpty()
            || !((String) stderr).isEmpty()) {
            throw new IOException("Malformed terminal completion response");
        }
        long completedExitCode = ((Long) exitValue).longValue();
        if (completedExitCode < Integer.MIN_VALUE
            || completedExitCode > Integer.MAX_VALUE) {
            throw new IOException("Terminal exit code is outside the allowed range");
        }
        synchronized (lock) {
            if (closed || generation != requestedGeneration
                || !requestedProcessId.equals(processId)) {
                return;
            }
            starting = false;
            running = false;
            stopRequested = false;
            processId = "";
            exitCode = (int) completedExitCode;
            output.finish();
            revision++;
        }
    }

    private void failCommand(
        long requestedGeneration,
        String requestedProcessId,
        Throwable error
    ) {
        boolean terminate = false;
        synchronized (lock) {
            if (closed || generation != requestedGeneration
                || !requestedProcessId.equals(processId)) {
                return;
            }
            terminate = running || starting;
            starting = false;
            running = false;
            stopRequested = true;
            processId = "";
            output.finish();
            failure = safeFailure(error);
            revision++;
        }
        if (terminate) {
            scheduleTerminate(requestedProcessId, false);
        }
    }

    private void failStart(long requestedGeneration, Throwable error) {
        synchronized (lock) {
            if (closed || generation != requestedGeneration) {
                return;
            }
            starting = false;
            running = false;
            processId = "";
            failure = safeFailure(error);
            revision++;
        }
    }

    private void sendInput(String targetProcessId, byte[] input) {
        try {
            synchronized (lock) {
                if (closed || !running || !targetProcessId.equals(processId)) {
                    return;
                }
            }
            client.requestTerminalInput(targetProcessId, input, CONTROL_TIMEOUT_MS);
            input = null;
        } catch (Throwable error) {
            recordControlFailure(targetProcessId, error);
        } finally {
            if (input != null) {
                Arrays.fill(input, (byte) 0);
            }
        }
    }

    private void scheduleResize(
        final String targetProcessId,
        final int requestedRows,
        final int requestedColumns,
        final boolean tolerateStartingRace
    ) {
        if (targetProcessId == null || targetProcessId.isEmpty()) {
            return;
        }
        try {
            controlOperations.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (lock) {
                            if (closed || !targetProcessId.equals(processId)) {
                                return;
                            }
                        }
                        client.request(
                            "command/exec/resize",
                            JsonCodec.object(
                                "processId", targetProcessId,
                                "size", terminalSize(requestedRows, requestedColumns)
                            ),
                            CONTROL_TIMEOUT_MS
                        );
                    } catch (Throwable error) {
                        if (!tolerateStartingRace) {
                            recordControlFailure(targetProcessId, error);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Closing the owner also closes the app-server connection and its PTY.
        }
    }

    private void scheduleTerminate(String targetProcessId) {
        scheduleTerminate(targetProcessId, false);
    }

    private void scheduleTerminate(
        final String targetProcessId,
        final boolean tolerateStartingRace
    ) {
        if (targetProcessId == null || targetProcessId.isEmpty()) {
            return;
        }
        try {
            controlOperations.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        client.request(
                            "command/exec/terminate",
                            JsonCodec.object("processId", targetProcessId),
                            CONTROL_TIMEOUT_MS
                        );
                    } catch (Throwable error) {
                        if (!tolerateStartingRace) {
                            recordControlFailure(targetProcessId, error);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Closing the owner also closes the app-server connection and its PTY.
        }
    }

    private void failProtocol(String targetProcessId, Throwable error) {
        synchronized (lock) {
            if (closed || !targetProcessId.equals(processId)) {
                return;
            }
            starting = false;
            running = false;
            stopRequested = true;
            failure = safeFailure(error);
            output.finish();
            revision++;
        }
        scheduleTerminate(targetProcessId);
    }

    private void recordControlFailure(String targetProcessId, Throwable error) {
        synchronized (lock) {
            if (closed || !targetProcessId.equals(processId)) {
                return;
            }
            failure = safeFailure(error);
            revision++;
        }
    }

    private static Map<String, Object> terminalSize(int rows, int columns) {
        return JsonCodec.object(
            "rows", Long.valueOf(rows),
            "cols", Long.valueOf(columns)
        );
    }

    private static void validateDimensions(int rows, int columns) {
        if (rows <= 0 || rows > 1000 || columns <= 0 || columns > 1000) {
            throw new IllegalArgumentException("Terminal dimensions are invalid");
        }
    }

    private static String requiredAbsolute(String value, String label) {
        if (value == null || value.isEmpty() || !value.startsWith("/")) {
            throw new IllegalArgumentException(label + " must be absolute");
        }
        return value;
    }

    private static String safeFailure(Throwable error) {
        if (error == null) {
            return "Unknown terminal failure";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getName();
        }
        return safeFailure(message);
    }

    private static String safeFailure(String message) {
        return CrashReportFormatter.redactVisibleText(
            message == null ? "Unknown terminal failure" : message,
            240
        );
    }

    private static void wipe(ByteBuffer buffer) {
        if (buffer == null || buffer.isReadOnly()) {
            return;
        }
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
            return;
        }
        for (int index = 0; index < buffer.capacity(); index++) {
            buffer.put(index, (byte) 0);
        }
    }
}
