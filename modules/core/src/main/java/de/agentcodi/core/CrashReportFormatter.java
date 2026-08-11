package de.agentcodi.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

public final class CrashReportFormatter {
    private static final int MAX_REPORT_CHARS = 16 * 1024;
    private static final int MAX_MESSAGE_CHARS = 4 * 1024;
    private static final int MAX_CAUSES = 4;
    private static final int MAX_FRAMES_PER_CAUSE = 48;
    private static final Pattern NAMED_SECRET = Pattern.compile(
        "(?i)([\\\"']?(?:authorization|access[_-]?token|refresh[_-]?token|id[_-]?token|"
            + "api[_-]?key|client[_-]?secret|password)[\\\"']?\\s*[:=]\\s*[\\\"']?)"
            + "[^\\\"'\\s,;&}]+"
    );
    private static final Pattern BEARER_SECRET = Pattern.compile(
        "(?i)bearer\\s+[^\\s,;]+"
    );
    private static final Pattern TOKEN_SHAPE = Pattern.compile(
        "\\b(?:cwx_|sk-)[A-Za-z0-9_-]{8,}"
    );
    private static final Pattern OAUTH_QUERY_SECRET = Pattern.compile(
        "(?i)([?&](?:code|state|access_token|refresh_token|id_token)=)[^&\\s]+"
    );
    private static final Pattern JWT_SHAPE = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{8,})?"
    );

    private CrashReportFormatter() {
    }

    public static String format(String source, Thread thread, Throwable error) {
        StringBuilder report = new StringBuilder(2048);
        append(report, BuildIdentity.summary());
        append(report, "Zeit (Unix ms): " + System.currentTimeMillis());
        append(report, "Quelle: " + redact(source));
        append(report, "Thread: " + redact(thread == null ? "unknown" : thread.getName()));

        if (error == null) {
            append(report, "Fehler: unknown");
            return report.toString();
        }

        Set<Throwable> visited = Collections.newSetFromMap(
            new IdentityHashMap<Throwable, Boolean>()
        );
        Throwable current = error;
        int causeIndex = 0;
        while (current != null
            && causeIndex < MAX_CAUSES
            && visited.add(current)
            && report.length() < MAX_REPORT_CHARS) {
            String prefix = causeIndex == 0 ? "Fehler: " : "Verursacht durch: ";
            append(report, prefix + current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                append(report, "Meldung: " + redact(current.getMessage()));
            }

            StackTraceElement[] frames = current.getStackTrace();
            int frameCount = Math.min(frames.length, MAX_FRAMES_PER_CAUSE);
            for (int index = 0; index < frameCount; index++) {
                append(report, "  at " + redact(frames[index].toString()));
            }
            if (frames.length > frameCount) {
                append(report, "  ... " + (frames.length - frameCount) + " weitere Frames");
            }
            current = current.getCause();
            causeIndex++;
        }
        if (current != null) {
            append(report, "... weitere Ursachen abgeschnitten");
        }
        return report.length() <= MAX_REPORT_CHARS
            ? report.toString()
            : report.substring(0, MAX_REPORT_CHARS);
    }

    public static String redact(String value) {
        return redactVisibleText(value, MAX_MESSAGE_CHARS);
    }

    public static String redactVisibleText(String value, int maximumCharacters) {
        if (value == null) {
            return "";
        }
        if (maximumCharacters <= 0) {
            return "";
        }
        String redacted = NAMED_SECRET.matcher(value).replaceAll("$1<redacted>");
        redacted = BEARER_SECRET.matcher(redacted).replaceAll("Bearer <redacted>");
        redacted = OAUTH_QUERY_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        redacted = JWT_SHAPE.matcher(redacted).replaceAll("<redacted-token>");
        redacted = TOKEN_SHAPE.matcher(redacted).replaceAll("<redacted-token>");
        if (redacted.length() <= maximumCharacters) {
            return redacted;
        }
        if (maximumCharacters <= 3) {
            return redacted.substring(0, maximumCharacters);
        }
        return redacted.substring(0, maximumCharacters - 3) + "...";
    }

    private static void append(StringBuilder report, String line) {
        if (report.length() >= MAX_REPORT_CHARS) {
            return;
        }
        String safeLine = line == null ? "" : line;
        int remaining = MAX_REPORT_CHARS - report.length();
        if (safeLine.length() + 1 <= remaining) {
            report.append(safeLine).append('\n');
            return;
        }
        int characters = Math.max(0, remaining - 1);
        report.append(safeLine, 0, Math.min(characters, safeLine.length())).append('\n');
    }
}
