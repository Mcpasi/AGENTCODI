package de.agentcodi.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Keeps the transcript usable when the keyboard or a small window reduces height. */
final class ConversationLayout extends LinearLayout {
    private final UiTheme theme;
    private View selectors;
    private LinearLayout composer;
    private ScrollView editorScroll;
    private EditText input;
    private boolean compact;

    ConversationLayout(Context context, UiTheme theme) {
        super(context);
        this.theme = theme;
        setOrientation(VERTICAL);
    }

    void bindComposer(View selectors, LinearLayout composer, ScrollView editorScroll, EditText input) {
        this.selectors = selectors;
        this.composer = composer;
        this.editorScroll = editorScroll;
        this.input = input;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (composer != null && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            int height = MeasureSpec.getSize(heightMeasureSpec) - getPaddingTop() - getPaddingBottom();
            placeSelectors(height < theme.dp(300));
            int width = Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight());
            int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            if (!compact) {
                selectors.measure(childWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            }
            int remaining = Math.max(0, height - (compact ? 0 : selectors.getMeasuredHeight()));
            int lineHeight = Math.max(1, input.getLineHeight());
            int inputPadding = input.getCompoundPaddingTop() + input.getCompoundPaddingBottom();
            int lines = Math.max(1, Math.min(6, (remaining / 3 - inputPadding) / lineHeight));
            if (input.getMaxLines() != lines) {
                input.setMaxLines(lines);
                input.setMinLines(Math.min(2, lines));
            }

            LayoutParams editorParams = (LayoutParams) editorScroll.getLayoutParams();
            editorParams.height = LayoutParams.WRAP_CONTENT;
            composer.measure(childWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int fixedHeight = composer.getMeasuredHeight() - editorScroll.getMeasuredHeight();
            int minimumEditor = Math.min(editorScroll.getMeasuredHeight(), Math.max(theme.dp(48),
                lineHeight + inputPadding));
            int transcriptReserve = Math.min(theme.dp(80),
                Math.max(0, remaining - fixedHeight - minimumEditor));
            int composerHeight = Math.min(composer.getMeasuredHeight(), remaining - transcriptReserve);
            editorParams.height = Math.max(0, composerHeight - fixedHeight);
            ((LayoutParams) composer.getLayoutParams()).height = composerHeight;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void placeSelectors(boolean nextCompact) {
        if (compact == nextCompact) {
            return;
        }
        LinearLayout previous = (LinearLayout) selectors.getParent();
        previous.removeView(selectors);
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (nextCompact) {
            params.bottomMargin = theme.dp(6);
            ((LinearLayout) editorScroll.getChildAt(0)).addView(selectors, 0, params);
        } else {
            addView(selectors, 0, params);
        }
        compact = nextCompact;
        editorScroll.post(new Runnable() {
            @Override
            public void run() {
                if (input.hasFocus()) {
                    Rect caret = new Rect();
                    input.getFocusedRect(caret);
                    input.requestRectangleOnScreen(caret, true);
                }
            }
        });
    }
}
