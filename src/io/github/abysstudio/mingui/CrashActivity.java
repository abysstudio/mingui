package io.github.abysstudio.mingui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Deliberately tiny and independent of the rest of the app (so it can't crash the same way):
 * shows the last crash report with Share and Continue.
 */
public class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File f = App.crashFile(this);
        String report;
        try {
            report = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            report = "(no report)";
        }
        final String text = report;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0918);
        int pad = (int) (20 * d);
        root.setPadding(pad, pad * 2, pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText(R.string.crash_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText(R.string.crash_body);
        sub.setTextColor(0xFFA9A3C9);
        sub.setTextSize(15);
        sub.setPadding(0, (int) (8 * d), 0, (int) (12 * d));
        root.addView(sub);

        ScrollView sc = new ScrollView(this);
        TextView log = new TextView(this);
        log.setText(text);
        log.setTextColor(0xFFD9D5F2);
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        log.setBackgroundColor(0xFF1A1533);
        sc.addView(log);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, (int) (12 * d), 0, 0);
        Button share = new Button(this);
        share.setText(R.string.crash_share);
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Mingui crash report")
                .putExtra(Intent.EXTRA_TEXT, text), null)));
        Button go = new Button(this);
        go.setText(R.string.crash_continue);
        go.setOnClickListener(v -> {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        buttons.addView(share);
        buttons.addView(go);
        root.addView(buttons);
        setContentView(root);
    }
}
