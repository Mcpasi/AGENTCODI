package de.agentcodi.browser;

public final class WorkspaceFilePreview {
    public enum Kind {
        TEXT,
        IMAGE,
        BINARY
    }

    private final String displayName;
    private final String relativePath;
    private final String mimeType;
    private final Kind kind;
    private final long byteCount;
    private final long byteOffset;
    private final int pageIndex;
    private final int pageCount;
    private final String renderedContent;
    private final byte[] imageBytes;

    private WorkspaceFilePreview(
        String displayName,
        String relativePath,
        String mimeType,
        Kind kind,
        long byteCount,
        long byteOffset,
        int pageIndex,
        int pageCount,
        String renderedContent,
        byte[] imageBytes
    ) {
        if (displayName == null || displayName.trim().isEmpty()
            || displayName.length() > 255
            || !safeRelativePath(relativePath)
            || !safeMimeType(mimeType)
            || kind == null || byteCount < 0L
            || byteCount > WorkspaceBrowserLimits.MAXIMUM_FILE_BYTES
            || byteOffset < 0L || byteOffset > byteCount
            || pageIndex < 0 || pageCount <= 0 || pageIndex >= pageCount
            || renderedContent == null
            || renderedContent.length()
                > WorkspaceBrowserLimits.MAXIMUM_RENDERED_PREVIEW_CHARACTERS
            || imageBytes == null) {
            throw new IllegalArgumentException("Workspace preview is invalid");
        }
        if (kind == Kind.IMAGE) {
            if (imageBytes.length == 0
                || imageBytes.length > WorkspaceBrowserLimits.MAXIMUM_IMAGE_PREVIEW_BYTES
                || byteCount != imageBytes.length || byteOffset != 0L
                || pageIndex != 0 || pageCount != 1 || !renderedContent.isEmpty()) {
                throw new IllegalArgumentException("Image preview shape is invalid");
            }
        } else {
            int pageBytes = kind == Kind.TEXT
                ? WorkspaceBrowserLimits.TEXT_PAGE_BYTES
                : WorkspaceBrowserLimits.BINARY_PAGE_BYTES;
            int expectedPageCount = byteCount == 0L
                ? 1
                : (int) (1L + (byteCount - 1L) / pageBytes);
            long expectedOffset = (long) pageIndex * (long) pageBytes;
            if (imageBytes.length != 0 || pageCount != expectedPageCount
                || byteOffset != expectedOffset) {
                throw new IllegalArgumentException("Paged preview shape is invalid");
            }
        }
        this.displayName = displayName;
        this.relativePath = relativePath;
        this.mimeType = mimeType;
        this.kind = kind;
        this.byteCount = byteCount;
        this.byteOffset = byteOffset;
        this.pageIndex = pageIndex;
        this.pageCount = pageCount;
        this.renderedContent = renderedContent;
        this.imageBytes = imageBytes.clone();
    }

    public static WorkspaceFilePreview text(
        String displayName,
        String relativePath,
        long byteCount,
        long byteOffset,
        int pageIndex,
        int pageCount,
        String content
    ) {
        return new WorkspaceFilePreview(
            displayName,
            relativePath,
            "text/plain; charset=utf-8",
            Kind.TEXT,
            byteCount,
            byteOffset,
            pageIndex,
            pageCount,
            content,
            new byte[0]
        );
    }

    public static WorkspaceFilePreview binary(
        String displayName,
        String relativePath,
        String mimeType,
        long byteCount,
        long byteOffset,
        int pageIndex,
        int pageCount,
        String content
    ) {
        return new WorkspaceFilePreview(
            displayName,
            relativePath,
            mimeType,
            Kind.BINARY,
            byteCount,
            byteOffset,
            pageIndex,
            pageCount,
            content,
            new byte[0]
        );
    }

    public static WorkspaceFilePreview image(
        String displayName,
        String relativePath,
        String mimeType,
        long byteCount,
        byte[] imageBytes
    ) {
        return new WorkspaceFilePreview(
            displayName,
            relativePath,
            mimeType,
            Kind.IMAGE,
            byteCount,
            0L,
            0,
            1,
            "",
            imageBytes
        );
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Kind getKind() {
        return kind;
    }

    public long getByteCount() {
        return byteCount;
    }

    public long getByteOffset() {
        return byteOffset;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getPageCount() {
        return pageCount;
    }

    public String getRenderedContent() {
        return renderedContent;
    }

    public byte[] getImageBytes() {
        return imageBytes.clone();
    }

    public boolean hasPreviousPage() {
        return pageIndex > 0;
    }

    public boolean hasNextPage() {
        return pageIndex + 1 < pageCount;
    }

    private static boolean safeRelativePath(String value) {
        if (value == null || value.isEmpty()
            || value.length() > WorkspaceBrowserLimits.MAXIMUM_RELATIVE_PATH_CHARACTERS
            || value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
            return false;
        }
        String[] components = value.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                || component.indexOf('\\') >= 0 || component.indexOf(':') >= 0) {
                return false;
            }
            for (int index = 0; index < component.length(); index++) {
                char character = component.charAt(index);
                if (character < 0x20 || character == 0x7f) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean safeMimeType(String value) {
        if (value == null || value.trim().isEmpty()
            || value.length() > WorkspaceBrowserLimits.MAXIMUM_PREVIEW_MIME_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return false;
            }
        }
        return true;
    }
}
