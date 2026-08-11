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
    }
}
