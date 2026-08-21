package de.agentcodi.tests;

public final class TestMain {
    private TestMain() {
    }

    public static void main(String[] arguments) throws Exception {
        int passed = 0;
        passed += BuildIdentityTest.run();
        passed += JsonCodecTest.run();
        passed += CodexAppServerClientTest.run();
        passed += CodexWorkspaceAttachmentContextTest.run();
        passed += CodexSessionControllerTest.run();
        passed += McpCatalogLoaderTest.run();
        passed += McpConfigurationControllerTest.run();
        passed += CredentialGuardTest.run();
        passed += TerminalOutputBufferTest.run();
        passed += ToolchainCommandTest.run();
        passed += CrashReportFormatterTest.run();
        passed += CrashReportStoreTest.run();
        passed += RuntimeStateMachineTest.run();
        passed += RuntimeReportFormatterTest.run();
        passed += UiStartupStateTest.run();
        passed += UiLanguageTest.run();
        passed += WorkspaceLayoutTest.run();
        passed += WorkspaceImportTest.run();
        passed += de.agentcodi.imports.client.WorkspaceImportLifecycleTest.run();
        passed += WorkspaceExportTest.run();
        System.out.println("Java tests passed: " + passed);
    }
}
