package de.agentcodi.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CodexInteractiveRequest {
    public enum Kind {
        COMMAND_APPROVAL,
        FILE_CHANGE_APPROVAL,
        MCP_TOOL_APPROVAL,
        USER_INPUT
    }

    private final long requestId;
    private final Kind kind;
    private final String threadId;
    private final String turnId;
    private final String itemId;
    private final String reason;
    private final String command;
    private final String cwd;
    private final String grantRoot;
    private final String networkHost;
    private final String networkProtocol;
    private final List<CodexFileChangeSummary> fileChanges;
    private final List<String> proposedExecPolicyAmendment;
    private final List<CodexNetworkPolicyAmendment> proposedNetworkPolicyAmendments;
    private final List<CodexUserInputQuestion> questions;
    private final boolean blocking;
    private final long expiresAtMilliseconds;
    private final boolean justInTimeApproval;
    private final String mcpServerName;
    private final String mcpToolArguments;

    public CodexInteractiveRequest(
        long requestId,
        Kind kind,
        String threadId,
        String turnId,
        String itemId,
        String reason,
        String command,
        String cwd,
        String grantRoot,
        String networkHost,
        String networkProtocol,
        List<CodexFileChangeSummary> fileChanges,
        List<String> proposedExecPolicyAmendment,
        List<CodexNetworkPolicyAmendment> proposedNetworkPolicyAmendments,
        List<CodexUserInputQuestion> questions,
        boolean blocking,
        long expiresAtMilliseconds,
        boolean justInTimeApprovalsEnabled
    ) {
        this(requestId, kind, threadId, turnId, itemId, reason, command, cwd, grantRoot,
            networkHost, networkProtocol, fileChanges, proposedExecPolicyAmendment,
            proposedNetworkPolicyAmendments, questions, blocking, expiresAtMilliseconds,
            justInTimeApprovalsEnabled, "", "");
    }

    private CodexInteractiveRequest(
        long requestId,
        Kind kind,
        String threadId,
        String turnId,
        String itemId,
        String reason,
        String command,
        String cwd,
        String grantRoot,
        String networkHost,
        String networkProtocol,
        List<CodexFileChangeSummary> fileChanges,
        List<String> proposedExecPolicyAmendment,
        List<CodexNetworkPolicyAmendment> proposedNetworkPolicyAmendments,
        List<CodexUserInputQuestion> questions,
        boolean blocking,
        long expiresAtMilliseconds,
        boolean justInTimeApprovalsEnabled,
        String mcpServerName,
        String mcpToolArguments
    ) {
        if (kind == null) {
            throw new IllegalArgumentException("Interactive request kind is required");
        }
        this.requestId = requestId;
        this.kind = kind;
        this.threadId = nonNull(threadId);
        this.turnId = nonNull(turnId);
        this.itemId = nonNull(itemId);
        this.reason = nonNull(reason);
        this.command = nonNull(command);
        this.cwd = nonNull(cwd);
        this.grantRoot = nonNull(grantRoot);
        this.networkHost = nonNull(networkHost);
        this.networkProtocol = nonNull(networkProtocol);
        this.fileChanges = immutableCopy(fileChanges);
        this.proposedExecPolicyAmendment = immutableStrings(proposedExecPolicyAmendment);
        this.proposedNetworkPolicyAmendments = immutableNetworkCopy(
            proposedNetworkPolicyAmendments
        );
        this.questions = immutableQuestionCopy(questions);
        this.blocking = blocking;
        this.expiresAtMilliseconds = expiresAtMilliseconds;
        if (justInTimeApprovalsEnabled) {
            throw new IllegalArgumentException("Package Edition does not support just-in-time permissions");
        }
        this.justInTimeApproval = false;
        this.mcpServerName = nonNull(mcpServerName);
        this.mcpToolArguments = nonNull(mcpToolArguments);
    }

    /** MCP approvals have a server request identity, with no itemId or startedAtMs. */
    public static CodexInteractiveRequest mcpToolApproval(
        long requestId, String threadId, String turnId, String serverName,
        String message, String arguments, long expiresAtMilliseconds
    ) {
        return new CodexInteractiveRequest(
            requestId, Kind.MCP_TOOL_APPROVAL, threadId, turnId, "", message, "", "", "",
            "", "", Collections.<CodexFileChangeSummary>emptyList(),
            Collections.<String>emptyList(),
            Collections.<CodexNetworkPolicyAmendment>emptyList(),
            Collections.<CodexUserInputQuestion>emptyList(), true, expiresAtMilliseconds,
            false, serverName, arguments
        );
    }

    public String getMcpServerName() {
        return mcpServerName;
    }

    public String getMcpToolArguments() {
        return mcpToolArguments;
    }

    public long getRequestId() {
        return requestId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getItemId() {
        return itemId;
    }

    public String getReason() {
        return reason;
    }

    public String getCommand() {
        return command;
    }

    public String getCwd() {
        return cwd;
    }

    public String getGrantRoot() {
        return grantRoot;
    }

    public String getNetworkHost() {
        return networkHost;
    }

    public String getNetworkProtocol() {
        return networkProtocol;
    }

    public List<CodexFileChangeSummary> getFileChanges() {
        return fileChanges;
    }

    public List<String> getProposedExecPolicyAmendment() {
        return proposedExecPolicyAmendment;
    }

    public List<CodexNetworkPolicyAmendment> getProposedNetworkPolicyAmendments() {
        return proposedNetworkPolicyAmendments;
    }

    public List<CodexUserInputQuestion> getQuestions() {
        return questions;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public long getExpiresAtMilliseconds() {
        return expiresAtMilliseconds;
    }

    public boolean isJustInTimeApproval() {
        return justInTimeApproval;
    }

    public boolean allowsDecision(CodexApprovalDecision decision) {
        if (kind == Kind.MCP_TOOL_APPROVAL) {
            return decision == CodexApprovalDecision.ACCEPT
                || decision == CodexApprovalDecision.DECLINE
                || decision == CodexApprovalDecision.CANCEL;
        }
        return decision != null && kind != Kind.USER_INPUT
            && (!justInTimeApproval || decision == CodexApprovalDecision.ACCEPT
                || decision == CodexApprovalDecision.DECLINE
                || decision == CodexApprovalDecision.CANCEL);
    }

    public CodexInteractiveRequest withFileChanges(List<CodexFileChangeSummary> changes) {
        return new CodexInteractiveRequest(
            requestId,
            kind,
            threadId,
            turnId,
            itemId,
            reason,
            command,
            cwd,
            grantRoot,
            networkHost,
            networkProtocol,
            changes,
            proposedExecPolicyAmendment,
            proposedNetworkPolicyAmendments,
            questions,
            blocking,
            expiresAtMilliseconds,
            justInTimeApproval,
            mcpServerName,
            mcpToolArguments
        );
    }

    private static List<CodexFileChangeSummary> immutableCopy(
        List<CodexFileChangeSummary> values
    ) {
        return Collections.unmodifiableList(new ArrayList<CodexFileChangeSummary>(
            values == null ? Collections.<CodexFileChangeSummary>emptyList() : values
        ));
    }

    private static List<String> immutableStrings(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
            values == null ? Collections.<String>emptyList() : values
        ));
    }

    private static List<CodexNetworkPolicyAmendment> immutableNetworkCopy(
        List<CodexNetworkPolicyAmendment> values
    ) {
        return Collections.unmodifiableList(new ArrayList<CodexNetworkPolicyAmendment>(
            values == null
                ? Collections.<CodexNetworkPolicyAmendment>emptyList()
                : values
        ));
    }

    private static List<CodexUserInputQuestion> immutableQuestionCopy(
        List<CodexUserInputQuestion> values
    ) {
        return Collections.unmodifiableList(new ArrayList<CodexUserInputQuestion>(
            values == null ? Collections.<CodexUserInputQuestion>emptyList() : values
        ));
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
