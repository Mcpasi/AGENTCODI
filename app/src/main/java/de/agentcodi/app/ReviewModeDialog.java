package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.agentcodi.core.CredentialGuard;

/** Transient native UI for the single supported custom inline review target. */
final class ReviewModeDialog {
    interface Starter {
        boolean start(String instructions);
    }

    private ReviewModeDialog() {
    }

    static void show(
        final Activity activity,
        UiTheme theme,
        int maximumCharacters,
        final Starter starter
    ) {
        if (activity == null || theme == null || starter == null
            || maximumCharacters <= 0) {
            throw new IllegalArgumentException("Review dialog dependencies are invalid");
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = theme.dp(20);
        content.setPadding(horizontal, theme.dp(4), horizontal, 0);

        TextView description = theme.text(
            activity.getString(R.string.review_mode_description),
            14,
            theme.secondary
        );
        description.setLineSpacing(0.0f, 1.15f);
        content.addView(description, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final EditText instructions = new EditText(activity);
        instructions.setHint(R.string.review_mode_instructions_hint);
        instructions.setHintTextColor(theme.secondary);
        instructions.setTextColor(theme.primary);
        instructions.setMinLines(4);
        instructions.setMaxLines(10);
        instructions.setInputType(
            InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        instructions.setFilters(new InputFilter[] {
            new InputFilter.LengthFilter(maximumCharacters)
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputParams.topMargin = theme.dp(12);
        content.addView(instructions, inputParams);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.review_mode_title)
            .setView(content)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.review_mode_start, null)
            .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Editable editable = instructions.getText();
                            if (CredentialGuard.containsLikelyCredential(editable)) {
                                editable.clear();
                                instructions.setError(activity.getString(
                                    R.string.user_input_credential_warning
                                ));
                                return;
                            }
                            if (editable.toString().trim().isEmpty()) {
                                instructions.setError(
                                    activity.getString(
                                        R.string.review_mode_instructions_required
                                    )
                                );
                                return;
                            }
                            String value = editable.toString();
                            if (starter.start(value)) {
                                editable.clear();
                                dialog.dismiss();
                                return;
                            }
                            instructions.setError(
                                activity.getString(R.string.review_mode_start_rejected)
                            );
                        }
                    }
                );
            }
        });
        dialog.show();
        instructions.requestFocus();
    }
}
