package de.agentcodi.runtime;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import de.agentcodi.core.UiLanguage;

import java.util.Locale;

final class RuntimeText {
    static final String NOTIFICATION_STARTING = "runtime_notification_starting";
    static final String NOTIFICATION_READY = "runtime_notification_ready";
    static final String NOTIFICATION_DISCONNECTED = "runtime_notification_disconnected";
    static final String NOTIFICATION_ERROR = "runtime_notification_error";
    static final String CHANNEL_NAME = "runtime_notification_channel";
    static final String CHANNEL_DESCRIPTION = "runtime_notification_channel_description";

    private RuntimeText() {
    }

    static String get(Context context, String resourceName, String fallback) {
        Context localized = localizedContext(context);
        int identifier = localized.getResources().getIdentifier(
            resourceName,
            "string",
            localized.getPackageName()
        );
        return identifier == 0 ? fallback : localized.getString(identifier);
    }

    private static Context localizedContext(Context context) {
        UiLanguage language = selected(context);
        Configuration configuration = new Configuration(
            context.getResources().getConfiguration()
        );
        String deviceTag = Resources.getSystem().getConfiguration()
            .getLocales()
            .get(0)
            .toLanguageTag();
        String languageTag = UiLanguage.effectiveLanguageTag(language, deviceTag);
        Locale locale = Locale.forLanguageTag(languageTag);
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }

    private static UiLanguage selected(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            UiLanguage platformSelection = Api33.selected(context);
            if (platformSelection != null) {
                return platformSelection;
            }
        }
        return UiLanguage.fromPreference(
            context.getSharedPreferences(UiLanguage.PREFERENCE_FILE, Context.MODE_PRIVATE)
                .getString(
                    UiLanguage.PREFERENCE_KEY,
                    UiLanguage.SYSTEM.getPreferenceValue()
                )
        );
    }

    private static final class Api33 {
        private Api33() {
        }

        static UiLanguage selected(Context context) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager == null) {
                return null;
            }
            LocaleList locales = manager.getApplicationLocales();
            if (locales.isEmpty()) {
                return UiLanguage.SYSTEM;
            }
            String language = locales.get(0).getLanguage();
            if ("de".equalsIgnoreCase(language)) {
                return UiLanguage.GERMAN;
            }
            if ("en".equalsIgnoreCase(language)) {
                return UiLanguage.ENGLISH;
            }
            return UiLanguage.SYSTEM;
        }
    }
}
