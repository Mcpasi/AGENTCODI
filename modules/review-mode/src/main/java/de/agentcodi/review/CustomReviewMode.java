package de.agentcodi.review;

import de.agentcodi.core.CodexReviewMode;
import de.agentcodi.core.CodexReviewRequest;
import de.agentcodi.core.CodexReviewState;
import de.agentcodi.core.CredentialGuard;

/**
 * The only AGENTCODI review implementation. It deliberately exposes neither
 * working-tree nor branch/commit targets because the product has no Git contract.
 */
public final class CustomReviewMode implements CodexReviewMode {
    private static final CustomReviewMode INSTANCE = new CustomReviewMode();

    private CustomReviewMode() {
    }

    public static CustomReviewMode get() {
        return INSTANCE;
    }

    @Override
    public CodexReviewRequest prepare(String threadId, String instructions) {
        if (CredentialGuard.containsLikelyCredential(instructions)) {
            throw new IllegalArgumentException(
                "Review instructions must not contain credential-shaped input"
            );
        }
        return new CodexReviewRequest(threadId, instructions);
    }

    @Override
    public CodexReviewState begin(
        CodexReviewState current,
        CodexReviewRequest request
    ) {
        requireState(current);
        if (request == null) {
            throw new IllegalArgumentException("Review request is required");
        }
        if (current.isReviewTurnInProgress()) {
            throw new IllegalStateException("A review turn is already in progress");
        }
        return CodexReviewState.starting(
            request.getThreadId(),
            "",
            "",
            false
        );
    }

    @Override
    public CodexReviewState correlateTurnStarted(
        CodexReviewState current,
        String threadId,
        String turnId
    ) {
        requireState(current);
        if (!current.isReviewTurnInProgress()
            || !current.getThreadId().equals(threadId)
            || !canBindNotificationTurn(current, turnId)) {
            return current;
        }
        return withNotificationTurn(current, turnId, current.getPhase());
    }

    @Override
    public CodexReviewState correlateStartResponse(
        CodexReviewState current,
        CodexReviewRequest request,
        String reviewThreadId,
        String turnId,
        boolean turnAlreadyCompleted
    ) {
        requireState(current);
        if (request == null
            || !request.getThreadId().equals(reviewThreadId)
            || !current.getThreadId().equals(request.getThreadId())
            || !isSafeIdentifier(turnId)) {
            throw new IllegalArgumentException("Review start response is not correlated");
        }
        String responseTurnId = current.getResponseTurnId();
        String notificationTurnId = current.getNotificationTurnId();
        if (current.isStartResponseCorrelated()) {
            if (!responseTurnId.equals(turnId)) {
                throw new IllegalArgumentException("Review start returned a different turn");
            }
        } else if (!responseTurnId.isEmpty() && !responseTurnId.equals(turnId)) {
            if (notificationTurnId.equals(turnId)) {
                notificationTurnId = responseTurnId;
                responseTurnId = turnId;
            } else if (notificationTurnId.isEmpty()) {
                notificationTurnId = responseTurnId;
                responseTurnId = turnId;
            } else {
                throw new IllegalArgumentException("Review start returned a third turn");
            }
        } else {
            responseTurnId = turnId;
        }
        if (turnAlreadyCompleted
            || current.getPhase() == CodexReviewState.Phase.COMPLETED) {
            return CodexReviewState.completed(
                reviewThreadId,
                responseTurnId,
                notificationTurnId,
                true
            );
        }
        if (current.getPhase() == CodexReviewState.Phase.ACTIVE) {
            return CodexReviewState.active(
                reviewThreadId,
                responseTurnId,
                notificationTurnId,
                true
            );
        }
        if (current.getPhase() == CodexReviewState.Phase.EXITED) {
            return CodexReviewState.exited(
                reviewThreadId,
                responseTurnId,
                notificationTurnId,
                true
            );
        }
        if (current.getPhase() != CodexReviewState.Phase.STARTING) {
            throw new IllegalArgumentException("Review start response has no pending request");
        }
        return CodexReviewState.starting(
            reviewThreadId,
            responseTurnId,
            notificationTurnId,
            true
        );
    }

    @Override
    public boolean acceptsItem(
        CodexReviewState current,
        String threadId,
        String turnId,
        String type,
        boolean authoritativeCompleted
    ) {
        requireState(current);
        if (!"enteredReviewMode".equals(type) && !"exitedReviewMode".equals(type)) {
            return false;
        }
        if (current.getPhase() == CodexReviewState.Phase.IDLE
            || current.getPhase() == CodexReviewState.Phase.FAILED
            || !current.getThreadId().equals(threadId)
            || !canBindNotificationTurn(current, turnId)) {
            return false;
        }
        return current.getPhase() != CodexReviewState.Phase.COMPLETED
            || (authoritativeCompleted
                && current.isCorrelatedWith(threadId, turnId));
    }

