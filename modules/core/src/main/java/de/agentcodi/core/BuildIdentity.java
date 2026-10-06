package de.agentcodi.core;

public final class BuildIdentity {
    public static final String APP_NAME = "AGENTCODI Package";
    public static final String APPLICATION_ID = "de.agentcodi.pkg";
    public static final String VERSION_NAME = "0.1.0-package.3";
    public static final int VERSION_CODE = 3;
    public static final String CODEX_RUNTIME_VERSION = "0.156.1-termux.1";
    public static final String CODEX_RUNTIME_LIBRARY = "libcodex.so";
    public static final String CODEX_CODE_MODE_HOST_LIBRARY = "libcodex-codehost.so";
    public static final String TERMINAL_SHELL_LIBRARY = "libagentcodi-shell.so";
    public static final int MIN_SDK = 29;
    public static final int TARGET_SDK = 28;

    private BuildIdentity() {
    }

    public static String summary() {
        return APP_NAME + " " + VERSION_NAME + " (" + APPLICATION_ID + ")";
    }
}
