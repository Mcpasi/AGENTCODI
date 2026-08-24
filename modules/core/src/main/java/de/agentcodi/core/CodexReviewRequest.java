package de.agentcodi.core;

/**
 * Immutable, Android-free request contract for the only review target exposed by
 * AGENTCODI: an inline custom-instructions review on an existing thread.
 */
public final class CodexReviewRequest {
    public static final int MAXIMUM_INSTRUCTIONS_CHARACTERS = 32 * 1024;
    public static final String DELIVERY_INLINE = "inline";
    public static final String TARGET_CUSTOM = "custom";

    private final String threadId;
    private final String instructions;

    public CodexReviewRequest(String threadId, String instructions) {
        if (!isSafeIdentifier(threadId)) {
            throw new IllegalArgumentException("Review thread id is invalid");
        }
        String normalized = instructions == null ? "" : instructions.trim();
        if (normalized.isEmpty()
            || normalized.length() > MAXIMUM_INSTRUCTIONS_CHARACTERS
            || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Review instructions are outside the limit");
        }
        this.threadId = threadId;
        this.instructions = normalized;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getInstructions() {
        return instructions;
    }

    public String getDelivery() {
        return DELIVERY_INLINE;
    }

    public String getTargetType() {
        return TARGET_CUSTOM;
    }

    static boolean isSafeIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 160) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_' && character != '.'
                && character != ':') {
                return false;
            }
        }
        return true;
    }
}
