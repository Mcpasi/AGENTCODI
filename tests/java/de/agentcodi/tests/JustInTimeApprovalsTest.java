package de.agentcodi.tests;

import de.agentcodi.core.CodexApprovalDecision;
import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.CodexInteractiveRequest;
import de.agentcodi.core.RuntimeSnapshot;
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
        CodexSessionControllerTest.justInTimeApprovalsAreOneActionInProtectedMode();
        CodexSessionControllerTest.rejectsJustInTimeCompatibilityMode();
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

        final RuntimeStateMachine runtime = new RuntimeStateMachine();
        TestSupport.assertFalse(runtime.snapshot().isJustInTimeApprovalsEnabled(), "initially off");
        for (final String[] configuration : new String[][] {
            {CodexExecutionMode.COMPATIBILITY_ID,
                CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID},
            {CodexExecutionMode.PROTECTED_ID,
                CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID},
            {CodexExecutionMode.COMPATIBILITY_ID,
                CodexExecutionMode.PROTECTED_PERMISSION_PROFILE_ID},
            {CodexExecutionMode.PROTECTED_ID, "unverified-profile"}
        }) {
            final RuntimeSnapshot before = runtime.snapshot();
            TestSupport.expectThrows(IllegalArgumentException.class,
                () -> runtime.beginStart(configuration[0], configuration[1], false, true),
                "JIT requires the protected mode and its exact permission profile");
            TestSupport.assertTrue(before == runtime.snapshot(),
                "invalid JIT startup changes neither phase nor generation");
        }

        long generation = runtime.beginStart(CodexExecutionMode.PROTECTED_ID,
            CodexExecutionMode.PROTECTED_PERMISSION_PROFILE_ID, false, true);
        TestSupport.assertTrue(runtime.snapshot().isJustInTimeApprovalsEnabled(), "startup selection");
        runtime.markReady(generation, "fixture", "", "/private/workspace");
        final RuntimeSnapshot ready = runtime.snapshot();
        TestSupport.assertTrue(ready.withExecutionMode(CodexExecutionMode.PROTECTED_ID,
            CodexExecutionMode.PROTECTED_PERMISSION_PROFILE_ID).isJustInTimeApprovalsEnabled(),
            "protected mode preserves JIT");
        TestSupport.expectThrows(IllegalArgumentException.class,
            () -> ready.withExecutionMode(CodexExecutionMode.COMPATIBILITY_ID,
                CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID),
            "active JIT cannot be combined with a compatibility profile");
        TestSupport.assertTrue(ready == runtime.snapshot(), "rejected mode change keeps ready state");
        runtime.markFailed(generation, "fixture disconnect");
        runtime.stop();
        runtime.beginStart();
        TestSupport.assertFalse(runtime.snapshot().isJustInTimeApprovalsEnabled(),
            "unconfirmed service restart returns to the default");
        runtime.stop();
        runtime.beginStart(CodexExecutionMode.COMPATIBILITY_ID,
            CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID, true, false);
        TestSupport.assertFalse(runtime.snapshot().isJustInTimeApprovalsEnabled(),
            "compatibility mode remains available without JIT");
        TestSupport.assertTrue(runtime.snapshot().isCompatibilityApprovalsEnabled(),
            "ordinary compatibility approvals remain independently available");
        return 4;
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
