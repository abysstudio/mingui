package io.github.abysstudio.mingui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Hides the blocked apps (via root `pm hide`) while today's routine is unfinished, and unhides them once it's done. */
final class Gate {
    static final String ACTION_MIDNIGHT = "io.github.abysstudio.mingui.MIDNIGHT";
    /** Bump when the su command changes so existing installs re-run it once. */
    private static final int OPS_VERSION = 4;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private Gate() {}

    /**
     * Applies the hidden/visible state for today. Skips the su call when the state is already applied,
     * unless {@code force}. {@code callback} (nullable) runs on the main thread with whether root worked.
     */
    static void sync(Context context, boolean force, Consumer<Boolean> callback) {
        final Context c = context.getApplicationContext();
        if (!BuildFlags.ROOT) {
            // No root: nothing to hide. Blocked apps are covered by UnlockWatcher instead.
            if (callback != null) new Handler(Looper.getMainLooper()).post(() -> callback.accept(true));
            return;
        }
        IO.execute(() -> {
            SharedPreferences p = Routine.prefs(c);
            Set<String> selected = Routine.blockedApps(c);
            Set<String> desired = Routine.isDoneToday(c) ? new HashSet<>() : selected;
            Set<String> applied = new HashSet<>(p.getStringSet("hidden_pkgs",
                    p.getBoolean("hidden", false) ? Routine.DEFAULT_BLOCKED : new HashSet<>()));
            boolean ok;
            if (!force && p.getInt("ops_version", 0) >= OPS_VERSION && applied.equals(desired)) {
                ok = true;
            } else {
                // Unhide anything we hid before (or that's selected) that shouldn't be hidden now.
                Set<String> show = new HashSet<>(applied);
                show.addAll(selected);
                show.removeAll(desired);
                ok = RootOps.apply(c, desired, show);
                SharedPreferences.Editor e = p.edit().putBoolean("root_ok", ok);
                if (ok) e.putStringSet("hidden_pkgs", new HashSet<>(desired)).putInt("ops_version", OPS_VERSION).remove("hidden");
                e.apply();
            }
            if (callback != null) {
                final boolean result = ok;
                new Handler(Looper.getMainLooper()).post(() -> callback.accept(result));
            }
        });
    }

    static boolean rootOk(Context c) {
        if (!BuildFlags.ROOT) return true;
        return Routine.prefs(c).getBoolean("root_ok", true);
    }

    /** Wakes BootReceiver just after the day boundary so blocked apps are re-hidden for the new day. */
    static void scheduleMidnight(Context c) {
        // Next day boundary (DAY_START_HOUR), not literal midnight.
        Calendar cal = Routine.dayCalendar();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        cal.set(Calendar.HOUR_OF_DAY, Routine.DAY_START_HOUR);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 5);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0,
                new Intent(c, AlarmReceiver.class).setAction(ACTION_MIDNIGHT),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        setAlarm(am, cal.getTimeInMillis(), pi);
    }

    /**
     * Wake-up alarm. The rooted build may use exact alarms; the Play (Lite) build has no exact-alarm
     * permission, so it uses an inexact one (fires within a few minutes, even in Doze).
     */
    static void setAlarm(AlarmManager am, long at, PendingIntent pi) {
        if (BuildFlags.ROOT) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            } catch (SecurityException ignored) {
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }
}
