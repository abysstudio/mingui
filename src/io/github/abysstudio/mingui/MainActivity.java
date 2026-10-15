package io.github.abysstudio.mingui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

public class MainActivity extends Activity {
    static final int BG = 0xFF0E1014, SHEET = 0xFF14171D, CARD = 0xFF181B22, CARD_DONE = 0xFF13211A,
            BORDER = 0xFF262A33, TEXT = 0xFFF2F4F8, MUTED = 0xFF8A93A6,
            ACCENT = 0xFF7C5CFF, ACCENT2 = 0xFF4F8BFF, GOOD = 0xFF2FD180, WARN = 0xFFFF5A5F;
    // Heatmap: one green ramp (validated against the sheet surface) plus a neutral for missed days.
    static final int HEAT_MISSED = 0xFF2A2F3A, HEAT_LOW = 0xFF21603F, HEAT_HIGH = 0xFF279A64, HEAT_DONE = GOOD;
    /** Not part of the green ramp: rest days (nothing scheduled) and freeze-covered days. */
    static final int HEAT_REST = 0xFF1E222B, HEAT_FROZEN = 0xFF2F6E9E;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** Whether the checklist is on screen; read by UnlockWatcher to decide whether to pull you back. */
    static volatile boolean visible;
    /** Habit to pulse when the checklist next opens (set by cue reminders). */
    static volatile String pendingHighlight;
    /** Checklist cards in display order: {habit, card, label, "Up next" badge}. */
    private final List<Object[]> cards = new ArrayList<>();

