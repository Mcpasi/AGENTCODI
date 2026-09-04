package de.agentcodi.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.agentcodi.core.CodexTranscriptItem;

final class TranscriptCardView extends LinearLayout {
    private final UiTheme theme;
    private final ImageView icon;
    private final TextView title;
    private final TextView status;
    private final TextView summary;
    private final TextView detailLabel;
    private final TextView detail;
    private CodexTranscriptItem boundItem;

    TranscriptCardView(Context context, UiTheme theme) {
        super(context);
        this.theme = theme;
        setOrientation(VERTICAL);
        setPadding(theme.dp(14), theme.dp(14), theme.dp(14), theme.dp(14));
        setSaveEnabled(false);
        setSaveFromParentEnabled(false);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        icon = new ImageView(context);
        icon.setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(theme.dp(36), theme.dp(36)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        title = theme.text("", 14, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextIsSelectable(true);
        labels.addView(title);
        status = theme.text("", 11, theme.secondary);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(theme.dp(7), theme.dp(2), theme.dp(7), theme.dp(2));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = theme.dp(4);
        labels.addView(status, statusParams);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        labelParams.setMarginStart(theme.dp(10));
        header.addView(labels, labelParams);
        addView(header);

        summary = theme.text("", 14, theme.primary);
        summary.setTextIsSelectable(true);
        summary.setLineSpacing(0.0f, 1.2f);
        theme.addWithTopMargin(this, summary, 12);

        detailLabel = theme.sectionLabel(context.getString(R.string.transcript_details));
        theme.addWithTopMargin(this, detailLabel, 12);
        detail = theme.text("", 13, theme.primary);
        detail.setTextIsSelectable(true);
        detail.setLineSpacing(0.0f, 1.22f);
        detail.setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10));
        detail.setBackground(theme.background(theme.surfaceRaised, Color.TRANSPARENT, 12));
        theme.addWithTopMargin(this, detail, 8);
    }

    boolean bind(CodexTranscriptItem item) {
        if (TranscriptCardPresentation.sameContent(boundItem, item)) {
            return false;
        }
        boundItem = item;
        int accent = kindColor(item);
        TranscriptCardPresentation.State state = TranscriptCardPresentation.state(item);
        int statusColor = statusColor(state);
        boolean needsAttention = state == TranscriptCardPresentation.State.FAILED
            || state == TranscriptCardPresentation.State.DECLINED
            || state == TranscriptCardPresentation.State.INTERRUPTED;
        setBackground(theme.background(
            theme.surface, needsAttention ? theme.tintedSurface(statusColor, 0.45f) : theme.border, 18
        ));
        icon.setImageResource(iconResource(item));
        icon.setColorFilter(accent);
        icon.setBackground(theme.background(
            theme.tintedSurface(accent, 0.12f), Color.TRANSPARENT, 11
        ));
        title.setText(UiText.cardTitle(getContext(), item));
        status.setText(statusLabel(state, item.getStatus()));
        status.setTextColor(statusColor);
        status.setBackground(theme.background(
            theme.tintedSurface(statusColor, theme.dark ? 0.1f : 0.06f),
            Color.TRANSPARENT,
            6
        ));
        status.setVisibility(state == TranscriptCardPresentation.State.NONE ? GONE : VISIBLE);

        String summaryText = UiText.cardSummary(getContext(), item);
        String detailText = UiText.cardDetail(getContext(), item.getDetail());
        if (summaryText.isEmpty() && detailText.isEmpty()
            && state == TranscriptCardPresentation.State.RUNNING) {
            summaryText = getContext().getString(R.string.transcript_receiving);
        }
        summary.setText(summaryText);
        summary.setVisibility(summaryText.isEmpty() ? GONE : VISIBLE);
        summary.setTypeface(TranscriptCardPresentation.monospaceSummary(item)
            ? Typeface.MONOSPACE : Typeface.DEFAULT);
        summary.setTextSize(TranscriptCardPresentation.monospaceSummary(item) ? 13 : 14);
        detail.setText(detailText);
        detail.setVisibility(detailText.isEmpty() ? GONE : VISIBLE);
        detail.setTypeface(TranscriptCardPresentation.monospaceDetail(item)
            ? Typeface.MONOSPACE : Typeface.DEFAULT);
        detailLabel.setVisibility(detailText.isEmpty() ? GONE : VISIBLE);
        return true;
    }

    private int kindColor(CodexTranscriptItem item) {
        if (item.getKind() == CodexTranscriptItem.Kind.REASONING) {
            return theme.dark ? 0xFFC4B5FD : 0xFF6D28D9;
        }
        if (item.getKind() == CodexTranscriptItem.Kind.PLAN) {
            return theme.dark ? 0xFF93C5FD : 0xFF1D4ED8;
        }
        return theme.accent;
    }

    private int statusColor(TranscriptCardPresentation.State state) {
        switch (state) {
            case FAILED:
                return theme.danger;
            case DECLINED:
            case INTERRUPTED:
                return theme.dark ? 0xFFFCD34D : 0xFF92400E;
            case RUNNING:
                return theme.dark ? 0xFF93C5FD : 0xFF1D4ED8;
            case COMPLETED:
                return theme.accent;
            default:
                return theme.secondary;
        }
    }

    private String statusLabel(TranscriptCardPresentation.State state, String raw) {
        switch (state) {
            case RUNNING:
                return getContext().getString(R.string.status_in_progress);
            case COMPLETED:
                return getContext().getString(R.string.status_completed);
            case FAILED:
                return getContext().getString(R.string.status_failed);
            case DECLINED:
                return getContext().getString(R.string.status_declined);
            case INTERRUPTED:
                return getContext().getString(R.string.status_interrupted);
            default:
                return raw;
        }
    }

    private int iconResource(CodexTranscriptItem item) {
        if (item.getKind() == CodexTranscriptItem.Kind.REASONING
            || "sleep".equals(item.getProtocolType())) {
            return R.drawable.ic_chat_hourglass;
        }
        if (item.getKind() == CodexTranscriptItem.Kind.PLAN
            || "enteredReviewMode".equals(item.getProtocolType())
            || "exitedReviewMode".equals(item.getProtocolType())) {
            return R.drawable.ic_chat_review;
        }
        if ("commandExecution".equals(item.getProtocolType())) {
            return R.drawable.ic_chat_terminal;
        }
        if ("fileChange".equals(item.getProtocolType())
            || "imageView".equals(item.getProtocolType())
            || "imageGeneration".equals(item.getProtocolType())) {
            return R.drawable.ic_chat_folder;
        }
        return R.drawable.ic_chat_connectors;
    }
}
