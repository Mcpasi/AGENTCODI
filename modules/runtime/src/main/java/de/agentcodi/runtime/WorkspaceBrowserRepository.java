package de.agentcodi.runtime;

import android.content.Context;
import android.net.Uri;

import de.agentcodi.browser.WorkspaceBrowserPage;
import de.agentcodi.browser.WorkspaceFilePreview;
import de.agentcodi.browser.client.WorkspaceFileBrowser;
import de.agentcodi.storage.WorkspaceLayout;

import java.io.File;
import java.io.IOException;

/** Android runtime facade for the pure workspace browser client. */
public final class WorkspaceBrowserRepository {
    private final Context applicationContext;
    private final File workspaceDirectory;
    private final WorkspaceFileBrowser browser;

    private WorkspaceBrowserRepository(
        Context applicationContext,
        File workspaceDirectory,
        WorkspaceFileBrowser browser
    ) {
        this.applicationContext = applicationContext;
        this.workspaceDirectory = workspaceDirectory;
        this.browser = browser;
    }

    public static WorkspaceBrowserRepository create(Context context) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        WorkspaceLayout layout = WorkspaceLayout.create(applicationContext.getFilesDir());
        WorkspaceFileBrowser browser = new WorkspaceFileBrowser(
            layout.getWorkspace(),
            NativeWorkspaceDirectoryCatalog.reader(),
            NativeWorkspaceFileAccess.opener()
        );
        return new WorkspaceBrowserRepository(
            applicationContext,
            layout.getWorkspace(),
            browser
        );
    }

    public WorkspaceBrowserPage list(String relativeDirectory, int pageIndex)
        throws IOException {
        return browser.list(relativeDirectory, pageIndex);
    }

    public WorkspaceFilePreview preview(String relativePath, int pageIndex)
        throws IOException {
        return browser.preview(relativePath, pageIndex);
    }

    public WorkspaceFileExporter.FileExport inspectExport(String relativePath)
        throws IOException {
        return WorkspaceFileExporter.inspect(
            applicationContext,
            absoluteWorkspacePath(relativePath)
        );
    }

    public WorkspaceFileExporter.FileExport export(
        String relativePath,
        Uri destination
    ) throws IOException {
        return WorkspaceFileExporter.export(
            applicationContext,
            absoluteWorkspacePath(relativePath),
            destination
        );
    }

    public WorkspaceFileExporter.ArchiveExport inspectArchive(
        String relativeDirectory
    ) throws IOException {
        return WorkspaceFileExporter.inspectArchive(
            applicationContext,
            requireBrowserDirectory(relativeDirectory)
        );
    }

    public WorkspaceFileExporter.ArchiveExport exportArchive(
        String relativeDirectory,
        Uri destination
    ) throws IOException {
        return WorkspaceFileExporter.exportArchive(
            applicationContext,
            requireBrowserDirectory(relativeDirectory),
            destination
        );
    }

    private String absoluteWorkspacePath(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()
            || relativePath.length() > 2048 || relativePath.startsWith("/")
            || relativePath.endsWith("/") || relativePath.contains("//")) {
            throw new IOException("Workspace browser export path is unsafe");
        }
        String[] components = relativePath.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                || component.indexOf('\\') >= 0 || component.indexOf(':') >= 0) {
                throw new IOException("Workspace browser export path is unsafe");
            }
            for (int index = 0; index < component.length(); index++) {
                char character = component.charAt(index);
                if (character < 0x20 || character == 0x7f) {
                    throw new IOException("Workspace browser export path is unsafe");
                }
            }
        }
        // The export facade performs the authoritative canonical no-follow check.
        return new File(workspaceDirectory, relativePath).getAbsolutePath();
    }

    private static String requireBrowserDirectory(String relativeDirectory)
        throws IOException {
        if (relativeDirectory == null || relativeDirectory.length() > 2048
            || relativeDirectory.startsWith("/") || relativeDirectory.endsWith("/")
            || relativeDirectory.contains("//")) {
            throw new IOException("Workspace browser directory export path is unsafe");
        }
        if (relativeDirectory.isEmpty()) {
            return "";
        }
        String[] components = relativeDirectory.split("/", -1);
        if (components.length > 64) {
            throw new IOException("Workspace browser directory export path is unsafe");
        }
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)
                || component.indexOf('\\') >= 0 || component.indexOf(':') >= 0) {
                throw new IOException("Workspace browser directory export path is unsafe");
            }
            for (int index = 0; index < component.length(); index++) {
                char character = component.charAt(index);
                if (character < 0x20 || character == 0x7f) {
                    throw new IOException("Workspace browser directory export path is unsafe");
                }
            }
        }
        return relativeDirectory;
    }
}
