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
        TestSupport.assertEquals("AGENTCODI Package", BuildIdentity.APP_NAME, "edition name");
        TestSupport.assertEquals("de.agentcodi.pkg", BuildIdentity.APPLICATION_ID, "separate installation ID");
        TestSupport.assertEquals("0.1.0-package.3", BuildIdentity.VERSION_NAME, "app version");
        TestSupport.assertEquals(3, BuildIdentity.VERSION_CODE, "app version code");
        TestSupport.assertEquals(28, BuildIdentity.TARGET_SDK, "package edition target SDK");
        TestSupport.assertEquals(29, BuildIdentity.MIN_SDK, "minimum Android version");
        TestSupport.assertEquals(
            "0.156.1-termux.1",
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
    }
}
