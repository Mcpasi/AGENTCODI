package de.agentcodi.tests;

import de.agentcodi.editor.syntax.SourceLines;
import de.agentcodi.editor.syntax.SyntaxHighlighter;
import de.agentcodi.editor.syntax.SyntaxLanguage;
import de.agentcodi.editor.syntax.SyntaxToken;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import static de.agentcodi.editor.syntax.SyntaxLanguage.*;
import static de.agentcodi.editor.syntax.SyntaxToken.Kind.*;

public final class WorkspaceSyntaxTest {
    private WorkspaceSyntaxTest() { }

    public static int run() {
        detectsOnlySupportedFileNames();
        javascriptCommentsAndQuotesTakePrecedence();
        javascriptNumbersRegexAndDivision();
        javascriptRegexAfterControlConditions();
        javascriptKeywordPropertiesKeepDivisionOperators();
        javascriptNestedTemplateExpressions();
        pythonStringsPrefixesAndComments();
        pythonEscapedCrLfStaysInsideStrings();
        pythonFormattedExpressionsAndDecorators();
        pythonKeywordsFunctionsAndNumbers();
        htmlAttributesEntitiesAndComments();
        htmlEmbeddedLanguagesAndRawText();
        htmlRawTextClosesBeforeTrailingSlash();
        cssSelectorsPropertiesValuesAndComments();
        cssGroupingRulesKeepSelectorsSeparateFromDeclarations();
        toleratesIncompleteEditsAndRetokenizes();
        retainsUnicodeUtf16Offsets();
        rangesStaySortedAndBoundedForArbitraryEdits();
        processesWholeMaximumSizedDocuments();
        cancelsSupersededWork();
        countsLogicalLinesAndCursorOffsets();
        indexesManyLinesAndRejectsInvalidOffsets();
        return 22;
    }

    private static void detectsOnlySupportedFileNames() {
        String[] paths = {"src/CODE.JS", "a.mjs", "a.cjs", "index.HTML", "index.htm", "a.css", "a.py", "a.pyw"};
        SyntaxLanguage[] expected = {JAVASCRIPT, JAVASCRIPT, JAVASCRIPT, HTML, HTML, CSS, PYTHON, PYTHON};
        for (int index = 0; index < paths.length; index++) {
            TestSupport.assertEquals(expected[index], SyntaxLanguage.fromPath(paths[index]), "extension language");
        }
        for (String path : new String[] {null, "", "JS", "file.txt", "a.js.bak", "a.ts", "a.jsx", "folder.js/plain", "folder.py\\plain"}) {
            TestSupport.assertEquals(PLAIN, SyntaxLanguage.fromPath(path), "plain fallback: " + path);
            TestSupport.assertTrue(SyntaxHighlighter.highlight(PLAIN, "const x = 1;").isEmpty(), "no guessing");
        }
        TestSupport.expectThrows(IllegalArgumentException.class,
            () -> SyntaxHighlighter.highlight(null, ""), "missing language");
    }

    private static void javascriptCommentsAndQuotesTakePrecedence() {
        String text = "#!/usr/bin/env node\n/* const 'not a string' */\nconst x = \"a\\\"//b\"; // return 2\nlet y = '//text';";
        token(JAVASCRIPT, text, "#!/usr/bin/env node", COMMENT);
        token(JAVASCRIPT, text, "/* const 'not a string' */", COMMENT);
        token(JAVASCRIPT, text, "\"a\\\"//b\"", STRING);
        token(JAVASCRIPT, text, "// return 2", COMMENT);
        token(JAVASCRIPT, text, "'//text'", STRING);
        token(JAVASCRIPT, text, "let", KEYWORD);
    }

