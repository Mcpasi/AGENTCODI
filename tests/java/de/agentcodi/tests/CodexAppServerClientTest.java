package de.agentcodi.tests;

import de.agentcodi.core.CodexAppServerClient;
import de.agentcodi.core.CodexRpcTransport;
import de.agentcodi.core.JsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

public final class CodexAppServerClientTest {
    private CodexAppServerClientTest() {
    }

    public static int run() throws Exception {
        performsOrderedHandshakeAndCorrelatesResponse();
        dispatchesNotifications();
        rejectsServerRequestsFailClosed();
        rejectsFractionalOrUnboundedIds();
        enforcesOutgoingLimitAndTimeout();
        return 5;
    }

    private static void performsOrderedHandshakeAndCorrelatesResponse() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(new Script() {
            @Override
            public void onWrite(Map<String, Object> message, ScriptedTransport target) {
                String method = JsonCodec.optionalString(message.get("method"));
                if ("initialize".equals(method)) {
                    target.enqueue(response(message, JsonCodec.object("userAgent", "fixture/1")));
                } else if ("account/read".equals(method)) {
                    target.enqueue(response(
                        message,
                        JsonCodec.object("account", null, "requiresOpenaiAuth", Boolean.TRUE)
                    ));
                }
            }
        });
        RecordingListener listener = new RecordingListener();
        CodexAppServerClient client = initializedClient(transport, listener);
        Map<String, Object> result = client.request(
            "account/read",
            JsonCodec.object("refreshToken", Boolean.FALSE),
            1_000L
        );
        TestSupport.assertEquals(Boolean.TRUE, result.get("requiresOpenaiAuth"), "response");
        TestSupport.assertEquals("initialize", transport.sentMethod(0), "first message");
        TestSupport.assertEquals("initialized", transport.sentMethod(1), "second message");
        TestSupport.assertEquals("account/read", transport.sentMethod(2), "third message");
        client.close();
    }

    private static void dispatchesNotifications() throws Exception {
        ScriptedTransport transport = handshakeOnlyTransport();
        RecordingListener listener = new RecordingListener();
        CodexAppServerClient client = initializedClient(transport, listener);
        transport.enqueue(JsonCodec.stringify(JsonCodec.object(
            "method", "item/agentMessage/delta",
            "params", JsonCodec.object(
                "threadId", "thr_fixture",
                "turnId", "turn_fixture",
                "itemId", "item_fixture",
                "delta", "Hallo"
            )
        )));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return listener.notifications.size() == 1;
            }
        }, "notification dispatch");
        TestSupport.assertEquals(
            "item/agentMessage/delta",
            listener.notifications.get(0),
            "method"
        );
        client.close();
    }

    private static void rejectsServerRequestsFailClosed() throws Exception {
        final ScriptedTransport transport = handshakeOnlyTransport();
        CodexAppServerClient client = initializedClient(transport, new RecordingListener());
        transport.enqueue(JsonCodec.stringify(JsonCodec.object(
            "id", Long.valueOf(0L),
            "method", "item/commandExecution/requestApproval",
            "params", JsonCodec.object("threadId", "thr_fixture")
        )));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return transport.hasErrorResponse(0L);
            }
        }, "fail-closed server request");
        client.close();
    }

    private static void enforcesOutgoingLimitAndTimeout() throws Exception {
        final ScriptedTransport transport = handshakeOnlyTransport();
        final CodexAppServerClient client = initializedClient(
            transport,
            new RecordingListener()
        );
        final StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < CodexAppServerClient.MAX_OUTGOING_BYTES; index++) {
            oversized.append('x');
        }
        TestSupport.expectThrows(
            IOException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    client.request(
                        "fixture/large",
                        JsonCodec.object("value", oversized.toString()),
                        1_000L
                    );
                }
            },
            "outgoing byte limit"
        );
        TestSupport.expectThrows(
            TimeoutException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() throws Exception {
                    client.request("fixture/timeout", Collections.<String, Object>emptyMap(), 30L);
                }
            },
            "request timeout"
        );
        client.close();
    }

    private static void rejectsFractionalOrUnboundedIds() throws Exception {
        ScriptedTransport fractionalTransport = handshakeOnlyTransport();
        RecordingListener fractionalListener = new RecordingListener();
        CodexAppServerClient fractionalClient = initializedClient(
            fractionalTransport,
            fractionalListener
        );
        fractionalTransport.enqueue(JsonCodec.stringify(JsonCodec.object(
            "id", Double.valueOf(7.5d),
            "method", "fixture/serverRequest",
            "params", JsonCodec.object()
        )));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return fractionalListener.transportFailure != null;
            }
        }, "fractional ID closes transport");
        TestSupport.assertContains(
            fractionalListener.transportFailure.getMessage(),
            "integer",
            "fractional ID failure"
        );
        fractionalClient.close();

        ScriptedTransport unboundedTransport = handshakeOnlyTransport();
        RecordingListener unboundedListener = new RecordingListener();
        CodexAppServerClient unboundedClient = initializedClient(
            unboundedTransport,
            unboundedListener
        );
        unboundedTransport.enqueue(JsonCodec.stringify(JsonCodec.object(
            "id", Long.valueOf(CodexAppServerClient.MAX_REQUEST_ID + 1L),
            "result", JsonCodec.object()
        )));
        waitFor(new Condition() {
            @Override
            public boolean isTrue() {
                return unboundedListener.transportFailure != null;
            }
        }, "unbounded ID closes transport");
        TestSupport.assertContains(
            unboundedListener.transportFailure.getMessage(),
            "allowed range",
            "unbounded ID failure"
        );
        unboundedClient.close();
    }

    private static CodexAppServerClient initializedClient(
        ScriptedTransport transport,
        RecordingListener listener
    ) throws Exception {
        CodexAppServerClient client = new CodexAppServerClient(transport, listener);
        client.start();
        client.initialize(
            JsonCodec.object(
                "clientInfo",
                JsonCodec.object("name", "fixture", "title", "Fixture", "version", "1")
            ),
            1_000L
        );
        return client;
    }

    private static ScriptedTransport handshakeOnlyTransport() {
        return new ScriptedTransport(new Script() {
            @Override
            public void onWrite(Map<String, Object> message, ScriptedTransport target) {
                if ("initialize".equals(JsonCodec.optionalString(message.get("method")))) {
                    target.enqueue(response(message, JsonCodec.object("userAgent", "fixture/1")));
                }
            }
        });
    }

    private static String response(Map<String, Object> request, Map<String, Object> result) {
        return JsonCodec.stringify(JsonCodec.object("id", request.get("id"), "result", result));
    }

    private static void waitFor(Condition condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (!condition.isTrue() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        TestSupport.assertTrue(condition.isTrue(), message);
    }

    private interface Condition {
        boolean isTrue();
    }

    private interface Script {
        void onWrite(Map<String, Object> message, ScriptedTransport target);
    }

    private static final class RecordingListener implements CodexAppServerClient.Listener {
        private final List<String> notifications =
            Collections.synchronizedList(new ArrayList<String>());
        private volatile Throwable transportFailure;

        @Override
        public void onNotification(String method, Map<String, Object> params) {
            notifications.add(method);
        }

        @Override
        public void onTransportClosed(Throwable error) {
            transportFailure = error;
        }
    }

    private static final class ScriptedTransport implements CodexRpcTransport {
        private static final Object CLOSED = new Object();
        private final LinkedBlockingQueue<Object> incoming = new LinkedBlockingQueue<Object>();
        private final List<Map<String, Object>> sent =
            Collections.synchronizedList(new ArrayList<Map<String, Object>>());
        private final Script script;
        private volatile boolean closed;

        private ScriptedTransport(Script script) {
            this.script = script;
        }

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
            if (closed) {
                throw new IOException("fixture closed");
            }
            if (line.getBytes(StandardCharsets.UTF_8).length > maximumBytes) {
                throw new IOException("fixture outgoing line too large");
            }
            Map<String, Object> message = JsonCodec.parseObject(line);
            sent.add(message);
            script.onWrite(message, this);
        }

        @Override
        public void close() {
            closed = true;
            incoming.offer(CLOSED);
        }

        private void enqueue(String line) {
            incoming.offer(line);
        }

        private String sentMethod(int index) {
            return JsonCodec.optionalString(sent.get(index).get("method"));
        }

        private boolean hasErrorResponse(long id) {
            synchronized (sent) {
                for (Map<String, Object> message : sent) {
                    if (JsonCodec.longValue(message.get("id"), -1L) == id
                        && message.containsKey("error")) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
