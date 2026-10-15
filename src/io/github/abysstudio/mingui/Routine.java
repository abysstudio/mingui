package io.github.abysstudio.mingui;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Routine items and today's check-offs, stored in device-protected prefs so they work right after boot. */
final class Routine {
    /** First four starter habits, in the user's language. */
    static String defaultItems(Context c) {
        String[] s = c.getResources().getStringArray(R.array.starter_habits);
        return s[0] + "\n" + s[1] + "\n" + s[2] + "\n" + s[3];
    }

    static final Set<String> DEFAULT_BLOCKED = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube"));

    private Routine() {}

    static boolean openOnUnlock(Context c) {
        return prefs(c).getBoolean("open_on_unlock", true) && !pausedToday(c);
    }

    static void setOpenOnUnlock(Context c, boolean on) {
        prefs(c).edit().putBoolean("open_on_unlock", on).apply();
    }

    static final int MAX_BREAKS = 3;
    static final long BREAK_MS = 5 * 60_000;

    static boolean buddy(Context c) {
        return prefs(c).getBoolean("buddy", true);
    }

    static void setBuddy(Context c, boolean on) {
        prefs(c).edit().putBoolean("buddy", on).apply();
    }

    static boolean focusLock(Context c) {
        // Opt-in in both builds (the Play build also asks for explicit consent).
        return prefs(c).getBoolean("focus_lock", false) && !pausedToday(c);
    }

    /**
     * Emergency exit (Play build): turns off focus lock, unlock prompts and app blocking for the rest of
     * today. Always available, because the user must be able to get their phone back.
     */
    static boolean pausedToday(Context c) {
        return !BuildFlags.ROOT && today().equals(prefs(c).getString("paused_day", ""));
    }

    static void pauseToday(Context c) {
        prefs(c).edit().putString("paused_day", today()).commit();
        History.addEvent(c, "leaves", System.currentTimeMillis());
    }

    static void unpause(Context c) {
        prefs(c).edit().remove("paused_day").commit();
    }

    static void setFocusLock(Context c, boolean on) {
        prefs(c).edit().putBoolean("focus_lock", on).apply();
    }

    static long breakUntil(Context c) {
        return prefs(c).getLong("break_until", 0);
    }

    static boolean onBreak(Context c) {
        return breakUntil(c) > System.currentTimeMillis();
    }

    static int breaksLeft(Context c) {
        return Math.max(0, MAX_BREAKS - prefs(c).getInt("breaks_" + today(), 0));
    }

