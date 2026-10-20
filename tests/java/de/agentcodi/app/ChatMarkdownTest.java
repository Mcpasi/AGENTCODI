package de.agentcodi.app;

import de.agentcodi.tests.TestSupport;

import java.util.Random;

public final class ChatMarkdownTest {
    public static int run() {
        preservesPlainTextAndUnicode();
        projectsNestedEmphasisAndEscapes();
        preservesCodeLiterally();
        preservesListMarkersInIndentedCode();
        rendersHeadingsWithoutTreatingHashesInWordsAsMarkup();
        rendersListsTasksAndQuotes();
        rendersTablesWithEscapedAndCodePipes();
        preservesTableColumnsWithUnfinishedCode();
        handlesBalancedLinksAndAutolinks();
        decodesEntitiesInLinkDestinations();
        keepsUnsafeDestinationsInactive();
        leavesUnfinishedInlineMarkupVisible();
        supportsOpenFencesDuringStreaming();
        decodesEntitiesOutsideCode();
        validatesEveryStreamingPrefix();
        boundsDeepAndDelimiterHeavyInput();
        validatesRandomizedUnicodeInput();
        return 17;
    }

    private static void preservesPlainTextAndUnicode() {
        String source = "Hallo 👩🏽‍💻 世界\r\nzweite Zeile\rdritte Zeile";
        ChatMarkdown.Document doc = ChatMarkdown.parse(source);
        TestSupport.assertEquals("Hallo 👩🏽‍💻 世界\nzweite Zeile\ndritte Zeile", doc.text,
            "plain text keeps Unicode and visible line breaks");
        TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(doc.spans.size()),
            "plain text does not acquire formatting");
        TestSupport.assertEquals("", ChatMarkdown.parse("").text, "empty response remains empty");
    }

    private static void projectsNestedEmphasisAndEscapes() {
        ChatMarkdown.Document nested = ChatMarkdown.parse("**Fett *kursiv 👋*** und *kursiv **fett***");
        TestSupport.assertEquals("Fett kursiv 👋 und kursiv fett", nested.text,
            "nested emphasis can share an adjacent closing run");
        span(nested, ChatMarkdown.Style.BOLD, "Fett kursiv 👋");
        span(nested, ChatMarkdown.Style.ITALIC, "kursiv 👋");
        span(nested, ChatMarkdown.Style.ITALIC, "kursiv fett");
        span(nested, ChatMarkdown.Style.BOLD, "fett");
        ChatMarkdown.Document doc = ChatMarkdown.parse("**Fett *kursiv 👋* Text** und ~~alt~~, ***beides***, foo_bar_baz, \\*literal\\*");
        TestSupport.assertEquals("Fett kursiv 👋 Text und alt, beides, foo_bar_baz, *literal*", doc.text,
            "delimiters are removed only for completed supported emphasis");
        span(doc, ChatMarkdown.Style.BOLD, "Fett kursiv 👋 Text");
        span(doc, ChatMarkdown.Style.ITALIC, "kursiv 👋");
        span(doc, ChatMarkdown.Style.STRIKE, "alt");
        span(doc, ChatMarkdown.Style.BOLD, "beides");
        span(doc, ChatMarkdown.Style.ITALIC, "beides");
    }

    private static void preservesCodeLiterally() {
        ChatMarkdown.Document inline = ChatMarkdown.parse("Code: `` `**x**` `` und `a\\` und `x\ny`");
        TestSupport.assertEquals("Code: `**x**` und a\\ und x y", inline.text,
            "exact code runs preserve punctuation and backslashes");
        span(inline, ChatMarkdown.Style.CODE, "`**x**`");
        span(inline, ChatMarkdown.Style.CODE, "a\\");
        span(inline, ChatMarkdown.Style.CODE, "x y");
        ChatMarkdown.Document block = ChatMarkdown.parse(
            "````java\n  **literal** &amp; [x](https://example.com)\n```\n````\nAfter"
        );
        TestSupport.assertEquals("  **literal** &amp; [x](https://example.com)\n```\nAfter", block.text,
            "fences preserve indentation, entities and shorter fence runs");
        span(block, ChatMarkdown.Style.CODE_BLOCK, "  **literal** &amp; [x](https://example.com)\n```\n");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(block.spans.size()),
            "code does not create links or emphasis");
        ChatMarkdown.Document indented = ChatMarkdown.parse("    **literal**\n    next\n");
        TestSupport.assertEquals("**literal**\nnext\n", indented.text, "indented code preserves its contents");
    }

    private static void rendersHeadingsWithoutTreatingHashesInWordsAsMarkup() {
        ChatMarkdown.Document doc = ChatMarkdown.parse("# Titel ##\n\nUntertitel\n---\n\nC# and #tag\n\n***\n");
        TestSupport.assertEquals("Titel\n\nUntertitel\n\nC# and #tag\n\n────────\n", doc.text,
            "headings and rules render while literal hashes remain");
        TestSupport.assertEquals(Integer.valueOf(1), Integer.valueOf(span(doc, ChatMarkdown.Style.HEADING, "Titel").level),
            "ATX heading retains its level");
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(span(doc, ChatMarkdown.Style.HEADING, "Untertitel").level),
            "setext heading retains its level");
        valid(doc);
    }

    private static void preservesListMarkersInIndentedCode() {
        ChatMarkdown.Document doc = ChatMarkdown.parse(
            "    - **literal**\n    1. [link](https://example.com)\n    + [x] task\n"
        );
        TestSupport.assertEquals("- **literal**\n1. [link](https://example.com)\n+ [x] task\n", doc.text,
            "list and task markers inside indented code remain literal");
        span(doc, ChatMarkdown.Style.CODE_BLOCK, "- **literal**\n");
        span(doc, ChatMarkdown.Style.CODE_BLOCK, "1. [link](https://example.com)\n");
        span(doc, ChatMarkdown.Style.CODE_BLOCK, "+ [x] task\n");
        TestSupport.assertEquals(Integer.valueOf(3), Integer.valueOf(doc.spans.size()),
            "indented code cannot activate links, emphasis or tasks");
        TestSupport.assertEquals("• outer\n    • inner", ChatMarkdown.parse("- outer\n    - inner").text,
            "an indented marker in an active list still renders a nested item");
        TestSupport.assertEquals("paragraph\n    - continuation",
            ChatMarkdown.parse("paragraph\n    - continuation").text,
            "an indented marker cannot interrupt an existing paragraph");
        valid(doc);
    }

    private static void rendersListsTasksAndQuotes() {
        ChatMarkdown.Document doc = ChatMarkdown.parse("- **eins**\n  + zwei\n1. drei\n2) [x] fertig\n- [ ] offen\n\n> Zitat *kursiv*\n");
        TestSupport.assertEquals("• eins\n  • zwei\n1. drei\n2) ☑ fertig\n• ☐ offen\n\nZitat kursiv\n", doc.text,
            "list nesting, numbering, tasks and quote text stay readable");
        span(doc, ChatMarkdown.Style.BOLD, "eins");
        span(doc, ChatMarkdown.Style.QUOTE, "Zitat kursiv");
    }

    private static void rendersTablesWithEscapedAndCodePipes() {
        ChatMarkdown.Document doc = ChatMarkdown.parse("| Name | Wert |\n| :--- | ---: |\n| **A** | `x|y` |\n| B | z\\|q |\n");
        TestSupport.assertEquals("Name │ Wert\nA    │ x|y\nB    │ z|q\n", doc.text,
            "table rules disappear, columns align and literal cell pipes survive");
        span(doc, ChatMarkdown.Style.CODE, "x|y");
        span(doc, ChatMarkdown.Style.BOLD, "A");
        span(doc, ChatMarkdown.Style.BOLD, "Name │ Wert");
        valid(doc);
        String invalid = "a | b\n--- | --- | ---";
        TestSupport.assertEquals(invalid, ChatMarkdown.parse(invalid).text,
            "mismatched headers and separators remain ordinary text");
    }

    private static void handlesBalancedLinksAndAutolinks() {
        ChatMarkdown.Document doc = ChatMarkdown.parse(
            "[**Doku 👋**](https://example.com/a_(b)?x=1&amp;y=2 \"Titel\") "
                + "<https://example.org> ![Bild](https://example.com/image.png)"
        );
        TestSupport.assertEquals("Doku 👋 https://example.org Bild", doc.text,
            "link labels render, and images render their alt text without loading resources");
        TestSupport.assertEquals("https://example.com/a_(b)?x=1&y=2",
            span(doc, ChatMarkdown.Style.LINK, "Doku 👋").destination,
            "balanced parentheses, entities and optional titles preserve the destination");
        span(doc, ChatMarkdown.Style.BOLD, "Doku 👋");
        span(doc, ChatMarkdown.Style.LINK, "https://example.org");
        int links = 0;
        for (ChatMarkdown.Span span : doc.spans) { if (span.style == ChatMarkdown.Style.LINK) { links++; } }
        TestSupport.assertEquals(Integer.valueOf(2), Integer.valueOf(links), "image alt text is not a remote image action");
    }

    private static void preservesTableColumnsWithUnfinishedCode() {
        ChatMarkdown.Document doc = ChatMarkdown.parse(
            "| A | B |\n| --- | --- |\n| `open | value |\n| `x|y` | ok |"
        );
        TestSupport.assertEquals("A     │ B\n`open │ value\nx|y   │ ok", doc.text,
            "an unmatched backtick is literal and cannot swallow table column separators");
        span(doc, ChatMarkdown.Style.TABLE, doc.text);
        span(doc, ChatMarkdown.Style.CODE, "x|y");
        ChatMarkdown.Document header = ChatMarkdown.parse("| `open | B |\n| --- | --- |\n| a | b |");
        TestSupport.assertEquals("`open │ B\na     │ b", header.text,
            "an unfinished code span in a header cannot prevent table recognition");
        span(header, ChatMarkdown.Style.TABLE, header.text);
        valid(doc);
        valid(header);
    }

    private static void decodesEntitiesInLinkDestinations() {
        for (String entity : new String[] { "&amp;", "&#38;", "&#x26;" }) {
            String encoded = "https://example.com/?a=1" + entity + "b=2";
            for (boolean angle : new boolean[] { true, false }) {
                String target = angle ? "<" + encoded + ">" : encoded;
                ChatMarkdown.Document doc = ChatMarkdown.parse("[**Doku 👋**](" + target + " \"Titel\")");
                TestSupport.assertEquals("Doku 👋", doc.text, "link labels keep their formatted Unicode text");
                TestSupport.assertEquals("https://example.com/?a=1&b=2",
                    span(doc, ChatMarkdown.Style.LINK, "Doku 👋").destination,
                    "named and numeric entities decode in bare and angle-bracket link destinations");
                span(doc, ChatMarkdown.Style.BOLD, "Doku 👋");
                valid(doc);
            }
        }
        ChatMarkdown.Document escaped = ChatMarkdown.parse(
            "[literal](<https://example.com/\\*x\\*?a=1\\&amp;b=2>)"
        );
        TestSupport.assertEquals("https://example.com/*x*?a=1&amp;b=2",
            span(escaped, ChatMarkdown.Style.LINK, "literal").destination,
            "escaped punctuation is decoded once and escaped entity text stays literal");
        ChatMarkdown.Document once = ChatMarkdown.parse("[once](<https://example.com/?x=&amp;amp;>)");
        TestSupport.assertEquals("https://example.com/?x=&amp;",
            span(once, ChatMarkdown.Style.LINK, "once").destination, "entities are never decoded twice");
        ChatMarkdown.Document unsafe = ChatMarkdown.parse("[unsafe](<javascript&#58;alert(1)>)");
        TestSupport.assertEquals("unsafe", unsafe.text, "encoded unsafe destinations retain their label");
        TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(unsafe.spans.size()),
            "destination validation happens after entity decoding");
    }

    private static void keepsUnsafeDestinationsInactive() {
        for (String url : new String[] { "javascript:alert(1)", "data:text/html,test", "file:///tmp/test",
            "intent://example", "https://", "https://example.com/a b", "relative/path" }) {
            TestSupport.assertFalse(ChatMarkdown.safeDestination(url), "unsupported or malformed link stays inactive: " + url);
        }
        ChatMarkdown.Document doc = ChatMarkdown.parse("[label](javascript:alert(1)) <script>raw</script>");
        TestSupport.assertEquals("label <script>raw</script>", doc.text, "raw HTML remains literal native text");
        TestSupport.assertEquals(Integer.valueOf(0), Integer.valueOf(doc.spans.size()), "no executable link spans are created");
        TestSupport.assertTrue(ChatMarkdown.safeDestination("HTTPS://example.com"), "scheme matching ignores case");
        TestSupport.assertTrue(ChatMarkdown.safeDestination("mailto:person@example.com"), "email links remain available");
    }

    private static void leavesUnfinishedInlineMarkupVisible() {
        for (String source : new String[] { "**part", "*part", "~~part", "`part", "[part](https://", "\\", "a_b_c" }) {
            TestSupport.assertEquals(source, ChatMarkdown.parse(source).text, "unfinished inline markup stays visible: " + source);
        }
        ChatMarkdown.Document completed = ChatMarkdown.parse("**part complete**");
        TestSupport.assertEquals("part complete", completed.text, "a later complete snapshot replaces unfinished markup");
        span(completed, ChatMarkdown.Style.BOLD, "part complete");
    }

    private static void supportsOpenFencesDuringStreaming() {
        ChatMarkdown.Document doc = ChatMarkdown.parse("~~~python\n    print('👋')\n**literal");
        TestSupport.assertEquals("    print('👋')\n**literal", doc.text, "open streaming fence already presents literal code");
        span(doc, ChatMarkdown.Style.CODE_BLOCK, doc.text);
        TestSupport.assertEquals("", ChatMarkdown.parse("```java").text, "an incomplete fence info line does not become code");
    }

    private static void decodesEntitiesOutsideCode() {
        ChatMarkdown.Document doc = ChatMarkdown.parse("&lt;b&gt; &amp; &#128075; &#x1F680; &#xD800; `&amp;`");
        TestSupport.assertEquals("<b> & 👋 🚀 &#xD800; &amp;", doc.text, "entities decode without HTML execution or invalid Unicode");
        span(doc, ChatMarkdown.Style.CODE, "&amp;");
    }

    private static void validatesEveryStreamingPrefix() {
        String source = "# 👋\n\n**bold *nested* end** [link](https://example.com/a_(b))\n\n```java\n  x();\n```\n";
        for (int end = 0; end <= source.length(); end++) { valid(ChatMarkdown.parse(source.substring(0, end))); }
    }

    private static void boundsDeepAndDelimiterHeavyInput() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 8192; index++) { source.append("[<*_`~ "); }
        ChatMarkdown.Document doc = ChatMarkdown.parse(source.toString());
        valid(doc);
        TestSupport.assertTrue(doc.text.length() <= source.length(), "adversarial markup cannot expand display storage");
        String nested = "x";
        for (int index = 0; index < 100; index++) { nested = "[" + nested + "](https://example.com)"; }
        valid(ChatMarkdown.parse(nested));
    }

    private static void validatesRandomizedUnicodeInput() {
        Random random = new Random(1606L);
        String[] tokens = { "👋", "世界", "a", " ", "\n", "*", "_", "~", "`", "[", "]", "(", ")", "|", ">", "<", "\\", "&" };
        for (int attempt = 0; attempt < 1000; attempt++) {
            StringBuilder source = new StringBuilder();
            for (int index = 0; index < 80; index++) { source.append(tokens[random.nextInt(tokens.length)]); }
            ChatMarkdown.Document doc = ChatMarkdown.parse(source.toString());
            valid(doc);
            for (ChatMarkdown.Span span : doc.spans) {
                TestSupport.assertFalse(span.start > 0 && Character.isLowSurrogate(doc.text.charAt(span.start)),
                    "formatting never splits the leading surrogate of an emoji");
                TestSupport.assertFalse(span.end < doc.text.length() && Character.isLowSurrogate(doc.text.charAt(span.end)),
                    "formatting never splits the trailing surrogate of an emoji");
            }
        }
    }

    private static ChatMarkdown.Span span(ChatMarkdown.Document doc, ChatMarkdown.Style style, String text) {
        for (ChatMarkdown.Span span : doc.spans) {
            if (span.style == style && text.equals(doc.text.substring(span.start, span.end))) { return span; }
        }
        throw new AssertionError("Missing " + style + " for " + text + " in " + doc.text);
    }

    private static void valid(ChatMarkdown.Document doc) {
        for (ChatMarkdown.Span span : doc.spans) {
            TestSupport.assertTrue(span.start >= 0 && span.end >= span.start && span.end <= doc.text.length(),
                "every span stays within the projected UTF-16 text");
        }
    }

    private ChatMarkdownTest() { }
}
