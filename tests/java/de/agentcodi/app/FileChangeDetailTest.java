package de.agentcodi.app;

import de.agentcodi.tests.TestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class FileChangeDetailTest {
    private FileChangeDetailTest() {
    }

    public static int run() throws IOException {
        splitsEveryReportedFileIntoItsOwnSection();
        keepsRenamedTargetsWithTheirChange();
        countsOnlyRealDiffLines();
        keepsUnrelatedSectionsVerbatim();
        keepsBlankAndHeadingLikeDiffLinesInsideTheirChange();
        keepsDetailsWithoutFileSectionsUnchanged();
        boundsTheNumberOfRenderedChangesWithoutLosingContent();
        projectsStructuredChangesForApprovals();
        classifiesDiffLines();
        splitsPathsIntoDirectoryAndFileName();
        readsTheDetailFormatTheCardBuilderWrites();
        rendersEveryChangeThroughTheSharedCard();
        reportsMissingDiffsAsEmptySoTheViewCanLocaliseThem();
        return 13;
    }

    private static void splitsEveryReportedFileIntoItsOwnSection() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "HINZUFÜGEN · /workspace/demo/app.js:\n"
                + "+const value = 1;\n"
                + "\n"
                + "LÖSCHEN · /workspace/demo/old.js:\n"
                + "-const old = 0;"
        );
        List<FileChangeDetail.FileChange> changes = projection.getChanges();
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(changes.size()),
            "every reported file becomes its own change"
        );
        TestSupport.assertEquals(
            FileChangeDetail.Kind.ADD,
            changes.get(0).getKind(),
            "added file kind"
        );
        TestSupport.assertEquals(
            "/workspace/demo/app.js",
            changes.get(0).getPath(),
            "added file path"
        );
        TestSupport.assertEquals(
            "+const value = 1;",
            changes.get(0).getDiff(),
            "added file diff keeps its lines"
        );
        TestSupport.assertEquals(
            FileChangeDetail.Kind.DELETE,
            changes.get(1).getKind(),
            "deleted file kind"
        );
        TestSupport.assertEquals(
            "-const old = 0;",
            changes.get(1).getDiff(),
            "deleted file diff keeps its lines"
        );
        TestSupport.assertEquals("", projection.getRemainder(), "no leftover text");
    }

    private static void keepsRenamedTargetsWithTheirChange() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "ÄNDERN · /workspace/a.txt → /workspace/b.txt:\n"
                + "@@ -1 +1 @@\n"
                + "-old\n"
                + "+new"
        );
        FileChangeDetail.FileChange change = projection.getChanges().get(0);
        TestSupport.assertEquals(
            FileChangeDetail.Kind.UPDATE,
            change.getKind(),
            "updated file kind"
        );
        TestSupport.assertEquals("/workspace/a.txt", change.getPath(), "source path");
        TestSupport.assertEquals("/workspace/b.txt", change.getMovePath(), "move target");
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(change.getAddedLines()),
            "renamed file added lines"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(change.getRemovedLines()),
            "renamed file removed lines"
        );
    }

    private static void countsOnlyRealDiffLines() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "ÄNDERN · /workspace/app.js:\n"
                + "--- a/app.js\n"
                + "+++ b/app.js\n"
                + "@@ -1,3 +1,4 @@\n"
                + " context\n"
                + "-removed\n"
                + "+added one\n"
                + "+added two\n"
                + "\\ No newline at end of file"
        );
        TestSupport.assertEquals(
            Integer.valueOf(2),
            Integer.valueOf(projection.getAddedLines()),
            "file headers never count as added lines"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(projection.getRemovedLines()),
            "file headers never count as removed lines"
        );
    }

    private static void keepsUnrelatedSectionsVerbatim() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "HINZUFÜGEN · /workspace/app.js:\n"
                + "+value\n"
                + "\n"
                + "Ausgabe:\n"
                + "patch applied"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(projection.getChanges().size()),
            "the file section is projected"
        );
        TestSupport.assertEquals(
            "Ausgabe:\npatch applied",
            projection.getRemainder(),
            "reported output survives next to the changes"
        );
    }

    private static void keepsBlankAndHeadingLikeDiffLinesInsideTheirChange() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "ÄNDERN · /workspace/notes.txt:\n"
                + " first\n"
                + "\n"
                + "+HINZUFÜGEN · not a heading\n"
                + "+ÄNDERN · still diff content"
        );
        TestSupport.assertEquals(
            Integer.valueOf(1),
            Integer.valueOf(projection.getChanges().size()),
            "diff content never starts a second change"
        );
        TestSupport.assertEquals(
            " first\n\n+HINZUFÜGEN · not a heading\n+ÄNDERN · still diff content",
            projection.getChanges().get(0).getDiff(),
            "blank and heading-like diff lines stay in the diff"
        );
    }

    private static void keepsDetailsWithoutFileSectionsUnchanged() {
        String raw = "Ausgabe:\nkeine Änderungen\n\nErgebnis:\nok";
        FileChangeDetail.Projection projection = FileChangeDetail.parse(raw);
        TestSupport.assertFalse(projection.hasChanges(), "no file section is invented");
        TestSupport.assertEquals(raw, projection.getRemainder(), "the detail stays untouched");
        TestSupport.assertFalse(
            FileChangeDetail.parse("").hasChanges(),
            "an empty detail has no changes"
        );
        TestSupport.assertEquals(
            "",
            FileChangeDetail.parse(null).getRemainder(),
            "a missing detail stays empty"
        );
    }

    private static void boundsTheNumberOfRenderedChangesWithoutLosingContent() {
        StringBuilder raw = new StringBuilder();
        for (int index = 0; index < 30; index++) {
            if (index != 0) {
                raw.append("\n\n");
            }
            raw.append("HINZUFÜGEN · /workspace/file").append(index).append(".txt:\n+value");
        }
        FileChangeDetail.Projection projection = FileChangeDetail.parse(raw.toString());
        TestSupport.assertEquals(
            Integer.valueOf(24),
            Integer.valueOf(projection.getChanges().size()),
            "rendered changes stay bounded"
        );
        TestSupport.assertContains(
            projection.getRemainder(),
            "/workspace/file29.txt",
            "changes beyond the bound remain readable as text"
        );
    }

    private static void projectsStructuredChangesForApprovals() {
        TestSupport.assertEquals(
            FileChangeDetail.Kind.ADD,
            FileChangeDetail.of("add", "/workspace/a", "", "+a").getKind(),
            "approval add kind"
        );
        TestSupport.assertEquals(
            FileChangeDetail.Kind.DELETE,
            FileChangeDetail.of("delete", "/workspace/a", "", "-a").getKind(),
            "approval delete kind"
        );
        FileChangeDetail.FileChange unknown = FileChangeDetail.of(null, null, null, null);
        TestSupport.assertEquals(
            FileChangeDetail.Kind.UPDATE,
            unknown.getKind(),
            "an unreported kind is shown as a change, never as a new file"
        );
        TestSupport.assertEquals("", unknown.getDiff(), "a missing diff stays empty");
    }

    private static void classifiesDiffLines() {
        TestSupport.assertEquals(
            FileChangeDetail.LineKind.ADDED,
            FileChangeDetail.lineKind("+value"),
            "added line"
        );
        TestSupport.assertEquals(
            FileChangeDetail.LineKind.REMOVED,
            FileChangeDetail.lineKind("-value"),
            "removed line"
        );
        TestSupport.assertEquals(
            FileChangeDetail.LineKind.HUNK,
            FileChangeDetail.lineKind("@@ -1 +1 @@"),
            "hunk header"
        );
        TestSupport.assertEquals(
            FileChangeDetail.LineKind.META,
            FileChangeDetail.lineKind("+++ b/app.js"),
            "file header"
        );
        TestSupport.assertEquals(
            FileChangeDetail.LineKind.CONTEXT,
            FileChangeDetail.lineKind(" value"),
            "context line"
        );
        TestSupport.assertEquals(
            FileChangeDetail.LineKind.CONTEXT,
            FileChangeDetail.lineKind(""),
            "empty line"
        );
    }

    /**
     * Mirrors what {@code CodexSessionController.formatFileChanges} actually emits: every
     * heading, path included, is closed with a colon before its body follows.
     */
    private static void readsTheDetailFormatTheCardBuilderWrites() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "HINZUFÜGEN · /private/workspace/neu.txt:\n"
                + "+eins\n"
                + "+zwei\n"
                + "\n"
                + "LÖSCHEN · /private/workspace/leer.txt:\n"
                + "Kein Text-Diff vorhanden.\n"
                + "\n"
                + "ÄNDERN · /private/workspace/alt.txt → /private/workspace/neu2.txt:\n"
                + "@@ -1 +1 @@\n"
                + "-alt\n"
                + "+neu"
        );
        List<FileChangeDetail.FileChange> changes = projection.getChanges();
        TestSupport.assertEquals(
            Integer.valueOf(3),
            Integer.valueOf(changes.size()),
            "every emitted section is projected"
        );
        TestSupport.assertEquals(
            "/private/workspace/neu.txt",
            changes.get(0).getPath(),
            "the heading terminator never becomes part of the path"
        );
        TestSupport.assertEquals(
            "neu.txt",
            changes.get(0).getFileName(),
            "the displayed file name stays clean"
        );
        TestSupport.assertEquals(
            "/private/workspace/leer.txt",
            changes.get(1).getPath(),
            "a change without a diff keeps a clean path"
        );
        TestSupport.assertEquals(
            "/private/workspace/alt.txt",
            changes.get(2).getPath(),
            "a moved file keeps a clean source path"
        );
        TestSupport.assertEquals(
            "/private/workspace/neu2.txt",
            changes.get(2).getMovePath(),
            "the heading terminator never becomes part of the move target"
        );
        TestSupport.assertEquals(
            "@@ -1 +1 @@\n-alt\n+neu",
            changes.get(2).getDiff(),
            "the diff keeps every reported line"
        );
    }

    private static void reportsMissingDiffsAsEmptySoTheViewCanLocaliseThem() {
        FileChangeDetail.Projection projection = FileChangeDetail.parse(
            "LÖSCHEN · /workspace/binary.png:\nKein Text-Diff vorhanden."
        );
        FileChangeDetail.FileChange change = projection.getChanges().get(0);
        TestSupport.assertEquals(
            "/workspace/binary.png",
            change.getPath(),
            "a change without a text diff keeps its path"
        );
        TestSupport.assertEquals(
            "",
            change.getDiff(),
            "the untranslated placeholder never reaches the diff view"
        );
        TestSupport.assertEquals(
            Integer.valueOf(0),
            Integer.valueOf(projection.getAddedLines()),
            "a missing diff counts no lines"
        );
    }

    private static void rendersEveryChangeThroughTheSharedCard() throws IOException {
        String card = read("app/src/main/java/de/agentcodi/app/TranscriptCardView.java");
        TestSupport.assertContains(
            card,
            "FileChangeDetail.parse(",
            "the transcript projects reported changes before rendering them"
        );
        TestSupport.assertContains(
            card,
            "new FileChangeView(getContext(), theme)",
            "the transcript renders every change as its own card"
        );

        String dialog = read("app/src/main/java/de/agentcodi/app/InteractiveRequestDialog.java");
        TestSupport.assertContains(
            dialog,
            "new FileChangeView(activity, theme)",
            "approvals show the same change cards as the transcript"
        );
        TestSupport.assertContains(
            dialog,
            "FileChangeDetail.of(",
            "approvals project their reported changes through one contract"
        );

        String view = read("app/src/main/java/de/agentcodi/app/FileChangeView.java");
        TestSupport.assertContains(
            view,
            "FileChangeDetail.lineKind(",
            "diff lines are colored by their reported kind"
        );

        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        for (String resource : new String[] {
            "card_change_add",
            "card_change_delete",
            "card_change_update",
            "card_change_stats_description",
            "card_change_moved_to",
            "card_change_entry_description",
            "card_no_text_diff"
        }) {
            String marker = "name=\"" + resource + "\"";
            TestSupport.assertContains(english, marker, "English change label: " + resource);
            TestSupport.assertContains(german, marker, "German change label: " + resource);
        }
    }

    private static String read(String relativePath) throws IOException {
        String configured = System.getProperty("agentcodi.projectRoot", "");
        if (configured.isEmpty()) {
            throw new IllegalStateException("agentcodi.projectRoot is not configured");
        }
        Path root = Paths.get(configured).toAbsolutePath().normalize();
        return new String(Files.readAllBytes(root.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private static void splitsPathsIntoDirectoryAndFileName() {
        FileChangeDetail.FileChange change = FileChangeDetail.of(
            "update", "/workspace/demo/app.js", "", ""
        );
        TestSupport.assertEquals("app.js", change.getFileName(), "file name");
        TestSupport.assertEquals("/workspace/demo", change.getDirectory(), "directory");
        FileChangeDetail.FileChange bare = FileChangeDetail.of("update", "app.js", "", "");
        TestSupport.assertEquals("app.js", bare.getFileName(), "bare file name");
        TestSupport.assertEquals("", bare.getDirectory(), "bare file has no directory");
    }
}
