package de.agentcodi.core;

public final class BuildIdentity {
    public static final String APP_NAME = "AGENTCODI";
    public static final String APPLICATION_ID = "de.agentcodi.app";
    public static final String VERSION_NAME = "0.4.5";
    public static final int VERSION_CODE = 25;
    public static final String CODEX_RUNTIME_VERSION = "0.147.2";
    public static final String CODEX_RUNTIME_LIBRARY = "libcodex.so";
    public static final String CODEX_CODE_MODE_HOST_LIBRARY = "libcodex-codehost.so";
    public static final int MIN_SDK = 29;
    public static final int TARGET_SDK = 35;

    private BuildIdentity() {
    }

    public static String summary() {
        return APP_NAME + " " + VERSION_NAME + " (" + APPLICATION_ID + ")";
    }
}
