package de.agentcodi.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

public final class WorkspaceLayout {
    public static final String NODE_TOOL_ALIAS = "node";
    public static final String NPM_TOOL_ALIAS = "npm";
    public static final String PYTHON_TOOL_ALIAS = "python";
    public static final String PYTHON3_TOOL_ALIAS = "python3";
    public static final String TOOLCHAIN_TOOL_ALIAS = "agentcodi-toolchain";

    private final File root;
    private final File workspace;
    private final File imports;
    private final File toolchain;
    private final File toolBin;
    private final File toolRuntime;
    private final File state;
    private final File logs;
    private final File home;
    private final File codexHome;

    private WorkspaceLayout(
        File root,
        File workspace,
        File imports,
        File toolchain,
        File toolBin,
        File toolRuntime,
        File state,
        File logs,
        File home,
        File codexHome
    ) {
        this.root = root;
        this.workspace = workspace;
        this.imports = imports;
        this.toolchain = toolchain;
        this.toolBin = toolBin;
        this.toolRuntime = toolRuntime;
        this.state = state;
        this.logs = logs;
        this.home = home;
        this.codexHome = codexHome;
    }

    public static WorkspaceLayout create(File appFilesDirectory) throws IOException {
        File canonicalBase = prepareCanonicalBase(appFilesDirectory);
        File root = secureChild(canonicalBase, "agentcodi");
        File workspace = secureChild(root, "workspace");
        File imports = secureChild(workspace, "imports");
        File toolchain = secureChild(workspace, "toolchain");
        File toolBin = secureChild(root, "tool-bin");
        File toolRuntime = secureChild(root, "tool-runtime");
        File state = secureChild(root, "state");
        File logs = secureChild(root, "logs");
        File home = secureChild(root, "home");
        File codexHome = secureChild(root, "codex-home");
        ensureSeparated(workspace, codexHome);
        validateRuntimeConfigurationFiles(codexHome);
        validateCanonicalCredential(codexHome);
        return new WorkspaceLayout(
            root,
            workspace,
            imports,
            toolchain,
            toolBin,
            toolRuntime,
            state,
            logs,
            home,
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

    public File getToolchain() {
        return toolchain;
    }

    public File getToolBin() {
        return toolBin;
    }

    public File getToolRuntime() {
        return toolRuntime;
    }

    public void preparePackagedToolAliases(File shellExecutable) throws IOException {
        File canonicalShell = requirePackagedExecutable(shellExecutable);
        prepareToolAlias(NODE_TOOL_ALIAS, canonicalShell);
        prepareToolAlias(NPM_TOOL_ALIAS, canonicalShell);
        prepareToolAlias(PYTHON_TOOL_ALIAS, canonicalShell);
        prepareToolAlias(PYTHON3_TOOL_ALIAS, canonicalShell);
        prepareToolAlias(TOOLCHAIN_TOOL_ALIAS, canonicalShell);
        requireOnlyPackagedToolAliases();
    }

    public File preparePackagedToolRuntime(
        String runtimeName,
        InputStream archive,
        InputStream manifest,
        File nativeLibraryDirectory
    ) throws IOException {
        return PackagedToolRuntime.prepare(
            toolRuntime,
            runtimeName,
            archive,
            manifest,
            nativeLibraryDirectory
        );
    }

    public boolean isNodeRuntimeEnabled(String version) throws IOException {
        return isPackagedToolEnabled("node", version);
    }

    public boolean isNpmRuntimeEnabled(String version) throws IOException {
        return isPackagedToolEnabled("npm", version);
    }

    public boolean isPythonRuntimeEnabled(String version) throws IOException {
        return isPackagedToolEnabled("python", version);
    }

    private boolean isPackagedToolEnabled(String packageName, String version)
        throws IOException {
        if (!packageName.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("Tool package name is invalid");
        }
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("Tool package version is invalid");
        }
        Path installed = toolchain.toPath().resolve("installed");
        if (!Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.isSymbolicLink(installed)
            || !Files.isDirectory(installed, LinkOption.NOFOLLOW_LINKS)
            || !installed.toFile().getCanonicalFile().equals(installed.toFile())) {
            return false;
        }
        Path marker = installed.resolve(packageName + "-" + version);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(marker)) {
            return false;
        }
        BasicFileAttributes attributes = Files.readAttributes(
            marker,
            BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS
        );
        byte[] expected = ("enabled " + version + "\n").getBytes(StandardCharsets.US_ASCII);
        if (!attributes.isRegularFile() || attributes.size() != expected.length) {
            return false;
        }
        try {
            WorkspaceFileBoundary.requireSingleLink(marker);
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
                marker,
                LinkOption.NOFOLLOW_LINKS
            );
            if (!permissions.equals(java.util.EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            ))) {
                return false;
            }
            byte[] actual = Files.readAllBytes(marker);
            try {
                return Arrays.equals(expected, actual);
            } finally {
                Arrays.fill(actual, (byte) 0);
            }
        } catch (IOException error) {
            return false;
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

    private void prepareToolAlias(String name, File target) throws IOException {
        Path alias = toolBin.toPath().resolve(name);
        if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(alias)) {
            if (!Files.isSymbolicLink(alias)) {
                throw new IOException("Packaged tool alias is not a symbolic link: " + name);
            }
            Path existingTarget = Files.readSymbolicLink(alias);
            Path resolvedTarget = existingTarget.isAbsolute()
                ? existingTarget.normalize()
                : alias.getParent().resolve(existingTarget).normalize();
            if (resolvedTarget.toFile().getCanonicalFile().equals(target)) {
                return;
            }
            Files.delete(alias);
        }
        Files.createSymbolicLink(alias, target.toPath());
        if (!Files.isSymbolicLink(alias)
            || !alias.toRealPath().equals(target.toPath().toRealPath())) {
            throw new IOException("Packaged tool alias failed canonical validation: " + name);
        }
    }

    private void requireOnlyPackagedToolAliases() throws IOException {
        int count = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(toolBin.toPath())) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!NODE_TOOL_ALIAS.equals(name)
                    && !NPM_TOOL_ALIAS.equals(name)
                    && !PYTHON_TOOL_ALIAS.equals(name)
                    && !PYTHON3_TOOL_ALIAS.equals(name)
                    && !TOOLCHAIN_TOOL_ALIAS.equals(name)) {
                    throw new IOException("Unexpected entry in packaged tool directory");
                }
                if (!Files.isSymbolicLink(entry)) {
                    throw new IOException("Packaged tool entry is not a symbolic link");
                }
                count++;
            }
        }
        if (count != 5) {
            throw new IOException("Packaged tool aliases are incomplete");
        }
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
