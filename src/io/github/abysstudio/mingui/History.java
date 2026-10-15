package io.github.abysstudio.mingui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Per-day log: "day_yyyy-MM-dd" -> {items, done, completedAt, breaks, breakTimes, leaves}. Today's entry is
 * rewritten on every change, so each past day holds its end-of-day state. Days with no entry after tracking
 * began count as missed.
 */
final class History {
    /** A day with at least this share of habits done (but not all) is a partial success: it keeps a streak alive. */
    static final float PARTIAL = 0.5f;

    static final class Day {
        final String date;
        final List<String> items = new ArrayList<>();
        final Set<String> done = new HashSet<>();
        final List<Long> breakTimes = new ArrayList<>();
        /** Times you left the checklist while focus lock was on. */
        final List<Long> leaves = new ArrayList<>();
        long completedAt;
        int breaks;
        /** Nothing was scheduled that day. */
        boolean rest;
        /** A streak freeze covered this (missed) day. */
        boolean frozen;
        /** When each habit was ticked (for learning your usual times). */
        final Map<String, Long> checkTimes = new java.util.HashMap<>();

        Day(String date) {
            this.date = date;
        }

        boolean complete() {
            return !items.isEmpty() && done.containsAll(items);
        }

        boolean partial() {
            return !complete() && fraction() >= PARTIAL;
        }

        /** Doesn't add to a streak but doesn't break it either. */
        boolean keepsStreak() {
            return partial() || rest || frozen;
        }

        /** Share of habits done, 0..1. */
        float fraction() {
            if (items.isEmpty()) return 0;
            int n = 0;
            for (String i : items) if (done.contains(i)) n++;
            return (float) n / items.size();
        }
    }

    /** A day in a stats window: {@code day} is null for an untouched (missed) day. */
    static final class Slot {
        final String date;
        final Day day;

        Slot(String date, Day day) {
            this.date = date;
            this.day = day;
        }

        boolean complete() {
            return day != null && day.complete();
        }

        boolean partial() {
            return day != null && day.partial();
        }

        boolean frozen() {
            return day != null && day.frozen;
        }
    }

    private History() {}

    private static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    /** Snapshot today's state. Called after every change to the checklist, the habit list or breaks. */
    static void logToday(Context c) {
        write(c, null, 0);
    }

    /** Append an event time ("leaves" or "breakTimes") to today's entry. */
    static void addEvent(Context c, String kind, long time) {
        write(c, kind, time);
    }

    private static synchronized void write(Context c, String eventKind, long eventTime) {
        String today = Routine.today();
        Day old = all(c).get(today);
        List<String> items = Routine.items(c);
        Set<String> checked = Routine.checked(c);
        JSONObject o = new JSONObject();
        try {
            JSONArray its = new JSONArray(), done = new JSONArray();
            for (String i : items) {
                its.put(i);
                if (checked.contains(i)) done.put(i);
            }
            boolean complete = !items.isEmpty() && checked.containsAll(items);
            // Finish time: kept while the day stays complete, cleared by an undo, re-stamped when finished again.
            long completedAt = !complete ? 0
                    : old != null && old.completedAt > 0 ? old.completedAt : System.currentTimeMillis();
            boolean rest = items.isEmpty() && !Routine.allItems(c).isEmpty();
            // Keep the first tick time of each habit that's still checked; stamp newly checked ones.
            JSONObject times = new JSONObject();
            for (String i : items) {
                if (!checked.contains(i)) continue;
                Long t = old == null ? null : old.checkTimes.get(i);
                times.put(i, t != null ? t : System.currentTimeMillis());
            }
            JSONArray breakTimes = times(old == null ? null : old.breakTimes);
            JSONArray leaves = times(old == null ? null : old.leaves);
            if ("breakTimes".equals(eventKind)) breakTimes.put(eventTime);
            if ("leaves".equals(eventKind)) leaves.put(eventTime);
            o.put("items", its).put("done", done).put("completedAt", completedAt)
                    .put("breaks", Routine.MAX_BREAKS - Routine.breaksLeft(c))
                    .put("breakTimes", breakTimes).put("leaves", leaves)
                    .put("rest", rest).put("frozen", old != null && old.frozen).put("times", times);
        } catch (JSONException e) {
            return;
        }
        prefs(c).edit().putString("day_" + today, o.toString()).commit();
    }

    private static JSONArray times(List<Long> list) {
        JSONArray a = new JSONArray();
        if (list != null) for (long t : list) a.put(t);
        return a;
    }

