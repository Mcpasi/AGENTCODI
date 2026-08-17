package de.agentcodi.app;

import android.app.Activity;
import android.content.Context;
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

import de.agentcodi.core.RuntimePhase;
import de.agentcodi.mcp.McpAppInfo;
import de.agentcodi.mcp.McpCatalogPhase;
import de.agentcodi.mcp.McpCatalogSnapshot;
import de.agentcodi.mcp.McpCatalogWarning;
import de.agentcodi.mcp.McpFeatureInfo;
import de.agentcodi.mcp.McpMarketplaceInfo;
import de.agentcodi.mcp.McpPluginInfo;
import de.agentcodi.mcp.McpServerInfo;
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
            render(snapshot);
            long delay = snapshot.getPhase() == McpCatalogPhase.LOADING
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
    private Button refreshButton;
    private long lastRevision = Long.MIN_VALUE;
    private McpCatalogPhase lastPhase;
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
        handler.removeCallbacks(refreshTask);
        if (AgentRuntimeService.snapshot().getPhase() == RuntimePhase.READY) {
            AgentRuntimeService.refreshMcpCatalog();
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
            }
        });
        theme.addWithTopMargin(statusCard, refreshButton, 14);
        theme.addWithTopMargin(page, statusCard, 18);

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
