package io.github.abysstudio.mingui;

/**
 * Our own alarms (day boundary, nudges). Not exported: since Android 15, explicit intents to an exported
 * component must match its intent filters, and these private actions shouldn't be public anyway.
 * Same handling as {@link BootReceiver}.
 */
public class AlarmReceiver extends BootReceiver {
}
