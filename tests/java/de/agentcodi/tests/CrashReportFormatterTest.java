package de.agentcodi.tests;

import de.agentcodi.core.CrashReportFormatter;

public final class CrashReportFormatterTest {
    private CrashReportFormatterTest() {
    }

    public static int run() {
        formatsBoundedStackTrace();
        redactsNamedAndBearerSecrets();
        redactsJsonOauthAndJwtSecrets();
        handlesCauseCycles();
        return 4;
    }

    private static void formatsBoundedStackTrace() {
        IllegalStateException error = new IllegalStateException("startup failed");
        String report = CrashReportFormatter.format("activity", Thread.currentThread(), error);
        TestSupport.assertContains(report, "Quelle: activity", "source");
        TestSupport.assertContains(
            report,
            "Fehler: java.lang.IllegalStateException",
            "exception class"
        );
        TestSupport.assertContains(report, "Meldung: startup failed", "message");
        TestSupport.assertTrue(report.length() <= 16 * 1024, "bounded report");
    }

    private static void redactsNamedAndBearerSecrets() {
        String redacted = CrashReportFormatter.redact(
            "authorization=example-value Bearer another-example password=not-for-logs"
        );
        TestSupport.assertFalse(redacted.contains("example-value"), "named secret removed");
        TestSupport.assertFalse(redacted.contains("another-example"), "bearer secret removed");
        TestSupport.assertFalse(redacted.contains("not-for-logs"), "password removed");
        TestSupport.assertContains(redacted, "<redacted>", "redaction marker");
    }

    private static void handlesCauseCycles() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        String report = CrashReportFormatter.format("cycle", Thread.currentThread(), first);
        TestSupport.assertTrue(report.length() <= 16 * 1024, "cycle remains bounded");
    }

    private static void redactsJsonOauthAndJwtSecrets() {
        String redacted = CrashReportFormatter.redact(
            "{\"accessToken\":\"temporary-value\"} "
                + "https://auth.openai.com/callback?state=temporary-state&code=temporary-code "
                + "eyJabcdefghijk.abcdefghijklmnop.qrstuvwxyzabcd"
        );
        TestSupport.assertFalse(redacted.contains("temporary-value"), "JSON token removed");
        TestSupport.assertFalse(redacted.contains("temporary-state"), "OAuth state removed");
        TestSupport.assertFalse(redacted.contains("temporary-code"), "OAuth code removed");
        TestSupport.assertFalse(redacted.contains("eyJabcdefghijk"), "JWT removed");
    }
}
