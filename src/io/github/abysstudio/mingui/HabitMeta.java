package io.github.abysstudio.mingui;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-habit "when" and "who":
 * <ul>
 * <li>Cue (implementation intention): "After coffee, I will..." or "At 07:30, I will...".</li>
 * <li>Identity: each completion is a vote for being "a reader", "an athlete", ...</li>
 * </ul>
 * Also learns when you usually do each habit, to suggest cues and to order today's list.
 */
final class HabitMeta {
    /** Everyday moments to anchor a habit to, with a rough default time (minutes after midnight). */
    private static final int[] ANCHOR_TIMES = {7 * 60, 7 * 60 + 30, 7 * 60 + 45, 8 * 60, 12 * 60 + 30,
            17 * 60 + 30, 18 * 60, 19 * 60 + 30, 22 * 60 + 30};
    /** The one anchor the phone can detect, the first unlock of the day, in every language we ship. */
    private static final String[] WAKE = {"waking up", "despertarme", "despertar-me"};

    /** Everyday moments in the user's language; same order as {@link #ANCHOR_TIMES}, first one is waking up. */
    static String[] anchors(Context c) {
        return c.getResources().getStringArray(R.array.anchors);
    }

    static boolean isWake(Context c, String anchor) {
        if (anchor.equalsIgnoreCase(anchors(c)[0])) return true;
        for (String w : WAKE) if (w.equalsIgnoreCase(anchor)) return true;
        return false;
    }

    static final class Cue {
        static final int NONE = 0, AFTER = 1, AT = 2;
        int type;
        /** AFTER: the anchor text. */
        String anchor = "";
        /** AT: minutes after midnight. */
        int minutes = -1;

        String describe(Context c) {
            if (type == AFTER) {
                // Catalan elides before a vowel ("Després d'esmorzar"); other languages use the same text twice.
                boolean vowel = !anchor.isEmpty() && "aeiouàèéíïòóúüh".indexOf(Character.toLowerCase(anchor.charAt(0))) >= 0;
                return c.getString(vowel ? R.string.cue_after_vowel : R.string.cue_after, anchor);
            }
            if (type == AT) return c.getString(R.string.cue_at, Prompts.hhmm(minutes));
            return "";
        }
    }

    private HabitMeta() {}

    // ---------------------------------------------------------------- storage: one JSON map keyed by habit name

