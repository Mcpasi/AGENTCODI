package de.agentcodi.app;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class UiTheme {
    final int page;
    final int surface;
    final int surfaceRaised;
    final int primary;
    final int secondary;
    final int accent;
    final int border;
    final int danger;
    final boolean dark;

    private final Context context;

    UiTheme(Context context) {
        this.context = context;
        int nightMode = context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK;
        dark = nightMode == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            page = 0xFF090E1A;
            surface = 0xFF131B2B;
            surfaceRaised = 0xFF1A2437;
            primary = 0xFFF4F7FB;
            secondary = 0xFFAAB5C7;
            accent = 0xFF2DD4BF;
            border = 0xFF2C3A50;
            danger = 0xFFFCA5A5;
        } else {
            page = 0xFFF4F6F9;
            surface = Color.WHITE;
            surfaceRaised = 0xFFF8FAFC;
            primary = 0xFF111827;
            secondary = 0xFF647084;
            accent = 0xFF0F766E;
            border = 0xFFDCE2EA;
            danger = 0xFFB91C1C;
        }
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    GradientDrawable background(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    TextView sectionLabel(String value) {
        TextView label = text(value, 12, secondary);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.1f);
        return label;
    }

    TextView body(String value) {
        TextView body = text(value, 15, primary);
        body.setLineSpacing(0.0f, 1.22f);
        return body;
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(background(surface, border, 18));
        return card;
    }

    Button primaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(background(accent, Color.TRANSPARENT, 13));
        return button;
    }

    Button secondaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(dark ? 0xFF99F6E4 : accent);
        button.setBackground(background(surfaceRaised, border, 13));
        return button;
    }

    Button compactButton(String label) {
        Button button = secondaryButton(label);
        button.setMinHeight(dp(42));
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setTextSize(14);
        return button;
    }

    void setEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.45f);
    }

    void addWithTopMargin(LinearLayout parent, View child, int marginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(marginDp);
        parent.addView(child, params);
    }

    private Button baseButton(String label) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(50));
        button.setMinimumHeight(dp(50));
        return button;
    }
}
