package de.agentcodi.app;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.tests.TestSupport;

public final class TranscriptCardPresentationTest {
    private TranscriptCardPresentationTest() {
    }

    public static int run() {
        terminalStatesTakePrecedenceOverStreaming();
        unknownAndEmptyStatesDoNotImplySuccess();
        technicalContentUsesCodeTypography();
        updatesIncludeStatusAndImageChanges();
        cardsNeverReuseMessageContent();
        return 5;
    }

    private static void terminalStatesTakePrecedenceOverStreaming() {
        String[] statuses = { "completed", "failed", "declined", "interrupted" };
        TranscriptCardPresentation.State[] expected = {
            TranscriptCardPresentation.State.COMPLETED,
            TranscriptCardPresentation.State.FAILED,
            TranscriptCardPresentation.State.DECLINED,
            TranscriptCardPresentation.State.INTERRUPTED
        };
        for (int index = 0; index < statuses.length; index++) {
            for (boolean streaming : new boolean[] { false, true }) {
                TestSupport.assertEquals(
                    expected[index],
                    TranscriptCardPresentation.state(card(statuses[index], streaming)),
                    "final status remains visible even with a streaming flag"
                );
            }
        }
        TestSupport.assertEquals(
            TranscriptCardPresentation.State.RUNNING,
            TranscriptCardPresentation.state(card("inProgress", true)),
            "running work has its own status"
        );
    }

    private static void unknownAndEmptyStatesDoNotImplySuccess() {
        TestSupport.assertEquals(
            TranscriptCardPresentation.State.RUNNING,
            TranscriptCardPresentation.state(card("", true)),
            "streaming without a status still shows activity"
        );
        TestSupport.assertEquals(
            TranscriptCardPresentation.State.NONE,
            TranscriptCardPresentation.state(card("", false)),
            "an idle card without a result is not successful"
        );
        for (boolean streaming : new boolean[] { false, true }) {
            TestSupport.assertEquals(
                TranscriptCardPresentation.State.OTHER,
                TranscriptCardPresentation.state(card("pending", streaming)),
                "unknown states retain their neutral presentation"
            );
        }
    }

    private static void technicalContentUsesCodeTypography() {
        CodexTranscriptItem command = card("completed", false);
        TestSupport.assertTrue(
            TranscriptCardPresentation.monospaceSummary(command),
            "command text uses monospace"
        );
        TestSupport.assertTrue(
            TranscriptCardPresentation.monospaceDetail(command),
            "command output preserves a code layout"
        );
        CodexTranscriptItem change = typedCard(CodexTranscriptItem.Kind.TOOL, "fileChange");
        TestSupport.assertFalse(
            TranscriptCardPresentation.monospaceSummary(change),
            "file-count summary remains prose"
        );
        TestSupport.assertTrue(
            TranscriptCardPresentation.monospaceDetail(change),
            "diff detail uses monospace"
        );
        for (CodexTranscriptItem prose : new CodexTranscriptItem[] {
            typedCard(CodexTranscriptItem.Kind.REASONING, "reasoning"),
            typedCard(CodexTranscriptItem.Kind.PLAN, "plan"),
            typedCard(CodexTranscriptItem.Kind.TOOL, "mcpToolCall"),
            typedCard(CodexTranscriptItem.Kind.TOOL, "imageGeneration"),
            typedCard(CodexTranscriptItem.Kind.TOOL, "unknownTool")
        }) {
            TestSupport.assertFalse(
                TranscriptCardPresentation.monospaceSummary(prose)
                    || TranscriptCardPresentation.monospaceDetail(prose),
                "narrative tools and reasoning retain proportional typography"
            );
        }
    }

    private static void updatesIncludeStatusAndImageChanges() {
        CodexTranscriptItem running = card("inProgress", true);
        TestSupport.assertFalse(
            TranscriptCardPresentation.sameContent(null, running),
            "first render is required"
        );
        TestSupport.assertTrue(
            TranscriptCardPresentation.sameContent(running, card("inProgress", true)),
            "unchanged polling does not reset selectable text"
        );
        TestSupport.assertFalse(
            TranscriptCardPresentation.sameContent(running, running.finish("completed")),
            "completion updates the badge without needing new output"
        );
        TestSupport.assertFalse(
            TranscriptCardPresentation.sameContent(running, card("inProgress", false)),
            "streaming changes invalidate the card"
        );
        CodexTranscriptItem image = typedCard(CodexTranscriptItem.Kind.TOOL, "imageGeneration");
        TestSupport.assertFalse(
            TranscriptCardPresentation.sameContent(
                image, image.withReportedImagePath("/workspace/generated/result.png")
            ),
            "an image result refreshes independently of its text"
        );
        String[] original = { "item", "commandExecution", "Command", "command", "first line", "inProgress" };
        for (int index = 0; index < original.length; index++) {
            String[] edited = original.clone();
            edited[index] = edited[index] + " changed";
            TestSupport.assertFalse(
                TranscriptCardPresentation.sameContent(running, CodexTranscriptItem.card(
                    edited[0], CodexTranscriptItem.Kind.TOOL, edited[1], edited[2],
                    edited[3], edited[4], edited[5], true
                )),
                "each rendered field can invalidate the previous card"
            );
        }
        TestSupport.assertFalse(
            TranscriptCardPresentation.sameContent(running, CodexTranscriptItem.card(
                "item", CodexTranscriptItem.Kind.PLAN, "commandExecution", "Command",
                "command", "first line", "inProgress", true
            )),
            "a changed item kind cannot reuse the previous presentation"
        );
    }

    private static void cardsNeverReuseMessageContent() {
        CodexTranscriptItem message = CodexTranscriptItem.message(new ChatMessage(
            "message", ChatMessage.Role.ASSISTANT, "Visible answer", false
        ));
        TestSupport.assertFalse(
            TranscriptCardPresentation.sameContent(message, message),
            "message content belongs to the separate message view"
        );
    }

    private static CodexTranscriptItem card(String status, boolean streaming) {
        return CodexTranscriptItem.card(
            "item", CodexTranscriptItem.Kind.TOOL, "commandExecution", "Command",
            "command", "first line", status, streaming
        );
    }

    private static CodexTranscriptItem typedCard(CodexTranscriptItem.Kind kind, String type) {
        return CodexTranscriptItem.card(
            "item", kind, type, "Activity", "Summary", "Detail", "completed", false
        );
    }
}
