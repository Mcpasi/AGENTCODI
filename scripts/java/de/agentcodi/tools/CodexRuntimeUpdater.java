package de.agentcodi.tools;

import de.agentcodi.core.JsonCodec;
import static de.agentcodi.tools.CodexPackageMetadata.validatePackage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Host-only updater. Compiled by the shell entrypoint and host tests, never into the APK. */
public final class CodexRuntimeUpdater {
    static final long ARCHIVE_LIMIT = 192L * 1024 * 1024;
    static final int TEXT_LIMIT = 16 * 1024 * 1024;
    static final String PACKAGE = CodexPackageMetadata.PACKAGE;
    static final String REGISTRY = "https://registry.npmjs.org/";
    static final String FORK = CodexPackageMetadata.FORK;
    static final String BUILD = "scripts/build-debug-apk.sh";
    static final String ARCHITECTURE = "scripts/check-architecture.sh";
    static final String IDENTITY = "modules/core/src/main/java/de/agentcodi/core/BuildIdentity.java";
    static final String IDENTITY_TEST = "tests/java/de/agentcodi/tests/BuildIdentityTest.java";
    static final String NOTICES = "app/src/main/res/raw/third_party_notices.txt";
    static final String NOTICE = "NOTICE.md";
    static final String[] MANAGED = {
        BUILD, ARCHITECTURE, IDENTITY, IDENTITY_TEST,
        "app/src/main/res/values/strings.xml", "app/src/main/res/values-de/strings.xml", NOTICES, NOTICE
    };
    static final String[] PIN_KEYS = {
        "CODEX_ANDROID_VERSION", "CODEX_ANDROID_SHA256", "CODEX_TERMUX_SOURCE_TAG",
        "CODEX_TERMUX_SOURCE_COMMIT", "CODEX_UPSTREAM_SOURCE_TAG", "CODEX_UPSTREAM_SOURCE_COMMIT",
        "CODEX_APP_SERVER_SOURCE_SHA256", "CODEX_CODE_MODE_HOST_SHA256",
        "CODEX_APP_SERVER_ANDROID_SHA256", "CODEX_LICENSE_SHA256", "CODEX_NOTICE_SHA256",
        "CODEX_SCHEMA_BUNDLE_SHA256", "CODEX_V2_SCHEMA_BUNDLE_SHA256", "CODEX_DEFAULT_HOST_OFFSET"
    };
    static final String ORIGINAL_HOST = "codex-code-mode-host";
    static final String PACKAGED_HOST = "libcodex-codehost.so";
    // This is the install-context string table, not the other diagnostic occurrence.
    static final String HOST_CONTEXT = ORIGINAL_HOST + "zshbincodex-resourcescodex-path";
    static final String[] SCHEMAS = {
        "codex_app_server_protocol.schemas.json", "codex_app_server_protocol.v2.schemas.json"
    };
    static final Set<String> ARCHIVE_MEMBERS = set(
        "package/bin/codex", "package/bin/codex-exec", "package/bin/codex.js",
        "package/bin/codex-exec.js", "package/scripts/postinstall_termux_launcher.js",
        "package/bin/codex.bin", "package/bin/codex-code-mode-host", "package/bin/libc++_shared.so",
        "package/package.json", "package/README.md", "package/LICENSE", "package/NOTICE"
    );
    static final Set<String> MATERIALIZED = set(
        "package/bin/codex.bin", "package/bin/codex-code-mode-host",
        "package/package.json", "package/LICENSE", "package/NOTICE"
    );
    private final Path root;
    private final Path work;
    private final Path cache;
    interface Mover { void move(Path from, Path to) throws IOException; }

    private CodexRuntimeUpdater(Path root, Path work, Path cache) {
        this.root = root;
        this.work = work;
        this.cache = cache;
    }

