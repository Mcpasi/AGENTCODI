package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

public final class WorkspaceLayout {
    private final File root;
    private final File workspace;
    private final File state;
    private final File logs;
    private final File home;
    private final File codexHome;

    private WorkspaceLayout(
        File root,
        File workspace,
        File state,
        File logs,
        File home,
        File codexHome
    ) {
        this.root = root;
        this.workspace = workspace;
        this.state = state;
        this.logs = logs;
        this.home = home;
        this.codexHome = codexHome;
    }

    public static WorkspaceLayout create(File appFilesDirectory) throws IOException {
        if (appFilesDirectory == null) {
            throw new IllegalArgumentException("appFilesDirectory must not be null");
        }
        rejectSymbolicLink(appFilesDirectory);
        ensureDirectory(appFilesDirectory);

        File canonicalBase = appFilesDirectory.getCanonicalFile();
        File root = secureChild(canonicalBase, "agentcodi");
        File workspace = secureChild(root, "workspace");
        File state = secureChild(root, "state");
        File logs = secureChild(root, "logs");
        File home = secureChild(root, "home");
        File codexHome = secureChild(root, "codex-home");
        ensureSeparated(workspace, codexHome);
        validateCanonicalCredential(codexHome);
        return new WorkspaceLayout(root, workspace, state, logs, home, codexHome);
    }

    public File getRoot() {
        return root;
    }

    public File getWorkspace() {
        return workspace;
    }

    public File getState() {
        return state;
    }

    public File getLogs() {
        return logs;
    }

    public File getHome() {
        return home;
    }

    public File getCodexHome() {
        return codexHome;
    }

    private static File secureChild(File parent, String name) throws IOException {
        File candidate = new File(parent, name);
        rejectSymbolicLink(candidate);
        File canonicalCandidate = candidate.getCanonicalFile();
        ensureContained(parent, canonicalCandidate);
        ensureDirectory(canonicalCandidate);
        restrictDirectoryToOwner(canonicalCandidate);
        return canonicalCandidate;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.exists() && !directory.isDirectory()) {
            throw new IOException("Expected directory: " + directory);
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create directory: " + directory);
        }
    }

    private static void ensureContained(File parent, File child) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        String prefix = parentPath.endsWith(File.separator)
            ? parentPath
            : parentPath + File.separator;
        if (!childPath.startsWith(prefix)) {
            throw new IOException("Workspace path escaped its parent");
        }
    }

    private static void ensureSeparated(File workspace, File codexHome) throws IOException {
        String workspacePath = workspace.getCanonicalPath();
        String codexHomePath = codexHome.getCanonicalPath();
        if (contains(workspacePath, codexHomePath) || contains(codexHomePath, workspacePath)) {
            throw new IOException("Codex home must remain separate from the workspace");
        }
    }

    private static boolean contains(String parent, String child) {
        return parent.equals(child) || child.startsWith(parent + File.separator);
    }

    private static void validateCanonicalCredential(File codexHome) throws IOException {
        File credential = new File(codexHome, "auth.json");
        rejectSymbolicLink(credential);
        ensureContained(codexHome, credential.getCanonicalFile());
        if (!credential.exists()) {
            return;
        }
        if (!Files.isRegularFile(credential.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Codex credential must be a regular file");
        }
        if (!credential.setReadable(false, false)
            || !credential.setWritable(false, false)
            || !credential.setExecutable(false, false)
            || !credential.setReadable(true, true)
            || !credential.setWritable(true, true)) {
            throw new IOException("Could not enforce owner-only Codex credential permissions");
        }
    }

    private static void rejectSymbolicLink(File path) throws IOException {
        if (Files.isSymbolicLink(path.toPath())) {
            throw new IOException("Symbolic links are not accepted: " + path);
        }
    }

    private static void restrictDirectoryToOwner(File directory) throws IOException {
        if (!directory.setReadable(false, false)
            || !directory.setWritable(false, false)
            || !directory.setExecutable(false, false)
            || !directory.setReadable(true, true)
            || !directory.setWritable(true, true)
            || !directory.setExecutable(true, true)) {
            throw new IOException("Could not enforce owner-only directory permissions");
        }
    }
}
