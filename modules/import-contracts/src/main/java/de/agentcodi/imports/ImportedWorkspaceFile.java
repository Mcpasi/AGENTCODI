package de.agentcodi.imports;

import java.util.Objects;

/**
 * Immutable, URI-free metadata for a document already materialized below
 * {@code workspace/imports/}.
 */
public final class ImportedWorkspaceFile {
    private final String relativePath;
    private final String displayName;
    private final String mediaType;
    private final long byteCount;
    private final String sha256;

    private ImportedWorkspaceFile(
        String relativePath,
        String displayName,
        String mediaType,
        long byteCount,
        String sha256
    ) {
        this.relativePath = relativePath;
        this.displayName = displayName;
        this.mediaType = mediaType;
        this.byteCount = byteCount;
        this.sha256 = sha256;
    }

    public static ImportedWorkspaceFile create(
        String relativePath,
        String displayName,
        String mediaType,
        long byteCount,
        String sha256
    ) {
        requireRelativeImportPath(relativePath);
        requireDisplayName(displayName);
        requireMediaType(mediaType);
        if (byteCount < 0L || byteCount > WorkspaceImportLimits.MAXIMUM_FILE_BYTES) {
            throw new IllegalArgumentException("Imported document size is outside its limit");
        }
        requireSha256(sha256);
        return new ImportedWorkspaceFile(
            relativePath,
            displayName,
            mediaType,
            byteCount,
            sha256
        );
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getByteCount() {
        return byteCount;
    }

    public String getSha256() {
        return sha256;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ImportedWorkspaceFile)) {
            return false;
        }
        ImportedWorkspaceFile value = (ImportedWorkspaceFile) other;
        return byteCount == value.byteCount
            && relativePath.equals(value.relativePath)
            && displayName.equals(value.displayName)
            && mediaType.equals(value.mediaType)
            && sha256.equals(value.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            relativePath,
            displayName,
            mediaType,
            Long.valueOf(byteCount),
            sha256
        );
    }

    private static void requireRelativeImportPath(String relativePath) {
        String prefix = WorkspaceImportLimits.IMPORT_DIRECTORY_NAME + "/";
        if (relativePath == null
            || relativePath.length() <= prefix.length()
            || relativePath.length()
                > WorkspaceImportLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS
            || !relativePath.startsWith(prefix)) {
            throw new IllegalArgumentException("Imported document path is outside imports");
        }
        String fileName = relativePath.substring(prefix.length());
        if (".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("Imported document path is unsafe");
        }
        for (int index = 0; index < fileName.length(); index++) {
            char character = fileName.charAt(index);
            if (character < 0x20 || character == 0x7f
                || character == '/' || character == '\\' || character == ':') {
                throw new IllegalArgumentException("Imported document path is unsafe");
            }
        }
    }

    private static void requireDisplayName(String displayName) {
        if (displayName == null || displayName.isEmpty()
            || displayName.length()
                > WorkspaceImportLimits.MAXIMUM_DISPLAY_NAME_CHARACTERS) {
            throw new IllegalArgumentException("Imported document name is outside its limit");
        }
        for (int index = 0; index < displayName.length(); index++) {
            char character = displayName.charAt(index);
            if (character < 0x20 || character == 0x7f
                || character == '/' || character == '\\') {
                throw new IllegalArgumentException("Imported document name is unsafe");
            }
        }
    }

    private static void requireMediaType(String mediaType) {
        if (mediaType == null || mediaType.isEmpty()
            || mediaType.length() > WorkspaceImportLimits.MAXIMUM_MEDIA_TYPE_CHARACTERS) {
            throw new IllegalArgumentException("Imported document media type is invalid");
        }
        int slash = mediaType.indexOf('/');
        if (slash <= 0 || slash != mediaType.lastIndexOf('/')
            || slash == mediaType.length() - 1) {
            throw new IllegalArgumentException("Imported document media type is invalid");
        }
        for (int index = 0; index < mediaType.length(); index++) {
            char character = mediaType.charAt(index);
            if (character == '/') {
                continue;
            }
            if (!isMediaTypeTokenCharacter(character)) {
                throw new IllegalArgumentException("Imported document media type is invalid");
            }
        }
    }

    private static void requireSha256(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            throw new IllegalArgumentException("Imported document digest is invalid");
        }
        for (int index = 0; index < sha256.length(); index++) {
            char character = sha256.charAt(index);
            if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'f')) {
                throw new IllegalArgumentException("Imported document digest is invalid");
            }
        }
    }

    private static boolean isMediaTypeTokenCharacter(char character) {
        return character >= 'a' && character <= 'z'
            || character >= 'A' && character <= 'Z'
            || character >= '0' && character <= '9'
            || character == '!' || character == '#' || character == '$'
            || character == '&' || character == '^' || character == '_'
            || character == '.' || character == '+' || character == '-';
    }
}
