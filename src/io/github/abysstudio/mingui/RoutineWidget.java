package io.github.abysstudio.mingui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Set;

/**
 * Home-screen widget. Small sizes get a compact card (big buddy, count, progress); larger sizes add a
 * tappable habit list (checkbox, name, timer/cue on the right, "up next" in accent, done ones struck through).
 */
public class RoutineWidget extends AppWidgetProvider {
    static final String ACTION_TOGGLE = "io.github.abysstudio.mingui.WIDGET_TOGGLE";
    private static final int[] ROWS = {R.id.r0, R.id.r1, R.id.r2, R.id.r3, R.id.r4, R.id.r5, R.id.r6, R.id.r7};
    private static final int[] CHECKS = {R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5, R.id.c6, R.id.c7};
    private static final int[] NAMES = {R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4, R.id.n5, R.id.n6, R.id.n7};
    private static final int[] METAS = {R.id.m0, R.id.m1, R.id.m2, R.id.m3, R.id.m4, R.id.m5, R.id.m6, R.id.m7};
    /** Below this size (dp) the compact layout is used. */
    private static final int COMPACT_W = 220, COMPACT_H = 170;

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        updateAll(c);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager mgr, int id, Bundle options) {
        // Resized: pick the layout that fits.
        try {
            mgr.updateAppWidget(id, build(c, options));
        } catch (RuntimeException ignored) {
        }
    }

    static void toggle(Context c, String item) {
        if (item == null || !Routine.items(c).contains(item)) return;
        boolean now = !Routine.checked(c).contains(item);
        // Timed habits need their timer, which lives in the app.
        if (now && Routine.timerMinutes(c, item) > 0 && Routine.timerLeft(c, item) != 0) {
            MainActivity.pendingHighlight = item;
            MainActivity.launch(c);
            return;
        }
        Routine.setChecked(c, item, now);
        if (now) Sounds.tick(c); else Sounds.untick(c);
        Gate.sync(c, false, null);
        UnlockWatcher.update(c);
        updateAll(c);
    }

    /** Redraws every placed widget at its own size. Cheap; call after anything that changes today's state. */
    static void updateAll(Context c) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(c);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(c, RoutineWidget.class));
        if (ids == null) return;
        for (int id : ids) {
            try {
                mgr.updateAppWidget(id, build(c, mgr.getAppWidgetOptions(id)));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static RemoteViews build(Context c, Bundle options) {
        // Portrait: width is the min width, height the max height.
        int w = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int h = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        boolean compact = w > 0 && h > 0 && (w < COMPACT_W || h < COMPACT_H);

        List<String> items = HabitMeta.ordered(c, Routine.items(c));
        Set<String> checked = Routine.checked(c);
        int n = 0;
        for (String i : items) if (checked.contains(i)) n++;
        boolean rest = Routine.isRestDay(c);
        boolean done = Routine.isDoneToday(c);
        float mood = items.isEmpty() ? 1f : (float) n / items.size();
        int streak = Routine.streak(c);
        int level = Levels.level(Levels.xp(c));
        String name = BuddyStyle.name(c);

        RemoteViews rv = new RemoteViews(c.getPackageName(), compact ? R.layout.widget_compact : R.layout.widget);
        PendingIntent open = PendingIntent.getActivity(c, 7, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.buddy, open);
        rv.setOnClickPendingIntent(R.id.title, open);
        if (compact) rv.setOnClickPendingIntent(R.id.root, open);

        // Header.
        int left = items.size() - n;
        String title = rest ? c.getString(R.string.widget_rest) : done ? c.getString(R.string.widget_done)
                : compact ? c.getString(R.string.widget_count, n, items.size())
                : n == 0 ? c.getString(R.string.widget_start)
                : c.getResources().getQuantityString(R.plurals.widget_to_go, left, left);
        rv.setTextViewText(R.id.title, title);
        String streakPart = streak > 0 ? c.getString(R.string.day_streak, streak) : c.getString(R.string.no_streak_yet);
        rv.setTextViewText(R.id.sub, compact ? streakPart
                : (done && !rest ? c.getString(R.string.widget_unlocked) : c.getString(R.string.widget_n_of, n, items.size()))
                + "  ·  " + streakPart + "  ·  " + name + " " + c.getString(R.string.lv, level));
        int pct = items.isEmpty() ? 100 : Math.round(100f * n / items.size());
        rv.setViewVisibility(R.id.progress, done ? View.GONE : View.VISIBLE);
        rv.setViewVisibility(R.id.progress_done, done ? View.VISIBLE : View.GONE);
        rv.setProgressBar(R.id.progress, 100, pct, false);

        float d = c.getResources().getDisplayMetrics().density;
        int bw = (int) ((compact ? 96 : 72) * d), bh = (int) ((compact ? 100 : 76) * d);
        rv.setImageViewBitmap(R.id.buddy, buddyBitmap(c, mood, done, level, bw, bh, compact ? 1.25f : 0.95f));
        if (compact) return rv;

        // Habit list.
        String upNext = null;
        for (String i : items) {
            if (!checked.contains(i)) {
                upNext = i;
                break;
            }
        }
        for (int i = 0; i < ROWS.length; i++) {
            if (i >= items.size()) {
                rv.setViewVisibility(ROWS[i], View.GONE);
                continue;
            }
            String item = items.get(i);
            boolean on = checked.contains(item);
            boolean next = item.equals(upNext) && items.size() > 1;
            rv.setViewVisibility(ROWS[i], View.VISIBLE);
            rv.setImageViewResource(CHECKS[i], on ? R.drawable.ic_box_checked : R.drawable.ic_box);

            SpannableString label = new SpannableString(item);
            if (on) label.setSpan(new StrikethroughSpan(), 0, item.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (next) label.setSpan(new StyleSpan(Typeface.BOLD), 0, item.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            rv.setTextViewText(NAMES[i], label);
            rv.setTextColor(NAMES[i], on ? MainActivity.MUTED : next ? 0xFFB3A3FF : MainActivity.TEXT);

            // Right side: timer length, or the cue time.
            int timer = Routine.timerMinutes(c, item);
            HabitMeta.Cue cue = HabitMeta.cue(c, item);
            String meta = "";
            boolean timed = false;
            if (!on && timer > 0) {
                meta = timer + "m";
                timed = true;
            } else if (!on && cue.type == HabitMeta.Cue.AT) {
                meta = Prompts.hhmm(cue.minutes);
            }
            rv.setTextViewText(METAS[i], meta);
            rv.setTextViewCompoundDrawablesRelative(METAS[i], timed ? R.drawable.ic_timer_small : 0, 0, 0, 0);

            Intent t = new Intent(c, WidgetActions.class).setAction(ACTION_TOGGLE).putExtra("item", item);
            rv.setOnClickPendingIntent(ROWS[i], PendingIntent.getBroadcast(c, 100 + i, t,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        if (items.size() > ROWS.length) {
            rv.setViewVisibility(R.id.more, View.VISIBLE);
            rv.setTextViewText(R.id.more, c.getString(R.string.widget_more, items.size() - ROWS.length));
            rv.setOnClickPendingIntent(R.id.more, open);
        } else {
            rv.setViewVisibility(R.id.more, View.GONE);
        }
        rv.setViewVisibility(R.id.empty, rest ? View.VISIBLE : View.GONE);
        if (rest) rv.setTextViewText(R.id.empty, c.getString(R.string.widget_rest_body));
        return rv;
    }

    /** Renders the buddy offscreen into a bitmap (widgets can't host custom views). */
    private static Bitmap buddyBitmap(Context c, float mood, boolean done, int level, int w, int h, float scale) {
        Buddy.BuddyView v = new Buddy.BuddyView(c);
        v.mood = mood;
        v.done = done;
        v.level = level;
        v.color = BuddyStyle.COLORS[BuddyStyle.colorIndex(c)];
        v.accessory = BuddyStyle.accessoryDef(c);
        v.setScale(scale);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(bmp));
        return bmp;
    }
}
