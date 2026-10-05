package de.agentcodi.runtime;

import android.content.Context;
import android.net.Uri;

import de.agentcodi.browser.WorkspaceBrowserPage;
import de.agentcodi.browser.WorkspaceBrowserArea;
import de.agentcodi.browser.WorkspaceFilePreview;
import de.agentcodi.browser.client.WorkspaceFileBrowser;
import de.agentcodi.storage.WorkspaceLayout;
import de.agentcodi.storage.WorkspaceFileScope;

import java.io.File;
import java.io.IOException;

/** Android runtime facade for the pure workspace browser client. */
public final class WorkspaceBrowserRepository {
    private final Context applicationContext;
    private final File workspaceDirectory;
    private final WorkspaceFileBrowser browser;
    private final WorkspaceFileScope scope;
    private final WorkspaceBrowserArea area;

    private WorkspaceBrowserRepository(
        Context applicationContext,
        File workspaceDirectory,
        WorkspaceFileBrowser browser,
        WorkspaceFileScope scope,
        WorkspaceBrowserArea area
    ) {
        this.applicationContext = applicationContext;
        this.workspaceDirectory = workspaceDirectory;
        this.browser = browser;
        this.scope = scope;
        this.area = area;
    }

    public static WorkspaceBrowserRepository create(Context context) throws IOException {
        return create(context, WorkspaceBrowserArea.WORKSPACE);
    }

    public static WorkspaceBrowserRepository create(Context context, WorkspaceBrowserArea area)
        throws IOException {
        if (area == null) {
            throw new IllegalArgumentException("Browser scope is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }
        WorkspaceFileScope scope = area == WorkspaceBrowserArea.WORKSPACE
            ? WorkspaceFileScope.WORKSPACE
            : area == WorkspaceBrowserArea.MANAGED_PACKAGES
                ? WorkspaceFileScope.MANAGED_PACKAGES : WorkspaceFileScope.USER_PACKAGES;
        WorkspaceLayout layout = WorkspaceLayout.create(applicationContext.getFilesDir());
        WorkspaceFileBrowser browser = new WorkspaceFileBrowser(
            scope.root(layout),
            scope.reader(NativeWorkspaceDirectoryCatalog.reader()),
            scope.opener(NativeWorkspaceFileAccess.opener())
        );
        return new WorkspaceBrowserRepository(
            applicationContext,
            scope.root(layout),
            browser,
            scope,
            area
        );
    }

    public WorkspaceBrowserArea getArea() {
        return area;
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
            absoluteWorkspacePath(relativePath),
            scope
        );
    }

    public WorkspaceFileExporter.FileExport export(
        String relativePath,
        Uri destination
    ) throws IOException {
        return WorkspaceFileExporter.export(
            applicationContext,
            absoluteWorkspacePath(relativePath),
            destination,
            scope
        );
    }

    public WorkspaceFileExporter.ArchiveExport inspectArchive(
        String relativeDirectory
    ) throws IOException {
        return WorkspaceFileExporter.inspectArchive(
            applicationContext,
            requireBrowserDirectory(relativeDirectory),
            scope
        );
    }

    public WorkspaceFileExporter.ArchiveExport exportArchive(
        String relativeDirectory,
        Uri destination
    ) throws IOException {
        return WorkspaceFileExporter.exportArchive(
            applicationContext,
            requireBrowserDirectory(relativeDirectory),
            destination,
            scope
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