    private static void javascriptNumbersRegexAndDivision() {
        String text = "const n = 0xFFn + 0b10 + 0o77 + .5e-2 + 1_000; const re = /a[\\/]+/giu; n / 2; n++ / 3;";
        for (String number : new String[] {"0xFFn", "0b10", "0o77", ".5e-2", "1_000"}) {
            token(JAVASCRIPT, text, number, NUMBER);
        }
        token(JAVASCRIPT, text, "/a[\\/]+/giu", STRING);
        int division = text.indexOf("n / 2") + 2;
        tokenAt(JAVASCRIPT, text, division, division + 1, OPERATOR);
        division = text.indexOf("n++ / 3") + 4;
        tokenAt(JAVASCRIPT, text, division, division + 1, OPERATOR);
    }

    private static void javascriptNestedTemplateExpressions() {
        String text = "const s = `hello ${format({x: `inner ${2 + 3}`})} tail`;";
        token(JAVASCRIPT, text, "`hello ", STRING);
        token(JAVASCRIPT, text, "format", FUNCTION);
        token(JAVASCRIPT, text, "`inner ", STRING);
        token(JAVASCRIPT, text, "2", NUMBER);
        token(JAVASCRIPT, text, " tail`", STRING);
        token(JAVASCRIPT, "`escaped \\${text}`", "`escaped \\${text}`", STRING);
    }

    private static void javascriptRegexAfterControlConditions() {
        for (String condition : new String[] {
            "if (ready)", "while (ready)", "for (; ready;)", "with (object)",
            "if (check(value / 2))", "for (const item of items)"
        }) {
            String text = condition + " /a[\\/]+/giu.test(value);";
            token(JAVASCRIPT, text, "/a[\\/]+/giu", STRING);
            token(JAVASCRIPT, text, "test", FUNCTION);
            ranges(JAVASCRIPT, text);
        }
        String division = "check(value) / 2 / 3; (value + 1) / 2 / 3;";
        for (int index = 0; index < division.length(); index++) {
            if (division.charAt(index) == '/') {
                tokenAt(JAVASCRIPT, division, index, index + 1, OPERATOR);
            }
        }
    }

    private static void javascriptKeywordPropertiesKeepDivisionOperators() {
        for (String property : new String[] {"return", "if", "for", "throw", "delete", "await"}) {
            for (String access : new String[] {".", "?."}) {
                String text = "const result = object" + access + property + " / 2 / 3;";
                int first = text.indexOf('/');
                int second = text.lastIndexOf('/');
                tokenAt(JAVASCRIPT, text, first, first + 1, OPERATOR);
                tokenAt(JAVASCRIPT, text, second, second + 1, OPERATOR);
                token(JAVASCRIPT, text, "2", NUMBER);
                token(JAVASCRIPT, text, "3", NUMBER);
            }
        }
    }

    private static void pythonEscapedCrLfStaysInsideStrings() {
        for (String prefix : new String[] {"", "r", "R", "b", "br", "f", "fr", "rf"}) {
            String literal = prefix + "\"first\\\r\nsecond\"";
            String text = "value = " + literal + "\r\nprint(value)";
            token(PYTHON, text, literal, STRING);
            token(PYTHON, text, "print", BUILTIN);
            ranges(PYTHON, text);
        }
    }

    private static void htmlRawTextClosesBeforeTrailingSlash() {
        for (String tag : new String[] {"script", "style", "textarea", "title"}) {
            String text = "<" + tag + ">plain</" + tag.toUpperCase(java.util.Locale.ROOT)
                + "/><main>after</main>";
            token(HTML, text, "main", TAG);
            token(HTML, text, tag.toUpperCase(java.util.Locale.ROOT), TAG);
            ranges(HTML, text);
        }
    }

    private static void pythonStringsPrefixesAndComments() {
        String text = "# def pretend\r\nx = r'\\#raw'\ny = BR\"bytes\"\nz = '''multi\n# string\nreturn True'''\nreal = 1 # comment";
        token(PYTHON, text, "# def pretend", COMMENT);
        token(PYTHON, text, "r'\\#raw'", STRING);
        token(PYTHON, text, "BR\"bytes\"", STRING);
        token(PYTHON, text, "'''multi\n# string\nreturn True'''", STRING);
        token(PYTHON, text, "# comment", COMMENT);
    }

