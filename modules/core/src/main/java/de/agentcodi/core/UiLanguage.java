package de.agentcodi.core;

import java.util.Locale;

public enum UiLanguage {
    SYSTEM("system", ""),
    ENGLISH("english", "en"),
    GERMAN("german", "de");

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
        String normalized = deviceLanguageTag == null
            ? ""
            : deviceLanguageTag.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("de") || normalized.startsWith("de-")
            || normalized.startsWith("de_")
            ? GERMAN.languageTag
            : ENGLISH.languageTag;
    }
}
