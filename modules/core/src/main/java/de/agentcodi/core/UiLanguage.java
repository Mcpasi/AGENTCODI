package de.agentcodi.core;

import java.util.Locale;

public enum UiLanguage {
    SYSTEM("system", ""),
    ENGLISH("english", "en"),
    GERMAN("german", "de"),
    SIMPLIFIED_CHINESE("simplified_chinese", "zh-Hans");

    public static final String PREFERENCE_FILE = "agentcodi-ui";
    public static final String PREFERENCE_KEY = "language";

    private final String preferenceValue;
    private final String languageTag;

    UiLanguage(String preferenceValue, String languageTag) {
        this.preferenceValue = preferenceValue;
        this.languageTag = languageTag;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public String getLanguageTag() {
        return languageTag;
    }

    public boolean followsSystem() {
        return this == SYSTEM;
    }

    public static UiLanguage fromPreference(String value) {
        if (value != null) {
            for (UiLanguage language : values()) {
                if (language.preferenceValue.equalsIgnoreCase(value.trim())) {
                    return language;
                }
            }
        }
        return SYSTEM;
    }

    public static String effectiveLanguageTag(UiLanguage selected, String deviceLanguageTag) {
        UiLanguage choice = selected == null ? SYSTEM : selected;
        if (!choice.followsSystem()) {
            return choice.languageTag;
        }
        UiLanguage device = fromLanguageTag(deviceLanguageTag);
        return device.followsSystem() ? ENGLISH.languageTag : device.languageTag;
    }

    public static UiLanguage fromLanguageTag(String languageTag) {
        if (languageTag == null || languageTag.trim().isEmpty()) {
            return SYSTEM;
        }
        Locale locale = Locale.forLanguageTag(languageTag.trim().replace('_', '-'));
        if ("de".equals(locale.getLanguage())) {
            return GERMAN;
        }
        if ("en".equals(locale.getLanguage())) {
            return ENGLISH;
        }
        if (!"zh".equals(locale.getLanguage())) {
            return SYSTEM;
        }
        // An explicit script takes precedence over the region. Without a script,
        // Chinese defaults to Hans except in Taiwan, Hong Kong and Macao.
        if (!locale.getScript().isEmpty()) {
            return "Hans".equals(locale.getScript()) ? SIMPLIFIED_CHINESE : SYSTEM;
        }
        String region = locale.getCountry();
        return "TW".equals(region) || "HK".equals(region) || "MO".equals(region)
            ? SYSTEM
            : SIMPLIFIED_CHINESE;
    }
}
