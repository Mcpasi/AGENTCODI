package de.agentcodi.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One reported file change rendered as its own card: change kind, file name, directory,
 * line counters and a colored unified diff. The transcript and the approval dialog share it
 * so both show the same bounded content in the same shape.
 */
final class FileChangeView extends LinearLayout {
    private final UiTheme theme;
    private final TextView kindBadge;
    private final TextView fileName;
    private final TextView directory;
    private final TextView stats;
    private final TextView movePath;
    private final TextView diff;
    private final TextView emptyDiff;

    FileChangeView(Context context, UiTheme theme) {
        super(context);
        this.theme = theme;
        setOrientation(VERTICAL);
        setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        setBackground(theme.background(theme.surfaceRaised, theme.border, 14));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        kindBadge = theme.badge("", theme.accent);
        header.addView(kindBadge, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout names = new LinearLayout(context);
        names.setOrientation(VERTICAL);
        fileName = theme.text("", 14, theme.primary);
        fileName.setTypeface(Typeface.DEFAULT_BOLD);
        fileName.setSingleLine(true);
        fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        names.addView(fileName);
        directory = theme.text("", 11, theme.secondary);
        directory.setSingleLine(true);
        directory.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        directory.setVisibility(GONE);
        names.addView(directory);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        );
        nameParams.setMarginStart(theme.dp(10));
        header.addView(names, nameParams);

        stats = theme.text("", 11, theme.secondary);
        stats.setTypeface(Typeface.MONOSPACE);
        stats.setIncludeFontPadding(false);
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        statsParams.setMarginStart(theme.dp(8));
        header.addView(stats, statsParams);
        addView(header);

        movePath = theme.text("", 12, theme.secondary);
        movePath.setSingleLine(true);
        movePath.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        movePath.setVisibility(GONE);
        theme.addWithTopMargin(this, movePath, 6);

        diff = theme.codeBlock("", 12);
        diff.setTextIsSelectable(true);
        diff.setVisibility(GONE);
        theme.addWithTopMargin(this, diff, 10);

        emptyDiff = theme.text("", 12, theme.secondary);
        emptyDiff.setVisibility(GONE);
        theme.addWithTopMargin(this, emptyDiff, 8);
    }

    void bind(FileChangeDetail.FileChange change) {
        Context context = getContext();
        int badgeColor = kindColor(change.getKind());
        kindBadge.setText(context.getString(kindLabel(change.getKind())));
        kindBadge.setTextColor(badgeColor);
        kindBadge.setBackground(theme.background(
            theme.tintedSurface(badgeColor, theme.dark ? 0.18f : 0.12f), Color.TRANSPARENT, 8
        ));
        fileName.setText(change.getFileName());
        String parent = change.getDirectory();
        directory.setText(parent);
        directory.setVisibility(parent.isEmpty() ? GONE : VISIBLE);
        setContentDescription(context.getString(
            R.string.card_change_entry_description,
            context.getString(kindLabel(change.getKind())),
            change.getPath()
        ));

        boolean counted = change.getAddedLines() > 0 || change.getRemovedLines() > 0;
        stats.setText(counted
            ? statsText(theme, change.getAddedLines(), change.getRemovedLines())
            : "");
        stats.setVisibility(counted ? VISIBLE : GONE);
        stats.setContentDescription(counted
            ? context.getString(
                R.string.card_change_stats_description,
                Integer.valueOf(change.getAddedLines()),
                Integer.valueOf(change.getRemovedLines())
            )
            : "");

        boolean moved = !change.getMovePath().isEmpty();
        movePath.setText(moved
            ? context.getString(R.string.card_change_moved_to, change.getMovePath())
            : "");
        movePath.setVisibility(moved ? VISIBLE : GONE);

        String value = change.getDiff();
        boolean hasDiff = !value.trim().isEmpty();
        diff.setText(hasDiff ? diffText(theme, value) : "");
        diff.setVisibility(hasDiff ? VISIBLE : GONE);
        emptyDiff.setText(hasDiff ? "" : context.getString(R.string.card_no_text_diff));
        emptyDiff.setVisibility(hasDiff ? GONE : VISIBLE);
    }

    /** Renders the added and removed line counters in their diff colors. */
    static CharSequence statsText(UiTheme theme, int addedLines, int removedLines) {
        SpannableStringBuilder value = new SpannableStringBuilder();
        append(value, "+" + addedLines, theme.diffAdded);
        value.append(' ');
        append(value, "−" + removedLines, theme.diffRemoved);
        return value;
    }

    private static void append(SpannableStringBuilder target, String value, int color) {
        int start = target.length();
        target.append(value);
        target.setSpan(
            new ForegroundColorSpan(color),
            start,
            target.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
    }

    /** Colors a unified diff line by line and keeps every reported character visible. */
    static CharSequence diffText(UiTheme theme, String value) {
        SpannableStringBuilder text = new SpannableStringBuilder(value);
        int lineStart = 0;
        while (lineStart <= value.length()) {
            int newline = value.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? value.length() : newline;
            FileChangeDetail.LineKind kind = FileChangeDetail.lineKind(
                value.substring(lineStart, lineEnd)
            );
            int spanEnd = newline < 0 ? lineEnd : lineEnd + 1;
            if (kind != FileChangeDetail.LineKind.CONTEXT && spanEnd > lineStart) {
                text.setSpan(
                    new ForegroundColorSpan(lineColor(theme, kind)),
                    lineStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                int fill = lineFill(theme, kind);
                if (fill != Color.TRANSPARENT) {
                    text.setSpan(
                        new DiffLineBackground(fill),
                        lineStart,
                        spanEnd,
                        Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                    );
                }
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return text;
    }

    private static int lineColor(UiTheme theme, FileChangeDetail.LineKind kind) {
        if (kind == FileChangeDetail.LineKind.ADDED) {
            return theme.diffAdded;
        }
        if (kind == FileChangeDetail.LineKind.REMOVED) {
            return theme.diffRemoved;
        }
        if (kind == FileChangeDetail.LineKind.HUNK) {
            return theme.accent;
        }
        return theme.diffMeta;
    }

    private static int lineFill(UiTheme theme, FileChangeDetail.LineKind kind) {
        if (kind == FileChangeDetail.LineKind.ADDED) {
            return theme.diffAddedFill;
        }
        if (kind == FileChangeDetail.LineKind.REMOVED) {
            return theme.diffRemovedFill;
        }
        return Color.TRANSPARENT;
    }

    private int kindColor(FileChangeDetail.Kind kind) {
        if (kind == FileChangeDetail.Kind.ADD) {
            return theme.diffAdded;
        }
        return kind == FileChangeDetail.Kind.DELETE ? theme.diffRemoved : theme.info;
    }

    private static int kindLabel(FileChangeDetail.Kind kind) {
        if (kind == FileChangeDetail.Kind.ADD) {
            return R.string.card_change_add;
        }
        return kind == FileChangeDetail.Kind.DELETE
            ? R.string.card_change_delete
            : R.string.card_change_update;
    }

    /** Paints the full line width so wrapped and short diff lines read as one block. */
    private static final class DiffLineBackground implements LineBackgroundSpan {
        private final int color;

        private DiffLineBackground(int color) {
            this.color = color;
        }

        @Override
        public void drawBackground(
            Canvas canvas,
            Paint paint,
            int left,
            int right,
            int top,
            int baseline,
            int bottom,
            CharSequence text,
            int start,
            int end,
            int lineNumber
        ) {
            int original = paint.getColor();
            paint.setColor(color);
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setColor(original);
        }
    }
}
