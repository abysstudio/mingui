package io.github.abysstudio.mingui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Habit taps from the home-screen widget. Not exported, so other apps can't tick your habits. */
public class WidgetActions extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (RoutineWidget.ACTION_TOGGLE.equals(intent.getAction())) {
            RoutineWidget.toggle(c, intent.getStringExtra("item"));
        }
    }
}
