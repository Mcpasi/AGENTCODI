package de.agentcodi.tests;

import de.agentcodi.core.JsonCodec;

import java.util.Map;

public final class JsonCodecTest {
    private JsonCodecTest() {
    }

    public static int run() {
        parsesAppServerFixture();
        roundTripsUnicodeAndEscapes();
        rejectsDuplicateKeys();
        rejectsTrailingContent();
        rejectsExcessiveNesting();
        rejectsUnpairedSurrogateEscape();
        return 6;
    }

    private static void parsesAppServerFixture() {
        Map<String, Object> message = JsonCodec.parseObject(
            "{\"id\":2,\"result\":{\"account\":null,\"requiresOpenaiAuth\":true}}"
        );
        TestSupport.assertEquals(Long.valueOf(2L), message.get("id"), "numeric request id");
        Map<String, Object> result = JsonCodec.requireObject(message.get("result"), "result");
        TestSupport.assertEquals(Boolean.TRUE, result.get("requiresOpenaiAuth"), "auth flag");
    }

    private static void roundTripsUnicodeAndEscapes() {
        Map<String, Object> source = JsonCodec.object(
            "method", "item/agentMessage/delta",
            "params", JsonCodec.object("delta", "Grüße 🌍\nzweite Zeile")
        );
        Map<String, Object> decoded = JsonCodec.parseObject(JsonCodec.stringify(source));
        Map<String, Object> params = JsonCodec.requireObject(decoded.get("params"), "params");
        TestSupport.assertEquals("Grüße 🌍\nzweite Zeile", params.get("delta"), "unicode");
    }

    private static void rejectsDuplicateKeys() {
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    JsonCodec.parse("{\"id\":1,\"id\":2}");
                }
            },
            "duplicate keys"
        );
    }

    private static void rejectsTrailingContent() {
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    JsonCodec.parse("{}[]");
                }
            },
            "trailing content"
        );
    }

    private static void rejectsExcessiveNesting() {
        final StringBuilder nested = new StringBuilder();
        for (int index = 0; index < 70; index++) {
            nested.append('[');
        }
        for (int index = 0; index < 70; index++) {
            nested.append(']');
        }
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    JsonCodec.parse(nested.toString());
                }
            },
            "nesting limit"
        );
    }

    private static void rejectsUnpairedSurrogateEscape() {
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    JsonCodec.parse("\"\\uD83D\"");
                }
            },
            "unpaired JSON surrogate"
        );
    }
}
