package de.agentcodi.tools;

import de.agentcodi.core.JsonCodec;
import de.agentcodi.tests.TestSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class CodexPackageMetadataTest {
    private CodexPackageMetadataTest() { }

    public static int run() throws Exception {
        permitsStaleAndAbsentReadmeWithoutTrustingIt();
        requiresAgreementOfRegistryArchiveAndSource();
        rejectsMalformedAndLinkedMetadata();
        return 3;
    }

    public static void main(String[] arguments) throws Exception {
        System.out.println("Codex metadata tests passed: " + run());
    }

    private static Map<String, Object> metadata() {
        return JsonCodec.object(
            "name", CodexPackageMetadata.PACKAGE, "version", "0.150.1", "license", "Apache-2.0",
            "os", JsonCodec.array("android"), "cpu", JsonCodec.array("arm64"),
            "repository", JsonCodec.object("url", "git+https://github.com/" + CodexPackageMetadata.FORK + ".git"),
            "description", "OpenAI Codex CLI upstream rust-v0.150.1 packaged for Android Termux"
        );
    }

    private static void permitsStaleAndAbsentReadmeWithoutTrustingIt() throws Exception {
        Path directory = Files.createTempDirectory("codex metadata with stale readme ");
        Path packageJson = directory.resolve("package.json");
        Path readme = directory.resolve("README.md");
        try {
            Files.write(packageJson, JsonCodec.stringify(metadata()).getBytes(StandardCharsets.UTF_8));
            String stale = "built from upstream OpenAI Codex `rust-v0.149.1`\n";
            Files.write(readme, stale.getBytes(StandardCharsets.UTF_8));
            // Use the same CLI invoked by the APK builder, including JSON without grep's whitespace.
            runBuilderGate(packageJson, 0);
            TestSupport.assertEquals(stale, new String(Files.readAllBytes(readme), StandardCharsets.UTF_8),
                "verifying metadata never rewrites the distributor README");
            Files.delete(readme);
            runBuilderGate(packageJson, 0);

            Map<String, Object> wrong = metadata();
            wrong.put("version", "0.149.1");
            wrong.put("unused", JsonCodec.object("version", "0.150.1"));
            Files.write(packageJson, JsonCodec.stringify(wrong).getBytes(StandardCharsets.UTF_8));
            Files.write(readme, "built from upstream OpenAI Codex `rust-v0.150.1`".getBytes(StandardCharsets.UTF_8));
            runBuilderGate(packageJson, 1);
        } finally {
            Files.deleteIfExists(readme);
            Files.deleteIfExists(packageJson);
            Files.deleteIfExists(directory);
        }
    }

    private static void runBuilderGate(Path file, int expectedExit) throws Exception {
        Process process = new ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin/java").toString(),
            "-Xmx64m", "-cp", System.getProperty("java.class.path"),
            "de.agentcodi.tools.CodexPackageMetadata", file.toString(), "0.150.1", "rust-v0.150.1"
        ).redirectErrorStream(true).start();
        process.getOutputStream().close();
        try {
            TestSupport.assertTrue(process.waitFor(15, TimeUnit.SECONDS), "metadata gate has a finite test timeout");
            TestSupport.assertEquals(expectedExit, process.exitValue(), "builder validates package declarations independently of README");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
        }
    }

    private static void requiresAgreementOfRegistryArchiveAndSource() throws Exception {
        Map<String, Object> registry = metadata();
        Map<String, Object> archive = metadata();
        Map<String, Object> source = metadata();
        TestSupport.assertEquals("rust-v0.150.1", CodexPackageMetadata.verifyAgreement("0.150.1", registry, archive, source),
            "source-commit package declarations agree with the published archive and registry");
        for (String field : new String[] {"version", "description", "name", "license", "repository", "os", "cpu"}) {
            Map<String, Object> changed = new LinkedHashMap<String, Object>(source);
            if ("description".equals(field)) changed.put(field, "OpenAI Codex CLI upstream rust-v0.149.1 packaged for Android Termux");
            else if ("repository".equals(field)) changed.put(field, JsonCodec.object("url", "git+https://github.com/untrusted/package.git"));
            else if ("os".equals(field)) changed.put(field, JsonCodec.array("linux"));
            else if ("cpu".equals(field)) changed.put(field, JsonCodec.array("x64"));
            else changed.put(field, "unexpected");
            for (int position = 0; position < 3; position++) {
                final Map<String, Object> a = position == 0 ? changed : registry;
                final Map<String, Object> b = position == 1 ? changed : archive;
                final Map<String, Object> c = position == 2 ? changed : source;
                TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
                    @Override public void run() throws Exception { CodexPackageMetadata.verifyAgreement("0.150.1", a, b, c); }
                }, "README relaxation does not allow a mismatch in " + field);
            }
        }
    }

    private static void rejectsMalformedAndLinkedMetadata() throws Exception {
        Path directory = Files.createTempDirectory("codex malformed metadata ");
        Path file = directory.resolve("package.json");
        Path link = directory.resolve("linked.json");
        try {
            for (String json : new String[] {"{", "{\"version\":\"0.150.1\",\"version\":\"0.149.1\"}"}) {
                Files.write(file, json.getBytes(StandardCharsets.UTF_8));
                rejectsFile(file);
            }
            Files.write(file, JsonCodec.stringify(metadata()).getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(link, file);
            rejectsFile(link);
            Files.delete(link);
            Files.createLink(link, file);
            rejectsFile(file);
            Files.delete(link);
            Files.write(file, new byte[2 * 1024 * 1024 + 1]);
            rejectsFile(file);
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void rejectsFile(final Path file) {
        TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
            @Override public void run() throws Exception { CodexPackageMetadata.verifyFile(file, "0.150.1", "rust-v0.150.1"); }
        }, "package metadata validation fails closed");
    }
}
