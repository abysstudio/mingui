package io.github.abysstudio.mingui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** On boot and at midnight: re-apply the blocked apps' hidden state and schedule the next midnight check. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prompts.ACTION_NUDGE.equals(intent.getAction())) {
            Prompts.scheduleNext(context);
            String cued = Prompts.cueDueNow(context);
            if (cued != null && !Routine.onBreak(context) && !Routine.pausedToday(context)) {
                Prompts.cueNudge(context, cued, null);
                return;
            }
            if (!Routine.isDoneToday(context) && !Routine.onBreak(context) && !Routine.pausedToday(context)) {
                try {
                    MainActivity.launch(context);
                } catch (RuntimeException ignored) {
                    // Background start refused; the next unlock or nudge will try again.
                }
            }
            return;
        }
        final boolean boot = !Gate.ACTION_MIDNIGHT.equals(intent.getAction());
        final PendingResult pending = goAsync();
        Gate.scheduleMidnight(context);
        Prompts.scheduleNext(context);
        // New routine day: cover missed days with banked freezes, refresh the widget.
        History.applyFreezes(context);
        RoutineWidget.updateAll(context);
        Gate.sync(context, true, ok -> {
            UnlockWatcher.update(context);
            if (boot && !Routine.isDoneToday(context)) {
                try {
                    MainActivity.launch(context);
                } catch (RuntimeException ignored) {
                    // Background activity start can still be refused (e.g. before unlock).
                }
            }
            pending.finish();
        });
    }
}
