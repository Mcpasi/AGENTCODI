package de.agentcodi.app;

import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import de.agentcodi.editor.syntax.SyntaxHighlighter;
import de.agentcodi.editor.syntax.SyntaxLanguage;
import de.agentcodi.editor.syntax.SyntaxToken;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Debounced background tokenization, generation checks and viewport-bounded color spans. */
final class EditorSyntaxHighlighter {
    static final class ColorSpan extends ForegroundColorSpan {
        ColorSpan(int color) { super(color); }
    }

    private final EditText input;
    private final UiTheme theme;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tokenize = new Runnable() { @Override public void run() { tokenize(); } };
    private SyntaxLanguage language = SyntaxLanguage.PLAIN;
    private ExecutorService worker;
    private Future<?> pending;
    private List<SyntaxToken> tokens = Collections.emptyList();
    private int generation;
    private int paintedStart = -1;
    private int paintedEnd = -1;
    private boolean paused;

    EditorSyntaxHighlighter(EditText input, UiTheme theme) {
        this.input = input;
        this.theme = theme;
    }

    void setPath(String path) {
        language = SyntaxLanguage.fromPath(path);
        textChanged();
    }

    void textChanged() {
        textChanging();
        if (!paused && language != SyntaxLanguage.PLAIN) { handler.postDelayed(tokenize, 80); }
    }

    void textChanging() {
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (pending != null) { pending.cancel(true); pending = null; }
        tokens = Collections.emptyList();
        clearColors();
    }

    void resume() {
        if (paused) { paused = false; textChanged(); }
    }

    void pause() {
        paused = true;
        textChanged();
        if (worker != null) { worker.shutdownNow(); worker = null; }
    }

    private void tokenize() {
        final int selectedGeneration = generation;
        final String snapshot = input.getText().toString();
        final SyntaxLanguage selectedLanguage = language;
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable action) {
                    Thread thread = new Thread(action, "workspace-editor-syntax");
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }
        pending = worker.submit(new Runnable() {
            @Override public void run() {
                final List<SyntaxToken> result;
                try { result = SyntaxHighlighter.highlight(selectedLanguage, snapshot); }
                catch (CancellationException cancelled) { return; }
                if (Thread.currentThread().isInterrupted()) { return; }
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (paused || generation != selectedGeneration) { return; }
                        tokens = result;
                        paintedStart = paintedEnd = -1;
                        viewportChanged();
                    }
                });
            }
        });
    }

    void viewportChanged() {
        if (tokens.isEmpty() || paused) { return; }
        Layout layout = input.getLayout();
        int start = 0;
        int end = Math.min(input.length(), 4096);
        if (layout != null) {
            int top = Math.max(0, input.getScrollY() - input.getTotalPaddingTop());
            int bottom = Math.max(top, input.getScrollY() + input.getHeight() - input.getTotalPaddingTop());
            int first = layout.getLineForVertical(top);
            int last = layout.getLineForVertical(bottom);
            start = layout.getLineStart(Math.max(0, first - 8));
            end = layout.getLineEnd(Math.min(layout.getLineCount() - 1, last + 8));
        }
        if (start == paintedStart && end == paintedEnd) { return; }
        clearColors();
        Editable text = input.getText();
        int low = 0;
        int high = tokens.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (tokens.get(middle).getEnd() <= start) { low = middle + 1; }
            else { high = middle; }
        }
        for (int index = low; index < tokens.size(); index++) {
            SyntaxToken token = tokens.get(index);
            if (token.getStart() >= end) { break; }
            text.setSpan(new ColorSpan(color(token.getKind())), Math.max(start, token.getStart()),
                Math.min(end, token.getEnd()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        paintedStart = start;
        paintedEnd = end;
    }

    private void clearColors() {
        Editable text = input.getText();
        for (ColorSpan span : text.getSpans(0, text.length(), ColorSpan.class)) { text.removeSpan(span); }
        paintedStart = paintedEnd = -1;
    }

    int color(SyntaxToken.Kind kind) {
        switch (kind) {
            case COMMENT: return theme.dark ? 0xFFA3AEBF : 0xFF647084;
            case KEYWORD: return theme.dark ? 0xFFC4B5FD : 0xFF6D28D9;
            case STRING: return theme.dark ? 0xFF86EFAC : 0xFF166534;
            case NUMBER: return theme.dark ? 0xFFFCD34D : 0xFF92400E;
            case FUNCTION: return theme.dark ? 0xFF93C5FD : 0xFF1D4ED8;
            case BUILTIN: return theme.dark ? 0xFF67E8F9 : 0xFF0E7490;
            case TAG: return theme.dark ? 0xFFFDA4AF : 0xFFBE123C;
            case ATTRIBUTE: return theme.dark ? 0xFFFDBA74 : 0xFF9A3412;
            case OPERATOR: return theme.dark ? 0xFFE2E8F0 : 0xFF475569;
            default: return theme.codeText;
        }
    }
}
