package io.github.abysstudio.mingui;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Looks up installed apps, including ones we've hidden. */
final class Apps {
    private static final int FLAGS = PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_COMPONENTS;

    static final class Entry {
        final String pkg, label;
        final Drawable icon;

        Entry(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private Apps() {}

    /** The given packages that are installed (hidden or not), sorted by label. */
    static List<Entry> load(Context c, Collection<String> pkgs) {
        List<Entry> out = new ArrayList<>();
        for (String pkg : pkgs) {
            Entry e = entry(c, pkg);
            if (e != null) out.add(e);
        }
        sort(out);
        return out;
    }

    /** Every app with a launcher icon, plus any extra packages (e.g. currently hidden ones). Slow: call off the main thread. */
    static List<Entry> launchable(Context c, Collection<String> extra) {
        PackageManager pm = c.getPackageManager();
        Map<String, Entry> map = new LinkedHashMap<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, FLAGS)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
            Entry e = entry(c, pkg);
            if (e != null) map.put(pkg, e);
        }
        for (String pkg : extra) {
            if (map.containsKey(pkg)) continue;
            Entry e = entry(c, pkg);
            if (e != null) map.put(pkg, e);
        }
        List<Entry> out = new ArrayList<>(map.values());
        sort(out);
        return out;
    }

    static Entry entry(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, FLAGS);
            return new Entry(pkg, String.valueOf(pm.getApplicationLabel(ai)), pm.getApplicationIcon(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String fg;
    private static long lastQuery;

    /**
     * Package of the app currently in front, from usage events; null if unknown (no usage access).
     * Reads only events since the last call, so it's cheap enough to poll every second.
     */
    @SuppressWarnings("deprecation")
    static synchronized String foreground(Context c) {
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        long from = lastQuery == 0 ? now - 3 * 60 * 60_000L : lastQuery - 2000;
        UsageEvents events = um.queryEvents(from, now);
        lastQuery = now;
        if (events == null) return fg;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) fg = e.getPackageName();
        }
        return fg;
    }

    /**
     * Apps that must always stay reachable: settings, phone/dialer, emergency, installer/uninstaller,
     * permission screens. Focus lock never pulls you out of these, and they can't be blocked.
     */
    static java.util.Set<String> essentials(Context c) {
        java.util.Set<String> s = new java.util.HashSet<>(java.util.Arrays.asList(
                "com.android.settings", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
                "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
                "com.android.emergency", "com.google.android.apps.safetyhub",
                "com.android.packageinstaller", "com.google.android.packageinstaller",
                "com.android.permissioncontroller", "com.google.android.permissioncontroller",
                c.getPackageName()));
        try {
            android.telecom.TelecomManager tm = c.getSystemService(android.telecom.TelecomManager.class);
            if (tm != null && tm.getDefaultDialerPackage() != null) s.add(tm.getDefaultDialerPackage());
        } catch (RuntimeException ignored) {
        }
        return s;
    }

    private static void sort(List<Entry> list) {
        Collections.sort(list, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
    }
}
