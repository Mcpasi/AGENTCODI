package de.agentcodi.tools;

import static de.agentcodi.tools.CodexRuntimeUpdater.*;
import de.agentcodi.core.JsonCodec;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local artifact selection and read-only Git provenance; never uses a registry. */
final class CodexLocalSource {
    // Reviewed fork authorship correction: adds Mcpasi's Android sandbox credit,
    // retaining Apache-2.0 and the OpenAI, Termux and Ratatui attributions.
    private static final String REVIEWED_FORK_NOTICE_SHA256 =
        "a8b3a4393683f9e8adbdecbafff07df27e34af020e9e23fed905e9a998b81647";

    static final class Options {
        Path source;
        Path archive;
        String sourceRef;
        boolean dryRun;

        static Options parse(Path root, String[] args, Map<String, String> environment) throws IOException {
            Options options = new Options();
            options.source = path(environment.get("AGENTCODI_CODEX_SOURCE_DIR"), root.resolve("../codex-termux"));
            options.archive = path(environment.get("AGENTCODI_CODEX_ARCHIVE"), null);
            boolean archiveSeen = false;
            boolean sourceSeen = false;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if ("--dry-run".equals(arg) && !options.dryRun) options.dryRun = true;
                else if ("--source-dir".equals(arg) && !sourceSeen && i + 1 < args.length) {
                    options.source = path(args[++i], null);
                    sourceSeen = true;
                } else if ("--source-ref".equals(arg) && options.sourceRef == null && i + 1 < args.length) {
                    options.sourceRef = args[++i];
                    require(options.sourceRef.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}"), "Invalid source ref.");
                } else if ("--archive".equals(arg) && !archiveSeen && i + 1 < args.length) {
                    options.archive = path(args[++i], null);
                    archiveSeen = true;
                } else if (!arg.startsWith("-") && arg.endsWith(".tgz") && !archiveSeen) {
                    options.archive = path(arg, null);
                    archiveSeen = true;
                } else throw new IOException("Usage: update-codex-runtime.sh [--dry-run] [--archive FILE.tgz] [--source-dir DIR] [--source-ref REF]");
            }
            require(options.source != null, "Missing local source directory.");
            return options;
        }

        private static Path path(String value, Path fallback) {
            return value == null || value.isEmpty() ? (fallback == null ? null : fallback.toAbsolutePath().normalize())
                : Paths.get(value).toAbsolutePath().normalize();
        }

        static Path cacheDirectory(Path root, Map<String, String> environment) {
            return path(environment.get("AGENTCODI_CACHE_DIR"), root.resolve(".cache/android"));
        }

