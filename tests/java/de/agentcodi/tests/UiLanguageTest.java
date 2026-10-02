package de.agentcodi.tests;

import de.agentcodi.core.UiLanguage;

public final class UiLanguageTest {
    private UiLanguageTest() {
    }

    public static int run() {
        defaultsToDeviceLanguage();
        resolvesSupportedDeviceLanguages();
        explicitSelectionOverridesDeviceLanguage();
        return 3;
    }

    private static void defaultsToDeviceLanguage() {
        TestSupport.assertEquals(
            UiLanguage.SYSTEM,
            UiLanguage.fromPreference(null),
            "missing language preference"
        );
        TestSupport.assertEquals(
            UiLanguage.SYSTEM,
            UiLanguage.fromPreference("unsupported"),
            "unknown language preference"
        );
        TestSupport.assertEquals(
            UiLanguage.SYSTEM,
            UiLanguage.fromPreference("system"),
            "return to device language"
        );
    }

    private static void resolvesSupportedDeviceLanguages() {
        TestSupport.assertEquals(
            "de",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "de-DE"),
            "German device language"
        );
        TestSupport.assertEquals(
            "en",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "en-GB"),
            "English device language"
        );
        TestSupport.assertEquals(
            "zh-CN",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "zh-CN"),
            "Simplified Chinese device language"
        );
        TestSupport.assertEquals(
            "zh-CN",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "zh-Hans-CN"),
            "Simplified Chinese script device language"
        );
        TestSupport.assertEquals(
            "en",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "fr-FR"),
            "unsupported device language falls back to English"
        );
    }

    private static void explicitSelectionOverridesDeviceLanguage() {
        TestSupport.assertEquals(
            "en",
            UiLanguage.effectiveLanguageTag(UiLanguage.ENGLISH, "de-DE"),
            "explicit English"
        );
        TestSupport.assertEquals(
            "de",
            UiLanguage.effectiveLanguageTag(UiLanguage.GERMAN, "en-US"),
            "explicit German"
        );
        TestSupport.assertEquals(
            "zh-CN",
            UiLanguage.effectiveLanguageTag(UiLanguage.SIMPLIFIED_CHINESE, "en-US"),
            "explicit Simplified Chinese"
        );
        TestSupport.assertEquals(
            UiLanguage.SIMPLIFIED_CHINESE,
            UiLanguage.fromPreference("simplified_chinese"),
            "Simplified Chinese preference"
        );
    }
}
