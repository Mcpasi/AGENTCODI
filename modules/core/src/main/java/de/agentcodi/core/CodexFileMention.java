package de.agentcodi.core;

/**
 * Bounded metadata for a verified imported workspace file. The native
 * {@code mention} preserves its visible user-history representation, while
 * {@link CodexWorkspaceAttachmentContext} gives the model the exact readable
 * workspace path. Filesystem materialization and verification remain the
 * responsibility of the import and runtime modules.
 */
public final class CodexFileMention {
    public static final int MAXIMUM_MENTIONS = 16;
    public static final int MAXIMUM_NAME_CHARACTERS = 160;
    public static final int MAXIMUM_PATH_CHARACTERS = 4096;

    private final String name;
    private final String path;

    private CodexFileMention(String name, String path) {
        this.name = name;
        this.path = path;
    }

    public static CodexFileMention create(String name, String path) {
        requireSafeName(name);
        requireSafeAbsolutePath(path);
        return new CodexFileMention(name, path);
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    private static void requireSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAXIMUM_NAME_CHARACTERS) {
            throw new IllegalArgumentException("Codex file mention name is outside its limit");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character < 0x20 || character == 0x7f
                || character == '/' || character == '\\') {
                throw new IllegalArgumentException("Codex file mention name is unsafe");
            }
        }
        if (CredentialGuard.containsLikelyCredential(name)
            || CredentialGuard.isLikelyCredentialFileName(name)) {
            throw new IllegalArgumentException(
                "Credential-shaped document names cannot enter a Codex turn"
            );
        }
    }

    private static void requireSafeAbsolutePath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAXIMUM_PATH_CHARACTERS
            || path.charAt(0) != '/') {
            throw new IllegalArgumentException("Codex file mention path is invalid");
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character < 0x20 || character == 0x7f || character == '\\') {
                throw new IllegalArgumentException("Codex file mention path is unsafe");
            }
        }
    }
}