    private static void pythonFormattedExpressionsAndDecorators() {
        String text = "@tools.cache\ndef greeting(name):\n    return f\"hello {{literal}} {len(name) + 2} tail\"";
        token(PYTHON, text, "@tools.cache", FUNCTION);
        token(PYTHON, text, "greeting", FUNCTION);
        token(PYTHON, text, "f\"hello {{literal}} ", STRING);
        token(PYTHON, text, "len", BUILTIN);
        token(PYTHON, text, "2", NUMBER);
        token(PYTHON, text, " tail\"", STRING);
    }

    private static void pythonKeywordsFunctionsAndNumbers() {
        String text = "async def count():\n    if True and None is not False:\n        print(0xF + 0b11 + 0o70 + 1_000.5e+2 + 3j)";
        for (String keyword : new String[] {"async", "def", "if", "True", "and", "None", "is", "not", "False"}) {
            token(PYTHON, text, keyword, KEYWORD);
        }
        token(PYTHON, text, "count", FUNCTION);
        token(PYTHON, text, "print", BUILTIN);
        for (String number : new String[] {"0xF", "0b11", "0o70", "1_000.5e+2", "3j"}) {
            token(PYTHON, text, number, NUMBER);
        }
    }

    private static void htmlAttributesEntitiesAndComments() {
        String text = "<!DOCTYPE html><!-- <script>fake()</script> --><main data-x=ok title=\"a>b\\\">&amp;<br/></main>";
        token(HTML, text, "<!DOCTYPE html>", KEYWORD);
        token(HTML, text, "<!-- <script>fake()</script> -->", COMMENT);
        token(HTML, text, "main", TAG);
        token(HTML, text, "data-x", ATTRIBUTE);
        token(HTML, text, "ok", STRING);
        token(HTML, text, "\"a>b\\\"", STRING);
        token(HTML, text, "&amp;", BUILTIN);
        token(HTML, text, "br", TAG);
    }

    private static void htmlEmbeddedLanguagesAndRawText() {
        String text = "<STYLE>.x { color: #fff; }</STYLE><SCRIPT type=\"module\">const x = 3;</SCRIPT>"
            + "<script type=\"application/json\">{\"ignored\":true}</script><textarea><b>plain</b></textarea>";
        token(HTML, text, "color", ATTRIBUTE);
        token(HTML, text, "#fff", NUMBER);
        token(HTML, text, "const", KEYWORD);
        token(HTML, text, "3", NUMBER);
        int ignored = text.indexOf("{\"ignored\":true}");
        for (SyntaxToken token : SyntaxHighlighter.highlight(HTML, text)) {
            TestSupport.assertFalse(token.getStart() >= ignored && token.getStart() < ignored + 16, "non-JS script remains plain");
            TestSupport.assertFalse(token.getStart() >= text.indexOf("<b>plain")
                && token.getStart() < text.indexOf("</textarea>"), "textarea is raw text");
        }
    }

    private static void cssSelectorsPropertiesValuesAndComments() {
        String text = "/* color: fake */\n@media screen { .card { --space: 1.5rem; color: #aBc123; width: calc(100% - 2px); margin: -12px +.5em; content: \"/* string */\"; } }";
        token(CSS, text, "/* color: fake */", COMMENT);
        token(CSS, text, "@media", KEYWORD);
        token(CSS, text, "--space", ATTRIBUTE);
        token(CSS, text, "1.5rem", NUMBER);
        token(CSS, text, "color", ATTRIBUTE);
        token(CSS, text, "#aBc123", NUMBER);
        token(CSS, text, "calc", FUNCTION);
        token(CSS, text, "100%", NUMBER);
        token(CSS, text, "2px", NUMBER);
        token(CSS, text, "-12px", NUMBER);
        token(CSS, text, "+.5em", NUMBER);
        token(CSS, text, "\"/* string */\"", STRING);
    }

