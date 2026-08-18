package de.agentcodi.tests;

import de.agentcodi.core.BuildIdentity;

public final class BuildIdentityTest {
    private BuildIdentityTest() {
    }

    public static int run() {
        pinsCompleteCodeModeRuntime();
        return 1;
    }

    private static void pinsCompleteCodeModeRuntime() {
        TestSupport.assertEquals("0.4.16", BuildIdentity.VERSION_NAME, "app version");
        TestSupport.assertEquals(36, BuildIdentity.VERSION_CODE, "app version code");
        TestSupport.assertEquals(
            "0.147.2",
            BuildIdentity.CODEX_RUNTIME_VERSION,
            "Codex runtime version"
        );
        TestSupport.assertEquals(
            "libcodex.so",
            BuildIdentity.CODEX_RUNTIME_LIBRARY,
            "Codex runtime library"
        );
        TestSupport.assertEquals(
            "libcodex-codehost.so",
            BuildIdentity.CODEX_CODE_MODE_HOST_LIBRARY,
            "Codex code-mode host library"
        );
        TestSupport.assertEquals(
            "libagentcodi-shell.so",
            BuildIdentity.TERMINAL_SHELL_LIBRARY,
            "terminal shell library"
        );
        TestSupport.assertEquals(
            "24.18.0",
            BuildIdentity.NODE_RUNTIME_VERSION,
            "Node.js runtime version"
        );
        TestSupport.assertEquals(
            "libnode.so",
            BuildIdentity.NODE_RUNTIME_LIBRARY,
            "Node.js runtime library"
        );
        TestSupport.assertEquals(
            "11.19.0",
            BuildIdentity.NPM_RUNTIME_VERSION,
            "npm runtime version"
        );
        TestSupport.assertEquals(
            "3.14.6",
            BuildIdentity.PYTHON_RUNTIME_VERSION,
            "Python runtime version"
        );
        TestSupport.assertEquals(
            "libpython-bin.so",
            BuildIdentity.PYTHON_RUNTIME_LIBRARY,
            "Python runtime library"
        );
    }
}
