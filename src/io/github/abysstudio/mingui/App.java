package io.github.abysstudio.mingui;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Catches crashes (in the app, the overlay service or the widget) and saves a report on the phone,
 * so the next launch can show it and offer to share it. Nothing is ever sent automatically.
 */
public class App extends Application {
    static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler next = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                save(this, thread, e);
            } catch (Throwable ignored) {
                // Never let the reporter itself get in the way of the real crash.
            }
            if (next != null) next.uncaughtException(thread, e);
        });
    }

    static File crashFile(Context c) {
        return new File(c.createDeviceProtectedStorageContext().getFilesDir(), CRASH_FILE);
    }

    private static void save(Context c, Thread thread, Throwable e) throws Exception {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        String version;
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception x) {
            version = "?";
        }
        String report = c.getPackageName() + " " + version + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Thread: " + thread.getName() + "\n"
                + "Time: " + new java.util.Date() + "\n\n" + trace;
        try (FileOutputStream out = new FileOutputStream(crashFile(c))) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        }
    }
}
