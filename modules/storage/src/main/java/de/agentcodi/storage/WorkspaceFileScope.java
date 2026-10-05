package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit browser/export roots. Package views never expose HOME or account storage. */
public enum WorkspaceFileScope {
    WORKSPACE,
    MANAGED_PACKAGES,
    USER_PACKAGES;

    public static final String REASON_CREDENTIAL = "credential";

    public File root(WorkspaceLayout layout) throws IOException {
        if (layout == null) {
            throw new IllegalArgumentException("Workspace layout is required");
        }
        File selected = this == WORKSPACE ? layout.getWorkspace()
            : this == MANAGED_PACKAGES ? layout.getPackagePrefix()
            : new File(layout.getHome(), ".local");
        if (Files.isSymbolicLink(selected.toPath())
            || !selected.equals(selected.getCanonicalFile())) {
            throw new IOException("Browser root is no longer a canonical directory");
        }
        WorkspaceFileBoundary.requireWorkspace(selected);
        return selected;
    }

    public WorkspaceFileAccess.Opener opener(final WorkspaceFileAccess.Opener delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("File opener is required");
        }
        return new WorkspaceFileAccess.Opener() {
            @Override
            public WorkspaceFileAccess.Source open(
                File root, String relativePath, long maximumBytes
            ) throws IOException {
                requireCanonicalRoot(root);
                requireAccessible(relativePath);
                return delegate.open(root, relativePath, maximumBytes);
            }
        };
    }

    public WorkspaceDirectoryCatalog.Reader reader(
        final WorkspaceDirectoryCatalog.Reader delegate
    ) {
        if (delegate == null) {
            throw new IllegalArgumentException("Directory reader is required");
        }
        return new WorkspaceDirectoryCatalog.Reader() {
            @Override
            public WorkspaceDirectoryCatalog.Snapshot list(
                File root, String relativeDirectory, int maximumEntries,
                int maximumCharacters, int maximumDepth
            ) throws IOException {
                requireCanonicalRoot(root);
                requireAccessible(relativeDirectory);
                WorkspaceDirectoryCatalog.Snapshot snapshot = delegate.list(
                    root, relativeDirectory, maximumEntries, maximumCharacters, maximumDepth
                );
                List<WorkspaceDirectoryCatalog.Entry> entries =
                    new ArrayList<WorkspaceDirectoryCatalog.Entry>();
                for (WorkspaceDirectoryCatalog.Entry entry : snapshot.getEntries()) {
                    entries.add(isCredentialPath(entry.getRelativePath())
                        ? WorkspaceDirectoryCatalog.Entry.unavailable(
                            entry.getDisplayName(), entry.getRelativePath(),
                            entry.getLastModifiedMillis(), REASON_CREDENTIAL
                        ) : entry);
                }
                return WorkspaceDirectoryCatalog.Snapshot.of(entries, snapshot.isTruncated());
            }
        };
    }

    private static void requireCanonicalRoot(File root) throws IOException {
        if (root == null || Files.isSymbolicLink(root.toPath())
            || !root.equals(root.getCanonicalFile())) {
            throw new IOException("Browser root is no longer canonical");
        }
    }

    public void requireAccessible(String relativePath) throws IOException {
        if (isCredentialPath(relativePath)) {
            throw new IOException("Credential paths are excluded from package file access");
        }
    }

    private boolean isCredentialPath(String relativePath) {
        if (this == WORKSPACE || relativePath == null) {
            return false;
        }
        for (String component : relativePath.split("/", -1)) {
            String name = component.toLowerCase(Locale.ROOT);
            if ("auth.json".equals(name) || "codex-home".equals(name)
                || ".codex".equals(name) || ".ssh".equals(name)
                || ".npmrc".equals(name) || ".pypirc".equals(name)
                || ".netrc".equals(name) || ".git-credentials".equals(name)
                || "auth.conf".equals(name) || "auth.conf.d".equals(name)) {
                return true;
            }
        }
        return false;
    }
}
