package de.agentcodi.tests;

import de.agentcodi.core.UiLanguage;

public final class UiLanguageTest {
    private UiLanguageTest() {
    }

    public static int run() {
        defaultsToDeviceLanguage();
        resolvesSupportedDeviceLanguages();
        explicitSelectionOverridesDeviceLanguage();
        resolvesSimplifiedChineseDeviceLanguages();
        honorsChineseScriptsBeforeRegions();
        unsupportedChineseScriptsFallBackToEnglish();
        restoresSimplifiedChineseSelection();
        return 7;
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
        TestSupport.assertEquals(
            "zh-Hans",
            UiLanguage.effectiveLanguageTag(UiLanguage.SIMPLIFIED_CHINESE, "de-DE"),
            "explicit Simplified Chinese overrides device language"
        );
        TestSupport.assertEquals(
            "en",
            UiLanguage.effectiveLanguageTag(UiLanguage.ENGLISH, "zh-CN"),
            "explicit English overrides a Chinese device"
        );
        TestSupport.assertEquals(
            "de",
            UiLanguage.effectiveLanguageTag(UiLanguage.GERMAN, "zh-Hans"),
            "explicit German overrides a Chinese device"
        );
    }

    private static void resolvesSimplifiedChineseDeviceLanguages() {
        for (String tag : new String[] {
            "zh", "zh-Hans", "zh-CN", "zh-SG", "zh-MY", "zh-Hans-CN",
            "zh-Hans-SG", " ZH_hans_cn "
        }) {
            TestSupport.assertEquals(
                "zh-Hans",
                UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, tag),
                "Simplified Chinese device: " + tag
            );
            TestSupport.assertEquals(
                UiLanguage.SIMPLIFIED_CHINESE,
                UiLanguage.fromLanguageTag(tag),
                "shared Android app-language resolution: " + tag
            );
        }
    }

    private static void honorsChineseScriptsBeforeRegions() {
        TestSupport.assertEquals(
            "zh-Hans",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "zh-Hans-TW"),
            "explicit Simplified script overrides a Traditional region"
        );
        TestSupport.assertEquals(
            "en",
            UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, "zh-Hant-CN"),
            "explicit Traditional script overrides a Simplified region"
        );
    }

    private static void unsupportedChineseScriptsFallBackToEnglish() {
        for (String tag : new String[] {
            "zh-Hant", "zh-Hant-TW", "zh-TW", "zh-HK", "zh-MO", "zh-Latn"
        }) {
            TestSupport.assertEquals(
                "en",
                UiLanguage.effectiveLanguageTag(UiLanguage.SYSTEM, tag),
                "unsupported Chinese device locale: " + tag
            );
            TestSupport.assertEquals(
                UiLanguage.SYSTEM,
                UiLanguage.fromLanguageTag(tag),
                "unsupported Android app-language locale: " + tag
            );
        }
        TestSupport.assertEquals(
            "en", UiLanguage.effectiveLanguageTag(null, null), "missing device locale"
        );
        TestSupport.assertEquals(
            UiLanguage.GERMAN, UiLanguage.fromLanguageTag("de_AT"), "legacy German tag"
        );
        TestSupport.assertEquals(
            UiLanguage.ENGLISH, UiLanguage.fromLanguageTag("en-US"), "platform English tag"
        );
    }

    private static void restoresSimplifiedChineseSelection() {
        TestSupport.assertEquals(
            UiLanguage.SIMPLIFIED_CHINESE,
            UiLanguage.fromPreference(UiLanguage.SIMPLIFIED_CHINESE.getPreferenceValue()),
            "restore persisted Simplified Chinese choice"
        );
        TestSupport.assertEquals(
            UiLanguage.SIMPLIFIED_CHINESE,
            UiLanguage.fromPreference(" SIMPLIFIED_CHINESE "),
            "normalize persisted Simplified Chinese choice"
        );
        TestSupport.assertFalse(
            UiLanguage.SIMPLIFIED_CHINESE.followsSystem(), "Chinese selection is explicit"
        );
    }
}
