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
        TestSupport.assertEquals("0.4.0", BuildIdentity.VERSION_NAME, "app version");
        TestSupport.assertEquals(20, BuildIdentity.VERSION_CODE, "app version code");
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
    }
}
