package de.agentcodi.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import de.agentcodi.core.CodexFileMention;
import de.agentcodi.imports.ImportedWorkspaceFile;
import de.agentcodi.imports.WorkspaceImportLimits;
import de.agentcodi.imports.WorkspaceImportSelection;
import de.agentcodi.imports.client.WorkspaceDocumentImporter;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android Storage Access Framework adapter for the pure import modules. */
public final class WorkspaceFileImporter {
    private WorkspaceFileImporter() {
    }

    public static ImportedWorkspaceFile importDocument(
        Context context,
        Uri sourceUri,
        long maximumBytes
    ) throws IOException {
        Context applicationContext = requireContext(context);
        requireContentSource(sourceUri);
        if (maximumBytes <= 0L) {
            throw new IOException("No import capacity remains for this message");
        }
        long boundedMaximum = Math.min(
            maximumBytes,
            WorkspaceImportLimits.MAXIMUM_FILE_BYTES
        );
        ContentResolver resolver = applicationContext.getContentResolver();
        DocumentMetadata metadata = readMetadata(resolver, sourceUri);
        WorkspaceLayout layout = WorkspaceLayout.create(applicationContext.getFilesDir());
        WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter(boundedMaximum);
        final InputStream opened;
        try {
            opened = resolver.openInputStream(sourceUri);
        } catch (RuntimeException error) {
            throw new IOException("Android could not open the selected document", error);
        }
        if (opened == null) {
            throw new IOException("Android did not provide selected document content");
        }
        try (InputStream source = opened) {
            return importer.importDocument(
                layout.getWorkspace(),
                layout.getImports(),
                metadata.displayName,
                metadata.mediaType,
                metadata.byteCount,
                source,
                NativeWorkspaceFileAccess.opener()
            );
        }
    }

    public static List<CodexFileMention> verifyForCodex(
        Context context,
        List<ImportedWorkspaceFile> importedFiles
    ) throws IOException {
        List<ImportedWorkspaceFile> files = WorkspaceImportSelection.copyOf(importedFiles);
        if (files.isEmpty()) {
            return Collections.emptyList();
        }
        WorkspaceLayout layout = WorkspaceLayout.create(requireContext(context).getFilesDir());
        WorkspaceDocumentImporter importer = new WorkspaceDocumentImporter();
        List<CodexFileMention> mentions =
            new ArrayList<CodexFileMention>(files.size());
        for (ImportedWorkspaceFile file : files) {
            mentions.add(importer.verifyForCodex(
                layout.getWorkspace(),
                file,
                NativeWorkspaceFileAccess.opener()
            ));
        }
        return Collections.unmodifiableList(mentions);
    }

    private static DocumentMetadata readMetadata(ContentResolver resolver, Uri sourceUri)
        throws IOException {
        String displayName = "";
        long byteCount = -1L;
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                sourceUri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
            );
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex);
                }
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    byteCount = cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Selected document metadata could not be read", error);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        final String mediaType;
        try {
            mediaType = resolver.getType(sourceUri);
        } catch (RuntimeException error) {
            throw new IOException("Selected document type could not be read", error);
        }
        return new DocumentMetadata(displayName, mediaType, byteCount);
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context applicationContext = context.getApplicationContext();
        return applicationContext == null ? context : applicationContext;
    }

    private static void requireContentSource(Uri sourceUri) throws IOException {
        if (sourceUri == null || !ContentResolver.SCHEME_CONTENT.equals(sourceUri.getScheme())) {
            throw new IOException("Android did not provide a private content document source");
        }
    }

    private static final class DocumentMetadata {
        private final String displayName;
        private final String mediaType;
        private final long byteCount;

        private DocumentMetadata(String displayName, String mediaType, long byteCount) {
            this.displayName = displayName;
            this.mediaType = mediaType;
            this.byteCount = byteCount;
        }
    }
}
