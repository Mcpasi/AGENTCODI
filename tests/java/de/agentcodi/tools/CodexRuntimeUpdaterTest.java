package de.agentcodi.tools;

import de.agentcodi.core.JsonCodec;
import de.agentcodi.tests.TestSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

public final class CodexRuntimeUpdaterTest {
    private static final CodexRuntimeUpdater.Mover MOVE = new CodexRuntimeUpdater.Mover() {
        @Override public void move(Path from, Path to) throws IOException {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    };
    private CodexRuntimeUpdaterTest() { }

    public static int run() throws Exception {
        validatesVersionsAndMetadata();
        verifiesPublishedIntegrity();
        extractsOnlyReviewedRegularMembers();
        rejectsUnsafeAndCorruptArchives();
        validatesElfAndFindsOnlyReviewedHostField();
        rejectsChangedElfContracts();
        checksSchemaCompatibility();
        acceptsReviewedRawAndThreadChanges();
        rejectsChangedConsumersOfReviewedSchemaTypes();
        keepsExistingProtocolFieldsStrict();
        supportsSuccessiveSchemaUpdates();
        updatesAllPinsAndPreservesUnrelatedContent();
        rejectsUnexpectedManagedContentBeforeWrites();
        refusesConcurrentEdits();
        rejectsLinkedSourcesAndDirectories();
        rollsBackFailedInstall();
        preservesConflictingEditsDuringRollback();
        boundsSubprocessExecution();
        selectsLocalForkArchives();
        keepsDependencyReviewStrictAcrossVersionBumps();
        cachesSameVersionRebuildsByContent();
        bindsLocalBytesToTheirBuildCommit();
        refusesToHideReplacedBuildInputsBehindTheCache();
        acceptsOnlyReviewedForkNoticeCorrections();
        return 24;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Codex updater tests passed: " + run());
    }

    private static void acceptsOnlyReviewedForkNoticeCorrections() throws Exception {
        Path work = Files.createTempDirectory("codex notice review ");
        try {
            Path notice = work.resolve("NOTICE");
            String previous = "Synthetic previously reviewed notice.\n";
            Files.write(notice, previous.getBytes(StandardCharsets.UTF_8));
            String previousHash = CodexRuntimeUpdater.digest(notice, "SHA-256");
            TestSupport.assertEquals(previous, CodexLocalSource.verifyNotice(notice, previousHash),
                "unchanged reviewed notices remain accepted");
            String corrected = "OpenAI Codex\nCopyright 2025 OpenAI\nCopyright 2026 Davide A. Guglielmi\n"
                + "Copyright 2026 Mcpasi\n\n"
                + "Codex Termux is a porting/distribution fork of OpenAI Codex for Android ARM64\n(Termux).\n\n"
                + "The original Android/Termux compatibility and distribution work in this fork\n"
                + "is maintained by Davide A. Guglielmi.\n\n"
                + "The Android seccomp/ptrace sandbox backend and its integration contained in\n"
                + "this derivative fork are maintained by Mcpasi.\n\n"
                + "These modifications are distributed under the Apache License, Version 2.0,\n"
                + "together with the upstream Codex codebase.\n\n"
                + "This project includes code derived from Ratatui\n"
                + "(https://github.com/ratatui/ratatui), licensed under the MIT license.\n"
                + "Copyright (c) 2016-2022 Florian Dehau\nCopyright (c) 2023-2025 The Ratatui Developers\n";
            Files.write(notice, corrected.getBytes(StandardCharsets.UTF_8));
            String reviewed = CodexLocalSource.verifyNotice(notice, previousHash);
            TestSupport.assertEquals(corrected, reviewed, "the exact reviewed sandbox attribution correction is accepted");
            for (String name : new String[] {"NOTICE", "npm-package/NOTICE"}) {
                CodexLocalSource.verifyDependencyInput(name, previous, corrected, reviewed);
                rejects(new Action() { public void run() throws Exception {
                    CodexLocalSource.verifyDependencyInput(name, previous, corrected + "Unreviewed addition.\n", reviewed);
                } });
            }
            for (String name : new String[] {"LICENSE", "npm-package/LICENSE", "vendor/NOTICE", "NOTICE.md", "COPYING", "Cargo.toml"}) {
                TestSupport.assertTrue(CodexLocalSource.dependencyInput(name), "legal and dependency inputs stay subject to review");
                rejects(new Action() { public void run() throws Exception {
                    CodexLocalSource.verifyDependencyInput(name, previous, corrected, reviewed);
                } });
            }
            for (String changed : new String[] {
                    corrected.replace("Copyright 2025 OpenAI\n", ""),
                    corrected.replace("Copyright 2026 Mcpasi\n", ""),
                    corrected.replace("The Ratatui Developers", "Unknown authors"),
                    corrected.replace("Apache License, Version 2.0", "Different license"),
                    corrected + "Unreviewed addition.\n"}) {
                Files.write(notice, changed.getBytes(StandardCharsets.UTF_8));
                rejects(new Action() { public void run() throws Exception { CodexLocalSource.verifyNotice(notice, previousHash); } });
            }
        } finally { remove(work); }
    }

    private static void validatesVersionsAndMetadata() throws Exception {
        for (String version : new String[] {"1.2.3-beta", "01.2.3", "1.2", "1.2.3\n", "../latest", "$(touch bad)", "1.2.3;false"}) {
            TestSupport.assertFalse(CodexRuntimeUpdater.isVersion(version), "reject untrusted version syntax");
        }
        TestSupport.assertTrue(CodexRuntimeUpdater.compareVersions("0.150.0", "0.99.0") > 0, "numeric version order");
        TestSupport.assertTrue(CodexRuntimeUpdater.compareVersions("0.1.0", "0.2.0") < 0, "detect downgrade");
        TestSupport.assertTrue(CodexRuntimeUpdater.comparePackageVersions("0.153.3-agentcodi.10", "0.153.3-agentcodi.2") > 0,
            "fork revision order is numeric");
        TestSupport.assertEquals(0, CodexRuntimeUpdater.comparePackageVersions("0.153.3-agentcodi.1", "0.153.3-agentcodi.1"),
            "same-version rebuilds can replace their package bytes");
        Map<String, Object> metadata = JsonCodec.object(
            "name", CodexRuntimeUpdater.PACKAGE, "version", "1.2.3", "license", "Apache-2.0",
            "os", JsonCodec.array("android"), "cpu", JsonCodec.array("arm64"),
            "repository", JsonCodec.object("url", "git+https://github.com/" + CodexRuntimeUpdater.FORK + ".git"),
            "description", "OpenAI Codex CLI upstream rust-v1.2.0 packaged for Android Termux"
        );
        CodexPackageMetadata.validatePackage(metadata, "1.2.3");
        TestSupport.assertEquals("rust-v1.2.0", CodexPackageMetadata.upstreamTag(metadata), "distinct upstream version");
        metadata.put("license", "GPL-3.0");
        rejects(new Action() { public void run() throws Exception { CodexPackageMetadata.validatePackage(metadata, "1.2.3"); } });
        metadata.put("license", "Apache-2.0");
        metadata.put("cpu", JsonCodec.array("x64"));
        rejects(new Action() { public void run() throws Exception { CodexPackageMetadata.validatePackage(metadata, "1.2.3"); } });
        metadata.put("cpu", JsonCodec.array("arm64"));
        metadata.put("name", "other/package");
        rejects(new Action() { public void run() throws Exception { CodexPackageMetadata.validatePackage(metadata, "1.2.3"); } });
    }

    private static void verifiesPublishedIntegrity() throws Exception {
        Path file = Files.createTempFile("codex-integrity-", ".tgz");
        try {
            Files.write(file, new byte[] {1, 2, 3});
            String integrity = "sha512-" + Base64.getEncoder().encodeToString(CodexRuntimeUpdater.digestBytes(file, "SHA-512"));
            CodexRuntimeUpdater.checkIntegrity(file, integrity);
            Files.write(file, new byte[] {1, 3, 2});
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.checkIntegrity(file, integrity); } });
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.validateIntegrity("sha1-abc"); } });
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.validateIntegrity(integrity + " " + integrity); } });
        } finally { Files.deleteIfExists(file); }
    }

    private static byte[] header(String name, char type, long size) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, "0000600");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", size));
        put(h, 136, "00000000000");
        Arrays.fill(h, 148, 156, (byte) ' ');
        h[156] = (byte) type;
        put(h, 257, "ustar");
        put(h, 263, "00");
        int sum = 0;
        for (byte b : h) sum += b & 255;
        put(h, 148, String.format("%06o", sum));
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    private static void put(byte[] bytes, int offset, String value) {
        byte[] text = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, bytes, offset, text.length);
    }

    private static byte[] archiveBytes(String extra, char type, boolean corrupt, boolean truncate) throws Exception {
        return archiveBytes(extra, type, corrupt, truncate, true);
    }

    private static byte[] archiveBytes(String extra, char type, boolean corrupt, boolean truncate, boolean includeReadme) throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (String name : CodexRuntimeUpdater.ARCHIVE_MEMBERS) {
            if (!includeReadme && "package/README.md".equals(name)) continue;
            byte[] h = header(name, '0', 4);
            if (corrupt) h[0] ^= 1;
            tar.write(h);
            tar.write(new byte[] {1, 2, 3, 4});
            tar.write(new byte[508]);
        }
        if (extra != null) {
            tar.write(header(extra, type, 1));
            tar.write(new byte[512]);
        }
        if (!truncate) tar.write(new byte[1024]);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(tar.toByteArray()); }
        return compressed.toByteArray();
    }

    private static void extractsOnlyReviewedRegularMembers() throws Exception {
        Path work = Files.createTempDirectory("codex-tar-");
        try {
            Path archive = work.resolve("runtime.tgz");
            Files.write(archive, archiveBytes(null, '0', false, false));
            Path target = work.resolve("output");
            CodexRuntimeUpdater.unpack(archive, target);
            for (String name : CodexRuntimeUpdater.MATERIALIZED) {
                TestSupport.assertEquals(4L, Files.size(target.resolve(name)), "bounded regular bytes extracted");
            }
            for (String name : new String[] {"package/bin/codex", "package/bin/codex.js", "package/scripts/postinstall_termux_launcher.js", "package/bin/libc++_shared.so", "package/README.md"}) {
                TestSupport.assertFalse(Files.exists(target.resolve(name)), "no wrapper/script or unused library extracted");
            }
            Path withoutReadme = work.resolve("without-readme.tgz");
            Files.write(withoutReadme, archiveBytes(null, '0', false, false, false));
            CodexRuntimeUpdater.unpack(withoutReadme, work.resolve("without-readme"));
            TestSupport.assertTrue(Files.isRegularFile(work.resolve("without-readme/package/LICENSE")),
                "a missing README does not remove required legal material");
        } finally { remove(work); }
    }

    private static void rejectsUnsafeAndCorruptArchives() throws Exception {
        Path work = Files.createTempDirectory("codex-bad-tar-");
        try {
            int i = 0;
            for (String member : new String[] {"../escaped", "/tmp/escaped", "package/bin/extra", "package/bin/codex.bin"}) {
                Path archive = work.resolve("bad" + i + ".tgz");
                Files.write(archive, archiveBytes(member, '0', false, false));
                Path output = work.resolve("out" + i++);
                rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.unpack(archive, output); } });
            }
            for (char type : new char[] {'1', '2', '3', '5', 'x', 'L'}) {
                Path archive = work.resolve("type" + type + ".tgz");
                Files.write(archive, archiveBytes("package/bin/codex.bin", type, false, false));
                Path output = work.resolve("type" + type);
                rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.unpack(archive, output); } });
            }
            for (boolean corrupt : new boolean[] {true, false}) {
                Path archive = work.resolve("corrupt" + corrupt + ".tgz");
                Files.write(archive, archiveBytes(null, '0', corrupt, !corrupt));
                Path output = work.resolve("corrupt" + corrupt);
                rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.unpack(archive, output); } });
            }
        } finally { remove(work); }
    }

    private static byte[] elfBytes() {
        byte[] raw = new byte[32768];
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x464c457f); b.put(4, (byte) 2); b.put(5, (byte) 1);
        b.putShort(16, (short) 3); b.putShort(18, (short) 183); b.putInt(20, 1);
        b.putLong(24, 16384); b.putLong(32, 64); b.putShort(54, (short) 56); b.putShort(56, (short) 4);
        // Read-only metadata LOAD, executable LOAD, INTERP, DYNAMIC.
        program(b, 64, 1, 4, 0, 16384, 16384);
        program(b, 120, 1, 5, 16384, 16384, 16384);
        program(b, 176, 3, 4, 600, 21, 1);
        program(b, 232, 2, 4, 2048, 128, 8);
        put(raw, 600, "/system/bin/linker64");
        put(raw, 1024, "libc.so\0libdl.so\0libm.so\0$ORIGIN\0");
        long[] table = {1, 0, 1, 8, 1, 17, 5, 1024, 10, 40, 29, 25, 0, 0};
        for (int i = 0; i < table.length; i++) b.putLong(2048 + 8 * i, table[i]);
        put(raw, 4096, CodexRuntimeUpdater.HOST_CONTEXT);
        put(raw, 4400, "diagnostic " + CodexRuntimeUpdater.ORIGINAL_HOST);
        b.putLong(40, 8000); b.putShort(58, (short) 64); b.putShort(60, (short) 3); b.putShort(62, (short) 1);
        put(raw, 7000, "\0.shstrtab\0.rodata\0");
        b.putInt(8064, 1); b.putLong(8064 + 24, 7000); b.putLong(8064 + 32, 20);
        b.putInt(8128, 11); b.putLong(8128 + 8, 2); b.putLong(8128 + 24, 4096); b.putLong(8128 + 32, 1024);
        return raw;
    }

    private static void program(ByteBuffer b, int p, int type, int flags, long offset, long size, long align) {
        b.putInt(p, type); b.putInt(p + 4, flags); b.putLong(p + 8, offset); b.putLong(p + 16, offset);
        b.putLong(p + 32, size); b.putLong(p + 40, size); b.putLong(p + 48, align);
    }

    private static void validatesElfAndFindsOnlyReviewedHostField() throws Exception {
        Path file = Files.createTempFile("codex-elf-", ".elf");
        try {
            Files.write(file, elfBytes());
            try (CodexRuntimeUpdater.Elf elf = new CodexRuntimeUpdater.Elf(file)) {
                elf.validate(CodexRuntimeUpdater.set("libc.so", "libdl.so", "libm.so"));
                TestSupport.assertEquals(4096, elf.hostOffset(), "find install-context field, not diagnostic occurrence");
            }
        } finally { Files.deleteIfExists(file); }
    }

    private static void rejectsChangedElfContracts() throws Exception {
        Path file = Files.createTempFile("codex-bad-elf-", ".elf");
        try {
            for (int variation = 0; variation < 9; variation++) {
                byte[] raw = elfBytes();
                ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
                switch (variation) {
                    case 0: b.putShort(18, (short) 62); break;
                    case 1: put(raw, 600, "/other/linker64\0"); break;
                    case 2: put(raw, 1024, "badc.so"); break;
                    case 3: b.putLong(64 + 48, 4096); break;
                    case 4: b.putInt(64 + 4, 7); break;
                    case 5: put(raw, 1024 + 25, "/tmp/xx"); break;
                    case 6: put(raw, 4600, CodexRuntimeUpdater.HOST_CONTEXT); break;
                    case 7: b.putLong(8128 + 8, 3); break;
                    case 8: b.putLong(40, Long.MAX_VALUE); break;
                    default: throw new AssertionError();
                }
                Files.write(file, raw);
                rejects(new Action() {
                    public void run() throws Exception {
                        try (CodexRuntimeUpdater.Elf elf = new CodexRuntimeUpdater.Elf(file)) {
                            elf.validate(CodexRuntimeUpdater.set("libc.so", "libdl.so", "libm.so"));
                            elf.hostOffset();
                        }
                    }
                });
            }
        } finally { Files.deleteIfExists(file); }
    }

    private static Map<String, Object> schema(String fieldType, boolean requiredExtra) {
        return JsonCodec.object("definitions", JsonCodec.object("TurnStartParams", JsonCodec.object(
            "type", "object", "properties", JsonCodec.object("threadId", JsonCodec.object("type", fieldType)),
            "required", requiredExtra ? JsonCodec.array("threadId", "newField") : JsonCodec.array("threadId"))));
    }

    private static void checksSchemaCompatibility() throws Exception {
        Map<String, Object> old = schema("string", false);
        Map<String, Object> next = schema("string", false);
        Map<String, Object> definition = CodexRuntimeUpdater.object(CodexRuntimeUpdater.object(next.get("definitions")).get("TurnStartParams"));
        definition.put("description", "Updated schema documentation");
        CodexRuntimeUpdater.object(definition.get("properties")).put("newOptional", JsonCodec.object("type", "string"));
        CodexRuntimeUpdater.compareSchemas(old, next);
        rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.compareSchemas(old, schema("integer", false)); } });
        rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.compareSchemas(old, schema("string", true)); } });
        rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.compareSchemas(old, JsonCodec.object("definitions", JsonCodec.object())); } });
    }

    // Minimal wire contracts reproduce the 0.148.1 -> 0.150.1/0.153.2 schema changes.
    private static Map<String, Object> schemaFixture(boolean nested, boolean newer) {
        String prefix = nested ? "#/definitions/v2/" : "#/definitions/";
        Map<String, Object> threadProperties = JsonCodec.object("id", JsonCodec.object("type", "string"));
        if (newer) threadProperties.put("projectId", JsonCodec.object("type", JsonCodec.array("string", "null")));
        Map<String, Object> definitions = JsonCodec.object(
            "Thread", JsonCodec.object("type", "object", "properties", threadProperties,
                "required", newer ? JsonCodec.array("id", "projectId") : JsonCodec.array("id")),
            "ThreadResumeResponse", JsonCodec.object("type", "object", "properties",
                JsonCodec.object("thread", JsonCodec.object("$ref", prefix + "Thread"))),
            "TurnStartParams", JsonCodec.object("type", "object", "properties",
                JsonCodec.object("threadId", JsonCodec.object("type", "string")), "required", JsonCodec.array("threadId")),
            "ResponseItem", JsonCodec.object("oneOf", JsonCodec.array(JsonCodec.object(
                "type", "object", "properties", JsonCodec.object(
                    "type", JsonCodec.object("type", "string", "enum", JsonCodec.array("function_call_output")),
                    "call_id", JsonCodec.object("type", newer ? JsonCodec.array("string", "null") : "string"),
                    "output", JsonCodec.object("type", "string")),
                "required", newer ? JsonCodec.array("output", "type") : JsonCodec.array("call_id", "output", "type")))),
            "RawResponseItemCompletedNotification", JsonCodec.object("type", "object", "properties",
                JsonCodec.object("item", JsonCodec.object("$ref", prefix + "ResponseItem")))
        );
        Map<String, Object> rootDefinitions = nested ? JsonCodec.object("v2", definitions) : definitions;
        rootDefinitions.put("ClientRequest", JsonCodec.object("$ref", prefix + "TurnStartParams"));
        return JsonCodec.object("definitions", rootDefinitions);
    }

    private static Map<String, Object> definitions(Map<String, Object> schema, boolean nested) {
        Map<String, Object> definitions = CodexRuntimeUpdater.object(schema.get("definitions"));
        return nested ? CodexRuntimeUpdater.object(definitions.get("v2")) : definitions;
    }

    private static Map<String, Object> definition(Map<String, Object> schema, boolean nested, String name) {
        return CodexRuntimeUpdater.object(definitions(schema, nested).get(name));
    }

    private static Map<String, Object> properties(Map<String, Object> schema, boolean nested, String name) {
        return CodexRuntimeUpdater.object(definition(schema, nested, name).get("properties"));
    }

    private static Map<String, Object> outputVariant(Map<String, Object> schema, boolean nested) {
        return CodexRuntimeUpdater.object(((List<?>) definition(schema, nested, "ResponseItem").get("oneOf")).get(0));
    }

    private static void acceptsReviewedRawAndThreadChanges() throws Exception {
        for (boolean nested : new boolean[] {true, false}) {
            Map<String, Object> old = schemaFixture(nested, false);
            Map<String, Object> next = schemaFixture(nested, true);
            String oldBytes = JsonCodec.stringify(old), newBytes = JsonCodec.stringify(next);
            List<String> reviewed = CodexRuntimeUpdater.compareSchemas(old, next);
            TestSupport.assertEquals(2, reviewed.size(), "both reviewed changes are reported");
            TestSupport.assertEquals(oldBytes, JsonCodec.stringify(old), "baseline schema is not rewritten");
            TestSupport.assertEquals(newBytes, JsonCodec.stringify(next), "candidate SHA remains bound to original schema bytes");
        }
    }

    private static void rejectsChangedConsumersOfReviewedSchemaTypes() throws Exception {
        for (boolean nested : new boolean[] {true, false}) {
            String prefix = nested ? "#/definitions/v2/" : "#/definitions/";
            for (String consumer : new String[] {"raw-other-notification", "thread-request", "thread-user-answer", "thread-indirect-request"}) {
                final Map<String, Object> old = schemaFixture(nested, false);
                final Map<String, Object> next = schemaFixture(nested, true);
                if ("raw-other-notification".equals(consumer)) {
                    definitions(next, nested).put("OtherNotification", JsonCodec.object("$ref", prefix + "ResponseItem"));
                } else if ("thread-user-answer".equals(consumer)) {
                    definitions(next, nested).put("ToolRequestUserInputResponse", JsonCodec.object("$ref", prefix + "Thread"));
                } else if ("thread-indirect-request".equals(consumer)) {
                    properties(next, nested, "TurnStartParams").put("extra", JsonCodec.object("$ref", prefix + "Wrapper"));
                    definitions(next, nested).put("Wrapper", JsonCodec.object("properties", JsonCodec.object(
                        "cycle", JsonCodec.object("$ref", prefix + "Wrapper"),
                        "thread", JsonCodec.object("$ref", prefix + "Thread"))));
                } else {
                    properties(next, nested, "TurnStartParams").put("extra", JsonCodec.object("$ref", prefix + "Thread"));
                }
                rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.compareSchemas(old, next); } });
            }
        }
    }

    private static void keepsExistingProtocolFieldsStrict() throws Exception {
        for (String changed : new String[] {"thread-id", "thread-required", "raw-output", "raw-call-id", "input-required", "raw-ref"}) {
            final Map<String, Object> old = schemaFixture(true, false);
            final Map<String, Object> next = schemaFixture(true, true);
            if ("thread-id".equals(changed)) properties(next, true, "Thread").put("id", JsonCodec.object("type", "integer"));
            if ("thread-required".equals(changed)) definition(next, true, "Thread").put("required", JsonCodec.array("projectId"));
            if ("raw-output".equals(changed) || "raw-call-id".equals(changed)) {
                String key = "raw-output".equals(changed) ? "output" : "call_id";
                CodexRuntimeUpdater.object(outputVariant(next, true).get("properties")).put(key, JsonCodec.object("type", "integer"));
            }
            if ("input-required".equals(changed)) {
                properties(next, true, "TurnStartParams").put("newField", JsonCodec.object("type", "string"));
                definition(next, true, "TurnStartParams").put("required", JsonCodec.array("threadId", "newField"));
            }
            if ("raw-ref".equals(changed)) {
                properties(next, true, "RawResponseItemCompletedNotification").put("item", JsonCodec.object("type", "string"));
            }
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.compareSchemas(old, next); } });
        }
    }

    private static void supportsSuccessiveSchemaUpdates() throws Exception {
        final Map<String, Object> old = schemaFixture(false, true);
        final Map<String, Object> next = schemaFixture(false, true);
        properties(next, false, "Thread").put("model", JsonCodec.object("type", JsonCodec.array("string", "null")));
        TestSupport.assertTrue(CodexRuntimeUpdater.compareSchemas(old, next).isEmpty(), "later additive update remains compatible");
        TestSupport.assertTrue(CodexRuntimeUpdater.compareSchemas(next, next).isEmpty(), "same-version recheck is idempotent");
        properties(next, false, "Thread").put("projectId", JsonCodec.object("type", "integer"));
        rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.compareSchemas(old, next); } });
    }

    private static Path fixture() throws Exception {
        Path fixture = Files.createTempDirectory("codex update source with spaces ");
        Path project = Paths.get(System.getProperty("agentcodi.projectRoot")).toAbsolutePath();
        for (String name : CodexRuntimeUpdater.MANAGED) {
            Path target = fixture.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(project.resolve(name), target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.createDirectories(fixture.resolve(".build"));
        Files.write(fixture.resolve("README.md"), "User maintained documentation\n".getBytes(StandardCharsets.UTF_8));
        return fixture;
    }

    private static CodexRuntimeUpdater.Plan plan(Path fixture) throws Exception {
        return new CodexRuntimeUpdater.Plan(fixture, Files.createTempDirectory(fixture.resolve(".build"), "proposal-"));
    }

    private static Map<String, String> target(Map<String, String> old) {
        Map<String, String> next = new LinkedHashMap<String, String>(old);
        next.put("CODEX_ANDROID_VERSION", "9.8.7");
        next.put("CODEX_TERMUX_SOURCE_TAG", "v9.8.7");
        next.put("CODEX_UPSTREAM_SOURCE_TAG", "rust-v9.8.7");
        for (String key : old.keySet()) {
            if (key.endsWith("SHA256")) next.put(key, repeat('a', 64));
            if (key.endsWith("COMMIT")) next.put(key, repeat(key.startsWith("CODEX_TERMUX") ? 'b' : 'c', 40));
        }
        next.put("CODEX_DEFAULT_HOST_OFFSET", "123456");
        return next;
    }

    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }

    private static void prepare(CodexRuntimeUpdater.Plan plan) throws Exception {
        Map<String, String> old = CodexRuntimeUpdater.readPins(plan.before.get(CodexRuntimeUpdater.BUILD));
        Map<String, String> next = target(old);
        plan.update(old, next);
        plan.saveProposal(next);
    }

    private static void updatesAllPinsAndPreservesUnrelatedContent() throws Exception {
        Path fixture = fixture();
        try {
            Path build = fixture.resolve(CodexRuntimeUpdater.BUILD);
            Files.write(build, (CodexRuntimeUpdater.text(build) + "\n# unrelated local edit\n").getBytes(StandardCharsets.UTF_8));
            Set<?> mode = Files.getPosixFilePermissions(build);
            CodexRuntimeUpdater.Plan plan = plan(fixture);
            prepare(plan);
            for (String file : CodexRuntimeUpdater.MANAGED) {
                TestSupport.assertEquals(plan.before.get(file), CodexRuntimeUpdater.text(fixture.resolve(file)), "proposal is a dry run");
            }
            TestSupport.assertEquals(8, plan.changed().size(), "all runtime pin consumers and NOTICE updated");
            plan.commit(MOVE);
            for (String file : CodexRuntimeUpdater.MANAGED) {
                TestSupport.assertEquals(plan.after.get(file), CodexRuntimeUpdater.text(fixture.resolve(file)), "committed proposed file");
                TestSupport.assertEquals(plan.before.get(file), CodexRuntimeUpdater.text(plan.work.resolve("before/" + file)), "original backup preserved");
            }
            TestSupport.assertEquals(mode, Files.getPosixFilePermissions(build), "executable mode preserved");
            TestSupport.assertContains(CodexRuntimeUpdater.text(build), "# unrelated local edit", "unrelated user edit retained");
            TestSupport.assertEquals("User maintained documentation\n", CodexRuntimeUpdater.text(fixture.resolve("README.md")), "other documentation unchanged");
            TestSupport.assertContains(CodexRuntimeUpdater.text(fixture.resolve("NOTICE.md")), "`9.8.7`", "NOTICE version updated");
            TestSupport.assertContains(CodexRuntimeUpdater.text(fixture.resolve("NOTICE.md")), "Pascal (Mc Pasi)", "sandbox authorship retained");
            TestSupport.assertFalse(Files.exists(fixture.resolve(".build/codex-update.pending")), "completed journal cleared");
            CodexRuntimeUpdater.Plan again = plan(fixture);
            Map<String, String> pinned = CodexRuntimeUpdater.readPins(again.before.get(CodexRuntimeUpdater.BUILD));
            again.update(pinned, pinned);
            TestSupport.assertTrue(again.changed().isEmpty(), "same-version update is idempotent");
        } finally { remove(fixture); }
    }

    private static void rejectsUnexpectedManagedContentBeforeWrites() throws Exception {
        Path fixture = fixture();
        try {
            CodexRuntimeUpdater.Plan plan = plan(fixture);
            String old = CodexRuntimeUpdater.readPins(plan.before.get(CodexRuntimeUpdater.BUILD)).get("CODEX_ANDROID_VERSION");
            Path identity = fixture.resolve(CodexRuntimeUpdater.IDENTITY);
            Files.write(identity, CodexRuntimeUpdater.text(identity).replace("CODEX_RUNTIME_VERSION = \"" + old + "\"", "CODEX_RUNTIME_VERSION = \"broken\"").getBytes(StandardCharsets.UTF_8));
            CodexRuntimeUpdater.Plan broken = plan(fixture);
            rejects(new Action() { public void run() throws Exception { prepare(broken); } });
            TestSupport.assertEquals(plan.before.get(CodexRuntimeUpdater.BUILD), CodexRuntimeUpdater.text(fixture.resolve(CodexRuntimeUpdater.BUILD)), "no partial source update");
            rejects(new Action() {
                public void run() throws Exception {
                    CodexRuntimeUpdater.readPins(plan.before.get(CodexRuntimeUpdater.BUILD) + "\nCODEX_ANDROID_VERSION=\"1.2.3\"\n");
                }
            });
        } finally { remove(fixture); }
    }

    private static void refusesConcurrentEdits() throws Exception {
        Path fixture = fixture();
        try {
            CodexRuntimeUpdater.Plan plan = plan(fixture);
            prepare(plan);
            Path identity = fixture.resolve(CodexRuntimeUpdater.IDENTITY);
            Files.write(identity, "concurrent user edit".getBytes(StandardCharsets.UTF_8));
            rejects(new Action() { public void run() throws Exception { plan.commit(MOVE); } });
            TestSupport.assertEquals("concurrent user edit", CodexRuntimeUpdater.text(identity), "concurrent edit retained");
            TestSupport.assertEquals(plan.before.get(CodexRuntimeUpdater.BUILD), CodexRuntimeUpdater.text(fixture.resolve(CodexRuntimeUpdater.BUILD)), "other source unchanged");
        } finally { remove(fixture); }
    }

    private static void rejectsLinkedSourcesAndDirectories() throws Exception {
        Path work = Files.createTempDirectory("codex-linked-");
        try {
            Path source = work.resolve("source");
            Files.write(source, new byte[] {1});
            Path symbolic = work.resolve("symbolic");
            Files.createSymbolicLink(symbolic, source);
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.regular(symbolic, 100); } });
            Path hard = work.resolve("hard");
            Files.createLink(hard, source);
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.regular(hard, 100); } });
            Path directoryLink = work.resolve("linked-directory");
            Files.createSymbolicLink(directoryLink, work);
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.safeDirectory(directoryLink); } });
        } finally { remove(work); }
    }

    private static void rollsBackFailedInstall() throws Exception {
        Path fixture = fixture();
        try {
            CodexRuntimeUpdater.Plan plan = plan(fixture);
            prepare(plan);
            CodexRuntimeUpdater.Mover failSecond = new CodexRuntimeUpdater.Mover() {
                int count;
                @Override public void move(Path from, Path to) throws IOException {
                    if (++count == 2) throw new IOException("injected write failure");
                    MOVE.move(from, to);
                }
            };
            rejects(new Action() { public void run() throws Exception { plan.commit(failSecond); } });
            for (String file : CodexRuntimeUpdater.MANAGED) {
                TestSupport.assertEquals(plan.before.get(file), CodexRuntimeUpdater.text(fixture.resolve(file)), "rollback restores all source bytes");
            }
            TestSupport.assertFalse(Files.exists(fixture.resolve(".build/codex-update.pending")), "successful rollback clears journal");
        } finally { remove(fixture); }
    }

    private static void preservesConflictingEditsDuringRollback() throws Exception {
        Path fixture = fixture();
        try {
            CodexRuntimeUpdater.Plan plan = plan(fixture);
            prepare(plan);
            CodexRuntimeUpdater.Mover conflicting = new CodexRuntimeUpdater.Mover() {
                int count;
                @Override public void move(Path from, Path to) throws IOException {
                    if (++count == 2) {
                        Files.write(fixture.resolve(CodexRuntimeUpdater.BUILD), "new user work".getBytes(StandardCharsets.UTF_8));
                        throw new IOException("injected failure after a concurrent edit");
                    }
                    MOVE.move(from, to);
                }
            };
            rejects(new Action() { public void run() throws Exception { plan.commit(conflicting); } });
            TestSupport.assertEquals("new user work", CodexRuntimeUpdater.text(fixture.resolve(CodexRuntimeUpdater.BUILD)), "rollback never overwrites new user work");
            TestSupport.assertTrue(Files.exists(fixture.resolve(".build/codex-update.pending")), "conflict leaves recoverable journal");
        } finally { remove(fixture); }
    }

    private static void boundsSubprocessExecution() throws Exception {
        Path work = Files.createTempDirectory("codex-command-");
        try {
            long start = System.nanoTime();
            rejects(new Action() {
                public void run() throws Exception {
                    CodexRuntimeUpdater.command(work, Collections.<String, String>emptyMap(), 1, "/bin/sh", "-c", "sleep 20");
                }
            });
            TestSupport.assertTrue(System.nanoTime() - start < 10_000_000_000L, "finite subprocess timeout");
        } finally { remove(work); }
    }

    private static void selectsLocalForkArchives() throws Exception {
        Path source = Files.createTempDirectory("local fork with spaces ");
        try {
            Files.createDirectories(source.resolve("npm-package"));
            Path project = Paths.get(System.getProperty("agentcodi.projectRoot")).toAbsolutePath();
            Map<String, String> pins = CodexRuntimeUpdater.readPins(CodexRuntimeUpdater.text(project.resolve(CodexRuntimeUpdater.BUILD)));
            String version = pins.get("CODEX_ANDROID_VERSION");
            Map<String, Object> metadata = JsonCodec.object("name", CodexRuntimeUpdater.PACKAGE, "version", version,
                "license", "Apache-2.0", "os", JsonCodec.array("android"), "cpu", JsonCodec.array("arm64"),
                "repository", JsonCodec.object("url", "git+https://github.com/" + CodexRuntimeUpdater.FORK + ".git"));
            Files.write(source.resolve("npm-package/package.json"), JsonCodec.stringify(metadata).getBytes(StandardCharsets.UTF_8));
            CodexLocalSource.Options options = CodexLocalSource.Options.parse(project,
                new String[] {project.toString(), "--source-dir", source.toString(), "--dry-run"}, Collections.<String, String>emptyMap());
            TestSupport.assertTrue(options.dryRun, "dry-run is explicit");
            rejects(new Action() { public void run() throws Exception { options.selectArchive(); } });
            Path first = source.resolve("downloaded.tgz");
            Files.write(first, new byte[] {1});
            TestSupport.assertEquals(first, options.selectArchive(), "one renamed download is accepted");
            Path second = source.resolve("another.tgz");
            Files.write(second, new byte[] {2});
            rejects(new Action() { public void run() throws Exception { options.selectArchive(); } });
            Path matching = source.resolve("mmmbuto-codex-cli-termux-" + version + ".tgz");
            Files.write(matching, new byte[] {3});
            TestSupport.assertEquals(matching, options.selectArchive(), "source package version disambiguates older archives");
            CodexLocalSource.Options explicit = CodexLocalSource.Options.parse(project,
                new String[] {project.toString(), "--source-dir", source.toString(), "--archive", first.toString()},
                Collections.<String, String>emptyMap());
            TestSupport.assertEquals(first, explicit.selectArchive(), "explicit path overrides discovery");
            Path link = source.resolve("linked.tgz");
            Files.createSymbolicLink(link, first);
            explicit.archive = link;
            rejects(new Action() { public void run() throws Exception { explicit.selectArchive(); } });
            rejects(new Action() { public void run() throws Exception {
                CodexLocalSource.Options.parse(project, new String[] {project.toString(), "latest"}, Collections.<String, String>emptyMap());
            } });
        } finally { remove(source); }
    }

    private static void keepsDependencyReviewStrictAcrossVersionBumps() throws Exception {
        String old = "[workspace.package]\nversion = \"0.153.3-agentcodi.1\"\n[workspace.dependencies]\nlibc = \"0.2.1\"\n";
        String next = old.replace("0.153.3-agentcodi.1", "0.153.4-agentcodi.1");
        TestSupport.assertEquals(CodexLocalSource.normalizeDependency("codex-rs/Cargo.toml", old),
            CodexLocalSource.normalizeDependency("codex-rs/Cargo.toml", next), "workspace version bumps are automatic");
        TestSupport.assertFalse(CodexLocalSource.normalizeDependency("Cargo.toml", old).equals(
            CodexLocalSource.normalizeDependency("Cargo.toml", next.replace("0.2.1", "0.2.2"))), "external dependency changes still need review");
        String lock = "version = 4\n[[package]]\nname = \"codex-cli\"\nversion = \"0.153.3\"\n\n"
            + "[[package]]\nname = \"libc\"\nversion = \"0.2.1\"\nsource = \"registry+fixture\"\nchecksum = \"fixture\"\n";
        TestSupport.assertEquals(CodexLocalSource.normalizeDependency("Cargo.lock", lock),
            CodexLocalSource.normalizeDependency("Cargo.lock", lock.replace("0.153.3", "0.153.4")), "workspace lockfile version bumps are automatic");
        TestSupport.assertFalse(CodexLocalSource.normalizeDependency("Cargo.lock", lock).equals(
            CodexLocalSource.normalizeDependency("Cargo.lock", lock.replace("0.2.1", "0.2.2"))), "locked external versions stay exact");
        for (String name : new String[] {"app_test_support", "core_test_support", "mcp_test_support"}) {
            String support = "[[package]]\nname = \"" + name + "\"\nversion = \"0.153.3-agentcodi.1\"\n"
                + "dependencies = [\"codex-core\"]\n";
            String bumped = support.replace("0.153.3-agentcodi.1", "0.153.3-agentcodi.2");
            CodexLocalSource.verifyDependencyInput("codex-rs/Cargo.lock", support, bumped, "unused");
            rejects(new Action() { public void run() throws Exception {
                CodexLocalSource.verifyDependencyInput("codex-rs/Cargo.lock", support,
                    bumped.replace("codex-core", "unreviewed-dependency"), "unused");
            } });
            String registry = support + "source = \"registry+fixture\"\nchecksum = \"fixture\"\n";
            rejects(new Action() { public void run() throws Exception {
                CodexLocalSource.verifyDependencyInput("codex-rs/Cargo.lock", registry,
                    registry.replace("0.153.3-agentcodi.1", "0.153.3-agentcodi.2"), "unused");
            } });
        }
        String unknown = "[[package]]\nname = \"unreviewed_test_support\"\nversion = \"1.0.0\"\n";
        rejects(new Action() { public void run() throws Exception {
            CodexLocalSource.verifyDependencyInput("Cargo.lock", unknown, unknown.replace("1.0.0", "2.0.0"), "unused");
        } });
        TestSupport.assertTrue(CodexLocalSource.dependencyInput("codex-rs/linux-sandbox/Cargo.toml"), "sandbox dependency declarations are checked");
        TestSupport.assertTrue(CodexLocalSource.dependencyInput("codex-rs/vendor/LICENSE-MIT"), "vendored licenses are checked");
        TestSupport.assertFalse(CodexLocalSource.dependencyInput("codex-rs/linux-sandbox/src/android_sandbox/mem.rs"), "ordinary fork source corrections do not require repinning dependencies");
    }

    private static void cachesSameVersionRebuildsByContent() throws Exception {
        Path work = Files.createTempDirectory("codex-cache-");
        try {
            Path a = work.resolve("first.tgz");
            Path b = work.resolve("rebuilt.tgz");
            Files.write(a, new byte[] {1, 2, 3});
            Files.write(b, new byte[] {4, 5, 6});
            String hashA = CodexRuntimeUpdater.digest(a, "SHA-256");
            String hashB = CodexRuntimeUpdater.digest(b, "SHA-256");
            Path cachedA = work.resolve("cache/" + hashA + "/package.tgz");
            Path cachedB = work.resolve("cache/" + hashB + "/package.tgz");
            CodexRuntimeUpdater.installVerified(a, cachedA, hashA, 1024);
            CodexRuntimeUpdater.installVerified(b, cachedB, hashB, 1024);
            CodexRuntimeUpdater.installVerified(a, cachedA, hashA, 1024);
            TestSupport.assertEquals(hashA, CodexRuntimeUpdater.digest(cachedA, "SHA-256"), "old baseline remains intact");
            TestSupport.assertEquals(hashB, CodexRuntimeUpdater.digest(cachedB, "SHA-256"), "same-version rebuild has its own cache entry");
            rejects(new Action() { public void run() throws Exception { CodexRuntimeUpdater.installVerified(b, cachedA, hashB, 1024); } });
            TestSupport.assertEquals(hashA, CodexRuntimeUpdater.digest(cachedA, "SHA-256"), "conflicting cache bytes are never overwritten");
        } finally { remove(work); }
    }

    private static void refusesToHideReplacedBuildInputsBehindTheCache() throws Exception {
        Path work = Files.createTempDirectory("codex build input with spaces ");
        try {
            Path source = work.resolve("fork");
            Path cache = work.resolve("cache");
            TestSupport.assertEquals(work.resolve(".cache/android"), CodexLocalSource.Options.cacheDirectory(work,
                Collections.<String, String>emptyMap()), "unset cache matches the APK builder default");
            TestSupport.assertEquals(work.resolve(".cache/android"), CodexLocalSource.Options.cacheDirectory(work,
                Collections.singletonMap("AGENTCODI_CACHE_DIR", "")), "empty cache matches the APK builder default");
            TestSupport.assertEquals(cache, CodexLocalSource.Options.cacheDirectory(work,
                Collections.singletonMap("AGENTCODI_CACHE_DIR", cache.toString())), "explicit cache directory is preserved");
            Files.createDirectories(source.resolve("npm-package"));
            Map<String, Object> metadata = JsonCodec.object("name", CodexRuntimeUpdater.PACKAGE, "version", "1.2.3",
                "license", "Apache-2.0", "os", JsonCodec.array("android"), "cpu", JsonCodec.array("arm64"),
                "repository", JsonCodec.object("url", "git+https://github.com/" + CodexRuntimeUpdater.FORK + ".git"));
            Path packageJson = source.resolve("npm-package/package.json");
            Files.write(packageJson, JsonCodec.stringify(metadata).getBytes(StandardCharsets.UTF_8));
            Path local = source.resolve("mmmbuto-codex-cli-termux-1.2.3.tgz");
            Files.write(local, new byte[] {1, 2, 3});
            String hash = CodexRuntimeUpdater.digest(local, "SHA-256");
            Path cached = cache.resolve("codex/" + hash + "/package.tgz");
            CodexRuntimeUpdater.installVerified(local, cached, hash, 1024);
            CodexLocalSource.Options options = CodexLocalSource.Options.parse(work,
                new String[] {work.toString(), "--source-dir", source.toString()}, Collections.<String, String>emptyMap());
            TestSupport.assertEquals(local, options.selectBuildArchive(cache, hash), "local bytes take precedence over cache");
            Files.write(local, new byte[] {4, 5, 6});
            rejects(new Action() { public void run() throws Exception { options.selectBuildArchive(cache, hash); } });
            TestSupport.assertEquals(hash, CodexRuntimeUpdater.digest(cached, "SHA-256"), "replacement never overwrites the old cache");
            Files.delete(local);
            Files.createSymbolicLink(local, cached);
            rejects(new Action() { public void run() throws Exception { options.selectBuildArchive(cache, hash); } });
            Files.delete(local);
            TestSupport.assertEquals(cached, options.selectBuildArchive(cache, hash), "cache works when no local archive exists");
            Files.write(local, new byte[] {1, 2, 3});
            metadata.put("version", "1.2.4");
            Files.write(packageJson, JsonCodec.stringify(metadata).getBytes(StandardCharsets.UTF_8));
            Path next = source.resolve("mmmbuto-codex-cli-termux-1.2.4.tgz");
            Files.write(next, new byte[] {4, 5, 6});
            rejects(new Action() { public void run() throws Exception { options.selectBuildArchive(cache, hash); } });
            options.archive = cached;
            TestSupport.assertEquals(cached, options.selectBuildArchive(cache, hash), "explicit verified input overrides discovery");
            options.archive = work.resolve("missing.tgz");
            rejects(new Action() { public void run() throws Exception { options.selectBuildArchive(cache, hash); } });
            options.archive = null;
            options.source = work.resolve("absent-fork");
            TestSupport.assertEquals(cached, options.selectBuildArchive(cache, hash), "a checkout without the fork can use verified cached bytes");
            Files.write(cached, new byte[] {7, 8, 9});
            rejects(new Action() { public void run() throws Exception { options.selectBuildArchive(cache, hash); } });
        } finally { remove(work); }
    }

    private static void bindsLocalBytesToTheirBuildCommit() throws Exception {
        Map<String, String> old = new LinkedHashMap<String, String>();
        old.put("CODEX_ANDROID_SHA256", repeat('a', 64));
        old.put("CODEX_TERMUX_SOURCE_COMMIT", repeat('b', 40));
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        TestSupport.assertEquals(repeat('b', 40), CodexLocalSource.sourceCommit(legacy, old, repeat('a', 64), null),
            "known legacy bytes retain their previously verified revision");
        rejects(new Action() { public void run() throws Exception {
            CodexLocalSource.sourceCommit(legacy, old, repeat('c', 64), null);
        } });
        Map<String, Object> rebuilt = JsonCodec.object("gitHead", repeat('d', 40));
        TestSupport.assertEquals(repeat('d', 40), CodexLocalSource.sourceCommit(rebuilt, old, repeat('c', 64), null),
            "same-version replacement uses the build's embedded source revision");
        rejects(new Action() { public void run() throws Exception {
            CodexLocalSource.sourceCommit(rebuilt, old, repeat('a', 64), null);
        } });
        rejects(new Action() { public void run() throws Exception {
            CodexLocalSource.sourceCommit(rebuilt, old, repeat('c', 64), repeat('e', 40));
        } });
        TestSupport.assertEquals(repeat('d', 40), CodexLocalSource.sourceCommit(legacy, old, repeat('c', 64), repeat('d', 40)),
            "legacy archives may explicitly identify their actual build revision");
    }

    interface Action { void run() throws Exception; }
    private static void rejects(final Action action) {
        TestSupport.expectThrows(IOException.class, new TestSupport.ThrowingRunnable() {
            @Override public void run() throws Exception { action.run(); }
        }, "fail closed on unsafe or inconsistent update");
    }
    private static void remove(Path path) throws IOException {
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) remove(child);
            }
        }
        Files.deleteIfExists(path);
    }
}