    private static JSONObject all(Context c) {
        try {
            return new JSONObject(Routine.prefs(c).getString("habit_meta", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static Cue cue(Context c, String habit) {
        Cue cue = new Cue();
        JSONObject o = all(c).optJSONObject(habit);
        if (o == null) return cue;
        String raw = o.optString("cue", "");
        if (raw.startsWith("after:")) {
            cue.type = Cue.AFTER;
            cue.anchor = raw.substring(6);
        } else if (raw.startsWith("at:")) {
            try {
                cue.minutes = Integer.parseInt(raw.substring(3));
                cue.type = Cue.AT;
            } catch (NumberFormatException ignored) {
            }
        }
        return cue;
    }

    static Cue decodeCue(String raw) {
        Cue cue = new Cue();
        if (raw == null) return cue;
        if (raw.startsWith("after:")) {
            cue.type = Cue.AFTER;
            cue.anchor = raw.substring(6);
        } else if (raw.startsWith("at:")) {
            try {
                cue.minutes = Integer.parseInt(raw.substring(3));
                cue.type = Cue.AT;
            } catch (NumberFormatException ignored) {
            }
        }
        return cue;
    }

    /** The identity you set yourself ("" = none), or null if it's on auto (suggested from the name). */
    static String explicitIdentity(Context c, String habit) {
        JSONObject o = all(c).optJSONObject(habit);
        return o != null && o.has("identity") ? o.optString("identity", "") : null;
    }

    static String encodeCue(Cue cue) {
        if (cue.type == Cue.AFTER && !cue.anchor.trim().isEmpty()) return "after:" + cue.anchor.trim();
        if (cue.type == Cue.AT && cue.minutes >= 0) return "at:" + cue.minutes;
        return "";
    }

    /** Identity for a habit: what you set, else a suggestion from its name. "" if explicitly turned off. */
    static String identity(Context c, String habit) {
        JSONObject o = all(c).optJSONObject(habit);
        if (o != null && o.has("identity")) return o.optString("identity", "");
        return suggestIdentity(c, habit);
    }

    /** Replaces all metadata: habit -> {cue, identity}. Called when the habit list is saved. */
    static void save(Context c, Map<String, String> cues, Map<String, String> identities) {
        JSONObject out = new JSONObject();
        try {
            for (String h : cues.keySet()) {
                JSONObject o = new JSONObject().put("cue", cues.get(h));
                if (identities.containsKey(h)) o.put("identity", identities.get(h));
                out.put(h, o);
            }
        } catch (JSONException ignored) {
        }
        Routine.prefs(c).edit().putString("habit_meta", out.toString()).commit();
    }

    // ---------------------------------------------------------------- identity

    /** A first guess at who this habit makes you, from keywords. */
    /** Keywords (English, Spanish, Catalan) -> identity, first match wins. */
    private static final Object[][] IDENTITY_RULES = {
            {"(study|learn|course|lesson|estudi|aprend|curs|lecci|lliçó)", R.string.id_learner},
            {"(read|book|page|leer|lectura|libro|p[aáà]gina|llegir|llibre)", R.string.id_reader},
            {"(run|jog|c[oó]rrer|trotar|footing)", R.string.id_runner},
            {"(gym|workout|lift|exercis|train|push|squat|sport|ejercicio|exercici|gimn|entren|pesas|peses"
                    + "|deporte|esport|flexi|sentadilla|esquat)", R.string.id_athlete},
            {"(walk|steps|hike|bike|cycl|swim|caminar|pasos|paseo|passeja|passos|bici|nadar|nedar|excursi)",
                    R.string.id_mover},
            {"(yoga|stretch|estira)", R.string.id_body},
            {"(water|drink|hydrat|agua|beber|hidrat|aigua|beure)", R.string.id_selfcare},
            {"(meditat|breath|mindful|calm|medita|respira)", R.string.id_calm},
            {"(journal|writ|diary|diario|diari|escrib|escriu)", R.string.id_writer},
            {"(bed|tidy|clean|dishes|laundry|organi|cama|ordenar|limpi|platos|colada|llit|endre|neteja|plats|rentadora)",
                    R.string.id_organised},
            {"(plan|todo|to-do|schedule|review|agenda|repasar|revisar)", R.string.id_planner},
            {"(sleep|bedtime|lights out|dormir|acostar|descans)", R.string.id_rests},
            {"(phone|screen|social|m[oó]vil|m[oò]bil|pantalla|redes|xarxes)", R.string.id_phone},
            {"(cook|meal|veg|fruit|salad|eat|cocina|comida|verdura|fruta|ensalada|cuina|menjar|fruita|amanida)",
                    R.string.id_eater},
            {"(spanish|french|german|language|duolingo|vocab|ingl[eé]s|idioma|franc[eéè]s|alem[aá]n|angl[eè]s"
                    + "|alemany|catal[aà])", R.string.id_languages},
            {"(guitar|piano|music|sing|instrument|m[uú]sica|cantar)", R.string.id_musician},
            {"(code|coding|program)", R.string.id_builder},
            {"(draw|paint|sketch|art|dibuj|dibuix|pint)", R.string.id_artist},
            {"(pray|gratitude|thank|rezar|resar|gratitud|agradec|agra[iï])", R.string.id_grateful},
            {"(save|budget|money|spend|ahorr|presupuesto|dinero|gasto|estalvi|pressupost|diners)", R.string.id_money},
            {"(call|text|friend|family|llamar|amig|familia|fam[ií]lia|trucar|amic)", R.string.id_people},
    };

    /** A first guess at who this habit makes you, from keywords, in the user's language. */
    static String suggestIdentity(Context c, String habit) {
        String h = habit.toLowerCase(Locale.ROOT);
        for (Object[] rule : IDENTITY_RULES) {
            if (java.util.regex.Pattern.compile((String) rule[0]).matcher(h).find()) return c.getString((Integer) rule[1]);
        }
        return c.getString(R.string.id_default);
    }

    /** "a reader" -> "reader", "una persona lectora" -> "persona lectora", for compact labels. */
    static String shortIdentity(String identity) {
        String s = identity.trim();
        for (String a : new String[]{"a ", "an ", "una ", "un "}) {
            if (s.toLowerCase(Locale.ROOT).startsWith(a)) return s.substring(a.length());
        }
        return s;
    }

    /** Thresholds for the "strengths" names (Emerging, Building, Strong, Core). */
    static final int[] STRENGTH_AT = {0, 10, 30, 60};

    static int strength(int votes) {
        int s = 0;
        for (int i = 0; i < STRENGTH_AT.length; i++) if (votes >= STRENGTH_AT[i]) s = i;
        return s;
    }

    /**
     * Identity -> {votes (days you did any habit with it), days it was scheduled in the last 30, done in last 30}.
     * Uses each habit's current identity, so renaming an identity carries its history.
     */
    static Map<String, int[]> identityVotes(Context c, TreeMap<String, History.Day> log) {
        Map<String, int[]> out = new LinkedHashMap<>();
        Map<String, String> cache = new HashMap<>();
        String since = History.daysAgo(29);
        for (History.Day d : log.values()) {
            Map<String, boolean[]> today = new HashMap<>(); // identity -> {scheduled, done}
            for (String habit : d.items) {
                String id = cache.get(habit);
                if (id == null) cache.put(habit, id = identity(c, habit));
                if (id.isEmpty()) continue;
                boolean[] f = today.get(id);
                if (f == null) today.put(id, f = new boolean[2]);
                f[0] = true;
                if (d.done.contains(habit)) f[1] = true;
            }
            for (Map.Entry<String, boolean[]> e : today.entrySet()) {
                int[] v = out.get(e.getKey());
                if (v == null) out.put(e.getKey(), v = new int[3]);
                if (e.getValue()[1]) v[0]++;
                if (d.date.compareTo(since) >= 0) {
                    v[1]++;
                    if (e.getValue()[1]) v[2]++;
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- timing

    /** Minutes after midnight you usually tick this habit (median of the last 30 days), or -1. */
    static int usualMinutes(TreeMap<String, History.Day> log, String habit) {
        List<Integer> mins = new ArrayList<>();
        String since = History.daysAgo(30);
        Calendar cal = Calendar.getInstance();
        int start = Routine.DAY_START_HOUR * 60;
        for (History.Day d : log.tailMap(since).values()) {
            Long t = d.checkTimes.get(habit);
            if (t == null) continue;
            cal.setTimeInMillis(t);
            int m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            mins.add((m - start + 1440) % 1440); // relative to day start, so 1am sorts late
        }
        if (mins.size() < 3) return -1;
        Collections.sort(mins);
        int med = mins.get(mins.size() / 2);
        // Round to 5 minutes: a suggestion, not a measurement.
        return ((med + start) % 1440 + 2) / 5 * 5 % 1440;
    }

    /** When in the day this habit "belongs", for ordering: cue time, else anchor's typical time, else usual time. */
    static int sortMinutes(Context c, TreeMap<String, History.Day> log, String habit) {
        Cue cue = cue(c, habit);
        if (cue.type == Cue.AT) return cue.minutes;
        int usual = usualMinutes(log, habit);
        if (usual >= 0) return usual;
        if (cue.type == Cue.AFTER) {
            String[] anchors = anchors(c);
            for (int i = 0; i < anchors.length && i < ANCHOR_TIMES.length; i++) {
                if (anchors[i].equalsIgnoreCase(cue.anchor)) return ANCHOR_TIMES[i];
            }
        }
        return -1;
    }

    /** 0 = smart (by cue / usual time, your order breaks ties), 1 = exactly your order. */
    static int orderMode(Context c) {
        return Routine.prefs(c).getInt("order_mode", 0);
    }

    static void setOrderMode(Context c, int mode) {
        Routine.prefs(c).edit().putInt("order_mode", mode).apply();
    }

    /** Today's habits in the order you're likely to do them; habits with no timing keep their place at the end. */
    static List<String> ordered(Context c, List<String> items) {
        if (orderMode(c) == 1) return new ArrayList<>(items);
        TreeMap<String, History.Day> log = History.all(c);
        final Map<String, Integer> key = new HashMap<>();
        int start = Routine.DAY_START_HOUR * 60;
        for (String h : items) {
            int m = sortMinutes(c, log, h);
            key.put(h, m < 0 ? Integer.MAX_VALUE : (m - start + 1440) % 1440);
        }
        List<String> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> Integer.compare(key.get(a), key.get(b))); // stable: ties keep order
        return out;
    }
}
