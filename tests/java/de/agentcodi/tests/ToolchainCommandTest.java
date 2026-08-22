package de.agentcodi.tests;

import de.agentcodi.core.ToolchainCommand;

public final class ToolchainCommandTest {
    private ToolchainCommandTest() {
    }

    public static int run() {
        recognizesPackageActivationRequests();
        rejectsUnrelatedOrEmbeddedText();
        rejectsArgumentsAndQuotedDescriptions();
        return 3;
    }

    private static void recognizesPackageActivationRequests() {
        TestSupport.assertEquals(
            "node",
            ToolchainCommand.requestedInstallationPackage(
                "agentcodi-toolchain   install\tnode"
            ),
            "shell function activation"
        );
        TestSupport.assertEquals(
            "npm",
            ToolchainCommand.requestedInstallationPackage(
                "agentcodi-toolchain install npm"
            ),
            "npm activation"
        );
        TestSupport.assertEquals(
            "python",
            ToolchainCommand.requestedInstallationPackage(
                "/private/native/libagentcodi-shell.so --toolchain install python"
                    + " && python --version"
            ),
            "direct Python shell bridge activation"
        );
        TestSupport.assertEquals(
            "ripgrep",
            ToolchainCommand.requestedInstallationPackage(
                "agentcodi-toolchain install ripgrep"
            ),
            "ripgrep activation"
        );
    }

    private static void rejectsUnrelatedOrEmbeddedText() {
        TestSupport.assertEquals(
            "",
            ToolchainCommand.requestedInstallationPackage("node --version"),
            "ordinary Node command"
        );
        TestSupport.assertEquals(
            "",
            ToolchainCommand.requestedInstallationPackage(
                "echo xagentcodi-toolchain install node-suffix"
            ),
            "embedded activation text"
        );
    }

    private static void rejectsArgumentsAndQuotedDescriptions() {
        TestSupport.assertEquals(
            "",
            ToolchainCommand.requestedInstallationPackage(
                "echo agentcodi-toolchain install node"
            ),
            "installer text used as an argument"
        );
        TestSupport.assertEquals(
            "",
            ToolchainCommand.requestedInstallationPackage(
                "agentcodi-toolchain install node unexpected"
            ),
            "invalid installer arguments"
        );
        TestSupport.assertEquals(
            "",
            ToolchainCommand.requestedInstallationPackage(
                "echo /private/native/libagentcodi-shell.so --toolchain install node"
            ),
            "direct bridge text used as an argument"
        );
    }
}
