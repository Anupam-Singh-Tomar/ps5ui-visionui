package io.freemytv.cinema;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Home settings: which home screen the remote's Home button opens (ps5UI, visionUI, or Google TV's own), and whether
 * Google TV's account picker comes first when the TV wakes. Picking ps5UI or visionUI turns on the Home button
 * (KeyService) through the TV's own ADB, as Free My TV does; where debugging is off, it says how to turn it on in
 * Settings instead. The choices are small files, so the Home button's own process (:keys) reads them as they are now.
 */
public class HomeSetup extends Activity {
    static final String PS5 = "ps5", VISION = "vision", OFF = "off";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, picker, accessibility;
    private final TextView[] homes = new TextView[3];
    private static final String[] HOME_IDS = {PS5, VISION, OFF};
    private static final String[] HOME_LABELS = {"ps5UI", "visionUI", "Google TV"};
    private boolean working;

    /** Once, the first time ps5UI or visionUI opens without the Home button on. */
    static void firstRun(Activity a) {
        File seen = new File(a.getFilesDir(), "setup-seen");
        if (seen.exists() || KeyService.on(a) || KeyService.freeMyTvOn(a)) return;
        write(a, "setup-seen", "1");
        a.startActivity(new Intent(a, HomeSetup.class));
    }

    static String home(Context c) {
        String s = read(c, "home");
        return s.equals(VISION) || s.equals(OFF) ? s : PS5;
    }

    static boolean picker(Context c) {
        return !read(c, "picker").equals("0");
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF101217);
        page.setPadding(dp(96), dp(64), dp(96), dp(48));

        page.addView(text("Home button", 40, Typeface.BOLD, 0xFFFFFFFF));
        TextView intro = text("Pick the home screen the remote's Home button opens, from any app. Google TV's own home "
                + "stays on underneath, so nothing on the TV is turned off.", 20, Typeface.NORMAL, 0xB3FFFFFF);
        intro.setPadding(0, dp(12), 0, dp(28));
        page.addView(intro);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < homes.length; i++) {
            String id = HOME_IDS[i];
            homes[i] = button(HOME_LABELS[i], v -> choose(id));
            row.addView(homes[i], spaced());
        }
        page.addView(row);

        picker = button("", v -> {
            write(this, "picker", picker(this) ? "0" : "1");
            update();
        });
        LinearLayout.LayoutParams lp = spaced();
        lp.topMargin = dp(24);
        if (installed("com.google.android.apps.tv.launcherx")) page.addView(picker, lp);

        status = text("", 22, Typeface.NORMAL, 0xFFFFFFFF);
        status.setPadding(0, dp(32), 0, dp(16));
        page.addView(status);

        accessibility = button("Open Accessibility settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        accessibility.setVisibility(View.GONE);
        page.addView(accessibility, spaced());

        setContentView(page);
        update();
        homes[indexOf(home(this))].requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!working) update();   // back from Accessibility settings
    }

    private void choose(String id) {
        if (working) return;
        write(this, "home", id);
        update();
        if (id.equals(OFF) || KeyService.on(this) || KeyService.freeMyTvOn(this)) return;
        working = true;
        status.setText("Turning on the Home button…");
        new Thread(() -> {
            String error = null;
            try (Adb adb = Adb.connect(this, 60_000, () -> handler.post(() -> status.setText(
                    "On the TV's \"Allow USB debugging?\" question, tick Always allow and pick Allow.")))) {
                enable(adb);
            } catch (IOException e) {
                error = e.getMessage();
            }
            String failed = error;
            handler.post(() -> {
                working = false;
                update();
                if (failed != null && !KeyService.on(this)) {
                    status.setText("Couldn't turn it on: " + failed + ".\n\nEither turn on USB debugging (Settings > "
                            + "System > About: press OK on Android TV OS build 7 times, then Settings > System > "
                            + "Developer options > USB debugging) and pick again, or turn on ps5UI Home button in "
                            + "Accessibility settings.");
                    accessibility.setVisibility(View.VISIBLE);
                }
            });
        }, "home-setup").start();
    }

    /** Adds ours to the accessibility services on, keeping the others (TalkBack and the like). */
    private static void enable(Adb adb) throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        StringBuilder list = new StringBuilder();
        for (String s : current.split(":")) {
            if (s.isEmpty() || s.equals("null") || s.equals(KeyService.NAME)) continue;
            if (!s.matches("[\\w.]+/[\\w.$]+")) throw new IOException("unexpected accessibility setting");
            list.append(s).append(':');
        }
        list.append(KeyService.NAME);
        adb.shell("settings put secure enabled_accessibility_services '" + list + "'");
        adb.shell("settings put secure accessibility_enabled 1");
    }

    private void update() {
        String home = home(this);
        for (int i = 0; i < homes.length; i++) homes[i].setSelected(HOME_IDS[i].equals(home));
        picker.setText("Choose an account when the TV wakes: " + (picker(this) ? "On" : "Off"));
        accessibility.setVisibility(View.GONE);
        String name = HOME_LABELS[indexOf(home)];
        if (KeyService.freeMyTvOn(this)) {
            status.setText("Free My TV handles the Home button on this TV. To open " + name + " with it, pick "
                    + name + " in Free My TV > Home Settings > Home Button.");
        } else if (home.equals(OFF)) {
            status.setText("Home opens Google TV's home screen.");
        } else if (KeyService.on(this)) {
            status.setText("Home opens " + name + ".");
        } else {
            status.setText("The Home button is off: pick " + name + " to turn it on.");
        }
    }

    // MARK: - Views

    private TextView text(String s, int sp, int style, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setTextColor(color);
        return t;
    }

    /** A pill: white when focused, a soft glass one otherwise, with a ring when it's the one picked. */
    private TextView button(String s, View.OnClickListener click) {
        TextView b = text(s, 24, Typeface.BOLD, 0);
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xFF111114, 0xFFFFFFFF}));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(36), dp(16), dp(36), dp(16));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, pill(0xFFF2F2F5, 0));
        bg.addState(new int[] {android.R.attr.state_selected}, pill(0x26FFFFFF, 0xFFFFFFFF));
        bg.addState(new int[] {}, pill(0x1AFFFFFF, 0));
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        return b;
    }

    private GradientDrawable pill(int color, int ring) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(40));
        if (ring != 0) d.setStroke(dp(2), ring);
        return d;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(20);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int indexOf(String id) {
        for (int i = 0; i < HOME_IDS.length; i++) if (HOME_IDS[i].equals(id)) return i;
        return 0;
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // MARK: - The choices, as small files

    private static String read(Context c, String name) {
        try {
            return new String(Files.readAllBytes(new File(c.getFilesDir(), name).toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(Context c, String name, String value) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }
}
