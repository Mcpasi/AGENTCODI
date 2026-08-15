package de.agentcodi.tests;

import de.agentcodi.core.ToolchainCommand;

public final class ToolchainCommandTest {
    private ToolchainCommandTest() {
    }

    public static int run() {
        recognizesNodeActivationRequests();
        rejectsUnrelatedOrEmbeddedText();
        rejectsArgumentsAndQuotedDescriptions();
        return 3;
    }

    private static void recognizesNodeActivationRequests() {
        TestSupport.assertTrue(
            ToolchainCommand.requestsNodeInstallation(
                "agentcodi-toolchain   install\tnode"
            ),
            "shell function activation"
        );
        TestSupport.assertTrue(
            ToolchainCommand.requestsNodeInstallation(
                "/private/native/libagentcodi-shell.so --toolchain install node"
                    + " && node --version"
            ),
            "direct shell bridge activation"
        );
    }

    private static void rejectsUnrelatedOrEmbeddedText() {
        TestSupport.assertFalse(
            ToolchainCommand.requestsNodeInstallation("node --version"),
            "ordinary Node command"
        );
        TestSupport.assertFalse(
            ToolchainCommand.requestsNodeInstallation(
                "echo xagentcodi-toolchain install node-suffix"
            ),
            "embedded activation text"
        );
    }

    private static void rejectsArgumentsAndQuotedDescriptions() {
        TestSupport.assertFalse(
            ToolchainCommand.requestsNodeInstallation(
                "echo agentcodi-toolchain install node"
            ),
            "installer text used as an argument"
        );
        TestSupport.assertFalse(
            ToolchainCommand.requestsNodeInstallation(
                "agentcodi-toolchain install node unexpected"
            ),
            "invalid installer arguments"
        );
        TestSupport.assertFalse(
            ToolchainCommand.requestsNodeInstallation(
                "echo /private/native/libagentcodi-shell.so --toolchain install node"
            ),
            "direct bridge text used as an argument"
        );
    }
}
