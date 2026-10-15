package io.github.abysstudio.mingui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Set;

/**
 * Runs while the buddy is on, or today's routine is unfinished and "Open on unlock" or "Focus lock" is on.
 * Hosts the buddy overlay.
 * Opens the checklist on unlock, and with focus lock pulls you back to it whenever you leave
 * (except during calls, with the screen off, or on a break).
 * USER_PRESENT can't be received by a manifest receiver, so this needs a live (foreground) service.
 */
public class UnlockWatcher extends Service {
    private static final String CHANNEL = "watcher";
    private static UnlockWatcher instance;
    private Buddy buddy;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pull = this::pullBack;
    private final Runnable guard = this::guardBlockedApps;

    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Routine.isDoneToday(c)) {
                if (!shouldRun(c)) stopSelf();
                return;
            }
            if (Routine.onBreak(c)) return;
            // First unlock of the day = "after waking up" cues.
            String today = Routine.today();
            if (!today.equals(Routine.prefs(c).getString("first_unlock_day", ""))) {
                Routine.prefs(c).edit().putString("first_unlock_day", today).apply();
                Set<String> checked = Routine.checked(c);
                for (String h : HabitMeta.ordered(c, Routine.items(c))) {
                    HabitMeta.Cue cue = HabitMeta.cue(c, h);
                    if (!checked.contains(h) && cue.type == HabitMeta.Cue.AFTER
                            && HabitMeta.isWake(c, cue.anchor)) {
                        Prompts.cueNudge(c, h, c.getString(R.string.good_morning_cue, cue.describe(c)));
                        return;
                    }
                }
            }
            if (Routine.focusLock(c)) {
                MainActivity.launch(c);
            } else if (Routine.openOnUnlock(c) && Prompts.shouldPromptOnUnlock(c)) {
                // Cooldown / daily cap / firm-hours rules keep unlock prompts from becoming wallpaper.
                Prompts.recordUnlockPrompt(c);
                MainActivity.launch(c);
            }
        }
    };

    static boolean shouldRun(Context c) {
        if (!Routine.isConfigured(c)) return false;
        boolean done = Routine.isDoneToday(c);
        // Without root, blocked apps can't be hidden, so we have to be running to cover them.
        boolean liteBlocking = !BuildFlags.ROOT && !done && !Routine.pausedToday(c) && !Routine.blockedApps(c).isEmpty();
        return Routine.buddy(c) || liteBlocking || ((Routine.openOnUnlock(c) || Routine.focusLock(c)) && !done);
    }

    /** Says something through the buddy if it's on screen. Returns false if it isn't. */
    static boolean buddySay(String text) {
        return instance != null && instance.buddy != null && instance.buddy.sayFromOutside(text);
    }

    /** Shows or hides the buddy to match its setting. */
    static void buddyChanged(Context c) {
        if (instance != null) instance.syncBuddy();
        update(c);
    }

    private void syncBuddy() {
        if (Routine.buddy(this)) {
            if (buddy == null) buddy = new Buddy(this);
            buddy.show();
        } else if (buddy != null) {
            buddy.hide();
            buddy = null;
        }
    }

    /** Starts or stops the watcher to match the settings and today's state. */
    static void update(Context c) {
        Intent i = new Intent(c, UnlockWatcher.class);
        if (shouldRun(c)) {
            try {
                c.startForegroundService(i);
            } catch (RuntimeException e) {
                // Background FGS start refused; it'll be retried on next app open, boot or midnight.
            }
        } else {
            c.stopService(i);
        }
    }

    /** Nudges the buddy to come back (or duck away) right now, without waiting for its next check. */
    static void pokeBuddy() {
        if (instance != null && instance.buddy != null) instance.buddy.poke();
    }

    /** Called when the checklist goes to the background. */
    static void activityLeft(Context c) {
        if (instance != null) {
            instance.handler.removeCallbacks(instance.pull);
            instance.handler.postDelayed(instance.pull, 600);
        } else {
            update(c);
        }
    }

    private void pullBack() {
        handler.removeCallbacks(pull);
        if (Routine.isDoneToday(this)) {
            if (!shouldRun(this)) stopSelf();
            return;
        }
        if (!Routine.focusLock(this) || MainActivity.visible) return;
        long breakLeft = Routine.breakUntil(this) - System.currentTimeMillis();
        if (breakLeft > 0) {
            handler.postDelayed(pull, breakLeft + 500);
            return;
        }
        // Screen off: the unlock receiver takes over when the phone is next unlocked.
        if (!getSystemService(PowerManager.class).isInteractive()) return;
        int mode = getSystemService(AudioManager.class).getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE) {
            handler.postDelayed(pull, 3000);
            return;
        }
        // Allowed work apps and essentials (settings, phone, installer...) can stay open.
        String fg = Apps.foreground(this);
        if (fg != null && (Routine.allowedApps(this).contains(fg) || Apps.essentials(this).contains(fg))) {
            handler.postDelayed(pull, 1500);
            return;
        }
        MainActivity.launch(this);
        // Retry in case the launch was delayed or refused; stops once the checklist is visible.
        handler.postDelayed(pull, 2000);
    }

    /**
     * No-root blocking: once a second, if a blocked app is in front before the routine is done,
     * cover it with the checklist. (The rooted build hides those apps instead.)
     */
    private void guardBlockedApps() {
        handler.removeCallbacks(guard);
        if (!getSystemService(PowerManager.class).isInteractive()) {
            handler.postDelayed(guard, 2000);
            return;
        }
        if (!Routine.isDoneToday(this) && !Routine.pausedToday(this) && !MainActivity.visible) {
            String fg = Apps.foreground(this);
            if (fg != null && Routine.blockedApps(this).contains(fg) && !Apps.essentials(this).contains(fg)) {
                MainActivity.launch(this);
                android.widget.Toast.makeText(this, getString(R.string.locked_until_done_toast),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        handler.postDelayed(guard, 1000);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_PRESENT));
        syncBuddy();
        if (!BuildFlags.ROOT) handler.post(guard);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_watcher), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(Routine.isDoneToday(this) ? getString(R.string.notif_hanging_out, BuddyStyle.name(this))
                        : getString(R.string.notif_not_done))
                .setContentText(getString(Routine.isDoneToday(this) ? R.string.notif_buddy_on : R.string.notif_blocked))
                .setContentIntent(open)
                .setOngoing(true);
        if (!BuildFlags.ROOT && !Routine.isDoneToday(this)) {
            // Always-available way out: opens the "Pause for today" screen.
            PendingIntent pause = PendingIntent.getActivity(this, 1,
                    new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PAUSE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nb.addAction(new Notification.Action.Builder(null, getString(R.string.pause_for_today), pause).build());
        }
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        // If we were (re)started while the checklist isn't showing, e.g. after boot or a break, pull back.
        handler.removeCallbacks(pull);
        handler.postDelayed(pull, 1000);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(pull);
        handler.removeCallbacks(guard);
        if (buddy != null) buddy.hide();
        unregisterReceiver(unlock);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
