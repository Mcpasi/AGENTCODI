package de.agentcodi.runtime;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import de.agentcodi.storage.WorkspaceExportTransaction;

import java.io.IOException;
import java.io.OutputStream;

final class AndroidDocumentExportDestination
    implements WorkspaceExportTransaction.Destination {
    private final ContentResolver resolver;
    private final Uri destination;

    AndroidDocumentExportDestination(ContentResolver resolver, Uri destination) {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        this.resolver = resolver;
        this.destination = destination;
    }

    @Override
    public OutputStream open() throws IOException {
        OutputStream output = resolver.openOutputStream(destination, "wt");
        if (output == null) {
            throw new IOException("Android did not open the selected export destination");
        }
        return output;
    }

    @Override
    public void rollback() throws IOException {
        Throwable deleteFailure = null;
        try {
            if (DocumentsContract.deleteDocument(resolver, destination)) {
                return;
            }
        } catch (IOException | RuntimeException failure) {
            deleteFailure = failure;
        }
        try {
            if (resolver.delete(destination, null, null) > 0) {
                return;
            }
        } catch (RuntimeException failure) {
            if (deleteFailure == null) {
                deleteFailure = failure;
            } else {
                deleteFailure.addSuppressed(failure);
            }
        }

        try {
            OutputStream opened = resolver.openOutputStream(destination, "wt");
            if (opened == null) {
                throw new IOException("Android did not reopen the failed export destination");
            }
            try (OutputStream output = opened) {
                output.flush();
            }
        } catch (IOException | RuntimeException truncateFailure) {
            IOException cleanupFailure = new IOException(
                "Android could not remove or clear the failed export destination",
                truncateFailure
            );
            if (deleteFailure != null) {
                cleanupFailure.addSuppressed(deleteFailure);
            }
            throw cleanupFailure;
        }
    }
}
