package de.agentcodi.core;

/** Bounded in-memory correlation state for one inline review lifecycle. */
public final class CodexReviewState {
    public enum Phase {
        IDLE,
        STARTING,
        ACTIVE,
        EXITED,
        COMPLETED,
        FAILED
    }

    private static final CodexReviewState IDLE =
        new CodexReviewState(Phase.IDLE, "", "", "", false);

    private final Phase phase;
    private final String threadId;
    private final String responseTurnId;
    private final String notificationTurnId;
    private final boolean startResponseCorrelated;

    private CodexReviewState(
        Phase phase,
        String threadId,
        String responseTurnId,
        String notificationTurnId,
        boolean startResponseCorrelated
    ) {
        if (phase == null) {
            throw new IllegalArgumentException("Review phase is required");
        }
        String safeThreadId = threadId == null ? "" : threadId;
        String safeResponseTurnId = responseTurnId == null ? "" : responseTurnId;
        String safeNotificationTurnId = notificationTurnId == null
            ? ""
            : notificationTurnId;
        if (safeResponseTurnId.equals(safeNotificationTurnId)) {
            safeNotificationTurnId = "";
        }
        if (phase == Phase.IDLE) {
            if (!safeThreadId.isEmpty()
                || !safeResponseTurnId.isEmpty()
                || !safeNotificationTurnId.isEmpty()
                || startResponseCorrelated) {
                throw new IllegalArgumentException("Idle review state cannot retain correlation");
            }
        } else {
            if (!CodexReviewRequest.isSafeIdentifier(safeThreadId)) {
                throw new IllegalArgumentException("Review state thread id is invalid");
            }
            if (!safeResponseTurnId.isEmpty()
                && !CodexReviewRequest.isSafeIdentifier(safeResponseTurnId)) {
                throw new IllegalArgumentException("Review response turn id is invalid");
            }
            if (!safeNotificationTurnId.isEmpty()
                && !CodexReviewRequest.isSafeIdentifier(safeNotificationTurnId)) {
                throw new IllegalArgumentException("Review notification turn id is invalid");
            }
            if (startResponseCorrelated && safeResponseTurnId.isEmpty()) {
                throw new IllegalArgumentException(
                    "A correlated review response requires its turn id"
                );
            }
            if (phase == Phase.FAILED
                && (!safeResponseTurnId.isEmpty()
                    || !safeNotificationTurnId.isEmpty()
                    || startResponseCorrelated)) {
                throw new IllegalArgumentException(
                    "Failed review state cannot retain turn correlation"
                );
            }
            if ((phase == Phase.ACTIVE
                || phase == Phase.EXITED
                || phase == Phase.COMPLETED)
                && safeResponseTurnId.isEmpty()
                && safeNotificationTurnId.isEmpty()) {
                throw new IllegalArgumentException("Correlated review state requires a turn id");
            }
        }
        this.phase = phase;
        this.threadId = safeThreadId;
        this.responseTurnId = safeResponseTurnId;
        this.notificationTurnId = safeNotificationTurnId;
        this.startResponseCorrelated = startResponseCorrelated;
    }

    public static CodexReviewState idle() {
        return IDLE;
    }

    public static CodexReviewState starting(String threadId, String turnId) {
        String safeTurnId = turnId == null ? "" : turnId;
        return starting(threadId, safeTurnId, "", !safeTurnId.isEmpty());
    }

    public static CodexReviewState starting(
        String threadId,
        String responseTurnId,
        String notificationTurnId,
        boolean startResponseCorrelated
    ) {
        return new CodexReviewState(
            Phase.STARTING,
            threadId,
            responseTurnId,
            notificationTurnId,
            startResponseCorrelated
        );
    }

    public static CodexReviewState active(String threadId, String turnId) {
        return active(threadId, turnId, "", true);
    }

    public static CodexReviewState active(
        String threadId,
        String responseTurnId,
        String notificationTurnId,
        boolean startResponseCorrelated
    ) {
        return new CodexReviewState(
            Phase.ACTIVE,
            threadId,
            responseTurnId,
            notificationTurnId,
            startResponseCorrelated
        );
    }

    public static CodexReviewState exited(String threadId, String turnId) {
        return exited(threadId, turnId, "", true);
    }

    public static CodexReviewState exited(
        String threadId,
        String responseTurnId,
        String notificationTurnId,
        boolean startResponseCorrelated
    ) {
        return new CodexReviewState(
            Phase.EXITED,
            threadId,
            responseTurnId,
            notificationTurnId,
            startResponseCorrelated
        );
    }

    public static CodexReviewState completed(String threadId, String turnId) {
        return completed(threadId, turnId, "", true);
    }

    public static CodexReviewState completed(
        String threadId,
        String responseTurnId,
        String notificationTurnId,
        boolean startResponseCorrelated
    ) {
        return new CodexReviewState(
            Phase.COMPLETED,
            threadId,
            responseTurnId,
            notificationTurnId,
            startResponseCorrelated
        );
    }

    public static CodexReviewState failed(String threadId) {
        return new CodexReviewState(Phase.FAILED, threadId, "", "", false);
    }

    public Phase getPhase() {
        return phase;
    }

    public String getThreadId() {
        return threadId;
    }

    public String getTurnId() {
        return responseTurnId.isEmpty() ? notificationTurnId : responseTurnId;
    }

    public String getResponseTurnId() {
        return responseTurnId;
    }

    public String getNotificationTurnId() {
        return notificationTurnId;
    }

    /**
     * The live app-server turn is announced by turn/started. Prefer that tightly
     * scoped id for turn/interrupt while retaining the response id for terminal
     * lifecycle correlation.
     */
    public String getControlTurnId() {
        return notificationTurnId.isEmpty() ? responseTurnId : notificationTurnId;
    }

    public boolean isStartResponseCorrelated() {
        return startResponseCorrelated;
    }

    public boolean isStarting() {
        return phase == Phase.STARTING;
    }

    public boolean isReviewModeActive() {
        return phase == Phase.ACTIVE;
    }

    public boolean isReviewTurnInProgress() {
        return phase == Phase.STARTING || phase == Phase.ACTIVE || phase == Phase.EXITED;
    }

    public boolean isFailed() {
        return phase == Phase.FAILED;
    }

    public boolean isCompleted() {
        return phase == Phase.COMPLETED;
    }

    public boolean isCorrelatedWith(String candidateThreadId, String candidateTurnId) {
        return phase != Phase.IDLE
            && threadId.equals(candidateThreadId)
            && candidateTurnId != null
            && !candidateTurnId.isEmpty()
            && (responseTurnId.equals(candidateTurnId)
                || notificationTurnId.equals(candidateTurnId));
    }
}
