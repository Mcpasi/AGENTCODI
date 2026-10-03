package de.agentcodi.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;

/** Package Edition exposes Full access and an independent approval preference. */
final class ExecutionModeSettingsCard {
    interface ActiveModeListener {
        boolean onActiveModeRequested(
            String executionModeId,
            boolean dangerWarningAcknowledged
        );

        boolean onActiveCompatibilityApprovalsRequested(boolean enabled);
    }

    interface ConfirmedLaunchListener {
        void onLaunchConfirmed(
            String executionModeId,
            boolean dangerWarningAcknowledged,
            boolean compatibilityApprovalsEnabled,
            boolean justInTimeApprovalsEnabled
        );
    }

    private final Activity activity;
    private final UiTheme theme;
    private final ActiveModeListener activeModeListener;
    private final LinearLayout card;
    private final TextView statusView;
    private final Switch approvalSwitch;
    private boolean approvalsEnabled;
    private boolean runtimeReady;
    private boolean controlsEnabled = true;
    private boolean updatingApprovalSwitch;

    ExecutionModeSettingsCard(
        Activity activity,
        UiTheme theme,
        ActiveModeListener activeModeListener
    ) {
        if (activity == null || theme == null || activeModeListener == null) {
            throw new IllegalArgumentException("Execution mode UI dependencies are required");
        }
        this.activity = activity;
        this.theme = theme;
        this.activeModeListener = activeModeListener;
        card = theme.card();
        card.addView(theme.body(activity.getString(R.string.execution_mode_description)));
        statusView = theme.text("", 15, theme.danger);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(card, statusView, 12);

        approvalSwitch = new Switch(activity);
        approvalSwitch.setText(R.string.execution_mode_compatibility_approvals);
        approvalSwitch.setTextColor(theme.primary);
        approvalSwitch.setTextSize(15);
        approvalSwitch.setSaveEnabled(false);
        approvalSwitch.setOnCheckedChangeListener(
            new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (updatingApprovalSwitch) {
                        return;
                    }
                    if (!controlsEnabled
                        || (runtimeReady
                            && !activeModeListener.onActiveCompatibilityApprovalsRequested(
                                isChecked))) {
                        updatePresentation();
                        return;
                    }
                    approvalsEnabled = isChecked;
                    updatePresentation();
                }
            }
        );
        theme.addWithTopMargin(card, approvalSwitch, 14);
        theme.addWithTopMargin(card, theme.body(activity.getString(
            R.string.execution_mode_compatibility_approvals_description)), 6);
        updatePresentation();
    }

    LinearLayout getView() {
        return card;
    }

    void render(RuntimeSnapshot runtime, CodexSessionSnapshot session) {
        runtimeReady = runtime.getPhase() == RuntimePhase.READY && session.isReady();
        if (runtimeReady) {
            approvalsEnabled = session.isCompatibilityApprovalsEnabled();
        } else if (runtime.getPhase() == RuntimePhase.STARTING) {
            approvalsEnabled = runtime.isCompatibilityApprovalsEnabled();
        }
        controlsEnabled = runtime.getPhase() != RuntimePhase.STARTING
            && runtime.getPhase() != RuntimePhase.STOPPING
            && (!runtimeReady
                || (!session.isOperationActive()
                    && !session.isTurnActive()
                    && !session.hasInteractiveRequest()));
        updatePresentation();
    }

    void confirmSelectedModeForLaunch(ConfirmedLaunchListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Launch confirmation listener is required");
        }
        listener.onLaunchConfirmed(
            CodexExecutionMode.COMPATIBILITY_ID,
            true,
            approvalsEnabled,
            false
        );
    }

    void dismiss() {
        // This edition has no mode-selection dialog.
    }

    private void updatePresentation() {
        statusView.setText(activity.getString(
            runtimeReady ? R.string.execution_mode_active : R.string.execution_mode_next_start,
            activity.getString(R.string.execution_mode_compatibility_name),
            CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID
        ));
        updatingApprovalSwitch = true;
        try {
            approvalSwitch.setChecked(approvalsEnabled);
            theme.setEnabled(approvalSwitch, controlsEnabled);
        } finally {
            updatingApprovalSwitch = false;
        }
    }
}
