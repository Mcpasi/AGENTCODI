package de.agentcodi.tests;

public final class TestSupport {
    private TestSupport() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(
                message + " expected=<" + expected + "> actual=<" + actual + ">"
            );
        }
    }

    public static void assertContains(String value, String expectedPart, String message) {
        if (value == null || !value.contains(expectedPart)) {
            throw new AssertionError(
                message + " expected part=<" + expectedPart + "> actual=<" + value + ">"
            );
        }
    }

    public static void expectThrows(
        Class<? extends Throwable> expectedType,
        ThrowingRunnable action,
        String message
    ) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expectedType.isInstance(error)) {
                return;
            }
            throw new AssertionError(
                message + " wrong exception=<" + error.getClass().getName() + ">",
                error
            );
        }
        throw new AssertionError(message + " expected exception=<" + expectedType.getName() + ">");
    }

    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}

