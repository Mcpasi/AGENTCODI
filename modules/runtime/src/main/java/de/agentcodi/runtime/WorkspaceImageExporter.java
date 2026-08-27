package de.agentcodi.runtime;

import android.content.Context;
import android.net.Uri;

import de.agentcodi.storage.WorkspaceExportTransaction;
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
        final Context context,
        final String sourcePath,
        Uri destination
    ) throws IOException {
        if (destination == null || !"content".equalsIgnoreCase(destination.getScheme())) {
            throw new IllegalArgumentException("destination must be an Android content URI");
        }
        requireContext(context);
        WorkspaceImageFile image = WorkspaceExportTransaction.execute(
            new AndroidDocumentExportDestination(
                context.getContentResolver(),
                destination
            ),
            new WorkspaceExportTransaction.Preparation<WorkspaceLayout>() {
                @Override
                public WorkspaceLayout prepare() throws IOException {
                    WorkspaceLayout preparedLayout = layout(context);
                    WorkspaceImageFile.inspect(
                        preparedLayout.getWorkspace(),
                        sourcePath,
                        MAXIMUM_IMAGE_BYTES,
                        NativeWorkspaceFileAccess.opener()
                    );
                    return preparedLayout;
                }
            },
            new WorkspaceExportTransaction.Writer<
                WorkspaceLayout,
                WorkspaceImageFile
            >() {
                @Override
                public WorkspaceImageFile write(
                    WorkspaceLayout preparedLayout,
                    OutputStream destinationStream
                ) throws IOException {
                    return WorkspaceImageFile.copyTo(
                        preparedLayout.getWorkspace(),
                        sourcePath,
                        MAXIMUM_IMAGE_BYTES,
                        destinationStream,
                        NativeWorkspaceFileAccess.opener()
                    );
                }
            }
        );
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
        requireContext(context);
        return WorkspaceLayout.create(context.getFilesDir());
    }

    private static void requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
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
