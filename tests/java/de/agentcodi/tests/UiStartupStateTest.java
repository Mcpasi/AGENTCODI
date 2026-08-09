package de.agentcodi.tests;

import de.agentcodi.core.UiStartupState;

public final class UiStartupStateTest {
    private UiStartupStateTest() {
    }

    public static int run() {
        startsWithoutRefresh();
        tracksFailureStage();
        enablesRefreshOnlyAfterCompletion();
        failureDisablesRefresh();
        rejectsInvalidTransitions();
        return 5;
    }

    private static void startsWithoutRefresh() {
        UiStartupState state = new UiStartupState();
        TestSupport.assertFalse(state.shouldRefresh(), "refresh before content is ready");
        TestSupport.assertEquals(
            "activity-created",
            state.failureSource(),
            "initial failure source"
        );
    }

    private static void tracksFailureStage() {
        UiStartupState state = new UiStartupState();
        state.enter("content-header");
        TestSupport.assertEquals(
            "activity-content-header",
            state.failureSource(),
            "precise failure source"
        );
    }

    private static void enablesRefreshOnlyAfterCompletion() {
        UiStartupState state = new UiStartupState();
        state.enter("content-complete");
        state.complete();
        TestSupport.assertTrue(state.shouldRefresh(), "refresh after complete content");
    }

    private static void failureDisablesRefresh() {
        UiStartupState state = new UiStartupState();
        state.enter("content-status");
        state.fail();
        TestSupport.assertFalse(state.shouldRefresh(), "no refresh for emergency UI");
        TestSupport.assertEquals(
            "activity-content-status",
            state.failureSource(),
            "failed stage remains visible"
        );
    }

    private static void rejectsInvalidTransitions() {
        UiStartupState state = new UiStartupState();
        TestSupport.expectThrows(
            IllegalArgumentException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    state.enter(" ");
                }
            },
            "blank startup stage"
        );
        state.complete();
        TestSupport.expectThrows(
            IllegalStateException.class,
            new TestSupport.ThrowingRunnable() {
                @Override
                public void run() {
                    state.enter("late");
                }
            },
            "stage after completion"
        );
    }
}
