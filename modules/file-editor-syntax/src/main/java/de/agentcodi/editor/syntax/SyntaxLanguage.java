package de.agentcodi.editor.syntax;

import java.util.Locale;

/** File-name based opt-in: unsupported files always remain plain text. */
public enum SyntaxLanguage {
    PLAIN, JAVASCRIPT, HTML, CSS, PYTHON;

    public static SyntaxLanguage fromPath(String path) {
        if (path == null) { return PLAIN; }
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
            .toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) { return PLAIN; }
        String extension = name.substring(dot + 1);
        if (extension.equals("js") || extension.equals("mjs") || extension.equals("cjs")) {
            return JAVASCRIPT;
        }
        if (extension.equals("html") || extension.equals("htm")) { return HTML; }
        if (extension.equals("css")) { return CSS; }
        if (extension.equals("py") || extension.equals("pyw")) { return PYTHON; }
        return PLAIN;
    }
}
