package de.agentcodi.app;

import android.content.Context;

/** Shared display formatting for chat attachment and image sizes. */
final class ChatUiFormatting {
    private ChatUiFormatting() {
    }

    static String readableByteCount(Context context, long bytes) {
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + " MiB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KiB";
        }
        return context.getString(R.string.byte_count_bytes, Long.valueOf(bytes));
    }

}