    private static void toleratesIncompleteEditsAndRetokenizes() {
        for (SyntaxLanguage language : new SyntaxLanguage[] {JAVASCRIPT, HTML, CSS, PYTHON}) {
            for (String text : new String[] {"", "'", "\"", "/*", "<!--", "<div x=", "`x ${", "f\"{", "'''open\n", "\\"}) {
                ranges(language, text);
            }
        }
        token(JAVASCRIPT, "/* const x */\nreturn 1", "/* const x */", COMMENT);
        token(JAVASCRIPT, "const x\nreturn 1", "const", KEYWORD);
        token(PYTHON, "'''def f():\nreturn 1", "'''def f():\nreturn 1", STRING);
        token(PYTHON, "def f():\nreturn 1", "def", KEYWORD);
        TestSupport.expectThrows(UnsupportedOperationException.class,
            () -> SyntaxHighlighter.highlight(JAVASCRIPT, "const x = 1").clear(), "immutable results");
    }

    private static void cssGroupingRulesKeepSelectorsSeparateFromDeclarations() {
        for (String rule : new String[] {"@media screen", "@supports (display: grid)", "@layer theme",
            "@container (width > 100px)", "@scope (.page)"}) {
            String text = rule + " { .card:hover { color: #abc; } #abc { width: 10px; } }"
                + " @font-face { font-family: 'Demo'; } a { margin: 0; }";
            token(CSS, text, ".card", TAG);
            int selector = text.indexOf("#abc {");
            tokenAt(CSS, text, selector, selector + 4, TAG);
            int color = text.indexOf("#abc;");
            tokenAt(CSS, text, color, color + 4, NUMBER);
            for (String property : new String[] {"color", "width", "font-family", "margin"}) {
                token(CSS, text, property, ATTRIBUTE);
            }
            ranges(CSS, text);
        }
        String nested = "@media screen { @supports (display: grid) { .card:hover { color: #abc; } } }";
        token(CSS, nested, ".card", TAG);
        token(CSS, nested, "color", ATTRIBUTE);
        token(CSS, nested, "#abc", NUMBER);
        String conditionalDeclaration = "a { @media (width > 10px) { color: #abc; } margin: 0; }";
        token(CSS, conditionalDeclaration, "color", ATTRIBUTE);
        token(CSS, conditionalDeclaration, "#abc", NUMBER);
        token(CSS, conditionalDeclaration, "margin", ATTRIBUTE);
        String animation = "@keyframes fade { from { opacity: 0; } 100% { opacity: 1; } }";
        token(CSS, animation, "from", TAG);
        token(CSS, animation, "opacity", ATTRIBUTE);
        token(CSS, animation, "100%", NUMBER);
    }

    private static void retainsUnicodeUtf16Offsets() {
        String text = "const Grüße = '世界 🐱';\r\nreturn Grüße;";
        token(JAVASCRIPT, text, "'世界 🐱'", STRING);
        token(JAVASCRIPT, text, "return", KEYWORD);
        ranges(JAVASCRIPT, text);
        ranges(PYTHON, "变量 = '世界 🐱'\rprint(变量)");
    }

    private static void rangesStaySortedAndBoundedForArbitraryEdits() {
        Random random = new Random(20261012);
        String alphabet = "abcXYZ_09/\\<>!#;:$+-={}[()]'\"` &\r\n\t🐱";
        for (SyntaxLanguage language : SyntaxLanguage.values()) {
            for (int sample = 0; sample < 100; sample++) {
                StringBuilder text = new StringBuilder();
                for (int index = 0; index < 200; index++) { text.append(alphabet.charAt(random.nextInt(alphabet.length()))); }
                ranges(language, text.toString());
            }
        }
        String nested = "`";
        for (int index = 0; index < 200; index++) { nested += "${`"; }
        ranges(JAVASCRIPT, nested);
    }

