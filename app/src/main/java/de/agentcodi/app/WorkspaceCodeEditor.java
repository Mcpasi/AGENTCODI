package de.agentcodi.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

/** Modular native editing surface; decorations never replace the editable buffer. */
final class WorkspaceCodeEditor extends LinearLayout {
    final WorkspaceCodeInput input;
    private final WorkspaceLineNumbers lineNumbers;
    private final EditorSyntaxHighlighter highlighter;

    WorkspaceCodeEditor(Context context, UiTheme theme) {
        super(context);
        setOrientation(HORIZONTAL);
        setBackground(theme.background(theme.code, theme.border, 12));
        input = new WorkspaceCodeInput(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(15);
        input.setTextColor(theme.codeText);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setHorizontallyScrolling(false);
        input.setPadding(theme.dp(8), theme.dp(12), theme.dp(12), theme.dp(12));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setTransformationMethod(new EditorLineEndingTransformation());
        input.setContentDescription(context.getString(R.string.editor_content_description));
        // Large documents must not be serialized into Android's bounded view-state Bundle.
        input.setSaveEnabled(false);
        lineNumbers = new WorkspaceLineNumbers(context, input, theme);
        highlighter = new EditorSyntaxHighlighter(input, theme);
        addView(lineNumbers, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(input, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
        final Runnable viewportChanged = new Runnable() {
            @Override public void run() {
                lineNumbers.invalidate();
                highlighter.viewportChanged();
            }
        };
        input.setViewportChanged(viewportChanged);
        input.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                int oldLeft, int oldTop, int oldRight, int oldBottom) { viewportChanged.run(); }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                highlighter.textChanging();
            }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                lineNumbers.setText(text);
                highlighter.textChanged();
            }
        });
    }

    void setPath(String path) { highlighter.setPath(path); }
    void dispose() { highlighter.pause(); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); highlighter.resume(); }
    @Override protected void onDetachedFromWindow() { highlighter.pause(); super.onDetachedFromWindow(); }
}
