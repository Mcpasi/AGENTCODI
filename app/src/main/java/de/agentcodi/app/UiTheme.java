package de.agentcodi.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
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
    final int warning;
    final int info;
    final int code;
    final int codeText;
    final int diffAdded;
    final int diffAddedFill;
    final int diffRemoved;
    final int diffRemovedFill;
    final int diffMeta;
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
            warning = 0xFFFCD34D;
            info = 0xFF93C5FD;
            code = 0xFF0C1322;
            codeText = 0xFFDCE4F0;
            diffAdded = 0xFF86EFAC;
            diffAddedFill = 0xFF10301F;
            diffRemoved = 0xFFFCA5A5;
            diffRemovedFill = 0xFF351A1E;
            diffMeta = 0xFF7F8CA3;
        } else {
            page = 0xFFF4F6F9;
            surface = Color.WHITE;
            surfaceRaised = 0xFFF8FAFC;
            primary = 0xFF111827;
            secondary = 0xFF647084;
            accent = 0xFF0F766E;
            border = 0xFFDCE2EA;
            danger = 0xFFB91C1C;
            warning = 0xFF92400E;
            info = 0xFF1D4ED8;
            code = 0xFFF6F8FB;
            codeText = 0xFF1F2937;
            diffAdded = 0xFF15803D;
            diffAddedFill = 0xFFE6F7EC;
            diffRemoved = 0xFFB91C1C;
            diffRemovedFill = 0xFFFDECEC;
            diffMeta = 0xFF7A879B;
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

    int tintedSurface(int color, float amount) {
        return Color.rgb(
            Math.round(Color.red(surface) + (Color.red(color) - Color.red(surface)) * amount),
            Math.round(Color.green(surface) + (Color.green(color) - Color.green(surface)) * amount),
            Math.round(Color.blue(surface) + (Color.blue(color) - Color.blue(surface)) * amount)
        );
    }

    int blend(int base, int overlay, float amount) {
        return Color.rgb(
            Math.round(Color.red(base) + (Color.red(overlay) - Color.red(base)) * amount),
            Math.round(Color.green(base) + (Color.green(overlay) - Color.green(base)) * amount),
            Math.round(Color.blue(base) + (Color.blue(overlay) - Color.blue(base)) * amount)
        );
    }

    Drawable touchBackground(int fill, int stroke, int radiusDp) {
        GradientDrawable content = background(fill, stroke, radiusDp);
        GradientDrawable mask = background(Color.WHITE, Color.TRANSPARENT, radiusDp);
        return new RippleDrawable(
            ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000), content, mask
        );
    }

    private GradientDrawable accentGradient(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] { accent, shade(accent, 0.8f) }
        );
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private static int shade(int color, float factor) {
        int r = Math.round(Color.red(color) * factor);
        int g = Math.round(Color.green(color) * factor);
        int b = Math.round(Color.blue(color) * factor);
        return Color.rgb(r, g, b);
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

    /** Small uppercase pill used for card states, change kinds and counters. */
    TextView badge(String value, int color) {
        TextView badge = text(value, 11, color);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setLetterSpacing(0.06f);
        badge.setIncludeFontPadding(false);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        badge.setBackground(background(
            tintedSurface(color, dark ? 0.16f : 0.1f), Color.TRANSPARENT, 8
        ));
        return badge;
    }

    /** Monospaced block used for commands, output and diffs. */
    TextView codeBlock(String value, int sizeSp) {
        TextView block = text(value, sizeSp, codeText);
        block.setTypeface(Typeface.MONOSPACE);
        block.setLineSpacing(0.0f, 1.18f);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackground(background(code, blend(code, border, 0.6f), 12));
        return block;
    }

    GradientDrawable dotShape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    View statusDot(int color) {
        View indicator = new View(context);
        indicator.setBackground(dotShape(color));
        indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return indicator;
    }

    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(background(surface, border, 20));
        card.setElevation(dp(1));
        return card;
    }

    Button primaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(Color.WHITE);
        button.setBackground(accentGradient(16));
        button.setElevation(dp(3));
        return button;
    }

    Button secondaryButton(String label) {
        Button button = baseButton(label);
        button.setTextColor(dark ? 0xFF99F6E4 : accent);
        button.setBackground(background(surfaceRaised, border, 16));
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

    ImageButton iconButton(int iconResource, String description) {
        return baseIconButton(
            iconResource,
            description,
            surfaceRaised,
            border,
            dark ? 0xFF99F6E4 : accent
        );
    }

    ImageButton primaryIconButton(int iconResource, String description) {
        ImageButton button = baseIconButton(
            iconResource,
            description,
            accent,
            Color.TRANSPARENT,
            Color.WHITE
        );
        button.setBackground(ripple(accentGradient(16)));
        button.setElevation(dp(2));
        return button;
    }

    ImageButton dangerIconButton(int iconResource, String description) {
        return baseIconButton(
            iconResource,
            description,
            surfaceRaised,
            border,
            danger
        );
    }

    void setIcon(ImageButton button, int iconResource, String description) {
        button.setImageResource(iconResource);
        button.setContentDescription(description);
        button.setTooltipText(description);
    }

    void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.45f);
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

    private ImageButton baseIconButton(
        int iconResource,
        String description,
        int fill,
        int stroke,
        int iconColor
    ) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(iconResource);
        button.setColorFilter(iconColor);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(iconBackground(fill, stroke));
        button.setContentDescription(description);
        button.setTooltipText(description);
        return button;
    }

    private Drawable iconBackground(int fill, int stroke) {
        return ripple(background(fill, stroke, 16));
    }

    private Drawable ripple(GradientDrawable content) {
        GradientDrawable mask = background(Color.WHITE, Color.TRANSPARENT, 16);
        int rippleColor = dark ? 0x33FFFFFF : 0x22000000;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }
}
