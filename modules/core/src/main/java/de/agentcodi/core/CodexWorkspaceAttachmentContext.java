package de.agentcodi.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the bounded app-server application context that binds visible file
 * mentions to their verified private workspace copies. The app-server keeps a
 * {@code mention} in user history but does not forward a filesystem mention to
 * the model, so the native {@code additionalContext} contract carries the
 * exact readable path without duplicating file bytes or exposing import
 * digests.
 */
public final class CodexWorkspaceAttachmentContext {
    public static final String CONTEXT_KEY_PREFIX = "agentcodi-import-";
    public static final String CONTEXT_KIND = "application";
    public static final int MAXIMUM_VALUE_CHARACTERS =
        CodexFileMention.MAXIMUM_PATH_CHARACTERS + 320;

    private static final String PATH_PREFIX =
        "The current user turn includes an imported regular file at this "
            + "canonical private workspace path:\n";
    private static final String READ_REQUIREMENT =
        "\nRead the file's actual bytes with the workspace tools before "
            + "answering. Do not infer its contents from the visible attachment label.";

    private CodexWorkspaceAttachmentContext() {
    }

    public static Map<String, Object> create(List<CodexFileMention> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return Collections.emptyMap();
        }
        if (mentions.size() > CodexFileMention.MAXIMUM_MENTIONS) {
            throw new IllegalArgumentException("Too many workspace attachment contexts");
        }
        Map<String, Object> context = new LinkedHashMap<String, Object>();
        for (int index = 0; index < mentions.size(); index++) {
            CodexFileMention mention = mentions.get(index);
            if (mention == null) {
                throw new IllegalArgumentException("Workspace attachment context is missing");
            }
            String value = PATH_PREFIX + mention.getPath() + READ_REQUIREMENT;
            if (value.length() > MAXIMUM_VALUE_CHARACTERS) {
                throw new IllegalArgumentException(
                    "Workspace attachment context exceeds its bound"
                );
            }
            context.put(
                CONTEXT_KEY_PREFIX + (index + 1),
                Collections.unmodifiableMap(JsonCodec.object(
                    "kind", CONTEXT_KIND,
                    "value", value
                ))
            );
        }
        return Collections.unmodifiableMap(context);
    }
}
