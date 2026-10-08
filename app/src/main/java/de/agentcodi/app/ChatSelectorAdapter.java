package de.agentcodi.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Compact labelled selection with full, wrapping names in the selection dialog. */
final class ChatSelectorAdapter extends ArrayAdapter<String> {
    private final UiTheme theme;
    private final String label;

    ChatSelectorAdapter(Context context, UiTheme theme, String label, List<String> values) {
        super(context, android.R.layout.simple_spinner_item, values);
        this.theme = theme;
        this.label = label;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(theme.dp(48));
        TextView heading = theme.text(label, 10, theme.secondary);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(heading);
        TextView value = theme.text(getItem(position), 14, theme.primary);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        Drawable chevron = getContext().getDrawable(R.drawable.ic_transcript_expand).mutate();
        chevron.setTint(theme.secondary);
        chevron.setBounds(0, 0, theme.dp(16), theme.dp(16));
        value.setCompoundDrawablesRelative(null, null, chevron, null);
        value.setCompoundDrawablePadding(theme.dp(4));
        row.addView(value);
        row.setContentDescription(label + ": " + getItem(position));
        return row;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        TextView value = theme.text(getItem(position), 16, theme.primary);
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setMinimumHeight(theme.dp(48));
        value.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(12));
        return value;
    }
}
