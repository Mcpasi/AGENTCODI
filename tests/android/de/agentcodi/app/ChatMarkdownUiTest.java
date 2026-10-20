package de.agentcodi.app;

import android.app.Application;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.widget.TextView;

import de.agentcodi.core.ChatMessage;
import de.agentcodi.core.CodexTranscriptItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ChatMarkdownUiTest {
    private ActivityController<MainActivity> lifecycle;
    private MainActivity activity;

    @Before public void create() {
        lifecycle = Robolectric.buildActivity(MainActivity.class).create();
        activity = lifecycle.get();
    }

    @After public void destroy() { lifecycle.destroy(); }

    @Test public void assistantRendersMarkdownWhileUserInputAndModelStayLiteral() throws Exception {
        ChatMessage assistant = new ChatMessage("assistant", ChatMessage.Role.ASSISTANT, "**Hello**", false);
        render(CodexTranscriptItem.message(assistant), CodexTranscriptItem.message(
            new ChatMessage("user", ChatMessage.Role.USER, "**literal input**", false)));
        TextView answer = text(0);
        assertEquals("Hello", answer.getText().toString());
        assertTrue(((Spanned) answer.getText()).getSpans(0, 5, StyleSpan.class).length > 0);
        assertEquals("**literal input**", text(1).getText().toString());
        assertEquals("**Hello**", assistant.getText());
    }

    @Test public void streamingRebindUpdatesExistingBubbleWithoutChangingItsSource() throws Exception {
        render(message("**in progress", true));
        TextView original = text(0);
        render(message("**finished**", false));
        assertSame(original, text(0));
        assertEquals("finished", original.getText().toString());
        render(message("**finished**", false));
        assertSame(original, text(0));
    }

    @Test public void unsafeLinksStayInactiveAndToolCommandsStayLiteral() throws Exception {
        CodexTranscriptItem tool = CodexTranscriptItem.card("tool", CodexTranscriptItem.Kind.TOOL,
            "commandExecution", "Command", "echo **literal**", "**unmodified output**", "completed", false);
        render(message("[safe](https://example.org) [unsafe](file:///private/data)", false), tool);
        Spanned answer = (Spanned) text(0).getText();
        URLSpan[] links = answer.getSpans(0, answer.length(), URLSpan.class);
        assertEquals(1, links.length);
        assertEquals("https://example.org", links[0].getURL());
        Object card = field(rows().get(1), "card");
        assertEquals("echo **literal**", ((TextView) field(card, "summary")).getText().toString());
        assertEquals("**unmodified output**", ((TextView) field(card, "detail")).getText().toString());
    }

    private CodexTranscriptItem message(String value, boolean streaming) {
        return CodexTranscriptItem.message(new ChatMessage("assistant", ChatMessage.Role.ASSISTANT,
            value, streaming));
    }

    private void render(CodexTranscriptItem... items) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("renderTranscript", String.class, List.class);
        method.setAccessible(true);
        method.invoke(activity, "thread", Arrays.asList(items));
    }

    private List<?> rows() throws Exception { return (List<?>) field(activity, "renderedTranscriptRows"); }
    private TextView text(int row) throws Exception { return (TextView) field(rows().get(row), "text"); }
    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
