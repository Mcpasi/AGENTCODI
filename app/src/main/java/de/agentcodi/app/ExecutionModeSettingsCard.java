package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.agentcodi.core.CodexExecutionMode;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.RuntimePhase;
import de.agentcodi.core.RuntimeSnapshot;

/** Android-only presentation and acknowledgement flow for execution modes. */
final class ExecutionModeSettingsCard {
    interface ActiveModeListener {
        boolean onActiveModeRequested(
            String executionModeId,
            boolean dangerWarningAcknowledged
        );
    }

    interface ConfirmedLaunchListener {
        void onLaunchConfirmed(
            String executionModeId,
            boolean dangerWarningAcknowledged
        );
    }

    private final Activity activity;
    private final UiTheme theme;
    private final ActiveModeListener activeModeListener;
    private final LinearLayout card;
    private final TextView statusView;
    private final Button protectedButton;
    private final Button compatibilityButton;

    private String selectedModeId = CodexExecutionMode.PROTECTED_ID;
    private boolean runtimeReady;
    private boolean controlsEnabled = true;
    private AlertDialog warningDialog;

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
        statusView = theme.text("", 15, theme.primary);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(card, statusView, 12);

        protectedButton = theme.secondaryButton(
            activity.getString(R.string.execution_mode_protected_option)
        );
        protectedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseProtected();
            }
        });
        theme.addWithTopMargin(card, protectedButton, 14);

        compatibilityButton = theme.secondaryButton(
            activity.getString(R.string.execution_mode_compatibility_option)
        );
        compatibilityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseCompatibility();
            }
        });
        theme.addWithTopMargin(card, compatibilityButton, 8);
        updatePresentation();
    }

    LinearLayout getView() {
        return card;
    }

    void render(RuntimeSnapshot runtime, CodexSessionSnapshot session) {
        runtimeReady = runtime.getPhase() == RuntimePhase.READY && session.isReady();
        if (runtimeReady) {
            selectedModeId = session.getExecutionModeId();
        } else if (runtime.getPhase() == RuntimePhase.STARTING
            && !runtime.getExecutionModeId().isEmpty()) {
            selectedModeId = runtime.getExecutionModeId();
        }
        controlsEnabled = runtime.getPhase() != RuntimePhase.STARTING
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
        if (CodexExecutionMode.COMPATIBILITY_ID.equals(selectedModeId)) {
            showDangerWarning(listener);
            return;
        }
        listener.onLaunchConfirmed(CodexExecutionMode.PROTECTED_ID, false);
    }

    void dismiss() {
        if (warningDialog != null) {
            warningDialog.dismiss();
            warningDialog = null;
        }
    }

    private void chooseProtected() {
        if (!controlsEnabled || CodexExecutionMode.PROTECTED_ID.equals(selectedModeId)) {
            return;
        }
        if (runtimeReady) {
            activeModeListener.onActiveModeRequested(
                CodexExecutionMode.PROTECTED_ID,
                false
            );
            return;
        }
        selectedModeId = CodexExecutionMode.PROTECTED_ID;
        updatePresentation();
    }

    private void chooseCompatibility() {
        if (!controlsEnabled
            || CodexExecutionMode.COMPATIBILITY_ID.equals(selectedModeId)) {
            return;
        }
        if (!runtimeReady) {
            selectedModeId = CodexExecutionMode.COMPATIBILITY_ID;
            updatePresentation();
            return;
        }
        showDangerWarning(new ConfirmedLaunchListener() {
            @Override
            public void onLaunchConfirmed(
                String executionModeId,
                boolean dangerWarningAcknowledged
            ) {
                activeModeListener.onActiveModeRequested(
                    executionModeId,
                    dangerWarningAcknowledged
                );
            }
        });
    }

    private void showDangerWarning(final ConfirmedLaunchListener listener) {
        dismiss();
        warningDialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.execution_mode_warning_title)
            .setMessage(R.string.execution_mode_warning_message)
            .setNegativeButton(R.string.execution_mode_warning_cancel, null)
            .setPositiveButton(
                R.string.execution_mode_warning_confirm,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        warningDialog = null;
                        listener.onLaunchConfirmed(
                            CodexExecutionMode.COMPATIBILITY_ID,
                            true
                        );
                    }
                }
            )
            .create();
        warningDialog.show();
    }

    private void updatePresentation() {
        boolean compatibility = CodexExecutionMode.COMPATIBILITY_ID.equals(selectedModeId);
        statusView.setText(runtimeReady
            ? activity.getString(
                R.string.execution_mode_active,
                modeName(compatibility),
                permissionProfile(compatibility)
            )
            : activity.getString(
                R.string.execution_mode_next_start,
                modeName(compatibility),
                permissionProfile(compatibility)
            )
        );
        statusView.setTextColor(compatibility ? theme.danger : theme.primary);
        card.setBackground(theme.background(
            theme.surface,
            compatibility ? theme.danger : theme.border,
            18
        ));
        protectedButton.setText(activity.getString(
            compatibility
                ? R.string.execution_mode_protected_option
                : R.string.execution_mode_protected_selected
        ));
        compatibilityButton.setText(activity.getString(
            compatibility
                ? R.string.execution_mode_compatibility_selected
                : R.string.execution_mode_compatibility_option
        ));
        theme.setEnabled(protectedButton, controlsEnabled && compatibility);
        theme.setEnabled(compatibilityButton, controlsEnabled && !compatibility);
    }

    private String modeName(boolean compatibility) {
        return activity.getString(compatibility
            ? R.string.execution_mode_compatibility_name
            : R.string.execution_mode_protected_name
        );
    }

    private static String permissionProfile(boolean compatibility) {
        return compatibility
            ? CodexExecutionMode.COMPATIBILITY_PERMISSION_PROFILE_ID
            : CodexExecutionMode.PROTECTED_PERMISSION_PROFILE_ID;
    }
}
