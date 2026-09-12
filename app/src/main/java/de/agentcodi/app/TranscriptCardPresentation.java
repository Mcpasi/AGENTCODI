package de.agentcodi.app;

import de.agentcodi.core.CodexTranscriptItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Keep choices across row rebuilds, retaining only the current thread's visible tools.
    static final class ExpansionState {
        private String threadId = "";
        private Map<String, CardExpansion> cards = new HashMap<String, CardExpansion>();

        void update(String activeThreadId, List<CodexTranscriptItem> items) {
            boolean sameThread = threadId.equals(activeThreadId);
            Map<String, CardExpansion> visibleCards = new HashMap<String, CardExpansion>();
            for (CodexTranscriptItem item : items) {
                if (!isCollapsible(item)) {
                    continue;
                }
                CardExpansion previous = sameThread ? card(item) : null;
                visibleCards.put(item.getId(), previous == null
                    ? new CardExpansion(item.getProtocolType()) : previous);
            }
            cards = visibleCards;
            threadId = activeThreadId;
        }

        boolean isExpanded(CodexTranscriptItem item) {
            if (!isCollapsible(item)) {
                return true;
            }
            CardExpansion value = card(item);
            return value != null && value.expanded;
        }

        void toggle(CodexTranscriptItem item) {
            CardExpansion value = isCollapsible(item) ? card(item) : null;
            if (value != null) {
                value.expanded = !value.expanded;
            }
        }

        private CardExpansion card(CodexTranscriptItem item) {
            CardExpansion value = cards.get(item.getId());
            return value != null && value.protocolType.equals(item.getProtocolType())
                ? value : null;
        }
    }

    private static final class CardExpansion {
        private final String protocolType;
        private boolean expanded;

        private CardExpansion(String protocolType) {
            this.protocolType = protocolType;
        }
    }

    private TranscriptCardPresentation() {
    }

    static boolean isCollapsible(CodexTranscriptItem item) {
        return item.getKind() == CodexTranscriptItem.Kind.TOOL;
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
