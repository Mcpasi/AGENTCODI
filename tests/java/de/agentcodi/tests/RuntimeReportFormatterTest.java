package de.agentcodi.tests;

import de.agentcodi.core.BuildIdentity;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeReportFormatter;
import de.agentcodi.core.RuntimeSnapshot;

public final class RuntimeReportFormatterTest {
    private RuntimeReportFormatterTest() {
    }

    public static int run() {
        formatsIdentityAndAvailableRuntimeFields();
        omitsUnavailableOptionalFields();
        return 2;
    }

    private static void formatsIdentityAndAvailableRuntimeFields() {
        RuntimeSnapshot snapshot = new RuntimeSnapshot(
            3L,
            RuntimePhase.READY,
            "bereit",
            "native/1",
            "abi=arm64",
            "/private/workspace"
        );
        String report = RuntimeReportFormatter.format(snapshot);
        TestSupport.assertContains(report, BuildIdentity.summary(), "identity");
        TestSupport.assertContains(report, "Phase: READY", "phase");
        TestSupport.assertContains(report, "Engine: native/1", "engine");
        TestSupport.assertContains(report, "Workspace: /private/workspace", "workspace");
    }

    private static void omitsUnavailableOptionalFields() {
        RuntimeSnapshot snapshot = new RuntimeSnapshot(
            0L,
            RuntimePhase.IDLE,
            "wartet",
            "",
            "",
            ""
        );
        String report = RuntimeReportFormatter.format(snapshot);
        TestSupport.assertFalse(report.contains("Engine:"), "empty engine omitted");
        TestSupport.assertFalse(report.contains("Workspace:"), "empty workspace omitted");
    }
}

