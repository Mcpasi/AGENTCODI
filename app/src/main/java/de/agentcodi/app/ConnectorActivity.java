package de.agentcodi.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final int MAXIMUM_AUTOMATIC_CONNECTION_CHECKS = 2;
    private static final long AUTOMATIC_CONNECTION_RETRY_DELAY_MS = 1_500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<ConnectorProvider, ConnectorSelection> selected =
        new EnumMap<ConnectorProvider, ConnectorSelection>(ConnectorProvider.class);
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            ConnectorCatalogSnapshot catalog = AgentRuntimeService.connectorCatalogSnapshot();
            CodexSessionSnapshot session = AgentRuntimeService.sessionSnapshot();
            render(catalog, session);
            continuePendingConnectionChecks(catalog);
            long delay = catalog.getPhase() == ConnectorPhase.LOADING
                || session.isOperationActive() || hasActiveConnectionCheck()
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
    private ConnectorProvider pendingConnectionProvider;
    private boolean pendingConnectionChecksStarted;
    private int pendingConnectionChecksRemaining;
    private long pendingConnectionCheckRevision = -1L;
    private long pendingConnectionRetryAtMillis = Long.MAX_VALUE;
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
        if (pendingConnectionProvider == null) {
            AgentRuntimeService.refreshConnectorCatalog(false, false);
        } else {
            beginAutomaticConnectionChecks();
        }
        handler.post(refreshTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingConnectionProvider != null && !pendingConnectionChecksStarted) {
            beginAutomaticConnectionChecks();
            lastCatalogSnapshot = null;
        }
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
        clearPendingConnection();
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
                if (pendingConnectionProvider != null) {
                    beginAutomaticConnectionChecks();
                } else {
                    AgentRuntimeService.refreshConnectorCatalog(true, true);
                }
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

        if (catalog.getPhase() != ConnectorPhase.LOADING) {
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
        }

        completePendingConnection(catalog, contextValid);

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
        if (pendingConnectionProvider != null) {
            if (phase == ConnectorPhase.LOADING || hasActiveConnectionCheck()) {
                return getString(
                    R.string.connector_connection_checking,
                    pendingConnectionProvider.getDisplayName()
                );
            }
            if (phase == ConnectorPhase.READY || phase == ConnectorPhase.PARTIAL
                || phase == ConnectorPhase.FAILED) {
                return getString(
                    R.string.connector_connection_waiting,
                    pendingConnectionProvider.getDisplayName()
                );
            }
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

        final boolean isSelected = selected.containsKey(connector.getProvider());
        TextView status = theme.text(
            connectorState(connector, isSelected),
            13,
            theme.secondary
        );
        status.setLineSpacing(0.0f, 1.15f);
        theme.addWithTopMargin(card, status, 7);
        if (!connector.getDescription().isEmpty()) {
            TextView description = theme.text(connector.getDescription(), 13, theme.secondary);
            description.setLineSpacing(0.0f, 1.15f);
            theme.addWithTopMargin(card, description, 8);
        }

        boolean addedAction = false;
        if (connector.isCallable()) {
            Button useAction = isSelected
                ? theme.secondaryButton(getString(R.string.connector_remove_from_chat))
                : theme.primaryButton(getString(R.string.connector_add_to_chat));
            useAction.setOnClickListener(new View.OnClickListener() {
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
            useAction.setEnabled(contextValid);
            theme.addWithTopMargin(card, useAction, 12);
            addedAction = true;
        }
        if (connector.hasTrustedInstallUrl()) {
            Button signInAction = connector.isCallable()
                ? theme.secondaryButton(getString(
                    R.string.connector_manage_sign_in_provider,
                    connector.getProvider().getDisplayName()
                ))
                : theme.primaryButton(getString(
                    R.string.connector_sign_in_provider,
                    connector.getProvider().getDisplayName()
                ));
            signInAction.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openConnectorSignIn(connector);
                }
            });
            signInAction.setEnabled(contextValid);
            theme.addWithTopMargin(card, signInAction, 12);
            addedAction = true;
        }
        if (!addedAction) {
            Button unavailableAction = theme.secondaryButton(
                getString(R.string.common_not_available)
            );
            unavailableAction.setEnabled(false);
            theme.addWithTopMargin(card, unavailableAction, 12);
        }
        theme.addWithTopMargin(connectorList, card, 10);
    }

    private String connectorState(ConnectorInfo connector, boolean isSelected) {
        if (!connector.isOffered()) {
            return getString(R.string.connector_not_offered);
        }
        if (connector.isCallable()) {
            return getString(
                isSelected
                    ? R.string.connector_selected_ready
                    : R.string.connector_connected_ready
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

    private void openConnectorSignIn(ConnectorInfo connector) {
        String url = connector.getInstallUrl();
        if (!ConnectorInstallUrl.isTrusted(url)) {
            Toast.makeText(this, R.string.connector_url_rejected, Toast.LENGTH_LONG).show();
            return;
        }
        pendingConnectionProvider = connector.getProvider();
        pendingConnectionChecksStarted = false;
        pendingConnectionChecksRemaining = 0;
        pendingConnectionCheckRevision = -1L;
        pendingConnectionRetryAtMillis = Long.MAX_VALUE;
        lastCatalogSnapshot = null;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable error) {
            clearPendingConnection();
            Toast.makeText(this, R.string.connector_page_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void beginAutomaticConnectionChecks() {
        if (pendingConnectionProvider == null) {
            return;
        }
        pendingConnectionChecksStarted = true;
        pendingConnectionChecksRemaining = MAXIMUM_AUTOMATIC_CONNECTION_CHECKS;
        pendingConnectionCheckRevision = -1L;
        pendingConnectionRetryAtMillis = SystemClock.elapsedRealtime();
        continuePendingConnectionChecks(AgentRuntimeService.connectorCatalogSnapshot());
    }

    private void continuePendingConnectionChecks(ConnectorCatalogSnapshot catalog) {
        if (pendingConnectionProvider == null || !pendingConnectionChecksStarted) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (pendingConnectionCheckRevision >= 0L) {
            if (catalog.getRevision() < pendingConnectionCheckRevision
                || (catalog.getRevision() == pendingConnectionCheckRevision
                    && catalog.getPhase() == ConnectorPhase.LOADING)) {
                return;
            }
            if (catalog.getRevision() > pendingConnectionCheckRevision
                && catalog.getPhase() == ConnectorPhase.LOADING) {
                pendingConnectionCheckRevision = catalog.getRevision();
                return;
            }
            pendingConnectionCheckRevision = -1L;
            pendingConnectionRetryAtMillis = pendingConnectionChecksRemaining > 0
                ? now + AUTOMATIC_CONNECTION_RETRY_DELAY_MS
                : Long.MAX_VALUE;
            return;
        }
        if (catalog.getPhase() == ConnectorPhase.LOADING) {
            // A refresh that began before the browser return is not proof of
            // the completed sign-in. Wait for it to settle, then start one of
            // this return flow's explicitly forced checks below.
            return;
        }
        if (pendingConnectionChecksRemaining <= 0
            || now < pendingConnectionRetryAtMillis) {
            return;
        }
        ConnectorInfo pendingConnector = catalog.find(pendingConnectionProvider);
        boolean needsFreshDirectory = !catalog.hasReusableDirectoryState()
            || !pendingConnector.isOffered()
            || !pendingConnector.isAccessible();
        boolean began = needsFreshDirectory
            ? AgentRuntimeService.refreshConnectorCatalog(true, true)
            : AgentRuntimeService.refreshConnectorAvailability(true);
        if (!began && !needsFreshDirectory) {
            began = AgentRuntimeService.refreshConnectorCatalog(true, true);
        }
        pendingConnectionChecksRemaining--;
        if (began) {
            ConnectorCatalogSnapshot started =
                AgentRuntimeService.connectorCatalogSnapshot();
            pendingConnectionCheckRevision = started.getRevision();
            pendingConnectionRetryAtMillis = Long.MAX_VALUE;
        } else {
            pendingConnectionRetryAtMillis = pendingConnectionChecksRemaining > 0
                ? now + AUTOMATIC_CONNECTION_RETRY_DELAY_MS
                : Long.MAX_VALUE;
        }
        lastCatalogSnapshot = null;
    }

    private boolean hasActiveConnectionCheck() {
        return pendingConnectionProvider != null && pendingConnectionChecksStarted
            && (pendingConnectionCheckRevision >= 0L
                || (pendingConnectionChecksRemaining > 0
                    && pendingConnectionRetryAtMillis != Long.MAX_VALUE));
    }

    private void clearPendingConnection() {
        pendingConnectionProvider = null;
        pendingConnectionChecksStarted = false;
        pendingConnectionChecksRemaining = 0;
        pendingConnectionCheckRevision = -1L;
        pendingConnectionRetryAtMillis = Long.MAX_VALUE;
    }

    private void completePendingConnection(
        ConnectorCatalogSnapshot catalog,
        boolean contextValid
    ) {
        if (!contextValid || pendingConnectionProvider == null
            || pendingConnectionCheckRevision < 0L
            || catalog.getRevision() < pendingConnectionCheckRevision
            || (catalog.getPhase() != ConnectorPhase.READY
                && catalog.getPhase() != ConnectorPhase.PARTIAL)) {
            return;
        }
        ConnectorInfo connector = catalog.find(pendingConnectionProvider);
        if (!connector.isCallable()) {
            return;
        }
        List<ConnectorSelection> updated;
        try {
            updated = ConnectorSelection.afterSuccessfulConnection(
                currentSelections(),
                pendingConnectionProvider,
                connector
            );
        } catch (RuntimeException error) {
            clearPendingConnection();
            Toast.makeText(this, R.string.chat_connector_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        selected.clear();
        for (ConnectorSelection selection : updated) {
            selected.put(selection.getProvider(), selection);
        }
        String providerName = pendingConnectionProvider.getDisplayName();
        clearPendingConnection();
        Toast.makeText(
            this,
            getString(R.string.connector_connected_selected, providerName),
            Toast.LENGTH_LONG
        ).show();
    }

    private List<ConnectorSelection> currentSelections() {
        List<ConnectorSelection> result = new ArrayList<ConnectorSelection>();
        for (ConnectorProvider provider : ConnectorProvider.values()) {
            ConnectorSelection selection = selected.get(provider);
            if (selection != null) {
                result.add(selection);
            }
        }
        return ConnectorSelection.copyOf(result);
    }

    private void finishWithSelection() {
        CodexSessionSnapshot session = AgentRuntimeService.sessionSnapshot();
        if (!session.isReady() || !launchThreadId.equals(session.getActiveThreadId())) {
            Toast.makeText(this, R.string.connector_context_changed, Toast.LENGTH_LONG).show();
            return;
        }
        List<ConnectorSelection> result = currentSelections();
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
