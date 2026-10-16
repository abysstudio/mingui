package io.github.abysstudio.mingui;

import android.content.Context;

import java.io.InputStream;
import java.util.Set;

/**
 * Everything that needs root, in one place. The Lite (Play) build swaps this file for a stub
 * (variants/lite/src/.../RootOps.java), so it contains no su or pm calls at all.
 */
final class RootOps {
    private RootOps() {}

    /** Hides {@code hide}, unhides {@code show}, and grants our own appops. Returns whether root worked. */
    static boolean apply(Context c, Set<String> hide, Set<String> show) {
        // SYSTEM_ALERT_WINDOW lets us open the checklist from the background; POST_NOTIFICATION ignore
        // silences the watcher's mandatory foreground-service notification; GET_USAGE_STATS lets
        // focus lock see when you're in an allowed app.
        String me = c.getPackageName();
        StringBuilder cmd = new StringBuilder("appops set " + me + " SYSTEM_ALERT_WINDOW allow; "
                + "appops set " + me + " POST_NOTIFICATION ignore; "
                + "appops set " + me + " GET_USAGE_STATS allow; "
                + "appops set " + me + " ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1; "
                // Battery: exempt from Doze so the watcher isn't stopped (what the Lite setup asks for).
                + "dumpsys deviceidle whitelist +" + me + " >/dev/null 2>&1; ");
        // What we hid is also recorded outside our data (/data/adb survives an uninstall), so a
        // reinstall still unhides apps an earlier install hid.
        String list = String.join(" ", hide);
        cmd.append("F=/data/adb/mingui-hidden; for p in $(cat $F 2>/dev/null); do case \" ").append(list)
                .append(" \" in *\" $p \"*) ;; *) pm unhide $p >/dev/null 2>&1; pm enable $p >/dev/null 2>&1;; esac; done; ");
        for (String pkg : hide) {
            cmd.append("pm hide ").append(pkg).append(" >/dev/null 2>&1 || pm disable-user --user 0 ")
                    .append(pkg).append(" >/dev/null 2>&1; ");
        }
        for (String pkg : show) {
            cmd.append("pm unhide ").append(pkg).append(" >/dev/null 2>&1; pm enable ")
                    .append(pkg).append(" >/dev/null 2>&1; ");
        }
        cmd.append("echo '").append(list).append("' > $F; true");
        return su(cmd.toString());
    }

    private static boolean su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[1024];
            while (in.read(buf) != -1) { /* drain */ }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
