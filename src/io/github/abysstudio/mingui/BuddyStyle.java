package io.github.abysstudio.mingui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/** The buddy's personalisation: name, colour, accessory, size, speed and chattiness. */
final class BuddyStyle {

    /** 0 = mood colours (grey -> violet -> green). Others fade from grey toward the colour as mood rises. */
    static final int[] COLORS = {0, MainActivity.ACCENT, MainActivity.GOOD, 0xFFFF8A5B, 0xFF3BA8FF, 0xFFFF6FB5,
            0xFFFFC53D};

    static final int NONE = 0;
    /** The accessory order before they became data, for migrating old saved choices. */
    private static final String[] LEGACY_IDS = {"none", "glasses", "bow", "party_hat", "headphones", "crown"};

    static final float[] SIZES = {0.8f, 1f, 1.25f};
    static final float[] SPEEDS = {0.6f, 1f, 1.6f};
    static final int CHAT_LEVELS = 3;

    private BuddyStyle() {}

    // Display names, in the user's language; same order as the arrays above.
    static String[] colorNames(Context c) {
        return c.getResources().getStringArray(R.array.color_names);
    }

    /** Accessories come from {@link Accessories}: names, unlock streaks and drawings are data. */
    static String[] accessoryNames(Context c) {
        List<Accessories.Def> all = Accessories.all(c);
        String[] n = new String[all.size()];
        n[0] = c.getString(R.string.accessory_none);
        for (int i = 1; i < n.length; i++) n[i] = all.get(i).name(c);
        return n;
    }

    static int accessoryCount(Context c) {
        return Accessories.all(c).size();
    }

    /** Best streak needed to unlock accessory {@code i}: they're streak rewards. */
    static int unlockStreak(Context c, int i) {
        return Accessories.all(c).get(i).streak;
    }

    static String[] sizeNames(Context c) {
        return c.getResources().getStringArray(R.array.size_names);
    }

    static String[] speedNames(Context c) {
        return c.getResources().getStringArray(R.array.speed_names);
    }

    static String[] chatNames(Context c) {
        return c.getResources().getStringArray(R.array.chat_names);
    }

    private static SharedPreferences prefs(Context c) {
        return Routine.prefs(c);
    }

    static String name(Context c) {
        String n = prefs(c).getString("buddy_name", "Pip").trim();
        return n.isEmpty() ? "Pip" : n;
    }

    static void setName(Context c, String name) {
        prefs(c).edit().putString("buddy_name", name.trim()).apply();
    }

    static int colorIndex(Context c) {
        return clamp(prefs(c).getInt("buddy_color", 0), COLORS.length);
    }

    /** Index of the accessory being worn (0 = none). Saved by id, so new accessories never shuffle it. */
    static int accessory(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("buddy_accessory_id", null);
        if (id == null) {
            int old = p.getInt("buddy_accessory", NONE);
            id = old > 0 && old < LEGACY_IDS.length ? LEGACY_IDS[old] : "none";
        }
        int a = Accessories.indexOf(c, id);
        return unlocked(c, a) ? a : NONE;
    }

    static Accessories.Def accessoryDef(Context c) {
        int a = accessory(c);
        return a == NONE ? null : Accessories.all(c).get(a);
    }

    static void setAccessory(Context c, int index) {
        prefs(c).edit().putString("buddy_accessory_id", Accessories.all(c).get(index).id)
                .remove("buddy_accessory").apply();
    }

    static int sizeIndex(Context c) {
        return clamp(prefs(c).getInt("buddy_size", 1), SIZES.length);
    }

    static int speedIndex(Context c) {
        return clamp(prefs(c).getInt("buddy_speed", 1), SPEEDS.length);
    }

    /** How the buddy appears and leaves (e.g. when you open or close the checklist). */
    static final int ENTRANCE_PORTAL = 0, ENTRANCE_POP = 1, ENTRANCE_WALK = 2, ENTRANCE_FADE = 3,
            ENTRANCE_NONE = 4, ENTRANCES = 5;

    static int entranceIndex(Context c) {
        return clamp(prefs(c).getInt("buddy_entrance", ENTRANCE_PORTAL), ENTRANCES);
    }

    static String[] entranceNames(Context c) {
        return c.getResources().getStringArray(R.array.entrance_names);
    }

    /** 0 quiet (only reacts to your progress), 1 normal, 2 chatty. */
    static int chatIndex(Context c) {
        return clamp(prefs(c).getInt("buddy_chat", 1), CHAT_LEVELS);
    }

    static void set(Context c, String key, int value) {
        prefs(c).edit().putInt(key, value).apply();
    }

    static int bestStreak(Context c) {
        return Math.max(Routine.streak(c), History.bestStreak(History.all(c)));
    }

    static boolean unlocked(Context c, int accessory) {
        int need = unlockStreak(c, accessory);
        return need == 0 || bestStreak(c) >= need;
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }
}
