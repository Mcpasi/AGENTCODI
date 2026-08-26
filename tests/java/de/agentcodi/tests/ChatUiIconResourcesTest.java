package de.agentcodi.tests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ChatUiIconResourcesTest {
    private static final String MATERIAL_ICONS_REVISION =
        "e083cc60a0828fdd3b404cea0cb8a5b900e9c23e";

    private ChatUiIconResourcesTest() {
    }

    public static int run() throws Exception {
        pinsTheConvertedVectorResources();
        keepsEveryChatActionOnAnIconButton();
        keepsIconsAccessibleAndAttributed();
        return 3;
    }

    private static void pinsTheConvertedVectorResources() throws Exception {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("ic_chat_active_threads.xml", "b141607bd71c3d8e713ed3e4bbf832130c6ad51d68506cc0154d56be462aadba");
        expected.put("ic_chat_add.xml", "ad8aacbb0b763ff76038f4141ab6d54bce4e2ab9e638f3a88c54b2297db4cc4a");
        expected.put("ic_chat_add_thread.xml", "7a5a0e08cb22633350b2fdf64e4e9d48be8d648352513eeb60ce32a738b8d214");
        expected.put("ic_chat_archived_threads.xml", "2f17b26b07eb4ee255ca55ecd1f39d72440eb5b89a804d0a4a683fda11b6d04d");
        expected.put("ic_chat_back.xml", "df5f492f450d0031e1f357c88246ad55fbae03912be3ed7d3e3dfe385676792d");
        expected.put("ic_chat_detach.xml", "ab5f0086666d9f41c2cd979a0ab3639315b1da6137a513f49e21b6df193e790f");
        expected.put("ic_chat_download.xml", "a5f565675e967a13acff7e75fdfc16acd919be89de0e1034b2b8b30839ba4533");
        expected.put("ic_chat_folder.xml", "58ac70a6301128c58cb53e59f9bb80d37e579ef5b5a67b2d2eb9ed9ec998f842");
        expected.put("ic_chat_hourglass.xml", "0120eade10008ea6d84d9617c7e1665e616cf658818bfe138032d98fbe8cd2a6");
        expected.put("ic_chat_more.xml", "b79a0c84b2a08a3d2cbfa3ee2f96fbf88c966b3b20ebf26e11caa8c78562b841");
        expected.put("ic_chat_refresh.xml", "dbf46b7fc0aeb6021bd88fe0eb5ee979d6a121b661238be8b71b61702b4d1ae5");
        expected.put("ic_chat_review.xml", "63e7c096e2f85dab8e32d38c2b884cea1e35294053d4da9dcf1595858b2950d4");
        expected.put("ic_chat_send.xml", "ebf8ee6b8c59437f65e7b88edaaca91d95dea430ea5e7407375277e183755387");
        expected.put("ic_chat_settings.xml", "9bfd08f3a203d821772fad43d167ac94c7f11f421d754d394196c55ccb656b4e");
        expected.put("ic_chat_stop.xml", "30a987e36989b914ac1935b209743e4899703b0984cbf2593cf1ebe36c938dc1");
        expected.put("ic_chat_terminal.xml", "c64ad90d33a49dd402b9adcab9aa956a2fe39fd2f7cd15cd049e8af43345d710");

        Path drawableDirectory = projectRoot().resolve("app/src/main/res/drawable");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Path resource = drawableDirectory.resolve(entry.getKey());
            TestSupport.assertTrue(
                Files.isRegularFile(resource),
                "chat icon resource exists: " + entry.getKey()
            );
            byte[] bytes = Files.readAllBytes(resource);
            String xml = new String(bytes, StandardCharsets.UTF_8);
            TestSupport.assertContains(xml, "<vector", "chat icon is a vector");
            TestSupport.assertContains(xml, "android:width=\"24dp\"", "chat icon width");
            TestSupport.assertContains(xml, "android:height=\"24dp\"", "chat icon height");
            TestSupport.assertContains(xml, "android:viewportWidth=\"24\"", "chat icon viewport width");
            TestSupport.assertContains(xml, "android:viewportHeight=\"24\"", "chat icon viewport height");
            TestSupport.assertContains(xml, "android:pathData=", "chat icon path");
            TestSupport.assertEquals(
                entry.getValue(),
                sha256(bytes),
                "converted chat icon hash: " + entry.getKey()
            );
        }
        TestSupport.assertEquals(
            Integer.valueOf(16),
            Integer.valueOf(expected.size()),
            "documented chat icon count"
        );
    }

    private static void keepsEveryChatActionOnAnIconButton() throws IOException {
        String activity = read("app/src/main/java/de/agentcodi/app/MainActivity.java");
        TestSupport.assertContains(
            activity,
            "import android.widget.ImageButton;",
            "chat uses Android image buttons"
        );
        TestSupport.assertFalse(
            activity.contains("import android.widget.Button;"),
            "chat does not retain text-button widgets"
        );
        TestSupport.assertFalse(
            activity.contains("theme.compactButton(")
                || activity.contains("theme.primaryButton(")
                || activity.contains("theme.secondaryButton("),
            "chat does not build text-width buttons"
        );

        String[] bindings = new String[] {
            "R.drawable.ic_chat_back",
            "R.drawable.ic_chat_folder",
            "R.drawable.ic_chat_terminal",
            "R.drawable.ic_chat_settings",
            "R.drawable.ic_chat_refresh",
            "R.drawable.ic_chat_active_threads",
            "R.drawable.ic_chat_archived_threads",
            "R.drawable.ic_chat_add_thread",
            "R.drawable.ic_chat_more",
            "R.drawable.ic_chat_add",
            "R.drawable.ic_chat_detach",
            "R.drawable.ic_chat_review",
            "R.drawable.ic_chat_stop",
            "R.drawable.ic_chat_send",
            "R.drawable.ic_chat_download",
            "R.drawable.ic_chat_hourglass"
        };
        for (String binding : bindings) {
            TestSupport.assertContains(activity, binding, "chat icon binding");
        }

        int importPosition = activity.indexOf("composerRow.addView(importButton);");
        int inputPosition = activity.indexOf("composerRow.addView(composerInput, inputParams);");
        TestSupport.assertTrue(
            importPosition >= 0 && inputPosition > importPosition,
            "file-import plus remains immediately to the left of the composer input"
        );
        TestSupport.assertContains(
            activity,
            "reviewButton.setVisibility(steering ? View.GONE : View.VISIBLE);",
            "review action yields its compact slot during an active turn"
        );
        TestSupport.assertContains(
            activity,
            "stopButton.setVisibility(steering ? View.VISIBLE : View.GONE);",
            "stop action remains visible during an active turn"
        );
        TestSupport.assertContains(
            activity,
            "AgentRuntimeService.interruptTurn();",
            "stop action keeps its runtime handler"
        );
        TestSupport.assertContains(
            activity,
            "AgentRuntimeService.startCustomReview(instructions)",
            "review action keeps its runtime handler"
        );
        TestSupport.assertContains(
            activity,
            "openDocumentImportPicker();",
            "import action keeps its document picker handler"
        );
    }

    private static void keepsIconsAccessibleAndAttributed() throws IOException {
        String theme = read("app/src/main/java/de/agentcodi/app/UiTheme.java");
        TestSupport.assertContains(theme, "button.setMinimumWidth(dp(48));", "48 dp touch width");
        TestSupport.assertContains(theme, "button.setMinimumHeight(dp(48));", "48 dp touch height");
        TestSupport.assertContains(theme, "button.setContentDescription(description);", "screen-reader label");
        TestSupport.assertContains(theme, "button.setTooltipText(description);", "long-press tooltip");

        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        for (String resource : new String[] {
            "chat_open_settings",
            "chat_import_files",
            "review_mode_action",
            "turn_stop",
            "turn_steer",
            "message_send",
            "license_material_icons_title",
            "license_material_icons_summary"
        }) {
            String marker = "name=\"" + resource + "\"";
            TestSupport.assertContains(english, marker, "English icon label: " + resource);
            TestSupport.assertContains(german, marker, "German icon label: " + resource);
        }

        String packagedNotice = read("app/src/main/res/raw/material_icons_notice.txt");
        String thirdPartyNotice = read("app/src/main/res/raw/third_party_notices.txt");
        String licensesActivity = read(
            "app/src/main/java/de/agentcodi/app/LicensesActivity.java"
        );
        TestSupport.assertContains(
            packagedNotice,
            MATERIAL_ICONS_REVISION,
            "packaged Material Icons revision"
        );
        TestSupport.assertContains(
            thirdPartyNotice,
            MATERIAL_ICONS_REVISION,
            "aggregate Material Icons revision"
        );
        TestSupport.assertContains(
            licensesActivity,
            "R.raw.material_icons_notice",
            "Material Icons notice is visible in the APK"
        );
        TestSupport.assertContains(
            licensesActivity,
            "R.raw.agentcodi_apache_2_0",
            "complete Apache 2.0 terms remain available"
        );
    }

    private static String read(String relativePath) throws IOException {
        return new String(
            Files.readAllBytes(projectRoot().resolve(relativePath)),
            StandardCharsets.UTF_8
        );
    }

    private static Path projectRoot() {
        String configured = System.getProperty("agentcodi.projectRoot", "");
        if (configured.isEmpty()) {
            throw new IllegalStateException("agentcodi.projectRoot is not configured");
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", Integer.valueOf(item & 0xff)));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
