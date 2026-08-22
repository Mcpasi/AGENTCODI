package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class LicensesActivity extends Activity {
    private static final int MAX_LICENSE_BYTES = 512 * 1024;

    private UiTheme theme;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = new UiTheme(this);
        setContentView(buildContent());
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(theme.page);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(theme.dp(18), theme.dp(20), theme.dp(18), theme.dp(36));
        scroll.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = theme.compactButton(getString(R.string.licenses_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        topBar.addView(back);
        TextView title = theme.text(getString(R.string.licenses_title), 25, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParams.leftMargin = theme.dp(14);
        topBar.addView(title, titleParams);
        page.addView(topBar);

        TextView subtitle = theme.body(getString(R.string.licenses_subtitle));
        theme.addWithTopMargin(page, subtitle, 10);

        addLicenseCard(
            page,
            R.string.license_agentcodi_title,
            R.string.license_agentcodi_summary,
            R.string.license_show_text,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return "Copyright 2026 Pascal (Mc Pasi)\n\n"
                        + readRawResource(R.raw.agentcodi_apache_2_0);
                }
            }
        );
        addLicenseCard(
            page,
            R.string.license_codex_runtime_title,
            R.string.license_codex_runtime_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return readAsset("third-party/codex/LICENSE")
                        + "\n\nNOTICE\n\n"
                        + readAsset("third-party/codex/NOTICE");
                }
            }
        );
        addLicenseCard(
            page,
            R.string.license_node_runtime_title,
            R.string.license_node_runtime_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return "NODE.JS\n\n"
                        + readAsset("third-party/node/NODE-LICENSE")
                        + "\n\nC-ARES\n\n"
                        + readAsset("third-party/node/CARES-LICENSE")
                        + "\n\nICU\n\n"
                        + readAsset("third-party/node/ICU-LICENSE")
                        + "\n\nOPENSSL\n\n"
                        + readAsset("third-party/node/OPENSSL-LICENSE")
                        + "\n\nZLIB\n\n"
                        + readAsset("third-party/node/ZLIB-LICENSE");
                }
            }
        );
        addLicenseCard(
            page,
            R.string.license_npm_runtime_title,
            R.string.license_npm_runtime_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return readAsset("third-party/npm/NPM-LICENSES");
                }
            }
        );
        addLicenseCard(
            page,
            R.string.license_python_runtime_title,
            R.string.license_python_runtime_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return readAsset("third-party/python/PYTHON-LICENSES");
                }
            }
        );
        addLicenseCard(
            page,
            R.string.license_ripgrep_runtime_title,
            R.string.license_ripgrep_runtime_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return "PROVENANCE\n\n"
                        + readAsset("third-party/ripgrep/PROVENANCE")
                        + "\n\nDEPENDENCY INVENTORY\n\n"
                        + readAsset("third-party/ripgrep/DEPENDENCIES")
                        + "\n\nLICENSES\n\n"
                        + readAsset("third-party/ripgrep/LICENSES");
                }
            }
        );
        addLicenseCard(
            page,
            R.string.license_third_party_title,
            R.string.license_third_party_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    String notices = readRawResource(R.raw.third_party_notices);
                    String marker = "LLVM libc++ shared runtime";
                    int offset = notices.indexOf(marker);
                    return offset < 0 ? notices : notices.substring(offset);
                }
            }
        );
        return scroll;
    }

    private void addLicenseCard(
        LinearLayout page,
        final int titleResource,
        int summaryResource,
        int actionResource,
        final LicenseLoader loader
    ) {
        LinearLayout card = theme.card();
        TextView title = theme.text(getString(titleResource), 18, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        TextView summary = theme.body(getString(summaryResource));
        theme.addWithTopMargin(card, summary, 8);
        Button show = theme.secondaryButton(getString(actionResource));
        show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLicenseText(getString(titleResource), loader);
            }
        });
        theme.addWithTopMargin(card, show, 12);
        theme.addWithTopMargin(page, card, 16);
    }

    private void showLicenseText(String title, LicenseLoader loader) {
        String text;
        try {
            text = loader.load();
        } catch (IOException error) {
            text = getString(R.string.license_load_failed);
        }
        TextView content = theme.text(text, 12, theme.primary);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setLineSpacing(0.0f, 1.12f);
        content.setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(
                R.string.license_close,
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                }
            )
            .show();
    }

    private String readRawResource(int resource) throws IOException {
        return readBounded(getResources().openRawResource(resource));
    }

    private String readAsset(String path) throws IOException {
        return readBounded(getAssets().open(path));
    }

    private static String readBounded(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > MAX_LICENSE_BYTES) {
                    throw new IOException("Packaged license exceeds the display limit");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private interface LicenseLoader {
        String load() throws IOException;
    }
}
