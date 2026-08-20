package de.agentcodi.runtime;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import de.agentcodi.storage.WorkspaceImageFile;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.IOException;
import java.io.OutputStream;

public final class WorkspaceImageExporter {
    public static final long MAXIMUM_IMAGE_BYTES = 64L * 1024L * 1024L;

    private WorkspaceImageExporter() {
    }

    public static ImageExport inspect(Context context, String sourcePath) throws IOException {
        WorkspaceImageFile image = inspectImage(context, sourcePath);
        return new ImageExport(
            image.getDisplayName(),
            image.getMimeType(),
            image.getByteCount()
        );
    }

    public static ImageExport export(
        Context context,
        String sourcePath,
        Uri destination
    ) throws IOException {
        if (destination == null || !"content".equalsIgnoreCase(destination.getScheme())) {
            throw new IllegalArgumentException("destination must be an Android content URI");
        }
        WorkspaceLayout layout = layout(context);
        WorkspaceImageFile.inspect(
            layout.getWorkspace(),
            sourcePath,
            MAXIMUM_IMAGE_BYTES,
            NativeWorkspaceFileAccess.opener()
        );
        ContentResolver resolver = context.getContentResolver();
        OutputStream output = resolver.openOutputStream(destination, "w");
        if (output == null) {
            throw new IOException("Android did not open the selected export destination");
        }
        WorkspaceImageFile image;
        try (OutputStream destinationStream = output) {
            image = WorkspaceImageFile.copyTo(
                layout.getWorkspace(),
                sourcePath,
                MAXIMUM_IMAGE_BYTES,
                destinationStream,
                NativeWorkspaceFileAccess.opener()
            );
        }
        return new ImageExport(
            image.getDisplayName(),
            image.getMimeType(),
            image.getByteCount()
        );
    }

    private static WorkspaceImageFile inspectImage(Context context, String sourcePath)
        throws IOException {
        WorkspaceLayout layout = layout(context);
        return WorkspaceImageFile.inspect(
            layout.getWorkspace(),
            sourcePath,
            MAXIMUM_IMAGE_BYTES,
            NativeWorkspaceFileAccess.opener()
        );
    }

    private static WorkspaceLayout layout(Context context) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return WorkspaceLayout.create(context.getFilesDir());
    }

    public static final class ImageExport {
        private final String displayName;
        private final String mimeType;
        private final long byteCount;

        private ImageExport(String displayName, String mimeType, long byteCount) {
            this.displayName = displayName;
            this.mimeType = mimeType;
            this.byteCount = byteCount;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getMimeType() {
            return mimeType;
        }

        public long getByteCount() {
            return byteCount;
        }
    }
}
