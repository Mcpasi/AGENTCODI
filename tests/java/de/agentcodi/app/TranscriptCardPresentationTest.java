package de.agentcodi.app;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexTranscriptItem;
import de.agentcodi.tests.TestSupport;

import java.util.Arrays;
import java.util.Collections;

public final class TranscriptCardPresentationTest {
    private TranscriptCardPresentationTest() {
    }

    public static int run() {
        terminalStatesTakePrecedenceOverStreaming();
        unknownAndEmptyStatesDoNotImplySuccess();
        technicalContentUsesCodeTypography();
        updatesIncludeStatusAndImageChanges();
        cardsNeverReuseMessageContent();
        toolCardsStartCollapsed();
        otherTranscriptItemsRemainVisible();
        toolCardsToggleIndependently();
        expansionSurvivesStreamingAndTranscriptRebuilds();
        expansionIsScopedToTheCurrentChat();
        removedAndReplacedCardsForgetExpansion();
        imageResultsPreserveExpansion();
        return 12;
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

    private static void toolCardsStartCollapsed() {
        for (String type : new String[] {
            "commandExecution", "fileChange", "mcpToolCall", "webSearch",
            "imageGeneration", "unknownTool"
        }) {
            for (String status : new String[] { "inProgress", "completed", "failed" }) {
                CodexTranscriptItem item = CodexTranscriptItem.card(
                    "item", CodexTranscriptItem.Kind.TOOL, type, "Activity",
                    "Summary", "Detail", status, "inProgress".equals(status)
                );
                TranscriptCardPresentation.ExpansionState expansion =
                    new TranscriptCardPresentation.ExpansionState();
                expansion.update("thread", Collections.singletonList(item));
                TestSupport.assertTrue(
                    TranscriptCardPresentation.isCollapsible(item),
                    "all tool types offer a disclosure control"
                );
                TestSupport.assertFalse(
                    expansion.isExpanded(item),
                    "live and resumed tools start collapsed regardless of result"
                );
            }
        }
    }

    private static void otherTranscriptItemsRemainVisible() {
        TranscriptCardPresentation.ExpansionState expansion =
            new TranscriptCardPresentation.ExpansionState();
        for (CodexTranscriptItem item : new CodexTranscriptItem[] {
            typedCard(CodexTranscriptItem.Kind.REASONING, "reasoning"),
            typedCard(CodexTranscriptItem.Kind.PLAN, "plan"),
            CodexTranscriptItem.message(new ChatMessage(
                "item", ChatMessage.Role.ASSISTANT, "Visible answer", false
            ))
        }) {
            expansion.update("thread", Collections.singletonList(item));
            TestSupport.assertFalse(
                TranscriptCardPresentation.isCollapsible(item),
                "messages, reasoning and plans retain their existing layout"
            );
            expansion.toggle(item);
            TestSupport.assertTrue(
                expansion.isExpanded(item),
                "other transcript content cannot be hidden by a tool toggle"
            );
        }
    }

    private static void toolCardsToggleIndependently() {
        CodexTranscriptItem command = card("inProgress", true);
        CodexTranscriptItem change = fileChange("inProgress", true);
        TranscriptCardPresentation.ExpansionState expansion =
            new TranscriptCardPresentation.ExpansionState();
        expansion.update("thread", Arrays.asList(command, change));
        expansion.toggle(command);
        TestSupport.assertTrue(expansion.isExpanded(command), "command opens on request");
        TestSupport.assertFalse(expansion.isExpanded(change), "its sibling stays collapsed");
        expansion.toggle(change);
        TestSupport.assertTrue(expansion.isExpanded(change), "file changes can also be opened");
        TestSupport.assertTrue(expansion.isExpanded(command), "multiple cards may stay open");
        expansion.toggle(command);
        TestSupport.assertFalse(expansion.isExpanded(command), "another toggle closes the card");
        TestSupport.assertTrue(expansion.isExpanded(change), "closing a sibling preserves the diff");
        expansion.toggle(change);
        TestSupport.assertFalse(expansion.isExpanded(change), "file changes can be closed again");
    }

    private static void expansionSurvivesStreamingAndTranscriptRebuilds() {
        CodexTranscriptItem command = card("inProgress", true);
        CodexTranscriptItem change = fileChange("inProgress", true);
        TranscriptCardPresentation.ExpansionState expansion =
            new TranscriptCardPresentation.ExpansionState();
        expansion.update("thread", Arrays.asList(command, change));
        expansion.toggle(command);
        expansion.toggle(change);
        expansion.toggle(change);

        CodexTranscriptItem updatedCommand = CodexTranscriptItem.card(
            "item", CodexTranscriptItem.Kind.TOOL, "commandExecution", "Updated title",
            "updated command", "first line\nsecond line", "inProgress", true
        );
        CodexTranscriptItem answer = CodexTranscriptItem.message(new ChatMessage(
            "answer", ChatMessage.Role.ASSISTANT, "Working", true
        ));
        expansion.update("thread", Arrays.asList(change, updatedCommand, answer));
        TestSupport.assertTrue(
            expansion.isExpanded(updatedCommand),
            "streaming, appended rows and reordering preserve the open card by identity"
        );
        TestSupport.assertFalse(
            expansion.isExpanded(change),
            "a manually closed card stays closed across a transcript rebuild"
        );

        CodexTranscriptItem completedCommand = updatedCommand.finish("completed");
        CodexTranscriptItem completedChange = change.finish("completed");
        expansion.update("thread", Arrays.asList(completedChange, completedCommand, answer));
        TestSupport.assertTrue(
            expansion.isExpanded(completedCommand),
            "completion does not close an expanded card"
        );
        TestSupport.assertFalse(
            expansion.isExpanded(completedChange),
            "completion does not open a collapsed file-change card"
        );
        TestSupport.assertEquals(
            TranscriptCardPresentation.State.COMPLETED,
            TranscriptCardPresentation.state(completedChange),
            "a collapsed card still exposes its authoritative completion status"
        );
        expansion.toggle(completedChange);
        TestSupport.assertTrue(
            expansion.isExpanded(completedChange),
            "completed file changes remain available for opening"
        );
    }

    private static void expansionIsScopedToTheCurrentChat() {
        CodexTranscriptItem command = card("completed", false);
        TranscriptCardPresentation.ExpansionState expansion =
            new TranscriptCardPresentation.ExpansionState();
        expansion.update("first-thread", Collections.singletonList(command));
        expansion.toggle(command);
        expansion.update("second-thread", Collections.singletonList(command));
        TestSupport.assertFalse(
            expansion.isExpanded(command),
            "matching item IDs in another chat do not inherit expansion"
        );
        expansion.toggle(command);
        expansion.update("first-thread", Collections.singletonList(command));
        TestSupport.assertFalse(
            expansion.isExpanded(command),
            "returning to a chat starts with collapsed tools"
        );
        expansion.toggle(command);
        TranscriptCardPresentation.ExpansionState recreated =
            new TranscriptCardPresentation.ExpansionState();
        recreated.update("first-thread", Collections.singletonList(command));
        TestSupport.assertFalse(
            recreated.isExpanded(command),
            "a recreated screen has no persisted expansion selection"
        );
        expansion.update("", Collections.<CodexTranscriptItem>emptyList());
        expansion.update("first-thread", Collections.singletonList(command));
        TestSupport.assertFalse(
            expansion.isExpanded(command),
            "clearing the active thread discards expansion state"
        );
    }

    private static void removedAndReplacedCardsForgetExpansion() {
        CodexTranscriptItem command = card("completed", false);
        CodexTranscriptItem change = fileChange("completed", false);
        TranscriptCardPresentation.ExpansionState expansion =
            new TranscriptCardPresentation.ExpansionState();
        expansion.update("thread", Arrays.asList(command, change));
        expansion.toggle(command);
        expansion.toggle(change);
        expansion.update("thread", Collections.singletonList(change));
        expansion.toggle(command);
        expansion.update("thread", Arrays.asList(command, change));
        TestSupport.assertFalse(
            expansion.isExpanded(command),
            "removed cards and stale toggles cannot retain state beyond the visible transcript"
        );
        TestSupport.assertTrue(
            expansion.isExpanded(change),
            "pruning one card leaves the remaining card open"
        );

        expansion.toggle(command);
        CodexTranscriptItem replacement = typedCard(CodexTranscriptItem.Kind.TOOL, "fileChange");
        expansion.update("thread", Collections.singletonList(replacement));
        expansion.toggle(command);
        TestSupport.assertFalse(
            expansion.isExpanded(replacement),
            "a different tool type sharing an ID starts collapsed and ignores the old toggle"
        );
        expansion.toggle(replacement);
        expansion.update("thread", Collections.singletonList(
            typedCard(CodexTranscriptItem.Kind.PLAN, "plan")
        ));
        expansion.update("thread", Collections.singletonList(replacement));
        TestSupport.assertFalse(
            expansion.isExpanded(replacement),
            "a replacement by another item kind also releases the previous tool state"
        );
    }

    private static void imageResultsPreserveExpansion() {
        CodexTranscriptItem image = typedCard(CodexTranscriptItem.Kind.TOOL, "imageGeneration");
        TranscriptCardPresentation.ExpansionState expansion =
            new TranscriptCardPresentation.ExpansionState();
        expansion.update("thread", Collections.singletonList(image));
        expansion.toggle(image);
        CodexTranscriptItem result = image.withReportedImagePath("/workspace/generated/result.png");
        expansion.update("thread", Collections.singletonList(result));
        TestSupport.assertTrue(
            expansion.isExpanded(result),
            "new image metadata does not collapse an expanded tool"
        );
        expansion.toggle(result);
        expansion.update("thread", Collections.singletonList(result));
        TestSupport.assertFalse(
            expansion.isExpanded(result),
            "refreshing an image result does not reopen a manually closed card"
        );
    }

    private static CodexTranscriptItem fileChange(String status, boolean streaming) {
        return CodexTranscriptItem.card(
            "change", CodexTranscriptItem.Kind.TOOL, "fileChange", "File changes",
            "1 file change", "--- before\n+++ after\n-old\n+new", status, streaming
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
