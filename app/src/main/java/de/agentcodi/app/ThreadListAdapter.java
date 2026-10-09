package de.agentcodi.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.agentcodi.core.CodexThreadSummary;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Binds and recycles every loaded thread without redundant refreshes. */
final class ThreadListAdapter extends BaseAdapter {
    interface Actions {
        void showThreadActions(CodexThreadSummary thread);
    }

    private final Activity activity;
    private final UiTheme theme;
    private final Actions actions;

    private final List<CodexThreadSummary> values = new ArrayList<CodexThreadSummary>();
    private String activeId = "";
    private boolean enabled;
    private String fingerprint = "";

    ThreadListAdapter(Activity activity, UiTheme theme, Actions actions) {
        this.activity = activity;
        this.theme = theme;
        this.actions = actions;
    }

    void setData(List<CodexThreadSummary> threads, String activeThreadId, boolean rowsEnabled) {
        StringBuilder nextFingerprint = new StringBuilder();
        int count = threads.size();
        for (int index = 0; index < count; index++) {
            CodexThreadSummary value = threads.get(index);
            nextFingerprint.append(value.getId()).append('\0')
                .append(value.getTitle()).append('\0')
                .append(value.getUpdatedAtSeconds()).append('\0')
                .append(value.isArchived()).append('\1');
        }
        nextFingerprint.append('|').append(activeThreadId).append('|').append(rowsEnabled);
        if (nextFingerprint.toString().equals(fingerprint)) {
            return;
        }
        fingerprint = nextFingerprint.toString();
        values.clear();
        for (int index = 0; index < count; index++) {
            values.add(threads.get(index));
        }
        activeId = activeThreadId;
        enabled = rowsEnabled;
        notifyDataSetChanged();
    }

    CodexThreadSummary item(int position) {
        return values.get(position);
    }

    @Override
    public int getCount() {
        return values.size();
    }

    @Override
    public Object getItem(int position) {
        return item(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean isEnabled(int position) {
        return enabled;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ThreadRow row;
        if (convertView instanceof LinearLayout && convertView.getTag() instanceof ThreadRow) {
            row = (ThreadRow) convertView.getTag();
        } else {
            LinearLayout container = new LinearLayout(activity);
            container.setOrientation(LinearLayout.HORIZONTAL);
            container.setGravity(Gravity.CENTER_VERTICAL);
            container.setPadding(theme.dp(14), theme.dp(16), theme.dp(10), theme.dp(16));
            ImageView icon = new ImageView(activity);
            icon.setPadding(theme.dp(9), theme.dp(9), theme.dp(9), theme.dp(9));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            container.addView(icon, new LinearLayout.LayoutParams(
                theme.dp(40), theme.dp(40)
            ));
            LinearLayout textColumn = new LinearLayout(activity);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView title = theme.text("", 15, theme.primary);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setLineSpacing(0.0f, 1.12f);
            textColumn.addView(title);
            TextView metadata = theme.text("", 12, theme.secondary);
            metadata.setLineSpacing(0.0f, 1.15f);
            theme.addWithTopMargin(textColumn, metadata, 6);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f
            );
            textParams.setMarginStart(theme.dp(12));
            container.addView(textColumn, textParams);
            ImageButton action = theme.iconButton(
                R.drawable.ic_chat_more,
                activity.getString(R.string.chat_actions)
            );
            action.setFocusable(false);
            action.setColorFilter(theme.secondary);
            action.setBackground(theme.touchBackground(
                Color.TRANSPARENT, Color.TRANSPARENT, 14
            ));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            actionParams.setMarginStart(theme.dp(4));
            container.addView(action, actionParams);
            row = new ThreadRow(container, icon, title, metadata, action);
            container.setTag(row);
        }
        final CodexThreadSummary value = item(position);
        boolean active = !value.isArchived() && value.getId().equals(activeId);
        row.title.setText(UiText.threadTitle(activity, value.getTitle()));
        row.title.setContentDescription(row.title.getText());
        String updated = value.getUpdatedAtSeconds() <= 0
            ? activity.getString(R.string.chat_not_updated)
            : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(value.getUpdatedAtSeconds() * 1000L));
        if (value.isArchived()) {
            row.metadata.setText(activity.getString(R.string.chat_archived_metadata, updated));
        } else {
            row.metadata.setText(
                active ? activity.getString(R.string.chat_active_metadata, updated) : updated
            );
        }
        row.metadata.setTextColor(active ? theme.accent : theme.secondary);
        row.icon.setImageResource(value.isArchived()
            ? R.drawable.ic_chat_archived_threads : R.drawable.ic_chat_active_threads);
        row.icon.setColorFilter(active ? theme.accent : theme.secondary);
        row.icon.setBackground(theme.background(
            active ? theme.tintedSurface(theme.accent, 0.14f) : theme.surfaceRaised,
            Color.TRANSPARENT,
            13
        ));
        row.root.setActivated(active);
        row.root.setBackground(theme.background(
            active ? theme.tintedSurface(theme.accent, 0.05f) : theme.surface,
            active ? theme.accent : theme.border,
            20
        ));
        row.root.setAlpha(enabled ? 1.0f : 0.55f);
        row.action.setEnabled(enabled);
        theme.setIcon(
            row.action,
            R.drawable.ic_chat_more,
            activity.getString(
                R.string.chat_actions_for,
                UiText.threadTitle(activity, value.getTitle())
            )
        );
        row.action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                actions.showThreadActions(value);
            }
        });
        return row.root;
    }

    private static final class ThreadRow {
        private final LinearLayout root;
        private final ImageView icon;
        private final TextView title;
        private final TextView metadata;
        private final ImageButton action;

        private ThreadRow(
            LinearLayout root,
            ImageView icon,
            TextView title,
            TextView metadata,
            ImageButton action
        ) {
            this.root = root;
            this.icon = icon;
            this.title = title;
            this.metadata = metadata;
            this.action = action;
        }
    }
}
