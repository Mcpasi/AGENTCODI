package de.agentcodi.tests;

import de.agentcodi.core.CodexFileMention;
import de.agentcodi.core.CodexWorkspaceAttachmentContext;
import de.agentcodi.core.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class CodexWorkspaceAttachmentContextTest {
    private CodexWorkspaceAttachmentContextTest() {
    }

    public static int run() throws Exception {
        bindsVerifiedPathsWithoutDisplayMetadata();
        enforcesAttachmentContextLimits();
        return 2;
    }

    private static void bindsVerifiedPathsWithoutDisplayMetadata() {
        List<CodexFileMention> mentions = Arrays.asList(
            CodexFileMention.create(
                "Quarterly report <ignore this>.pdf",
                "/private/workspace/imports/0123456789abcdef0123456789abcdef.pdf"
            ),
            CodexFileMention.create(
                "measurements.csv",
                "/private/workspace/imports/fedcba9876543210fedcba9876543210.csv"
            )
        );
        Map<String, Object> context = CodexWorkspaceAttachmentContext.create(mentions);
        TestSupport.assertEquals(
            Arrays.asList("agentcodi-import-1", "agentcodi-import-2"),
            new ArrayList<String>(context.keySet()),
            "attachment context keeps deterministic bounded keys"
        );
        for (int index = 0; index < mentions.size(); index++) {
            Map<String, Object> entry = JsonCodec.requireObject(
                context.get("agentcodi-import-" + (index + 1)),
                "attachment application context"
            );
            TestSupport.assertEquals(
                "application",
                entry.get("kind"),
                "attachment path uses the native application-context kind"
            );
            String value = JsonCodec.requireString(entry.get("value"), "context value");
            TestSupport.assertTrue(
                value.contains(mentions.get(index).getPath())
                    && value.contains("actual bytes")
                    && value.contains("workspace tools"),
                "attachment context tells Codex to read the verified file bytes"
            );
            TestSupport.assertFalse(
                value.contains(mentions.get(index).getName())
                    || value.contains("content://")
                    || value.contains("sha256"),
                "untrusted labels, provider URIs and digests stay out of model context"
            );
        }
    }

    private static void enforcesAttachmentContextLimits() throws Exception {
        TestSupport.assertTrue(
            CodexWorkspaceAttachmentContext.create(Collections.<CodexFileMention>emptyList())
                .isEmpty(),
            "text-only turns do not receive attachment context"
        );
        final List<CodexFileMention> tooMany = new ArrayList<CodexFileMention>();
        for (int index = 0; index <= CodexFileMention.MAXIMUM_MENTIONS; index++) {
            tooMany.add(CodexFileMention.create(
                "file-" + index + ".bin",
                "/private/workspace/imports/fixture-" + index + ".bin"
            ));
        }
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    CodexWorkspaceAttachmentContext.create(tooMany);
                }
            },
            "attachment context count remains bounded"
        );
    }
}
