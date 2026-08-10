package de.agentcodi.tests;

public final class TestMain {
    private TestMain() {
    }

    public static void main(String[] arguments) throws Exception {
        int passed = 0;
        passed += BuildIdentityTest.run();
        passed += JsonCodecTest.run();
        passed += CodexAppServerClientTest.run();
        passed += CodexSessionControllerTest.run();
        passed += CrashReportFormatterTest.run();
        passed += CrashReportStoreTest.run();
        passed += RuntimeStateMachineTest.run();
        passed += RuntimeReportFormatterTest.run();
        passed += UiStartupStateTest.run();
        passed += WorkspaceLayoutTest.run();
        System.out.println("Java tests passed: " + passed);
    }
}
