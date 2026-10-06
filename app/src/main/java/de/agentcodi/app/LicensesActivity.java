package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class LicensesActivity extends Activity {
    private static final int MAX_LICENSE_BYTES = 512 * 1024;
    private static final int MPL_SOURCE_EXPORT_REQUEST = 1401;
    private static final String MPL_SOURCE_ARCHIVE = "third-party/codex/MPL-SOURCES.zip";

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
            R.string.license_material_icons_title,
            R.string.license_material_icons_summary,
            R.string.license_show_text,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return readRawResource(R.raw.material_icons_notice)
                        + "\n\n"
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
            R.string.license_zlib_runtime_title,
            R.string.license_zlib_runtime_summary,
            R.string.license_show_notice,
            new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return readAsset("third-party/zlib/ZLIB-LICENSE");
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
                    return readAsset("third-party/libcxx/DISTRIBUTOR-LICENSE")
                        + "\n\nLLVM UPSTREAM NOTICES\n\n"
                        + readAsset("third-party/libcxx/LLVM-LICENSES")
                        + "\n\n"
                        + readRawResource(R.raw.third_party_notices);
                }
            }
        );
        addCommunityCard(page);
        addBootstrapCard(page);
        return scroll;
    }

    private void addCommunityCard(LinearLayout page) {
        LinearLayout card = theme.card();
        TextView title = theme.text(getString(R.string.license_codex_dependencies_title), 18, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        theme.addWithTopMargin(card, theme.body(getString(R.string.license_codex_dependencies_summary)), 8);
        Button show = theme.secondaryButton(getString(R.string.license_show_text));
        show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCommunityComponents();
            }
        });
        theme.addWithTopMargin(card, show, 12);
        Button sourceNotice = theme.secondaryButton(getString(R.string.license_mpl_show_sources));
        sourceNotice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLicenseText(getString(R.string.license_mpl_sources_title), new LicenseLoader() {
                    @Override
                    public String load() throws IOException {
                        return readAsset("third-party/codex/MPL-SOURCE-OFFER.txt");
                    }
                });
            }
        });
        theme.addWithTopMargin(card, sourceNotice, 12);
        theme.addWithTopMargin(card, theme.body(getString(R.string.license_mpl_sources_summary)), 8);
        Button saveSources = theme.secondaryButton(getString(R.string.license_mpl_save_sources));
        saveSources.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE, "AGENTCODI-MPL-sources.zip");
                try {
                    startActivityForResult(intent, MPL_SOURCE_EXPORT_REQUEST);
                } catch (RuntimeException error) {
                    Toast.makeText(LicensesActivity.this, R.string.document_picker_open_failed,
                        Toast.LENGTH_LONG).show();
                }
            }
        });
        theme.addWithTopMargin(card, saveSources, 12);
        theme.addWithTopMargin(page, card, 16);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != MPL_SOURCE_EXPORT_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri destination = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                int result = R.string.license_mpl_sources_saved;
                try (InputStream input = getAssets().open(MPL_SOURCE_ARCHIVE);
                     OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) {
                        throw new IOException("No output stream for MPL source destination");
                    }
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                } catch (IOException | RuntimeException error) {
                    result = R.string.license_mpl_sources_save_failed;
                }
                final int message = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) {
                            Toast.makeText(LicensesActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "mpl-source-export").start();
    }

    private void showCommunityComponents() {
        try {
            JSONObject index = new JSONObject(readBounded(
                getAssets().open("third-party/codex/DEPENDENCY-LICENSE-INDEX.json"), 4 * 1024 * 1024));
            final JSONArray components = index.getJSONArray("components");
            String[] labels = new String[components.length()];
            for (int i = 0; i < labels.length; i++) {
                JSONObject component = components.getJSONObject(i);
                labels[i] = component.getString("name") + " " + component.getString("version");
            }
            new AlertDialog.Builder(this)
                .setTitle(R.string.license_codex_dependencies_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            showCommunityFiles(components.getJSONObject(which));
                        } catch (JSONException error) {
                            showBootstrapError();
                        }
                    }
                })
                .setNegativeButton(R.string.license_close, null)
                .show();
        } catch (IOException | JSONException error) {
            showBootstrapError();
        }
    }

    private void showCommunityFiles(final JSONObject component) throws JSONException {
        final JSONArray files = component.getJSONArray("files");
        final String title = component.getString("name") + " " + component.getString("version");
        String[] labels = new String[files.length()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = files.getJSONObject(i).getString("source_path");
        }
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        final JSONObject file = files.getJSONObject(which);
                        showLicenseText(title, new LicenseLoader() {
                            @Override
                            public String load() throws IOException {
                                try {
                                    String text = readZipLicense(
                                        "third-party/codex/DEPENDENCY-LICENSES.zip", file.getString("path"));
                                    if (file.getString("source_path").endsWith(".html")) {
                                        return android.text.Html.fromHtml(text,
                                            android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                                    }
                                    return text;
                                } catch (JSONException error) {
                                    throw new IOException("Invalid dependency notice", error);
                                }
                            }
                        });
                    } catch (JSONException error) {
                        showBootstrapError();
                    }
                }
            })
            .setNegativeButton(R.string.license_close, null)
            .show();
    }

    private void addBootstrapCard(LinearLayout page) {
        LinearLayout card = theme.card();
        TextView title = theme.text(getString(R.string.license_bootstrap_title), 18, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);
        theme.addWithTopMargin(card, theme.body(getString(R.string.license_bootstrap_summary)), 8);
        Button show = theme.secondaryButton(getString(R.string.license_show_text));
        show.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showBootstrapPackages();
            }
        });
        theme.addWithTopMargin(card, show, 12);
        theme.addWithTopMargin(page, card, 16);
    }

    private void showBootstrapPackages() {
        try {
            JSONObject index = new JSONObject(readAsset("third-party/package-bootstrap/BOOTSTRAP-LICENSE-INDEX.json"));
            final JSONArray packages = index.getJSONArray("packages");
            String[] labels = new String[packages.length()];
            for (int i = 0; i < labels.length; i++) {
                JSONObject entry = packages.getJSONObject(i);
                labels[i] = entry.getString("name") + " " + entry.getString("version");
            }
            new AlertDialog.Builder(this)
                .setTitle(R.string.license_bootstrap_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            showBootstrapFiles(packages.getJSONObject(which));
                        } catch (JSONException error) {
                            showBootstrapError();
                        }
                    }
                })
                .setNegativeButton(R.string.license_close, null)
                .show();
        } catch (IOException | JSONException error) {
            showBootstrapError();
        }
    }

    private void showBootstrapFiles(final JSONObject entry) throws JSONException {
        final JSONArray files = entry.getJSONArray("files");
        final String name = entry.getString("name") + " " + entry.getString("version");
        if (files.length() == 0) {
            final String notice = entry.getString("notice");
            showLicenseText(name, new LicenseLoader() {
                @Override
                public String load() throws IOException {
                    return notice.isEmpty() ? getString(R.string.license_bootstrap_missing)
                        : notice + "\n\n" + readRawResource(R.raw.agentcodi_apache_2_0);
                }
            });
            return;
        }
        String[] paths = new String[files.length()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = files.getJSONObject(i).getString("path");
        }
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(paths, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        final String path = files.getJSONObject(which).getString("path");
                        showLicenseText(name, new LicenseLoader() {
                            @Override
                            public String load() throws IOException {
                                return readBootstrapLicense(path);
                            }
                        });
                    } catch (JSONException error) {
                        showBootstrapError();
                    }
                }
            })
            .setNegativeButton(R.string.license_close, null)
            .show();
    }

    private String readBootstrapLicense(String path) throws IOException {
        return readZipLicense("third-party/package-bootstrap/bootstrap-aarch64.zip", path);
    }

    private String readZipLicense(String asset, String path) throws IOException {
        try (ZipInputStream archive = new ZipInputStream(getAssets().open(asset))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.getName().equals(path)) {
                    return readBounded(archive);
                }
            }
        }
        throw new IOException("Bootstrap license entry is missing");
    }

    private void showBootstrapError() {
        new AlertDialog.Builder(this)
            .setMessage(R.string.license_load_failed)
            .setPositiveButton(R.string.license_close, null)
            .show();
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
        return readBounded(input, MAX_LICENSE_BYTES);
    }

    private static String readBounded(InputStream input, int limit) throws IOException {
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
                if (total > limit) {
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
