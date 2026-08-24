package de.agentcodi.core;

/**
 * Pure policy and state-correlation contract implemented by the dedicated
 * review-mode module. Core owns the transport lifecycle but not target policy.
 */
public interface CodexReviewMode {
    CodexReviewRequest prepare(String threadId, String instructions);

    CodexReviewState begin(CodexReviewState current, CodexReviewRequest request);

    CodexReviewState correlateTurnStarted(
        CodexReviewState current,
        String threadId,
        String turnId
    );

    CodexReviewState correlateStartResponse(
        CodexReviewState current,
        CodexReviewRequest request,
        String reviewThreadId,
        String turnId,
        boolean turnAlreadyCompleted
    );

    boolean acceptsItem(
        CodexReviewState current,
        String threadId,
        String turnId,
        String type,
        boolean authoritativeCompleted
    );

    CodexReviewState correlateItem(
        CodexReviewState current,
        String threadId,
        String turnId,
        String type,
        boolean authoritativeCompleted
    );

    CodexReviewState correlateTurnCompleted(
        CodexReviewState current,
        String threadId,
        String turnId
    );

    boolean acceptsTurnCompletion(
        CodexReviewState current,
        String threadId,
        String turnId
    );
}
