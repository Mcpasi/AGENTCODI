package de.agentcodi.core;

public final class BuildIdentity {
    public static final String APP_NAME = "AGENTCODI";
    public static final String APPLICATION_ID = "de.agentcodi.app";
    public static final String VERSION_NAME = "0.6.8";
    public static final int VERSION_CODE = 75;
    public static final String CODEX_RUNTIME_VERSION = "0.148.1";
    public static final String CODEX_RUNTIME_LIBRARY = "libcodex.so";
    public static final String CODEX_CODE_MODE_HOST_LIBRARY = "libcodex-codehost.so";
    public static final String TERMINAL_SHELL_LIBRARY = "libagentcodi-shell.so";
    public static final String NODE_RUNTIME_VERSION = "24.18.0";
    public static final String NODE_RUNTIME_LIBRARY = "libnode.so";
    public static final String NPM_RUNTIME_VERSION = "11.19.0";
    public static final String PYTHON_RUNTIME_VERSION = "3.14.6";
    public static final String PYTHON_RUNTIME_LIBRARY = "libpython-bin.so";
    public static final String RIPGREP_RUNTIME_VERSION = "15.2.0";
    public static final String RIPGREP_RUNTIME_LIBRARY = "libripgrep.so";
    public static final String TOOL_RUNTIME_NAME = "python-3.14.6-npm-11.19.0";
    public static final String TOOL_RUNTIME_ARCHIVE_ASSET =
        "third-party/toolchain/RUNTIME.zip";
    public static final String TOOL_RUNTIME_MANIFEST_ASSET =
        "third-party/toolchain/RUNTIME-MANIFEST";
    public static final int MIN_SDK = 29;
    public static final int TARGET_SDK = 35;

    private BuildIdentity() {
    }

    public static String summary() {
        return APP_NAME + " " + VERSION_NAME + " (" + APPLICATION_ID + ")";
    }
}
