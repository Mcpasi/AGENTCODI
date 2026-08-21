package de.agentcodi.core;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * One-shot ownership boundary between verified workspace imports and a Codex
 * request.
 *
 * <p>The producer must retain every file handle used for the full content
 * verification, revalidate the complete handle/path batch immediately before
 * invoking the sender, and keep those handles open until the sender returns.
 * The sender is invoked synchronously at most once. Closing the transaction
 * without invoking it cancels the prepared attachment batch.</p>
 */
public interface CodexFileMentionTransaction extends Closeable {
    int getFileCount();

    void withVerifiedMentions(VerifiedSender sender) throws Exception;

    @Override
    void close() throws IOException;

    interface VerifiedSender {
        void send(
            List<CodexFileMention> mentions,
            SendGuard sendGuard
        ) throws Exception;
    }

    /** Revalidates the retained batch inside the RPC transport write lock. */
    interface SendGuard {
        void verifyUnchanged() throws IOException;
    }
}
