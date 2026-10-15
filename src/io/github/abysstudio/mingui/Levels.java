package io.github.abysstudio.mingui;

import android.content.Context;

import java.util.TreeMap;

/**
 * Buddy XP and levels, derived from the history log (so backups carry them): 10 XP per habit done,
 * +20 for finishing the whole day. Partial days still earn XP, so a broken streak never wipes progress.
 */
final class Levels {
    static final int XP_PER_HABIT = 10, XP_DAY_BONUS = 20;
    /** Levels at which the buddy visibly evolves. */
    static final int SPROUT = 3, GLOW = 5, WINGS = 8;
    static final int[] EVOLUTIONS = {SPROUT, GLOW, WINGS};

    private Levels() {}

    static int xp(TreeMap<String, History.Day> log) {
        int xp = 0;
        for (History.Day d : log.values()) {
            int n = 0;
            for (String i : d.items) if (d.done.contains(i)) n++;
            xp += n * XP_PER_HABIT + (d.complete() ? XP_DAY_BONUS : 0);
        }
        return xp;
    }

    static int xp(Context c) {
        return xp(History.all(c));
    }

    /** Total XP needed to reach a level: 0, 100, 300, 600, 1000, ... */
    static int threshold(int level) {
        return 50 * level * (level - 1);
    }

    static int level(int xp) {
        int l = 1;
        while (xp >= threshold(l + 1)) l++;
        return l;
    }

    /** Name of what the buddy gains at this level, or null. */
    static String evolutionAt(Context c, int level) {
        String[] names = c.getResources().getStringArray(R.array.evolution_names);
        for (int i = 0; i < EVOLUTIONS.length; i++) if (EVOLUTIONS[i] == level) return names[i];
        return null;
    }
}
