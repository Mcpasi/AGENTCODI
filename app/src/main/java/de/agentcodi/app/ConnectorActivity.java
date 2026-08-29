package de.agentcodi.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
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

import de.agentcodi.connectors.ConnectorCatalogSnapshot;
import de.agentcodi.connectors.ConnectorInfo;
import de.agentcodi.connectors.ConnectorInstallUrl;
import de.agentcodi.connectors.ConnectorPhase;
import de.agentcodi.connectors.ConnectorProvider;
import de.agentcodi.connectors.ConnectorSelection;
import de.agentcodi.core.CodexSessionSnapshot;
import de.agentcodi.runtime.AgentRuntimeService;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ConnectorActivity extends Activity {
    private static final String EXTRA_THREAD_ID =
        "de.agentcodi.app.connector.THREAD_ID";
    private static final String EXTRA_PROVIDERS =
        "de.agentcodi.app.connector.PROVIDERS";
    private static final String EXTRA_IDS =
        "de.agentcodi.app.connector.IDS";
    private static final String EXTRA_NAMES =
        "de.agentcodi.app.connector.NAMES";
    private static final long ACTIVE_REFRESH_INTERVAL_MS = 250L;
    private static final long IDLE_REFRESH_INTERVAL_MS = 900L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<ConnectorProvider, ConnectorSelection> selected =
        new EnumMap<ConnectorProvider, ConnectorSelection>(ConnectorProvider.class);
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            ConnectorCatalogSnapshot catalog = AgentRuntimeService.connectorCatalogSnapshot();
            CodexSessionSnapshot session = AgentRuntimeService.sessionSnapshot();
            render(catalog, session);
            long delay = catalog.getPhase() == ConnectorPhase.LOADING
                || session.isOperationActive()
                ? ACTIVE_REFRESH_INTERVAL_MS
                : IDLE_REFRESH_INTERVAL_MS;
            handler.postDelayed(this, delay);
        }
    };

    private UiTheme theme;
    private TextView statusView;
    private LinearLayout connectorList;
    private Button refreshButton;
    private Button doneButton;
    private String launchThreadId = "";
    private ConnectorCatalogSnapshot lastCatalogSnapshot;
    private long lastSessionRevision = Long.MIN_VALUE;

    public static Intent createIntent(
        Context context,
        String threadId,
        List<ConnectorSelection> selections
    ) {
        Intent intent = new Intent(context, ConnectorActivity.class);
        intent.putExtra(EXTRA_THREAD_ID, safeThreadId(threadId));
        putSelections(intent, ConnectorSelection.copyOf(selections));
        return intent;
    }

    public static String resultThreadId(Intent data) {
        return data == null ? "" : safeThreadId(data.getStringExtra(EXTRA_THREAD_ID));
    }

    public static List<ConnectorSelection> resultSelections(Intent data) {
        if (data == null) {
            return ConnectorSelection.copyOf(null);
        }
        String[] providers = data.getStringArrayExtra(EXTRA_PROVIDERS);
        String[] ids = data.getStringArrayExtra(EXTRA_IDS);
        String[] names = data.getStringArrayExtra(EXTRA_NAMES);
        if (providers == null || ids == null || names == null
            || providers.length != ids.length || ids.length != names.length
            || ids.length > ConnectorSelection.MAXIMUM_SELECTED) {
            throw new IllegalArgumentException("Connector result is malformed");
        }
        List<ConnectorSelection> selections = new ArrayList<ConnectorSelection>();
        for (int index = 0; index < ids.length; index++) {
            selections.add(new ConnectorSelection(
                ConnectorProvider.valueOf(providers[index]),
                ids[index],
                names[index]
            ));
        }
        return ConnectorSelection.copyOf(selections);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = new UiTheme(this);
        launchThreadId = safeThreadId(getIntent().getStringExtra(EXTRA_THREAD_ID));
        try {
            for (ConnectorSelection selection : resultSelections(getIntent())) {
                selected.put(selection.getProvider(), selection);
            }
        } catch (RuntimeException ignored) {
            selected.clear();
        }
        setContentView(buildContent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(refreshTask);
        lastCatalogSnapshot = null;
        lastSessionRevision = Long.MIN_VALUE;
        AgentRuntimeService.refreshConnectorCatalog(true);
        handler.post(refreshTask);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refreshTask);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        selected.clear();
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
        Button close = theme.compactButton(getString(R.string.connector_back));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(close);
        TextView title = theme.text(getString(R.string.connector_title), 26, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(14);
        topBar.addView(title, titleParams);
        page.addView(topBar);

        TextView subtitle = theme.text(
            getString(R.string.connector_subtitle),
            14,
            theme.secondary
        );
        subtitle.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(page, subtitle, 10);

        TextView boundary = theme.text(
            getString(R.string.connector_auth_boundary),
            13,
            theme.secondary
        );
        boundary.setLineSpacing(0.0f, 1.16f);
        theme.addWithTopMargin(page, boundary, 10);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusView = theme.text(getString(R.string.connector_loading), 13, theme.primary);
        statusRow.addView(statusView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        refreshButton = theme.compactButton(getString(R.string.connector_refresh));
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AgentRuntimeService.refreshConnectorCatalog(true);
                lastCatalogSnapshot = null;
            }
        });
        statusRow.addView(refreshButton);
        theme.addWithTopMargin(page, statusRow, 22);

        connectorList = new LinearLayout(this);
        connectorList.setOrientation(LinearLayout.VERTICAL);
        theme.addWithTopMargin(page, connectorList, 10);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = theme.secondaryButton(getString(R.string.common_cancel));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        actions.addView(cancel);
        doneButton = theme.primaryButton(getString(R.string.connector_done));
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finishWithSelection();
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        doneParams.leftMargin = theme.dp(10);
        actions.addView(doneButton, doneParams);
        theme.addWithTopMargin(page, actions, 24);
        return scroll;
    }

    private void render(
        ConnectorCatalogSnapshot catalog,
        CodexSessionSnapshot session
    ) {
        if (catalog == lastCatalogSnapshot
            && session.getRevision() == lastSessionRevision) {
            return;
        }
        lastCatalogSnapshot = catalog;
        lastSessionRevision = session.getRevision();
        boolean sessionContextValid = session.isReady()
            && launchThreadId.equals(session.getActiveThreadId());
        boolean catalogContextValid = launchThreadId.equals(catalog.getThreadId());
        boolean contextValid = sessionContextValid && catalogContextValid;

        for (ConnectorProvider provider : ConnectorProvider.values()) {
            ConnectorSelection selection = selected.get(provider);
            ConnectorInfo current = catalog.find(provider);
            if (selection != null
                && (!current.isCallable()
                    || !selection.getId().equals(current.getId())
                    || !selection.getName().equals(current.getName()))) {
                selected.remove(provider);
            }
        }

        statusView.setText(statusText(
            catalog.getPhase(),
            sessionContextValid,
            catalogContextValid
        ));
        refreshButton.setEnabled(
            session.isReady() && catalog.getPhase() != ConnectorPhase.LOADING
        );
        doneButton.setEnabled(contextValid && catalog.getPhase() != ConnectorPhase.LOADING);
        connectorList.removeAllViews();
        for (ConnectorProvider provider : ConnectorProvider.values()) {
            addConnectorCard(catalog.find(provider), contextValid);
        }
    }

    private String statusText(
        ConnectorPhase phase,
        boolean sessionContextValid,
        boolean catalogContextValid
    ) {
        if (!sessionContextValid) {
            return getString(R.string.connector_context_changed);
        }
        if (!catalogContextValid) {
            return getString(R.string.connector_loading);
        }
        switch (phase) {
            case LOADING:
                return getString(R.string.connector_loading);
            case READY:
                return getString(R.string.connector_ready);
            case PARTIAL:
                return getString(R.string.connector_partial);
            case FAILED:
                return getString(R.string.connector_failed);
            default:
                return getString(R.string.connector_stopped);
        }
    }

    private void addConnectorCard(final ConnectorInfo connector, boolean contextValid) {
        LinearLayout card = theme.card();
        TextView title = theme.text(connector.getProvider().getDisplayName(), 18, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView status = theme.text(connectorState(connector), 13, theme.secondary);
        status.setLineSpacing(0.0f, 1.15f);
        theme.addWithTopMargin(card, status, 7);
        if (!connector.getDescription().isEmpty()) {
            TextView description = theme.text(connector.getDescription(), 13, theme.secondary);
            description.setLineSpacing(0.0f, 1.15f);
            theme.addWithTopMargin(card, description, 8);
        }

        Button action;
        if (connector.isCallable()) {
            final boolean isSelected = selected.containsKey(connector.getProvider());
            action = isSelected
                ? theme.secondaryButton(getString(R.string.connector_remove_from_chat))
                : theme.primaryButton(getString(R.string.connector_add_to_chat));
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (selected.containsKey(connector.getProvider())) {
                        selected.remove(connector.getProvider());
                    } else {
                        selected.put(connector.getProvider(), connector.selection());
                    }
                    lastCatalogSnapshot = null;
                    render(
                        AgentRuntimeService.connectorCatalogSnapshot(),
                        AgentRuntimeService.sessionSnapshot()
                    );
                }
            });
        } else if (connector.hasTrustedInstallUrl()) {
            action = theme.primaryButton(getString(
                connector.isAccessible()
                    ? R.string.connector_manage_in_chatgpt
                    : R.string.connector_connect
            ));
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openHostedConnectorPage(connector.getInstallUrl());
                }
            });
        } else {
            action = theme.secondaryButton(getString(R.string.common_not_available));
            action.setEnabled(false);
        }
        action.setEnabled(contextValid && action.isEnabled());
        theme.addWithTopMargin(card, action, 12);
        theme.addWithTopMargin(connectorList, card, 10);
    }

    private String connectorState(ConnectorInfo connector) {
        if (!connector.isOffered()) {
            return getString(R.string.connector_not_offered);
        }
        if (connector.isCallable()) {
            return getString(
                R.string.connector_callable,
                Integer.valueOf(connector.getToolCount())
            );
        }
        if (!connector.isAccessible()) {
            return getString(R.string.connector_needs_connection);
        }
        if (!connector.isEnabled()) {
            return getString(R.string.connector_disabled);
        }
        if (!connector.isInstalled()) {
            return getString(R.string.connector_not_installed);
        }
        return getString(R.string.connector_needs_reauthentication);
    }

    private void openHostedConnectorPage(String url) {
        if (!ConnectorInstallUrl.isTrusted(url)) {
            Toast.makeText(this, R.string.connector_url_rejected, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable error) {
            Toast.makeText(this, R.string.connector_page_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void finishWithSelection() {
        CodexSessionSnapshot session = AgentRuntimeService.sessionSnapshot();
        if (!session.isReady() || !launchThreadId.equals(session.getActiveThreadId())) {
            Toast.makeText(this, R.string.connector_context_changed, Toast.LENGTH_LONG).show();
            return;
        }
        List<ConnectorSelection> result = new ArrayList<ConnectorSelection>();
        for (ConnectorProvider provider : ConnectorProvider.values()) {
            ConnectorSelection selection = selected.get(provider);
            if (selection != null) {
                result.add(selection);
            }
        }
        Intent data = new Intent();
        data.putExtra(EXTRA_THREAD_ID, launchThreadId);
        putSelections(data, ConnectorSelection.copyOf(result));
        setResult(RESULT_OK, data);
        finish();
    }

    private static void putSelections(Intent intent, List<ConnectorSelection> selections) {
        String[] providers = new String[selections.size()];
        String[] ids = new String[selections.size()];
        String[] names = new String[selections.size()];
        for (int index = 0; index < selections.size(); index++) {
            ConnectorSelection selection = selections.get(index);
            providers[index] = selection.getProvider().name();
            ids[index] = selection.getId();
            names[index] = selection.getName();
        }
        intent.putExtra(EXTRA_PROVIDERS, providers);
        intent.putExtra(EXTRA_IDS, ids);
        intent.putExtra(EXTRA_NAMES, names);
    }

    private static String safeThreadId(String value) {
        if (value == null || value.length() > 256) {
            return "";
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return "";
            }
        }
        return value;
    }
}