    /** Every logged day, oldest first. */
    static TreeMap<String, Day> all(Context c) {
        TreeMap<String, Day> out = new TreeMap<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (!e.getKey().startsWith("day_") || !(e.getValue() instanceof String)) continue;
            String date = e.getKey().substring(4);
            try {
                JSONObject o = new JSONObject((String) e.getValue());
                Day d = new Day(date);
                JSONArray its = o.getJSONArray("items"), done = o.getJSONArray("done");
                for (int i = 0; i < its.length(); i++) d.items.add(its.getString(i));
                for (int i = 0; i < done.length(); i++) d.done.add(done.getString(i));
                d.completedAt = o.optLong("completedAt");
                d.breaks = o.optInt("breaks");
                d.rest = o.optBoolean("rest");
                d.frozen = o.optBoolean("frozen");
                JSONObject tm = o.optJSONObject("times");
                if (tm != null) {
                    JSONArray tn = tm.names();
                    if (tn != null) for (int i = 0; i < tn.length(); i++) d.checkTimes.put(tn.getString(i), tm.getLong(tn.getString(i)));
                }
                JSONArray bt = o.optJSONArray("breakTimes"), lv = o.optJSONArray("leaves");
                if (bt != null) for (int i = 0; i < bt.length(); i++) d.breakTimes.add(bt.getLong(i));
                if (lv != null) for (int i = 0; i < lv.length(); i++) d.leaves.add(lv.getLong(i));
                out.put(date, d);
            } catch (JSONException ignored) {
                // Skip a corrupt entry rather than losing all stats.
            }
        }
        return out;
    }

    /** Date string {@code daysAgo} routine-days before today. */
    static String daysAgo(int daysAgo) {
        Calendar cal = Routine.dayCalendar();
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.getTime());
    }

    /**
     * The last {@code n} days that count for stats, oldest first: only days since tracking began,
     * and today only once it's complete (an unfinished today isn't a miss yet).
     */
    static List<Slot> window(TreeMap<String, Day> log, int n) {
        List<Slot> out = new ArrayList<>();
        if (log.isEmpty()) return out;
        String first = log.firstKey();
        for (int i = n - 1; i >= 0; i--) {
            String date = daysAgo(i);
            if (date.compareTo(first) < 0) continue;
            Day d = log.get(date);
            if (i == 0 && (d == null || !d.complete())) continue;
            if (d != null && d.rest) continue; // nothing scheduled: neither a win nor a miss
            out.add(new Slot(date, d));
        }
        return out;
    }

    /**
     * Streak ending today (or yesterday, if today isn't finished yet). Complete days add one;
     * partial days keep the streak alive without adding; a missed day ends it.
     */
    static int currentStreak(TreeMap<String, Day> log) {
        if (log.isEmpty()) return 0;
        String first = log.firstKey();
        int streak = 0;
        Day today = log.get(daysAgo(0));
        if (today != null && today.complete()) streak++;
        for (int i = 1; ; i++) {
            String date = daysAgo(i);
            if (date.compareTo(first) < 0) break;
            Day d = log.get(date);
            if (d == null || (!d.complete() && !d.keepsStreak())) break;
            if (d.complete()) streak++;
        }
        return streak;
    }

    /** Longest streak in the log, with the same partial-day rule as {@link #currentStreak}. */
    static int bestStreak(TreeMap<String, Day> log) {
        if (log.isEmpty()) return 0;
        String first = log.firstKey();
        int best = 0, run = 0;
        // Walk every day from the first log entry to today, so gaps count as misses.
        int span = 0;
        while (daysAgo(span).compareTo(first) >= 0) span++;
        for (int i = span - 1; i >= 0; i--) {
            Day d = log.get(daysAgo(i));
            if (d != null && d.complete()) run++;
            else if (d != null && d.keepsStreak()) { /* keeps the run alive */ }
            else if (i != 0) run = 0; // an unfinished today doesn't break anything yet
            best = Math.max(best, run);
        }
        return best;
    }

    /**
     * Once a day: if the days just before today were missed and there are enough banked freezes to
     * cover all of them, use the freezes so the streak survives. Returns how many were used.
     */
    static int applyFreezes(Context c) {
        SharedPreferences rp = Routine.prefs(c);
        String today = Routine.today();
        if (today.equals(rp.getString("freeze_checked", ""))) return 0;
        rp.edit().putString("freeze_checked", today).apply();
        int free = Routine.freezes(c);
        TreeMap<String, Day> log = all(c);
        if (free == 0 || log.isEmpty()) return 0;
        String first = log.firstKey();
        List<String> missed = new ArrayList<>();
        Day anchor = null;
        for (int i = 1; ; i++) {
            String date = daysAgo(i);
            if (date.compareTo(first) < 0) return 0; // gap reaches back to before tracking: no streak to save
            Day d = log.get(date);
            if (d != null && (d.complete() || d.keepsStreak())) {
                anchor = d;
                break;
            }
            missed.add(date);
            if (missed.size() > free) return 0; // can't cover the whole gap
        }
        if (missed.isEmpty() || anchor == null) return 0;
        for (String date : missed) markFrozen(c, date);
        Routine.setFreezes(c, free - missed.size());
        rp.edit().putString("freeze_notice", today + ":" + missed.size()).apply();
        return missed.size();
    }

    private static void markFrozen(Context c, String date) {
        String key = "day_" + date;
        try {
            String raw = prefs(c).getString(key, null);
            JSONObject o = raw != null ? new JSONObject(raw)
                    : new JSONObject().put("items", new JSONArray()).put("done", new JSONArray());
            o.put("frozen", true);
            prefs(c).edit().putString(key, o.toString()).commit();
        } catch (JSONException ignored) {
        }
    }

    /** How many past days can be corrected ("I did it but forgot to tick it"). */
    static final int EDITABLE_DAYS = 2;

    /** True for yesterday and the day before: recent enough to remember, too old to game. */
    static boolean editable(String date) {
        return date.compareTo(daysAgo(EDITABLE_DAYS)) >= 0 && date.compareTo(daysAgo(0)) < 0;
    }

    /** The habits that were due on a past day: from its log entry, or worked out from the schedule. */
    static List<String> itemsFor(Context c, String date) {
        Day d = all(c).get(date);
        if (d != null && !d.items.isEmpty()) return new ArrayList<>(d.items);
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date));
            cal.set(Calendar.HOUR_OF_DAY, 12);
            return Routine.itemsOn(c, cal);
        } catch (java.text.ParseException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Corrects a recent past day. If that turns a frozen (missed) day into a real one, the streak
     * freeze it used goes back in the bank.
     */
    static synchronized void setPastDay(Context c, String date, List<String> items, Set<String> done) {
        if (!editable(date)) return;
        String key = "day_" + date;
        try {
            String raw = prefs(c).getString(key, null);
            JSONObject o = raw != null ? new JSONObject(raw) : new JSONObject();
            JSONArray its = new JSONArray(), dn = new JSONArray();
            for (String i : items) {
                its.put(i);
                if (done.contains(i)) dn.put(i);
            }
            o.put("items", its).put("done", dn).put("edited", true);
            boolean complete = !items.isEmpty() && done.containsAll(items);
            if (!complete) o.put("completedAt", 0);
            boolean wasFrozen = o.optBoolean("frozen");
            Day probe = new Day(date);
            probe.items.addAll(items);
            probe.done.addAll(done);
            if (wasFrozen && (probe.complete() || probe.partial())) {
                o.put("frozen", false);
                Routine.setFreezes(c, Routine.freezes(c) + 1);
            }
            prefs(c).edit().putString(key, o.toString()).commit();
        } catch (JSONException ignored) {
        }
    }

    /** Every logged day's raw JSON, for backups. */
    static Map<String, ?> raw(Context c) {
        return prefs(c).getAll();
    }

    static SharedPreferences store(Context c) {
        return prefs(c);
    }

    /** Per habit over the window: {done, tracked} days, in order of first appearance. */
    static Map<String, int[]> habitRates(List<Slot> window) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Slot s : window) {
            if (s.day == null) continue;
            for (String item : s.day.items) {
                int[] r = out.get(item);
                if (r == null) out.put(item, r = new int[2]);
                r[1]++;
                if (s.day.done.contains(item)) r[0]++;
            }
        }
        return out;
    }

    /**
     * Average finish time as minutes after midnight, or -1 if nothing finished in the window.
     * Averaged relative to the day start so a 01:00 finish counts as late, not early.
     */
    static int avgFinishMinutes(List<Slot> window) {
        long sum = 0;
        int n = 0;
        int start = Routine.DAY_START_HOUR * 60;
        Calendar cal = Calendar.getInstance();
        for (Slot s : window) {
            if (!s.complete() || s.day.completedAt <= 0) continue;
            cal.setTimeInMillis(s.day.completedAt);
            int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            sum += (m - start + 1440) % 1440;
            n++;
        }
        return n == 0 ? -1 : (int) ((sum / n + start) % 1440);
    }

    static int totalBreaks(List<Slot> window) {
        int n = 0;
        for (Slot s : window) if (s.day != null) n += s.day.breaks;
        return n;
    }

    /** Exit attempts (leaving the checklist + breaks) per clock hour 0..23 over the window. */
    static int[] escapesByHour(TreeMap<String, Day> log, int days) {
        int[] hours = new int[24];
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < days; i++) {
            Day d = log.get(daysAgo(i));
            if (d == null) continue;
            List<Long> all = new ArrayList<>(d.leaves);
            all.addAll(d.breakTimes);
            for (long t : all) {
                cal.setTimeInMillis(t);
                hours[cal.get(Calendar.HOUR_OF_DAY)]++;
            }
        }
        return hours;
    }
}
