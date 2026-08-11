package de.agentcodi.core;

import java.util.Objects;

public final class CodexTranscriptItem {
    public enum Kind {
        MESSAGE,
        REASONING,
        PLAN,
        TOOL
    }

    private final String id;
    private final Kind kind;
    private final ChatMessage message;
    private final String protocolType;
    private final String title;
    private final String summary;
    private final String detail;
    private final String status;
    private final boolean streaming;
    private final String reportedImagePath;

    private CodexTranscriptItem(
        String id,
        Kind kind,
        ChatMessage message,
        String protocolType,
        String title,
        String summary,
        String detail,
        String status,
        boolean streaming,
        String reportedImagePath
    ) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Transcript item id must not be blank");
        }
        this.id = id;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.message = message;
        this.protocolType = nonNull(protocolType);
        this.title = nonNull(title);
        this.summary = nonNull(summary);
        this.detail = nonNull(detail);
        this.status = nonNull(status);
        this.streaming = streaming;
        this.reportedImagePath = nonNull(reportedImagePath);
        if (kind == Kind.MESSAGE && message == null) {
            throw new IllegalArgumentException("Message transcript item requires a message");
        }
        if (kind != Kind.MESSAGE && message != null) {
            throw new IllegalArgumentException("Card transcript item must not contain a message");
        }
        if (kind != Kind.MESSAGE && this.title.isEmpty()) {
            throw new IllegalArgumentException("Card transcript item requires a title");
        }
        if (!this.reportedImagePath.isEmpty()
            && (kind == Kind.MESSAGE
                || !this.reportedImagePath.startsWith("/")
                || this.reportedImagePath.indexOf('\n') >= 0
                || this.reportedImagePath.indexOf('\r') >= 0
                || this.reportedImagePath.indexOf('\0') >= 0
                || this.reportedImagePath.length() > 4096)) {
            throw new IllegalArgumentException("Reported image path is invalid");
        }
    }

    public static CodexTranscriptItem message(ChatMessage message) {
        ChatMessage value = Objects.requireNonNull(message, "message");
        return new CodexTranscriptItem(
            value.getId(),
            Kind.MESSAGE,
            value,
            "",
            "",
            "",
            "",
            "",
            value.isStreaming(),
            ""
        );
    }

    public static CodexTranscriptItem card(
        String id,
        Kind kind,
        String protocolType,
        String title,
        String summary,
        String detail,
        String status,
        boolean streaming
    ) {
        if (kind == Kind.MESSAGE) {
            throw new IllegalArgumentException("Use message() for message transcript items");
        }
        return new CodexTranscriptItem(
            id,
            kind,
            null,
            protocolType,
            title,
            summary,
            detail,
            status,
            streaming,
            ""
        );
    }

    public CodexTranscriptItem withReportedImagePath(String path) {
        if (isMessage()) {
            throw new IllegalStateException("Message transcript items cannot expose files");
        }
        return new CodexTranscriptItem(
            id,
            kind,
            null,
            protocolType,
            title,
            summary,
            detail,
            status,
            streaming,
            path
        );
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isMessage() {
        return kind == Kind.MESSAGE;
    }

    public ChatMessage getMessage() {
        return message;
    }

    public String getProtocolType() {
        return protocolType;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public String getStatus() {
        return status;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public String getReportedImagePath() {
        return reportedImagePath;
    }

    public int getVisibleCharacterCount() {
        if (message != null) {
            return message.getText().length();
        }
        return title.length() + summary.length() + detail.length() + status.length()
            + reportedImagePath.length();
    }

    public CodexTranscriptItem finish(String finalStatus) {
        if (!streaming) {
            return this;
        }
        if (message != null) {
            return message(new ChatMessage(
                message.getId(),
                message.getRole(),
                message.getText(),
                false
            ));
        }
        return new CodexTranscriptItem(
            id,
            kind,
            null,
            protocolType,
            title,
            summary,
            detail,
            finalStatus == null || finalStatus.isEmpty() ? status : finalStatus,
            false,
            reportedImagePath
        );
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