    @Override
    public CodexReviewState correlateItem(
        CodexReviewState current,
        String threadId,
        String turnId,
        String type,
        boolean authoritativeCompleted
    ) {
        if (!acceptsItem(current, threadId, turnId, type, authoritativeCompleted)) {
            return current;
        }
        if (current.getPhase() == CodexReviewState.Phase.COMPLETED) {
            return current;
        }
        CodexReviewState correlated = withNotificationTurn(
            current,
            turnId,
            current.getPhase()
        );
        if ("enteredReviewMode".equals(type)) {
            if (correlated.getPhase() == CodexReviewState.Phase.EXITED) {
                return correlated;
            }
            return withPhase(correlated, CodexReviewState.Phase.ACTIVE);
        }
        if (authoritativeCompleted) {
            return withPhase(correlated, CodexReviewState.Phase.EXITED);
        }
        return correlated;
    }

    @Override
    public CodexReviewState correlateTurnCompleted(
        CodexReviewState current,
        String threadId,
        String turnId
    ) {
        requireState(current);
        if (!acceptsTurnCompletion(current, threadId, turnId)) {
            return current;
        }
        return withPhase(
            withNotificationTurn(current, turnId, current.getPhase()),
            CodexReviewState.Phase.COMPLETED
        );
    }

    @Override
    public boolean acceptsTurnCompletion(
        CodexReviewState current,
        String threadId,
        String turnId
    ) {
        requireState(current);
        return current.isReviewTurnInProgress()
            && current.getThreadId().equals(threadId)
            && canBindNotificationTurn(current, turnId);
    }

    private static boolean canBindNotificationTurn(
        CodexReviewState current,
        String turnId
    ) {
        if (!isSafeIdentifier(turnId)) {
            return false;
        }
        if (current.isCorrelatedWith(current.getThreadId(), turnId)) {
            return true;
        }
        if (current.getNotificationTurnId().isEmpty()) {
            return true;
        }
        return !current.isStartResponseCorrelated()
            && current.getResponseTurnId().isEmpty();
    }

    private static CodexReviewState withNotificationTurn(
        CodexReviewState current,
        String turnId,
        CodexReviewState.Phase phase
    ) {
        String responseTurnId = current.getResponseTurnId();
        String notificationTurnId = current.getNotificationTurnId();
        if (!current.isCorrelatedWith(current.getThreadId(), turnId)) {
            if (notificationTurnId.isEmpty()) {
                notificationTurnId = turnId;
            } else if (!current.isStartResponseCorrelated()
                && responseTurnId.isEmpty()) {
                responseTurnId = turnId;
            }
        }
        return state(
            phase,
            current.getThreadId(),
            responseTurnId,
            notificationTurnId,
            current.isStartResponseCorrelated()
        );
    }

    private static CodexReviewState withPhase(
        CodexReviewState current,
        CodexReviewState.Phase phase
    ) {
        return state(
            phase,
            current.getThreadId(),
            current.getResponseTurnId(),
            current.getNotificationTurnId(),
            current.isStartResponseCorrelated()
        );
    }

    private static CodexReviewState state(
        CodexReviewState.Phase phase,
        String threadId,
        String responseTurnId,
        String notificationTurnId,
        boolean startResponseCorrelated
    ) {
        if (phase == CodexReviewState.Phase.ACTIVE) {
            return CodexReviewState.active(
                threadId,
                responseTurnId,
                notificationTurnId,
                startResponseCorrelated
            );
        }
        if (phase == CodexReviewState.Phase.EXITED) {
            return CodexReviewState.exited(
                threadId,
                responseTurnId,
                notificationTurnId,
                startResponseCorrelated
            );
        }
        if (phase == CodexReviewState.Phase.COMPLETED) {
            return CodexReviewState.completed(
                threadId,
                responseTurnId,
                notificationTurnId,
                startResponseCorrelated
            );
        }
        return CodexReviewState.starting(
            threadId,
            responseTurnId,
            notificationTurnId,
            startResponseCorrelated
        );
    }

    private static void requireState(CodexReviewState state) {
        if (state == null) {
            throw new IllegalArgumentException("Review state is required");
        }
    }

    private static boolean isSafeIdentifier(String value) {
        if (value == null || value.isEmpty() || value.length() > 160) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                && !(character >= 'A' && character <= 'Z')
                && !(character >= '0' && character <= '9')
                && character != '-' && character != '_' && character != '.'
                && character != ':') {
                return false;
            }
        }
        return true;
    }
}