    /** Starts a break if any are left today. */
    static boolean takeBreak(Context c) {
        int left = breaksLeft(c);
        if (left <= 0) return false;
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : prefs(c).getAll().keySet()) {
            if (k.startsWith("breaks_")) e.remove(k);
        }
        e.putInt("breaks_" + today(), MAX_BREAKS - left + 1)
                .putLong("break_until", System.currentTimeMillis() + BREAK_MS)
                .commit();
        History.addEvent(c, "breakTimes", System.currentTimeMillis());
        return true;
    }

    // ---- timer-verified habits: a timed habit can only be checked after its timer has run out.

    static final int[] TIMER_CHOICES = {0, 5, 10, 15, 20, 30, 45, 60};

    /** Habit name -> timer minutes, for habits that have a timer. */
    static java.util.Map<String, Integer> timers(Context c) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (String e : prefs(c).getStringSet(key("timers"), new HashSet<String>())) {
            int tab = e.indexOf('\t');
            if (tab <= 0) continue;
            try {
                out.put(e.substring(tab + 1), Integer.parseInt(e.substring(0, tab)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void setTimers(Context c, java.util.Map<String, Integer> timers) {
        Set<String> s = new HashSet<>();
        for (java.util.Map.Entry<String, Integer> e : timers.entrySet()) {
            if (e.getValue() > 0) s.add(e.getValue() + "\t" + e.getKey());
        }
        prefs(c).edit().putStringSet("timers", s).commit();
    }

    static int timerMinutes(Context c, String item) {
        Integer m = timers(c).get(item);
        return m == null ? 0 : m;
    }

    private static String timerKey(String item) {
        return "tstart_" + today() + "_" + item;
    }

    /** When today's timer for this habit was started, or 0. */
    static long timerStart(Context c, String item) {
        return prefs(c).getLong(timerKey(item), 0);
    }

    static void startTimer(Context c, String item) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : prefs(c).getAll().keySet()) {
            if (k.startsWith("tstart_") && !k.startsWith("tstart_" + today() + "_")) e.remove(k);
        }
        e.putLong(timerKey(item), System.currentTimeMillis()).commit();
    }

    static void resetTimer(Context c, String item) {
        prefs(c).edit().remove(timerKey(item)).commit();
    }

    /** Milliseconds left on today's timer: -1 if not started, 0 if finished. */
    static long timerLeft(Context c, String item) {
        long start = timerStart(c, item);
        if (start == 0) return -1;
        return Math.max(0, start + timerMinutes(c, item) * 60_000L - System.currentTimeMillis());
    }

    /** Apps you may use during focus lock (work, essentials). */
    static Set<String> allowedApps(Context c) {
        return new HashSet<>(prefs(c).getStringSet("allowed_apps", new HashSet<String>()));
    }

    static void setAllowedApps(Context c, Set<String> pkgs) {
        prefs(c).edit().putStringSet("allowed_apps", new HashSet<>(pkgs)).apply();
    }

    static Set<String> blockedApps(Context c) {
        return new HashSet<>(prefs(c).getStringSet("blocked_apps", DEFAULT_BLOCKED));
    }

    static void setBlockedApps(Context c, Set<String> pkgs) {
        prefs(c).edit().putStringSet("blocked_apps", new HashSet<>(pkgs)).apply();
    }

    static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.createDeviceProtectedStorageContext().getSharedPreferences("routine", Context.MODE_PRIVATE);
        applyPending(p);
        return p;
    }

    // ---- edits saved for tomorrow: an edit that would make an unfinished day easier (removing an
    // unticked habit, shortening its timer, taking today off its schedule) can't unlock apps early,
    // so the edited routine is kept under "pending_" keys and becomes the routine when its day starts.

    /** The prefs that make up the routine itself; everything else (ticks, history, settings) applies right away. */
    private static final String[] ROUTINE_KEYS = {"items", "timers", "days", "since", "habit_meta"};
    private static final String PENDING = "pending_", PENDING_FROM = "pending_from";
    /** Set while the editor loads its drafts, so it shows (and keeps editing) tomorrow's routine. */
    private static final ThreadLocal<Boolean> READ_PENDING = new ThreadLocal<>();

    static String key(String k) {
        return Boolean.TRUE.equals(READ_PENDING.get()) ? PENDING + k : k;
    }

    static boolean hasPending(Context c) {
        return prefs(c).contains(PENDING_FROM);
    }

    /** Runs {@code r} with the routine getters reading tomorrow's pending routine, if there is one. */
    static void readingPending(Context c, Runnable r) {
        boolean on = hasPending(c);
        READ_PENDING.set(on);
        try {
            r.run();
        } finally {
            READ_PENDING.remove();
        }
    }

    static java.util.Map<String, Object> snapshot(Context c) {
        java.util.Map<String, ?> all = prefs(c).getAll();
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        for (String k : ROUTINE_KEYS) out.put(k, all.get(k));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void write(SharedPreferences.Editor e, String prefix, java.util.Map<String, ?> values) {
        for (String k : ROUTINE_KEYS) {
            Object v = values.get(k);
            if (v == null) e.remove(prefix + k);
            else if (v instanceof String) e.putString(prefix + k, (String) v);
            else e.putStringSet(prefix + k, new HashSet<>((Set<String>) v));
        }
    }

    /** Moves the routine just saved to tomorrow, and puts {@code before} back as today's routine. */
    static void stageForTomorrow(Context c, java.util.Map<String, Object> before) {
        SharedPreferences p = prefs(c);
        java.util.Map<String, ?> edited = snapshot(c);
        SharedPreferences.Editor e = p.edit();
        write(e, PENDING, edited);
        write(e, "", before);
        e.putString(PENDING_FROM, tomorrow()).commit();
    }

    /** Drops tomorrow's routine (a later edit replaced it with one that applies now). */
    static void clearPending(Context c) {
        SharedPreferences.Editor e = prefs(c).edit().remove(PENDING_FROM);
        for (String k : ROUTINE_KEYS) e.remove(PENDING + k);
        e.commit();
    }

    private static void applyPending(SharedPreferences p) {
        String from = p.getString(PENDING_FROM, null);
        if (from == null || from.compareTo(today()) > 0) return;
        java.util.Map<String, ?> all = p.getAll();
        java.util.Map<String, Object> pending = new java.util.HashMap<>();
        for (String k : ROUTINE_KEYS) pending.put(k, all.get(PENDING + k));
        SharedPreferences.Editor e = p.edit();
        write(e, "", pending);
        e.remove(PENDING_FROM);
        for (String k : ROUTINE_KEYS) e.remove(PENDING + k);
        e.commit();
    }

    /** The routine day starts at this hour, so late nights still count as the previous day. */
    static final int DAY_START_HOUR = 4;

    /** Now, shifted back by DAY_START_HOUR: its date is the current routine day. */
    static Calendar dayCalendar() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.HOUR_OF_DAY, -DAY_START_HOUR);
        return cal;
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dayCalendar().getTime());
    }

    static boolean isConfigured(Context c) {
        return prefs(c).contains("items");
    }

    /** Every habit in the routine, whatever day it's scheduled for. */
    static List<String> allItems(Context c) {
        List<String> out = new ArrayList<>();
        for (String s : prefs(c).getString(key("items"), defaultItems(c)).split("\n")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    /** Habits scheduled for today (the current routine day). */
    static List<String> items(Context c) {
        return itemsOn(c, dayCalendar());
    }

    /** Habits scheduled on the routine day of {@code cal} (by weekday, and only once they've started). */
    static List<String> itemsOn(Context c, Calendar cal) {
        List<String> out = new ArrayList<>();
        int bit = dayBit(cal);
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        java.util.Map<String, Integer> days = days(c);
        java.util.Map<String, String> since = startDates(c);
        for (String s : allItems(c)) {
            Integer mask = days.get(s);
            String from = since.get(s);
            if ((mask == null || (mask & bit) != 0) && (from == null || from.compareTo(date) <= 0)) out.add(s);
        }
        return out;
    }

    // ---- per-habit schedule: a weekday bitmask, Monday = bit 0 ... Sunday = bit 6.

    static final int EVERY_DAY = 0x7F;
    /** Monday first, in the user's language. */
    static String[] dayLetters(Context c) {
        return c.getResources().getStringArray(R.array.day_letters);
    }

    static int dayBit(Calendar cal) {
        return 1 << ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7);
    }

    /** Habit name -> weekday mask, only for habits not scheduled every day. */
    static java.util.Map<String, Integer> days(Context c) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (String e : prefs(c).getStringSet(key("days"), new HashSet<String>())) {
            int tab = e.indexOf('\t');
            if (tab <= 0) continue;
            try {
                out.put(e.substring(tab + 1), Integer.parseInt(e.substring(0, tab)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void setDays(Context c, java.util.Map<String, Integer> days) {
        Set<String> s = new HashSet<>();
        for (java.util.Map.Entry<String, Integer> e : days.entrySet()) {
            int m = e.getValue() & EVERY_DAY;
            if (m != EVERY_DAY && m != 0) s.add(m + "\t" + e.getKey());
        }
        prefs(c).edit().putStringSet("days", s).commit();
    }

    static int dayMask(Context c, String item) {
        Integer m = days(c).get(item);
        return m == null ? EVERY_DAY : m;
    }

    static String describeDays(Context c, int mask) {
        mask &= EVERY_DAY;
        String[] presets = c.getResources().getStringArray(R.array.day_presets);
        if (mask == EVERY_DAY || mask == 0) return presets[0];
        if (mask == 0x1F) return presets[1];
        if (mask == 0x60) return presets[2];
        String[] names = c.getResources().getStringArray(R.array.day_short);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(names[i]);
        }
        return sb.toString();
    }

    // ---- streak freezes: earned every 7 streak days, auto-used to cover missed days.

    static final int MAX_FREEZES = 2;

    static int freezes(Context c) {
        return prefs(c).getInt("freezes", 0);
    }

    static void setFreezes(Context c, int n) {
        prefs(c).edit().putInt("freezes", Math.max(0, Math.min(MAX_FREEZES, n))).apply();
    }

    static void setItems(Context c, String text) {
        prefs(c).edit().putString("items", text).commit();
        History.logToday(c);
        RoutineWidget.updateAll(c);
    }

    static Set<String> checked(Context c) {
        return new HashSet<>(prefs(c).getStringSet("done_" + today(), new HashSet<String>()));
    }

    static void setChecked(Context c, String item, boolean on) {
        Set<String> s = checked(c);
        if (on) s.add(item); else s.remove(item);
        // Only keep today's key; older days are dropped.
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : prefs(c).getAll().keySet()) {
            if (k.startsWith("done_")) e.remove(k);
        }
        e.putStringSet("done_" + today(), s).commit();
        History.logToday(c);
        RoutineWidget.updateAll(c);
    }

    /**
     * Current streak. Always worked out from the day log (the single source of truth), so undoing a
     * habit, back-filling a forgotten day or editing the routine can never leave it out of date.
     */
    static int streak(Context c) {
        return History.currentStreak(History.all(c));
    }

    // ---- start dates: a habit added after today's routine was finished starts tomorrow,
    // so editing never "un-finishes" a day you already completed.

    static java.util.Map<String, String> startDates(Context c) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String e : prefs(c).getStringSet(key("since"), new HashSet<String>())) {
            int tab = e.indexOf('\t');
            if (tab > 0) out.put(e.substring(tab + 1), e.substring(0, tab));
        }
        return out;
    }

    static void setStartDates(Context c, java.util.Map<String, String> since) {
        Set<String> s = new HashSet<>();
        String today = today();
        for (java.util.Map.Entry<String, String> e : since.entrySet()) {
            // Only future dates matter; past ones are dropped so the set stays small.
            if (e.getValue().compareTo(today) > 0) s.add(e.getValue() + "\t" + e.getKey());
        }
        prefs(c).edit().putStringSet("since", s).commit();
    }

    static String tomorrow() {
        Calendar cal = dayCalendar();
        cal.add(Calendar.DAY_OF_YEAR, 1);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    /** Keeps today's ticks and timers with a habit when it's renamed. */
    static void renameToday(Context c, java.util.Map<String, String> oldToNew) {
        if (oldToNew.isEmpty()) return;
        SharedPreferences p = prefs(c);
        Set<String> done = checked(c), moved = new HashSet<>();
        for (String d : done) moved.add(oldToNew.containsKey(d) ? oldToNew.get(d) : d);
        SharedPreferences.Editor e = p.edit().putStringSet("done_" + today(), moved);
        for (java.util.Map.Entry<String, String> r : oldToNew.entrySet()) {
            String from = "tstart_" + today() + "_" + r.getKey();
            if (p.contains(from)) {
                e.putLong("tstart_" + today() + "_" + r.getValue(), p.getLong(from, 0)).remove(from);
            }
        }
        e.commit();
    }

    /** Nothing scheduled today (but a routine exists): counts as done, keeps the streak. */
    static boolean isRestDay(Context c) {
        return items(c).isEmpty() && !allItems(c).isEmpty();
    }

    static boolean isDoneToday(Context c) {
        List<String> items = items(c);
        if (items.isEmpty()) return isRestDay(c);
        return checked(c).containsAll(items);
    }
}