    public static void main(String[] arguments) {
        try {
            require(arguments.length >= 1, "Missing project root.");
            Path root = Paths.get(arguments[0]).toAbsolutePath().normalize();
            safeDirectory(root);
            if (arguments.length == 2 && "--select-build-archive".equals(arguments[1])) {
                require(!Files.exists(root.resolve(".build/codex-update.pending"), LinkOption.NOFOLLOW_LINKS),
                    "An interrupted Codex update needs recovery before building.");
                Map<String, String> pins = readPins(text(root.resolve(BUILD)));
                Path cache = CodexLocalSource.Options.cacheDirectory(root, System.getenv());
                CodexLocalSource.Options buildOptions = CodexLocalSource.Options.parse(root,
                    new String[] {root.toString()}, System.getenv());
                System.out.println(buildOptions.selectBuildArchive(cache, pins.get("CODEX_ANDROID_SHA256")));
                return;
            }
            CodexLocalSource.Options options = CodexLocalSource.Options.parse(root, arguments, System.getenv());
            Path build = root.resolve(".build");
            makeDirectory(build);
            Path lockPath = build.resolve("codex-update.lock");
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) regular(lockPath, 1024);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock()) {
                require(lock != null, "Another Codex update is running.");
                Path pending = build.resolve("codex-update.pending");
                require(!Files.exists(pending, LinkOption.NOFOLLOW_LINKS),
                    "An interrupted update needs recovery. Inspect .build/codex-update.pending and its backups.");
                Path work = Files.createTempDirectory(build, "codex-update.",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                System.out.println("Update artifacts: " + work);
                Path cache = CodexLocalSource.Options.cacheDirectory(root, System.getenv());
                new CodexRuntimeUpdater(root, work, cache).run(options);
            }
        } catch (Exception failure) {
            System.err.println("Codex update aborted: " + failure.getMessage());
            System.exit(1);
        }
    }

    private void run(CodexLocalSource.Options options) throws Exception {
        Plan plan = new Plan(root, work);
        Map<String, String> old = readPins(plan.before.get(BUILD));
        plan.update(old, old);
        require(plan.before.get(BUILD).contains("CODEX_DEFAULT_HOST_NAME=\"" + ORIGINAL_HOST + "\"")
            && plan.before.get(BUILD).contains("CODEX_PACKAGED_HOST_NAME=\"" + PACKAGED_HOST + "\""),
            "The builder's host relocation contract changed.");
        Path selected = options.selectArchive();
        String archiveHash = digest(selected, "SHA-256");
        Path archive = work.resolve("candidate.tgz");
        copyBounded(selected, archive, ARCHIVE_LIMIT);
        checkHash(archive, archiveHash);
        Path candidate = work.resolve("candidate");
        unpack(archive, candidate);
        CodexLocalSource source = new CodexLocalSource(options, old, archiveHash, candidate);
        source.verify();
        // The exact previously reviewed license texts and dependency graph must
        // still apply. A checksum alone does not approve a new dependency.
        checkHash(candidate.resolve("package/LICENSE"), old.get("CODEX_LICENSE_SHA256"));
        checkHash(candidate.resolve("package/NOTICE"), old.get("CODEX_NOTICE_SHA256"));
        Map<String, String> next = new LinkedHashMap<String, String>(old);
        next.putAll(inspect(candidate, source.upstreamTag));
        next.put("CODEX_ANDROID_VERSION", source.version);
        next.put("CODEX_ANDROID_SHA256", archiveHash);
        next.put("CODEX_TERMUX_SOURCE_TAG", source.tag);
        next.put("CODEX_TERMUX_SOURCE_COMMIT", source.commit);
        next.put("CODEX_UPSTREAM_SOURCE_TAG", source.upstreamTag);
        next.put("CODEX_UPSTREAM_SOURCE_COMMIT", source.upstreamCommit);
        require(comparePackageVersions(source.version, old.get("CODEX_ANDROID_VERSION")) >= 0,
            "Downgrades are not supported.");
        StringBuilder compatibility = new StringBuilder();
        for (int i = 0; i < SCHEMAS.length; i++) {
            String key = i == 0 ? "CODEX_SCHEMA_BUNDLE_SHA256" : "CODEX_V2_SCHEMA_BUNDLE_SHA256";
            List<String> reviewed = Collections.emptyList();
            if (!old.get(key).equals(next.get(key))) {
                Path baseline = cache.resolve("codex/" + old.get("CODEX_ANDROID_SHA256") + "/" + SCHEMAS[i]);
                require(Files.exists(baseline, LinkOption.NOFOLLOW_LINKS),
                    "The protocol changed and its verified baseline is missing. Run the updater once with the currently pinned archive before replacing it.");
                checkHash(baseline, old.get(key));
                reviewed = compareSchemas(json(baseline), json(candidate.resolve("schema/" + SCHEMAS[i])));
            }
            compatibility.append(SCHEMAS[i]).append(": compatible\n");
            for (String change : reviewed) compatibility.append("  ").append(change).append('\n');
        }
        Files.write(work.resolve("compatibility.txt"), compatibility.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
        plan.update(old, next);
        plan.saveProposal(next);
        checkHash(selected, archiveHash);
        System.out.println("Verified local fork " + source.version + " at " + source.commit + ". Proposed pins:");
        for (String key : PIN_KEYS) System.out.println(key + "=\"" + next.get(key) + "\"");
        if (options.dryRun) {
            System.out.println("Dry run complete. Project files and build cache were not changed.");
            return;
        }
        Path cached = cache.resolve("codex/" + archiveHash);
        installVerified(archive, cached.resolve("package.tgz"), archiveHash, ARCHIVE_LIMIT);
        for (int i = 0; i < SCHEMAS.length; i++) {
            String key = i == 0 ? "CODEX_SCHEMA_BUNDLE_SHA256" : "CODEX_V2_SCHEMA_BUNDLE_SHA256";
            installVerified(candidate.resolve("schema/" + SCHEMAS[i]), cached.resolve(SCHEMAS[i]), next.get(key), TEXT_LIMIT);
        }
        if (plan.changed().isEmpty()) {
            System.out.println("Already pinned. Verified archive and schema baseline cached for future updates.");
            return;
        }
        plan.commit(new Mover() {
            @Override public void move(Path from, Path to) throws IOException {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        });
        System.out.println("Updated " + plan.changed().size() + " project files, including NOTICE.md. Backups: " + work.resolve("before"));
        System.out.println("Next: run ./scripts/test.sh, then ./scripts/build-debug-apk.sh.");
        System.out.println("Pinning does not verify sandbox operation. No Cargo, test suite, APK build or device test was run.");
    }

    static void copyBounded(Path source, Path target, long limit) throws Exception {
        regular(source, limit);
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                require((total += count) <= limit, "Archive changed beyond copy bounds.");
                output.write(buffer, 0, count);
            }
        }
        regular(source, limit);
    }

    static void installVerified(Path source, Path target, String hash, long limit) throws Exception {
        makeDirectory(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            checkHash(target, hash);
            return;
        }
        Path staging = Files.createTempDirectory(target.getParent(), ".codex-cache-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path pending = staging.resolve("data");
        try {
            copyBounded(source, pending, limit);
            checkHash(pending, hash);
            // All updater invocations hold the project lock; do not replace an
            // unrelated pre-existing cache entry.
            require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Cache entry appeared during update.");
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending);
            Files.deleteIfExists(staging);
        }
    }

    private Map<String, String> inspect(Path directory, String upstreamTag) throws Exception {
        Path binary = directory.resolve("package/bin/codex.bin");
        Path host = directory.resolve("package/bin/" + ORIGINAL_HOST);
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("CODEX_APP_SERVER_SOURCE_SHA256", digest(binary, "SHA-256"));
        result.put("CODEX_CODE_MODE_HOST_SHA256", digest(host, "SHA-256"));
        require(!result.get("CODEX_APP_SERVER_SOURCE_SHA256").equals(result.get("CODEX_CODE_MODE_HOST_SHA256")),
            "The code-mode host must be a separate executable.");
        try (Elf elf = new Elf(binary)) {
            elf.validate(set("libc.so", "libdl.so", "libm.so"));
            int offset = elf.hostOffset();
            result.put("CODEX_DEFAULT_HOST_OFFSET", Integer.toString(offset));
            Path patched = directory.resolve("libcodex.so");
            Files.copy(binary, patched);
            try (RandomAccessFile output = new RandomAccessFile(patched.toFile(), "rw")) {
                output.seek(offset);
                output.write(PACKAGED_HOST.getBytes(StandardCharsets.US_ASCII));
                output.getChannel().force(true);
            }
            try (Elf derived = new Elf(patched)) {
                require(derived.occurrences(ORIGINAL_HOST).size() == 1
                    && derived.occurrences(PACKAGED_HOST).size() == 1, "Ambiguous derived host relocation.");
            }
            result.put("CODEX_APP_SERVER_ANDROID_SHA256", digest(patched, "SHA-256"));
        }
        try (Elf elf = new Elf(host)) { elf.validate(set("libc.so", "libdl.so", "liblog.so", "libm.so")); }
        for (String legal : new String[] {"LICENSE", "NOTICE"}) {
            result.put("CODEX_" + legal + "_SHA256", digest(directory.resolve("package/" + legal), "SHA-256"));
        }
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(host, PosixFilePermissions.fromString("rwx------"));
        Path schema = directory.resolve("schema");
        makeDirectory(schema);
        Path probeHome = directory.resolve("probe-home");
        Path probeTemp = directory.resolve("probe-temp");
        makeDirectory(probeHome);
        makeDirectory(probeHome.resolve("codex-home"));
        makeDirectory(probeTemp);
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("HOME", probeHome.toString());
        environment.put("CODEX_HOME", probeHome.resolve("codex-home").toString());
        environment.put("TMPDIR", probeTemp.toString());
        environment.put("CODEX_CODE_MODE_HOST_PATH", host.toString());
        String version = string(CodexPackageMetadata.read(directory.resolve("package/package.json")), "version");
        require(("codex-cli " + version).equals(capture(directory, environment, 30, binary.toString(), "--version").trim()),
            "Executable version differs from package metadata.");
        // Executables were inspected above and only depend on Android platform libraries.
        System.out.println("Generating schemas from the verified Android ELF (" + upstreamTag + ")...");
        command(directory, environment, 90, binary.toString(), "app-server", "generate-json-schema", "--out", schema.toString());
        command(directory, environment, 30, host.toString(), "--help");
        result.put("CODEX_SCHEMA_BUNDLE_SHA256", digest(schema.resolve(SCHEMAS[0]), "SHA-256"));
        result.put("CODEX_V2_SCHEMA_BUNDLE_SHA256", digest(schema.resolve(SCHEMAS[1]), "SHA-256"));
        return result;
    }

    static Map<String, String> readPins(String build) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String key : PIN_KEYS) {
            Matcher matcher = Pattern.compile("(?m)^" + key + "=\"([^\"\\r\\n]+)\"$").matcher(build);
            require(matcher.find(), "Missing pin: " + key);
            String value = matcher.group(1);
            require(!matcher.find(), "Duplicate pin: " + key);
            if (key.endsWith("SHA256")) require(value.matches("[a-f0-9]{64}"), "Invalid SHA-256 pin: " + key);
            if (key.endsWith("COMMIT")) require(value.matches("[a-f0-9]{40}"), "Invalid source commit: " + key);
            if (key.endsWith("OFFSET")) require(value.matches("[0-9]{1,10}"), "Invalid ELF offset.");
            result.put(key, value);
        }
        return result;
    }

    static boolean isVersion(String version) {
        return CodexPackageMetadata.isVersion(version);
    }

    static int compareVersions(String left, String right) throws IOException {
        require(isVersion(left) && isVersion(right), "Expected stable MAJOR.MINOR.PATCH versions.");
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            if (order != 0) return order;
        }
        return 0;
    }

    static int comparePackageVersions(String left, String right) throws IOException {
        require(CodexPackageMetadata.isPackageVersion(left) && CodexPackageMetadata.isPackageVersion(right),
            "Expected a stable or -agentcodi.N package version.");
        String[] a = left.split("-agentcodi\\.", -1);
        String[] b = right.split("-agentcodi\\.", -1);
        int base = compareVersions(a[0], b[0]);
        if (base != 0) return base;
        if (a.length != b.length) return a.length == 1 ? 1 : -1;
        return a.length == 1 ? 0 : new BigInteger(a[1]).compareTo(new BigInteger(b[1]));
    }

    static String archiveUrl(String version) { return REGISTRY + PACKAGE + "/-/codex-cli-termux-" + version + ".tgz"; }

    static void validateIntegrity(String integrity) throws IOException {
        require(integrity.matches("sha512-[A-Za-z0-9+/]{86}=="), "A single npm SHA-512 integrity digest is required.");
        byte[] bytes = Base64.getDecoder().decode(integrity.substring(7));
        require(bytes.length == 64 && Base64.getEncoder().encodeToString(bytes).equals(integrity.substring(7)),
            "Invalid npm integrity digest.");
    }

    static void checkIntegrity(Path file, String integrity) throws Exception {
        validateIntegrity(integrity);
        String actual = Base64.getEncoder().encodeToString(digestBytes(file, "SHA-512"));
        require(integrity.substring(7).equals(actual), "npm integrity mismatch; source files were not changed.");
    }

    static void checkHash(Path file, String expected) throws Exception {
        require(expected.matches("[a-f0-9]{64}") && expected.equals(digest(file, "SHA-256")),
            "SHA-256 mismatch for " + file.getFileName() + "; review/download required.");
    }

    static byte[] digestBytes(Path file, String algorithm) throws Exception {
        regular(file, 512L * 1024 * 1024);
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            int count;
            long total = 0;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                require(total <= 512L * 1024 * 1024, "File changed beyond hashing bounds.");
                digest.update(buffer, 0, count);
            }
        }
        return digest.digest();
    }

    static String digest(Path file, String algorithm) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : digestBytes(file, algorithm)) result.append(String.format("%02x", b & 255));
        return result.toString();
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    static void safeDirectory(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
            && normalized.equals(normalized.toRealPath()), "Unsafe or symlinked directory: " + path);
    }

    static void makeDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = path.getParent();
            if (parent != null) makeDirectory(parent);
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        safeDirectory(path);
    }

    static void regular(Path path, long limit) throws IOException {
        safeDirectory(path.toAbsolutePath().getParent());
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
            && Files.size(path) <= limit, "Unsafe, linked or oversized file: " + path.getFileName());
    }

    static String text(Path path) throws IOException {
        regular(path, TEXT_LIMIT);
        byte[] bytes = Files.readAllBytes(path);
        require(bytes.length <= TEXT_LIMIT, "Text input exceeds its limit.");
        String text = new String(bytes, StandardCharsets.UTF_8);
        require(Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)), "Invalid UTF-8 input.");
        return text;
    }

    static Map<String, Object> json(Path file) throws IOException {
        try { return JsonCodec.parseObject(text(file)); }
        catch (IllegalArgumentException failure) { throw new IOException("Malformed JSON: " + file.getFileName(), failure); }
    }
    static Map<String, Object> object(Object value) { return JsonCodec.requireObject(value, "metadata"); }
    static String string(Map<String, Object> map, String key) throws IOException {
        Object value = map.get(key);
        require(value instanceof String && ((String) value).length() <= 4096, "Missing/invalid metadata field: " + key);
        return (String) value;
    }
    static Set<String> set(String... values) { return new LinkedHashSet<String>(Arrays.asList(values)); }

    static void command(Path directory, Map<String, String> environment, int seconds, String... command) throws Exception {
        capture(directory, environment, seconds, command);
    }

    static String capture(Path directory, Map<String, String> environment, int seconds, String... command) throws Exception {
        List<String> args = new ArrayList<String>(Arrays.asList("timeout", "--signal=TERM", "--kill-after=5s", seconds + "s"));
        args.addAll(Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(args).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/bin:/bin:/system/bin");
        builder.environment().put("LC_ALL", "C");
        builder.environment().putAll(environment);
        Process process = builder.start();
        process.getOutputStream().close();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        long count = 0;
        try (InputStream output = process.getInputStream()) {
            byte[] bytes = new byte[8192];
            int read;
            while ((read = output.read(bytes)) >= 0) {
                count += read;
                require(count <= 1024 * 1024, "Subprocess output exceeded its limit.");
                captured.write(bytes, 0, read);
            }
            boolean exited = process.waitFor(seconds + 10L, TimeUnit.SECONDS);
            require(exited && process.exitValue() == 0,
                "Command failed or timed out: " + Paths.get(command[0]).getFileName()
                + (exited ? " (exit " + process.exitValue() + ")" : "")
                + ". Check the local source checkout and Android ARM64 host; no source pins changed yet.");
            return new String(captured.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                // SIGTERM lets timeout signal its process group before the forced fallback.
                process.destroy();
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }

    // Deliberately narrow USTAR reader. Never extract arbitrary archive paths or run launchers.
    static void unpack(Path archive, Path destination) throws Exception {
        regular(archive, ARCHIVE_LIMIT);
        makeDirectory(destination);
        Set<String> seen = new HashSet<String>();
        long total = 0;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            while (true) {
                byte[] header = readExact(input, 512);
                if (allZero(header)) {
                    require(allZero(readExact(input, 512)), "Missing tar end marker.");
                    long padding = 0;
                    int b;
                    while ((b = input.read()) >= 0) {
                        require(b == 0 && ++padding <= 1024 * 1024, "Unexpected trailing tar data.");
                    }
                    break;
                }
                long sum = 0;
                for (int i = 0; i < header.length; i++) sum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                require(sum == octal(header, 148, 8), "Tar header checksum mismatch.");
                String name = tarString(header, 0, 100);
                require(tarString(header, 257, 6).equals("ustar") && tarString(header, 345, 155).isEmpty()
                    && tarString(header, 157, 100).isEmpty(), "Unsupported tar format or linked entry.");
                require((header[156] == 0 || header[156] == '0') && ARCHIVE_MEMBERS.contains(name)
                    && seen.add(name), "Unknown, duplicate or unsafe archive member.");
                long size = octal(header, 124, 12);
                long limit = name.equals("package/bin/codex.bin") ? 320L * 1024 * 1024
                    : name.equals("package/bin/codex-code-mode-host") ? 128L * 1024 * 1024
                    : name.equals("package/bin/libc++_shared.so") ? 8L * 1024 * 1024 : 1024 * 1024;
                total += size;
                require(size > 0 && size <= limit && total <= 480L * 1024 * 1024, "Archive size exceeds reviewed bounds.");
                OutputStream output = null;
                try {
                    if (MATERIALIZED.contains(name)) {
                        Path target = destination.resolve(name);
                        makeDirectory(target.getParent());
                        output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    }
                    byte[] bytes = new byte[65536];
                    long remaining = size;
                    while (remaining > 0) {
                        int count = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
                        require(count > 0, "Truncated tar member.");
                        if (output != null) output.write(bytes, 0, count);
                        remaining -= count;
                    }
                } finally { if (output != null) output.close(); }
                require(allZero(readExact(input, (int) ((512 - size % 512) % 512))), "Invalid tar padding.");
            }
        }
        require(seen.containsAll(MATERIALIZED) && seen.contains("package/bin/libc++_shared.so"),
            "Archive is missing a required runtime, host, metadata or legal file.");
    }

    static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int position = 0;
        while (position < length) {
            int count = input.read(bytes, position, length - position);
            require(count > 0, "Truncated archive.");
            position += count;
        }
        return bytes;
    }
    static boolean allZero(byte[] bytes) { for (byte b : bytes) if (b != 0) return false; return true; }
    static String tarString(byte[] bytes, int offset, int length) throws IOException {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            require(bytes[end] >= 32 && bytes[end] <= 126, "Invalid tar header text.");
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }
    static long octal(byte[] bytes, int offset, int length) throws IOException {
        String value = tarString(bytes, offset, length).trim();
        require(value.matches("[0-7]{1,12}"), "Invalid tar number.");
        return Long.parseLong(value, 8);
    }

    static final class Elf implements AutoCloseable {
        final FileChannel channel;
        final ByteBuffer bytes;
        final List<long[]> loads = new ArrayList<long[]>();
        long dynamicOffset = -1;
        long dynamicSize;
        String interpreter;
        Elf(Path path) throws Exception {
            regular(path, 320L * 1024 * 1024);
            channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try {
                require(channel.size() >= 64, "Truncated ELF.");
                bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN);
                require(bytes.getInt(0) == 0x464c457f && bytes.get(4) == 2 && bytes.get(5) == 1
                    && bytes.getShort(18) == 183 && bytes.getShort(16) == 3 && bytes.getInt(20) == 1,
                    "Expected an Android ARM64 ELF64 PIE.");
                long ph = bytes.getLong(32);
                int count = bytes.getShort(56) & 65535;
                require(bytes.getShort(54) == 56 && count > 0 && count <= 128, "Invalid ELF program headers.");
                bounds(ph, count * 56L);
                for (int i = 0; i < count; i++) {
                    int p = (int) ph + i * 56;
                    int type = bytes.getInt(p);
                    int flags = bytes.getInt(p + 4);
                    long offset = bytes.getLong(p + 8);
                    long address = bytes.getLong(p + 16);
                    long size = bytes.getLong(p + 32);
                    long memorySize = bytes.getLong(p + 40);
                    long align = bytes.getLong(p + 48);
                    bounds(offset, size);
                    if (type == 1) {
                        require(size <= memorySize && align >= 16384 && (align & (align - 1)) == 0
                            && offset % align == address % align && (flags & 3) != 3, "Unsafe or unaligned ELF LOAD segment.");
                        loads.add(new long[] {offset, address, size, flags});
                    } else if (type == 2) {
                        require(dynamicOffset < 0 && size <= 65536 && size % 16 == 0, "Invalid ELF dynamic table.");
                        dynamicOffset = offset;
                        dynamicSize = size;
                    } else if (type == 3) {
                        require(interpreter == null && size <= 128, "Invalid ELF interpreter.");
                        interpreter = cstring(offset, size);
                    }
                }
            } catch (Exception failure) { channel.close(); throw failure; }
        }
        void bounds(long offset, long length) throws IOException {
            require(offset >= 0 && length >= 0 && offset <= bytes.limit() && length <= bytes.limit() - offset,
                "ELF offset is out of bounds.");
        }
        String cstring(long offset, long limit) throws IOException {
            bounds(offset, limit);
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                int b = bytes.get((int) offset + i) & 255;
                if (b == 0) return value.toString();
                require(b >= 32 && b <= 126, "Malformed ELF string.");
                value.append((char) b);
            }
            throw new IOException("Unterminated ELF string.");
        }
        long fileOffset(long address, long length) throws IOException {
            for (long[] load : loads) {
                if (address >= load[1] && address - load[1] <= load[2]
                    && length <= load[2] - (address - load[1])) return load[0] + address - load[1];
            }
            throw new IOException("ELF dynamic address is not file-backed.");
        }
        void validate(Set<String> expectedLibraries) throws IOException {
            require("/system/bin/linker64".equals(interpreter) && dynamicOffset >= 0 && !loads.isEmpty(),
                "Expected the Android dynamic linker.");
            long strings = -1;
            long stringSize = -1;
            boolean terminated = false;
            List<Long> needed = new ArrayList<Long>();
            List<Long> paths = new ArrayList<Long>();
            for (long p = dynamicOffset; p < dynamicOffset + dynamicSize; p += 16) {
                long type = bytes.getLong((int) p);
                long value = bytes.getLong((int) p + 8);
                if (type == 0) { terminated = true; break; }
                if (type == 1) needed.add(value);
                if (type == 5) { require(strings == -1, "Duplicate ELF string table."); strings = value; }
                if (type == 10) { require(stringSize == -1, "Duplicate ELF string size."); stringSize = value; }
                if (type == 15 || type == 29) paths.add(value);
                require(type != 22 && type != 0x7ffffffdL && type != 0x7fffffffL,
                    "ELF text relocations/filter dependencies are not accepted.");
            }
            require(terminated && stringSize > 0 && stringSize <= 1024 * 1024 && strings >= 0,
                "Incomplete ELF dynamic metadata.");
            long start = fileOffset(strings, stringSize);
            Set<String> libraries = new LinkedHashSet<String>();
            for (long n : needed) {
                require(n >= 0 && n < stringSize, "Invalid ELF dependency name.");
                require(libraries.add(cstring(start + n, stringSize - n)), "Duplicate ELF dependency.");
            }
            require(libraries.equals(expectedLibraries), "Native dependency graph changed: " + libraries + "; review required.");
            for (long n : paths) {
                require(n >= 0 && n < stringSize, "Invalid ELF runpath.");
                String path = cstring(start + n, stringSize - n);
                require("$ORIGIN".equals(path) || "$ORIGIN:$ORIGIN".equals(path), "ELF contains an external library search path.");
            }
        }
        List<Integer> occurrences(String value) {
            byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
            List<Integer> result = new ArrayList<Integer>();
            for (int i = 0; i <= bytes.limit() - needle.length; i++) {
                if (bytes.get(i) != needle[0]) continue;
                int j = 1;
                while (j < needle.length && bytes.get(i + j) == needle[j]) j++;
                if (j == needle.length) result.add(i);
                if (result.size() > 16) break;
            }
            return result;
        }
        int hostOffset() throws IOException {
            require(ORIGINAL_HOST.length() == PACKAGED_HOST.length(), "Host relocation changes the binary layout.");
            List<Integer> matches = occurrences(HOST_CONTEXT);
            require(matches.size() == 1 && occurrences(ORIGINAL_HOST).size() == 2 && occurrences(PACKAGED_HOST).isEmpty(),
                "Unknown/ambiguous install-context host field; manual binary review required.");
            int offset = matches.get(0);
            long sh = bytes.getLong(40);
            int count = bytes.getShort(60) & 65535;
            int namesIndex = bytes.getShort(62) & 65535;
            require(bytes.getShort(58) == 64 && count > 0 && count <= 256 && namesIndex < count,
                "Invalid ELF section table.");
            bounds(sh, count * 64L);
            int names = (int) sh + namesIndex * 64;
            long namesOffset = bytes.getLong(names + 24);
            long namesSize = bytes.getLong(names + 32);
            bounds(namesOffset, namesSize);
            for (int i = 0; i < count; i++) {
                int p = (int) sh + i * 64;
                int name = bytes.getInt(p);
                require(name >= 0 && name < namesSize, "Invalid ELF section name.");
                if (!".rodata".equals(cstring(namesOffset + name, namesSize - name))) continue;
                long start = bytes.getLong(p + 24);
                long size = bytes.getLong(p + 32);
                long flags = bytes.getLong(p + 8);
                bounds(start, size);
                require((flags & 7) == 2 && offset >= start && offset + HOST_CONTEXT.length() <= start + size,
                    "Host field is not in read-only allocated ELF data.");
                return offset;
            }
            throw new IOException("ELF is missing the reviewed .rodata section.");
        }
        @Override public void close() throws IOException { channel.close(); }
    }

    /** Keep existing contracts strict, with the two reviewed consumer-compatible exceptions below. */
    static List<String> compareSchemas(Map<String, Object> old, Map<String, Object> next) throws IOException {
        Map<String, Object> oldDefinitions = object(old.get("definitions"));
        Map<String, Object> newDefinitions = object(next.get("definitions"));
        for (String required : new String[] {"ClientRequest", "ServerRequest", "ServerNotification"}) {
            if (oldDefinitions.containsKey(required)) require(newDefinitions.containsKey(required), "Missing protocol envelope: " + required);
        }
        // Work on copies: the original bytes remain the source of the schema SHA-256 pins.
        Map<String, Object> before = JsonCodec.parseObject(JsonCodec.stringify(old));
        Map<String, Object> after = JsonCodec.parseObject(JsonCodec.stringify(next));
        List<String> reviewed = new ArrayList<String>();
        String prefix = oldDefinitions.containsKey("v2") ? "#/definitions/v2/" : "#/definitions/";
        normalizeRawCallId(before, after, prefix, reviewed);
        normalizeThreadAdditions(before, after, prefix, reviewed);
        compareSchemaNode(before, after, "schema");
        return reviewed;
    }

    private static Object reference(Map<String, Object> schema, String ref) throws IOException {
        require(ref.equals("#/definitions") || ref.startsWith("#/definitions/"),
            "Non-local schema reference; protocol review required.");
        Object value = schema;
        for (String part : ref.substring(2).split("/")) {
            require(value instanceof Map && object(value).containsKey(part), "Unresolved schema reference: " + ref);
            value = object(value).get(part);
        }
        return value;
    }

    private static void findReferences(Object node, String path, Map<String, String> references) throws IOException {
        if (node instanceof Map) {
            for (Map.Entry<String, Object> entry : object(node).entrySet()) {
                if (set("description", "title", "default", "examples", "$schema").contains(entry.getKey())) continue;
                if ("$ref".equals(entry.getKey())) {
                    require(entry.getValue() instanceof String, "Malformed schema reference.");
                    references.put(path + "/$ref", (String) entry.getValue());
                } else findReferences(entry.getValue(), path + "/" + entry.getKey(), references);
            }
        } else if (node instanceof List) {
            int index = 0;
            for (Object child : (List<?>) node) findReferences(child, path + "/" + index++, references);
        }
    }

    private static void requireRawItemOnly(Map<String, Object> schema, String prefix) throws IOException {
        Map<String, String> references = new LinkedHashMap<String, String>();
        findReferences(schema, "#", references);
        int count = 0;
        for (Map.Entry<String, String> ref : references.entrySet()) {
            if (!(prefix + "ResponseItem").equals(ref.getValue())) continue;
            require((prefix + "RawResponseItemCompletedNotification/properties/item/$ref").equals(ref.getKey()),
                "ResponseItem is no longer exclusive to the opted-out raw notification; protocol review required.");
            count++;
        }
        require(count == 1, "Raw ResponseItem usage changed; protocol review required.");
    }

    private static Map<String, Object> rawFunctionOutput(Map<String, Object> schema, String prefix) throws IOException {
        Map<String, Object> definitions = object(reference(schema, prefix.substring(0, prefix.length() - 1)));
        if (!definitions.containsKey("ResponseItem")) return null;
        Object variants = object(definitions.get("ResponseItem")).get("oneOf");
        require(variants instanceof List, "ResponseItem union changed; protocol review required.");
        Map<String, Object> found = null;
        for (Object variant : (List<?>) variants) {
            Map<String, Object> node = object(variant);
            Map<String, Object> properties = object(node.get("properties"));
            if (!Collections.singletonList("function_call_output").equals(object(properties.get("type")).get("enum"))) continue;
            require(found == null, "Ambiguous function_call_output schema.");
            found = node;
        }
        return found;
    }

    private static void normalizeRawCallId(Map<String, Object> old, Map<String, Object> next,
            String prefix, List<String> reviewed) throws IOException {
        Map<String, Object> before = rawFunctionOutput(old, prefix);
        Map<String, Object> after = rawFunctionOutput(next, prefix);
        if (before == null || after == null) return; // The structural gate still rejects removed variants.
        Map<String, Object> oldProperties = object(before.get("properties"));
        Map<String, Object> newProperties = object(after.get("properties"));
        Object a = oldProperties.get("call_id"), b = newProperties.get("call_id");
        if (java.util.Objects.equals(a, b) && java.util.Objects.equals(before.get("required"), after.get("required"))) return;
        requireRawItemOnly(old, prefix);
        requireRawItemOnly(next, prefix);
        // CodexSessionController opts out of rawResponseItem/completed and ignores it even
        // if delivered. Only this field's string/null and presence changes were reviewed.
        // Do not exempt the rest of ResponseItem, its outputs, or another call_id field.
        for (Map<String, Object> node : Arrays.asList(before, after)) {
            Map<String, Object> callId = object(object(node.get("properties")).get("call_id"));
            Object type = callId.get("type");
            require("string".equals(type) || Arrays.asList("string", "null").equals(type),
                "Raw function_call_output.call_id changed beyond optional/nullable string.");
            callId.put("type", Arrays.asList("string", "null"));
            List<Object> required = new ArrayList<Object>(JsonCodec.requireArray(node.get("required"), "required"));
            required.remove("call_id");
            node.put("required", required);
        }
        reviewed.add("ResponseItem.function_call_output.call_id may be absent/null in the unused raw notification.");
    }

    private static void requireThreadNotSent(Map<String, Object> schema, String prefix) throws IOException {
        Map<String, Object> definitions = object(reference(schema, prefix.substring(0, prefix.length() - 1)));
        Set<String> pending = new LinkedHashSet<String>();
        // Parameters include both client and server requests. The three native dialog
        // responses are the only additional typed payloads AGENTCODI sends to the server.
        Set<String> envelopes = set("ClientRequest", "ClientNotification", "ServerRequest",
            "CommandExecutionRequestApprovalResponse", "FileChangeRequestApprovalResponse", "ToolRequestUserInputResponse");
        for (String name : definitions.keySet()) {
            if (name.endsWith("Params") || envelopes.contains(name)) pending.add(prefix + name);
        }
        for (String name : envelopes) {
            if (object(schema.get("definitions")).containsKey(name)) pending.add("#/definitions/" + name);
        }
        Set<String> visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            String ref = pending.iterator().next();
            pending.remove(ref);
            if (!visited.add(ref)) continue;
            require(!(prefix + "Thread").equals(ref), "Thread is now a request payload; protocol review required.");
            Map<String, String> nested = new LinkedHashMap<String, String>();
            findReferences(reference(schema, ref), ref, nested);
            pending.addAll(nested.values());
        }
    }

    private static void normalizeThreadAdditions(Map<String, Object> old, Map<String, Object> next,
            String prefix, List<String> reviewed) throws IOException {
        Map<String, Object> oldDefinitions = object(reference(old, prefix.substring(0, prefix.length() - 1)));
        Map<String, Object> newDefinitions = object(reference(next, prefix.substring(0, prefix.length() - 1)));
        if (!oldDefinitions.containsKey("Thread") || !newDefinitions.containsKey("Thread")) return;
        Map<String, Object> before = object(oldDefinitions.get("Thread"));
        Map<String, Object> after = object(newDefinitions.get("Thread"));
        if (java.util.Objects.equals(before.get("required"), after.get("required"))) return;
        requireThreadNotSent(old, prefix);
        requireThreadNotSent(next, prefix);
        Map<String, Object> oldProperties = object(before.get("properties"));
        Map<String, Object> newProperties = object(after.get("properties"));
        List<Object> required = new ArrayList<Object>(JsonCodec.requireArray(after.get("required"), "required"));
        for (Object field : new ArrayList<Object>(required)) {
            // Thread projection explicitly reads known fields. A new guaranteed output
            // field (e.g. projectId) needs no request change. Existing fields stay strict.
            if (field instanceof String && !oldProperties.containsKey(field) && newProperties.containsKey(field)) {
                required.remove(field);
                reviewed.add("Thread." + field + " is a new server-output field; existing thread contracts are unchanged.");
            }
        }
        after.put("required", required);
    }

    static void compareSchemaNode(Object old, Object next, String path) throws IOException {
        if (old == null ? next == null : old.equals(next)) return;
        if (old instanceof Map && next instanceof Map) {
            Map<String, Object> before = object(old);
            Map<String, Object> after = object(next);
            for (String key : before.keySet()) {
                if (set("description", "title", "default", "examples", "$schema").contains(key)) continue;
                require(after.containsKey(key), "Schema contract removed: " + path + "/" + key);
                compareSchemaNode(before.get(key), after.get(key), path + "/" + key);
            }
            for (String key : after.keySet()) {
                if (!before.containsKey(key)) {
                    require(path.endsWith("/definitions") || path.endsWith("/properties")
                        || path.endsWith("/definitions/v2")
                        || set("description", "title", "default", "examples", "$schema").contains(key),
                        "Schema constraint added: " + path + "/" + key + "; protocol review required.");
                }
            }
        } else if (old instanceof List && next instanceof List) {
            List<?> before = (List<?>) old;
            List<?> after = (List<?>) next;
            if (path.endsWith("/enum")) {
                require(after.containsAll(before), "Schema enum removed: " + path);
            } else if (path.endsWith("/oneOf") || path.endsWith("/anyOf")) {
                for (Object variant : before) {
                    boolean matched = false;
                    for (Object candidate : after) {
                        try { compareSchemaNode(variant, candidate, path + "/variant"); matched = true; break; }
                        catch (IOException incompatible) { /* Try the next union alternative. */ }
                    }
                    require(matched, "Schema alternative changed: " + path + "; protocol review required.");
                }
            } else {
                require(new HashSet<Object>(before).equals(new HashSet<Object>(after)), "Schema list changed: " + path);
            }
        } else {
            require(old == null ? next == null : old.equals(next), "Schema contract changed: " + path + "; protocol review required.");
        }
    }

    static final class Plan {
        final Path root;
        final Path work;
        final Map<String, String> before = new LinkedHashMap<String, String>();
        final Map<String, String> after = new LinkedHashMap<String, String>();
        final Map<String, Set<PosixFilePermission>> modes = new LinkedHashMap<String, Set<PosixFilePermission>>();
        Plan(Path root, Path work) throws IOException {
            this.root = root;
            this.work = work;
            for (String name : MANAGED) {
                Path file = root.resolve(name);
                before.put(name, text(file));
                modes.put(name, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
            }
            after.putAll(before);
        }
        void replace(String file, String old, String next, int expected) throws IOException {
            String content = after.get(file);
            int count = 0;
            for (int p = 0; (p = content.indexOf(old, p)) >= 0; p += old.length()) count++;
            require(count == expected, "Unexpected managed content in " + file + ": expected " + expected + " matches, got " + count);
            after.put(file, content.replace(old, next));
        }
        void update(Map<String, String> old, Map<String, String> next) throws IOException {
            for (String key : PIN_KEYS) {
                replace(BUILD, key + "=\"" + old.get(key) + "\"", key + "=\"" + next.get(key) + "\"", 1);
                if ("CODEX_DEFAULT_HOST_OFFSET".equals(key)) continue;
                String a = old.get(key);
                String b = next.get(key);
                if (key.endsWith("VERSION") || key.endsWith("TAG")) { a = a.replace(".", "\\."); b = b.replace(".", "\\."); }
                replace(ARCHITECTURE, key + "=\"" + a + "\"", key + "=\"" + b + "\"", 1);
            }
            String a = old.get("CODEX_ANDROID_VERSION");
            String b = next.get("CODEX_ANDROID_VERSION");
            replace(IDENTITY, "CODEX_RUNTIME_VERSION = \"" + a + "\"", "CODEX_RUNTIME_VERSION = \"" + b + "\"", 1);
            replace(IDENTITY_TEST, "\"" + a + "\",\n            BuildIdentity.CODEX_RUNTIME_VERSION",
                "\"" + b + "\",\n            BuildIdentity.CODEX_RUNTIME_VERSION", 1);
            replace(ARCHITECTURE, "CODEX_RUNTIME_VERSION = \"" + a.replace(".", "\\.") + "\"",
                "CODEX_RUNTIME_VERSION = \"" + b.replace(".", "\\.") + "\"", 1);
            replace(ARCHITECTURE, "'" + a.replace(".", "\\.") + "'", "'" + b.replace(".", "\\.") + "'", 2);
            replace(ARCHITECTURE, " / Codex " + a + " identity", " / Codex " + b + " identity", 1);
            for (String key : new String[] {"CODEX_TERMUX_SOURCE_COMMIT", "CODEX_UPSTREAM_SOURCE_COMMIT"}) {
                replace(ARCHITECTURE, "'" + old.get(key) + "'", "'" + next.get(key) + "'", 2);
            }
            for (String file : new String[] {"app/src/main/res/values/strings.xml", "app/src/main/res/values-de/strings.xml"}) {
                replace(file, "OpenAI Codex " + a + " ", "OpenAI Codex " + b + " ", 1);
            }
            replace(NOTICES, "community build " + a + "\n", "community build " + b + "\n", 1);
            replace(NOTICES, "Distribution source: " + old.get("CODEX_TERMUX_SOURCE_TAG") + ", commit",
                "Distribution source: " + next.get("CODEX_TERMUX_SOURCE_TAG") + ", commit", 1);
            replace(NOTICES, "OpenAI source base: " + old.get("CODEX_UPSTREAM_SOURCE_TAG") + ", commit",
                "OpenAI source base: " + next.get("CODEX_UPSTREAM_SOURCE_TAG") + ", commit", 1);
            for (String key : new String[] {"CODEX_TERMUX_SOURCE_COMMIT", "CODEX_UPSTREAM_SOURCE_COMMIT", "CODEX_ANDROID_SHA256"}) {
                replace(NOTICES, old.get(key), next.get(key), 1);
            }
            updateNotice(old, next);
            require(readPins(after.get(BUILD)).equals(next), "Staged pins do not match the verified runtime.");
        }
        private void updateNotice(Map<String, String> old, Map<String, String> next) throws IOException {
            String notice = after.get(NOTICE);
            int begin = notice.indexOf(" pins the user-supplied Android ARM64 Codex CLI/app-server build ");
            int end = notice.indexOf("\n\nThe inspected app-server", begin);
            require(begin >= 0 && end > begin, "NOTICE.md is missing the managed Codex provenance paragraphs.");
            String block = notice.substring(begin, end);
            Map<String, String> replacements = new LinkedHashMap<String, String>();
            replacements.put("mmmbuto-codex-cli-termux-" + old.get("CODEX_ANDROID_VERSION") + ".tgz",
                "mmmbuto-codex-cli-termux-" + next.get("CODEX_ANDROID_VERSION") + ".tgz");
            for (String key : PIN_KEYS) {
                if (key.equals("CODEX_TERMUX_SOURCE_TAG")) {
                    replacements.put("snapshot is " + old.get(key) + " at commit", "snapshot is " + next.get(key) + " at commit");
                } else replacements.put("`" + old.get(key) + "`", "`" + next.get(key) + "`");
            }
            // One pass prevents a new value from being mistaken for another old
            // value when two version/hash fields happen to coincide.
            StringBuilder pattern = new StringBuilder();
            for (String token : replacements.keySet()) {
                require(block.contains(token), "NOTICE.md is missing a current Codex pin.");
                if (pattern.length() > 0) pattern.append('|');
                pattern.append(Pattern.quote(token));
            }
            Matcher matcher = Pattern.compile(pattern.toString()).matcher(block);
            StringBuffer updated = new StringBuffer();
            while (matcher.find()) matcher.appendReplacement(updated, Matcher.quoteReplacement(replacements.get(matcher.group())));
            matcher.appendTail(updated);
            after.put(NOTICE, notice.substring(0, begin) + updated + notice.substring(end));
        }
        List<String> changed() {
            List<String> names = new ArrayList<String>();
            for (String name : MANAGED) if (!before.get(name).equals(after.get(name))) names.add(name);
            return names;
        }
        void saveProposal(Map<String, String> pins) throws IOException {
            StringBuilder report = new StringBuilder();
            for (String key : PIN_KEYS) report.append(key).append("=\"").append(pins.get(key)).append("\"\n");
            Files.write(work.resolve("pins.txt"), report.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            StringBuilder diff = new StringBuilder();
            for (String name : MANAGED) {
                Path original = work.resolve("before/" + name);
                Path proposed = work.resolve("after/" + name);
                makeDirectory(original.getParent());
                makeDirectory(proposed.getParent());
                Files.write(original, before.get(name).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                Files.write(proposed, after.get(name).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(original, modes.get(name));
                Files.setPosixFilePermissions(proposed, modes.get(name));
                if (before.get(name).equals(after.get(name))) continue;
                String[] oldLines = before.get(name).split("\n", -1);
                String[] newLines = after.get(name).split("\n", -1);
                require(oldLines.length == newLines.length, "Unexpected line count change.");
                diff.append("--- a/").append(name).append("\n+++ b/").append(name).append('\n');
                for (int i = 0; i < oldLines.length; i++) {
                    if (!oldLines[i].equals(newLines[i])) diff.append("@@ -").append(i + 1).append(",1 +")
                        .append(i + 1).append(",1 @@\n-").append(oldLines[i]).append("\n+").append(newLines[i]).append('\n');
                }
            }
            Files.write(work.resolve("changes.patch"), diff.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        }
        void verifyUnchanged() throws IOException {
            for (String name : MANAGED) {
                require(before.get(name).equals(text(root.resolve(name)))
                    && modes.get(name).equals(Files.getPosixFilePermissions(root.resolve(name), LinkOption.NOFOLLOW_LINKS)),
                    "Source changed during update: " + name + "; refusing to overwrite it.");
            }
        }
        synchronized void commit(Mover mover) throws Exception {
            verifyUnchanged();
            List<String> changed = changed();
            Map<String, Path> replacements = new LinkedHashMap<String, Path>();
            List<String> installed = new ArrayList<String>();
            Path pending = root.resolve(".build/codex-update.pending");
            final Object monitor = this;
            Thread shutdown = new Thread(new Runnable() {
                @Override public void run() { synchronized (monitor) { /* Wait for commit or rollback to finish. */ } }
            }, "codex-update-transaction");
            Runtime.getRuntime().addShutdownHook(shutdown);
            boolean complete = false;
            try {
                for (String name : changed) {
                    Path temporary = Files.createTempFile(root.resolve(name).getParent(), ".codex-update-", ".tmp");
                    replacements.put(name, temporary);
                    Files.write(temporary, after.get(name).getBytes(StandardCharsets.UTF_8));
                    Files.setPosixFilePermissions(temporary, modes.get(name));
                    try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                }
                verifyUnchanged();
                Files.write(pending, ("Backups and proposed changes: " + work + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
                for (String name : changed) {
                    require(before.get(name).equals(text(root.resolve(name))), "Concurrent source edit: " + name);
                    mover.move(replacements.get(name), root.resolve(name));
                    installed.add(name);
                }
                complete = true;
            } catch (Exception failure) {
                for (int i = installed.size() - 1; i >= 0; i--) {
                    String name = installed.get(i);
                    Path target = root.resolve(name);
                    // Never undo a subsequent edit made by somebody else.
                    require(after.get(name).equals(text(target)), "Rollback conflict; inspect " + pending);
                    Path restore = Files.createTempFile(target.getParent(), ".codex-restore-", ".tmp");
                    try {
                        Files.write(restore, before.get(name).getBytes(StandardCharsets.UTF_8));
                        Files.setPosixFilePermissions(restore, modes.get(name));
                        mover.move(restore, target);
                    } finally { Files.deleteIfExists(restore); }
                }
                Files.deleteIfExists(pending);
                throw failure;
            } finally {
                for (Path temporary : replacements.values()) Files.deleteIfExists(temporary);
                if (complete) Files.deleteIfExists(pending);
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException shuttingDown) { /* The hook waits for this monitor. */ }
            }
        }
    }
}
