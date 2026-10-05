package de.agentcodi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.agentcodi.browser.WorkspaceBreadcrumb;
import de.agentcodi.browser.WorkspaceBrowserEntry;
import de.agentcodi.browser.WorkspaceBrowserLimits;
import de.agentcodi.browser.WorkspaceBrowserPage;
import de.agentcodi.browser.WorkspaceFilePreview;
import de.agentcodi.runtime.WorkspaceBrowserRepository;
import de.agentcodi.runtime.WorkspaceFileExporter;
import de.agentcodi.runtime.WorkspaceFileImporter;
import de.agentcodi.browser.WorkspaceBrowserArea;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportGrant;
import de.agentcodi.imports.WorkspaceImportLimits;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class WorkspaceBrowserActivity extends Activity {
    private static final int IMPORT_REQUEST_CODE = 7303;
    private static final int FILE_EXPORT_REQUEST_CODE = 7301;
    private static final int DIRECTORY_EXPORT_REQUEST_CODE = 7302;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService operations = Executors.newSingleThreadExecutor();

    private UiTheme theme;
    private WorkspaceBrowserRepository repository;
    private LinearLayout breadcrumbRow;
    private HorizontalScrollView breadcrumbScroll;
    private TextView statusView;
    private LinearLayout directoryPanel;
    private ListView entryList;
    private EntryAdapter entryAdapter;
    private Button previousDirectoryPageButton;
    private Button nextDirectoryPageButton;
    private TextView directoryPageView;
    private Button directoryExportButton;
    private Button scopeButton;
    private Button importButton;
    private TextView scopeDescriptionView;
    private boolean pendingImport;
    private LinearLayout previewPanel;
    private TextView previewTitleView;
    private TextView previewDetailsView;
    private TextView previewTextView;
    private ScrollView previewTextScroll;
    private ImageView previewImageView;
    private Button previousPreviewPageButton;
    private Button nextPreviewPageButton;
    private TextView previewPageView;
    private Button exportButton;
    private Bitmap displayedBitmap;
    private WorkspaceBrowserPage currentDirectoryPage;
    private String currentDirectory = "";
    private int currentDirectoryPageIndex;
    private String previewRelativePath = "";
    private int previewPageIndex;
    private int previewPageCount = 1;
    private PendingExport pendingExport;
    private long operationGeneration;
    private boolean busy;
    private boolean destroyed;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.attach(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            theme = new UiTheme(this);
            repository = WorkspaceBrowserRepository.create(this);
            setContentView(buildContent());
            loadDirectory("", 0);
        } catch (Throwable error) {
            showEmergencyScreen();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        operationGeneration++;
        handler.removeCallbacksAndMessages(null);
        operations.shutdownNow();
        clearPreviewVisuals();
        pendingExport = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!previewRelativePath.isEmpty()) {
            operationGeneration++;
            setBusy(false, 0);
            showDirectoryPanel();
            return;
        }
        if (!currentDirectory.isEmpty()) {
            loadDirectory(parentDirectory(currentDirectory), 0);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == IMPORT_REQUEST_CODE) {
            boolean expected = pendingImport;
            pendingImport = false;
            updateControls();
            if (expected && resultCode == RESULT_OK && data != null && data.getData() != null) {
                WorkspaceImportGrant grant = WorkspaceImportGrant.fromResultIntentFlags(
                    data.getFlags(), Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
                runImport(data.getData(), grant);
            } else {
                restoreVisibleStatus();
            }
            return;
        }
        if (requestCode != FILE_EXPORT_REQUEST_CODE
            && requestCode != DIRECTORY_EXPORT_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        final PendingExport export = pendingExport;
        pendingExport = null;
        updateControls();
        final Uri destination = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || destination == null || export == null
            || !export.matchesRequestCode(requestCode)) {
            restoreVisibleStatus();
            return;
        }
        if (export.directory) {
            runDirectoryExport(export.repository, export.relativePath, destination);
        } else {
            runExport(export.repository, export.relativePath, destination);
        }
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(theme.dp(14), theme.dp(18), theme.dp(14), theme.dp(12));
        root.setBackgroundColor(theme.page);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = theme.compactButton(getString(R.string.browser_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
        topBar.addView(back);
        TextView title = theme.text(getString(R.string.browser_title), 24, theme.primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParameters = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleParameters.leftMargin = theme.dp(12);
        titleParameters.rightMargin = theme.dp(8);
        topBar.addView(title, titleParameters);
        Button refresh = theme.compactButton(getString(R.string.browser_refresh));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshVisibleContent();
            }
        });
        topBar.addView(refresh);
        root.addView(topBar);

        LinearLayout fileActions = new LinearLayout(this);
        fileActions.setGravity(Gravity.CENTER_VERTICAL);
        scopeButton = theme.compactButton(scopeLabel(repository.getArea()));
        scopeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseScope();
            }
        });
        fileActions.addView(scopeButton);
        importButton = theme.compactButton(getString(R.string.browser_import));
        importButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseImport();
            }
        });
        fileActions.addView(importButton);
        theme.addWithTopMargin(root, fileActions, 8);
        scopeDescriptionView = theme.text(scopeDescription(), 12, theme.secondary);
        theme.addWithTopMargin(root, scopeDescriptionView, 5);

        breadcrumbRow = new LinearLayout(this);
        breadcrumbRow.setOrientation(LinearLayout.HORIZONTAL);
        breadcrumbRow.setGravity(Gravity.CENTER_VERTICAL);
        breadcrumbScroll = new HorizontalScrollView(this);
        breadcrumbScroll.setHorizontalScrollBarEnabled(false);
        breadcrumbScroll.addView(breadcrumbRow);
        theme.addWithTopMargin(root, breadcrumbScroll, 10);

        statusView = theme.text(getString(R.string.browser_loading), 13, theme.secondary);
        statusView.setPadding(theme.dp(12), theme.dp(9), theme.dp(12), theme.dp(9));
        statusView.setBackground(theme.background(theme.surfaceRaised, theme.border, 12));
        theme.addWithTopMargin(root, statusView, 8);

        directoryPanel = buildDirectoryPanel();
        LinearLayout.LayoutParams contentParameters = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        contentParameters.topMargin = theme.dp(8);
        root.addView(directoryPanel, contentParameters);

        previewPanel = buildPreviewPanel();
        previewPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams previewParameters = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        previewParameters.topMargin = theme.dp(8);
        root.addView(previewPanel, previewParameters);
        return root;
    }

    private LinearLayout buildDirectoryPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        entryAdapter = new EntryAdapter();
        entryList = new ListView(this);
        entryList.setAdapter(entryAdapter);
        entryList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        entryList.setDividerHeight(theme.dp(8));
        entryList.setSelector(theme.touchBackground(Color.TRANSPARENT, Color.TRANSPARENT, 14));
        entryList.setDrawSelectorOnTop(true);
        entryList.setClipToPadding(false);
        entryList.setPadding(0, theme.dp(2), 0, theme.dp(2));
        entryList.setEmptyView(null);
        entryList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(
                AdapterView<?> parent,
                View view,
                int position,
                long id
            ) {
                openEntry(entryAdapter.getItem(position));
            }
        });
        panel.addView(entryList, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        ));

        LinearLayout paging = new LinearLayout(this);
        paging.setGravity(Gravity.CENTER_VERTICAL);
        previousDirectoryPageButton = theme.compactButton(
            getString(R.string.browser_previous_page)
        );
        previousDirectoryPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadDirectory(currentDirectory, currentDirectoryPageIndex - 1);
            }
        });
        paging.addView(previousDirectoryPageButton);
        directoryPageView = theme.text("", 13, theme.secondary);
        directoryPageView.setGravity(Gravity.CENTER);
        paging.addView(directoryPageView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        nextDirectoryPageButton = theme.compactButton(
            getString(R.string.browser_next_page)
        );
        nextDirectoryPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadDirectory(currentDirectory, currentDirectoryPageIndex + 1);
            }
        });
        paging.addView(nextDirectoryPageButton);
        theme.addWithTopMargin(panel, paging, 8);

        directoryExportButton = theme.primaryButton(
            getString(R.string.browser_directory_export)
        );
        directoryExportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prepareDirectoryExport();
            }
        });
        theme.addWithTopMargin(panel, directoryExportButton, 8);
        return panel;
    }

    private LinearLayout buildPreviewPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        previewTitleView = theme.text("", 20, theme.primary);
        previewTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        previewTitleView.setSingleLine(true);
        previewTitleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        panel.addView(previewTitleView);
        previewDetailsView = theme.text("", 13, theme.secondary);
        previewDetailsView.setLineSpacing(0.0f, 1.14f);
        theme.addWithTopMargin(panel, previewDetailsView, 5);

        FrameLayout body = new FrameLayout(this);
        body.setBackground(theme.background(theme.surface, theme.border, 14));
        previewTextView = theme.text("", 13, theme.primary);
        previewTextView.setTypeface(Typeface.MONOSPACE);
        previewTextView.setTextIsSelectable(true);
        previewTextView.setPadding(
            theme.dp(14),
            theme.dp(12),
            theme.dp(14),
            theme.dp(12)
        );
        HorizontalScrollView horizontalText = new HorizontalScrollView(this);
        horizontalText.addView(previewTextView);
        previewTextScroll = new ScrollView(this);
        previewTextScroll.addView(horizontalText);
        body.addView(previewTextScroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        previewImageView = new ImageView(this);
        previewImageView.setAdjustViewBounds(true);
        previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImageView.setPadding(
            theme.dp(10),
            theme.dp(10),
            theme.dp(10),
            theme.dp(10)
        );
        previewImageView.setVisibility(View.GONE);
        body.addView(previewImageView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        LinearLayout.LayoutParams bodyParameters = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        bodyParameters.topMargin = theme.dp(8);
        panel.addView(body, bodyParameters);

        LinearLayout paging = new LinearLayout(this);
        paging.setGravity(Gravity.CENTER_VERTICAL);
        previousPreviewPageButton = theme.compactButton(
            getString(R.string.browser_previous_content)
        );
        previousPreviewPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadPreview(previewRelativePath, previewPageIndex - 1);
            }
        });
        paging.addView(previousPreviewPageButton);
        previewPageView = theme.text("", 13, theme.secondary);
        previewPageView.setGravity(Gravity.CENTER);
        paging.addView(previewPageView, new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ));
        nextPreviewPageButton = theme.compactButton(
            getString(R.string.browser_next_content)
        );
        nextPreviewPageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                loadPreview(previewRelativePath, previewPageIndex + 1);
            }
        });
        paging.addView(nextPreviewPageButton);
        theme.addWithTopMargin(panel, paging, 8);

        exportButton = theme.primaryButton(getString(R.string.browser_export));
        exportButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                prepareExport();
            }
        });
        theme.addWithTopMargin(panel, exportButton, 8);
        return panel;
    }

    private String scopeLabel(WorkspaceBrowserArea scope) {
        return getString(scope == WorkspaceBrowserArea.WORKSPACE
            ? R.string.browser_workspace_root
            : scope == WorkspaceBrowserArea.MANAGED_PACKAGES
                ? R.string.browser_managed_packages : R.string.browser_user_packages);
    }

    private String scopeDescription() {
        return getString(repository.getArea() == WorkspaceBrowserArea.WORKSPACE
            ? R.string.browser_workspace_scope_hint : R.string.browser_package_scope_hint);
    }

    private void chooseScope() {
        if (busy || pendingExport != null || pendingImport) {
            return;
        }
        final WorkspaceBrowserArea[] scopes = WorkspaceBrowserArea.values();
        String[] labels = new String[scopes.length];
        for (int index = 0; index < scopes.length; index++) {
            labels[index] = scopeLabel(scopes[index]);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.browser_choose_scope)
            .setSingleChoiceItems(labels, repository.getArea().ordinal(),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        switchScope(scopes[which]);
                    }
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void switchScope(final WorkspaceBrowserArea scope) {
        if (busy || pendingExport != null || pendingImport) {
            return;
        }
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_loading);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceBrowserRepository selected =
                        WorkspaceBrowserRepository.create(WorkspaceBrowserActivity.this, scope);
                    final WorkspaceBrowserPage page = selected.list("", 0);
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            repository = selected;
                            scopeButton.setText(scopeLabel(scope));
                            scopeDescriptionView.setText(scopeDescription());
                            renderDirectory(page);
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_directory_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void chooseImport() {
        if (busy || pendingExport != null || pendingImport) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pendingImport = true;
        updateControls();
        try {
            startActivityForResult(intent, IMPORT_REQUEST_CODE);
        } catch (RuntimeException error) {
            pendingImport = false;
            updateControls();
            Toast.makeText(this, R.string.document_picker_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void runImport(final Uri source, final WorkspaceImportGrant grant) {
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_importing);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final ImportedWorkspaceFile imported = WorkspaceFileImporter.importDocument(
                        WorkspaceBrowserActivity.this, source, grant,
                        WorkspaceImportLimits.MAXIMUM_FILE_BYTES
                    );
                    final WorkspaceBrowserRepository selected = WorkspaceBrowserRepository.create(
                        WorkspaceBrowserActivity.this, WorkspaceBrowserArea.WORKSPACE
                    );
                    final WorkspaceBrowserPage page = selected.list("imports", 0);
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            repository = selected;
                            scopeButton.setText(scopeLabel(selected.getArea()));
                            scopeDescriptionView.setText(scopeDescription());
                            renderDirectory(page);
                            String message = getString(
                                R.string.browser_imported, imported.getRelativePath()
                            );
                            statusView.setText(message);
                            Toast.makeText(WorkspaceBrowserActivity.this, message,
                                Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_import_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void refreshVisibleContent() {
        if (previewRelativePath.isEmpty()) {
            loadDirectory(currentDirectory, currentDirectoryPageIndex);
        } else {
            loadPreview(previewRelativePath, previewPageIndex);
        }
    }

    private void openEntry(WorkspaceBrowserEntry entry) {
        if (entry == null || busy) {
            return;
        }
        if (!entry.isOpenable()) {
            Toast.makeText(
                this,
                unavailableMessage(entry.getUnavailableReason()),
                Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (entry.getKind() == WorkspaceBrowserEntry.Kind.DIRECTORY) {
            loadDirectory(entry.getRelativePath(), 0);
        } else if (entry.getKind() == WorkspaceBrowserEntry.Kind.FILE) {
            loadPreview(entry.getRelativePath(), 0);
        }
    }

    private void loadDirectory(final String relativeDirectory, final int pageIndex) {
        if (repository == null || pageIndex < 0) {
            return;
        }
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_loading);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceBrowserPage page = repository.list(
                        relativeDirectory,
                        pageIndex
                    );
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            renderDirectory(page);
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_directory_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void loadPreview(final String relativePath, final int pageIndex) {
        if (repository == null || relativePath == null || relativePath.isEmpty()
            || pageIndex < 0) {
            return;
        }
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_preview_loading);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                PreviewResult result = null;
                try {
                    WorkspaceFilePreview preview = repository.preview(relativePath, pageIndex);
                    result = new PreviewResult(preview, decodeImage(preview));
                    final PreviewResult completed = result;
                    result = null;
                    postPreviewResult(generation, completed);
                } catch (Throwable error) {
                    if (result != null) {
                        result.recycle();
                    }
                    postFailure(generation, R.string.browser_preview_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void renderDirectory(WorkspaceBrowserPage page) {
        currentDirectoryPage = page;
        currentDirectory = page.getRelativeDirectory();
        currentDirectoryPageIndex = page.getPageIndex();
        previewRelativePath = "";
        previewPageIndex = 0;
        previewPageCount = 1;
        clearPreviewVisuals();
        entryAdapter.replace(page.getEntries());
        renderBreadcrumbs(page.getBreadcrumbs());
        directoryPageView.setText(getString(
            R.string.browser_page,
            page.getPageIndex() + 1,
            page.getPageCount()
        ));
        statusView.setText(page.isScanTruncated()
            ? getString(
                R.string.browser_directory_truncated,
                page.getTotalEntryCount()
            )
            : getResources().getQuantityString(
                R.plurals.browser_directory_entries,
                page.getTotalEntryCount(),
                page.getTotalEntryCount()
            ));
        showDirectoryPanel();
        setBusy(false, 0);
    }

    private void renderPreview(PreviewResult result) {
        WorkspaceFilePreview preview = result.preview;
        clearPreviewVisuals();
        previewRelativePath = preview.getRelativePath();
        previewPageIndex = preview.getPageIndex();
        previewPageCount = preview.getPageCount();
        previewTitleView.setText(preview.getDisplayName());
        previewDetailsView.setText(getString(
            R.string.browser_preview_details,
            preview.getRelativePath(),
            formatBytes(preview.getByteCount()),
            previewType(preview)
        ));
        previewPageView.setText(getString(
            R.string.browser_content_page,
            preview.getPageIndex() + 1,
            preview.getPageCount()
        ));
        if (preview.getKind() == WorkspaceFilePreview.Kind.IMAGE) {
            displayedBitmap = result.takeBitmap();
            previewImageView.setImageBitmap(displayedBitmap);
            previewImageView.setContentDescription(getString(
                R.string.browser_image_description,
                preview.getDisplayName()
            ));
            previewImageView.setVisibility(View.VISIBLE);
            previewTextScroll.setVisibility(View.GONE);
        } else {
            previewTextView.setText(preview.getRenderedContent());
            previewTextScroll.setVisibility(View.VISIBLE);
            previewImageView.setVisibility(View.GONE);
            previewTextScroll.post(new Runnable() {
                @Override
                public void run() {
                    previewTextScroll.scrollTo(0, 0);
                }
            });
        }
        directoryPanel.setVisibility(View.GONE);
        previewPanel.setVisibility(View.VISIBLE);
        statusView.setText(R.string.browser_preview_ready);
        result.recycle();
        setBusy(false, 0);
    }

    private void showDirectoryPanel() {
        previewRelativePath = "";
        previewPanel.setVisibility(View.GONE);
        directoryPanel.setVisibility(View.VISIBLE);
        clearPreviewVisuals();
        if (currentDirectoryPage != null) {
            statusView.setText(currentDirectoryPage.isScanTruncated()
                ? getString(
                    R.string.browser_directory_truncated,
                    currentDirectoryPage.getTotalEntryCount()
                )
                : getResources().getQuantityString(
                    R.plurals.browser_directory_entries,
                    currentDirectoryPage.getTotalEntryCount(),
                    currentDirectoryPage.getTotalEntryCount()
                ));
        }
        updateControls();
    }

    private void renderBreadcrumbs(List<WorkspaceBreadcrumb> breadcrumbs) {
        breadcrumbRow.removeAllViews();
        for (int index = 0; index < breadcrumbs.size(); index++) {
            final WorkspaceBreadcrumb breadcrumb = breadcrumbs.get(index);
            if (index > 0) {
                TextView separator = theme.text("/", 14, theme.secondary);
                separator.setPadding(theme.dp(4), 0, theme.dp(4), 0);
                breadcrumbRow.addView(separator);
            }
            String label = breadcrumb.getRelativePath().isEmpty()
                ? scopeLabel(repository.getArea())
                : breadcrumb.getLabel();
            Button button = theme.compactButton(label);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    loadDirectory(breadcrumb.getRelativePath(), 0);
                }
            });
            breadcrumbRow.addView(button);
        }
        breadcrumbScroll.post(new Runnable() {
            @Override
            public void run() {
                breadcrumbScroll.fullScroll(View.FOCUS_RIGHT);
            }
        });
    }

    private void prepareExport() {
        if (busy || previewRelativePath.isEmpty()) {
            return;
        }
        final String relativePath = previewRelativePath;
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_export_checking);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceFileExporter.FileExport source =
                        repository.inspectExport(relativePath);
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            openExportDocument(relativePath, source);
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_export_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void openExportDocument(
        String relativePath,
        WorkspaceFileExporter.FileExport source
    ) {
        pendingExport = PendingExport.file(relativePath, repository);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(source.getMimeType());
        intent.putExtra(Intent.EXTRA_TITLE, source.getDisplayName());
        try {
            startActivityForResult(intent, FILE_EXPORT_REQUEST_CODE);
            statusView.setText(R.string.browser_preview_ready);
        } catch (RuntimeException error) {
            pendingExport = null;
            Toast.makeText(this, R.string.document_picker_open_failed, Toast.LENGTH_LONG).show();
        } finally {
            setBusy(false, 0);
        }
    }

    private void runExport(
        final WorkspaceBrowserRepository selected, final String relativePath, final Uri destination
    ) {
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_exporting);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceFileExporter.FileExport exported =
                        selected.export(relativePath, destination);
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(
                                WorkspaceBrowserActivity.this,
                                getString(
                                    R.string.browser_exported,
                                    exported.getDisplayName()
                                ),
                                Toast.LENGTH_LONG
                            ).show();
                            statusView.setText(R.string.browser_preview_ready);
                            setBusy(false, 0);
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_export_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void prepareDirectoryExport() {
        if (busy || currentDirectoryPage == null || !previewRelativePath.isEmpty()) {
            return;
        }
        final String relativeDirectory = currentDirectory;
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_directory_export_checking);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceFileExporter.ArchiveExport archive =
                        repository.inspectArchive(relativeDirectory);
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            openDirectoryExportDocument(relativeDirectory, archive);
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_directory_export_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private void openDirectoryExportDocument(
        String relativeDirectory,
        WorkspaceFileExporter.ArchiveExport archive
    ) {
        pendingExport = PendingExport.directory(relativeDirectory, repository);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, archive.getDisplayName());
        try {
            startActivityForResult(intent, DIRECTORY_EXPORT_REQUEST_CODE);
            statusView.setText(archiveStatus(
                R.plurals.browser_directory_export_target,
                archive
            ));
        } catch (RuntimeException error) {
            pendingExport = null;
            Toast.makeText(this, R.string.document_picker_open_failed, Toast.LENGTH_LONG).show();
            restoreVisibleStatus();
        } finally {
            setBusy(false, 0);
        }
    }

    private void runDirectoryExport(
        final WorkspaceBrowserRepository selected,
        final String relativeDirectory,
        final Uri destination
    ) {
        final long generation = ++operationGeneration;
        setBusy(true, R.string.browser_directory_exporting);
        if (!submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final WorkspaceFileExporter.ArchiveExport exported =
                        selected.exportArchive(relativeDirectory, destination);
                    postResult(generation, new Runnable() {
                        @Override
                        public void run() {
                            String message = archiveStatus(
                                R.plurals.browser_directory_exported,
                                exported
                            );
                            Toast.makeText(
                                WorkspaceBrowserActivity.this,
                                message,
                                Toast.LENGTH_LONG
                            ).show();
                            statusView.setText(message);
                            setBusy(false, 0);
                        }
                    });
                } catch (Throwable error) {
                    postFailure(generation, R.string.browser_directory_export_failed);
                }
            }
        })) {
            postFailure(generation, R.string.browser_operation_rejected);
        }
    }

    private String archiveStatus(
        int pluralResource,
        WorkspaceFileExporter.ArchiveExport archive
    ) {
        String omitted = archive.getOmittedEntryCount() == 0
            ? getString(R.string.browser_archive_omitted_none)
            : getResources().getQuantityString(
                R.plurals.browser_archive_omitted,
                archive.getOmittedEntryCount(),
                archive.getOmittedEntryCount()
            );
        return getResources().getQuantityString(
            pluralResource,
            archive.getFileCount(),
            archive.getFileCount(),
            formatBytes(archive.getByteCount()),
            omitted
        );
    }

    private void restoreVisibleStatus() {
        if (statusView == null) {
            return;
        }
        if (!previewRelativePath.isEmpty()) {
            statusView.setText(R.string.browser_preview_ready);
            return;
        }
        if (currentDirectoryPage != null) {
            statusView.setText(currentDirectoryPage.isScanTruncated()
                ? getString(
                    R.string.browser_directory_truncated,
                    currentDirectoryPage.getTotalEntryCount()
                )
                : getResources().getQuantityString(
                    R.plurals.browser_directory_entries,
                    currentDirectoryPage.getTotalEntryCount(),
                    currentDirectoryPage.getTotalEntryCount()
                ));
        }
    }

    private void setBusy(boolean value, int statusResource) {
        busy = value;
        if (statusResource != 0) {
            statusView.setText(statusResource);
        }
        updateControls();
    }

    private void updateControls() {
        if (theme == null || entryList == null) {
            return;
        }
        boolean pickerIdle = pendingExport == null && !pendingImport;
        theme.setEnabled(scopeButton, !busy && pickerIdle);
        theme.setEnabled(importButton, !busy && pickerIdle);
        boolean directoryVisible = directoryPanel != null
            && directoryPanel.getVisibility() == View.VISIBLE;
        boolean previewVisible = previewPanel != null
            && previewPanel.getVisibility() == View.VISIBLE;
        boolean hasPreviousDirectory = currentDirectoryPage != null
            && currentDirectoryPage.hasPreviousPage();
        boolean hasNextDirectory = currentDirectoryPage != null
            && currentDirectoryPage.hasNextPage();
        theme.setEnabled(previousDirectoryPageButton, !busy && hasPreviousDirectory);
        theme.setEnabled(nextDirectoryPageButton, !busy && hasNextDirectory);
        theme.setEnabled(
            directoryExportButton,
            !busy && directoryVisible && currentDirectoryPage != null
        );
        entryList.setEnabled(!busy && directoryVisible);
        theme.setEnabled(
            previousPreviewPageButton,
            !busy && previewVisible && previewPageIndex > 0
        );
        theme.setEnabled(
            nextPreviewPageButton,
            !busy && previewVisible && previewPageIndex + 1 < previewPageCount
        );
        theme.setEnabled(exportButton, !busy && previewVisible && !previewRelativePath.isEmpty());
    }

    private boolean submit(Runnable operation) {
        try {
            operations.execute(operation);
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    private void postResult(final long generation, final Runnable result) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || generation != operationGeneration) {
                    return;
                }
                result.run();
            }
        });
    }

    private void postPreviewResult(
        final long generation,
        final PreviewResult result
    ) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || generation != operationGeneration) {
                    result.recycle();
                    return;
                }
                renderPreview(result);
            }
        });
    }

    private void postFailure(final long generation, final int messageResource) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || generation != operationGeneration) {
                    return;
                }
                statusView.setText(messageResource);
                Toast.makeText(
                    WorkspaceBrowserActivity.this,
                    messageResource,
                    Toast.LENGTH_LONG
                ).show();
                setBusy(false, 0);
            }
        });
    }

    private void clearPreviewVisuals() {
        if (previewTextView != null) {
            previewTextView.setText("");
        }
        if (previewImageView != null) {
            previewImageView.setImageDrawable(null);
            previewImageView.setContentDescription(null);
        }
        if (displayedBitmap != null) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
    }

    private Bitmap decodeImage(WorkspaceFilePreview preview) throws IOException {
        if (preview.getKind() != WorkspaceFilePreview.Kind.IMAGE) {
            return null;
        }
        byte[] bytes = preview.getImageBytes();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > WorkspaceBrowserLimits.MAXIMUM_DECODED_IMAGE_EDGE
                || bounds.outHeight > WorkspaceBrowserLimits.MAXIMUM_DECODED_IMAGE_EDGE) {
                throw new IOException("Workspace image dimensions are outside the preview limit");
            }
            int sample = 1;
            while (decodedPixels(bounds.outWidth, bounds.outHeight, sample)
                    > WorkspaceBrowserLimits.MAXIMUM_DECODED_IMAGE_PIXELS
                || Math.max(bounds.outWidth / sample, bounds.outHeight / sample)
                    > WorkspaceBrowserLimits.TARGET_IMAGE_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null || decodedPixels(bitmap.getWidth(), bitmap.getHeight(), 1)
                > WorkspaceBrowserLimits.MAXIMUM_DECODED_IMAGE_PIXELS) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                throw new IOException("Workspace image could not be decoded safely");
            }
            return bitmap;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static long decodedPixels(int width, int height, int sample) {
        long sampledWidth = Math.max(1, width / sample);
        long sampledHeight = Math.max(1, height / sample);
        return sampledWidth * sampledHeight;
    }

    private static String parentDirectory(String relativeDirectory) {
        int separator = relativeDirectory == null
            ? -1
            : relativeDirectory.lastIndexOf('/');
        return separator < 0 ? "" : relativeDirectory.substring(0, separator);
    }

    private String previewType(WorkspaceFilePreview preview) {
        if (preview.getKind() == WorkspaceFilePreview.Kind.IMAGE) {
            return getString(R.string.browser_type_image);
        }
        if (preview.getKind() == WorkspaceFilePreview.Kind.TEXT) {
            return getString(R.string.browser_type_text);
        }
        return getString(R.string.browser_type_binary);
    }

    private String unavailableMessage(String reason) {
        if ("credential".equals(reason)) {
            return getString(R.string.browser_unavailable_credential);
        }
        if ("symbolic-link".equals(reason)) {
            return getString(R.string.browser_unavailable_symlink);
        }
        if ("hard-link".equals(reason)) {
            return getString(R.string.browser_unavailable_hardlink);
        }
        if ("special-entry".equals(reason)) {
            return getString(R.string.browser_unavailable_special);
        }
        if ("unsafe-name".equals(reason)) {
            return getString(R.string.browser_unavailable_name);
        }
        return getString(R.string.browser_unavailable_unreadable);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(
            Locale.ROOT,
            "%.1f GiB",
            bytes / (1024.0 * 1024.0 * 1024.0)
        );
    }

    private void showEmergencyScreen() {
        TextView emergency = new TextView(this);
        emergency.setPadding(32, 48, 32, 48);
        emergency.setText(R.string.browser_initialization_failed);
        setContentView(emergency);
    }

    private final class EntryAdapter extends BaseAdapter {
        private final List<WorkspaceBrowserEntry> entries =
            new ArrayList<WorkspaceBrowserEntry>();

        void replace(List<WorkspaceBrowserEntry> values) {
            entries.clear();
            entries.addAll(values);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public WorkspaceBrowserEntry getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            EntryRow row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof EntryRow) {
                row = (EntryRow) convertView.getTag();
            } else {
                LinearLayout container = new LinearLayout(WorkspaceBrowserActivity.this);
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(
                    theme.dp(14),
                    theme.dp(13),
                    theme.dp(14),
                    theme.dp(13)
                );
                container.setBackground(theme.background(theme.surface, theme.border, 14));
                TextView name = theme.text("", 16, theme.primary);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                container.addView(name);
                TextView metadata = theme.text("", 12, theme.secondary);
                metadata.setSingleLine(true);
                metadata.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams metadataParameters = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                metadataParameters.topMargin = theme.dp(3);
                container.addView(metadata, metadataParameters);
                row = new EntryRow(container, name, metadata);
                container.setTag(row);
            }
            WorkspaceBrowserEntry entry = getItem(position);
            if (entry.getKind() == WorkspaceBrowserEntry.Kind.DIRECTORY) {
                row.name.setText(getString(
                    R.string.browser_directory_entry,
                    entry.getDisplayName()
                ));
                row.metadata.setText(getString(R.string.browser_directory_metadata));
                row.name.setTextColor(theme.primary);
            } else if (entry.getKind() == WorkspaceBrowserEntry.Kind.FILE) {
                row.name.setText(getString(
                    R.string.browser_file_entry,
                    entry.getDisplayName()
                ));
                String modified = entry.getLastModifiedMillis() <= 0L
                    ? getString(R.string.browser_modified_unknown)
                    : DateFormat.getDateTimeInstance(
                        DateFormat.SHORT,
                        DateFormat.SHORT
                    ).format(new Date(entry.getLastModifiedMillis()));
                row.metadata.setText(getString(
                    R.string.browser_file_metadata,
                    formatBytes(entry.getByteCount()),
                    modified
                ));
                row.name.setTextColor(theme.primary);
            } else {
                row.name.setText(getString(
                    R.string.browser_unavailable_entry,
                    entry.getDisplayName()
                ));
                row.metadata.setText(unavailableMessage(entry.getUnavailableReason()));
                row.name.setTextColor(theme.secondary);
            }
            row.container.setContentDescription(
                row.name.getText() + ". " + row.metadata.getText()
            );
            return row.container;
        }
    }

    private static final class EntryRow {
        final LinearLayout container;
        final TextView name;
        final TextView metadata;

        EntryRow(LinearLayout container, TextView name, TextView metadata) {
            this.container = container;
            this.name = name;
            this.metadata = metadata;
        }
    }

    private static final class PendingExport {
        final boolean directory;
        final String relativePath;

        final WorkspaceBrowserRepository repository;

        private PendingExport(
            boolean directory, String relativePath, WorkspaceBrowserRepository repository
        ) {
            this.repository = repository;
            this.directory = directory;
            this.relativePath = relativePath;
        }

        static PendingExport file(
            String relativePath, WorkspaceBrowserRepository repository
        ) {
            return new PendingExport(false, relativePath, repository);
        }

        static PendingExport directory(
            String relativePath, WorkspaceBrowserRepository repository
        ) {
            return new PendingExport(true, relativePath, repository);
        }

        boolean matchesRequestCode(int requestCode) {
            return directory
                ? requestCode == DIRECTORY_EXPORT_REQUEST_CODE
                : requestCode == FILE_EXPORT_REQUEST_CODE;
        }
    }

    private static final class PreviewResult {
        final WorkspaceFilePreview preview;
        private Bitmap bitmap;

        PreviewResult(WorkspaceFilePreview preview, Bitmap bitmap) {
            this.preview = preview;
            this.bitmap = bitmap;
        }

        Bitmap takeBitmap() {
            Bitmap result = bitmap;
            bitmap = null;
            return result;
        }

        void recycle() {
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
        }
    }
}
