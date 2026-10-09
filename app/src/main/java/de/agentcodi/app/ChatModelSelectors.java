package de.agentcodi.app;

import android.app.Activity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;

import de.agentcodi.core.CodexModelOption;
import de.agentcodi.core.CodexReasoningOption;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.runtime.AgentRuntimeService;

import java.util.ArrayList;
import java.util.List;

/** Owns model/effort binding and preserves adapters across session refreshes. */
final class ChatModelSelectors {
    private final Activity activity;
    private final UiTheme theme;
    private final Spinner modelSpinner;
    private final Spinner effortSpinner;
    private final ChatScreenView detailsHost;
    private boolean bindingSelectors;
    private final List<String> renderedModelLabels = new ArrayList<String>();
    private final List<String> renderedEffortLabels = new ArrayList<String>();

    ChatModelSelectors(Activity activity, UiTheme theme, Spinner modelSpinner,
        Spinner effortSpinner, ChatScreenView detailsHost) {
        this.activity = activity;
        this.theme = theme;
        this.modelSpinner = modelSpinner;
        this.effortSpinner = effortSpinner;
        this.detailsHost = detailsHost;
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelectors) {
                    return;
                }
                CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
                List<CodexModelOption> models = snapshot.getModels();
                if (position >= 0 && position < models.size()
                    && !models.get(position).getId().equals(snapshot.getSelectedModelId())) {
                    AgentRuntimeService.selectModel(models.get(position).getId());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        effortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelectors) {
                    return;
                }
                CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
                CodexModelOption model = selectedModel(snapshot);
                if (model != null && position >= 0
                    && position < model.getReasoningOptions().size()
                    && !model.getReasoningOptions().get(position).getEffort().equals(
                        snapshot.getSelectedReasoningEffort()
                    )) {
                    AgentRuntimeService.selectReasoningEffort(model
                        .getReasoningOptions()
                        .get(position)
                        .getEffort());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

    }

    void showDetails() {
        CodexSessionSnapshot snapshot = AgentRuntimeService.sessionSnapshot();
        CodexModelOption model = selectedModel(snapshot);
        String description = selectorDescription(model, snapshot.getSelectedReasoningEffort());
        String selection = model == null ? "" : model.getDisplayName() + " · "
            + reasoningLabel(snapshot.getSelectedReasoningEffort()) + "\n\n";
        detailsHost.showChatDetails(activity.getString(R.string.chat_model_details), selection + description);
    }

    void bindSelectors(CodexSessionSnapshot session, boolean enabled) {
        bindingSelectors = true;
        try {
            List<String> modelLabels = new ArrayList<String>();
            int modelIndex = 0;
            for (int index = 0; index < session.getModels().size(); index++) {
                CodexModelOption model = session.getModels().get(index);
                modelLabels.add(model.getDisplayName());
                if (model.getId().equals(session.getSelectedModelId())) {
                    modelIndex = index;
                }
            }
            bindSelectorLabels(modelSpinner, R.string.model_section, modelLabels, renderedModelLabels);
            if (!modelLabels.isEmpty() && modelSpinner.getSelectedItemPosition() != modelIndex) {
                modelSpinner.setSelection(modelIndex, false);
            }

            CodexModelOption selected = selectedModel(session);
            List<String> effortLabels = new ArrayList<String>();
            int effortIndex = 0;
            if (selected != null) {
                for (int index = 0; index < selected.getReasoningOptions().size(); index++) {
                    CodexReasoningOption option = selected.getReasoningOptions().get(index);
                    effortLabels.add(reasoningLabel(option.getEffort()));
                    if (option.getEffort().equals(session.getSelectedReasoningEffort())) {
                        effortIndex = index;
                    }
                }
            }
            bindSelectorLabels(
                effortSpinner, R.string.chat_reasoning_label, effortLabels, renderedEffortLabels
            );
            if (!effortLabels.isEmpty() && effortSpinner.getSelectedItemPosition() != effortIndex) {
                effortSpinner.setSelection(effortIndex, false);
            }
            modelSpinner.setEnabled(enabled && !modelLabels.isEmpty());
            effortSpinner.setEnabled(enabled && !effortLabels.isEmpty());
            modelSpinner.setAlpha(modelSpinner.isEnabled() ? 1.0f : 0.5f);
            effortSpinner.setAlpha(effortSpinner.isEnabled() ? 1.0f : 0.5f);
            String modelSelection = activity.getString(R.string.model_section) + ": "
                + (selected == null ? activity.getString(R.string.models_unavailable) : selected.getDisplayName());
            String effortSelection = activity.getString(R.string.reasoning_effort_section) + ": "
                + reasoningLabel(session.getSelectedReasoningEffort());
            modelSpinner.setContentDescription(modelSelection);
            modelSpinner.setTooltipText(modelSelection);
            effortSpinner.setContentDescription(effortSelection);
            effortSpinner.setTooltipText(effortSelection);
        } finally {
            bindingSelectors = false;
        }
    }

    private void bindSelectorLabels(
        Spinner spinner, int labelResource, List<String> labels, List<String> renderedLabels
    ) {
        if (spinner.getAdapter() == null || !renderedLabels.equals(labels)) {
            renderedLabels.clear();
            renderedLabels.addAll(labels);
            List<String> displayLabels = new ArrayList<String>(labels);
            if (displayLabels.isEmpty()) {
                displayLabels.add("—");
            }
            spinner.setAdapter(new ChatSelectorAdapter(
                activity, theme, activity.getString(labelResource), displayLabels
            ));
        }
    }

    private static CodexModelOption selectedModel(CodexSessionSnapshot session) {
        for (CodexModelOption model : session.getModels()) {
            if (model.getId().equals(session.getSelectedModelId())) {
                return model;
            }
        }
        return null;
    }

    private String selectorDescription(CodexModelOption model, String effort) {
        if (model == null) {
            return activity.getString(R.string.models_unavailable);
        }
        String effortDescription = "";
        for (CodexReasoningOption option : model.getReasoningOptions()) {
            if (option.getEffort().equals(effort)) {
                effortDescription = option.getDescription();
                break;
            }
        }
        StringBuilder value = new StringBuilder(model.getDescription());
        if (!effortDescription.isEmpty()) {
            if (value.length() != 0) {
                value.append(" · ");
            }
            value.append(effortDescription);
        }
        return value.toString();
    }

    private String reasoningLabel(String effort) {
        if ("low".equals(effort)) {
            return activity.getString(R.string.reasoning_low);
        }
        if ("medium".equals(effort)) {
            return activity.getString(R.string.reasoning_medium);
        }
        if ("high".equals(effort)) {
            return activity.getString(R.string.reasoning_high);
        }
        if ("xhigh".equals(effort)) {
            return activity.getString(R.string.reasoning_xhigh);
        }
        if ("max".equals(effort)) {
            return activity.getString(R.string.reasoning_max);
        }
        if ("ultra".equals(effort)) {
            return activity.getString(R.string.reasoning_ultra);
        }
        return effort;
    }

}
