package io.github.abysstudio.mingui;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * In-app language choice. On Android 13+ it's the system per-app language (so it also applies to the
 * widget and notifications, and shows up in Android's settings); older versions keep it in prefs.
 */
final class Lang {
    /** "" = follow the phone. */
    static final String[] TAGS = {"", "en", "es", "ca"};

    private Lang() {
    }

    /** The chosen language tag, or "" when following the phone. */
    static String current(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList l = c.getSystemService(LocaleManager.class).getApplicationLocales();
            return l.isEmpty() ? "" : l.get(0).getLanguage();
        }
        return Routine.prefs(c).getString("app_lang", "");
    }

    static void set(Activity a, String tag) {
        if (tag.equals(current(a))) return;
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the activity with the new language.
            a.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            Routine.prefs(a).edit().putString("app_lang", tag).apply();
            a.recreate();
        }
    }

    /** Before Android 13: applies the stored choice to an activity's context. */
    static Context wrap(Context base) {
        if (Build.VERSION.SDK_INT >= 33) return base;
        String tag = Routine.prefs(base).getString("app_lang", "");
        if (tag.isEmpty()) return base;
        Locale l = Locale.forLanguageTag(tag);
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocales(new LocaleList(l));
        return base.createConfigurationContext(conf);
    }

    /** A language's own name ("Català"); "" means the phone's language. */
    static String nativeName(String tag) {
        Locale l = Locale.forLanguageTag(tag);
        String n = l.getDisplayLanguage(l);
        return n.isEmpty() ? tag : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
