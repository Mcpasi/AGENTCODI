package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class WorkspaceLayout {
    private final File root;
    private final File workspace;
    private final File imports;
    private final File state;
    private final File logs;
    private final File home;
    private final File packagePrefix;
    private final File codexHome;

    private WorkspaceLayout(
        File root,
        File workspace,
        File imports,
        File state,
        File logs,
        File home,
        File packagePrefix,
        File codexHome
    ) {
        this.root = root;
        this.workspace = workspace;
        this.imports = imports;
        this.state = state;
        this.logs = logs;
        this.home = home;
        this.packagePrefix = packagePrefix;
        this.codexHome = codexHome;
    }

    public static WorkspaceLayout create(File appFilesDirectory) throws IOException {
        File canonicalBase = prepareCanonicalBase(appFilesDirectory);
        File root = secureChild(canonicalBase, "agentcodi");
        File workspace = secureChild(root, "workspace");
        File imports = secureChild(workspace, "imports");
        File state = secureChild(root, "state");
        File logs = secureChild(root, "logs");
        File home = secureChild(root, "home");
        PackageBootstrap.recoverPrefix(canonicalBase, state);
        File packagePrefix = secureChild(canonicalBase, "usr");
        // Keep earlier installations in place: native packages may embed this path.
        File legacyPrefix = secureChild(home, ".local");
        secureChild(home, ".npm");
        secureChild(home, ".cache");
        for (File prefix : new File[] {packagePrefix, legacyPrefix}) {
            for (String directory : new String[] {"bin", "lib", "include", "share", "etc", "tmp"}) {
                secureChild(prefix, directory);
            }
        }
        ensureSeparated(packagePrefix, home);
        ensureSeparated(packagePrefix, workspace);
        File codexHome = secureChild(root, "codex-home");
        ensureSeparated(workspace, codexHome);
        ensureSeparated(packagePrefix, codexHome);
        validateRuntimeConfigurationFiles(codexHome);
        validateCanonicalCredential(codexHome);
        return new WorkspaceLayout(
            root,
            workspace,
            imports,
            state,
            logs,
            home,
            packagePrefix,
            codexHome
        );
    }

    static File createStateDirectory(File appFilesDirectory) throws IOException {
        File canonicalBase = prepareCanonicalBase(appFilesDirectory);
        File root = secureChild(canonicalBase, "agentcodi");
        return secureChild(root, "state");
    }

    public File getRoot() {
        return root;
    }

    public File getWorkspace() {
        return workspace;
    }

    public File getImports() {
        return imports;
    }

    /**
     * One-time-compatible migration of recognized APK aliases. Retired storage
     * is optional: do not create, traverse or validate it as part of startup.
     * Existing archives, activation markers and user files remain untouched.
     */
    public void retireLegacyToolAliases(File shellExecutable) throws IOException {
        Path legacyBin = root.toPath().resolve("tool-bin");
        if (Files.isSymbolicLink(legacyBin)
            || !Files.isDirectory(legacyBin, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        File shell = requirePackagedExecutable(shellExecutable);
        for (String name : new String[] {
            "node", "npm", "python", "python3", "rg", "agentcodi-toolchain"
        }) {
            Path alias = legacyBin.resolve(name);
            if (Files.isSymbolicLink(alias)) {
                Path target = Files.readSymbolicLink(alias);
                if (target.getFileName() != null
                    && shell.getName().equals(target.getFileName().toString())) {
                    Files.delete(alias);
                }
            }
        }
    }

    public File getState() {
        return state;
    }

    private File requirePackagedExecutable(File executable) throws IOException {
        if (executable == null) {
            throw new IllegalArgumentException("Packaged shell executable is required");
        }
        rejectSymbolicLink(executable);
        File canonical = executable.getCanonicalFile();
        if (!Files.isRegularFile(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)
            || !canonical.canExecute()) {
            throw new IOException("Packaged shell is not a regular executable file");
        }
        if (contains(root.getCanonicalPath(), canonical.getCanonicalPath())) {
            throw new IOException("Packaged shell must remain outside private writable storage");
        }
        return canonical;
    }

    public File getLogs() {
        return logs;
    }

    public File getHome() {
        return home;
    }

    /** Managed installation prefix in files/usr, outside HOME, workspace and CODEX_HOME. */
    public File getPackagePrefix() {
        return packagePrefix;
    }

    public File getCodexHome() {
        return codexHome;
    }

    private static File prepareCanonicalBase(File appFilesDirectory) throws IOException {
        if (appFilesDirectory == null) {
            throw new IllegalArgumentException("appFilesDirectory must not be null");
        }
        rejectSymbolicLink(appFilesDirectory);
        ensureDirectory(appFilesDirectory);
        return appFilesDirectory.getCanonicalFile();
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
        if (!restrictFileToOwner(credential)) {
            throw new IOException("Could not enforce owner-only Codex credential permissions");
        }
    }

    private static void validateRuntimeConfigurationFiles(File codexHome) throws IOException {
        String[] names = {"config.toml", "requirements.toml", "hooks.json"};
        for (String name : names) {
            File configuration = new File(codexHome, name);
            rejectSymbolicLink(configuration);
            ensureContained(codexHome, configuration.getCanonicalFile());
            if (!Files.exists(configuration.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(
                configuration.toPath(),
                LinkOption.NOFOLLOW_LINKS
            )) {
                throw new IOException("Codex configuration must be a regular file");
            }
            if (!restrictFileToOwner(configuration)) {
                throw new IOException(
                    "Could not enforce owner-only Codex configuration permissions"
                );
            }
        }
    }

    private static boolean restrictFileToOwner(File file) {
        return file.setReadable(false, false)
            && file.setWritable(false, false)
            && file.setExecutable(false, false)
            && file.setReadable(true, true)
            && file.setWritable(true, true);
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
