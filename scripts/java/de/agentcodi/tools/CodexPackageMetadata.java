package de.agentcodi.tools;

import de.agentcodi.core.JsonCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared, offline metadata gate for the updater and APK builder. Never packaged. */
public final class CodexPackageMetadata {
    static final String PACKAGE = "@mmmbuto/codex-cli-termux";
    static final String FORK = "DioNanos/codex-termux";
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private CodexPackageMetadata() { }

    public static void main(String[] arguments) {
        try {
            require(arguments.length == 3, "Usage: CodexPackageMetadata PACKAGE_JSON VERSION UPSTREAM_TAG");
            verifyFile(Paths.get(arguments[0]), arguments[1], arguments[2]);
            System.out.println("Pinned Codex package metadata verified (README is informational).");
        } catch (Exception failure) {
            System.err.println("Codex package metadata rejected: " + failure.getMessage());
            System.exit(1);
        }
    }

    static void verifyFile(Path file, String version, String expectedUpstream) throws IOException {
        require(expectedUpstream != null && expectedUpstream.startsWith("rust-v")
            && isVersion(expectedUpstream.substring(6)), "Invalid pinned upstream tag.");
        Map<String, Object> metadata = read(file);
        validatePackage(metadata, version);
        require(expectedUpstream.equals(upstreamTag(metadata)), "Package upstream tag differs from the pin.");
    }

    static Map<String, Object> read(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        require(normalized.getParent().equals(normalized.getParent().toRealPath())
            && Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
            && ((Number) Files.getAttribute(normalized, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() == 1
            && Files.size(normalized) <= MAX_BYTES, "Unsafe, linked or oversized package metadata.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                require(count <= MAX_BYTES - bytes.size(), "Package metadata exceeds its limit.");
                bytes.write(buffer, 0, count);
            }
        }
        byte[] raw = bytes.toByteArray();
        String text = new String(raw, StandardCharsets.UTF_8);
        require(java.util.Arrays.equals(raw, text.getBytes(StandardCharsets.UTF_8)), "Invalid metadata UTF-8.");
        try { return JsonCodec.parseObject(text); }
        catch (IllegalArgumentException failure) { throw new IOException("Malformed package metadata JSON.", failure); }
    }

    static void validatePackage(Map<String, Object> metadata, String version) throws IOException {
        require(isVersion(version) && version.equals(string(metadata, "version")), "Invalid package version.");
        require(PACKAGE.equals(string(metadata, "name")), "Unexpected package name.");
        require("Apache-2.0".equals(string(metadata, "license")), "Package license changed; review required.");
        require(Collections.singletonList("android").equals(metadata.get("os"))
            && Collections.singletonList("arm64").equals(metadata.get("cpu")), "Package is not Android ARM64-only.");
        require(metadata.get("repository") instanceof Map, "Invalid package repository.");
        Map<String, Object> repository = JsonCodec.requireObject(metadata.get("repository"), "repository");
        require(("git+https://github.com/" + FORK + ".git").equals(string(repository, "url")),
            "Package repository changed; provenance review required.");
    }

    /** Compare declarations only after the caller has verified the archive and resolved the source commit. */
    @SafeVarargs
    static String verifyAgreement(String version, Map<String, Object>... packages) throws IOException {
        require(packages.length >= 2 && packages.length <= 3, "Expected registry, archive and optional source metadata.");
        String upstream = null;
        for (Map<String, Object> metadata : packages) {
            validatePackage(metadata, version);
            String candidate = upstreamTag(metadata);
            require(upstream == null || upstream.equals(candidate), "Registry/archive/source upstream versions disagree.");
            upstream = candidate;
        }
        return upstream;
    }

    static String upstreamTag(Map<String, Object> metadata) throws IOException {
        Matcher matcher = Pattern.compile("\\bupstream (rust-v[0-9]+\\.[0-9]+\\.[0-9]+)\\b").matcher(string(metadata, "description"));
        require(matcher.find(), "Missing upstream release tag.");
        String result = matcher.group(1);
        require(isVersion(result.substring(6)) && !matcher.find(), "Invalid or ambiguous upstream release tag.");
        return result;
    }

    static boolean isVersion(String version) {
        return version != null && version.length() <= 32
            && version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    }

    private static String string(Map<String, Object> map, String key) throws IOException {
        Object value = map.get(key);
        require(value instanceof String && ((String) value).length() <= 4096, "Missing/invalid metadata field: " + key);
        return (String) value;
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