    private boolean wasDone;
    private TextView breakLink;
    /** Live timer cards, refreshed every second by {@link #tick}. */
    private final List<Runnable> timerViews = new ArrayList<>();
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            for (Runnable r : timerViews) r.run();
            ticker.postDelayed(this, 1000);
        }
    };
    /** Set when we open an allowed app ourselves, so that leaving isn't logged as an escape attempt. */
    private boolean openingAllowedApp;
    private View workApps;
    private boolean rootShownOk;
    private ProgressRing ring;
    private TextView heroTitle, heroSub, streakLabel, cta;
    private Dialog editor, settings;
    private final List<View[]> tiles = new ArrayList<>(); // {icon, lock badge}
    private Apps.Entry ctaApp;

    /** Localized string shorthand. */
    String s(int id, Object... args) {
        return getString(id, args);
    }

    /** Localized plural: the count picks the form and is also passed as the first argument. */
    String q(int id, int n, Object... more) {
        Object[] args = new Object[more.length + 1];
        args[0] = n;
        System.arraycopy(more, 0, args, 1, more.length);
        return getResources().getQuantityString(id, n, args);
    }

    /** A date format in the user's language, from a skeleton like "EEEEMMMMd". */
    static SimpleDateFormat dateFormat(String skeleton) {
        Locale l = Locale.getDefault();
        return new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(l, skeleton), l);
    }

    static void launch(Context c) {
        c.startActivity(new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    static final String EXTRA_PAUSE = "pause";
    private boolean pendingPause;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pendingPause = getIntent().getBooleanExtra(EXTRA_PAUSE, false);
        // Also runs after a language switch, so the widget follows.
        RoutineWidget.updateAll(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_PAUSE, false)) pendingPause = true;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        build(); // rotation / resize on large screens: just lay out again
    }

    /**
     * Edge-to-edge (enforced from Android 15/16): pad a view by the system bars, cutout and keyboard,
     * on top of its own padding. {@code top} = also pad for the status bar.
     */
    @SuppressWarnings("deprecation")
    void fitSystemBars(final View v, final boolean top) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener((view, in) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                il = x.left;
                it = x.top;
                ir = x.right;
                ib = x.bottom;
            } else {
                il = in.getSystemWindowInsetLeft();
                it = in.getSystemWindowInsetTop();
                ir = in.getSystemWindowInsetRight();
                ib = in.getSystemWindowInsetBottom();
            }
            view.setPadding(l + il, t + (top ? it : 0), r + ir, b + ib);
            return in;
        });
        v.requestApplyInsets();
    }

    // Android 13+: Back is a callback; registered only while focus lock is holding you here.
    private android.window.OnBackInvokedCallback backCallback;

    private void updateBackBlock() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean want = locked();
        if (want && backCallback == null) {
            backCallback = this::shakeForBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else if (!want && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
    }

    private void shakeForBack() {
        View content = findViewById(android.R.id.content);
        content.animate().translationX(dp(10)).setDuration(50).withEndAction(() ->
                content.animate().translationX(0).setDuration(300)
                        .setInterpolator(new OvershootInterpolator(6f)).start()).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        UnlockWatcher.pokeBuddy();
        Prompts.markShown(this);
        openingAllowedApp = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        UnlockWatcher.pokeBuddy();
        if (!isChangingConfigurations() && locked()) {
            UnlockWatcher.activityLeft(this);
            // Log it as an escape attempt, unless the screen just went off or we opened a work app.
            if (!openingAllowedApp && getSystemService(android.os.PowerManager.class).isInteractive()) {
                History.addEvent(this, "leaves", System.currentTimeMillis());
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Only reached before Android 13; newer versions use updateBackBlock().
        if (!locked()) {
            super.onBackPressed();
            return;
        }
        shakeForBack();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    /** Focus lock is active: routine unfinished, setting on, not on a break. */
    private boolean locked() {
        return Routine.focusLock(this) && !Routine.isDoneToday(this) && !Routine.onBreak(this);
    }

    /** Hide the status and navigation bars while locked, so it's harder to swipe away. */
    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        boolean hide = locked();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (hide) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hide
                    ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                    : 0);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
        RoutineWidget.updateAll(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tick);
        ticker.postDelayed(tick, 1000);
        Gate.scheduleMidnight(this);
        Prompts.scheduleNext(this);
        if (Routine.isConfigured(this)) {
            History.applyFreezes(this);
            History.logToday(this);
        }
        build();
        updateBackBlock();
        if (pendingPause) {
            pendingPause = false;
            openPauseSheet();
        }
        if (onboardRefresh != null) onboardRefresh.run(); // back from a permission screen
        if (!Routine.prefs(this).getBoolean("onboarded", false) && !Routine.isConfigured(this)) {
            openOnboarding();
        } else if (!Routine.isConfigured(this)) {
            openEditor(true);
        }
    }

    // ---------------------------------------------------------------- main screen

    private void build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(28), dp(20), dp(16));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // Header: date + greeting, edit pill on the right.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView date = text(dateFormat("EEEEMMMMd")
                .format(Routine.dayCalendar().getTime()).toUpperCase(Locale.getDefault()), 12, MUTED);
        date.setLetterSpacing(0.12f);
        date.setTypeface(MEDIUM);
        titles.addView(date);
        TextView greet = text(greeting(), 30, TEXT);
        greet.setTypeface(Typeface.DEFAULT_BOLD);
        greet.setPadding(0, dp(2), 0, 0);
        titles.addView(greet);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settingsPill = text("", 14, TEXT);
        settingsPill.setText(TextUtils.concat(icon(R.drawable.ic_settings, TEXT, 16), "  " + s(R.string.settings)));
        settingsPill.setTypeface(MEDIUM);
        settingsPill.setPadding(dp(14), dp(8), dp(14), dp(8));
        settingsPill.setBackground(rounded(CARD, 20, BORDER));
        settingsPill.setForeground(ripple(20));
        settingsPill.setOnClickListener(v -> openSettings());
        header.addView(settingsPill);
        content.addView(header);
        content.addView(space(20));

        // Hero card: progress ring + status + streak.
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        hero.setBackground(rounded(CARD, 24, BORDER));
        hero.setForeground(ripple(24));
        hero.setOnClickListener(v -> openStats());
        ring = new ProgressRing(this);
        hero.addView(ring, new LinearLayout.LayoutParams(dp(96), dp(96)));
        LinearLayout heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(dp(18), 0, 0, 0);
        heroTitle = text("", 20, TEXT);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        heroSub = text("", 14, MUTED);
        heroSub.setPadding(0, dp(2), 0, dp(10));
        streakLabel = text("", 13, TEXT);
        streakLabel.setTypeface(MEDIUM);
        streakLabel.setPadding(dp(10), dp(5), dp(10), dp(5));
        streakLabel.setBackground(rounded(withAlpha(0xFFFF9F43, 0x26), 14, 0));
        heroText.addView(heroTitle);
        heroText.addView(heroSub);
        heroText.addView(streakLabel, new LinearLayout.LayoutParams(-2, -2));
        hero.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(hero);

        if (Routine.pausedToday(this)) content.addView(pausedBanner());
        // Required permissions always show; merely recommended ones can be dismissed for good.
        if (!BuildFlags.ROOT && (!Perms.requiredGranted(this)
                || (!Perms.allWanted(this) && !Routine.prefs(this).getBoolean("setup_dismissed", false)))) {
            content.addView(setupCard());
        }

        // Checklist.
        TextView section = text(s(R.string.todays_routine), 12, MUTED);
        section.setLetterSpacing(0.12f);
        section.setTypeface(MEDIUM);
        section.setPadding(dp(4), dp(28), 0, dp(10));
        content.addView(section);
        Set<String> checked = Routine.checked(this);
        timerViews.clear();
        View freeze = freezeBanner();
        if (freeze != null) content.addView(freeze);
        cards.clear();
        // Ordered by when you're likely to do them (cue time, or when you usually do it).
        for (String item : HabitMeta.ordered(this, Routine.items(this))) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            View card = itemCard(item, checked.contains(item));
            content.addView(card, lp);
            if (item.equals(pendingHighlight)) {
                // Arrived here from a cue reminder: draw the eye to that habit.
                pendingHighlight = null;
                card.postDelayed(() -> card.animate().scaleX(1.04f).scaleY(1.04f).setDuration(220)
                        .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f).setDuration(350)
                                .setInterpolator(new OvershootInterpolator(3f)).start()).start(), 450);
            }
        }
        if (Routine.isRestDay(this)) {
            TextView rest = text(s(R.string.rest_day_body), 15, MUTED);
            rest.setGravity(Gravity.CENTER);
            rest.setPadding(dp(16), dp(22), dp(16), dp(22));
            rest.setBackground(rounded(CARD, 18, BORDER));
            content.addView(rest);
        }
        // Habits that exist but aren't scheduled today.
        List<String> notToday = new ArrayList<>(Routine.allItems(this));
        notToday.removeAll(Routine.items(this));
        if (!notToday.isEmpty()) {
            TextView nt = text("", 13, MUTED);
            nt.setText(TextUtils.concat(icon(R.drawable.ic_calendar, MUTED, 13),
                    "  " + s(R.string.not_today, TextUtils.join(s(R.string.list_sep), notToday))));
            nt.setPadding(dp(4), dp(4), dp(4), dp(6));
            content.addView(nt);
        }

        List<Apps.Entry> allowed = Apps.load(this, Routine.allowedApps(this));
        LinearLayout work = new LinearLayout(this);
        work.setOrientation(LinearLayout.VERTICAL);
        if (!allowed.isEmpty()) {
            TextView wl = text(s(R.string.work_apps), 12, MUTED);
            wl.setLetterSpacing(0.12f);
            wl.setTypeface(MEDIUM);
            wl.setPadding(dp(4), dp(18), 0, dp(10));
            work.addView(wl);
            work.addView(allowedTiles(allowed));
        }
        content.addView(work);
        workApps = work;

        breakLink = text("", 14, MUTED);
        breakLink.setGravity(Gravity.CENTER);
        breakLink.setPadding(0, dp(14), 0, dp(14));
        breakLink.setOnClickListener(v -> openBreakSheet());
        content.addView(breakLink, new LinearLayout.LayoutParams(-1, -2));

        List<Apps.Entry> blockedApps = Apps.load(this, Routine.blockedApps(this));
        ctaApp = blockedApps.size() == 1 ? blockedApps.get(0) : null;

        rootShownOk = Gate.rootOk(this);
        if (!rootShownOk) content.addView(rootCard());

        // Bottom bar with the main call to action.
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(20), dp(8), dp(20), dp(20));
        cta = text("", 17, TEXT);
        cta.setTypeface(MEDIUM);
        cta.setGravity(Gravity.CENTER);
        cta.setForeground(ripple(28));
        cta.setOnClickListener(v -> {
            if (Routine.isDoneToday(this) && ctaApp != null) launchApp(ctaApp);
        });
        bar.addView(cta, new LinearLayout.LayoutParams(-1, dp(56)));
        page.addView(bar);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        fitSystemBars(page, true);
        snackHost = frame;
        setContentView(frame);
        wasDone = Routine.isDoneToday(this);
        refreshSummary(false);
    }

    private View itemCard(final String item, boolean on) {
        final LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(17), dp(16), dp(17));
        final CheckMark mark = new CheckMark(this);
        mark.setChecked(on, false);
        card.addView(mark, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        final TextView label = text(item, 17, TEXT);
        col.addView(label);
        HabitMeta.Cue cue = HabitMeta.cue(this, item);
        if (cue.type != HabitMeta.Cue.NONE) {
            TextView cueLine = text("", 13, MUTED);
            cueLine.setText(TextUtils.concat(icon(cue.type == HabitMeta.Cue.AT ? R.drawable.ic_calendar
                    : R.drawable.ic_arrow, MUTED, 13), "  " + cue.describe(this)));
            cueLine.setPadding(0, dp(2), 0, 0);
            col.addView(cueLine);
        }
        final int minutes = Routine.timerMinutes(this, item);
        final TextView timer = text("", 13, MUTED);
        if (minutes > 0) {
            timer.setPadding(0, dp(2), 0, 0);
            col.addView(timer);
            final boolean[] wasRunning = {Routine.timerLeft(this, item) > 0};
            Runnable update = () -> {
                boolean checkedNow = Routine.checked(this).contains(item);
                long left = Routine.timerLeft(this, item);
                if (checkedNow) {
                    timer.setText(TextUtils.concat(icon(R.drawable.ic_timer, MUTED, 13), "  " + s(R.string.n_min, minutes)));
                    timer.setTextColor(MUTED);
                    mark.setTimerProgress(-1);
                } else if (left < 0) {
                    timer.setText(TextUtils.concat(icon(R.drawable.ic_timer, MUTED, 13),
                            "  " + s(R.string.timer_tap_start, minutes)));
                    timer.setTextColor(MUTED);
                    mark.setTimerProgress(-1);
                } else if (left > 0) {
                    long sec = (left + 999) / 1000;
                    timer.setText(TextUtils.concat(icon(R.drawable.ic_timer, ACCENT, 13),
                            "  " + s(R.string.timer_left, (int) (sec / 60), (int) (sec % 60))));
                    timer.setTextColor(ACCENT);
                    mark.setTimerProgress(1f - (float) left / (minutes * 60_000L));
                } else {
                    timer.setText(TextUtils.concat(icon(R.drawable.ic_check, GOOD, 13), "  " + s(R.string.times_up)));
                    timer.setTextColor(GOOD);
                    mark.setTimerProgress(-1);
                    if (wasRunning[0]) card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
                wasRunning[0] = left > 0;
            };
            update.run();
            timerViews.add(update);
            card.setOnLongClickListener(v -> {
                if (Routine.timerLeft(this, item) <= 0) return false;
                Routine.resetTimer(this, item);
                update.run();
                Toast.makeText(this, s(R.string.timer_reset), Toast.LENGTH_SHORT).show();
                return true;
            });
        }
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text(s(R.string.up_next_badge), 10, Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setLetterSpacing(0.08f);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        badge.setBackground(rounded(ACCENT, 8, 0));
        badge.setVisibility(View.GONE);
        card.addView(badge);
        cards.add(new Object[]{item, card, label, badge});
        styleItem(card, label, on);
        card.setForeground(ripple(18));
        card.setOnClickListener(v -> {
            boolean now = !Routine.checked(this).contains(item);
            if (now && minutes > 0) {
                long left = Routine.timerLeft(this, item);
                if (left < 0) {
                    // First tap starts the timer; the habit can be checked once it runs out.
                    Routine.startTimer(this, item);
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    for (Runnable r : timerViews) r.run();
                    return;
                }
                if (left > 0) {
                    v.animate().translationX(dp(6)).setDuration(50).withEndAction(() ->
                            v.animate().translationX(0).setDuration(250)
                                    .setInterpolator(new OvershootInterpolator(6f)).start()).start();
                    return;
                }
            }
            Routine.setChecked(this, item, now);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (now) {
                Sounds.tick(this);
                showVote(v, item);
            } else {
                Sounds.untick(this);
                showUndo(item, () -> {
                    Routine.setChecked(this, item, true);
                    mark.setChecked(true, true);
                    styleItem(card, label, true);
                    for (Runnable rr : timerViews) rr.run();
                    refreshSummary(true);
                });
            }
            mark.setChecked(now, true);
            styleItem(card, label, now);
            for (Runnable r : timerViews) r.run();
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(160)
                            .setInterpolator(new OvershootInterpolator()).start())
                    .start();
            refreshSummary(true);
        });
        return card;
    }

    private void styleItem(View card, TextView label, boolean on) {
        card.setBackground(rounded(on ? CARD_DONE : CARD, 18, on ? withAlpha(GOOD, 0x40) : BORDER));
        label.setTextColor(on ? MUTED : TEXT);
        label.setPaintFlags(on ? label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                : label.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
    }

    /**
     * No-root build: walk through the permissions that root would otherwise grant automatically.
     * Only what the features you turned on actually use is listed, most important first, and each
     * one says what it powers. Opens with the offline promise, since that's what makes them safe.
     */
    private View setupCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(10));
        final boolean required = !Perms.requiredGranted(this);
        box.setBackground(rounded(CARD, 18, required ? withAlpha(WARN, 0x66) : BORDER));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(s(required ? R.string.finish_setup : R.string.recommended), 16, required ? WARN : TEXT);
        t.setTypeface(MEDIUM);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (!required && Routine.isConfigured(this)) {
            ImageView x = iconView(R.drawable.ic_close, MUTED);
            x.setPadding(dp(4), dp(4), dp(4), dp(4));
            x.setContentDescription(s(R.string.not_now));
            x.setOnClickListener(v -> {
                Routine.prefs(this).edit().putBoolean("setup_dismissed", true).apply();
                build();
            });
            head.addView(x, new LinearLayout.LayoutParams(dp(28), dp(28)));
        }
        box.addView(head);
        TextView s = text(s(required ? R.string.setup_required_body : R.string.setup_optional_body), 14, MUTED);
        s.setPadding(0, dp(4), 0, dp(10));
        box.addView(s);
        box.addView(offlineBadge());

        boolean usageNeeded = Perms.needsUsage(this);
        if (usageNeeded && !Perms.usageAccess(this) && !Perms.restrictedSettingsAllowed(this)) {
            box.addView(permRow(R.drawable.ic_lock, s(R.string.restricted), s(R.string.restricted_sub), false,
                    s(R.string.restricted), s(R.string.restricted_disclosure),
                    () -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())))));
        }
        // Overlay first: once it's on, the app may bring itself back from Settings after the next grant.
        if (Perms.wantsOverlay(this)) {
            box.addView(permRow(R.drawable.ic_layers, s(R.string.overlay),
                    s(Perms.needsOverlay(this) ? R.string.overlay_sub : R.string.overlay_sub_buddy),
                    Perms.overlay(this), s(R.string.overlay), s(R.string.overlay_disclosure), () -> {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                        returnWhenGranted(() -> Perms.overlay(this));
                    }));
        }
        if (usageNeeded) {
            box.addView(permRow(R.drawable.ic_eye, s(R.string.usage_access), s(R.string.usage_access_sub),
                    Perms.usageAccess(this), s(R.string.usage_access), s(R.string.usage_access_disclosure), () -> {
                        // Straight to this app's switch where the phone supports it, else the list.
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName())));
                        } catch (RuntimeException e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS));
                        }
                        returnWhenGranted(() -> Perms.usageAccess(this));
                    }));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            box.addView(permRow(R.drawable.ic_bell, s(R.string.notifications), s(R.string.notifications_sub),
                    Perms.notifications(this), null,
                    () -> requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7)));
        }
        if (Perms.wantsBattery(this)) {
            box.addView(permRow(R.drawable.ic_battery, s(R.string.battery), s(R.string.battery_sub),
                    Perms.batteryExempt(this), null, () -> {
                        startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        returnWhenGranted(() -> Perms.batteryExempt(this));
                    }));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        box.setLayoutParams(lp);
        return box;
    }

    /** "100% offline" pill: tapping it explains why that makes the permissions safe. */
    private View offlineBadge() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), dp(10), dp(12), dp(10));
        pill.setBackground(rounded(withAlpha(GOOD, 0x1F), 14, withAlpha(GOOD, 0x55)));
        pill.setForeground(ripple(14));
        pill.addView(iconView(R.drawable.ic_cloud_off, GOOD), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(6), 0);
        TextView a = text(s(R.string.offline_title), 14, TEXT);
        a.setTypeface(MEDIUM);
        col.addView(a);
        col.addView(text(s(R.string.offline_short), 12, MUTED));
        pill.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        pill.addView(iconView(R.drawable.ic_chevron, MUTED), new LinearLayout.LayoutParams(dp(20), dp(20)));
        pill.setOnClickListener(v -> openPrivacy());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        pill.setLayoutParams(lp);
        return pill;
    }

    /** What the app can and can't do with your data, and what each permission is for. */
    private void openPrivacy() {
        if (settings != null && settings.isShowing()) settings.dismiss();
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(rounded(withAlpha(GOOD, 0x26), 20, 0));
        badge.addView(iconView(R.drawable.ic_shield, GOOD), new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(56), dp(56));
        blp.bottomMargin = dp(12);
        sheet.addView(badge, blp);
        TextView t = text(s(R.string.privacy_title), 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(t);
        TextView b = text(s(R.string.privacy_body), 15, MUTED);
        b.setPadding(0, dp(8), 0, dp(4));
        sheet.addView(b);

        sheet.addView(sectionLabel(s(R.string.privacy_perms_header)));
        int[][] rows = BuildFlags.ROOT
                ? new int[][]{{R.drawable.ic_lock, R.string.root_title, R.string.root_why},
                {R.drawable.ic_layers, R.string.overlay, R.string.overlay_why},
                {R.drawable.ic_eye, R.string.usage_access, R.string.usage_why},
                {R.drawable.ic_bell, R.string.notifications, R.string.notifications_why}}
                : new int[][]{{R.drawable.ic_layers, R.string.overlay, R.string.overlay_why},
                {R.drawable.ic_eye, R.string.usage_access, R.string.usage_why},
                {R.drawable.ic_bell, R.string.notifications, R.string.notifications_why},
                {R.drawable.ic_battery, R.string.battery, R.string.battery_why}};
        View[] views = new View[rows.length];
        for (int i = 0; i < rows.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            ImageView ic = iconView(rows[i][0], ACCENT);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
            ilp.rightMargin = dp(12);
            ilp.topMargin = dp(1);
            row.addView(ic, ilp);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView tt = text(s(rows[i][1]), 15, TEXT);
            tt.setTypeface(MEDIUM);
            col.addView(tt);
            TextView st = text(s(rows[i][2]), 13, MUTED);
            st.setPadding(0, dp(2), 0, 0);
            col.addView(st);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            views[i] = row;
        }
        sheet.addView(group(views));

        TextView ok = text(s(R.string.got_it), 16, Color.WHITE);
        ok.setTypeface(MEDIUM);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(gradientPill(26));
        ok.setForeground(ripple(26));
        ok.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, dp(52));
        olp.topMargin = dp(18);
        sheet.addView(ok, olp);
        showSheet(d, sheet);
    }

    /**
     * After sending you to a Settings screen, watch for the permission and bring the app back by itself
     * as soon as it's on, so you don't have to find your way back. Gives up after two minutes.
     */
    private void returnWhenGranted(final java.util.function.BooleanSupplier granted) {
        final long until = SystemClock.uptimeMillis() + 120_000;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (visible || SystemClock.uptimeMillis() > until) return; // came back on their own
                if (!granted.getAsBoolean()) {
                    h.postDelayed(this, 400);
                    return;
                }
                try {
                    startActivity(new Intent(MainActivity.this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    Toast.makeText(MainActivity.this, s(R.string.perm_back), Toast.LENGTH_SHORT).show();
                } catch (RuntimeException ignored) {
                    // Android may refuse a background start; then you just come back yourself.
                }
            }
        }, 800);
    }

    /** A permission line. If {@code disclosureTitle} is set, explain first (prominent disclosure), then ask. */
    private View permRow(int icon, String title, String sub, boolean granted, final String disclosureTitle,
                         final String disclosure, final Runnable ask) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        FrameLayout ib = new FrameLayout(this);
        ib.setBackground(rounded(withAlpha(granted ? GOOD : ACCENT, 0x22), 12, 0));
        ib.addView(iconView(icon, granted ? GOOD : ACCENT), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        LinearLayout.LayoutParams iblp = new LinearLayout.LayoutParams(dp(36), dp(36));
        iblp.rightMargin = dp(12);
        row.addView(ib, iblp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tt = text(title, 15, TEXT);
        tt.setTypeface(MEDIUM);
        col.addView(tt);
        col.addView(text(sub, 12, MUTED));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (granted) {
            row.addView(iconView(R.drawable.ic_check, GOOD), new LinearLayout.LayoutParams(dp(24), dp(24)));
        } else {
            TextView btn = text(s(R.string.allow), 14, Color.WHITE);
            btn.setTypeface(MEDIUM);
            btn.setPadding(dp(16), dp(8), dp(16), dp(8));
            btn.setBackground(gradientPill(18));
            btn.setForeground(ripple(18));
            final Runnable safeAsk = () -> {
                try {
                    ask.run();
                } catch (RuntimeException e) {
                    startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                }
            };
            btn.setOnClickListener(v -> {
                if (disclosureTitle == null) safeAsk.run();
                else showDisclosure(disclosureTitle, disclosure, safeAsk);
            });
            row.addView(btn);
        }
        return row;
    }

    private View permRow(int icon, String title, String sub, boolean granted, String disclosureTitle, Runnable ask) {
        return permRow(icon, title, sub, granted, disclosureTitle, null, ask);
    }

    /** Prominent disclosure before a special-permission screen: what, why, and that data stays on device. */
    private void showDisclosure(String title, String body, final Runnable onContinue) {
        showDisclosure(title, body, s(R.string.not_now), s(R.string.continue_), onContinue);
    }

    private void showDisclosure(String title, String body, String noLabel, String goLabel, final Runnable onContinue) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        TextView t = text(title, 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(t);
        TextView b = text(body == null ? "" : body, 15, MUTED);
        b.setPadding(0, dp(10), 0, dp(18));
        sheet.addView(b);
        LinearLayout buttons = new LinearLayout(this);
        TextView no = text(noLabel, 16, TEXT);
        no.setTypeface(MEDIUM);
        no.setGravity(Gravity.CENTER);
        no.setBackground(rounded(CARD, 26, BORDER));
        no.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, dp(52), 1);
        nlp.rightMargin = dp(10);
        buttons.addView(no, nlp);
        TextView go = text(goLabel, 16, Color.WHITE);
        go.setTypeface(MEDIUM);
        go.setGravity(Gravity.CENTER);
        go.setBackground(gradientPill(26));
        go.setOnClickListener(v -> {
            d.dismiss();
            onContinue.run();
        });
        buttons.addView(go, new LinearLayout.LayoutParams(0, dp(52), 1));
        sheet.addView(buttons);
        showSheet(d, sheet);
    }

    /** Explicit consent the first time Focus lock is turned on (Play build). */
    private void confirmFocusLock() {
        // Off until confirmed; "Not now" simply leaves it off.
        Routine.setFocusLock(this, false);
        if (settings != null) settings.dismiss();
        showDisclosure(s(R.string.focus_q), s(R.string.focus_body, Routine.MAX_BREAKS),
                () -> {
                    Routine.prefs(this).edit().putBoolean("focus_consent", true).apply();
                    Routine.setFocusLock(this, true);
                    UnlockWatcher.update(this);
                    build();
                });
    }

    // ---------------------------------------------------------------- pause for today (emergency exit)

    private View pausedBanner() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(14), dp(12), dp(14));
        box.setBackground(rounded(withAlpha(STREAK_ORANGE, 0x1A), 18, withAlpha(STREAK_ORANGE, 0x66)));
        TextView t = text(s(R.string.paused_banner), 14, TEXT);
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView resume = text(s(R.string.resume), 14, ACCENT);
        resume.setTypeface(MEDIUM);
        resume.setPadding(dp(12), dp(6), dp(4), dp(6));
        resume.setOnClickListener(v -> {
            Routine.unpause(this);
            UnlockWatcher.update(this);
            build();
        });
        box.addView(resume);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        box.setLayoutParams(lp);
        return box;
    }

    /** Always-available emergency exit (Play build): hold to pause blocking and focus lock until tomorrow. */
    void openPauseSheet() {
        if (BuildFlags.ROOT) return;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        boolean paused = Routine.pausedToday(this);
        TextView t = text(s(paused ? R.string.paused_for_today : R.string.pause_q), 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(t);
        TextView b = text(s(paused ? R.string.paused_body : R.string.pause_body), 15, MUTED);
        b.setPadding(0, dp(8), 0, dp(18));
        sheet.addView(b);
        if (paused) {
            TextView resume = text(s(R.string.resume_now), 16, Color.WHITE);
            resume.setTypeface(MEDIUM);
            resume.setGravity(Gravity.CENTER);
            resume.setBackground(gradientPill(26));
            resume.setOnClickListener(v -> {
                Routine.unpause(this);
                UnlockWatcher.update(this);
                d.dismiss();
                build();
            });
            sheet.addView(resume, new LinearLayout.LayoutParams(-1, dp(52)));
        } else {
            HoldButton hold = new HoldButton(this, s(R.string.hold_to_pause), () -> {
                Routine.pauseToday(this);
                UnlockWatcher.update(this);
                d.dismiss();
                build();
                Toast.makeText(this, s(R.string.paused_toast), Toast.LENGTH_SHORT).show();
            });
            sheet.addView(hold, new LinearLayout.LayoutParams(-1, dp(56)));
            TextView cancel = text(s(R.string.keep_going), 16, TEXT);
            cancel.setTypeface(MEDIUM);
            cancel.setGravity(Gravity.CENTER);
            cancel.setBackground(rounded(CARD, 28, BORDER));
            cancel.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(52));
            clp.topMargin = dp(10);
            sheet.addView(cancel, clp);
        }
        showSheet(d, sheet);
    }

    private View rootCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(rounded(CARD, 18, withAlpha(WARN, 0x66)));
        TextView t = text(s(R.string.root_needed), 16, WARN);
        t.setTypeface(MEDIUM);
        box.addView(t);
        TextView s = text(s(R.string.root_needed_body, s(R.string.app_name)), 14, MUTED);
        s.setPadding(0, dp(4), 0, dp(12));
        box.addView(s);
        TextView btn = text(s(R.string.try_again), 14, TEXT);
        btn.setTypeface(MEDIUM);
        btn.setPadding(dp(16), dp(9), dp(16), dp(9));
        btn.setBackground(gradientPill(20));
        btn.setForeground(ripple(20));
        btn.setOnClickListener(v -> Gate.sync(this, true, ok -> {
            if (!ok) Toast.makeText(this, s(R.string.still_no_root), Toast.LENGTH_SHORT).show();
            build();
        }));
        box.addView(btn, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(18);
        box.setLayoutParams(lp);
        return box;
    }

    private void refreshSummary(boolean animate) {
        List<String> items = Routine.items(this);
        Set<String> checked = Routine.checked(this);
        int total = items.size(), n = 0;
        for (String i : items) if (checked.contains(i)) n++;
        boolean done = Routine.isDoneToday(this);
        boolean rest = total == 0 && done;
        UnlockWatcher.update(this);
        applyImmersive();
        updateBackBlock();
        boolean lockOn = Routine.focusLock(this) && !done;
        breakLink.setVisibility(lockOn ? View.VISIBLE : View.GONE);
        workApps.setVisibility(lockOn ? View.VISIBLE : View.GONE);
        if (Routine.onBreak(this)) {
            breakLink.setText(s(R.string.on_break_until, new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(Routine.breakUntil(this)))));
        } else {
            int b = Routine.breaksLeft(this);
            breakLink.setText(q(R.plurals.breaks_left, b));
        }
        Gate.sync(this, false, ok -> {
            if (ok != rootShownOk && !isFinishing()) build();
        });
        int left = total - n;

        ring.set(total == 0 ? (done ? 1 : 0) : (float) n / total, done, n + "/" + total, animate);
        heroTitle.setText(rest ? s(R.string.hero_rest)
                : done ? TextUtils.concat(s(R.string.hero_done) + "  ", icon(R.drawable.ic_sparkle, GOOD, 20))
                : n == 0 ? s(R.string.hero_start)
                : left == 1 ? s(R.string.hero_one_more)
                : (float) n / total >= 0.5f ? s(R.string.hero_halfway) : s(R.string.hero_nice_start));
        updateUpNext();
        String showedUp = showedUpAs(checked);
        heroSub.setText(done && !rest && showedUp != null ? s(R.string.showed_up_as, showedUp)
                : done ? s(R.string.apps_unlocked_today)
                : q(R.plurals.habits_left_unlock, left));
        TreeMap<String, History.Day> log = History.all(this);
        int streak = History.currentStreak(log);
        int bestEver = Math.max(streak, History.bestStreak(log));
        int freezes = Routine.freezes(this);
        int level = Levels.level(Levels.xp(log));
        streakLabel.setText(TextUtils.concat(icon(R.drawable.ic_flame, 0xFFFF9F43, 15),
                streak > 0 ? " " + s(R.string.day_streak, streak) + (streak > 1 && streak == bestEver ? s(R.string.best_suffix) : "")
                        : " " + s(R.string.start_streak),
                freezes > 0 ? TextUtils.concat("  ", icon(R.drawable.ic_snow, 0xFF7CC4FF, 14), " " + freezes) : "",
                "  ·  " + s(R.string.lv, level)));
        checkLevelUp(level, animate && !(done && !wasDone));

        cta.setEnabled(done);
        if (done) {
            cta.setText(ctaApp != null
                    ? TextUtils.concat(s(R.string.open_app, ctaApp.label) + "  ", icon(R.drawable.ic_arrow, Color.WHITE, 18))
                    : TextUtils.concat(s(R.string.all_apps_unlocked) + "  ", icon(R.drawable.ic_check, Color.WHITE, 18)));
            cta.setTextColor(Color.WHITE);
            cta.setBackground(gradientPill(28));
        } else {
            cta.setText(TextUtils.concat(icon(R.drawable.ic_lock, MUTED, 17), "  " + q(R.plurals.more_to_unlock, left)));
            cta.setTextColor(MUTED);
            cta.setBackground(rounded(CARD, 28, BORDER));
        }

        if (animate && done && !wasDone && !rest) {
            Sounds.celebrate(this);
            celebrate();
            ring.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            ring.animate().scaleX(1.12f).scaleY(1.12f).setDuration(180).withEndAction(() ->
                    ring.animate().scaleX(1f).scaleY(1f).setDuration(320)
                            .setInterpolator(new OvershootInterpolator(3f)).start()).start();
            cta.setScaleX(0.9f);
            cta.setScaleY(0.9f);
            cta.animate().scaleX(1f).scaleY(1f).setDuration(400)
                    .setInterpolator(new OvershootInterpolator(2f)).start();
        }
        wasDone = done;
    }

    private void launchApp(Apps.Entry app) {
        // Make sure the unhide has gone through before asking for the app's launch intent.
        Gate.sync(this, false, ok -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(app.pkg);
            if (i == null) {
                Toast.makeText(this, ok || !BuildFlags.ROOT ? s(R.string.couldnt_open, app.label)
                                : s(R.string.couldnt_unhide, app.label),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(i);
            finish();
        });
    }

    // ---------------------------------------------------------------- blocked apps

    private void openSettings() {
        if (settings != null && settings.isShowing()) return;
        final Dialog d = new Dialog(this);
        settings = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        // Title, with the quick switches (sound, language) as small icon buttons beside it.
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(s(R.string.settings), 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        final ImageView sound = headerIcon(Sounds.on(this) ? R.drawable.ic_volume : R.drawable.ic_volume_off,
                s(R.string.sounds));
        sound.setOnClickListener(v -> {
            boolean on = !Sounds.on(this);
            Sounds.setOn(this, on);
            if (on) Sounds.tick(this);
            sound.setImageDrawable(tinted(on ? R.drawable.ic_volume : R.drawable.ic_volume_off, TEXT, 22));
            stateDesc(sound, s(on ? R.string.on : R.string.off));
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Toast.makeText(this, s(on ? R.string.sounds_on : R.string.sounds_off), Toast.LENGTH_SHORT).show();
        });
        stateDesc(sound, s(Sounds.on(this) ? R.string.on : R.string.off));
        header.addView(sound);
        ImageView lang = headerIcon(R.drawable.ic_language, s(R.string.language) + ": " + currentLanguage());
        lang.setOnClickListener(v -> {
            settings.dismiss();
            openLanguagePicker();
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(44), dp(44));
        llp.leftMargin = dp(8);
        header.addView(lang, llp);
        ImageView shield = headerIcon(R.drawable.ic_shield, s(R.string.privacy_title));
        shield.setOnClickListener(v -> openPrivacy());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(44), dp(44));
        plp.leftMargin = dp(8);
        header.addView(shield, plp);
        sheet.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        // Buddy first: it's the friendly face of the app.
        View hero = buddyHeroCard();
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(14);
        body.addView(hero, hlp);
        body.addView(routineSection());
        body.addView(blockedAppsSection());
        body.addView(settingsSection());
        body.addView(advancedSection());
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView close = text(s(R.string.done), 16, Color.WHITE);
        close.setTypeface(MEDIUM);
        close.setGravity(Gravity.CENTER);
        close.setBackground(gradientPill(26));
        close.setForeground(ripple(26));
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(52));
        clp.topMargin = dp(16);
        sheet.addView(close, clp);

        d.setContentView(sheet);
        fitSystemBars(sheet, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
    }

    private View routineSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView section = text(s(R.string.routine_header), 12, MUTED);
        section.setLetterSpacing(0.12f);
        section.setTypeface(MEDIUM);
        section.setPadding(dp(4), dp(18), 0, dp(10));
        box.addView(section);

        final boolean done = Routine.isDoneToday(this);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(rounded(CARD, 18, BORDER));
        row.setForeground(ripple(18));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(s(R.string.edit_habits), 16, TEXT);
        title.setTypeface(MEDIUM);
        col.addView(title);
        int n = Routine.allItems(this).size();
        TextView sub = text("", 13, MUTED);
        sub.setText(done ? q(R.plurals.n_habits, n) : TextUtils.concat(q(R.plurals.n_habits, n)
                + "  ·  ", icon(R.drawable.ic_lock, MUTED, 13), s(R.string.asks_first)));
        sub.setPadding(0, dp(2), dp(8), 0);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(iconView(R.drawable.ic_chevron, MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.setOnClickListener(v -> {
            settings.dismiss();
            if (Routine.isDoneToday(this)) {
                openEditor(false);
                return;
            }
            // Mid-day edits are allowed, but only after a deliberate "yes".
            showDisclosure(s(R.string.edit_routine_q), s(R.string.edit_routine_body),
                    s(R.string.not_now), s(R.string.yes_edit), () -> openEditor(false));
        });

        LinearLayout stats = new LinearLayout(this);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setPadding(dp(16), dp(14), dp(16), dp(14));
        stats.setBackground(rounded(CARD, 18, BORDER));
        stats.setForeground(ripple(18));
        LinearLayout scol = new LinearLayout(this);
        scol.setOrientation(LinearLayout.VERTICAL);
        TextView stitle = text(s(R.string.your_stats), 16, TEXT);
        stitle.setTypeface(MEDIUM);
        scol.addView(stitle);
        TextView ssub = text(s(R.string.your_stats_sub), 13, MUTED);
        ssub.setPadding(0, dp(2), dp(8), 0);
        scol.addView(ssub);
        stats.addView(scol, new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(iconView(R.drawable.ic_chevron, MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
        stats.setOnClickListener(v -> {
            settings.dismiss();
            openStats();
        });
        box.addView(group(row, stats));
        return box;
    }

    private View allowedAppsSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(22), 0, dp(10));
        TextView section = text(s(R.string.allowed_during_focus), 12, MUTED);
        section.setLetterSpacing(0.12f);
        section.setTypeface(MEDIUM);
        head.addView(section, new LinearLayout.LayoutParams(0, -2, 1));
        TextView manage = text(s(R.string.manage), 14, ACCENT);
        manage.setTypeface(MEDIUM);
        manage.setPadding(dp(12), dp(6), dp(4), dp(6));
        manage.setOnClickListener(v -> {
            settings.dismiss();
            openAppPicker(true);
        });
        head.addView(manage);
        box.addView(head);

        List<Apps.Entry> apps = Apps.load(this, Routine.allowedApps(this));
        if (apps.isEmpty()) {
            TextView empty = text(s(R.string.no_work_apps), 14, MUTED);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            empty.setBackground(rounded(CARD, 18, BORDER));
            empty.setOnClickListener(v -> {
                settings.dismiss();
                openAppPicker(true);
            });
            box.addView(empty);
            return box;
        }
        box.addView(allowedTiles(apps));
        return box;
    }

    /** Horizontally scrolling row of allowed apps; tapping one opens it. */
    private View allowedTiles(List<Apps.Entry> apps) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (final Apps.Entry app : apps) {
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setPadding(dp(2), 0, dp(2), 0);
            FrameLayout iconBox = new FrameLayout(this);
            iconBox.setBackground(rounded(CARD, 20, BORDER));
            iconBox.setForeground(ripple(20));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            iconBox.addView(icon, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            t.addView(iconBox, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView label = text(app.label, 12, MUTED);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, dp(6), 0, 0);
            t.addView(label, new LinearLayout.LayoutParams(dp(72), -2));
            iconBox.setOnClickListener(v -> {
                if (settings != null) settings.dismiss();
                Intent i = getPackageManager().getLaunchIntentForPackage(app.pkg);
                if (i == null) {
                    Toast.makeText(this, s(R.string.couldnt_open, app.label), Toast.LENGTH_SHORT).show();
                    return;
                }
                openingAllowedApp = true;
                startActivity(i);
            });
            row.addView(t);
        }
        hs.addView(row);
        return hs;
    }

    private View blockedAppsSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(18), 0, dp(10));
        TextView section = text(s(R.string.blocked_apps_header), 12, MUTED);
        section.setLetterSpacing(0.12f);
        section.setTypeface(MEDIUM);
        head.addView(section, new LinearLayout.LayoutParams(0, -2, 1));
        TextView manage = text(s(R.string.manage), 14, ACCENT);
        manage.setTypeface(MEDIUM);
        manage.setPadding(dp(12), dp(6), dp(4), dp(6));
        manage.setOnClickListener(v -> {
            settings.dismiss();
            openAppPicker();
        });
        head.addView(manage);
        box.addView(head);

        tiles.clear();
        List<Apps.Entry> apps = Apps.load(this, Routine.blockedApps(this));
        if (apps.isEmpty()) {
            TextView empty = text(s(R.string.no_blocked_apps), 14, MUTED);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            empty.setBackground(rounded(CARD, 18, BORDER));
            empty.setOnClickListener(v -> {
                settings.dismiss();
                openAppPicker();
            });
            box.addView(empty);
            return box;
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (Apps.Entry app : apps) row.addView(appTile(app));
        hs.addView(row);
        box.addView(hs);
        styleTiles(Routine.isDoneToday(this));
        return box;
    }

    private void styleTiles(boolean done) {
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0);
        for (View[] t : tiles) {
            ImageView icon = (ImageView) t[0];
            icon.setColorFilter(done ? null : new ColorMatrixColorFilter(gray));
            icon.setImageAlpha(done ? 255 : 120);
            t[1].setVisibility(done ? View.GONE : View.VISIBLE);
        }
    }

    private View appTile(final Apps.Entry app) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(2), 0, dp(2), 0);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(CARD, 20, BORDER));
        iconBox.setForeground(ripple(20));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        ImageView lock = iconView(R.drawable.ic_lock, TEXT);
        lock.setPadding(dp(5), dp(5), dp(5), dp(5));
        lock.setBackground(rounded(BG, 11, BORDER));
        iconBox.addView(lock, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM | Gravity.END));
        t.addView(iconBox, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView label = text(app.label, 12, MUTED);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);
        t.addView(label, new LinearLayout.LayoutParams(dp(72), -2));

        iconBox.setOnClickListener(v -> {
            if (Routine.isDoneToday(this)) {
                launchApp(app);
            } else {
                v.animate().translationX(dp(6)).setDuration(50).withEndAction(() ->
                        v.animate().translationX(0).setDuration(250)
                                .setInterpolator(new OvershootInterpolator(6f)).start()).start();
                Toast.makeText(this, s(R.string.finish_to_unlock_app, app.label), Toast.LENGTH_SHORT).show();
            }
        });
        tiles.add(new View[]{icon, lock});
        return t;
    }

    private View settingsSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView section = text(s(R.string.staying_on_track), 12, MUTED);
        section.setLetterSpacing(0.12f);
        section.setTypeface(MEDIUM);
        section.setPadding(dp(4), dp(24), 0, dp(10));
        box.addView(section);
        // Allowed apps only matter while Focus lock is on, so they show (and hide) with it.
        final View allowed = allowedAppsSection();
        allowed.setVisibility(Routine.prefs(this).getBoolean("focus_lock", false) ? View.VISIBLE : View.GONE);
        View focus = toggleRow(s(R.string.focus_lock), s(R.string.focus_lock_sub),
                Routine.focusLock(this), on -> {
                    Routine.setFocusLock(this, on);
                    allowed.setVisibility(on ? View.VISIBLE : View.GONE);
                    if (on && !BuildFlags.ROOT && !Routine.prefs(this).getBoolean("focus_consent", false)) {
                        confirmFocusLock();
                    }
                });
        decorateFocusRow((LinearLayout) focus);
        if (!BuildFlags.ROOT) {
            View pause = navRow(s(Routine.pausedToday(this) ? R.string.paused_for_today : R.string.pause_for_today),
                    s(R.string.emergency_exit), v -> {
                        settings.dismiss();
                        openPauseSheet();
                    });
            box.addView(group(focus, pause));
        } else {
            box.addView(group(focus));
        }
        box.addView(allowed);
        return box;
    }

    /** Focus lock explained at a glance: an icon, plus three small icon chips for how it behaves. */
    private void decorateFocusRow(LinearLayout row) {
        ImageView ic = iconView(R.drawable.ic_focus, ACCENT);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        ic.setBackground(rounded(withAlpha(ACCENT, 0x26), 12, 0));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.rightMargin = dp(12);
        ilp.gravity = Gravity.TOP;
        row.addView(ic, 0, ilp);
        LinearLayout col = (LinearLayout) row.getChildAt(1);
        FlowLayout chips = new FlowLayout(this, dp(6), dp(6));
        chips.setPadding(0, dp(8), 0, 0);
        chips.addView(focusChip(R.drawable.ic_undo, s(R.string.focus_chip_back)));
        chips.addView(focusChip(R.drawable.ic_coffee, s(R.string.focus_chip_breaks, Routine.MAX_BREAKS,
                (int) (Routine.BREAK_MS / 60_000))));
        chips.addView(focusChip(R.drawable.ic_check, s(R.string.focus_chip_done)));
        col.addView(chips);
    }

    private View focusChip(int icon, String label) {
        TextView t = text("", 12, TEXT);
        t.setText(TextUtils.concat(icon(icon, MUTED, 14), " " + label));
        t.setPadding(dp(8), dp(4), dp(10), dp(4));
        t.setBackground(rounded(withAlpha(TEXT, 0x10), 12, 0));
        return t;
    }

    /** After you confirm once, loosening is allowed for a few minutes without asking again. */
    private long loosenUntil;

    private boolean loosenAllowed() {
        return Routine.isDoneToday(this) || SystemClock.elapsedRealtime() < loosenUntil;
    }

    /**
     * One rule for every setting: making things stricter always works straight away; making them looser
     * before today's routine is done asks first (like editing habits), then applies immediately.
     */
    private void whenLoosenAllowed(Runnable change) {
        if (loosenAllowed()) {
            change.run();
            return;
        }
        showDisclosure(s(R.string.loosen_q), s(R.string.loosen_body), s(R.string.not_now), s(R.string.yes_change),
                () -> {
                    loosenUntil = SystemClock.elapsedRealtime() + 5 * 60_000;
                    change.run();
                });
    }

    /** Rows stacked in one card with hairline dividers, instead of a card each. */
    private View group(View... rows) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 18, BORDER));
        card.setClipToOutline(true);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) card.addView(divider());
            rows[i].setBackground(null);
            card.addView(rows[i], new LinearLayout.LayoutParams(-1, -2));
        }
        return card;
    }

    private static void stateDesc(View v, CharSequence state) {
        if (Build.VERSION.SDK_INT >= 30) v.setStateDescription(state);
    }

    /** Small round icon button on the buddy card. */
    private ImageView heroIcon() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(rounded(withAlpha(BG, 0x80), 18, BORDER));
        v.setForeground(ripple(18));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return v;
    }

    /** Small round icon button for the sheet header. */
    private ImageView headerIcon(int res, String label) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(tinted(res, TEXT, 22));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(rounded(CARD, 22, BORDER));
        v.setForeground(ripple(22));
        v.setContentDescription(label);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return v;
    }

    /** Settings that most people never touch, folded away behind one row. */
    private View advancedSection() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(group(toggleRow(s(R.string.open_on_unlock), s(R.string.open_on_unlock_sub),
                Routine.openOnUnlock(this), on -> Routine.setOpenOnUnlock(this, on))));
        content.addView(promptsSection());
        content.addView(dataSection());
        return collapsible(s(R.string.advanced_header), "settings_advanced_open", content);
    }

    /** A section header with a chevron that folds {@code content} away; remembers its state under {@code key}. */
    private View collapsible(String header, final String key, final View content) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(26), dp(4), dp(10));
        TextView label = text(header, 12, MUTED);
        label.setLetterSpacing(0.12f);
        label.setTypeface(MEDIUM);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        final ImageView chev = iconView(R.drawable.ic_chevron, MUTED);
        row.addView(chev, new LinearLayout.LayoutParams(dp(20), dp(20)));
        box.addView(row);
        box.addView(content);

        final boolean[] open = {Routine.prefs(this).getBoolean(key, false)};
        final Runnable paint = () -> {
            content.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            chev.setRotation(open[0] ? 270 : 90);
            stateDesc(row, s(open[0] ? R.string.expanded : R.string.collapsed));
        };
        paint.run();
        row.setContentDescription(header);
        row.setOnClickListener(v -> {
            open[0] = !open[0];
            Routine.prefs(this).edit().putBoolean(key, open[0]).apply();
            paint.run();
        });
        return box;
    }

    /** A setting switch that can always be turned on, but only turned off once today's routine is done. */
    private View toggleRow(String titleText, String subText, boolean value, java.util.function.Consumer<Boolean> save) {
        return toggleRow(titleText, subText, value, save, true);
    }

    private View toggleRow(String titleText, String subText, boolean value, java.util.function.Consumer<Boolean> save,
                           final boolean strict) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(12), dp(14));
        row.setBackground(rounded(CARD, 18, BORDER));
        row.setForeground(ripple(18));
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleText, 16, TEXT);
        title.setTypeface(MEDIUM);
        col.addView(title);
        TextView sub = text(subText, 13, MUTED);
        sub.setPadding(0, dp(2), dp(8), 0);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        final Switch sw = new Switch(this);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ACCENT, 0xFFB0B6C3}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{withAlpha(ACCENT, 0x80), BORDER}));
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, on) -> {
            if (strict && !on && !loosenAllowed()) {
                b.setChecked(true);
                whenLoosenAllowed(() -> b.setChecked(false));
                return;
            }
            save.accept(on);
            UnlockWatcher.update(this);
            refreshSummary(false);
        });
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    // ---------------------------------------------------------------- stats

    private void openStats() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());

        TreeMap<String, History.Day> log = History.all(this);
        TextView title = text(s(R.string.your_stats), 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);
        String since = log.isEmpty() ? s(R.string.tracking_starts_today)
                : s(R.string.tracking_since, prettyDate(log.firstKey(), "yMMMd"));
        TextView sub = text(since, 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(6));
        sheet.addView(sub);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        List<History.Slot> week = History.window(log, 7), month = History.window(log, 30);
        int current = History.currentStreak(log);
        int best = Math.max(current, History.bestStreak(log));

        // At a glance: the streak, big. Everything else is one tap away under "More stats".
        body.addView(streakHero(current, best, month));

        body.addView(sectionLabel(s(R.string.last_12_weeks)));
        final TextView caption = text(s(R.string.tap_day), 13, MUTED);
        // Recent days can be corrected: "I did it but forgot to tick it".
        final TextView fix = text("", 14, ACCENT);
        fix.setTypeface(MEDIUM);
        fix.setPadding(dp(2), dp(10), dp(2), dp(4));
        fix.setVisibility(View.GONE);
        HeatmapView heat = new HeatmapView(this, log, date -> {
            caption.setText(describeDay(log, date));
            boolean canFix = History.editable(date) && !History.itemsFor(this, date).isEmpty();
            fix.setVisibility(canFix ? View.VISIBLE : View.GONE);
            fix.setText(TextUtils.concat(icon(R.drawable.ic_check, ACCENT, 15), " " + s(R.string.fix_day)));
            fix.setOnClickListener(v -> openFixDay(date, d));
        });
        body.addView(heat, new LinearLayout.LayoutParams(-1, -2));
        body.addView(heatLegend());
        caption.setPadding(dp(2), dp(8), 0, 0);
        body.addView(caption);
        body.addView(fix);
        TextView hint = text(s(R.string.fix_day_hint), 12, MUTED);
        hint.setPadding(dp(2), dp(4), 0, 0);
        body.addView(hint);

        Map<String, int[]> rates = History.habitRates(month);
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(rates.entrySet());
        sorted.sort((a, b) -> Float.compare((float) a.getValue()[0] / a.getValue()[1],
                (float) b.getValue()[0] / b.getValue()[1]));
        if (!sorted.isEmpty()) {
            body.addView(sectionLabel(s(R.string.needs_love)));
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                Map.Entry<String, int[]> e = sorted.get(i);
                body.addView(habitBar(e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
        }

        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.VERTICAL);
        more.addView(sectionLabel(s(R.string.overview)));
        more.addView(tileRow(
                statTile(rate(week), s(R.string.last_7_days), completedOf(week)),
                statTile(rate(month), s(R.string.last_30_days), completedOf(month))));
        more.addView(sectionLabel(s(R.string.milestones)));
        more.addView(milestoneBadges(best));
        if (sorted.size() > 3) {
            more.addView(sectionLabel(s(R.string.habits_weakest)));
            for (Map.Entry<String, int[]> e : sorted) more.addView(habitBar(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        Map<String, int[]> ids = HabitMeta.identityVotes(this, log);
        if (!ids.isEmpty()) {
            more.addView(sectionLabel(s(R.string.who_becoming)));
            TextView intro = text(s(R.string.who_becoming_intro), 13, MUTED);
            intro.setPadding(dp(4), 0, 0, dp(10));
            more.addView(intro);
            List<Map.Entry<String, int[]>> byVotes = new ArrayList<>(ids.entrySet());
            byVotes.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
            for (Map.Entry<String, int[]> e : byVotes) more.addView(identityCard(e.getKey(), e.getValue()));
        }

        more.addView(sectionLabel(s(R.string.timing_header)));
        int avg = History.avgFinishMinutes(month);
        String avgText = avg < 0 ? "--" : new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(0, 0, 1, avg / 60, avg % 60));
        int breaks = History.totalBreaks(month);
        more.addView(tileRow(
                statTile(avgText, s(R.string.average_finish)),
                statTile(String.valueOf(breaks), q(R.plurals.breaks_taken, breaks))));

        // When you try to get out: leaving the checklist during focus lock, plus breaks.
        int[] hours = History.escapesByHour(log, 30);
        int total = 0, peak = 0;
        for (int h = 0; h < 24; h++) {
            total += hours[h];
            if (hours[h] > hours[peak]) peak = h;
        }
        more.addView(sectionLabel(s(R.string.weak_moments)));
        more.addView(tileRow(
                statTile(String.valueOf(total), q(R.plurals.exit_attempts, total)),
                statTile(total == 0 ? "--" : String.format(Locale.US, "%02d:00", peak), s(R.string.peak_hour))));
        if (total > 0) {
            final TextView hourCaption = text(s(R.string.exit_by_hour), 13, MUTED);
            HourBarsView bars = new HourBarsView(this, hours, h -> hourCaption.setText(
                    q(R.plurals.hour_attempts, hours[h], h, (h + 1) % 24)));
            LinearLayout chart = new LinearLayout(this);
            chart.setOrientation(LinearLayout.VERTICAL);
            chart.setPadding(dp(14), dp(14), dp(14), dp(12));
            chart.setBackground(rounded(CARD, 18, BORDER));
            chart.addView(bars, new LinearLayout.LayoutParams(-1, dp(130)));
            hourCaption.setPadding(dp(2), dp(10), 0, 0);
            chart.addView(hourCaption);
            more.addView(chart);
        }

        body.addView(collapsible(s(R.string.more_stats), "stats_more_open", more));

        TextView close = text(s(R.string.done), 16, Color.WHITE);
        close.setTypeface(MEDIUM);
        close.setGravity(Gravity.CENTER);
        close.setBackground(gradientPill(26));
        close.setForeground(ripple(26));
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(52));
        clp.topMargin = dp(16);
        sheet.addView(close, clp);

        d.setContentView(sheet);
        fitSystemBars(sheet, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.92f));
    }

    /** Tick off habits you did on a recent day but forgot to check. Re-opens the stats afterwards. */
    private void openFixDay(final String date, final Dialog stats) {
        final List<String> items = History.itemsFor(this, date);
        History.Day day = History.all(this).get(date);
        final Set<String> done = new HashSet<>(day == null ? new HashSet<>() : day.done);
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        TextView t = text(prettyDate(date, "EEEEMMMMd"), 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(t);
        TextView sub = text(s(R.string.fix_day_sub), 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(12));
        sheet.addView(sub);
        View[] rows = new View[items.size()];
        for (int i = 0; i < items.size(); i++) {
            final String item = items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setForeground(ripple(18));
            final CheckMark mark = new CheckMark(this);
            mark.setChecked(done.contains(item), false);
            row.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView l = text(item, 16, TEXT);
            l.setPadding(dp(12), 0, 0, 0);
            row.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> {
                boolean on = !done.contains(item);
                if (on) done.add(item); else done.remove(item);
                mark.setChecked(on, true);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            });
            rows[i] = row;
        }
        sheet.addView(group(rows));
        TextView save = text(s(R.string.save), 16, Color.WHITE);
        save.setTypeface(MEDIUM);
        save.setGravity(Gravity.CENTER);
        save.setBackground(gradientPill(26));
        save.setForeground(ripple(26));
        save.setOnClickListener(v -> {
            History.setPastDay(this, date, items, done);
            RoutineWidget.updateAll(this);
            d.dismiss();
            stats.dismiss();
            openStats();
            build();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(52));
        slp.topMargin = dp(16);
        sheet.addView(save, slp);
        showSheet(d, sheet);
    }

    /** Big flame + current streak, with best streak and the 30-day rate alongside. */
    private View streakHero(int current, int best, List<History.Slot> month) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(STREAK_ORANGE, 0x38), withAlpha(ACCENT, 0x1A)});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), withAlpha(STREAK_ORANGE, 0x66));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(14);
        card.setLayoutParams(clp);

        ImageView flame = iconView(R.drawable.ic_flame, current > 0 ? STREAK_ORANGE : MUTED);
        flame.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(flame, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView big = text(String.valueOf(current), 40, TEXT);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setIncludeFontPadding(false);
        col.addView(big);
        col.addView(text(s(R.string.current_streak), 13, MUTED));
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        TextView bestV = text(days(best), 16, TEXT);
        bestV.setTypeface(MEDIUM);
        side.addView(bestV);
        side.addView(text(s(R.string.best_streak), 12, MUTED));
        TextView rateV = text(rate(month), 16, TEXT);
        rateV.setTypeface(MEDIUM);
        rateV.setPadding(0, dp(8), 0, 0);
        side.addView(rateV);
        side.addView(text(s(R.string.last_30_days), 12, MUTED));
        card.addView(side);
        return card;
    }

    private String days(int n) {
        return q(R.plurals.n_days, n);
    }

    private static String rate(List<History.Slot> window) {
        if (window.isEmpty()) return "--";
        int done = 0;
        for (History.Slot s : window) if (s.complete()) done++;
        return Math.round(100f * done / window.size()) + "%";
    }

    private String completedOf(List<History.Slot> window) {
        int done = 0, partial = 0;
        for (History.Slot s : window) {
            if (s.complete()) done++;
            else if (s.partial()) partial++;
        }
        int n = window.size();
        return partial > 0 ? getResources().getQuantityString(R.plurals.done_partial_of_days, n, done, n, partial)
                : getResources().getQuantityString(R.plurals.done_of_days, n, done, n);
    }

    private String describeDay(TreeMap<String, History.Day> log, String date) {
        String pretty = prettyDate(date, "EEEMMMd");
        History.Day d = log.get(date);
        if (log.isEmpty() || date.compareTo(log.firstKey()) < 0) return s(R.string.day_not_tracked, pretty);
        if (d == null) return s(R.string.day_missed, pretty);
        if (d.frozen) return s(R.string.day_frozen, pretty);
        if (d.rest) return s(R.string.day_rest, pretty);
        int n = d.done.size();
        String out = s(R.string.day_habits, pretty, Math.min(n, d.items.size()), d.items.size());
        if (d.complete() && d.completedAt > 0) {
            out += s(R.string.day_finished, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(d.completedAt)));
        }
        if (d.breaks > 0) out += q(R.plurals.day_breaks, d.breaks);
        return out;
    }

    /** {@code skeleton} is a date skeleton like "EEEMMMd"; the order and words follow the user's language. */
    private static String prettyDate(String isoDate, String skeleton) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate);
            return dateFormat(skeleton).format(date);
        } catch (java.text.ParseException e) {
            return isoDate;
        }
    }

    private TextView sectionLabel(String label) {
        TextView t = text(label, 12, MUTED);
        t.setLetterSpacing(0.12f);
        t.setTypeface(MEDIUM);
        t.setPadding(dp(4), dp(22), 0, dp(10));
        return t;
    }

    private View statTile(String value, String label) {
        return statTile(value, label, null);
    }

    private View statTile(String value, String label, String detail) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(rounded(CARD, 18, BORDER));
        TextView v = text(value, 26, TEXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        t.addView(v);
        TextView l = text(label, 13, MUTED);
        l.setPadding(0, dp(2), 0, 0);
        t.addView(l);
        if (detail != null) {
            TextView dt = text(detail, 12, MUTED);
            dt.setAlpha(0.75f);
            t.addView(dt);
        }
        return t;
    }

    private View tileRow(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, -1, 1);
        la.rightMargin = dp(5);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, -1, 1);
        lb.leftMargin = dp(5);
        row.addView(a, la);
        row.addView(b, lb);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private View heatLegend() {
        FlowLayout row = new FlowLayout(this, dp(14), dp(8));
        row.setPadding(dp(2), dp(12), 0, 0);
        int[] colors = {HEAT_MISSED, HEAT_LOW, HEAT_HIGH, HEAT_DONE, HEAT_FROZEN};
        String[] labels = getResources().getStringArray(R.array.heat_labels);
        for (int i = 0; i < colors.length; i++) {
            // Swatch + label stay together; whole items wrap to the next line.
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            View sw = new View(this);
            sw.setBackground(rounded(colors[i], 3, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(12), dp(12));
            lp.rightMargin = dp(6);
            item.addView(sw, lp);
            item.addView(text(labels[i], 12, MUTED));
            row.addView(item);
        }
        return row;
    }

    private View identityCard(String identity, int[] v) {
        int votes = v[0], strength = HabitMeta.strength(votes);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        box.setBackground(rounded(CARD, 18, BORDER));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String shortId = HabitMeta.shortIdentity(identity);
        TextView name = text(Character.toUpperCase(shortId.charAt(0)) + shortId.substring(1), 16, TEXT);
        name.setTypeface(MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        int[] tones = {MUTED, ACCENT2, ACCENT, STREAK_ORANGE};
        String[] strengths = getResources().getStringArray(R.array.strengths);
        TextView pill = text(strengths[strength], 11, Color.WHITE);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setPadding(dp(8), dp(3), dp(8), dp(3));
        pill.setBackground(rounded(tones[strength], 8, 0));
        head.addView(pill);
        box.addView(head);
        String month = v[1] > 0 ? s(R.string.days_this_month, v[2], v[1]) : "";
        TextView detail = text(q(R.plurals.n_votes, votes) + month, 12, MUTED);
        detail.setPadding(0, dp(2), 0, dp(8));
        box.addView(detail);
        boolean maxed = strength == HabitMeta.STRENGTH_AT.length - 1;
        int lo = HabitMeta.STRENGTH_AT[strength], hi = maxed ? lo : HabitMeta.STRENGTH_AT[strength + 1];
        box.addView(new BarView(this, maxed ? 1f : (float) (votes - lo) / (hi - lo)),
                new LinearLayout.LayoutParams(-1, dp(8)));
        if (!maxed) {
            TextView next = text(s(R.string.more_to_strength, hi - votes,
                    strengths[strength + 1].toLowerCase(Locale.getDefault())), 11, MUTED);
            next.setPadding(0, dp(5), 0, 0);
            box.addView(next);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    private View habitBar(String habit, int done, int total) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        box.setBackground(rounded(CARD, 18, BORDER));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(habit, 15, TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView pct = text(Math.round(100f * done / total) + "%", 15, TEXT);
        pct.setTypeface(MEDIUM);
        head.addView(pct);
        box.addView(head);
        TextView detail = text(s(R.string.x_of_y_days, done, total), 12, MUTED);
        detail.setPadding(0, dp(1), 0, dp(8));
        box.addView(detail);
        box.addView(new BarView(this, (float) done / total), new LinearLayout.LayoutParams(-1, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        box.setLayoutParams(lp);
        return box;
    }

    // ---------------------------------------------------------------- cues & identity on the checklist

    /** Highlights the first unchecked habit (in display order) as "Up next". */
    private void updateUpNext() {
        Set<String> checked = Routine.checked(this);
        boolean found = false;
        for (Object[] c : cards) {
            String item = (String) c[0];
            View card = (View) c[1];
            View badge = (View) c[3];
            boolean on = checked.contains(item);
            boolean next = !found && !on;
            if (next) found = true;
            badge.setVisibility(next && cards.size() > 1 ? View.VISIBLE : View.GONE);
            if (next && cards.size() > 1) card.setBackground(rounded(CARD, 18, withAlpha(ACCENT, 0xB0)));
            else styleItem(card, (TextView) c[2], on);
        }
    }

    /** "a reader and an athlete" for the identities behind today's checked habits, or null. */
    private String showedUpAs(Set<String> checked) {
        List<String> ids = new ArrayList<>();
        for (String h : Routine.items(this)) {
            if (!checked.contains(h)) continue;
            String id = HabitMeta.identity(this, h);
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) return null;
        if (ids.size() > 3) ids = ids.subList(0, 3);
        if (ids.size() == 1) return ids.get(0);
        return s(R.string.and_last, TextUtils.join(s(R.string.list_sep), ids.subList(0, ids.size() - 1)),
                ids.get(ids.size() - 1));
    }

    /** Little "+10 XP / +1 reader" badge that floats up from a habit you just ticked. Compact and on-screen. */
    private void showVote(final View anchor, String item) {
        String id = HabitMeta.identity(this, item);
        final LinearLayout pop = new LinearLayout(this);
        pop.setOrientation(LinearLayout.VERTICAL);
        pop.setGravity(Gravity.CENTER_HORIZONTAL);
        pop.setPadding(dp(12), dp(5), dp(12), dp(6));
        pop.setBackground(rounded(withAlpha(ACCENT, 0xF0), 14, 0));
        pop.setElevation(dp(4));
        TextView xp = text(s(R.string.xp_plus, Levels.XP_PER_HABIT), 14, Color.WHITE);
        xp.setTypeface(Typeface.DEFAULT_BOLD);
        pop.addView(xp);
        if (!id.isEmpty()) {
            TextView vote = text(s(R.string.vote_plus, HabitMeta.shortIdentity(id)), 11, 0xDDFFFFFF);
            vote.setSingleLine(true);
            vote.setEllipsize(TextUtils.TruncateAt.END);
            vote.setMaxWidth(dp(150));
            pop.addView(vote);
        }
        snackHost.addView(pop, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        pop.setAlpha(0f);
        pop.post(() -> {
            int[] a = new int[2], h = new int[2];
            anchor.getLocationInWindow(a);
            snackHost.getLocationInWindow(h);
            // Right side of the card, but never past either screen edge.
            float x = a[0] - h[0] + anchor.getWidth() - pop.getWidth() - dp(14);
            x = Math.max(dp(12), Math.min(snackHost.getWidth() - pop.getWidth() - dp(12), x));
            pop.setX(x);
            pop.setY(a[1] - h[1] - dp(4));
            pop.animate().alpha(1f).translationYBy(-dp(30)).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> pop.animate().alpha(0f).translationYBy(-dp(14)).setStartDelay(550)
                            .setDuration(450).withEndAction(() -> snackHost.removeView(pop)).start())
                    .start();
        });
    }

    // ---------------------------------------------------------------- undo, freezes, levels

    private FrameLayout snackHost;
    private View snack;

    /** "Unticked X · Undo" bar above the bottom button for a few seconds. */
    private void showUndo(String item, final Runnable undo) {
        if (snack != null) snackHost.removeView(snack);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(12), dp(8), dp(12));
        bar.setBackground(rounded(0xFF262A33, 16, 0));
        bar.setElevation(dp(6));
        TextView msg = text(s(R.string.unticked, item), 14, TEXT);
        msg.setSingleLine(true);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(msg, new LinearLayout.LayoutParams(0, -2, 1));
        TextView btn = text(s(R.string.undo), 14, ACCENT);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(12), dp(4), dp(12), dp(4));
        bar.addView(btn);
        final View me = bar;
        btn.setOnClickListener(v -> {
            undo.run();
            dismissSnack(me);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = lp.rightMargin = dp(16);
        lp.bottomMargin = dp(90);
        snackHost.addView(bar, lp);
        snack = bar;
        bar.setTranslationY(dp(40));
        bar.setAlpha(0f);
        bar.animate().translationY(0).alpha(1f).setDuration(200).start();
        bar.postDelayed(() -> dismissSnack(me), 4000);
    }

    private void dismissSnack(final View bar) {
        if (bar.getParent() == null) return;
        bar.animate().alpha(0f).translationY(dp(20)).setDuration(180)
                .withEndAction(() -> snackHost.removeView(bar)).start();
        if (snack == bar) snack = null;
    }

    /** Shown on the day a freeze saved the streak. */
    private View freezeBanner() {
        String notice = Routine.prefs(this).getString("freeze_notice", "");
        if (!notice.startsWith(Routine.today() + ":")) return null;
        int used = Integer.parseInt(notice.substring(notice.indexOf(':') + 1));
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(rounded(withAlpha(0xFF7CC4FF, 0x1F), 18, withAlpha(0xFF7CC4FF, 0x66)));
        box.addView(iconView(R.drawable.ic_snow, 0xFF7CC4FF), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(q(R.plurals.freeze_covered, used), 14, TEXT);
        t.setPadding(dp(12), 0, 0, 0);
        box.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        box.setLayoutParams(lp);
        return box;
    }

    private int pendingLevelUp;

    /** Level-up moment when XP crosses a threshold (folded into the day's celebration if both happen). */
    private void checkLevelUp(int level, boolean showOverlay) {
        SharedPreferences p = Routine.prefs(this);
        int seen = p.getInt("level_seen", 0);
        if (seen == 0) {
            p.edit().putInt("level_seen", level).apply(); // first run: no fanfare for existing progress
            return;
        }
        if (level <= seen) return;
        p.edit().putInt("level_seen", level).apply();
        pendingLevelUp = level;
        if (!showOverlay) return; // celebrate() will mention it
        pendingLevelUp = 0;
        Sounds.levelUp(this);
        String evo = Levels.evolutionAt(this, level);
        String bn = BuddyStyle.name(this);
        showCelebration(R.drawable.ic_sparkle, 0xFFFFD166, s(R.string.level_reached, bn, level),
                evo != null ? s(R.string.evolved, bn, evo.toLowerCase(Locale.getDefault()))
                        : s(R.string.level_stronger, bn),
                new ArrayList<>(), 120);
    }

    // ---------------------------------------------------------------- celebrations

    static final int[] MILESTONES = {3, 7, 14, 21, 30, 50, 100, 365};
    static final int STREAK_ORANGE = 0xFFFF9F43;

    private String milestoneTitle(int n) {
        switch (n) {
            case 3: return s(R.string.ms_3);
            case 7: return s(R.string.ms_7);
            case 14: return s(R.string.ms_14);
            case 21: return s(R.string.ms_21);
            case 30: return s(R.string.ms_30);
            case 50: return s(R.string.ms_50);
            case 100: return s(R.string.ms_100);
            case 365: return s(R.string.ms_365);
            default: return s(R.string.ms_other, n);
        }
    }

    /** Shown once per day, the first time the routine is completed. */
    private void celebrate() {
        SharedPreferences p = Routine.prefs(this);
        String today = Routine.today();
        if (today.equals(p.getString("celebrated_day", ""))) return; // re-checking a habit doesn't celebrate twice
        p.edit().putString("celebrated_day", today).apply();

        TreeMap<String, History.Day> log = History.all(this);
        int streak = History.currentStreak(log);
        TreeMap<String, History.Day> before = new TreeMap<>(log);
        before.remove(today);
        int bestBefore = History.bestStreak(before);

        boolean milestone = false;
        for (int m : MILESTONES) if (m == streak) milestone = true;
        boolean newBest = streak >= 2 && streak > bestBefore;
        History.Day yesterday = log.get(History.daysAgo(1));
        boolean comeback = streak <= 1 && !log.isEmpty() && log.firstKey().compareTo(History.daysAgo(1)) < 0
                && (yesterday == null || (!yesterday.complete() && !yesterday.partial()));

        int iconRes = R.drawable.ic_sparkle, iconColor = GOOD, particles = 90;
        String title, sub;
        if (milestone) {
            iconRes = R.drawable.ic_flame;
            iconColor = STREAK_ORANGE;
            particles = 180;
            title = milestoneTitle(streak);
            sub = s(newBest ? R.string.ms_best : R.string.ms_keep);
        } else if (newBest) {
            iconRes = R.drawable.ic_flame;
            iconColor = STREAK_ORANGE;
            particles = 150;
            title = s(R.string.new_best);
            sub = s(R.string.new_best_sub, streak);
        } else if (comeback) {
            title = s(R.string.welcome_back);
            sub = s(R.string.welcome_back_sub);
        } else {
            String[] titles = getResources().getStringArray(R.array.done_titles);
            title = titles[new java.util.Random().nextInt(titles.length)];
            sub = streak > 1 ? s(R.string.streak_counting, streak) : s(R.string.day_one);
        }

        List<String> extras = new ArrayList<>();
        // Earlier than usual?
        History.Day td = log.get(today);
        int avg = History.avgFinishMinutes(History.window(before, 30));
        if (td != null && td.completedAt > 0 && avg >= 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(td.completedAt);
            int start = Routine.DAY_START_HOUR * 60;
            int now = (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE) - start + 1440) % 1440;
            int usual = (avg - start + 1440) % 1440;
            if (usual - now >= 20) extras.add(s(R.string.earlier_than_usual, formatDuration(usual - now)));
        }
        // Longest per-habit run ending today.
        String bestHabit = null;
        int bestRun = 0;
        if (td != null) {
            for (String habit : td.items) {
                int run = 0;
                for (int i = 0; ; i++) {
                    History.Day d = log.get(History.daysAgo(i));
                    if (d == null || !d.done.contains(habit)) break;
                    run++;
                }
                if (run > bestRun) {
                    bestRun = run;
                    bestHabit = habit;
                }
            }
        }
        if (bestHabit != null && bestRun >= 5) extras.add(s(R.string.habit_run, bestHabit, bestRun));
        String who = showedUpAs(Routine.checked(this));
        if (who != null) extras.add(0, s(R.string.today_showed_up, who));
        if (streak > 0 && streak % 7 == 0 && Routine.freezes(this) < Routine.MAX_FREEZES) {
            Routine.setFreezes(this, Routine.freezes(this) + 1);
            extras.add(s(R.string.earned_freeze, Routine.freezes(this)));
        }
        if (pendingLevelUp > 0) {
            String evo = Levels.evolutionAt(this, pendingLevelUp);
            extras.add(evo != null
                    ? s(R.string.level_extra_evo, BuddyStyle.name(this), pendingLevelUp, evo.toLowerCase(Locale.getDefault()))
                    : s(R.string.level_extra, BuddyStyle.name(this), pendingLevelUp));
            pendingLevelUp = 0;
        }
        for (int a = 1; a < BuddyStyle.accessoryCount(this); a++) {
            int need = BuddyStyle.unlockStreak(this, a);
            if (need == 0) continue; // free (e.g. seasonal) ones aren't streak rewards
            if (streak >= need && bestBefore < need) {
                extras.add(s(R.string.new_for, BuddyStyle.name(this),
                        BuddyStyle.accessoryNames(this)[a].toLowerCase(Locale.getDefault())));
            }
        }
        if (td != null && td.breaks == 0 && td.leaves.isEmpty() && Routine.focusLock(this)) {
            extras.add(s(R.string.no_breaks_today));
        }
        showCelebration(iconRes, iconColor, title, sub, extras, particles);
    }

    private String formatDuration(int minutes) {
        if (minutes < 60) return s(R.string.n_min, minutes);
        return minutes % 60 > 0 ? s(R.string.dur_h_min, minutes / 60, minutes % 60) : s(R.string.dur_h, minutes / 60);
    }

    private void showCelebration(int iconRes, int iconColor, String title, String sub, List<String> extras,
                                 int particles) {
        final FrameLayout root = findViewById(android.R.id.content);
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xE00E1014);
        overlay.setClickable(true);
        final ConfettiView confetti = new ConfettiView(this, particles);
        overlay.addView(confetti, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(22));
        card.setBackground(rounded(SHEET, 28, BORDER));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(rounded(withAlpha(iconColor, 0x26), 48, withAlpha(iconColor, 0x66)));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(tinted(iconRes, iconColor, 48));
        badge.addView(ic, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        card.addView(badge, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView t = text(title, 26, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(18), 0, dp(6));
        card.addView(t);
        TextView s = text(sub, 15, MUTED);
        s.setGravity(Gravity.CENTER);
        card.addView(s);
        for (String e : extras) {
            TextView x = text("", 14, TEXT);
            x.setText(TextUtils.concat(icon(R.drawable.ic_check, GOOD, 15), "  " + e));
            x.setGravity(Gravity.CENTER);
            x.setPadding(dp(12), dp(8), dp(12), dp(8));
            x.setBackground(rounded(CARD, 14, BORDER));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(10);
            card.addView(x, lp);
        }
        TextView btn = text(s(R.string.keep_it_up), 16, Color.WHITE);
        btn.setTypeface(MEDIUM);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(gradientPill(26));
        btn.setForeground(ripple(26));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
        blp.topMargin = dp(22);
        card.addView(btn, blp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        clp.leftMargin = clp.rightMargin = dp(24);
        overlay.addView(card, clp);

        final Runnable dismiss = () -> overlay.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> root.removeView(overlay)).start();
        btn.setOnClickListener(v -> dismiss.run());
        overlay.setOnClickListener(v -> dismiss.run());

        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(220).start();
        card.setScaleX(0.85f);
        card.setScaleY(0.85f);
        card.animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new OvershootInterpolator(2f)).start();
        badge.setRotation(-20f);
        badge.animate().rotation(0f).setDuration(600).setInterpolator(new OvershootInterpolator(3f)).start();
        confetti.start();
    }

    private View milestoneBadges(int best) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int m : MILESTONES) {
            boolean earned = best >= m;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setBackground(earned ? rounded(withAlpha(STREAK_ORANGE, 0x26), 18, withAlpha(STREAK_ORANGE, 0x66))
                    : rounded(CARD, 18, BORDER));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(tinted(earned ? R.drawable.ic_flame : R.drawable.ic_lock,
                    earned ? STREAK_ORANGE : MUTED, 20));
            b.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView n = text(String.valueOf(m), 17, earned ? TEXT : MUTED);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            n.setGravity(Gravity.CENTER);
            b.addView(n);
            TextView l = text(getResources().getQuantityString(R.plurals.days_word, m), 11, MUTED);
            l.setGravity(Gravity.CENTER);
            b.addView(l);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(84));
            lp.rightMargin = dp(8);
            row.addView(b, lp);
        }
        hs.addView(row);
        return hs;
    }

    // ---------------------------------------------------------------- buddy studio

    private void openBuddyStudio() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- Stage: a lit little room where the buddy walks around wearing its current look.
        final FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(true);
        tintStage(stage);
        View spot = new View(this);
        GradientDrawable spotlight = new GradientDrawable();
        spotlight.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        spotlight.setColors(new int[]{0x33FFFFFF, 0x00FFFFFF});
        spotlight.setGradientRadius(dp(130));
        spot.setBackground(spotlight);
        stage.addView(spot, new FrameLayout.LayoutParams(-1, -1));
        View floor = new View(this);
        GradientDrawable fl = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x33000000, 0x66000000});
        fl.setCornerRadii(new float[]{0, 0, 0, 0, dp(24), dp(24), dp(24), dp(24)});
        floor.setBackground(fl);
        stage.addView(floor, new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM));

        final LinearLayout actor = new LinearLayout(this);
        actor.setOrientation(LinearLayout.VERTICAL);
        actor.setGravity(Gravity.CENTER_HORIZONTAL);
        final Buddy.BuddyView preview = new Buddy.BuddyView(this);
        preview.selfAnimate = true;
        preview.mood = 1f;
        preview.done = true;
        applyPreviewStyle(preview);
        actor.addView(preview, new LinearLayout.LayoutParams(dp(200), dp(176)));
        final TextView tag = text(BuddyStyle.name(this), 13, TEXT);
        tag.setTypeface(MEDIUM);
        tag.setPadding(dp(12), dp(3), dp(12), dp(3));
        tag.setBackground(rounded(0x99000000, 12, 0));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.topMargin = -dp(6);
        actor.addView(tag, tagLp);
        FrameLayout.LayoutParams actorLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        actorLp.bottomMargin = dp(10);
        stage.addView(actor, actorLp);
        preview.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            preview.jump();
            preview.startIdle(Buddy.BuddyView.SPIN);
        });

        // "Surprise me": random colour + random unlocked accessory.
        TextView dice = text("", 13, TEXT);
        dice.setTypeface(MEDIUM);
        dice.setText(TextUtils.concat(icon(R.drawable.ic_sparkle, 0xFFFFD166, 15), " " + s(R.string.surprise_me)));
        dice.setPadding(dp(12), dp(7), dp(12), dp(7));
        dice.setBackground(rounded(0x66000000, 16, withAlpha(Color.WHITE, 0x33)));
        dice.setForeground(ripple(16));
        FrameLayout.LayoutParams diceLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        diceLp.topMargin = dp(12);
        diceLp.rightMargin = dp(12);
        stage.addView(dice, diceLp);

        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, dp(250));
        stageLp.topMargin = dp(8);
        body.addView(stage, stageLp);

        // Walk back and forth across the stage, doing a little move at each stop.
        final java.util.Random rnd = new java.util.Random();
        final Runnable[] stroll = new Runnable[1];
        stroll[0] = () -> {
            if (!actor.isAttachedToWindow()) return;
            float range = dp(85);
            float from = actor.getTranslationX(), to = (rnd.nextFloat() * 2 - 1) * range;
            final int dir = to > from ? 1 : -1;
            preview.facing = dir;
            preview.walking = true;
            // Now and then (when happy) it dashes instead, leaning in and skidding to a stop.
            final boolean dash = preview.done && Math.abs(to - from) > dp(60) && rnd.nextInt(3) == 0;
            long ms = dash ? (long) (Math.abs(to - from) / dp(220) * 1000) + 120
                    : (long) (Math.abs(to - from) / dp(45) * 1000) + 300;
            if (dash) preview.animate().rotation(9 * dir).setDuration(120).start();
            final Runnable trail = dash ? speedTrail(stage, actor, preview, dir) : null;
            actor.animate().translationX(to).setDuration(ms)
                    .setInterpolator(dash ? new android.view.animation.AccelerateInterpolator(1.2f) : new LinearInterpolator())
                    .withEndAction(() -> {
                        if (trail != null) actor.removeCallbacks(trail);
                        if (dash) {
                            skid(stage, actor, preview, dir, () -> actor.postDelayed(stroll[0], 1500 + rnd.nextInt(1200)));
                            return;
                        }
                        preview.walking = false;
                        int[] moves = preview.done
                                ? new int[]{Buddy.BuddyView.DANCE, Buddy.BuddyView.WAVE, Buddy.BuddyView.LOOK}
                                : new int[]{Buddy.BuddyView.LOOK, Buddy.BuddyView.SIGH};
                        preview.startIdle(moves[rnd.nextInt(moves.length)]);
                        actor.postDelayed(stroll[0], 1800 + rnd.nextInt(1500));
                    }).start();
        };
        // Entrance: sprints in from off-stage, skids to a stop in a puff of dust, waves, then strolls.
        actor.setVisibility(View.INVISIBLE);
        stage.post(() -> sprintIn(stage, actor, preview, () -> actor.postDelayed(stroll[0], 1400)));

        LinearLayout moods = chipGroup(getResources().getStringArray(R.array.moods), 2, i -> {
            preview.mood = i == 0 ? 0f : i == 1 ? 0.5f : 1f;
            preview.done = i == 2;
            preview.jump();
        });
        moods.setPadding(0, dp(10), 0, 0);
        body.addView(moods);
        body.addView(levelSection());

        // ---- Name (the stage tag updates as you type).
        body.addView(sectionLabel(s(R.string.name_header)));
        final EditText name = new EditText(this);
        name.setText(BuddyStyle.name(this));
        name.setTextColor(TEXT);
        name.setHintTextColor(MUTED);
        name.setHint("Pip");
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        name.setPadding(dp(14), dp(12), dp(14), dp(12));
        name.setBackground(rounded(CARD, 14, BORDER));
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                String n = e.toString().trim();
                tag.setText(n.isEmpty() ? "Pip" : n);
            }
        });
        body.addView(name, new LinearLayout.LayoutParams(-1, -2));

        // ---- Colours.
        body.addView(sectionLabel(s(R.string.colour_header)));
        final LinearLayout swatches = new LinearLayout(this);
        HorizontalScrollView sw = new HorizontalScrollView(this);
        sw.setHorizontalScrollBarEnabled(false);
        sw.addView(swatches);
        body.addView(sw);

        // ---- Accessory collection.
        final int best = BuddyStyle.bestStreak(this);
        int owned = 0;
        for (int i = 1; i < BuddyStyle.accessoryCount(this); i++) if (best >= BuddyStyle.unlockStreak(this, i)) owned++;
        int total = BuddyStyle.accessoryCount(this) - 1;
        LinearLayout collHead = new LinearLayout(this);
        collHead.setGravity(Gravity.CENTER_VERTICAL);
        collHead.setPadding(dp(4), dp(22), dp(4), dp(8));
        TextView cl = text(s(R.string.collection), 12, MUTED);
        cl.setLetterSpacing(0.12f);
        cl.setTypeface(MEDIUM);
        collHead.addView(cl, new LinearLayout.LayoutParams(0, -2, 1));
        TextView cc = text(s(R.string.n_unlocked, owned, total), 13, TEXT);
        cc.setTypeface(MEDIUM);
        collHead.addView(cc);
        body.addView(collHead);
        LinearLayout.LayoutParams cbar = new LinearLayout.LayoutParams(-1, dp(6));
        cbar.bottomMargin = dp(12);
        body.addView(new BarView(this, (float) owned / total), cbar);
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        body.addView(grid);

        // Equipping something is a little celebration.
        final Runnable celebrateEquip = () -> {
            applyPreviewStyle(preview);
            colourSweep(stage, actor);
            tintStage(stage);
            preview.jump();
            preview.startIdle(Buddy.BuddyView.SPIN);
            preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Sounds.pop(this);
            final ConfettiView burst = new ConfettiView(this, 45);
            stage.addView(burst, new FrameLayout.LayoutParams(-1, -1));
            burst.post(burst::start);
            burst.postDelayed(() -> stage.removeView(burst), 3300);
        };
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            fillSwatches(swatches, celebrateEquip, refresh[0]);
            fillAccessoryGrid(grid, best, celebrateEquip, refresh[0]);
        };
        refresh[0].run();
        dice.setOnClickListener(v -> {
            int color;
            do {
                color = rnd.nextInt(BuddyStyle.COLORS.length);
            } while (color == BuddyStyle.colorIndex(this) && BuddyStyle.COLORS.length > 1);
            List<Integer> open = new ArrayList<>();
            for (int i = 0; i < BuddyStyle.accessoryCount(this); i++) if (best >= BuddyStyle.unlockStreak(this, i)) open.add(i);
            BuddyStyle.set(this, "buddy_color", color);
            BuddyStyle.setAccessory(this, open.get(rnd.nextInt(open.size())));
            celebrateEquip.run();
            refresh[0].run();
        });

        // ---- Personality.
        body.addView(sectionLabel(s(R.string.size_header)));
        body.addView(chipGroup(BuddyStyle.sizeNames(this), BuddyStyle.sizeIndex(this), i -> {
            BuddyStyle.set(this, "buddy_size", i);
            applyPreviewStyle(preview);
            preview.jump();
        }));
        body.addView(sectionLabel(s(R.string.speed_header)));
        body.addView(chipGroup(BuddyStyle.speedNames(this), BuddyStyle.speedIndex(this),
                i -> BuddyStyle.set(this, "buddy_speed", i)));
        body.addView(sectionLabel(s(R.string.chat_header)));
        body.addView(chipGroup(BuddyStyle.chatNames(this), BuddyStyle.chatIndex(this),
                i -> BuddyStyle.set(this, "buddy_chat", i)));

        TextView reset = text(s(R.string.reset_position), 15, ACCENT);
        reset.setTypeface(MEDIUM);
        reset.setPadding(dp(4), dp(20), dp(4), dp(8));
        reset.setOnClickListener(v -> {
            Routine.prefs(this).edit().remove("buddy_y").apply();
            Toast.makeText(this, s(R.string.reset_position_toast), Toast.LENGTH_SHORT).show();
        });
        body.addView(reset, new LinearLayout.LayoutParams(-2, -2));

        TextView close = text(s(R.string.done), 16, Color.WHITE);
        close.setTypeface(MEDIUM);
        close.setGravity(Gravity.CENTER);
        close.setBackground(gradientPill(26));
        close.setForeground(ripple(26));
        // Exit: the buddy dashes off-stage first, then the sheet closes.
        final boolean[] leaving = {false};
        final Runnable exit = () -> {
            if (leaving[0]) return;
            leaving[0] = true;
            actor.removeCallbacks(stroll[0]);
            dashOut(stage, actor, preview, d::dismiss);
        };
        close.setOnClickListener(v -> exit.run());
        if (Build.VERSION.SDK_INT >= 33) {
            d.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, exit::run);
        }
        d.setOnKeyListener((dlg, code, e) -> {
            if (code != KeyEvent.KEYCODE_BACK) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) exit.run();
            return true;
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(52));
        clp.topMargin = dp(16);
        sheet.addView(close, clp);
        d.setOnDismissListener(x -> {
            BuddyStyle.setName(this, name.getText().toString());
            UnlockWatcher.buddyChanged(this);
        });

        d.setContentView(sheet);
        fitSystemBars(sheet, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.94f));
    }

    /** Level, XP bar and the evolution roadmap (locked stages are silhouettes). */
    private View levelSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int xp = Levels.xp(this), lv = Levels.level(xp);
        int from = Levels.threshold(lv), to = Levels.threshold(lv + 1);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(22), dp(4), dp(8));
        TextView l = text(s(R.string.level_caps, lv), 12, MUTED);
        l.setLetterSpacing(0.12f);
        l.setTypeface(MEDIUM);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(text(s(R.string.xp_progress, xp - from, to - from), 13, TEXT));
        box.addView(head);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(6));
        blp.bottomMargin = dp(6);
        box.addView(new BarView(this, (float) (xp - from) / (to - from)), blp);
        TextView how = text(s(R.string.xp_how, Levels.XP_PER_HABIT, Levels.XP_DAY_BONUS), 12, MUTED);
        how.setPadding(dp(4), 0, 0, dp(10));
        box.addView(how);

        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < Levels.EVOLUTIONS.length; i++) {
            int at = Levels.EVOLUTIONS[i];
            boolean open = lv >= at;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(6), dp(8), dp(6), dp(10));
            tile.setBackground(rounded(open ? withAlpha(0xFFFFD166, 0x1A) : CARD, 18,
                    open ? withAlpha(0xFFFFD166, 0x80) : BORDER));
            Buddy.BuddyView mini = new Buddy.BuddyView(this);
            mini.mood = 1f;
            mini.done = open;
            mini.level = at;
            mini.setScale(0.75f);
            mini.color = BuddyStyle.COLORS[BuddyStyle.colorIndex(this)];
            mini.silhouette = !open;
            mini.selfAnimate = open;
            mini.setClickable(false);
            tile.addView(mini, new LinearLayout.LayoutParams(dp(76), dp(70)));
            TextView name = text(open ? getResources().getStringArray(R.array.evolution_names)[i] : "???", 13,
                    open ? TEXT : MUTED);
            name.setTypeface(MEDIUM);
            tile.addView(name);
            tile.addView(text(s(R.string.level_n, at), 11, open ? MUTED : STREAK_ORANGE));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) tlp.leftMargin = dp(8);
            row.addView(tile, tlp);
        }
        box.addView(row);
        return box;
    }

    /** Stage backdrop takes on the buddy's colour. */
    // ---- stage animations for the Personalize screen

    /** Where the buddy's feet are, in stage coordinates. */
    private float[] feet(View actor, View preview) {
        return new float[]{actor.getLeft() + actor.getTranslationX() + actor.getWidth() / 2f,
                actor.getTop() + preview.getBottom() - dp(10)};
    }

    /** Runs in from beyond the left edge, then skids. */
    private void sprintIn(final FrameLayout stage, final LinearLayout actor, final Buddy.BuddyView preview,
                          final Runnable then) {
        if (stage.getWidth() == 0 || actor.getWidth() == 0) {
            // Not laid out yet: try again next frame.
            stage.post(() -> sprintIn(stage, actor, preview, then));
            return;
        }
        float start = -(stage.getWidth() / 2f + actor.getWidth() / 2f + dp(10));
        actor.setTranslationX(start);
        actor.setVisibility(View.VISIBLE);
        preview.setPivotX(preview.getWidth() / 2f);
        preview.setPivotY(preview.getHeight() * 0.92f);
        preview.facing = 1;
        preview.walking = true;
        preview.setRotation(11);
        final Runnable trail = speedTrail(stage, actor, preview, 1);
        actor.animate().translationX(-dp(28)).setDuration(520)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    actor.removeCallbacks(trail);
                    skid(stage, actor, preview, 1, then);
                }).start();
    }

    /**
     * Brakes: legs lock, it leans back and slides on (decelerating) while dust puffs fly off its feet,
     * then it rocks upright with a little hop and waves.
     */
    private void skid(final FrameLayout stage, final LinearLayout actor, final Buddy.BuddyView preview,
                      final int dir, final Runnable then) {
        preview.walking = false;
        preview.animate().rotation(-13 * dir).setDuration(140).start();
        final float to = actor.getTranslationX() + dir * dp(34);
        final Runnable[] puff = new Runnable[1];
        puff[0] = () -> {
            float[] f = feet(actor, preview);
            dustPuff(stage, f[0] - dir * dp(12), f[1], -dir);
            actor.postDelayed(puff[0], 55);
        };
        puff[0].run();
        actor.animate().translationX(to).setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f))
                .withEndAction(() -> {
                    actor.removeCallbacks(puff[0]);
                    preview.animate().rotation(0).setDuration(420)
                            .setInterpolator(new OvershootInterpolator(3f)).start();
                    preview.jump();
                    preview.startIdle(Buddy.BuddyView.WAVE);
                    if (then != null) then.run();
                }).start();
    }

    /** Crouch, kick off in a puff of dust and sprint out past the right edge. */
    private void dashOut(final FrameLayout stage, final LinearLayout actor, final Buddy.BuddyView preview,
                         final Runnable then) {
        actor.animate().cancel();
        preview.setPivotX(preview.getWidth() / 2f);
        preview.setPivotY(preview.getHeight() * 0.92f);
        preview.facing = 1;
        float[] f = feet(actor, preview);
        for (int i = 0; i < 3; i++) dustPuff(stage, f[0] - dp(8), f[1], -1);
        preview.animate().scaleY(0.86f).scaleX(1.1f).setDuration(110).withEndAction(() -> {
            preview.animate().scaleX(1f).scaleY(1f).rotation(13).setDuration(140).start();
            preview.walking = true;
            final Runnable trail = speedTrail(stage, actor, preview, 1);
            float end = stage.getWidth() / 2f + actor.getWidth() / 2f + dp(20);
            actor.animate().translationX(end).setDuration(360)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.6f))
                    .withEndAction(() -> {
                        actor.removeCallbacks(trail);
                        then.run();
                    }).start();
        }).start();
    }

    /** Spawns speed lines behind the buddy while it runs; remove the returned runnable's callbacks to stop. */
    private Runnable speedTrail(final FrameLayout stage, final View actor, final View preview, final int dir) {
        final java.util.Random rnd = new java.util.Random();
        final Runnable[] r = new Runnable[1];
        r[0] = () -> {
            float[] f = feet(actor, preview);
            View line = new View(this);
            line.setBackground(rounded(withAlpha(Color.WHITE, 0x99), 2, 0));
            int w = dp(18 + rnd.nextInt(22));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, dp(3));
            stage.addView(line, lp);
            line.setX(f[0] - dir * (dp(36) + rnd.nextInt(dp(20))) - (dir > 0 ? w : 0));
            line.setY(f[1] - dp(18 + rnd.nextInt(70)));
            line.animate().translationXBy(-dir * dp(30)).alpha(0f).scaleX(0.3f).setDuration(320)
                    .withEndAction(() -> stage.removeView(line)).start();
            actor.postDelayed(r[0], 45);
        };
        r[0].run();
        return r[0];
    }

    /** A little puff of dust kicked up at (x, y), flying in direction {@code dir} and up. */
    private void dustPuff(final FrameLayout stage, float x, float y, int dir) {
        final java.util.Random rnd = new java.util.Random();
        View p = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(withAlpha(0xFFE8E2FF, 0x88));
        p.setBackground(g);
        int size = dp(6 + rnd.nextInt(9));
        stage.addView(p, new FrameLayout.LayoutParams(size, size));
        p.setX(x - size / 2f);
        p.setY(y - size / 2f);
        p.animate().translationXBy(dir * dp(14 + rnd.nextInt(26))).translationYBy(-dp(6 + rnd.nextInt(16)))
                .scaleX(2f).scaleY(2f).alpha(0f).setDuration(420 + rnd.nextInt(200))
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> stage.removeView(p)).start();
    }

    /** New colour washes across the stage from the buddy in a growing circle. */
    private void colourSweep(final FrameLayout stage, View actor) {
        int col = BuddyStyle.COLORS[BuddyStyle.colorIndex(this)];
        if (col == 0) col = ACCENT;
        final View wash = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(withAlpha(col, 0x66));
        g.setCornerRadius(dp(24));
        wash.setBackground(g);
        stage.addView(wash, 2, new FrameLayout.LayoutParams(-1, -1)); // above spotlight + floor, below the buddy
        wash.post(() -> {
            if (!wash.isAttachedToWindow()) return;
            int cx = (int) (actor.getLeft() + actor.getTranslationX() + actor.getWidth() / 2f);
            int cy = actor.getTop() + actor.getHeight() / 2;
            float r = (float) Math.hypot(Math.max(cx, stage.getWidth() - cx), Math.max(cy, stage.getHeight() - cy));
            Animator a = android.view.ViewAnimationUtils.createCircularReveal(wash, cx, cy, 0, r);
            a.setDuration(420);
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator x) {
                    wash.animate().alpha(0f).setDuration(380).withEndAction(() -> stage.removeView(wash)).start();
                }
            });
            a.start();
        });
    }

    private void tintStage(View stage) {
        int col = BuddyStyle.COLORS[BuddyStyle.colorIndex(this)];
        if (col == 0) col = ACCENT;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(col, 0x55), withAlpha(col, 0x14)});
        g.setCornerRadius(dp(24));
        g.setStroke(dp(1), withAlpha(col, 0x66));
        stage.setBackground(g);
    }

    /** Big, tappable card in Settings: your buddy live, its name, and progress to the next accessory. */
    private View buddyHeroCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{withAlpha(ACCENT, 0x40), withAlpha(ACCENT2, 0x1A)});
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), withAlpha(ACCENT, 0x80));
        card.setBackground(bg);
        card.setForeground(ripple(22));

        final Buddy.BuddyView live = new Buddy.BuddyView(this);
        live.selfAnimate = true;
        List<String> items = Routine.items(this);
        Set<String> checked = Routine.checked(this);
        int n = 0;
        for (String i : items) if (checked.contains(i)) n++;
        live.mood = items.isEmpty() ? 0 : (float) n / items.size();
        live.done = Routine.isDoneToday(this);
        live.setScale(1.15f);
        live.color = BuddyStyle.COLORS[BuddyStyle.colorIndex(this)];
        live.accessory = BuddyStyle.accessoryDef(this);
        live.level = Levels.level(Levels.xp(this));
        card.addView(live, new LinearLayout.LayoutParams(dp(104), dp(108)));
        // Keep it lively: a little idle move every few seconds while the card is on screen.
        final int[] moves = {Buddy.BuddyView.WAVE, Buddy.BuddyView.LOOK, Buddy.BuddyView.DANCE};
        final java.util.Random rnd = new java.util.Random();
        live.post(new Runnable() {
            @Override
            public void run() {
                if (!live.isAttachedToWindow()) return;
                live.startIdle(live.done ? moves[rnd.nextInt(moves.length)] : Buddy.BuddyView.LOOK);
                live.postDelayed(this, 3500);
            }
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), 0, 0, 0);

        // Name, then the two quick switches: show/hide, and snooze for an hour.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(BuddyStyle.name(this), 22, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        final ImageView eye = heroIcon();
        final ImageView zzz = heroIcon();
        final Runnable paint = () -> {
            boolean shown = Routine.buddy(this);
            long until = Routine.prefs(this).getLong("buddy_hidden_until", 0);
            boolean snoozed = shown && until > System.currentTimeMillis();
            String nm = BuddyStyle.name(this);
            eye.setImageDrawable(tinted(shown ? R.drawable.ic_eye : R.drawable.ic_eye_off, TEXT, 20));
            eye.setContentDescription(s(R.string.show_buddy));
            stateDesc(eye, s(shown ? R.string.on : R.string.off));
            zzz.setImageDrawable(tinted(R.drawable.ic_snooze, snoozed ? Color.WHITE : TEXT, 20));
            zzz.setBackground(snoozed ? rounded(ACCENT, 18, 0) : rounded(withAlpha(BG, 0x80), 18, BORDER));
            zzz.setContentDescription(s(R.string.snooze_name, nm));
            stateDesc(zzz, snoozed ? s(R.string.back_at, new SimpleDateFormat("HH:mm",
                    Locale.getDefault()).format(new Date(until))) : "");
            // Snoozing only means something while the buddy is shown.
            zzz.setEnabled(shown);
            zzz.setAlpha(shown ? 1f : 0.35f);
            live.setAlpha(shown ? (snoozed ? 0.55f : 1f) : 0.35f);
        };
        eye.setOnClickListener(v -> {
            boolean on = !Routine.buddy(this);
            Routine.setBuddy(this, on);
            if (on) Routine.prefs(this).edit().putLong("buddy_hidden_until", 0).apply();
            UnlockWatcher.buddyChanged(this);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            paint.run();
            Toast.makeText(this, s(on ? R.string.buddy_shown : R.string.buddy_hidden, BuddyStyle.name(this)),
                    Toast.LENGTH_SHORT).show();
        });
        zzz.setOnClickListener(v -> {
            boolean snoozed = Routine.prefs(this).getLong("buddy_hidden_until", 0) > System.currentTimeMillis();
            Routine.prefs(this).edit().putLong("buddy_hidden_until",
                    snoozed ? 0 : System.currentTimeMillis() + Buddy.SNOOZE_MS).apply();
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            paint.run();
            Toast.makeText(this, s(snoozed ? R.string.is_back : R.string.back_in_hour, BuddyStyle.name(this)),
                    Toast.LENGTH_SHORT).show();
        });
        paint.run();
        top.addView(eye);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(dp(36), dp(36));
        zlp.leftMargin = dp(6);
        top.addView(zzz, zlp);
        col.addView(top);

        TextView sub = text("", 13, ACCENT);
        sub.setTypeface(MEDIUM);
        sub.setText(TextUtils.concat(s(R.string.personalize) + "  ", icon(R.drawable.ic_arrow, ACCENT, 14)));
        sub.setPadding(0, 0, 0, dp(12));
        col.addView(sub);

        // One progress bar: XP toward the next level.
        int xp = Levels.xp(this), lv = Levels.level(xp);
        int from = Levels.threshold(lv), to = Levels.threshold(lv + 1);
        LinearLayout lvRow = new LinearLayout(this);
        lvRow.setGravity(Gravity.BOTTOM);
        TextView lvText = text(s(R.string.level_n, lv), 13, TEXT);
        lvText.setTypeface(MEDIUM);
        lvRow.addView(lvText, new LinearLayout.LayoutParams(0, -2, 1));
        lvRow.addView(text(s(R.string.xp_progress, xp - from, to - from), 12, MUTED));
        col.addView(lvRow);
        BarView xpBar = new BarView(this, (float) (xp - from) / (to - from), ACCENT);
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(-1, dp(8));
        xlp.topMargin = dp(6);
        col.addView(xpBar, xlp);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        card.setOnClickListener(v -> {
            settings.dismiss();
            openBuddyStudio();
        });
        return card;
    }

    private void applyPreviewStyle(Buddy.BuddyView v) {
        // Preview is drawn larger than life; size setting scales it relative to that.
        v.setScale(1.4f * BuddyStyle.SIZES[BuddyStyle.sizeIndex(this)]);
        v.color = BuddyStyle.COLORS[BuddyStyle.colorIndex(this)];
        v.accessory = BuddyStyle.accessoryDef(this);
        v.level = Levels.level(Levels.xp(this));
        v.invalidate();
    }

    private void fillSwatches(final LinearLayout row, final Runnable equip, final Runnable refresh) {
        row.removeAllViews();
        int selected = BuddyStyle.colorIndex(this);
        for (int i = 0; i < BuddyStyle.COLORS.length; i++) {
            final int idx = i;
            final boolean on = i == selected;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout dotBox = new FrameLayout(this);
            View dot = new View(this);
            GradientDrawable g = i == 0
                    ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6B7280, ACCENT, GOOD})
                    : new GradientDrawable();
            if (i != 0) g.setColor(BuddyStyle.COLORS[i]);
            g.setShape(GradientDrawable.OVAL);
            if (on) g.setStroke(dp(3), TEXT);
            dot.setBackground(g);
            dotBox.addView(dot, new FrameLayout.LayoutParams(-1, -1));
            if (on) {
                dotBox.addView(iconView(R.drawable.ic_check, Color.WHITE),
                        new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            }
            int size = on ? 56 : 48;
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(size), dp(size));
            dlp.topMargin = on ? 0 : dp(4);
            dlp.bottomMargin = on ? 0 : dp(4);
            cell.addView(dotBox, dlp);
            TextView l = text(BuddyStyle.colorNames(this)[i], 11, on ? TEXT : MUTED);
            if (on) l.setTypeface(MEDIUM);
            l.setPadding(0, dp(5), 0, 0);
            cell.addView(l);
            cell.setOnClickListener(v -> {
                if (idx == BuddyStyle.colorIndex(this)) return;
                BuddyStyle.set(this, "buddy_color", idx);
                equip.run();
                refresh.run();
            });
            row.addView(cell, new LinearLayout.LayoutParams(dp(70), -2));
        }
    }

    /** Three-column collection grid: owned accessories animate, locked ones are silhouettes with progress. */
    private void fillAccessoryGrid(final LinearLayout grid, final int best, final Runnable equip,
                                   final Runnable refresh) {
        grid.removeAllViews();
        int selected = BuddyStyle.accessory(this);
        // The closest locked reward (the list is in display order, not necessarily by streak).
        int next = -1;
        for (int i = 1; i < BuddyStyle.accessoryCount(this); i++) {
            int need = BuddyStyle.unlockStreak(this, i);
            if (need > best && (next < 0 || need < BuddyStyle.unlockStreak(this, next))) next = i;
        }
        List<Accessories.Def> defs = Accessories.all(this);
        LinearLayout row = null;
        int n = defs.size();
        String[] accNames = BuddyStyle.accessoryNames(this);
        for (int i = 0; i < n; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.bottomMargin = dp(8);
                grid.addView(row, rlp);
            }
            final int idx = i;
            final int need = BuddyStyle.unlockStreak(this, i);
            final boolean open = best >= need;
            final boolean equipped = i == selected;
            FrameLayout tileWrap = new FrameLayout(this);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(6), dp(10), dp(6), dp(10));
            tile.setBackground(equipped ? rounded(withAlpha(ACCENT, 0x30), 18, ACCENT)
                    : i == next ? rounded(withAlpha(STREAK_ORANGE, 0x14), 18, withAlpha(STREAK_ORANGE, 0x80))
                    : rounded(CARD, 18, BORDER));
            tile.setForeground(ripple(18));

            FrameLayout art = new FrameLayout(this);
            Buddy.BuddyView mini = new Buddy.BuddyView(this);
            mini.mood = 1f;
            mini.setScale(0.85f);
            mini.accessory = i == 0 ? null : defs.get(i);
            mini.color = BuddyStyle.COLORS[BuddyStyle.colorIndex(this)];
            mini.silhouette = !open;
            mini.selfAnimate = open; // owned ones breathe and blink
            mini.setClickable(false);
            art.addView(mini, new FrameLayout.LayoutParams(dp(70), dp(76), Gravity.CENTER));
            if (!open) {
                ImageView lock = iconView(R.drawable.ic_lock, TEXT);
                lock.setPadding(dp(4), dp(4), dp(4), dp(4));
                lock.setBackground(rounded(BG, 11, BORDER));
                art.addView(lock, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.BOTTOM | Gravity.END));
            }
            tile.addView(art, new LinearLayout.LayoutParams(-1, dp(76)));

            TextView l = text(open ? accNames[i] : "???", 13, open ? TEXT : MUTED);
            l.setTypeface(MEDIUM);
            l.setGravity(Gravity.CENTER);
            tile.addView(l);
            if (equipped) {
                TextView eq = text(s(R.string.equipped), 11, ACCENT);
                eq.setGravity(Gravity.CENTER);
                tile.addView(eq);
            } else if (open) {
                TextView eq = text(need == 0 ? s(R.string.default_) : s(R.string.n_day_reward, need), 11, MUTED);
                eq.setGravity(Gravity.CENTER);
                tile.addView(eq);
            } else {
                int toGo = need - best;
                TextView req = text(q(R.plurals.days_to_go, toGo), 11, STREAK_ORANGE);
                req.setGravity(Gravity.CENTER);
                tile.addView(req);
                BarView bar = new BarView(this, (float) best / need);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(5));
                blp.topMargin = dp(6);
                blp.leftMargin = blp.rightMargin = dp(8);
                tile.addView(bar, blp);
            }
            tileWrap.addView(tile, new FrameLayout.LayoutParams(-1, -1));

            if (i == next) {
                // Pulsing "NEXT" badge on the closest locked reward.
                TextView badge = text(s(R.string.next_badge), 10, Color.WHITE);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setLetterSpacing(0.1f);
                badge.setPadding(dp(7), dp(2), dp(7), dp(2));
                badge.setBackground(rounded(STREAK_ORANGE, 8, 0));
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                blp.topMargin = dp(6);
                blp.rightMargin = dp(6);
                tileWrap.addView(badge, blp);
                ValueAnimator pulse = ValueAnimator.ofFloat(1f, 0.45f);
                pulse.setDuration(900);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.addUpdateListener(a -> {
                    if (!badge.isAttachedToWindow() && a.getCurrentPlayTime() > 2000) a.cancel();
                    badge.setAlpha((float) a.getAnimatedValue());
                });
                pulse.start();
            }

            tile.setOnClickListener(v -> {
                if (!open) {
                    Toast.makeText(this, s(R.string.mystery_accessory, need),
                            Toast.LENGTH_SHORT).show();
                    v.animate().translationX(dp(5)).setDuration(50).withEndAction(() ->
                            v.animate().translationX(0).setDuration(250)
                                    .setInterpolator(new OvershootInterpolator(6f)).start()).start();
                    return;
                }
                if (idx == BuddyStyle.accessory(this)) return;
                BuddyStyle.setAccessory(this, idx);
                equip.run();
                refresh.run();
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 3 != 0) tlp.leftMargin = dp(8);
            row.addView(tileWrap, tlp);
        }
        // Keep tiles the same width when the last row isn't full.
        for (int i = n; i % 3 != 0; i++) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1);
            slp.leftMargin = dp(8);
            row.addView(new View(this), slp);
        }
        // Pad the last row so tiles keep their width.
        while (n % 3 != 0) {
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
            tlp.leftMargin = dp(8);
            row.addView(new View(this), tlp);
            n++;
        }
    }

    /** Single-choice pills in a row. */
    private LinearLayout chipGroup(final String[] labels, int selected, final java.util.function.IntConsumer onPick) {
        final LinearLayout row = new LinearLayout(this);
        final TextView[] chips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView chip = text(labels[i], 14, TEXT);
            chip.setTypeface(MEDIUM);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(8), dp(10), dp(8), dp(10));
            chip.setForeground(ripple(20));
            chips[i] = chip;
            chip.setOnClickListener(v -> {
                for (int k = 0; k < chips.length; k++) styleChip(chips[k], k == idx);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onPick.accept(idx);
            });
            styleChip(chip, i == selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(8);
            row.addView(chip, lp);
        }
        return row;
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setBackground(on ? rounded(withAlpha(ACCENT, 0x26), 20, ACCENT) : rounded(CARD, 20, BORDER));
        chip.setTextColor(on ? TEXT : MUTED);
    }

    // ---------------------------------------------------------------- onboarding

    private Dialog onboarding;
    private Runnable onboardRefresh;


    /** First launch: meet the buddy, how it works, permissions, first habits. */
    private void openOnboarding() {
        if (onboarding != null && onboarding.isShowing()) return;
        final Dialog d = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        onboarding = d;
        d.setCancelable(false);
        final FrameLayout rootView = new FrameLayout(this);
        rootView.setBackgroundColor(BG);
        final FrameLayout pageHost = new FrameLayout(this);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(-1, -1);
        hostLp.bottomMargin = dp(96);
        rootView.addView(pageHost, hostLp);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(dp(24), 0, dp(24), dp(24));
        final LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        nav.addView(dots, new LinearLayout.LayoutParams(-1, dp(24)));
        final TextView next = text("", 17, Color.WHITE);
        next.setTypeface(MEDIUM);
        next.setGravity(Gravity.CENTER);
        next.setBackground(gradientPill(28));
        next.setForeground(ripple(28));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(56));
        nlp.topMargin = dp(8);
        nav.addView(next, nlp);
        rootView.addView(nav, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        final String[] name = {BuddyStyle.name(this)};
        final String[] starters = getResources().getStringArray(R.array.starter_habits);
        final Set<String> picked = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                starters[0], starters[1], starters[2]));
        final int pages = 4;
        final int[] page = {0};
        final Runnable[] show = new Runnable[1];
        show[0] = () -> {
            pageHost.removeAllViews();
            View v;
            switch (page[0]) {
                case 0: v = onboardMeet(name); break;
                case 1: v = onboardHow(name[0]); break;
                case 2: v = onboardSetup(); break;
                default: v = onboardHabits(picked); break;
            }
            pageHost.addView(v, new FrameLayout.LayoutParams(-1, -1));
            v.setAlpha(0f);
            v.setTranslationX(dp(40));
            v.animate().alpha(1f).translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
            dots.removeAllViews();
            for (int i = 0; i < pages; i++) {
                View dot = new View(this);
                dot.setBackground(rounded(i == page[0] ? ACCENT : BORDER, 4, 0));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(i == page[0] ? 22 : 8), dp(8));
                dl.leftMargin = dl.rightMargin = dp(3);
                dots.addView(dot, dl);
            }
            next.setText(page[0] == 0 ? s(R.string.hi_name, name[0]) : s(page[0] == pages - 1 ? R.string.lets_go : R.string.next));
        };
        onboardRefresh = () -> {
            if (page[0] == 2) show[0].run();
        };
        next.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (page[0] == 0) BuddyStyle.setName(this, name[0]);
            if (page[0] < pages - 1) {
                page[0]++;
                show[0].run();
                return;
            }
            if (picked.isEmpty()) {
                Toast.makeText(this, s(R.string.pick_one_habit), Toast.LENGTH_SHORT).show();
                return;
            }
            // Timers for habits like "Exercise 20 min".
            Map<String, Integer> timers = new java.util.HashMap<>();
            for (String h : picked) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*min").matcher(h);
                if (m.find()) timers.put(h, Integer.parseInt(m.group(1)));
            }
            Routine.setTimers(this, timers);
            Routine.setItems(this, TextUtils.join("\n", picked));
            Routine.prefs(this).edit().putBoolean("onboarded", true).apply();
            onboardRefresh = null;
            d.dismiss();
            build();
            UnlockWatcher.buddyChanged(this);
            Toast.makeText(this, s(R.string.onboard_done_toast), Toast.LENGTH_LONG).show();
        });
        show[0].run();
        d.setContentView(rootView);
        fitSystemBars(rootView, true);
        d.getWindow().setStatusBarColor(BG);
        d.getWindow().setNavigationBarColor(BG);
        d.show();
    }

    private View onboardPage(String title, String sub) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(24), dp(48), dp(24), dp(12));
        TextView t = text(title, 30, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        v.addView(t);
        if (sub != null) {
            TextView st = text(sub, 16, MUTED);
            st.setPadding(0, dp(8), 0, dp(20));
            v.addView(st);
        }
        return v;
    }

    private View onboardMeet(final String[] name) {
        LinearLayout v = (LinearLayout) onboardPage(s(R.string.meet_buddy), s(R.string.meet_buddy_sub));
        FrameLayout stage = new FrameLayout(this);
        tintStage(stage);
        final Buddy.BuddyView b = new Buddy.BuddyView(this);
        b.selfAnimate = true;
        b.mood = 1f;
        b.done = true;
        b.setScale(1.6f);
        stage.addView(b, new FrameLayout.LayoutParams(dp(220), dp(200), Gravity.CENTER));
        b.post(new Runnable() {
            @Override
            public void run() {
                if (!b.isAttachedToWindow()) return;
                b.startIdle(Buddy.BuddyView.WAVE);
                b.postDelayed(this, 2600);
            }
        });
        b.setOnClickListener(x -> {
            b.jump();
            b.startIdle(Buddy.BuddyView.SPIN);
        });
        v.addView(stage, new LinearLayout.LayoutParams(-1, dp(240)));
        TextView l = text(s(R.string.give_name), 14, MUTED);
        l.setPadding(dp(4), dp(20), 0, dp(8));
        v.addView(l);
        EditText et = new EditText(this);
        et.setText(name[0]);
        et.setTextColor(TEXT);
        et.setTextSize(18);
        et.setSingleLine(true);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(16)});
        et.setPadding(dp(16), dp(14), dp(16), dp(14));
        et.setBackground(rounded(CARD, 16, BORDER));
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int bb, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int bb, int c) {}
            @Override public void afterTextChanged(Editable e) {
                String n = e.toString().trim();
                name[0] = n.isEmpty() ? "Pip" : n;
            }
        });
        v.addView(et, new LinearLayout.LayoutParams(-1, -2));
        return v;
    }

    private View onboardHow(String name) {
        LinearLayout v = (LinearLayout) onboardPage(s(R.string.how_it_works), null);
        Object[][] rows = {
                {R.drawable.ic_check, GOOD, s(R.string.how_1), s(R.string.how_1_sub)},
                {R.drawable.ic_lock, ACCENT, s(R.string.how_2), s(R.string.how_2_sub)},
                {R.drawable.ic_flame, STREAK_ORANGE, s(R.string.how_3), s(R.string.how_3_sub, name)},
                {R.drawable.ic_sparkle, 0xFFFFD166, s(R.string.how_4, name), s(R.string.how_4_sub, name)},
        };
        for (Object[] row : rows) {
            LinearLayout item = new LinearLayout(this);
            item.setPadding(0, dp(14), 0, dp(14));
            FrameLayout iconBox = new FrameLayout(this);
            iconBox.setBackground(rounded(withAlpha((Integer) row[1], 0x26), 16, 0));
            iconBox.addView(iconView((Integer) row[0], (Integer) row[1]),
                    new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
            item.addView(iconBox, new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(16), 0, 0, 0);
            TextView t = text((String) row[2], 17, TEXT);
            t.setTypeface(MEDIUM);
            col.addView(t);
            TextView st = text((String) row[3], 14, MUTED);
            st.setPadding(0, dp(3), 0, 0);
            col.addView(st);
            item.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            v.addView(item);
        }
        return v;
    }

    private View onboardSetup() {
        if (!BuildFlags.ROOT) {
            LinearLayout v = (LinearLayout) onboardPage(s(R.string.quick_setup), s(R.string.quick_setup_sub));
            View card = setupCard();
            v.addView(card);
            if (Perms.requiredGranted(this)) {
                TextView ok = text(s(R.string.all_set), 16, GOOD);
                ok.setTypeface(MEDIUM);
                ok.setPadding(dp(4), dp(16), 0, 0);
                v.addView(ok);
            }
            return v;
        }
        LinearLayout v = (LinearLayout) onboardPage(s(R.string.quick_setup),
                s(R.string.quick_setup_root, s(R.string.app_name)));
        final TextView status = text("", 15, MUTED);
        status.setPadding(dp(4), dp(14), 0, 0);
        TextView grant = text(s(R.string.grant_root), 16, Color.WHITE);
        grant.setTypeface(MEDIUM);
        grant.setGravity(Gravity.CENTER);
        grant.setBackground(gradientPill(26));
        grant.setForeground(ripple(26));
        grant.setOnClickListener(x -> {
            status.setText(s(R.string.waiting_root));
            Gate.sync(this, true, ok -> {
                status.setText(ok ? s(R.string.root_granted) : s(R.string.no_root_yet, s(R.string.app_name)));
                status.setTextColor(ok ? GOOD : WARN);
            });
        });
        v.addView(grant, new LinearLayout.LayoutParams(-1, dp(52)));
        v.addView(status);
        return v;
    }

    private View onboardHabits(final Set<String> picked) {
        LinearLayout v = (LinearLayout) onboardPage(s(R.string.first_habits), s(R.string.first_habits_sub));
        ScrollView sc = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        for (final String h : getResources().getStringArray(R.array.starter_habits)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            final CheckMark mark = new CheckMark(this);
            mark.setChecked(picked.contains(h), false);
            row.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
            final TextView t = text(h, 16, TEXT);
            t.setPadding(dp(14), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            final Runnable style = () -> row.setBackground(picked.contains(h)
                    ? rounded(withAlpha(ACCENT, 0x1F), 16, withAlpha(ACCENT, 0x80)) : rounded(CARD, 16, BORDER));
            style.run();
            row.setForeground(ripple(16));
            row.setOnClickListener(x -> {
                boolean on = !picked.contains(h);
                if (on) picked.add(h); else picked.remove(h);
                mark.setChecked(on, true);
                style.run();
                x.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
        v.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        return v;
    }

    // ---------------------------------------------------------------- backup

    private static final int REQ_EXPORT = 41, REQ_IMPORT = 42;

    /** The app's current language, by its own name ("Español"). */
    private String currentLanguage() {
        Locale l = getResources().getConfiguration().getLocales().get(0);
        String n = l.getDisplayLanguage(l);
        return n.isEmpty() ? s(R.string.language_sub) : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private View dataSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionLabel(s(R.string.your_data)));
        View export = navRow(s(R.string.export_backup), s(R.string.export_backup_sub), v -> {
            settings.dismiss();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "mingui-backup-" + Routine.today() + ".json");
            startActivityForResult(i, REQ_EXPORT);
        });
        View restore = navRow(s(R.string.restore_backup), s(R.string.restore_backup_sub), v -> {
            Runnable pick = () -> {
                settings.dismiss();
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                startActivityForResult(i, REQ_IMPORT);
            };
            // Restoring replaces today's state too, so before you're done it counts as loosening.
            if (Routine.isConfigured(this)) whenLoosenAllowed(pick); else pick.run();
        });
        box.addView(group(export, restore));
        return box;
    }

    /** In-app language list: the phone's language, then each translation by its own name. */
    private void openLanguagePicker() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        TextView t = text(s(R.string.language), 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(14));
        sheet.addView(t);

        String cur = Lang.current(this);
        View[] rows = new View[Lang.TAGS.length];
        for (int i = 0; i < rows.length; i++) {
            final String tag = Lang.TAGS[i];
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setForeground(ripple(18));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(tag.isEmpty() ? s(R.string.language_phone) : Lang.nativeName(tag), 16, TEXT);
            name.setTypeface(MEDIUM);
            col.addView(name);
            if (tag.isEmpty()) {
                Locale sys = android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
                TextView sub = text(Lang.nativeName(sys.getLanguage()), 13, MUTED);
                sub.setPadding(0, dp(2), 0, 0);
                col.addView(sub);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            boolean on = tag.equals(cur);
            if (on) row.addView(iconView(R.drawable.ic_check, ACCENT), new LinearLayout.LayoutParams(dp(24), dp(24)));
            stateDesc(row, on ? s(R.string.selected) : "");
            row.setOnClickListener(v -> {
                d.dismiss();
                Lang.set(this, tag);
            });
            rows[i] = row;
        }
        sheet.addView(group(rows));
        showSheet(d, sheet);
    }

    /** Card row with a title, subtitle and chevron. */
    private View navRow(String title, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(rounded(CARD, 18, BORDER));
        row.setForeground(ripple(18));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 16, TEXT);
        t.setTypeface(MEDIUM);
        col.addView(t);
        TextView st = text(sub, 13, MUTED);
        st.setPadding(0, dp(2), dp(8), 0);
        col.addView(st);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(iconView(R.drawable.ic_chevron, MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
        row.setOnClickListener(click);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        try {
            if (req == REQ_EXPORT) {
                try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                    out.write(Backup.export(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Toast.makeText(this, s(R.string.backup_saved), Toast.LENGTH_SHORT).show();
            } else if (req == REQ_IMPORT) {
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                        getContentResolver().openInputStream(uri), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line).append('\n');
                }
                Backup.restore(this, sb.toString());
                Routine.prefs(this).edit().putBoolean("onboarded", true).apply();
                Gate.sync(this, true, null);
                UnlockWatcher.buddyChanged(this);
                RoutineWidget.updateAll(this);
                build();
                Toast.makeText(this, s(R.string.backup_restored), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, s(R.string.didnt_work, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- smart prompts

    private View promptsSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(sectionLabel(s(R.string.smart_prompts)));
        final boolean done = Routine.isDoneToday(this);
        if (!done) {
            TextView note = text("", 13, MUTED);
            note.setText(TextUtils.concat(icon(R.drawable.ic_lock, MUTED, 13), s(R.string.asks_first)));
            note.setPadding(dp(4), 0, 0, dp(10));
            box.addView(note);
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 18, BORDER));
        card.addView(cycleRow(s(R.string.cooldown), s(R.string.cooldown_sub),
                Prompts.COOLDOWN_CHOICES, Prompts.cooldownMinutes(this), "prompt_cooldown",
                v -> v == 0 ? s(R.string.off) : s(R.string.n_min, v)));
        card.addView(divider());
        card.addView(cycleRow(s(R.string.daily_limit), s(R.string.daily_limit_sub),
                Prompts.CAP_CHOICES, Prompts.dailyCap(this), "prompt_cap",
                v -> v == 0 ? s(R.string.no_limit) : s(R.string.per_day, v)));
        card.addView(divider());
        card.addView(cycleRow(s(R.string.firmer_after), s(R.string.firmer_after_sub),
                Prompts.FIRM_CHOICES, Prompts.firmHour(this), "prompt_firm_hour",
                v -> v < 0 ? s(R.string.off) : Prompts.hhmm(v * 60)));
        card.addView(divider());
        card.addView(cycleRow(s(R.string.weak_hour), s(R.string.weak_hour_sub),
                new int[]{1, 0}, Prompts.peakNudge(this) ? 1 : 0, null,
                v -> s(v == 1 ? R.string.on : R.string.off)));
        card.addView(divider());
        card.addView(nudgeRow());
        box.addView(card);

        TextView foot = text(s(R.string.prompts_foot), 12, MUTED);
        foot.setPadding(dp(4), dp(8), 0, 0);
        box.addView(foot);
        return box;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    /** A setting row whose value cycles through {@code choices} on tap. {@code key} null = weak-hour toggle. */
    private View cycleRow(String titleText, String subText, final int[] choices, int current, final String key,
                          final java.util.function.IntFunction<String> fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setForeground(ripple(18));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleText, 15, TEXT);
        title.setTypeface(MEDIUM);
        col.addView(title);
        TextView sub = text(subText, 12, MUTED);
        sub.setPadding(0, dp(1), dp(8), 0);
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView value = text(fmt.apply(current), 14, ACCENT);
        value.setTypeface(MEDIUM);
        value.setTag(current);
        row.addView(value);
        row.setOnClickListener(v -> whenLoosenAllowed(() -> {
            int cur = (Integer) value.getTag(), next = choices[0];
            for (int i = 0; i < choices.length; i++) {
                if (choices[i] == cur) next = choices[(i + 1) % choices.length];
            }
            value.setTag(next);
            value.setText(fmt.apply(next));
            if (key == null) Prompts.setPeakNudge(this, next == 1);
            else Prompts.setInt(this, key, next);
            Prompts.scheduleNext(this);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }));
        return row;
    }

    /** Scheduled nudge times as chips: tap a time to remove it, "+ Add" to pick a new one. */
    private View nudgeRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        TextView title = text(s(R.string.scheduled_nudges), 15, TEXT);
        title.setTypeface(MEDIUM);
        box.addView(title);
        TextView sub = text(s(R.string.scheduled_nudges_sub), 12, MUTED);
        sub.setPadding(0, dp(1), 0, dp(10));
        box.addView(sub);
        final LinearLayout chips = new LinearLayout(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(chips);
        box.addView(hs);
        fillNudgeChips(chips);
        return box;
    }

    private void fillNudgeChips(final LinearLayout chips) {
        chips.removeAllViews();
        final List<Integer> times = Prompts.nudges(this);
        for (final int m : times) {
            TextView chip = text("", 14, TEXT);
            chip.setText(TextUtils.concat(Prompts.hhmm(m) + "  ", icon(R.drawable.ic_close, MUTED, 14)));
            chip.setPadding(dp(12), dp(7), dp(10), dp(7));
            chip.setBackground(rounded(withAlpha(ACCENT, 0x26), 16, withAlpha(ACCENT, 0x66)));
            chip.setForeground(ripple(16));
            chip.setOnClickListener(v -> whenLoosenAllowed(() -> {
                List<Integer> next = new ArrayList<>(times);
                next.remove(Integer.valueOf(m));
                Prompts.setNudges(this, next);
                Prompts.scheduleNext(this);
                fillNudgeChips(chips);
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }
        TextView add = text("", 14, ACCENT);
        add.setText(TextUtils.concat(icon(R.drawable.ic_add, ACCENT, 16), " " + s(R.string.add)));
        add.setTypeface(MEDIUM);
        add.setPadding(dp(12), dp(7), dp(12), dp(7));
        add.setBackground(rounded(CARD, 16, BORDER));
        add.setForeground(ripple(16));
        // Adding a nudge only makes things stricter, so it never asks.
        add.setOnClickListener(v -> {
            new android.app.TimePickerDialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert,
                    (picker, h, min) -> {
                        List<Integer> next = new ArrayList<>(Prompts.nudges(this));
                        int m = h * 60 + min;
                        if (!next.contains(m)) next.add(m);
                        Prompts.setNudges(this, next);
                        Prompts.scheduleNext(this);
                        fillNudgeChips(chips);
                    }, 12, 0, true).show();
        });
        chips.addView(add);
    }

    // ---------------------------------------------------------------- breaks

    private void openBreakSheet() {
        if (Routine.onBreak(this)) return;
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());

        int left = Routine.breaksLeft(this);
        TextView title = text(s(left > 0 ? R.string.take_break_q : R.string.no_breaks_left), 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);
        TextView sub = text(left > 0
                ? s(R.string.break_body, (int) (Routine.BREAK_MS / 60_000), left, Routine.MAX_BREAKS)
                : s(R.string.breaks_used_body, Routine.MAX_BREAKS),
                14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(18));
        sheet.addView(sub);

        if (left > 0) {
            HoldButton hold = new HoldButton(this, s(R.string.hold_break), () -> {
                if (!Routine.takeBreak(this)) return;
                d.dismiss();
                refreshSummary(false);
                moveTaskToBack(true);
            });
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, dp(56));
            hlp.bottomMargin = dp(10);
            sheet.addView(hold, hlp);
        }
        TextView cancel = text(s(left > 0 ? R.string.keep_going : R.string.ok), 16, TEXT);
        cancel.setTypeface(MEDIUM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(rounded(CARD, 28, BORDER));
        cancel.setForeground(ripple(28));
        cancel.setOnClickListener(v -> d.dismiss());
        sheet.addView(cancel, new LinearLayout.LayoutParams(-1, dp(52)));

        d.setContentView(sheet);
        fitSystemBars(sheet, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void openAppPicker() {
        openAppPicker(false);
    }

    /**
     * Picks blocked apps or (with {@code allowList}) apps usable during focus lock. Before today's routine is done
     * you can only make things stricter: add blocked apps / remove allowed ones.
     */
    private void openAppPicker(final boolean allowList) {
        final boolean done = Routine.isDoneToday(this);
        final Set<String> selection = allowList ? Routine.allowedApps(this) : Routine.blockedApps(this);
        final Set<String> original = new HashSet<>(selection);
        final java.util.function.Predicate<String> locked = pkg -> !loosenAllowed()
                && (allowList ? !original.contains(pkg) : original.contains(pkg));
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());

        TextView title = text(s(allowList ? R.string.allowed_apps : R.string.blocked_apps), 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);
        TextView sub = text(allowList
                ? s(done ? R.string.allowed_done_sub : R.string.allowed_locked_sub)
                : s(done ? (BuildFlags.ROOT ? R.string.blocked_done_sub_root : R.string.blocked_done_sub)
                        : R.string.blocked_locked_sub), 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(14));
        sheet.addView(sub);

        EditText search = new EditText(this);
        search.setHint(s(R.string.search_apps));
        search.setCompoundDrawablesRelative(tinted(R.drawable.ic_search, MUTED, 20), null, null, null);
        search.setCompoundDrawablePadding(dp(10));
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setBackground(rounded(CARD, 14, BORDER));
        sheet.addView(search, new LinearLayout.LayoutParams(-1, -2));

        final ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, 0, 1);
        llp.topMargin = dp(8);
        sheet.addView(list, llp);
        final TextView loading = text(s(R.string.loading_apps), 14, MUTED);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(24), 0, dp(24));
        sheet.addView(loading, new LinearLayout.LayoutParams(-1, -2));

        final AppAdapter adapter = new AppAdapter(selection, locked);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Apps.Entry app = adapter.getItem(pos);
            if (locked.test(app.pkg)) {
                // Loosening: ask once, then let this tap through.
                whenLoosenAllowed(() -> {
                    adapter.notifyDataSetChanged();
                    list.performItemClick(view, pos, id);
                });
                return;
            }
            boolean on = !selection.contains(app.pkg);
            if (on) selection.add(app.pkg); else selection.remove(app.pkg);
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            ((CheckMark) view.getTag()).setChecked(on, true);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(12), 0, 0);
        TextView cancel = text(s(R.string.cancel), 16, TEXT);
        cancel.setTypeface(MEDIUM);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(rounded(CARD, 26, BORDER));
        cancel.setForeground(ripple(26));
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(52), 1);
        clp.rightMargin = dp(10);
        buttons.addView(cancel, clp);
        TextView save = text(s(R.string.save), 16, Color.WHITE);
        save.setTypeface(MEDIUM);
        save.setGravity(Gravity.CENTER);
        save.setBackground(gradientPill(26));
        save.setForeground(ripple(26));
        save.setOnClickListener(v -> {
            d.dismiss();
            if (allowList) {
                Routine.setAllowedApps(this, selection);
                build();
                return;
            }
            Routine.setBlockedApps(this, selection);
            build();
            Gate.sync(this, true, ok -> build());
        });
        buttons.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        sheet.addView(buttons);

        d.setContentView(sheet);
        fitSystemBars(sheet, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.85f));

        final Set<String> extra = new HashSet<>(selection);
        final Set<String> blocked = Routine.blockedApps(this);
        new Thread(() -> {
            // Selected apps first, then alphabetical. Blocked apps can't also be allowed.
            List<Apps.Entry> all = Apps.launchable(this, extra);
            if (allowList) all.removeIf(e -> blocked.contains(e.pkg));
            // Settings, phone, installer etc. are always reachable, so they're not offered at all.
            final java.util.Set<String> essentials = Apps.essentials(this);
            all.removeIf(e -> essentials.contains(e.pkg));
            List<Apps.Entry> sorted = new ArrayList<>();
            for (Apps.Entry e : all) if (extra.contains(e.pkg)) sorted.add(e);
            for (Apps.Entry e : all) if (!extra.contains(e.pkg)) sorted.add(e);
            runOnUiThread(() -> {
                loading.setVisibility(View.GONE);
                adapter.setApps(sorted);
            });
        }).start();
    }

    private class AppAdapter extends BaseAdapter {
        private final Set<String> selection;
        private final java.util.function.Predicate<String> locked;
        private List<Apps.Entry> all = new ArrayList<>(), shown = new ArrayList<>();
        private String query = "";

        AppAdapter(Set<String> selection, java.util.function.Predicate<String> locked) {
            this.selection = selection;
            this.locked = locked;
        }

        void setApps(List<Apps.Entry> apps) {
            all = apps;
            filter(query);
        }

        void filter(String q) {
            query = q.trim().toLowerCase(Locale.ROOT);
            shown = new ArrayList<>();
            for (Apps.Entry e : all) {
                if (query.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(query)
                        || e.pkg.contains(query)) shown.add(e);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Apps.Entry getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(10), dp(4), dp(10));
                ImageView icon = new ImageView(MainActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding(dp(14), 0, dp(10), 0);
                TextView label = text("", 16, TEXT);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                TextView pkg = text("", 12, MUTED);
                pkg.setSingleLine(true);
                pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(label);
                col.addView(pkg);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                CheckMark mark = new CheckMark(MainActivity.this);
                row.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
                row.setTag(mark);
            }
            Apps.Entry app = shown.get(pos);
            ((ImageView) row.getChildAt(0)).setImageDrawable(app.icon);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            ((TextView) col.getChildAt(0)).setText(app.label);
            ((TextView) col.getChildAt(1)).setText(locked.test(app.pkg)
                    ? TextUtils.concat(icon(R.drawable.ic_lock, MUTED, 12), s(R.string.locked_until_done)) : app.pkg);
            ((CheckMark) row.getTag()).setChecked(selection.contains(app.pkg), false);
            return row;
        }
    }

    // ---------------------------------------------------------------- routine editor (bottom sheet)

    /** Editable copy of one habit while the editor is open; written back only on Save. */
    private static final class HabitDraft {
        String name;
        /** The name when the editor opened (null = added in this edit), to carry today's progress over renames. */
        String original;
        int timer;
        int days = Routine.EVERY_DAY;
        String cue = "";
        /** null = auto (suggested from the name), "" = none, else your own. */
        String identity;

        String effectiveIdentity(Context c) {
            return identity == null ? HabitMeta.suggestIdentity(c, name) : identity;
        }
    }

    private void openEditor(boolean firstRun) {
        if (editor != null && editor.isShowing()) return;
        final Dialog d = new Dialog(this);
        editor = d;
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Load habits into drafts.
        final List<HabitDraft> drafts = new ArrayList<>();
        List<String> names = firstRun ? java.util.Arrays.asList(Routine.defaultItems(this).split("\n")) : Routine.allItems(this);
        for (String n : names) {
            HabitDraft h = new HabitDraft();
            h.name = n.trim();
            if (!firstRun) h.original = h.name;
            h.timer = Routine.timerMinutes(this, h.name);
            if (h.timer == 0 && firstRun) h.timer = minutesIn(h.name);
            h.days = Routine.dayMask(this, h.name);
            h.cue = HabitMeta.encodeCue(HabitMeta.cue(this, h.name));
            h.identity = HabitMeta.explicitIdentity(this, h.name);
            drafts.add(h);
        }

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());

        TextView title = text(s(firstRun ? R.string.build_routine : R.string.your_habits), 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);
        TextView sub = text(s(R.string.editor_sub), 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(14));
        sheet.addView(sub);

        // Checklist order: smart (by time) or exactly the order below.
        final TextView orderNote = text("", 12, MUTED);
        LinearLayout order = chipGroup(getResources().getStringArray(R.array.order_modes), HabitMeta.orderMode(this), i -> {
            HabitMeta.setOrderMode(this, i);
            orderNote.setText(orderHint(i));
        });
        sheet.addView(order);
        orderNote.setText(orderHint(HabitMeta.orderMode(this)));
        orderNote.setPadding(dp(4), dp(6), 0, dp(12));
        sheet.addView(orderNote);

        final DragList rows = new DragList(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            rows.removeAllViews();
            for (HabitDraft h : drafts) rows.addView(habitRow(rows, h, drafts, render[0]));
        };
        // After a drag, the drafts follow the new on-screen order.
        rows.onReorder = () -> {
            drafts.clear();
            for (int i = 0; i < rows.getChildCount(); i++) drafts.add((HabitDraft) rows.getChildAt(i).getTag());
        };
        render[0].run();
        ScrollView rowScroll = new ScrollView(this);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.addView(rows);
        sheet.addView(rowScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView add = text("", 16, ACCENT);
        add.setText(TextUtils.concat(icon(R.drawable.ic_add, ACCENT, 20), "  " + s(R.string.add_habit)));
        add.setTypeface(MEDIUM);
        add.setGravity(Gravity.CENTER);
        add.setBackground(rounded(withAlpha(ACCENT, 0x14), 26, withAlpha(ACCENT, 0x66)));
        add.setForeground(ripple(26));
        add.setOnClickListener(v -> {
            HabitDraft h = new HabitDraft();
            h.name = "";
            openHabitDetail(h, true, drafts, render[0]);
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, dp(52));
        alp.topMargin = dp(10);
        sheet.addView(add, alp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(10), 0, 0);
        if (!firstRun) {
            TextView cancel = text(s(R.string.cancel), 16, TEXT);
            cancel.setTypeface(MEDIUM);
            cancel.setGravity(Gravity.CENTER);
            cancel.setBackground(rounded(CARD, 26, BORDER));
            cancel.setForeground(ripple(26));
            cancel.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(52), 1);
            clp.rightMargin = dp(10);
            buttons.addView(cancel, clp);
        }
        TextView save = text(s(R.string.save), 16, Color.WHITE);
        save.setTypeface(MEDIUM);
        save.setGravity(Gravity.CENTER);
        save.setBackground(gradientPill(26));
        save.setForeground(ripple(26));
        save.setOnClickListener(v -> {
            if (drafts.isEmpty()) {
                Toast.makeText(this, s(R.string.add_one_habit), Toast.LENGTH_SHORT).show();
                return;
            }
            StringBuilder sb = new StringBuilder();
            Map<String, Integer> timers = new java.util.HashMap<>();
            Map<String, Integer> dayMasks = new java.util.HashMap<>();
            Map<String, String> cues = new java.util.HashMap<>();
            Map<String, String> identities = new java.util.HashMap<>();
            for (HabitDraft h : drafts) {
                sb.append(h.name).append('\n');
                timers.put(h.name, h.timer);
                dayMasks.put(h.name, h.days);
                cues.put(h.name, h.cue);
                if (h.identity != null) identities.put(h.name, h.identity);
            }
            final boolean wasDone = !firstRun && Routine.isConfigured(this) && Routine.isDoneToday(this);
            Map<String, String> renames = new java.util.HashMap<>();
            for (HabitDraft h : drafts) {
                if (h.original != null && !h.original.equals(h.name)) renames.put(h.original, h.name);
            }
            Map<String, String> since = Routine.startDates(this);
            for (Map.Entry<String, String> r : renames.entrySet()) {
                String from = since.remove(r.getKey());
                if (from != null) since.put(r.getValue(), from);
            }
            since.keySet().retainAll(dayMasks.keySet());
            Routine.renameToday(this, renames);
            Routine.setTimers(this, timers);
            Routine.setDays(this, dayMasks);
            HabitMeta.save(this, cues, identities);
            Routine.setStartDates(this, since);
            Routine.setItems(this, sb.toString());
            // A finished day stays finished: anything these edits made due today starts tomorrow instead.
            if (wasDone && !Routine.isDoneToday(this)) {
                Set<String> ticked = Routine.checked(this);
                String tomorrow = Routine.tomorrow();
                for (String item : Routine.items(this)) if (!ticked.contains(item)) since.put(item, tomorrow);
                Routine.setStartDates(this, since);
                History.logToday(this);
                RoutineWidget.updateAll(this);
                Toast.makeText(this, s(R.string.starts_tomorrow_toast), Toast.LENGTH_LONG).show();
            }
            Gate.sync(this, false, null);
            UnlockWatcher.update(this);
            Prompts.scheduleNext(this);
            d.dismiss();
            build();
        });
        buttons.addView(save, new LinearLayout.LayoutParams(0, dp(52), 1));
        sheet.addView(buttons);

        d.setContentView(sheet);
        fitSystemBars(sheet, false);
        d.setCancelable(!firstRun);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.9f));
    }

    private static int minutesIn(String habit) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*min").matcher(habit);
        return m.find() ? Math.min(60, Integer.parseInt(m.group(1))) : 0;
    }

    /** One line about a habit's details: "20 min · Mon Wed Fri · After coffee · reader". */
    private String habitSummary(HabitDraft h) {
        List<String> parts = new ArrayList<>();
        String from = h.original == null ? null : Routine.startDates(this).get(h.original);
        if (from != null && from.compareTo(Routine.today()) > 0) parts.add(s(R.string.starts_tomorrow));
        if (h.timer > 0) parts.add(s(R.string.n_min, h.timer));
        parts.add(Routine.describeDays(this, h.days));
        HabitMeta.Cue cue = HabitMeta.decodeCue(h.cue);
        if (cue.type != HabitMeta.Cue.NONE) parts.add(cue.describe(this));
        String id = h.effectiveIdentity(this);
        if (!id.isEmpty()) parts.add(HabitMeta.shortIdentity(id));
        return TextUtils.join("  ·  ", parts);
    }

    /** A habit in the editor list: drag handle, name, summary, chevron. Tap to edit. */
    private View habitRow(final DragList rows, final HabitDraft h, final List<HabitDraft> drafts,
                          final Runnable rerender) {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(10), dp(12), dp(10));
        row.setBackground(rounded(CARD, 18, BORDER));
        row.setTag(h);
        ImageView handle = iconView(R.drawable.ic_drag, MUTED);
        handle.setPadding(dp(10), dp(12), dp(10), dp(12));
        handle.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                rows.startDrag(row, e.getRawY());
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
            return false;
        });
        row.addView(handle, new LinearLayout.LayoutParams(dp(44), dp(48)));

        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setForeground(ripple(18));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(h.name, 17, TEXT);
        name.setTypeface(MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView sum = text(habitSummary(h), 12, MUTED);
        sum.setSingleLine(true);
        sum.setEllipsize(TextUtils.TruncateAt.END);
        sum.setPadding(0, dp(3), 0, 0);
        col.addView(sum);
        body.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(iconView(R.drawable.ic_chevron, MUTED), new LinearLayout.LayoutParams(dp(24), dp(24)));
        body.setOnClickListener(v -> openHabitDetail(h, false, drafts, rerender));
        row.addView(body, new LinearLayout.LayoutParams(0, dp(52), 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** Full-height editor for one habit: name, timer, days, cue, identity, delete. Big, labelled, roomy. */
    private void openHabitDetail(final HabitDraft h, final boolean isNew, final List<HabitDraft> drafts,
                                 final Runnable onChanged) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final HabitDraft work = new HabitDraft(); // edit a copy; apply on Done
        work.name = h.name;
        work.timer = h.timer;
        work.days = h.days;
        work.cue = h.cue;
        work.identity = h.identity;

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(20), dp(10), dp(20), dp(20));
        outer.setBackground(sheetBackground());
        outer.addView(sheetHandle());
        TextView title = text(s(isNew ? R.string.new_habit : R.string.edit_habit), 24, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        outer.addView(title);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        sc.addView(body);
        outer.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        // Name.
        body.addView(sectionLabel(s(R.string.name_header)));
        final EditText name = new EditText(this);
        name.setText(work.name);
        name.setHint(s(R.string.habit_hint));
        name.setTextColor(TEXT);
        name.setHintTextColor(MUTED);
        name.setTextSize(19);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setPadding(dp(16), dp(14), dp(16), dp(14));
        name.setBackground(rounded(CARD, 16, BORDER));
        body.addView(name, new LinearLayout.LayoutParams(-1, -2));

        final Runnable[] refresh = new Runnable[1];
        if (isNew) {
            // Quick picks so you don't have to type on a phone keyboard.
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout picks = new LinearLayout(this);
            picks.setPadding(0, dp(10), 0, 0);
            for (final String s : getResources().getStringArray(R.array.starter_habits)) {
                TextView chip = text(s, 14, TEXT);
                chip.setPadding(dp(14), dp(9), dp(14), dp(9));
                chip.setBackground(rounded(CARD, 18, BORDER));
                chip.setForeground(ripple(18));
                chip.setOnClickListener(v -> {
                    name.setText(s);
                    name.setSelection(s.length());
                    if (work.timer == 0) work.timer = minutesIn(s);
                    refresh[0].run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(8);
                picks.addView(chip, lp);
            }
            hs.addView(picks);
            body.addView(hs);
        }

        // Timer: a 4x2 grid of big targets.
        body.addView(sectionLabel(s(R.string.timer_header)));
        TextView timerNote = text(s(R.string.timer_note), 12, MUTED);
        timerNote.setPadding(dp(4), 0, 0, dp(8));
        body.addView(timerNote);
        final List<TextView> timerChips = new ArrayList<>();
        int[] choices = Routine.TIMER_CHOICES;
        for (int r0 = 0; r0 < choices.length; r0 += 4) {
            LinearLayout row = new LinearLayout(this);
            for (int i = r0; i < Math.min(r0 + 4, choices.length); i++) {
                final int m = choices[i];
                TextView chip = text(m == 0 ? s(R.string.off) : s(R.string.n_min, m), 14, TEXT);
                chip.setTypeface(MEDIUM);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(0, dp(12), 0, dp(12));
                chip.setTag(m);
                chip.setOnClickListener(v -> {
                    work.timer = m;
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    refresh[0].run();
                });
                timerChips.add(chip);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > r0) lp.leftMargin = dp(8);
                row.addView(chip, lp);
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            if (r0 > 0) rlp.topMargin = dp(8);
            body.addView(row, rlp);
        }

        // Days: seven circles + presets, right here (no extra dialog).
        body.addView(sectionLabel(s(R.string.days_header)));
        final TextView daysNote = text("", 12, MUTED);
        daysNote.setPadding(dp(4), 0, 0, dp(8));
        body.addView(daysNote);
        LinearLayout circles = new LinearLayout(this);
        final TextView[] dots = new TextView[7];
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            TextView dot = text(Routine.dayLetters(this)[i], 15, TEXT);
            dot.setTypeface(MEDIUM);
            dot.setGravity(Gravity.CENTER);
            dot.setOnClickListener(v -> {
                int next = work.days ^ bit;
                if ((next & Routine.EVERY_DAY) == 0) return; // at least one day
                work.days = next;
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                refresh[0].run();
            });
            dots[i] = dot;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) lp.leftMargin = dp(5);
            circles.addView(dot, lp);
        }
        body.addView(circles);
        LinearLayout presets = new LinearLayout(this);
        presets.setPadding(0, dp(8), 0, 0);
        String[] pNames = getResources().getStringArray(R.array.day_presets);
        int[] pMasks = {Routine.EVERY_DAY, 0x1F, 0x60};
        for (int i = 0; i < 3; i++) {
            final int m = pMasks[i];
            TextView pr = text(pNames[i], 13, ACCENT);
            pr.setTypeface(MEDIUM);
            pr.setGravity(Gravity.CENTER);
            pr.setPadding(dp(4), dp(9), dp(4), dp(9));
            pr.setBackground(rounded(CARD, 16, BORDER));
            pr.setForeground(ripple(16));
            pr.setOnClickListener(v -> {
                work.days = m;
                refresh[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = dp(8);
            presets.addView(pr, lp);
        }
        body.addView(presets);

        // Cue and identity: big rows that read as sentences; tap opens their pickers.
        body.addView(sectionLabel(s(R.string.when_header)));
        final TextView cueRow = bigChoiceRow();
        cueRow.setOnClickListener(v -> pickCue(name.getText().toString().trim(), work.cue, enc -> {
            work.cue = enc;
            refresh[0].run();
        }));
        body.addView(cueRow, new LinearLayout.LayoutParams(-1, -2));
        body.addView(sectionLabel(s(R.string.who_header)));
        final TextView idRow = bigChoiceRow();
        idRow.setOnClickListener(v -> pickIdentity(name.getText().toString().trim(), work.identity, id -> {
            work.identity = id;
            refresh[0].run();
        }));
        body.addView(idRow, new LinearLayout.LayoutParams(-1, -2));

        if (!isNew) {
            TextView del = text(s(R.string.delete_habit), 15, WARN);
            del.setTypeface(MEDIUM);
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, dp(14), 0, dp(14));
            del.setOnClickListener(v -> {
                drafts.remove(h);
                onChanged.run();
                d.dismiss();
            });
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
            dl.topMargin = dp(16);
            body.addView(del, dl);
        }

        refresh[0] = () -> {
            for (TextView ch : timerChips) {
                boolean on = (Integer) ch.getTag() == work.timer;
                ch.setTextColor(on ? TEXT : MUTED);
                ch.setBackground(on ? rounded(withAlpha(ACCENT, 0x30), 16, ACCENT) : rounded(CARD, 16, BORDER));
            }
            for (int i = 0; i < 7; i++) {
                boolean on = (work.days & (1 << i)) != 0;
                dots[i].setTextColor(on ? Color.WHITE : MUTED);
                dots[i].setBackground(on ? gradientPill(22) : rounded(CARD, 22, BORDER));
            }
            daysNote.setText(Routine.describeDays(this, work.days));
            String nm = name.getText().toString().trim();
            String what = nm.isEmpty() ? s(R.string.do_it) : Character.toLowerCase(nm.charAt(0)) + nm.substring(1);
            HabitMeta.Cue cue = HabitMeta.decodeCue(work.cue);
            cueRow.setText(cue.type == HabitMeta.Cue.NONE
                    ? TextUtils.concat(icon(R.drawable.ic_add, ACCENT, 16), "  " + s(R.string.add_cue, what))
                    : TextUtils.concat(icon(cue.type == HabitMeta.Cue.AT ? R.drawable.ic_calendar : R.drawable.ic_arrow,
                    ACCENT, 16), "  " + s(R.string.cue_sentence, cue.describe(this), what)));
            work.name = nm;
            String id = work.effectiveIdentity(this);
            idRow.setText(id.isEmpty()
                    ? TextUtils.concat(icon(R.drawable.ic_add, ACCENT, 16), "  " + s(R.string.add_identity))
                    : TextUtils.concat(icon(R.drawable.ic_sparkle, 0xFFFFD166, 16), "  " + s(R.string.im_identity, id)
                    + (work.identity == null ? s(R.string.suggested) : "")));
        };
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { refresh[0].run(); }
        });
        refresh[0].run();

        TextView done = text(s(isNew ? R.string.add_habit : R.string.done), 16, Color.WHITE);
        done.setTypeface(MEDIUM);
        done.setGravity(Gravity.CENTER);
        done.setBackground(gradientPill(26));
        done.setForeground(ripple(26));
        done.setOnClickListener(v -> {
            String nm = name.getText().toString().trim();
            if (nm.isEmpty()) {
                Toast.makeText(this, s(R.string.give_name_toast), Toast.LENGTH_SHORT).show();
                return;
            }
            for (HabitDraft other : drafts) {
                if (other != h && other.name.equalsIgnoreCase(nm)) {
                    Toast.makeText(this, s(R.string.already_have, nm), Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            h.name = nm;
            h.timer = work.timer;
            h.days = work.days;
            h.cue = work.cue;
            h.identity = work.identity;
            if (isNew) drafts.add(h);
            onChanged.run();
            d.dismiss();
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(54));
        dlp.topMargin = dp(12);
        outer.addView(done, dlp);

        d.setContentView(outer);
        fitSystemBars(outer, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.5f);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (isNew ? WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN));
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.94f));
        if (isNew) name.requestFocus();
    }

    /** Full-width tappable row used for cue / identity in the habit editor. */
    private TextView bigChoiceRow() {
        TextView t = text("", 15, TEXT);
        t.setPadding(dp(16), dp(15), dp(16), dp(15));
        t.setBackground(rounded(CARD, 16, BORDER));
        t.setForeground(ripple(16));
        return t;
    }

    private String orderHint(int mode) {
        return s(mode == 0 ? R.string.order_smart_hint : R.string.order_mine_hint);
    }

    /** "When will you do it?": no cue, after an everyday moment, or at a time. Suggests your usual time. */
    private void pickCue(final String habit, String encoded, final java.util.function.Consumer<String> onDone) {
        final HabitMeta.Cue cue = HabitMeta.decodeCue(encoded);
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        TextView title = text(s(R.string.when_q), 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);
        TextView sub = text(s(R.string.when_sub), 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(14));
        sheet.addView(sub);

        // The plan, as a sentence. This is the part that does the work.
        final TextView plan = text("", 17, TEXT);
        plan.setTypeface(MEDIUM);
        plan.setPadding(dp(16), dp(14), dp(16), dp(14));
        plan.setBackground(rounded(withAlpha(ACCENT, 0x1A), 16, withAlpha(ACCENT, 0x55)));
        sheet.addView(plan, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout afterBox = new LinearLayout(this);
        afterBox.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout atBox = new LinearLayout(this);
        atBox.setOrientation(LinearLayout.VERTICAL);
        final String what = habit.isEmpty() ? s(R.string.do_it) : Character.toLowerCase(habit.charAt(0)) + habit.substring(1);

        final Runnable[] refresh = new Runnable[1];
        final String[] anchors = HabitMeta.anchors(this);
        LinearLayout modes = chipGroup(getResources().getStringArray(R.array.cue_modes), cue.type, i -> {
            cue.type = i;
            if (i == HabitMeta.Cue.AT && cue.minutes < 0) cue.minutes = 8 * 60;
            if (i == HabitMeta.Cue.AFTER && cue.anchor.isEmpty()) cue.anchor = anchors[1];
            refresh[0].run();
        });
        modes.setPadding(0, dp(14), 0, dp(4));
        sheet.addView(modes);

        // After...: everyday moments as chips (3 per row) + your own.
        final List<TextView> anchorChips = new ArrayList<>();
        LinearLayout arow = null;
        for (int i = 0; i < anchors.length; i++) {
            if (i % 3 == 0) {
                arow = new LinearLayout(this);
                arow.setPadding(0, dp(8), 0, 0);
                afterBox.addView(arow);
            }
            final String an = anchors[i];
            TextView chip = text(an, 13, TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), dp(9), dp(4), dp(9));
            chip.setOnClickListener(v -> {
                cue.anchor = an;
                refresh[0].run();
            });
            anchorChips.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 3 != 0) lp.leftMargin = dp(6);
            arow.addView(chip, lp);
        }
        final EditText own = new EditText(this);
        own.setHint(s(R.string.own_anchor_hint));
        own.setTextColor(TEXT);
        own.setHintTextColor(MUTED);
        own.setTextSize(15);
        own.setSingleLine(true);
        own.setPadding(dp(14), dp(11), dp(14), dp(11));
        own.setBackground(rounded(CARD, 14, BORDER));
        boolean custom = cue.type == HabitMeta.Cue.AFTER && !java.util.Arrays.asList(anchors).contains(cue.anchor);
        if (custom) own.setText(cue.anchor);
        own.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (!own.hasFocus()) return;
                String t = e.toString().trim();
                if (!t.isEmpty()) {
                    cue.anchor = t;
                    refresh[0].run();
                }
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-1, -2);
        olp.topMargin = dp(10);
        afterBox.addView(own, olp);
        sheet.addView(afterBox);

        // At a time: a big tappable clock.
        final TextView clock = text("", 34, TEXT);
        clock.setTypeface(Typeface.DEFAULT_BOLD);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(0, dp(14), 0, dp(14));
        clock.setBackground(rounded(CARD, 18, BORDER));
        clock.setForeground(ripple(18));
        clock.setOnClickListener(v -> new android.app.TimePickerDialog(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert, (tp, h, m) -> {
                    cue.minutes = h * 60 + m;
                    refresh[0].run();
                }, Math.max(0, cue.minutes) / 60, Math.max(0, cue.minutes) % 60, true).show());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        atBox.addView(clock, clp);
        TextView atNote = text(s(R.string.at_note), 12, MUTED);
        atNote.setPadding(dp(4), dp(6), 0, 0);
        atBox.addView(atNote);
        sheet.addView(atBox);

        // Learned suggestion: when you actually tend to do it.
        final int usual = habit.isEmpty() ? -1 : HabitMeta.usualMinutes(History.all(this), habit);
        if (usual >= 0) {
            LinearLayout sug = new LinearLayout(this);
            sug.setGravity(Gravity.CENTER_VERTICAL);
            sug.setPadding(dp(14), dp(10), dp(8), dp(10));
            sug.setBackground(rounded(withAlpha(0xFFFFD166, 0x14), 14, withAlpha(0xFFFFD166, 0x55)));
            TextView st = text("", 13, TEXT);
            st.setText(TextUtils.concat(icon(R.drawable.ic_sparkle, 0xFFFFD166, 14),
                    "  " + s(R.string.usually_around, Prompts.hhmm(usual))));
            sug.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
            TextView use = text(s(R.string.use_it), 13, ACCENT);
            use.setTypeface(MEDIUM);
            use.setPadding(dp(10), dp(4), dp(10), dp(4));
            use.setOnClickListener(v -> {
                cue.type = HabitMeta.Cue.AT;
                cue.minutes = usual;
                refresh[0].run();
            });
            sug.addView(use);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
            slp.topMargin = dp(12);
            sheet.addView(sug, slp);
        }

        refresh[0] = () -> {
            for (int k = 0; k < modes.getChildCount(); k++) styleChip((TextView) modes.getChildAt(k), k == cue.type);
            afterBox.setVisibility(cue.type == HabitMeta.Cue.AFTER ? View.VISIBLE : View.GONE);
            atBox.setVisibility(cue.type == HabitMeta.Cue.AT ? View.VISIBLE : View.GONE);
            for (TextView ch : anchorChips) styleChip(ch, ch.getText().toString().equals(cue.anchor));
            if (cue.minutes >= 0) clock.setText(Prompts.hhmm(cue.minutes));
            plan.setText(cue.type == HabitMeta.Cue.NONE
                    ? s(R.string.cue_tip, what) : s(R.string.cue_plan, cue.describe(this), what));
        };
        refresh[0].run();

        TextView done = text(s(R.string.done), 16, Color.WHITE);
        done.setTypeface(MEDIUM);
        done.setGravity(Gravity.CENTER);
        done.setBackground(gradientPill(26));
        done.setForeground(ripple(26));
        done.setOnClickListener(v -> {
            onDone.accept(HabitMeta.encodeCue(cue));
            d.dismiss();
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(52));
        dlp.topMargin = dp(16);
        sheet.addView(done, dlp);
        showSheet(d, sheet);
    }

    /** "Who does this make you?" Suggestions from the habit name, your own words, or none. */
    private void pickIdentity(final String habit, String explicit, final java.util.function.Consumer<String> onDone) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(20), dp(10), dp(20), dp(20));
        sheet.setBackground(sheetBackground());
        sheet.addView(sheetHandle());
        TextView title = text(s(R.string.who_q), 22, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(title);
        TextView sub = text(s(R.string.who_sub), 14, MUTED);
        sub.setPadding(0, dp(4), 0, dp(14));
        sheet.addView(sub);

        final String suggested = HabitMeta.suggestIdentity(this, habit);
        final String[] value = {explicit == null ? suggested : explicit};
        final TextView preview = text("", 18, TEXT);
        preview.setTypeface(MEDIUM);
        preview.setPadding(dp(16), dp(14), dp(16), dp(14));
        preview.setBackground(rounded(withAlpha(0xFFFFD166, 0x1A), 16, withAlpha(0xFFFFD166, 0x66)));
        sheet.addView(preview, new LinearLayout.LayoutParams(-1, -2));

        final EditText field = new EditText(this);
        field.setText(value[0]);
        field.setHint(s(R.string.identity_hint));
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        field.setBackground(rounded(CARD, 14, BORDER));
        final Runnable paint = () -> preview.setText(value[0].trim().isEmpty() ? s(R.string.no_identity_for)
                : s(R.string.im_quote, value[0].trim()));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                value[0] = e.toString();
                paint.run();
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(12);
        sheet.addView(field, flp);

        // Quick picks: the suggestion plus a few that fit almost anything.
        List<String> picks = new ArrayList<>();
        for (String p : new String[]{suggested, s(R.string.id_disciplined), s(R.string.id_healthy),
                s(R.string.id_default)}) {
            if (!picks.contains(p)) picks.add(p);
        }
        for (final String p : picks) {
            TextView chip = text(p, 14, TEXT);
            chip.setPadding(dp(14), dp(10), dp(14), dp(10));
            chip.setBackground(rounded(CARD, 16, BORDER));
            chip.setForeground(ripple(16));
            chip.setOnClickListener(v -> field.setText(p));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            sheet.addView(chip, lp);
        }
        paint.run();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(16), 0, 0);
        TextView none = text(s(R.string.no_identity), 15, TEXT);
        none.setTypeface(MEDIUM);
        none.setGravity(Gravity.CENTER);
        none.setBackground(rounded(CARD, 26, BORDER));
        none.setOnClickListener(v -> {
            onDone.accept("");
            d.dismiss();
        });
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, dp(52), 1);
        nlp.rightMargin = dp(10);
        buttons.addView(none, nlp);
        TextView done = text(s(R.string.done), 16, Color.WHITE);
        done.setTypeface(MEDIUM);
        done.setGravity(Gravity.CENTER);
        done.setBackground(gradientPill(26));
        done.setOnClickListener(v -> {
            String t = value[0].trim();
            // Same as the suggestion: keep it on auto so it follows renames.
            onDone.accept(t.isEmpty() ? "" : t.equals(suggested) ? null : t);
            d.dismiss();
        });
        buttons.addView(done, new LinearLayout.LayoutParams(0, dp(52), 1));
        sheet.addView(buttons);
        showSheet(d, sheet);
    }

    private void showSheet(Dialog d, View sheet) {
        ScrollView sc = new ScrollView(this);
        sc.addView(sheet);
        d.setContentView(sc);
        fitSystemBars(sc, false);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setDimAmount(0.65f);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        w.setWindowAnimations(android.R.style.Animation_InputMethod);
        d.show();
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---------------------------------------------------------------- helpers

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 5) return s(R.string.up_late);
        if (h < 12) return s(R.string.good_morning);
        if (h < 17) return s(R.string.good_afternoon);
        return s(R.string.good_evening);
    }

    private Drawable sheetBackground() {
        GradientDrawable bg = new GradientDrawable();
        float r = dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(SHEET);
        return bg;
    }

    private View sheetHandle() {
        View handle = new View(this);
        handle.setBackground(rounded(BORDER, 2, 0));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(18);
        handle.setLayoutParams(hlp);
        return handle;
    }

    /** A tinted vector icon sized to {@code sizeDp}, with bounds set. */
    private Drawable tinted(int res, int color, int sizeDp) {
        Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    /** An icon as a piece of text, so it flows inline with labels. */
    private CharSequence icon(int res, int color, int sizeDp) {
        SpannableString s = new SpannableString(" ");
        s.setSpan(new IconSpan(tinted(res, color, sizeDp)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private ImageView iconView(int res, int color) {
        ImageView v = new ImageView(this);
        v.setImageDrawable(tinted(res, color, 24));
        return v;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable gradientPill(int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT, ACCENT2});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Drawable ripple(int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, rounded(Color.WHITE, radiusDp, 0));
    }

    private View space(int dpH) {
        View v = new View(this);
        v.setMinimumHeight(dp(dpH));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ---------------------------------------------------------------- custom views

    /** Circular progress with a gradient arc that turns green when complete. */
    static class ProgressRing extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
                big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final RectF bounds = new RectF();
        private SweepGradient sweep;
        private ValueAnimator anim;
        private float shown;
        private boolean done;
        private String center = "";

        ProgressRing(Context c) {
            super(c);
            float d = getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(9 * d);
            track.setColor(BORDER);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(9 * d);
            arc.setStrokeCap(Paint.Cap.ROUND);
            big.setColor(TEXT);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTextSize(22 * d);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            small.setColor(MUTED);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(11 * d);
            small.setLetterSpacing(0.1f);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(3.5f * d);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            tick.setColor(GOOD);
        }

        void set(float progress, boolean done, String center, boolean animate) {
            this.done = done;
            this.center = center;
            if (anim != null) anim.cancel();
            if (!animate) {
                shown = progress;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(shown, progress);
            anim.setDuration(500);
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float half = track.getStrokeWidth() / 2;
            bounds.set(half, half, w - half, h - half);
            sweep = new SweepGradient(w / 2f, h / 2f, new int[]{ACCENT2, ACCENT, ACCENT2}, null);
            Matrix m = new Matrix();
            m.setRotate(-90, w / 2f, h / 2f);
            sweep.setLocalMatrix(m);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawArc(bounds, 0, 360, false, track);
            if (done) {
                arc.setShader(null);
                arc.setColor(GOOD);
            } else {
                arc.setShader(sweep);
            }
            if (shown > 0.001f) c.drawArc(bounds, -90, 360 * shown, false, arc);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            if (done) {
                float u = big.getTextSize() * 0.45f;
                check.reset();
                check.moveTo(cx - u, cy - u * 0.3f);
                check.lineTo(cx - u * 0.25f, cy + u * 0.4f);
                check.lineTo(cx + u, cy - u * 0.85f);
                c.drawPath(check, tick);
            } else {
                c.drawText(center, cx, cy + big.getTextSize() * 0.2f, big);
            }
            c.drawText(getContext().getString(done ? R.string.ring_done : R.string.ring_habits), cx,
                    cy + big.getTextSize() * 0.85f, small);
        }
    }

    /** Round checkbox that fills in and draws its tick when checked. */
    static class CheckMark extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path(), seg = new Path();
        private PathMeasure measure;
        private ValueAnimator anim;
        private float t;
        private float timerProgress = -1;
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcBounds = new RectF();

        /** Draws a timer arc around the empty circle; -1 hides it. */
        void setTimerProgress(float p) {
            if (p == timerProgress) return;
            timerProgress = p;
            invalidate();
        }

        CheckMark(Context c) {
            super(c);
            float d = getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(MUTED);
            fill.setColor(GOOD);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(2.6f * d);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            tick.setColor(BG);
        }

        void setChecked(boolean on, boolean animate) {
            float target = on ? 1f : 0f;
            if (anim != null) anim.cancel();
            if (!animate) {
                t = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(t, target);
            anim.setDuration(on ? 320 : 180);
            anim.setInterpolator(on ? new OvershootInterpolator(2f) : new AccelerateInterpolator());
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            path.reset();
            path.moveTo(w * 0.29f, h * 0.52f);
            path.lineTo(w * 0.44f, h * 0.66f);
            path.lineTo(w * 0.72f, h * 0.37f);
            measure = new PathMeasure(path, false);
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float rad = Math.min(cx, cy) - ring.getStrokeWidth() * 1.5f;
            float k = Math.max(0f, t);
            ring.setAlpha((int) (255 * Math.max(0f, Math.min(1f, 1 - k))));
            c.drawCircle(cx, cy, rad, ring);
            if (timerProgress >= 0 && k < 0.001f) {
                arc.setStyle(Paint.Style.STROKE);
                arc.setStrokeWidth(ring.getStrokeWidth() * 1.5f);
                arc.setStrokeCap(Paint.Cap.ROUND);
                arc.setColor(ACCENT);
                arcBounds.set(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(arcBounds, -90, 360 * timerProgress, false, arc);
            }
            if (k > 0.001f) {
                c.drawCircle(cx, cy, rad * k, fill);
                if (measure != null) {
                    seg.reset();
                    measure.getSegment(0, measure.getLength() * Math.min(1f, k), seg, true);
                    c.drawPath(seg, tick);
                }
            }
        }
    }

    /** ImageSpan that centers its icon on the text instead of sitting on the baseline. */
    static class IconSpan extends ImageSpan {
        IconSpan(Drawable d) {
            super(d);
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return getDrawable().getBounds().right;
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         Paint paint) {
            Drawable d = getDrawable();
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            float dy = y + (fm.descent + fm.ascent) / 2f - d.getBounds().height() / 2f;
            c.save();
            c.translate(x, dy);
            d.draw(c);
            c.restore();
        }
    }

    /** Pill button that must be held down until its fill completes. */
    static class HoldButton extends View {
        private static final long HOLD_MS = 3000;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final String text;
        private final Runnable onDone;
        private ValueAnimator anim;
        private float p;
        private boolean fired;

        HoldButton(Context c, String text, Runnable onDone) {
            super(c);
            this.text = text;
            this.onDone = onDone;
            float d = getResources().getDisplayMetrics().density;
            bg.setColor(CARD);
            label.setColor(TEXT);
            label.setTextSize(16 * d);
            label.setTypeface(MEDIUM);
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            rect.set(0, 0, w, h);
            fill.setShader(new android.graphics.LinearGradient(0, 0, w, 0, ACCENT, ACCENT2,
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override
        @SuppressWarnings("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent e) {
            if (fired) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    animateTo(1f, (long) (HOLD_MS * (1 - p)), new LinearInterpolator());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateTo(0f, 250, new DecelerateInterpolator());
                    return true;
            }
            return true;
        }

        private void animateTo(float target, long ms, android.animation.TimeInterpolator interp) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(p, target);
            anim.setDuration(Math.max(1, ms));
            anim.setInterpolator(interp);
            anim.addUpdateListener(a -> {
                p = (float) a.getAnimatedValue();
                invalidate();
            });
            if (target >= 1f) {
                anim.addListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;

                    @Override
                    public void onAnimationCancel(Animator a) {
                        cancelled = true;
                    }

                    @Override
                    public void onAnimationEnd(Animator a) {
                        if (cancelled || fired) return;
                        fired = true;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        onDone.run();
                    }
                });
            }
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float rad = getHeight() / 2f;
            c.drawRoundRect(rect, rad, rad, bg);
            if (p > 0) {
                c.save();
                c.clipRect(0, 0, getWidth() * p, getHeight());
                c.drawRoundRect(rect, rad, rad, fill);
                c.restore();
            }
            String s = p > 0 && p < 1 ? getContext().getString(R.string.keep_holding) : text;
            Paint.FontMetrics fm = label.getFontMetrics();
            c.drawText(s, getWidth() / 2f, getHeight() / 2f - (fm.ascent + fm.descent) / 2, label);
        }
    }

    /** 12-week calendar heatmap, Monday-first columns, newest week on the right. Tap a cell for details. */
    static class HeatmapView extends View {
        private static final int WEEKS = 12;
        private final TreeMap<String, History.Day> log;
        private final Consumer<String> onTap;
        private final Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG), outline = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final String[] dates = new String[WEEKS * 7];
        private final String[] months = new String[WEEKS];
        private final float d, gap, labelW, topH, pad;
        private float size, left;
        private int selected = -1, todayIndex;

        HeatmapView(Context c, TreeMap<String, History.Day> log, Consumer<String> onTap) {
            super(c);
            this.log = log;
            this.onTap = onTap;
            d = getResources().getDisplayMetrics().density;
            gap = 3 * d;
            labelW = 18 * d;
            topH = 18 * d;
            pad = 4 * d; // room for the selection ring around edge cells
            outline.setStyle(Paint.Style.STROKE);
            label.setColor(MUTED);
            label.setTextSize(10 * d);

            // Grid starts on the Monday WEEKS-1 weeks before this week's Monday.
            Calendar cal = Routine.dayCalendar();
            int dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0
            todayIndex = (WEEKS - 1) * 7 + dow;
            cal.add(Calendar.DAY_OF_YEAR, -todayIndex);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat mon = new SimpleDateFormat("MMM", Locale.getDefault());
            int lastMonth = -1;
            for (int i = 0; i < dates.length; i++) {
                dates[i] = iso.format(cal.getTime());
                if (i % 7 == 0) {
                    int m = cal.get(Calendar.MONTH);
                    months[i / 7] = m != lastMonth ? mon.format(cal.getTime()) : null;
                    lastMonth = m;
                }
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            // Capped so the grid stays compact on wide screens; centred in the leftover space.
            size = Math.min(20 * d, (w - labelW - 2 * pad - (WEEKS - 1) * gap) / WEEKS);
            float gridW = labelW + WEEKS * size + (WEEKS - 1) * gap;
            left = Math.max(pad, (w - gridW) / 2);
            setMeasuredDimension(w, (int) Math.ceil(topH + 7 * size + 6 * gap + pad));
        }

        private int colorFor(int i) {
            String first = log.isEmpty() ? null : log.firstKey();
            if (first == null || dates[i].compareTo(first) < 0) return 0; // not tracked
            History.Day day = log.get(dates[i]);
            if (day == null) return i == todayIndex ? 0 : HEAT_MISSED;
            if (day.frozen) return HEAT_FROZEN;
            if (day.rest) return HEAT_REST;
            if (day.complete()) return HEAT_DONE;
            float f = day.fraction();
            if (f >= 0.5f) return HEAT_HIGH;
            if (f > 0) return HEAT_LOW;
            return i == todayIndex ? 0 : HEAT_MISSED;
        }

        @Override
        protected void onDraw(Canvas c) {
            String[] letters = getResources().getStringArray(R.array.day_letters);
            String[] rowLabels = {letters[0], "", letters[2], "", letters[4], "", ""};
            for (int row = 0; row < 7; row++) {
                if (rowLabels[row].isEmpty()) continue;
                c.drawText(rowLabels[row], left, topH + row * (size + gap) + size * 0.75f, label);
            }
            for (int col = 0; col < WEEKS; col++) {
                if (months[col] != null) c.drawText(months[col], left + labelW + col * (size + gap), topH - 6 * d, label);
            }
            float rad = 3 * d;
            for (int i = 0; i <= todayIndex; i++) {
                int col = i / 7, row = i % 7;
                float x = left + labelW + col * (size + gap), y = topH + row * (size + gap);
                r.set(x, y, x + size, y + size);
                int color = colorFor(i);
                if (color != 0) {
                    cell.setColor(color);
                    c.drawRoundRect(r, rad, rad, cell);
                } else {
                    outline.setStrokeWidth(d);
                    outline.setColor(BORDER);
                    r.inset(d / 2, d / 2);
                    c.drawRoundRect(r, rad, rad, outline);
                    r.inset(-d / 2, -d / 2);
                }
                if (i == todayIndex || i == selected) {
                    outline.setStrokeWidth(2 * d);
                    outline.setColor(i == selected ? TEXT : MUTED);
                    r.inset(-1.5f * d, -1.5f * d);
                    c.drawRoundRect(r, rad + d, rad + d, outline);
                }
            }
        }

        @Override
        @SuppressWarnings("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float gx = e.getX() - left - labelW;
            int col = (int) Math.floor(gx / (size + gap));
            int row = (int) ((e.getY() - topH) / (size + gap));
            if (gx < 0 || e.getY() < topH || col < 0 || col >= WEEKS || row < 0 || row >= 7) return true;
            int i = col * 7 + row;
            if (i > todayIndex) return true;
            selected = i;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
            onTap.accept(dates[i]);
            return true;
        }
    }

    /** Lays children out left to right, wrapping to a new line when one doesn't fit. */
    static class FlowLayout extends ViewGroup {
        private final int hGap, vGap;

        FlowLayout(Context c, int hGap, int vGap) {
            super(c);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int max = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + ch.getMeasuredWidth() > max) {
                    x = 0;
                    y += lineH + vGap;
                    lineH = 0;
                }
                x += ch.getMeasuredWidth() + hGap;
                lineH = Math.max(lineH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + vGap;
                    lineH = 0;
                }
                ch.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + hGap;
                lineH = Math.max(lineH, h);
            }
        }
    }

    /** Thin horizontal progress bar with rounded ends. */
    static class BarView extends View {
        private final float value;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        BarView(Context c, float value) {
            this(c, value, GOOD);
        }

        BarView(Context c, float value, int color) {
            super(c);
            this.value = Math.max(0, Math.min(1, value));
            track.setColor(BORDER);
            fill.setColor(color);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), rad = h / 2;
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, rad, rad, track);
            if (value > 0) {
                r.set(0, 0, Math.max(h, getWidth() * value), h);
                c.drawRoundRect(r, rad, rad, fill);
            }
        }
    }

    /** 24 thin bars (count per clock hour) on a shared baseline; tap one to select it. */
    static class HourBarsView extends View {
        private final int[] counts;
        private final Consumer<Integer> onTap;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint(Paint.ANTI_ALIAS_FLAG),
                label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float d, labelH;
        private int max = 1, selected = -1;

        HourBarsView(Context c, int[] counts, Consumer<Integer> onTap) {
            super(c);
            this.counts = counts;
            this.onTap = onTap;
            for (int n : counts) max = Math.max(max, n);
            d = getResources().getDisplayMetrics().density;
            labelH = 16 * d;
            base.setColor(BORDER);
            base.setStrokeWidth(d);
            label.setColor(MUTED);
            label.setTextSize(10 * d);
            label.setTextAlign(Paint.Align.CENTER);
        }

        private float slot() {
            return getWidth() / 24f;
        }

        @Override
        protected void onDraw(Canvas c) {
            float slot = slot(), w = Math.max(2 * d, slot - 3 * d), bottom = getHeight() - labelH, rad = 2 * d;
            for (int h = 0; h < 24; h++) {
                float x = h * slot + (slot - w) / 2;
                if (counts[h] > 0) {
                    float top = bottom - (bottom - 4 * d) * counts[h] / max;
                    // Rounded top, square base on the baseline.
                    path.reset();
                    path.addRoundRect(new RectF(x, top, x + w, bottom),
                            new float[]{rad, rad, rad, rad, 0, 0, 0, 0}, Path.Direction.CW);
                    bar.setColor(selected == -1 || selected == h ? ACCENT : withAlpha(ACCENT, 0x66));
                    c.drawPath(path, bar);
                }
                if (h % 6 == 0) c.drawText(String.format(Locale.US, "%02d", h), x + w / 2, getHeight() - 3 * d, label);
            }
            c.drawLine(0, bottom, getWidth(), bottom, base);
        }

        @Override
        @SuppressWarnings("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
            int h = (int) (e.getX() / slot());
            if (h < 0 || h > 23) return true;
            selected = h;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
            onTap.accept(h);
            return true;
        }
    }

    /** One-shot confetti burst: paper rectangles thrown up from the middle, falling under gravity. */
    static class ConfettiView extends View {
        private static final int[] COLORS = {ACCENT, ACCENT2, GOOD, STREAK_ORANGE, 0xFFFFD166, 0xFFF2F4F8};
        private final int n;
        private final float[] x, y, vx, vy, rot, vr, w, h;
        private final int[] color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final java.util.Random rnd = new java.util.Random();
        private ValueAnimator anim;
        private float t, gravity;

        ConfettiView(Context c, int n) {
            super(c);
            this.n = n;
            x = new float[n]; y = new float[n]; vx = new float[n]; vy = new float[n];
            rot = new float[n]; vr = new float[n]; w = new float[n]; h = new float[n];
            color = new int[n];
        }

        @Override
        protected void onSizeChanged(int W, int H, int ow, int oh) {
            float d = getResources().getDisplayMetrics().density;
            gravity = H * 1.4f;
            for (int i = 0; i < n; i++) {
                x[i] = W / 2f + (rnd.nextFloat() - 0.5f) * W * 0.3f;
                y[i] = H * 0.42f;
                vx[i] = (rnd.nextFloat() - 0.5f) * W * 1.6f;
                vy[i] = -(H * 0.5f + rnd.nextFloat() * H * 0.9f);
                rot[i] = rnd.nextFloat() * 360;
                vr[i] = (rnd.nextFloat() - 0.5f) * 900;
                w[i] = (6 + rnd.nextFloat() * 6) * d;
                h[i] = (3 + rnd.nextFloat() * 4) * d;
                color[i] = COLORS[rnd.nextInt(COLORS.length)];
            }
        }

        void start() {
            anim = ValueAnimator.ofFloat(0f, 3.2f);
            anim.setDuration(3200);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (t <= 0) return;
            // Air drag: slow the sideways drift over time.
            float drag = (float) Math.exp(-1.2 * t);
            int alpha = t < 2.4f ? 255 : (int) (255 * Math.max(0, (3.2f - t) / 0.8f));
            for (int i = 0; i < n; i++) {
                float px = x[i] + vx[i] * (1 - drag) / 1.2f;
                float py = y[i] + vy[i] * t + 0.5f * gravity * t * t;
                if (py > getHeight() + 20) continue;
                paint.setColor(color[i]);
                paint.setAlpha(alpha);
                c.save();
                c.translate(px, py);
                c.rotate(rot[i] + vr[i] * t);
                // Fake 3D flutter by squashing the height.
                float flutter = (float) Math.abs(Math.cos((rot[i] + vr[i] * t) * Math.PI / 90));
                c.drawRect(-w[i] / 2, -h[i] * flutter / 2, w[i] / 2, h[i] * flutter / 2, paint);
                c.restore();
            }
        }
    }

    /**
     * Vertical list whose children can be dragged to reorder (started from a handle via {@link #startDrag}).
     * Neighbours are moved around the dragged row, never the row itself, so the touch stream isn't lost.
     */
    static class DragList extends LinearLayout {
        private View dragged;
        private float lastY;
        /** Called after a drop, so the caller can read the new child order. */
        Runnable onReorder;

        DragList(Context c) {
            super(c);
        }

        void startDrag(View child, float rawY) {
            dragged = child;
            lastY = rawY;
            child.setElevation(12 * getResources().getDisplayMetrics().density);
            child.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return dragged != null || super.onInterceptTouchEvent(e);
        }

        private int gap(View v) {
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            return v.getHeight() + lp.topMargin + lp.bottomMargin;
        }

        @Override
        @SuppressWarnings("ClickableViewAccessibility")
        public boolean onTouchEvent(MotionEvent e) {
            if (dragged == null) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - lastY;
                    lastY = e.getRawY();
                    dragged.setTranslationY(dragged.getTranslationY() + dy);
                    int idx = indexOfChild(dragged);
                    float t = dragged.getTranslationY();
                    if (t < 0 && idx > 0) {
                        View prev = getChildAt(idx - 1);
                        int h = gap(prev);
                        if (-t > h / 2f) {
                            // Move the neighbour below us; we now sit one slot higher.
                            removeView(prev);
                            addView(prev, idx);
                            dragged.setTranslationY(t + h);
                            prev.setTranslationY(-h);
                            prev.animate().translationY(0).setDuration(150).start();
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                    } else if (t > 0 && idx < getChildCount() - 1) {
                        View next = getChildAt(idx + 1);
                        int h = gap(next);
                        if (t > h / 2f) {
                            removeView(next);
                            addView(next, idx);
                            dragged.setTranslationY(t - h);
                            next.setTranslationY(h);
                            next.animate().translationY(0).setDuration(150).start();
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    final View v = dragged;
                    dragged = null;
                    if (onReorder != null) onReorder.run();
                    v.animate().translationY(0).scaleX(1f).scaleY(1f).setDuration(180)
                            .withEndAction(() -> v.setElevation(0)).start();
                    return true;
                }
            }
            return true;
        }
    }
}
