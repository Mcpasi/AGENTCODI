package de.agentcodi.app;

import android.graphics.Rect;
import android.text.Spanned;
import android.text.method.TransformationMethod;
import android.view.View;

import java.lang.reflect.Array;

/** Display CR and CRLF as line breaks without changing text, spans or cursor offsets. */
final class EditorLineEndingTransformation implements TransformationMethod {
    @Override public CharSequence getTransformation(CharSequence source, View view) {
        return source == null ? null : new DisplayText(source);
    }

    @Override public void onFocusChanged(View view, CharSequence source, boolean focused,
        int direction, Rect previouslyFocusedRect) { }

    private static final class DisplayText implements Spanned {
        private final CharSequence source;

        DisplayText(CharSequence source) { this.source = source; }
        @Override public int length() { return source.length(); }
        @Override public char charAt(int index) {
            char value = source.charAt(index);
            if (value != '\r') { return value; }
            return index + 1 < length() && source.charAt(index + 1) == '\n' ? '\ufeff' : '\n';
        }
        @Override public CharSequence subSequence(int start, int end) {
            StringBuilder result = new StringBuilder(end - start);
            for (int index = start; index < end; index++) { result.append(charAt(index)); }
            return result.toString();
        }
        @Override public String toString() { return subSequence(0, length()).toString(); }
        @Override public int getSpanStart(Object span) {
            return source instanceof Spanned ? ((Spanned) source).getSpanStart(span) : -1;
        }
        @Override public int getSpanEnd(Object span) {
            return source instanceof Spanned ? ((Spanned) source).getSpanEnd(span) : -1;
        }
        @Override public int getSpanFlags(Object span) {
            return source instanceof Spanned ? ((Spanned) source).getSpanFlags(span) : 0;
        }
        @Override public int nextSpanTransition(int start, int limit, Class type) {
            return source instanceof Spanned ? ((Spanned) source).nextSpanTransition(start, limit, type) : limit;
        }
        @SuppressWarnings("unchecked")
        @Override public <T> T[] getSpans(int start, int end, Class<T> type) {
            return source instanceof Spanned ? ((Spanned) source).getSpans(start, end, type)
                : (T[]) Array.newInstance(type, 0);
        }
    }
}
