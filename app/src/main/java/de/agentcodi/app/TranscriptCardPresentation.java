package de.agentcodi.app;

import de.agentcodi.core.CodexTranscriptItem;

final class TranscriptCardPresentation {
    enum State {
        NONE,
        RUNNING,
        COMPLETED,
        FAILED,
        DECLINED,
        INTERRUPTED,
        OTHER
    }

    private TranscriptCardPresentation() {
    }

    static State state(CodexTranscriptItem item) {
        String status = item.getStatus();
        if ("completed".equals(status)) {
            return State.COMPLETED;
        }
        if ("failed".equals(status)) {
            return State.FAILED;
        }
        if ("declined".equals(status)) {
            return State.DECLINED;
        }
        if ("interrupted".equals(status)) {
            return State.INTERRUPTED;
        }
        if ("inProgress".equals(status)) {
            return State.RUNNING;
        }
        if (!status.isEmpty()) {
            return State.OTHER;
        }
        return item.isStreaming() ? State.RUNNING : State.NONE;
    }

    static boolean monospaceSummary(CodexTranscriptItem item) {
        return "commandExecution".equals(item.getProtocolType());
    }

    static boolean monospaceDetail(CodexTranscriptItem item) {
        return monospaceSummary(item) || "fileChange".equals(item.getProtocolType());
    }

    static boolean sameContent(CodexTranscriptItem previous, CodexTranscriptItem next) {
        return previous != null
            && !previous.isMessage()
            && !next.isMessage()
            && previous.getId().equals(next.getId())
            && previous.getKind() == next.getKind()
            && previous.getProtocolType().equals(next.getProtocolType())
            && previous.getTitle().equals(next.getTitle())
            && previous.getSummary().equals(next.getSummary())
            && previous.getDetail().equals(next.getDetail())
            && previous.getStatus().equals(next.getStatus())
            && previous.isStreaming() == next.isStreaming()
            && previous.getReportedImagePath().equals(next.getReportedImagePath());
    }
}
