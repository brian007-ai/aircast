package com.aircast.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Applies the in-app language choice without AndroidX: the chosen locale is
 * baked into a configuration context, and every Activity (plus the
 * Application) wraps its base context in it.
 *
 * The preference is read straight from the prefs file rather than through
 * {@link Settings}, because this can run before an Application instance exists.
 */
final class LocaleHelper {
    private LocaleHelper() {}

    static Context wrap(Context base) {
        String lang = base.getSharedPreferences("cast", Context.MODE_PRIVATE)
                .getString("language", Settings.LANG_SYSTEM);
        if (!Settings.LANG_EN.equals(lang) && !Settings.LANG_ZH.equals(lang)) {
            return base;
        }
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        cfg.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(cfg);
    }
}
