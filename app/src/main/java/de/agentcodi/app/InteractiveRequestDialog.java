package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.CodexApprovalDecision;
import de.agentcodi.core.CodexFileChangeSummary;
import de.agentcodi.core.CodexInteractiveRequest;
import de.agentcodi.core.CodexNetworkPolicyAmendment;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.core.CodexUserInputOption;
import de.agentcodi.core.CodexUserInputQuestion;
import de.agentcodi.core.CredentialGuard;
import de.agentcodi.runtime.AgentRuntimeService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InteractiveRequestDialog {
    private static final int MAX_ANSWER_CHARACTERS = 16 * 1024;

    private final Activity activity;
    private final UiTheme theme;
    private final List<QuestionBinding> questionBindings = new ArrayList<QuestionBinding>();
    private AlertDialog dialog;
    private long activeRequestId = -1L;
    private String activeFingerprint = "";

    InteractiveRequestDialog(Activity activity, UiTheme theme) {
        this.activity = activity;
        this.theme = theme;
    }

    void render(CodexSessionSnapshot snapshot) {
        List<CodexInteractiveRequest> requests = snapshot.getInteractiveRequests();
        if (requests.isEmpty()) {
            dismissForLifecycle();
            return;
        }
        CodexInteractiveRequest request = requests.get(0);
        String fingerprint = fingerprint(request);
        if (activeRequestId == request.getRequestId()
            && activeFingerprint.equals(fingerprint)
            && dialog != null
            && dialog.isShowing()) {
            return;
        }
        dismissForLifecycle();
        if (activity.isFinishing()
            || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            return;
        }
        activeRequestId = request.getRequestId();
        activeFingerprint = fingerprint;
        if (request.getKind() == CodexInteractiveRequest.Kind.USER_INPUT) {
            showUserInput(request);
        } else {
            showApproval(request);
        }
    }

    void dismissForLifecycle() {
        clearQuestionInputs();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        activeRequestId = -1L;
        activeFingerprint = "";
    }

    private void showApproval(final CodexInteractiveRequest request) {
        final List<ApprovalAction> actions = approvalActions(request);
        ApprovalAction accept = findApprovalAction(actions, CodexApprovalDecision.ACCEPT);
        ApprovalAction decline = findApprovalAction(actions, CodexApprovalDecision.DECLINE);
        ApprovalAction cancel = findApprovalAction(actions, CodexApprovalDecision.CANCEL);

        if (request.getKind() == CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL
            && request.getFileChanges().isEmpty()) {
            showPendingFileChangeDetails(request, cancel);
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(approvalTitle(request))
            .setView(approvalContent(request, actions));
        if (accept != null) {
            builder.setPositiveButton(
                activity.getString(R.string.approval_allow),
                approvalClickListener(request, accept)
            );
        }
        builder.setNegativeButton(
            activity.getString(R.string.approval_decline),
            approvalClickListener(request, decline)
        );
        builder.setNeutralButton(
            activity.getString(R.string.approval_stop_turn),
            approvalClickListener(request, cancel)
        );

        final AlertDialog shown = builder.create();
        shown.setCancelable(false);
        shown.setCanceledOnTouchOutside(false);
        shown.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface source) {
                styleApprovalButton(shown.getButton(AlertDialog.BUTTON_POSITIVE), theme.accent);
                styleApprovalButton(shown.getButton(AlertDialog.BUTTON_NEGATIVE), theme.danger);
                styleApprovalButton(shown.getButton(AlertDialog.BUTTON_NEUTRAL), theme.secondary);
            }
        });
        dialog = shown;
        shown.show();
    }

    private void showPendingFileChangeDetails(
        final CodexInteractiveRequest request,
        ApprovalAction cancel
    ) {
        final AlertDialog shown = new AlertDialog.Builder(activity)
            .setTitle(R.string.approval_preview_loading_title)
            .setMessage(R.string.approval_preview_loading_message)
            .setNeutralButton(
                R.string.approval_stop_turn,
                approvalClickListener(request, cancel)
            )
            .create();
        shown.setCancelable(false);
        shown.setCanceledOnTouchOutside(false);
        shown.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface source) {
                styleApprovalButton(shown.getButton(AlertDialog.BUTTON_NEUTRAL), theme.danger);
            }
        });
        dialog = shown;
        shown.show();
    }

    private void styleApprovalButton(Button button, int textColor) {
        if (button == null) {
            return;
        }
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setMinHeight(theme.dp(48));
        button.setMinimumHeight(theme.dp(48));
    }

    private View approvalContent(
        final CodexInteractiveRequest request,
        List<ApprovalAction> actions
    ) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.dp(22), theme.dp(8), theme.dp(22), theme.dp(12));

        TextView details = theme.text(approvalDetails(request), 14, theme.primary);
        details.setTextIsSelectable(true);
        details.setLineSpacing(0.0f, 1.15f);
        if (!request.getCommand().isEmpty() || !request.getFileChanges().isEmpty()) {
            details.setTypeface(Typeface.MONOSPACE);
        }
        content.addView(details, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        boolean headingAdded = false;
        for (ApprovalAction action : actions) {
            if (action.decision == CodexApprovalDecision.ACCEPT
                || action.decision == CodexApprovalDecision.DECLINE
                || action.decision == CodexApprovalDecision.CANCEL) {
                continue;
            }
            if (!headingAdded) {
                TextView heading = theme.sectionLabel(
                    activity.getString(R.string.approval_more_decisions)
                );
                theme.addWithTopMargin(content, heading, 18);
                headingAdded = true;
            }
            Button button = theme.compactButton(action.label);
            final ApprovalAction selectedAction = action;
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    resolveApproval(request, selectedAction);
                }
            });
            theme.addWithTopMargin(content, button, 8);
        }

        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scroll;
    }

    private DialogInterface.OnClickListener approvalClickListener(
        final CodexInteractiveRequest request,
        final ApprovalAction action
    ) {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface source, int which) {
                resolveApproval(request, action);
            }
        };
    }

    private void resolveApproval(
        CodexInteractiveRequest request,
        ApprovalAction action
    ) {
        if (action == null) {
            return;
        }
        AgentRuntimeService.resolveApproval(
            request.getRequestId(),
            action.decision,
            action.amendmentIndex
        );
        finishCurrentDialog();
    }

    private static ApprovalAction findApprovalAction(
        List<ApprovalAction> actions,
        CodexApprovalDecision decision
    ) {
        for (ApprovalAction action : actions) {
            if (action.decision == decision) {
                return action;
            }
        }
        return null;
    }

    private void showUserInput(final CodexInteractiveRequest request) {
        questionBindings.clear();
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.dp(22), theme.dp(8), theme.dp(22), theme.dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView credentialWarning = theme.text(
            activity.getString(R.string.user_input_credential_warning),
            13,
            theme.danger
        );
        credentialWarning.setLineSpacing(0.0f, 1.15f);
        content.addView(credentialWarning);

        for (int index = 0; index < request.getQuestions().size(); index++) {
            CodexUserInputQuestion question = request.getQuestions().get(index);
            QuestionBinding binding = addQuestion(content, question, true);
            questionBindings.add(binding);
        }

        AlertDialog shown = new AlertDialog.Builder(activity)
            .setTitle(request.isBlocking()
                ? R.string.user_input_blocking_title
                : R.string.user_input_title)
            .setView(scroll)
            .setPositiveButton(R.string.user_input_answer, null)
            .setNegativeButton(R.string.user_input_without_answer, null)
            .create();
        shown.setCancelable(false);
        shown.setCanceledOnTouchOutside(false);
        shown.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface source) {
                Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positive.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Map<String, char[]> answers = collectAnswers();
                        if (answers == null) {
                            Toast.makeText(
                                activity,
                                R.string.user_input_answer_all,
                                Toast.LENGTH_SHORT
                            ).show();
                            return;
                        }
                        if (containsCredential(answers)) {
                            clearQuestionInputs();
                            wipeAnswers(answers);
                            Toast.makeText(
                                activity,
                                R.string.user_input_credential_warning,
                                Toast.LENGTH_LONG
                            ).show();
                            return;
                        }
                        clearQuestionInputs();
                        AgentRuntimeService.answerUserInput(request.getRequestId(), answers);
                        finishCurrentDialog();
                    }
                });
                Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                negative.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        clearQuestionInputs();
                        AgentRuntimeService.dismissUserInput(request.getRequestId());
                        finishCurrentDialog();
                    }
                });
            }
        });
        dialog = shown;
        shown.show();
    }

    private QuestionBinding addQuestion(
        LinearLayout content,
        CodexUserInputQuestion question,
        boolean addTopMargin
    ) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        if (addTopMargin) {
            block.setPadding(0, theme.dp(18), 0, 0);
        }

        TextView header = theme.sectionLabel(question.getHeader().toUpperCase());
        block.addView(header);
        TextView prompt = theme.text(question.getQuestion(), 15, theme.primary);
        prompt.setLineSpacing(0.0f, 1.18f);
        theme.addWithTopMargin(block, prompt, 5);

        RadioGroup choices = null;
        EditText customInput = null;
        int otherChoiceId = View.NO_ID;
        if (!question.getOptions().isEmpty()) {
            choices = new RadioGroup(activity);
            choices.setOrientation(RadioGroup.VERTICAL);
            for (CodexUserInputOption option : question.getOptions()) {
                RadioButton choice = new RadioButton(activity);
                choice.setId(View.generateViewId());
                choice.setTag(option.getLabel());
                choice.setText(optionLabel(option));
                choice.setTextColor(theme.primary);
                choice.setTextSize(14);
                choices.addView(choice);
            }
            if (question.isOtherAllowed()) {
                RadioButton other = new RadioButton(activity);
                other.setId(View.generateViewId());
                otherChoiceId = other.getId();
                other.setTag(OtherChoice.INSTANCE);
                other.setText(R.string.user_input_other);
                other.setTextColor(theme.primary);
                other.setTextSize(14);
                choices.addView(other);
                customInput = answerInput(question.isSecret(), false);
                customInput.setEnabled(false);
            }
            theme.addWithTopMargin(block, choices, 8);
            if (customInput != null) {
                theme.addWithTopMargin(block, customInput, 5);
            }
        } else {
            customInput = answerInput(question.isSecret(), true);
            theme.addWithTopMargin(block, customInput, 8);
        }

        final EditText watchedInput = customInput;
        final int watchedOtherChoiceId = otherChoiceId;
        if (choices != null && watchedInput != null) {
            choices.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    boolean enabled = checkedId == watchedOtherChoiceId;
                    watchedInput.setEnabled(enabled);
                    if (!enabled) {
                        watchedInput.getText().clear();
                    }
                }
            });
        }
        content.addView(block, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return new QuestionBinding(question, choices, customInput, otherChoiceId);
    }

    private EditText answerInput(boolean secret, boolean multiline) {
        EditText input = new EditText(activity);
        input.setHint(secret
            ? R.string.user_input_secret_hint
            : R.string.user_input_custom_hint);
        input.setHintTextColor(theme.secondary);
        input.setTextColor(theme.primary);
        input.setSaveEnabled(false);
        input.setMaxLines(multiline ? 5 : 2);
        if (secret) {
            input.setSingleLine(true);
            input.setInputType(
                InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
            if (Build.VERSION.SDK_INT >= 26) {
                input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                input.setAutofillHints(new String[0]);
            }
        } else {
            input.setInputType(
                InputType.TYPE_CLASS_TEXT
                    | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            );
        }
        return input;
    }

    private Map<String, char[]> collectAnswers() {
        Map<String, char[]> answers = new LinkedHashMap<String, char[]>();
        for (QuestionBinding binding : questionBindings) {
            char[] answer = answerFor(binding);
            if (answer == null) {
                wipeAnswers(answers);
                return null;
            }
            answers.put(binding.question.getId(), answer);
        }
        return answers;
    }

    private char[] answerFor(QuestionBinding binding) {
        if (binding.choices == null) {
            return charactersFrom(binding.customInput);
        }
        int checkedId = binding.choices.getCheckedRadioButtonId();
        if (checkedId == View.NO_ID) {
            return null;
        }
        View selected = binding.choices.findViewById(checkedId);
        if (selected == null) {
            return null;
        }
        if (checkedId == binding.otherChoiceId || selected.getTag() == OtherChoice.INSTANCE) {
            return charactersFrom(binding.customInput);
        }
        Object label = selected.getTag();
        return label instanceof String ? ((String) label).toCharArray() : null;
    }

    private static char[] charactersFrom(EditText input) {
        if (input == null) {
            return null;
        }
        Editable editable = input.getText();
        if (editable == null || editable.length() == 0
            || editable.length() > MAX_ANSWER_CHARACTERS) {
            return null;
        }
        char[] value = new char[editable.length()];
        boolean visibleCharacter = false;
        for (int index = 0; index < editable.length(); index++) {
            value[index] = editable.charAt(index);
            if (!Character.isWhitespace(value[index])) {
                visibleCharacter = true;
            }
        }
        if (!visibleCharacter) {
            Arrays.fill(value, '\0');
            return null;
        }
        return value;
    }

    private List<ApprovalAction> approvalActions(CodexInteractiveRequest request) {
        List<ApprovalAction> actions = new ArrayList<ApprovalAction>();
        boolean detailsAvailable = request.getKind()
            == CodexInteractiveRequest.Kind.FILE_CHANGE_APPROVAL
            ? !request.getFileChanges().isEmpty()
            : !request.getCommand().isEmpty() || !request.getNetworkHost().isEmpty();
        if (detailsAvailable) {
            actions.add(new ApprovalAction(
                activity.getString(R.string.approval_once),
                CodexApprovalDecision.ACCEPT,
                -1
            ));
            actions.add(new ApprovalAction(
                activity.getString(R.string.approval_session),
                CodexApprovalDecision.ACCEPT_FOR_SESSION,
                -1
            ));
        }
        if (detailsAvailable
            && request.getKind() == CodexInteractiveRequest.Kind.COMMAND_APPROVAL
            && !request.getProposedExecPolicyAmendment().isEmpty()) {
            actions.add(new ApprovalAction(
                activity.getString(R.string.approval_command_rule),
                CodexApprovalDecision.ACCEPT_WITH_EXEC_POLICY_AMENDMENT,
                -1
            ));
        }
        for (int index = 0; index < request.getProposedNetworkPolicyAmendments().size(); index++) {
            CodexNetworkPolicyAmendment amendment =
                request.getProposedNetworkPolicyAmendments().get(index);
            actions.add(new ApprovalAction(
                activity.getString(
                    R.string.approval_network_rule,
                    amendment.getAction().toUpperCase(),
                    amendment.getHost()
                ),
                CodexApprovalDecision.APPLY_NETWORK_POLICY_AMENDMENT,
                index
            ));
        }
        actions.add(new ApprovalAction(
            activity.getString(R.string.approval_decline_continue),
            CodexApprovalDecision.DECLINE,
            -1
        ));
        actions.add(new ApprovalAction(
            activity.getString(R.string.approval_decline_cancel),
            CodexApprovalDecision.CANCEL,
            -1
        ));
        return actions;
    }

    private String approvalTitle(CodexInteractiveRequest request) {
        if (!request.getNetworkHost().isEmpty()) {
            return activity.getString(R.string.approval_network_title);
        }
        return request.getKind() == CodexInteractiveRequest.Kind.COMMAND_APPROVAL
            ? activity.getString(R.string.approval_command_title)
            : activity.getString(R.string.approval_file_title);
    }

    private String approvalDetails(CodexInteractiveRequest request) {
        StringBuilder details = new StringBuilder();
        if (!request.getReason().isEmpty()) {
            details.append(request.getReason()).append("\n\n");
        }
        if (!request.getNetworkHost().isEmpty()) {
            details.append(activity.getString(R.string.approval_target)).append(": ")
                .append(request.getNetworkProtocol())
                .append("://")
                .append(request.getNetworkHost())
                .append('\n');
        }
        if (!request.getCommand().isEmpty()) {
            details.append(activity.getString(R.string.approval_command))
                .append(":\n").append(request.getCommand()).append('\n');
        }
        if (!request.getCwd().isEmpty()) {
            details.append(activity.getString(R.string.approval_cwd))
                .append(": ").append(request.getCwd()).append('\n');
        }
        if (!request.getGrantRoot().isEmpty()) {
            details.append(activity.getString(R.string.approval_write_scope)).append(": ")
                .append(request.getGrantRoot())
                .append('\n');
        }
        for (CodexFileChangeSummary change : request.getFileChanges()) {
            details.append('\n')
                .append(change.getKind().isEmpty()
                    ? activity.getString(R.string.approval_change)
                    : localizedFileChangeKind(change.getKind()))
                .append(" · ")
                .append(change.getPath());
            if (!change.getMovePath().isEmpty()) {
                details.append(" → ").append(change.getMovePath());
            }
            details.append('\n');
            if (!change.getDiff().isEmpty()) {
                details.append(change.getDiff()).append('\n');
            }
        }
        if (details.length() == 0) {
            details.append(activity.getString(R.string.approval_default_detail));
        }
        details.append("\n\n").append(activity.getString(R.string.approval_explanation));
        return details.toString();
    }

    private String localizedFileChangeKind(String kind) {
        if ("add".equalsIgnoreCase(kind)) {
            return activity.getString(R.string.card_change_add);
        }
        if ("delete".equalsIgnoreCase(kind)) {
            return activity.getString(R.string.card_change_delete);
        }
        if ("update".equalsIgnoreCase(kind)) {
            return activity.getString(R.string.card_change_update);
        }
        return kind;
    }

    private static String optionLabel(CodexUserInputOption option) {
        return option.getDescription().isEmpty()
            ? option.getLabel()
            : option.getLabel() + "\n" + option.getDescription();
    }

    private void clearQuestionInputs() {
        for (QuestionBinding binding : questionBindings) {
            if (binding.customInput != null) {
                binding.customInput.getText().clear();
            }
            if (binding.choices != null) {
                binding.choices.clearCheck();
            }
        }
        questionBindings.clear();
    }

    private void finishCurrentDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        activeRequestId = -1L;
        activeFingerprint = "";
    }

    private static String fingerprint(CodexInteractiveRequest request) {
        StringBuilder value = new StringBuilder()
            .append(request.getRequestId()).append('|')
            .append(request.getKind().name()).append('|')
            .append(request.getCommand()).append('|')
            .append(request.getNetworkHost()).append('|')
            .append(request.getFileChanges().size());
        for (CodexFileChangeSummary change : request.getFileChanges()) {
            value.append('|').append(change.getPath())
                .append(':').append(change.getKind())
                .append(':').append(change.getMovePath())
                .append(':').append(change.getDiff());
        }
        return value.toString();
    }

    private static void wipeAnswers(Map<String, char[]> answers) {
        for (char[] value : answers.values()) {
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
        answers.clear();
    }

    private static boolean containsCredential(Map<String, char[]> answers) {
        for (char[] value : answers.values()) {
            if (CredentialGuard.containsLikelyCredential(value)) {
                return true;
            }
        }
        return false;
    }

    private static final class ApprovalAction {
        private final String label;
        private final CodexApprovalDecision decision;
        private final int amendmentIndex;

        private ApprovalAction(
            String label,
            CodexApprovalDecision decision,
            int amendmentIndex
        ) {
            this.label = label;
            this.decision = decision;
            this.amendmentIndex = amendmentIndex;
        }
    }

    private static final class QuestionBinding {
        private final CodexUserInputQuestion question;
        private final RadioGroup choices;
        private final EditText customInput;
        private final int otherChoiceId;

        private QuestionBinding(
            CodexUserInputQuestion question,
            RadioGroup choices,
            EditText customInput,
            int otherChoiceId
        ) {
            this.question = question;
            this.choices = choices;
            this.customInput = customInput;
            this.otherChoiceId = otherChoiceId;
        }
    }

    private enum OtherChoice {
        INSTANCE
    }
}
