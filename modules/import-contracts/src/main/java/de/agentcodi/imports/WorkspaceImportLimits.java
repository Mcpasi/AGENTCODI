package de.agentcodi.imports;

public final class WorkspaceImportLimits {
    public static final String IMPORT_DIRECTORY_NAME = "imports";
    public static final int MAXIMUM_FILES_PER_MESSAGE = 16;
    public static final int MAXIMUM_DISPLAY_NAME_CHARACTERS = 160;
    public static final int MAXIMUM_RELATIVE_PATH_CHARACTERS = 256;
    public static final int MAXIMUM_MEDIA_TYPE_CHARACTERS = 127;
    public static final long MAXIMUM_FILE_BYTES = 512L * 1024L * 1024L;
    public static final long MAXIMUM_TOTAL_BYTES = 1024L * 1024L * 1024L;

    private WorkspaceImportLimits() {
    }
}
