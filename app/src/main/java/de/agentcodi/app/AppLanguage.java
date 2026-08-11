package de.agentcodi.app;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import de.agentcodi.core.UiLanguage;

import java.util.Locale;

final class AppLanguage {
    private AppLanguage() {
    }

    static Context attach(Context base) {
        UiLanguage selected = selected(base);
        Configuration configuration = new Configuration(
            base.getResources().getConfiguration()
        );
        String languageTag = selected.followsSystem()
            ? UiLanguage.effectiveLanguageTag(selected, deviceLanguageTag())
            : selected.getLanguageTag();
        Locale locale = Locale.forLanguageTag(languageTag);
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    static UiLanguage selected(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            UiLanguage platformSelection = Api33.selected(context);
            if (platformSelection != null) {
                return platformSelection;
            }
        }
        return UiLanguage.fromPreference(preferences(context).getString(
            UiLanguage.PREFERENCE_KEY,
            UiLanguage.SYSTEM.getPreferenceValue()
        ));
    }

    static String effectiveLanguageTag(Context context) {
        return UiLanguage.effectiveLanguageTag(selected(context), deviceLanguageTag());
    }

    static boolean select(Activity activity, UiLanguage language) {
        UiLanguage next = language == null ? UiLanguage.SYSTEM : language;
        UiLanguage current = selected(activity);
        if (current == next) {
            return false;
        }
        preferences(activity).edit()
            .putString(UiLanguage.PREFERENCE_KEY, next.getPreferenceValue())
            .apply();
        if (Build.VERSION.SDK_INT >= 33 && Api33.select(activity, next)) {
            return true;
        }
        activity.recreate();
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(
            UiLanguage.PREFERENCE_FILE,
            Context.MODE_PRIVATE
        );
    }

    private static String deviceLanguageTag() {
        return Resources.getSystem().getConfiguration().getLocales().get(0).toLanguageTag();
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

        static boolean select(Context context, UiLanguage language) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager == null) {
                return false;
            }
            LocaleList locales = language.followsSystem()
                ? LocaleList.getEmptyLocaleList()
                : LocaleList.forLanguageTags(language.getLanguageTag());
            manager.setApplicationLocales(locales);
            return true;
        }
    }
}
