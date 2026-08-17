package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.core.RuntimePhase;
import de.agentcodi.mcp.McpAppInfo;
import de.agentcodi.mcp.McpCatalogPhase;
import de.agentcodi.mcp.McpCatalogSnapshot;
import de.agentcodi.mcp.McpCatalogWarning;
import de.agentcodi.mcp.McpFeatureInfo;
import de.agentcodi.mcp.McpConfigurationNotice;
import de.agentcodi.mcp.McpConfigurationPhase;
import de.agentcodi.mcp.McpConfigurationSnapshot;
import de.agentcodi.mcp.McpMarketplaceInfo;
import de.agentcodi.mcp.McpPluginInfo;
import de.agentcodi.mcp.McpServerInfo;
import de.agentcodi.mcp.McpServerConfiguration;
import de.agentcodi.mcp.McpServerDraft;
import de.agentcodi.mcp.McpServerOrigin;
import de.agentcodi.mcp.McpSkillInfo;
import de.agentcodi.mcp.McpToolInfo;
import de.agentcodi.runtime.AgentRuntimeService;

import java.util.List;

public final class McpManagementActivity extends Activity {
    private static final long LOADING_REFRESH_INTERVAL_MS = 250L;
    private static final long IDLE_REFRESH_INTERVAL_MS = 1000L;
    private static final int MAX_SECTION_CHARACTERS = 32 * 1024;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            McpCatalogSnapshot snapshot = AgentRuntimeService.mcpCatalogSnapshot();
            McpConfigurationSnapshot configuration =
                AgentRuntimeService.mcpConfigurationSnapshot();
            render(snapshot);
            renderConfiguration(configuration);
            long delay = snapshot.getPhase() == McpCatalogPhase.LOADING
                    || configuration.isBusy()
                ? LOADING_REFRESH_INTERVAL_MS
                : IDLE_REFRESH_INTERVAL_MS;
            handler.postDelayed(this, delay);
        }
    };

    private UiTheme theme;
    private TextView statusView;
    private TextView summaryView;
    private TextView warningsView;
    private TextView featuresView;
    private TextView serversView;
    private TextView skillsView;
    private TextView appsView;
    private TextView marketplacesView;
    private TextView configurationStatusView;
    private TextView configurationNoticeView;
    private LinearLayout configurationList;
    private Button refreshButton;
    private Button addServerButton;
    private Button reloadServersButton;
    private long lastRevision = Long.MIN_VALUE;
    private McpCatalogPhase lastPhase;
    private long lastConfigurationRevision = Long.MIN_VALUE;
    private McpConfigurationPhase lastConfigurationPhase;
    private long catalogRefreshConfigurationRevision = Long.MIN_VALUE;
    private boolean destroyed;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = new UiTheme(this);
        setContentView(buildContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        lastRevision = Long.MIN_VALUE;
        lastPhase = null;
        lastConfigurationRevision = Long.MIN_VALUE;
        lastConfigurationPhase = null;
        handler.removeCallbacks(refreshTask);
        if (AgentRuntimeService.snapshot().getPhase() == RuntimePhase.READY) {
            AgentRuntimeService.refreshMcpCatalog();
            AgentRuntimeService.refreshMcpConfiguration();
        }
        handler.post(refreshTask);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTask);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(theme.page);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(theme.dp(18), theme.dp(20), theme.dp(18), theme.dp(36));
        scroll.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = theme.compactButton(getString(R.string.mcp_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(back);
        TextView title = theme.text(getString(R.string.mcp_title), 25, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(14);
        topBar.addView(title, titleParams);
        page.addView(topBar);

        TextView subtitle = theme.body(getString(R.string.mcp_subtitle));
        theme.addWithTopMargin(page, subtitle, 10);

        LinearLayout statusCard = theme.card();
        statusView = heading(getString(R.string.mcp_status_stopped));
        statusCard.addView(statusView);
        summaryView = theme.body(getString(R.string.mcp_summary_empty));
        theme.addWithTopMargin(statusCard, summaryView, 8);
        refreshButton = theme.primaryButton(getString(R.string.mcp_refresh));
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshMcpCatalog();
                AgentRuntimeService.refreshMcpConfiguration();
            }
        });
        theme.addWithTopMargin(statusCard, refreshButton, 14);
        theme.addWithTopMargin(page, statusCard, 18);

        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(R.string.mcp_configuration_section)),
            24
        );
        LinearLayout expertCard = theme.card();
        expertCard.addView(theme.body(getString(R.string.mcp_configuration_expert_warning)));
        theme.addWithTopMargin(page, expertCard, 8);

        LinearLayout configurationCard = theme.card();
        configurationStatusView = heading(getString(R.string.mcp_configuration_stopped));
        configurationCard.addView(configurationStatusView);
        configurationNoticeView = theme.text("", 13, theme.secondary);
        configurationNoticeView.setLineSpacing(0.0f, 1.16f);
        configurationNoticeView.setVisibility(View.GONE);
        theme.addWithTopMargin(configurationCard, configurationNoticeView, 8);
        addServerButton = theme.primaryButton(getString(R.string.mcp_configuration_add));
        addServerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showServerEditor(null);
            }
        });
        theme.addWithTopMargin(configurationCard, addServerButton, 14);
        reloadServersButton = theme.secondaryButton(
            getString(R.string.mcp_configuration_reload)
        );
        reloadServersButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!AgentRuntimeService.reloadMcpConfiguration()) {
                    showOperationRejected();
                }
            }
        });
        theme.addWithTopMargin(configurationCard, reloadServersButton, 8);
        theme.addWithTopMargin(page, configurationCard, 8);

        configurationList = new LinearLayout(this);
        configurationList.setOrientation(LinearLayout.VERTICAL);
        theme.addWithTopMargin(page, configurationList, 2);

        warningsView = addSection(
            page,
            R.string.mcp_warnings_section,
            getString(R.string.mcp_no_warnings)
        );
        featuresView = addSection(
            page,
            R.string.mcp_features_section,
            getString(R.string.mcp_empty_features)
        );
        serversView = addSection(
            page,
            R.string.mcp_servers_section,
            getString(R.string.mcp_empty_servers)
        );
        skillsView = addSection(
            page,
            R.string.mcp_skills_section,
            getString(R.string.mcp_empty_skills)
        );
        appsView = addSection(
            page,
            R.string.mcp_apps_section,
            getString(R.string.mcp_empty_apps)
        );
        marketplacesView = addSection(
            page,
            R.string.mcp_marketplaces_section,
            getString(R.string.mcp_empty_marketplaces)
        );

        LinearLayout boundaryCard = theme.card();
        boundaryCard.addView(theme.body(getString(R.string.mcp_boundary_description)));
        theme.addWithTopMargin(page, boundaryCard, 16);
        return scroll;
    }

    private TextView addSection(LinearLayout page, int labelResource, String initialValue) {
        theme.addWithTopMargin(
            page,
            theme.sectionLabel(getString(labelResource)),
            24
        );
        LinearLayout card = theme.card();
        TextView content = theme.body(initialValue);
        content.setTextIsSelectable(true);
        card.addView(content);
        theme.addWithTopMargin(page, card, 8);
        return content;
    }

    private TextView heading(String value) {
        TextView heading = theme.text(value, 18, theme.primary);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        return heading;
    }

    private void render(McpCatalogSnapshot snapshot) {
        if (snapshot.getRevision() == lastRevision && snapshot.getPhase() == lastPhase) {
            return;
        }
        lastRevision = snapshot.getRevision();
        lastPhase = snapshot.getPhase();

        statusView.setText(statusText(snapshot.getPhase()));
        summaryView.setText(getString(
            R.string.mcp_summary,
            Integer.valueOf(snapshot.getServers().size()),
            Integer.valueOf(snapshot.getToolCount()),
            Integer.valueOf(snapshot.getSkills().size()),
            Integer.valueOf(snapshot.getApps().size()),
            Integer.valueOf(snapshot.getPluginCount())
        ));
        boolean runtimeReady = AgentRuntimeService.snapshot().getPhase() == RuntimePhase.READY;
        theme.setEnabled(
            refreshButton,
            runtimeReady && snapshot.getPhase() != McpCatalogPhase.LOADING
        );
        refreshButton.setText(
            snapshot.getPhase() == McpCatalogPhase.LOADING
                ? getString(R.string.mcp_refreshing)
                : getString(R.string.mcp_refresh)
        );

        warningsView.setText(formatWarnings(snapshot.getWarnings()));
        featuresView.setText(formatFeatures(snapshot.getFeatures()));
        serversView.setText(formatServers(snapshot.getServers()));
        skillsView.setText(formatSkills(snapshot.getSkills()));
        appsView.setText(formatApps(snapshot.getApps()));
        marketplacesView.setText(formatMarketplaces(snapshot.getMarketplaces()));
    }

    private void renderConfiguration(McpConfigurationSnapshot snapshot) {
        if (snapshot.getPhase() == McpConfigurationPhase.READY
            && isSuccessfulNotice(snapshot.getNotice())
            && catalogRefreshConfigurationRevision != snapshot.getRevision()
            && AgentRuntimeService.mcpCatalogSnapshot().getPhase()
                != McpCatalogPhase.LOADING
            && AgentRuntimeService.refreshMcpCatalog()) {
            catalogRefreshConfigurationRevision = snapshot.getRevision();
        }
        if (snapshot.getRevision() == lastConfigurationRevision
            && snapshot.getPhase() == lastConfigurationPhase) {
            return;
        }
        lastConfigurationRevision = snapshot.getRevision();
        lastConfigurationPhase = snapshot.getPhase();
        configurationStatusView.setText(configurationStatusText(snapshot.getPhase()));
        String notice = configurationNoticeText(snapshot.getNotice());
        configurationNoticeView.setText(notice);
        configurationNoticeView.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);

        boolean runtimeReady = AgentRuntimeService.snapshot().getPhase() == RuntimePhase.READY;
        theme.setEnabled(
            addServerButton,
            runtimeReady && snapshot.getPhase() == McpConfigurationPhase.READY
                && snapshot.getServers().size() < 64
        );
        theme.setEnabled(
            reloadServersButton,
            runtimeReady && !snapshot.isBusy()
        );
        addServerButton.setText(
            snapshot.isBusy()
                ? getString(R.string.mcp_configuration_busy)
                : getString(R.string.mcp_configuration_add)
        );

        configurationList.removeAllViews();
        if (snapshot.getServers().isEmpty()) {
            LinearLayout empty = theme.card();
            empty.addView(theme.body(getString(R.string.mcp_configuration_empty)));
            theme.addWithTopMargin(configurationList, empty, 8);
        } else {
            boolean mutationsUnavailable =
                snapshot.getPhase() != McpConfigurationPhase.READY;
            for (McpServerConfiguration server : snapshot.getServers()) {
                addConfigurationCard(server, mutationsUnavailable);
            }
        }
    }

    private void addConfigurationCard(
        final McpServerConfiguration server,
        boolean mutationsUnavailable
    ) {
        LinearLayout card = theme.card();
        TextView name = heading(server.getName());
        card.addView(name);
        String endpoint;
        if (server.hasSensitiveValuesHidden()) {
            endpoint = getString(R.string.mcp_configuration_sensitive_hidden);
        } else if (server.getTransport() == de.agentcodi.mcp.McpTransport.STDIO) {
            endpoint = getString(R.string.mcp_configuration_stdio_summary, server.getCommand());
        } else if (server.getTransport() == de.agentcodi.mcp.McpTransport.STREAMABLE_HTTP) {
            endpoint = getString(R.string.mcp_configuration_http_summary, server.getUrl());
        } else {
            endpoint = getString(R.string.mcp_configuration_transport_unsupported);
        }
        TextView details = theme.body(
            endpoint + "\n"
                + enabledText(server.isEnabled()) + " · "
                + getString(
                    server.isRequired()
                        ? R.string.mcp_configuration_required
                        : R.string.mcp_configuration_optional
                ) + " · "
                + getString(
                    R.string.mcp_configuration_timeouts,
                    Integer.valueOf(server.getStartupTimeoutSeconds()),
                    Integer.valueOf(server.getToolTimeoutSeconds())
                ) + "\n"
                + getString(
                    R.string.mcp_configuration_approval_mode,
                    approvalModeText(server.getApprovalMode())
                ) + "\n"
                + originText(server.getOrigin())
        );
        details.setTextIsSelectable(true);
        theme.addWithTopMargin(card, details, 8);
        if (server.hasPreservedAdvancedFields()) {
            TextView advanced = theme.text(
                getString(R.string.mcp_configuration_advanced),
                12,
                theme.secondary
            );
            advanced.setLineSpacing(0.0f, 1.16f);
            theme.addWithTopMargin(card, advanced, 8);
        }

        if (server.isEditable()) {
            Button edit = theme.secondaryButton(getString(R.string.mcp_configuration_edit));
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showServerEditor(server);
                }
            });
            theme.setEnabled(edit, !mutationsUnavailable);
            theme.addWithTopMargin(card, edit, 12);
        }
        if (server.isUserOwned() && McpServerDraft.isSafeName(server.getName())) {
            Button toggle = theme.secondaryButton(getString(
                server.isEnabled()
                    ? R.string.mcp_configuration_disable
                    : R.string.mcp_configuration_enable
            ));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (!AgentRuntimeService.setMcpServerEnabled(
                            server.getName(),
                            !server.isEnabled())) {
                        showOperationRejected();
                    }
                }
            });
            theme.setEnabled(
                toggle,
                !mutationsUnavailable && (server.isEnabled()
                    || "prompt".equals(server.getApprovalMode()))
            );
            theme.addWithTopMargin(card, toggle, 8);

            Button delete = theme.secondaryButton(getString(R.string.mcp_configuration_delete));
            delete.setTextColor(theme.danger);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    confirmDelete(server);
                }
            });
            theme.setEnabled(delete, !mutationsUnavailable);
            theme.addWithTopMargin(card, delete, 8);
        }
        theme.addWithTopMargin(configurationList, card, 8);
    }

    private void showServerEditor(final McpServerConfiguration existing) {
        McpServerEditorDialog.show(
            this,
            theme,
            existing,
            new McpServerEditorDialog.Listener() {
                @Override
                public boolean onSave(McpServerDraft draft) {
                    return AgentRuntimeService.saveMcpServer(draft);
                }
            }
        );
    }

    private void confirmDelete(final McpServerConfiguration server) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.mcp_configuration_delete_title))
            .setMessage(getString(R.string.mcp_configuration_delete_message, server.getName()))
            .setPositiveButton(
                R.string.mcp_configuration_delete,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!AgentRuntimeService.deleteMcpServer(server.getName())) {
                            showOperationRejected();
                        }
                    }
                }
            )
            .setNegativeButton(R.string.mcp_editor_cancel, null)
            .show();
    }

    private void showOperationRejected() {
        Toast.makeText(
            this,
            getString(R.string.mcp_configuration_operation_rejected),
            Toast.LENGTH_SHORT
        ).show();
    }

    private String configurationStatusText(McpConfigurationPhase phase) {
        if (phase == McpConfigurationPhase.LOADING) {
            return getString(R.string.mcp_configuration_loading);
        }
        if (phase == McpConfigurationPhase.SAVING) {
            return getString(R.string.mcp_configuration_saving);
        }
        if (phase == McpConfigurationPhase.READY) {
            return getString(R.string.mcp_configuration_ready);
        }
        if (phase == McpConfigurationPhase.FAILED) {
            return getString(R.string.mcp_configuration_failed);
        }
        return getString(R.string.mcp_configuration_stopped);
    }

    private String configurationNoticeText(McpConfigurationNotice notice) {
        switch (notice) {
            case SAVED:
                return getString(R.string.mcp_configuration_saved);
            case ENABLED:
                return getString(R.string.mcp_configuration_enabled_notice);
            case DISABLED:
                return getString(R.string.mcp_configuration_disabled_notice);
            case DELETED:
                return getString(R.string.mcp_configuration_deleted_notice);
            case RELOADED:
                return getString(R.string.mcp_configuration_reloaded_notice);
            case APPLIED_OVERRIDDEN:
                return getString(R.string.mcp_configuration_applied_overridden);
            case READ_FAILED:
                return getString(R.string.mcp_configuration_read_failed);
            case WRITE_FAILED:
                return getString(R.string.mcp_configuration_write_failed);
            case RELOAD_REQUIRED:
                return getString(R.string.mcp_configuration_reload_required);
            case APPLIED_REFRESH_FAILED:
                return getString(R.string.mcp_configuration_applied_refresh_failed);
            case NONE:
            default:
                return "";
        }
    }

    private String originText(McpServerOrigin origin) {
        switch (origin) {
            case USER:
                return getString(R.string.mcp_origin_user);
            case PROJECT:
                return getString(R.string.mcp_origin_project);
            case MANAGED:
                return getString(R.string.mcp_origin_managed);
            case SESSION:
                return getString(R.string.mcp_origin_session);
            case MIXED:
                return getString(R.string.mcp_origin_mixed);
            case UNKNOWN:
            default:
                return getString(R.string.mcp_origin_unknown);
        }
    }

    private String approvalModeText(String approvalMode) {
        return "prompt".equals(approvalMode)
            ? getString(R.string.mcp_configuration_approval_prompt)
            : getString(R.string.mcp_configuration_approval_unsafe);
    }

    private static boolean isSuccessfulNotice(McpConfigurationNotice notice) {
        return notice == McpConfigurationNotice.SAVED
            || notice == McpConfigurationNotice.ENABLED
            || notice == McpConfigurationNotice.DISABLED
            || notice == McpConfigurationNotice.DELETED
            || notice == McpConfigurationNotice.RELOADED
            || notice == McpConfigurationNotice.APPLIED_OVERRIDDEN;
    }

    private String statusText(McpCatalogPhase phase) {
        if (phase == McpCatalogPhase.LOADING) {
            return getString(R.string.mcp_status_loading);
        }
        if (phase == McpCatalogPhase.READY) {
            return getString(R.string.mcp_status_ready);
        }
        if (phase == McpCatalogPhase.PARTIAL) {
            return getString(R.string.mcp_status_partial);
        }
        if (phase == McpCatalogPhase.FAILED) {
            return getString(R.string.mcp_status_failed);
        }
        return getString(R.string.mcp_status_stopped);
    }

    private String formatWarnings(List<McpCatalogWarning> warnings) {
        if (warnings.isEmpty()) {
            return getString(R.string.mcp_no_warnings);
        }
        StringBuilder output = new StringBuilder();
        for (McpCatalogWarning warning : warnings) {
            appendLine(output, "• " + warningText(warning));
        }
        return output.toString();
    }

    private String warningText(McpCatalogWarning warning) {
        switch (warning) {
            case FEATURES_UNAVAILABLE:
                return getString(R.string.mcp_warning_features);
            case SKILLS_UNAVAILABLE:
                return getString(R.string.mcp_warning_skills);
            case SKILL_LOAD_ERRORS:
                return getString(R.string.mcp_warning_skill_errors);
            case MCP_SERVERS_UNAVAILABLE:
                return getString(R.string.mcp_warning_servers);
            case APPS_UNAVAILABLE:
                return getString(R.string.mcp_warning_apps);
            case APP_DETAILS_INCOMPLETE:
                return getString(R.string.mcp_warning_app_details);
            case PLUGINS_UNAVAILABLE:
                return getString(R.string.mcp_warning_plugins);
            case MARKETPLACE_LOAD_ERRORS:
                return getString(R.string.mcp_warning_marketplaces);
            case CATALOG_TRUNCATED:
                return getString(R.string.mcp_warning_truncated);
            default:
                return getString(R.string.common_unknown_error);
        }
    }

    private String formatFeatures(List<McpFeatureInfo> features) {
        if (features.isEmpty()) {
            return getString(R.string.mcp_empty_features);
        }
        StringBuilder output = new StringBuilder();
        for (McpFeatureInfo feature : features) {
            String label = feature.getDisplayName().isEmpty()
                ? feature.getName()
                : feature.getDisplayName();
            appendLine(output, "• " + label + " · " + feature.getStage() + " · "
                + enabledText(feature.isEnabled()));
            appendDescription(output, feature.getDescription());
        }
        return output.toString();
    }

    private String formatServers(List<McpServerInfo> servers) {
        if (servers.isEmpty()) {
            return getString(R.string.mcp_empty_servers);
        }
        StringBuilder output = new StringBuilder();
        for (McpServerInfo server : servers) {
            String label = server.getTitle().isEmpty() ? server.getName() : server.getTitle();
            appendLine(output, "• " + label + " · "
                + getString(R.string.mcp_auth_value, server.getAuthStatus()) + " · "
                + getString(R.string.mcp_tool_count, Integer.valueOf(server.getTools().size())));
            appendDescription(output, server.getDescription());
            for (McpToolInfo tool : server.getTools()) {
                String toolLabel = tool.getTitle().isEmpty() ? tool.getName() : tool.getTitle();
                appendLine(output, "  ↳ " + toolLabel);
                appendDescription(output, tool.getDescription());
            }
        }
        return output.toString();
    }

    private String formatSkills(List<McpSkillInfo> skills) {
        if (skills.isEmpty()) {
            return getString(R.string.mcp_empty_skills);
        }
        StringBuilder output = new StringBuilder();
        for (McpSkillInfo skill : skills) {
            String label = skill.getDisplayName().isEmpty()
                ? skill.getName()
                : skill.getDisplayName();
            appendLine(output, "• " + label + " · " + skill.getScope() + " · "
                + enabledText(skill.isEnabled()) + " · "
                + getString(
                    R.string.mcp_dependency_count,
                    Integer.valueOf(skill.getToolDependencyCount())
                ));
            appendDescription(output, skill.getDescription());
        }
        return output.toString();
    }

    private String formatApps(List<McpAppInfo> apps) {
        if (apps.isEmpty()) {
            return getString(R.string.mcp_empty_apps);
        }
        StringBuilder output = new StringBuilder();
        for (McpAppInfo app : apps) {
            appendLine(output, "• " + app.getName() + " · " + enabledText(app.isEnabled())
                + " · " + (app.isCallable()
                    ? getString(R.string.mcp_callable)
                    : getString(R.string.mcp_not_callable)));
            for (McpToolInfo tool : app.getTools()) {
                String toolLabel = tool.getTitle().isEmpty() ? tool.getName() : tool.getTitle();
                appendLine(output, "  ↳ " + toolLabel + " · " + enabledText(tool.isEnabled()));
                appendDescription(output, tool.getDescription());
            }
        }
        return output.toString();
    }

    private String formatMarketplaces(List<McpMarketplaceInfo> marketplaces) {
        if (marketplaces.isEmpty()) {
            return getString(R.string.mcp_empty_marketplaces);
        }
        StringBuilder output = new StringBuilder();
        for (McpMarketplaceInfo marketplace : marketplaces) {
            String label = marketplace.getDisplayName().isEmpty()
                ? marketplace.getName()
                : marketplace.getDisplayName();
            appendLine(output, "• " + label + " · " + getString(
                R.string.mcp_plugin_count,
                Integer.valueOf(marketplace.getPlugins().size())
            ));
            for (McpPluginInfo plugin : marketplace.getPlugins()) {
                String pluginLabel = plugin.getDisplayName().isEmpty()
                    ? plugin.getName()
                    : plugin.getDisplayName();
                appendLine(output, "  ↳ " + pluginLabel + " · "
                    + (plugin.isInstalled()
                        ? getString(R.string.mcp_installed)
                        : getString(R.string.mcp_not_installed))
                    + " · " + enabledText(plugin.isEnabled()));
                appendDescription(output, plugin.getDescription());
            }
        }
        return output.toString();
    }

    private String enabledText(boolean enabled) {
        return enabled
            ? getString(R.string.mcp_enabled)
            : getString(R.string.mcp_disabled);
    }

    private void appendDescription(StringBuilder output, String description) {
        if (!description.isEmpty()) {
            appendLine(output, "    " + description);
        }
    }

    private static void appendLine(StringBuilder output, String value) {
        if (output.length() >= MAX_SECTION_CHARACTERS) {
            return;
        }
        if (output.length() > 0) {
            output.append('\n');
        }
        int remaining = MAX_SECTION_CHARACTERS - output.length();
        output.append(value, 0, Math.min(value.length(), remaining));
    }
}
