package io.github.abysstudio.mingui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * When the checklist pops up by itself, so it doesn't turn into wallpaper:
 * a cooldown and a daily cap on unlock prompts, scheduled nudges, firmer prompting late in the day,
 * and an extra nudge just before your usual weak hour.
 */
final class Prompts {
    static final String ACTION_NUDGE = "io.github.abysstudio.mingui.NUDGE";
    static final int[] COOLDOWN_CHOICES = {0, 5, 15, 30, 60};
    static final int[] CAP_CHOICES = {3, 5, 8, 12, 0}; // 0 = no limit
    static final int[] FIRM_CHOICES = {-1, 16, 17, 18, 19, 20, 21}; // -1 = off
    /** Minimum exit attempts in the last 2 weeks before the weak-hour nudge kicks in. */
    private static final int PEAK_MIN_ATTEMPTS = 5;

    private Prompts() {}

    private static SharedPreferences prefs(Context c) {
        return Routine.prefs(c);
    }

    static int cooldownMinutes(Context c) {
        return prefs(c).getInt("prompt_cooldown", 15);
    }

    static int dailyCap(Context c) {
        return prefs(c).getInt("prompt_cap", 8);
    }

    static int firmHour(Context c) {
        return prefs(c).getInt("prompt_firm_hour", 18);
    }

    static boolean peakNudge(Context c) {
        return prefs(c).getBoolean("prompt_peak_nudge", true);
    }

    static void setInt(Context c, String key, int v) {
        prefs(c).edit().putInt(key, v).apply();
    }

    static void setPeakNudge(Context c, boolean on) {
        prefs(c).edit().putBoolean("prompt_peak_nudge", on).apply();
    }

    /** Nudge times as minutes after midnight, sorted. */
    static List<Integer> nudges(Context c) {
        Set<String> raw = prefs(c).getStringSet("prompt_nudges", new HashSet<>(Arrays.asList("540", "1200")));
        List<Integer> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out);
        return out;
    }

    static void setNudges(Context c, List<Integer> minutes) {
        Set<String> s = new HashSet<>();
        for (int m : minutes) s.add(String.valueOf(m));
        prefs(c).edit().putStringSet("prompt_nudges", s).apply();
    }

    static String hhmm(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** The checklist was on screen (any reason); starts the cooldown. */
    static void markShown(Context c) {
        prefs(c).edit().putLong("prompt_last", System.currentTimeMillis()).apply();
    }

    /** Late in the routine day with less than half done: ignore cooldown and cap. */
    static boolean firm(Context c) {
        int firm = firmHour(c);
        if (firm < 0) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        boolean late = h >= firm || h < Routine.DAY_START_HOUR;
        if (!late) return false;
        List<String> items = Routine.items(c);
        if (items.isEmpty()) return false;
        Set<String> checked = Routine.checked(c);
        int n = 0;
        for (String i : items) if (checked.contains(i)) n++;
        return (float) n / items.size() < History.PARTIAL;
    }

    /** Whether an unlock should open the checklist (when focus lock isn't already doing that). */
    static boolean shouldPromptOnUnlock(Context c) {
        if (firm(c)) return true;
        long since = System.currentTimeMillis() - prefs(c).getLong("prompt_last", 0);
        if (since < cooldownMinutes(c) * 60_000L) return false;
        int cap = dailyCap(c);
        return cap == 0 || prefs(c).getInt("prompts_" + Routine.today(), 0) < cap;
    }

    /** Count an unlock prompt against today's cap. */
    static void recordUnlockPrompt(Context c) {
        String key = "prompts_" + Routine.today();
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : prefs(c).getAll().keySet()) {
            if (k.startsWith("prompts_") && !k.equals(key)) e.remove(k);
        }
        e.putInt(key, prefs(c).getInt(key, 0) + 1).apply();
    }

    /** A habit whose "At HH:MM" cue is due right now (within a few minutes), scheduled and not done yet. */
    static String cueDueNow(Context c) {
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        Set<String> checked = Routine.checked(c);
        for (String h : Routine.items(c)) {
            if (checked.contains(h)) continue;
            HabitMeta.Cue cue = HabitMeta.cue(c, h);
            if (cue.type != HabitMeta.Cue.AT) continue;
            int diff = Math.abs(m - cue.minutes);
            if (Math.min(diff, 1440 - diff) <= 3) return h;
        }
        return null;
    }

    /** Remind about one habit: through the buddy if it's out, otherwise by opening the checklist on it. */
    static void cueNudge(Context c, String habit, String prefix) {
        HabitMeta.Cue cue = HabitMeta.cue(c, habit);
        String when = prefix != null ? prefix : cue.describe(c);
        if (UnlockWatcher.buddySay(c.getString(R.string.cue_say, when, habit))) return;
        MainActivity.pendingHighlight = habit;
        try {
            MainActivity.launch(c);
        } catch (RuntimeException ignored) {
        }
    }

    /** Minutes after midnight for the weak-hour nudge (15 min before your peak exit hour), or -1. */
    static int peakNudgeMinutes(Context c) {
        if (!peakNudge(c)) return -1;
        int[] hours = History.escapesByHour(History.all(c), 14);
        int total = 0, peak = 0;
        for (int h = 0; h < 24; h++) {
            total += hours[h];
            if (hours[h] > hours[peak]) peak = h;
        }
        if (total < PEAK_MIN_ATTEMPTS) return -1;
        return (peak * 60 - 15 + 1440) % 1440;
    }

    /** Sets one alarm for the next nudge (scheduled or weak-hour). Re-armed each time it fires. */
    static void scheduleNext(Context c) {
        List<Integer> times = new ArrayList<>(nudges(c));
        int peak = peakNudgeMinutes(c);
        if (peak >= 0) times.add(peak);
        // "At 07:30, I will..." cues fire too.
        for (String h : Routine.allItems(c)) {
            HabitMeta.Cue cue = HabitMeta.cue(c, h);
            if (cue.type == HabitMeta.Cue.AT) times.add(cue.minutes);
        }
        PendingIntent pi = PendingIntent.getBroadcast(c, 1,
                new Intent(c, AlarmReceiver.class).setAction(ACTION_NUDGE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (times.isEmpty()) {
            am.cancel(pi);
            return;
        }
        Calendar now = Calendar.getInstance();
        long best = Long.MAX_VALUE;
        for (int m : times) {
            Calendar t = (Calendar) now.clone();
            t.set(Calendar.HOUR_OF_DAY, m / 60);
            t.set(Calendar.MINUTE, m % 60);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (!t.after(now)) t.add(Calendar.DAY_OF_YEAR, 1);
            best = Math.min(best, t.getTimeInMillis());
        }
        Gate.setAlarm(am, best, pi);
    }
}