    private static void processesWholeMaximumSizedDocuments() {
        StringBuilder source = new StringBuilder(1024 * 1024);
        while (source.length() < 1024 * 1024 - 64) { source.append("\n "); }
        int tail = source.length();
        for (SyntaxLanguage language : new SyntaxLanguage[] {JAVASCRIPT, HTML, CSS, PYTHON}) {
            String text = source + (language == HTML ? "<div>tail</div>" : language == CSS ? "a{width:2px}" : "return 123");
            List<SyntaxToken> tokens = SyntaxHighlighter.highlight(language, text);
            TestSupport.assertTrue(!tokens.isEmpty() && tokens.get(tokens.size() - 1).getEnd() > tail,
                "highlighting reaches the end of large " + language + " documents");
            ranges(language, text);
        }
    }

    private static void cancelsSupersededWork() {
        Thread.currentThread().interrupt();
        try {
            TestSupport.expectThrows(CancellationException.class,
                () -> SyntaxHighlighter.highlight(JAVASCRIPT, "const x = 1"), "cancel background scan");
        } finally { Thread.interrupted(); }
    }

    private static void countsLogicalLinesAndCursorOffsets() {
        SourceLines empty = new SourceLines("");
        TestSupport.assertEquals(1, empty.getLineCount(), "empty file has line 1");
        SourceLines lines = new SourceLines("a\r\nb\nc\rd\n");
        TestSupport.assertEquals(5, lines.getLineCount(), "CRLF, LF, CR and trailing empty line");
        int[] starts = {0, 3, 5, 7, 9};
        for (int line = 0; line < starts.length; line++) {
            TestSupport.assertEquals(starts[line], lines.getStart(line), "logical line start");
            TestSupport.assertEquals(line, lines.getLineForOffset(starts[line]), "cursor at line start");
        }
        TestSupport.assertEquals(0, lines.getLineForOffset(2), "cursor inside CRLF stays on preceding line");
        TestSupport.assertEquals(1, new SourceLines("🐱\n世界").getLineForOffset(3), "UTF-16 offsets");
        TestSupport.assertEquals(1, new SourceLines("long wrapped-looking text").getLineCount(), "wrapping does not create source lines");
    }

    private static void indexesManyLinesAndRejectsInvalidOffsets() {
        String text = new String(new char[10000]).replace('\0', '\n');
        SourceLines lines = new SourceLines(text);
        TestSupport.assertEquals(10001, lines.getLineCount(), "all blank lines indexed");
        TestSupport.assertEquals(10000, lines.getLineForOffset(10000), "final empty line");
        TestSupport.expectThrows(IndexOutOfBoundsException.class, () -> lines.getLineForOffset(-1), "negative cursor");
        TestSupport.expectThrows(IndexOutOfBoundsException.class, () -> lines.getLineForOffset(10001), "past end cursor");
    }

    private static void token(SyntaxLanguage language, String text, String value, SyntaxToken.Kind kind) {
        int start = text.indexOf(value);
        TestSupport.assertTrue(start >= 0, "test fragment exists: " + value);
        // A fragment inside a larger comment/string must not count as a separate token.
        for (SyntaxToken token : SyntaxHighlighter.highlight(language, text)) {
            if (text.substring(token.getStart(), token.getEnd()).equals(value) && token.getKind() == kind) { return; }
        }
        throw new AssertionError(language + " missing " + kind + " token: " + value);
    }

    private static void tokenAt(SyntaxLanguage language, String text, int start, int end, SyntaxToken.Kind kind) {
        for (SyntaxToken token : SyntaxHighlighter.highlight(language, text)) {
            if (token.getStart() == start && token.getEnd() == end && token.getKind() == kind) { return; }
        }
        throw new AssertionError(language + " missing " + kind + " range " + start + ":" + end);
    }

    private static void ranges(SyntaxLanguage language, String text) {
        int end = 0;
        for (SyntaxToken token : SyntaxHighlighter.highlight(language, text)) {
            TestSupport.assertTrue(token.getStart() >= end && token.getEnd() > token.getStart()
                && token.getEnd() <= text.length(), "ordered non-overlapping ranges: " + language);
            end = token.getEnd();
        }
    }
}
