package io.github.abysstudio.mingui;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

/** Special permissions the no-root build needs the user to grant by hand (root grants them itself). */
final class Perms {
    private Perms() {}

    /** Usage access: to see which app is in front (blocking, allowed work apps). */
    @SuppressWarnings("deprecation")
    static boolean usageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        int mode = Build.VERSION.SDK_INT >= 29
                ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Display over other apps: to open the checklist from the background, and for the buddy. */
    static boolean overlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Not killed by battery saving: keeps the blocker and buddy running. Recommended, not required. */
    static boolean batteryExempt(Context c) {
        return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static boolean notifications(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * True when Android may block Usage access as a "Restricted setting": Android 13+ and not installed
     * by Google Play. (Whether it's actually been allowed can't be read reliably on Android 15+.)
     */
    static boolean maybeRestricted(Context c) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            String installer = c.getPackageManager().getInstallSourceInfo(c.getPackageName()).getInstallingPackageName();
            return !"com.android.vending".equals(installer);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Permissions are asked for only when a feature that uses them is on. Blocking apps and Focus lock
     * need both Usage access (which app is open) and the overlay (show the checklist over it).
     */
    static boolean blockingOn(Context c) {
        return !Routine.blockedApps(c).isEmpty() || Routine.prefs(c).getBoolean("focus_lock", false);
    }

    static boolean needsUsage(Context c) {
        return blockingOn(c);
    }

    static boolean needsOverlay(Context c) {
        return blockingOn(c);
    }

    /** The buddy also draws over other apps, but it's optional, so it's only recommended. */
    static boolean wantsOverlay(Context c) {
        return needsOverlay(c) || Routine.buddy(c);
    }

    /** Anything running in the background that Android's battery saver could stop. */
    static boolean wantsBattery(Context c) {
        return blockingOn(c) || Routine.buddy(c);
    }

    static boolean requiredGranted(Context c) {
        return (!needsUsage(c) || usageAccess(c)) && (!needsOverlay(c) || overlay(c));
    }

    /** True when every permission worth having for the current features is granted. */
    static boolean allWanted(Context c) {
        return requiredGranted(c) && (!wantsOverlay(c) || overlay(c)) && notifications(c)
                && (!wantsBattery(c) || batteryExempt(c));
    }
}
