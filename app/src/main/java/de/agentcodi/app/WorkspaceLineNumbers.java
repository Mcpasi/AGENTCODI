package de.agentcodi.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.view.View;
import android.widget.EditText;

import de.agentcodi.editor.syntax.SourceLines;

/** Draws only visible logical line starts using the text field's actual layout baselines. */
final class WorkspaceLineNumbers extends View {
    private final EditText input;
    private final UiTheme theme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private SourceLines lines = new SourceLines("");

    WorkspaceLineNumbers(Context context, EditText input, UiTheme theme) {
        super(context);
        this.input = input;
        this.theme = theme;
        paint.setTypeface(input.getTypeface());
        paint.setTextSize(input.getTextSize());
        paint.setTextAlign(Paint.Align.RIGHT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
    }

    void setText(CharSequence text) {
        int digits = Integer.toString(lines.getLineCount()).length();
        lines = new SourceLines(text);
        if (digits != Integer.toString(lines.getLineCount()).length()) { requestLayout(); }
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        paint.setTextSize(input.getTextSize());
        int width = (int) Math.ceil(paint.measureText(Integer.toString(lines.getLineCount()))) + theme.dp(20);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(0, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Layout layout = input.getLayout();
        if (layout == null) { return; }
        int origin = input.getTotalPaddingTop() - input.getScrollY();
        int first = layout.getLineForVertical(Math.max(0, -origin));
        int last = layout.getLineForVertical(Math.max(0, getHeight() - origin));
        int selection = input.getSelectionStart();
        int active = selection < 0 ? -1 : lines.getLineForOffset(Math.min(selection, input.length()));
        canvas.save();
        canvas.clipRect(0, input.getTotalPaddingTop(), getWidth(), getHeight() - input.getTotalPaddingBottom());
        for (int visual = first; visual <= last; visual++) {
            int logical = lines.getLineForOffset(Math.min(input.length(), layout.getLineStart(visual)));
            if (visual != layout.getLineForOffset(lines.getStart(logical))) { continue; }
            paint.setColor(logical == active && input.isFocused() ? theme.accent : theme.secondary);
            canvas.drawText(Integer.toString(logical + 1), getWidth() - theme.dp(8),
                origin + layout.getLineBaseline(visual), paint);
        }
        paint.setColor(theme.border);
        paint.setStrokeWidth(theme.dp(1));
        canvas.drawLine(getWidth() - 1, theme.dp(8), getWidth() - 1, getHeight() - theme.dp(8), paint);
        canvas.restore();
    }
}
