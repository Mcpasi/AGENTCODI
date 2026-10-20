package de.agentcodi.app;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.widget.TextView;

/** One cache per existing TextView; no transcript-wide cache or extra UI refreshes. */
final class ChatMarkdownText {
    private final TextView view;
    private final UiTheme theme;
    private String source;
    private boolean markdown;

    ChatMarkdownText(TextView view, UiTheme theme) {
        this.view = view;
        this.theme = theme;
    }

    boolean bind(String next, boolean useMarkdown) {
        if (source != null && source.equals(next) && markdown == useMarkdown) {
            return false;
        }
        source = next;
        markdown = useMarkdown;
        if (!useMarkdown) {
            view.setText(next);
            view.setMovementMethod(ArrowKeyMovementMethod.getInstance());
            return true;
        }
        ChatMarkdown.Document document = ChatMarkdown.parse(next);
        SpannableStringBuilder text = new SpannableStringBuilder(document.text);
        boolean hasLinks = false;
        for (ChatMarkdown.Span span : document.spans) {
            if (span.end <= span.start) { continue; }
            switch (span.style) {
                case BOLD:
                    apply(text, span, new StyleSpan(Typeface.BOLD));
                    break;
                case ITALIC:
                    apply(text, span, new StyleSpan(Typeface.ITALIC));
                    break;
                case STRIKE:
                    apply(text, span, new StrikethroughSpan());
                    break;
                case CODE:
                case CODE_BLOCK:
                    apply(text, span, new TypefaceSpan("monospace"));
                    apply(text, span, new RelativeSizeSpan(0.93f));
                    apply(text, span, new ForegroundColorSpan(theme.codeText));
                    apply(text, span, new BackgroundColorSpan(theme.code));
                    if (span.style == ChatMarkdown.Style.CODE_BLOCK) {
                        apply(text, span, new LeadingMarginSpan.Standard(theme.dp(10)));
                    }
                    break;
                case HEADING:
                    apply(text, span, new StyleSpan(Typeface.BOLD));
                    apply(text, span, new RelativeSizeSpan(
                        span.level == 1 ? 1.5f : span.level == 2 ? 1.3f : 1.12f
                    ));
                    break;
                case QUOTE:
                    apply(text, span, new QuoteSpan(theme.secondary, theme.dp(2), theme.dp(8)));
                    break;
                case LINK:
                    apply(text, span, new ThemedLink(span.destination, theme.accent));
                    hasLinks = true;
                    break;
                case TABLE:
                    apply(text, span, new TypefaceSpan("monospace"));
                    apply(text, span, new RelativeSizeSpan(0.9f));
                    break;
                case RULE:
                    apply(text, span, new ForegroundColorSpan(theme.secondary));
                    break;
                default:
                    break;
            }
        }
        view.setText(text);
        view.setLinkTextColor(theme.accent);
        view.setMovementMethod(hasLinks
            ? LinkMovementMethod.getInstance() : ArrowKeyMovementMethod.getInstance());
        return true;
    }

    private static void apply(SpannableStringBuilder text, ChatMarkdown.Span span, Object style) {
        text.setSpan(style, span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class ThemedLink extends URLSpan {
        private final int color;

        ThemedLink(String destination, int color) {
            super(destination);
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            super.updateDrawState(paint);
            paint.setColor(color);
        }
    }
}
