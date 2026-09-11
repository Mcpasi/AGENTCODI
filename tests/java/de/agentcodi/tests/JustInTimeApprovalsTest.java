package de.agentcodi.tests;

import de.agentcodi.core.CodexApprovalDecision;
import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.CodexInteractiveRequest;
import de.agentcodi.core.RuntimeStateMachine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class JustInTimeApprovalsTest {
    private JustInTimeApprovalsTest() {
    }

    public static void main(String[] arguments) throws Exception {
        run();
        CodexSessionControllerTest.justInTimeApprovalsAreOneActionInBothModes();
        System.out.println("Just-in-time approval tests passed.");
    }

    public static int run() {
        for (CodexInteractiveRequest.Kind kind : Arrays.asList(
            CodexInteractiveRequest.Kind.COMMAND_APPROVAL,
            CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL
        )) {
            CodexInteractiveRequest request = request(kind, "", true);
            TestSupport.assertEquals(Arrays.asList(CodexApprovalDecision.ACCEPT,
                CodexApprovalDecision.DECLINE, CodexApprovalDecision.CANCEL),
                decisions(request), "JIT dialog offers no reusable grant");
            TestSupport.assertTrue(request.withFileChanges(Collections.emptyList())
                .isJustInTimeApproval(), "preview replacement keeps JIT policy");
            TestSupport.assertEquals(Arrays.asList(CodexApprovalDecision.values()),
                decisions(request(kind, "", false)), "disabled mode preserves existing decisions");
        }
        TestSupport.assertFalse(request(CodexInteractiveRequest.Kind.COMMAND_APPROVAL,
            "example.invalid", true).isJustInTimeApproval(), "network request retains its own policy");
        TestSupport.assertEquals(Collections.emptyList(), decisions(request(
            CodexInteractiveRequest.Kind.USER_INPUT, "", true)), "questions cannot grant approvals");

        RuntimeStateMachine runtime = new RuntimeStateMachine();
        TestSupport.assertFalse(runtime.snapshot().isJustInTimeApprovalsEnabled(), "initially off");
        for (String mode : Arrays.asList(CodexExecutionMode.PROTECTED_ID,
            CodexExecutionMode.COMPATIBILITY_ID)) {
            String profile = CodexExecutionMode.PROTECTED_ID.equals(mode)
                ? "agentcodi-workspace" : ":danger-full-access";
            long generation = runtime.beginStart(mode, profile, false, true);
            TestSupport.assertTrue(runtime.snapshot().isJustInTimeApprovalsEnabled(), "startup selection");
            runtime.markReady(generation, "fixture", "", "/private/workspace");
            TestSupport.assertTrue(runtime.snapshot().withExecutionMode(
                CodexExecutionMode.PROTECTED_ID, "agentcodi-workspace")
                .isJustInTimeApprovalsEnabled(), "profile switching preserves JIT");
            runtime.markFailed(generation, "fixture disconnect");
            runtime.stop();
        }
        runtime.beginStart();
        TestSupport.assertFalse(runtime.snapshot().isJustInTimeApprovalsEnabled(),
            "unconfirmed service restart returns to the default");
        return 3;
    }

    private static List<CodexApprovalDecision> decisions(CodexInteractiveRequest request) {
        List<CodexApprovalDecision> decisions = new ArrayList<CodexApprovalDecision>();
        for (CodexApprovalDecision decision : CodexApprovalDecision.values()) {
            if (request.allowsDecision(decision)) {
                decisions.add(decision);
            }
        }
        return decisions;
    }

    private static CodexInteractiveRequest request(
        CodexInteractiveRequest.Kind kind, String host, boolean enabled
    ) {
        return new CodexInteractiveRequest(1L, kind, "thread", "turn", "item", "",
            "node checks", "/private/workspace", "", host, "", Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            true, Long.MAX_VALUE, enabled);
    }
}