        Path selectArchive() throws Exception {
            safeDirectory(source);
            if (archive != null) {
                regular(archive, ARCHIVE_LIMIT);
                return archive;
            }
            Map<String, Object> metadata = CodexPackageMetadata.read(source.resolve("npm-package/package.json"));
            String version = string(metadata, "version");
            CodexPackageMetadata.validatePackage(metadata, version);
            Path matching = source.resolve("mmmbuto-codex-cli-termux-" + version + ".tgz");
            if (Files.exists(matching, LinkOption.NOFOLLOW_LINKS)) {
                regular(matching, ARCHIVE_LIMIT);
                return matching;
            }
            List<Path> candidates = new ArrayList<Path>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(source, "*.tgz")) {
                for (Path entry : entries) {
                    require(candidates.size() < 32, "Too many archives; use --archive FILE.tgz.");
                    candidates.add(entry);
                }
            }
            require(candidates.size() == 1, "No unique local .tgz; place the new package in codex-termux or use --archive FILE.tgz.");
            regular(candidates.get(0), ARCHIVE_LIMIT);
            return candidates.get(0);
        }

        Path selectBuildArchive(Path cache, String pinnedHash) throws Exception {
            require(pinnedHash != null && pinnedHash.matches("[a-f0-9]{64}"), "Invalid archive pin.");
            Path selected = archive;
            if (selected == null && Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                safeDirectory(source);
                boolean hasArchive;
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(source, "*.tgz")) {
                    hasArchive = entries.iterator().hasNext();
                }
                if (hasArchive) selected = selectArchive();
            }
            if (selected == null) selected = cache.resolve("codex/" + pinnedHash + "/package.tgz");
            regular(selected, ARCHIVE_LIMIT);
            require(pinnedHash.equals(digest(selected, "SHA-256")),
                "Local Codex archive differs from the build pin. Run ./scripts/update-codex-runtime.sh before building. "
                    + "The build will not substitute an older cached runtime.");
            return selected;
        }
    }

    final Options options;
    final Map<String, String> old;
    final String archiveHash;
    final Path candidate;
    String version;
    String commit;
    String tag;
    String upstreamTag;
    String upstreamCommit;

    CodexLocalSource(Options options, Map<String, String> old, String archiveHash, Path candidate) {
        this.options = options;
        this.old = old;
        this.archiveHash = archiveHash;
        this.candidate = candidate;
    }

    void verify() throws Exception {
        safeDirectory(options.source);
        Map<String, Object> metadata = CodexPackageMetadata.read(candidate.resolve("package/package.json"));
        version = string(metadata, "version");
        CodexPackageMetadata.validatePackage(metadata, version);
        boolean sameArchive = archiveHash.equals(old.get("CODEX_ANDROID_SHA256"));
        commit = resolve(sourceCommit(metadata, old, archiveHash,
            options.sourceRef == null ? null : resolve(options.sourceRef)));
        Map<String, Object> sourcePackage = JsonCodec.parseObject(git("show", commit + ":npm-package/package.json"));
        upstreamTag = CodexPackageMetadata.verifyAgreement(version, metadata, sourcePackage);
        if (metadata.containsKey("agentcodiUpstreamCommit")) {
            String declaredUpstream = string(metadata, "agentcodiUpstreamCommit");
            require(declaredUpstream.matches("[a-f0-9]{40}"), "Invalid archive upstream commit.");
            upstreamCommit = resolve(declaredUpstream);
            if (upstreamTag.equals(old.get("CODEX_UPSTREAM_SOURCE_TAG"))) {
                require(upstreamCommit.equals(old.get("CODEX_UPSTREAM_SOURCE_COMMIT")), "The pinned upstream tag moved.");
            } else require(upstreamCommit.equals(resolve(upstreamTag)), "Upstream tag differs from the archive declaration.");
        } else upstreamCommit = upstreamTag.equals(old.get("CODEX_UPSTREAM_SOURCE_TAG"))
            ? resolve(old.get("CODEX_UPSTREAM_SOURCE_COMMIT")) : resolve(upstreamTag);
        git("merge-base", "--is-ancestor", upstreamCommit, commit);
        git("merge-base", "--is-ancestor", old.get("CODEX_TERMUX_SOURCE_COMMIT"), commit);
        tag = sameArchive ? old.get("CODEX_TERMUX_SOURCE_TAG") : "untagged";
        for (String legal : new String[] {"LICENSE", "NOTICE"}) {
            require(git("show", commit + ":" + legal).equals(text(candidate.resolve("package/" + legal))),
                "Archive " + legal + " differs from the declared source commit.");
        }
        String reviewedNotice = verifyNotice(candidate.resolve("package/NOTICE"), old.get("CODEX_NOTICE_SHA256"));
        String changed = git("diff", "--no-ext-diff", "--no-textconv", "--name-only", "--no-renames", "-z",
            old.get("CODEX_TERMUX_SOURCE_COMMIT"), commit, "--");
        for (String name : changed.split("\u0000")) {
            if (name.isEmpty() || !dependencyInput(name)) continue;
            verifyDependencyInput(name, git("show", old.get("CODEX_TERMUX_SOURCE_COMMIT") + ":" + name),
                git("show", commit + ":" + name), reviewedNotice);
        }
    }

    static String verifyNotice(Path notice, String previousHash) throws Exception {
        String hash = digest(notice, "SHA-256");
        require(hash.equals(previousHash) || hash.equals(REVIEWED_FORK_NOTICE_SHA256),
            "Unreviewed NOTICE change; review its attributions and license terms before accepting this package.");
        return text(notice);
    }

    static void verifyDependencyInput(String path, String before, String after, String reviewedNotice) throws IOException {
        boolean noticeCopy = "NOTICE".equals(path) || "npm-package/NOTICE".equals(path);
        require(noticeCopy ? after.equals(reviewedNotice)
                : normalizeDependency(path, before).equals(normalizeDependency(path, after)),
            "Dependency/license input changed: " + path + "; review its dependency graph and notices before accepting this package.");
    }

    static String sourceCommit(Map<String, Object> metadata, Map<String, String> old,
            String archiveHash, String explicitCommit) throws IOException {
        String declared = metadata.containsKey("gitHead") ? string(metadata, "gitHead") : null;
        if (declared != null) require(declared.matches("[a-f0-9]{40}"), "Invalid archive gitHead.");
        boolean sameArchive = archiveHash.equals(old.get("CODEX_ANDROID_SHA256"));
        String commit;
        if (explicitCommit != null) {
            require(explicitCommit.matches("[a-f0-9]{40}"), "Invalid source commit.");
            require(declared == null || declared.equals(explicitCommit), "Requested source ref differs from the archive gitHead.");
            commit = explicitCommit;
        } else if (declared != null) commit = declared;
        else {
            require(sameArchive, "This new archive has no source commit. Build it with the updated fork Action, or identify its build commit with --source-ref REF.");
            commit = old.get("CODEX_TERMUX_SOURCE_COMMIT");
        }
        if (sameArchive) require(commit.equals(old.get("CODEX_TERMUX_SOURCE_COMMIT")),
            "The same package bytes cannot acquire a different source commit.");
        return commit;
    }

    private String resolve(String ref) throws Exception {
        String sha = git("rev-parse", "--verify", "--end-of-options", ref + "^{commit}").trim();
        require(sha.matches("[a-f0-9]{40}"), "Source ref does not resolve to a commit.");
        return sha;
    }

    private String git(String... arguments) throws Exception {
        List<String> args = new ArrayList<String>(Arrays.asList("git", "--no-pager", "--no-optional-locks",
            "-c", "core.fsmonitor=false", "-C", options.source.toString()));
        args.addAll(Arrays.asList(arguments));
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        return capture(options.source, environment, 30, args.toArray(new String[0]));
    }

    static boolean dependencyInput(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.equals("Cargo.toml") || name.equals("Cargo.lock") || name.startsWith("LICENSE")
            || name.startsWith("NOTICE") || name.startsWith("COPYING") || name.equals("rust-toolchain.toml")
            || path.contains(".cargo/") || path.startsWith("patches/")
            || path.equals("scripts/fetch_rusty_v8_android.py") || path.equals("scripts/build_rusty_v8_android.sh");
    }

    // Permit workspace version bumps while keeping external versions, checksums,
    // sources, feature flags and dependency edges identical to the reviewed graph.
    static String normalizeDependency(String path, String contents) {
        if (path.endsWith("Cargo.lock")) {
            StringBuilder result = new StringBuilder();
            for (String block : contents.split("(?m)(?=^\\[\\[package\\]\\]$)")) {
                if (block.startsWith("[[package]]") && !block.matches("(?s).*\nsource\\s*=.*")
                    && block.matches("(?s).*\nname = \"(?:codex-[^\"]+|app_test_support|core_test_support|mcp_test_support)\"\n.*")) {
                    block = block.replaceAll("(?m)^version = \"[^\"]+\"$", "version = \"WORKSPACE\"");
                }
                result.append(block);
            }
            return result.toString();
        }
        if (path.endsWith("Cargo.toml")) {
            StringBuilder result = new StringBuilder();
            String section = "";
            for (String line : contents.split("\n", -1)) {
                if (line.startsWith("[")) section = line.trim();
                if (("[workspace.package]".equals(section) || "[package]".equals(section))
                    && line.matches("version = \"[0-9]+\\.[0-9]+\\.[0-9]+(?:-agentcodi\\.[0-9]+)?\"")) {
                    line = "version = \"WORKSPACE\"";
                }
                result.append(line).append('\n');
            }
            return result.toString();
        }
        return contents;
    }
}
