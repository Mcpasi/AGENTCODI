package de.agentcodi.tests;

import de.agentcodi.core.CodexAppServerClient;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

public final class CodexLargeIncomingFrameTest {
    private CodexLargeIncomingFrameTest() {
    }

    public static int run() throws Exception {
        acceptsIncomingFrameAboveOneMiB();
        return 1;
    }

    private static void acceptsIncomingFrameAboveOneMiB() throws Exception {
        LargeFrameTransport transport = new LargeFrameTransport();
        RecordingListener listener = new RecordingListener();
        CodexAppServerClient client = new CodexAppServerClient(transport, listener);
        client.start();
        client.initialize(
            JsonCodec.object(
                "clientInfo",
                JsonCodec.object("name", "fixture", "title", "Fixture", "version", "1")
            ),
            1_000L
        );

        final int payloadLength = 2 * 1024 * 1024;
        StringBuilder payload = new StringBuilder(payloadLength);
        for (int index = 0; index < payloadLength; index++) {
            payload.append('x');
        }
        String frame = "{\"method\":\"fixture/largeNotification\",\"params\":{\"payload\":\""
            + payload.toString()
            + "\"}}";
        TestSupport.assertTrue(
            frame.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024,
            "fixture must exceed the old 1 MiB limit"
        );
        TestSupport.assertTrue(
            frame.getBytes(StandardCharsets.UTF_8).length < CodexAppServerClient.MAX_INCOMING_BYTES,
            "fixture must stay below the configured incoming limit"
        );

        transport.enqueue(frame);
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return listener.payloadLength == payloadLength;
            }
        }, "large incoming notification dispatch");
        TestSupport.assertEquals(
            "fixture/largeNotification",
            listener.method,
            "large incoming method"
        );
        TestSupport.assertEquals(
            Integer.valueOf(payloadLength),
            Integer.valueOf(listener.payloadLength),
            "large incoming payload length"
        );
        TestSupport.assertEquals(null, listener.transportFailure, "transport remains open");
        client.close();
    }

    private static void waitFor(Condition condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!condition.isTrue() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertTrue(condition.isTrue(), message);
    }

    private interface Condition {
        boolean isTrue();
    }

    private static final class RecordingListener implements CodexAppServerClient.Listener {
        private volatile String method;
        private volatile int payloadLength = -1;
        private volatile Throwable transportFailure;

        @Override
        public boolean onServerRequest(
            long requestId,
            String method,
            Map<String, Object> params
        ) {
            return false;
        }

        @Override
        public void onNotification(String method, Map<String, Object> params) {
            this.method = method;
            String payload = JsonCodec.optionalString(params.get("payload"));
            payloadLength = payload.length();
        }

        @Override
        public void onTransportClosed(Throwable error) {
            transportFailure = error;
        }
    }

    private static final class LargeFrameTransport implements CodexRpcTransport {
        private static final Object CLOSED = new Object();
        private final LinkedBlockingQueue<Object> incoming = new LinkedBlockingQueue<Object>();
        private volatile boolean closed;

        @Override
        public String readLine(int maximumBytes) throws IOException {
            try {
                Object value = incoming.take();
                if (value == CLOSED) {
                    return null;
                }
                String line = (String) value;
                if (line.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
                    throw new IOException("fixture line too large");
                }
                return line;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("fixture interrupted", error);
            }
        }

        @Override
        public void writeLine(String line, int maximumBytes) throws IOException {
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            writeBytes(bytes, bytes.length, maximumBytes);
        }

        @Override
        public void writeBytes(byte[] line, int length, int maximumBytes) throws IOException {
            if (closed) {
                throw new IOException("fixture closed");
            }
            if (line == null || length <= 0 || length > line.length || length > maximumBytes) {
                throw new IOException("fixture outgoing bytes too large");
            }
            byte[] copy = Arrays.copyOf(line, length);
            try {
                Map<String, Object> message = JsonCodec.parseObject(
                    new String(copy, StandardCharsets.UTF_8)
                );
                if ("initialize".equals(JsonCodec.optionalString(message.get("method")))) {
                    enqueue(JsonCodec.stringify(JsonCodec.object(
                        "id", message.get("id"),
                        "result", JsonCodec.object("userAgent", "fixture/1")
                    )));
                }
            } finally {
                Arrays.fill(copy, (byte) 0);
                Arrays.fill(line, (byte) 0);
            }
        }

        @Override
        public void close() {
            closed = true;
            incoming.offer(CLOSED);
        }

        private void enqueue(String line) {
            incoming.offer(line);
        }
    }
}
