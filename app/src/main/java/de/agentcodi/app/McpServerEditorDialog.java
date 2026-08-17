package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import de.agentcodi.core.CredentialGuard;
import de.agentcodi.mcp.McpServerConfiguration;
import de.agentcodi.mcp.McpServerDraft;
import de.agentcodi.mcp.McpTransport;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class McpServerEditorDialog {
    interface Listener {
        boolean onSave(McpServerDraft draft);
    }

    private McpServerEditorDialog() {
    }

    static void show(
        final Activity activity,
        final UiTheme theme,
        final McpServerConfiguration existing,
        final Listener listener
    ) {
        final boolean editing = existing != null;
        final ScrollView scroll = new ScrollView(activity);
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(12));
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView boundary = theme.text(
            activity.getString(R.string.mcp_editor_boundary),
            13,
            theme.secondary
        );
        boundary.setLineSpacing(0.0f, 1.18f);
        content.addView(boundary);

        final EditText name = field(activity, theme, false);
        name.setHint(activity.getString(R.string.mcp_editor_name_hint));
        name.setText(editing ? existing.getName() : "");
        name.setEnabled(!editing);
        addField(content, theme, R.string.mcp_editor_name, name);

        final Spinner transport = new Spinner(activity);
        String[] transportLabels = {
            activity.getString(R.string.mcp_transport_stdio),
            activity.getString(R.string.mcp_transport_http)
        };
        ArrayAdapter<String> transportAdapter = new ArrayAdapter<String>(
            activity,
            android.R.layout.simple_spinner_item,
            transportLabels
        );
        transportAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transport.setAdapter(transportAdapter);
        transport.setEnabled(!editing);
        if (editing && existing.getTransport() == McpTransport.STREAMABLE_HTTP) {
            transport.setSelection(1);
        }
        addField(content, theme, R.string.mcp_editor_transport, transport);

        final EditText endpoint = field(activity, theme, false);
        endpoint.setText(editing
            ? existing.getTransport() == McpTransport.STDIO
                ? existing.getCommand()
                : existing.getUrl()
            : "");
        addField(content, theme, R.string.mcp_editor_endpoint, endpoint);

        final LinearLayout argumentsGroup = new LinearLayout(activity);
        argumentsGroup.setOrientation(LinearLayout.VERTICAL);
        final EditText arguments = field(activity, theme, true);
        arguments.setHint(activity.getString(R.string.mcp_editor_args_hint));
        arguments.setText(editing ? join(existing.getArguments()) : "");
        addField(argumentsGroup, theme, R.string.mcp_editor_args, arguments);
        content.addView(argumentsGroup);

        final CheckBox enabled = new CheckBox(activity);
        enabled.setText(activity.getString(R.string.mcp_editor_enabled));
        enabled.setTextColor(theme.primary);
        enabled.setChecked(editing && existing.isEnabled());
        enabled.setEnabled(false);
        theme.addWithTopMargin(content, enabled, 12);

        final CheckBox required = new CheckBox(activity);
        required.setText(activity.getString(R.string.mcp_editor_required));
        required.setTextColor(theme.primary);
        required.setChecked(editing && existing.isRequired());
        required.setEnabled(editing);
        theme.addWithTopMargin(content, required, 4);

        if (!editing) {
            TextView disabledHint = theme.text(
                activity.getString(R.string.mcp_editor_new_disabled),
                12,
                theme.secondary
            );
            theme.addWithTopMargin(content, disabledHint, 2);
        } else {
            TextView activationHint = theme.text(
                activity.getString(R.string.mcp_editor_activation_separate),
                12,
                theme.secondary
            );
            theme.addWithTopMargin(content, activationHint, 2);
        }

        final EditText startupTimeout = numberField(activity, theme);
        startupTimeout.setText(Integer.toString(
            editing
                ? existing.getStartupTimeoutSeconds()
                : McpServerDraft.DEFAULT_STARTUP_TIMEOUT_SECONDS
        ));
        addField(content, theme, R.string.mcp_editor_startup_timeout, startupTimeout);

        final EditText toolTimeout = numberField(activity, theme);
        toolTimeout.setText(Integer.toString(
            editing
                ? existing.getToolTimeoutSeconds()
                : McpServerDraft.DEFAULT_TOOL_TIMEOUT_SECONDS
        ));
        addField(content, theme, R.string.mcp_editor_tool_timeout, toolTimeout);

        TextView approval = theme.body(activity.getString(R.string.mcp_editor_approval_fixed));
        addField(content, theme, R.string.mcp_editor_approval, approval);

        final EditText enabledTools = field(activity, theme, true);
        enabledTools.setHint(activity.getString(R.string.mcp_editor_tools_hint));
        enabledTools.setText(editing ? join(existing.getEnabledTools()) : "");
        addField(content, theme, R.string.mcp_editor_enabled_tools, enabledTools);

        final EditText disabledTools = field(activity, theme, true);
        disabledTools.setHint(activity.getString(R.string.mcp_editor_tools_hint));
        disabledTools.setText(editing ? join(existing.getDisabledTools()) : "");
        addField(content, theme, R.string.mcp_editor_disabled_tools, disabledTools);

        if (editing && existing.hasPreservedAdvancedFields()) {
            TextView advanced = theme.text(
                activity.getString(R.string.mcp_editor_advanced_preserved),
                12,
                theme.secondary
            );
            advanced.setLineSpacing(0.0f, 1.18f);
            theme.addWithTopMargin(content, advanced, 14);
        }

        final TextView error = theme.text("", 13, theme.danger);
        error.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(content, error, 12);

        final Runnable updateTransport = new Runnable() {
            @Override
            public void run() {
                boolean stdio = transport.getSelectedItemPosition() == 0;
                endpoint.setHint(activity.getString(
                    stdio ? R.string.mcp_editor_command_hint : R.string.mcp_editor_url_hint
                ));
                argumentsGroup.setVisibility(stdio ? View.VISIBLE : View.GONE);
            }
        };
        transport.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                AdapterView<?> parent,
                View view,
                int position,
                long id
            ) {
                updateTransport.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateTransport.run();
            }
        });
        updateTransport.run();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(
                editing ? R.string.mcp_editor_edit_title : R.string.mcp_editor_add_title
            ))
            .setView(scroll)
            .setPositiveButton(R.string.mcp_editor_save, null)
            .setNegativeButton(R.string.mcp_editor_cancel, null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            McpTransport selectedTransport = transport.getSelectedItemPosition() == 0
                                ? McpTransport.STDIO
                                : McpTransport.STREAMABLE_HTTP;
                            String endpointValue = endpoint.getText().toString().trim();
                            String argumentText = arguments.getText().toString();
                            String enabledToolText = enabledTools.getText().toString();
                            String disabledToolText = disabledTools.getText().toString();
                            if (CredentialGuard.containsLikelyCredential(endpointValue)
                                || CredentialGuard.containsLikelyCredential(argumentText)
                                || CredentialGuard.containsLikelyCredential(enabledToolText)
                                || CredentialGuard.containsLikelyCredential(disabledToolText)) {
                                endpoint.setText("");
                                arguments.setText("");
                                enabledTools.setText("");
                                disabledTools.setText("");
                                error.setText(R.string.mcp_editor_credential_rejected);
                                return;
                            }
                            if (selectedTransport == McpTransport.STREAMABLE_HTTP
                                && !isSafeHttpsUrl(endpointValue)) {
                                error.setText(R.string.mcp_editor_https_required);
                                return;
                            }
                            try {
                                McpServerDraft draft = new McpServerDraft(
                                    name.getText().toString().trim(),
                                    selectedTransport,
                                    selectedTransport == McpTransport.STDIO
                                        ? endpointValue : "",
                                    selectedTransport == McpTransport.STDIO
                                        ? lines(argumentText) : new ArrayList<String>(),
                                    selectedTransport == McpTransport.STREAMABLE_HTTP
                                        ? endpointValue : "",
                                    editing && enabled.isChecked(),
                                    required.isChecked(),
                                    parseTimeout(startupTimeout),
                                    parseTimeout(toolTimeout),
                                    "prompt",
                                    lines(enabledToolText),
                                    lines(disabledToolText)
                                );
                                if (!listener.onSave(draft)) {
                                    error.setText(R.string.mcp_editor_save_rejected);
                                    return;
                                }
                                dialog.dismiss();
                            } catch (RuntimeException validationError) {
                                error.setText(R.string.mcp_editor_invalid);
                            }
                        }
                    }
                );
            }
        });
        dialog.show();
    }

    private static EditText field(Activity activity, UiTheme theme, boolean multiline) {
        EditText field = new EditText(activity);
        field.setSaveEnabled(false);
        field.setTextColor(theme.primary);
        field.setHintTextColor(theme.secondary);
        field.setTextSize(14);
        field.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
        field.setBackground(theme.background(theme.surfaceRaised, theme.border, 10));
        if (multiline) {
            field.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
            field.setMinLines(3);
            field.setMaxLines(7);
        } else {
            field.setSingleLine(true);
            field.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            );
        }
        return field;
    }

    private static EditText numberField(Activity activity, UiTheme theme) {
        EditText field = field(activity, theme, false);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    private static void addField(
        LinearLayout parent,
        UiTheme theme,
        int labelResource,
        View field
    ) {
        TextView label = theme.text(
            parent.getContext().getString(labelResource),
            12,
            theme.secondary
        );
        label.setTypeface(Typeface.DEFAULT_BOLD);
        theme.addWithTopMargin(parent, label, 14);
        theme.addWithTopMargin(parent, field, 5);
    }

    private static List<String> lines(String value) {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        String[] split = (value == null ? "" : value).split("\\r?\\n", -1);
        for (String entry : split) {
            String normalized = entry.trim();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return new ArrayList<String>(values);
    }

    private static String join(List<String> values) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) {
                output.append('\n');
            }
            output.append(value);
        }
        return output.toString();
    }

    private static int parseTimeout(EditText field) {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Timeout is required");
        }
        return Integer.parseInt(value);
    }

    private static boolean isSafeHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        } catch (URISyntaxException error) {
            return false;
        }
    }
}
