package de.agentcodi.browser;

public final class WorkspaceBrowserLimits {
    public static final int DEFAULT_DIRECTORY_PAGE_SIZE = 48;
    public static final int MAXIMUM_DIRECTORY_PAGE_SIZE = 96;
    public static final int MAXIMUM_SCANNED_DIRECTORY_ENTRIES = 65536;
    public static final int MAXIMUM_RELATIVE_PATH_CHARACTERS = 2048;
    public static final int MAXIMUM_DIRECTORY_DEPTH = 64;
    public static final long MAXIMUM_FILE_BYTES = 512L * 1024L * 1024L;
    public static final long MAXIMUM_IMAGE_PREVIEW_BYTES = 16L * 1024L * 1024L;
    public static final int TEXT_PROBE_BYTES = 4096;
    public static final int TEXT_PAGE_BYTES = 32 * 1024;
    public static final int BINARY_PAGE_BYTES = 2 * 1024;
    public static final int MAXIMUM_RENDERED_PREVIEW_CHARACTERS = 64 * 1024;
    public static final int MAXIMUM_PREVIEW_MIME_CHARACTERS = 128;
    public static final long MAXIMUM_DECODED_IMAGE_PIXELS = 16L * 1024L * 1024L;
    public static final int MAXIMUM_DECODED_IMAGE_EDGE = 16384;
    public static final int TARGET_IMAGE_EDGE = 2048;

    private WorkspaceBrowserLimits() {
    }
}
