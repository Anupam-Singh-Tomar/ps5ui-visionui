package io.freemytv.cinema;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The top right corner, beside the clock: the picture of who uses the TV (their initial on a colour of their own), in
 * the empty space above the shows. Up from the shows reaches it; OK drops a small menu under it: Switch account
 * (Google TV's own picker), Free memory and Home settings (Free My TV's), TV settings. Free My TV tells us who chose
 * which account on that picker (CornerReceiver); until then it's a plain person.
 */
final class Corner {
    static final int SWITCH = 0, FREE_MEMORY = 1, HOME_SETTINGS = 2, TV_SETTINGS = 3;
    private static final String[] LABELS = {"Switch account", "Free memory", "Home settings", "TV settings"};
    private static final String FREE_MY_TV = "io.freemytv";
    private static final String PROFILES = "com.google.android.apps.tv.launcherx/.profile.chooser.ProfileChooserActivity";
    /** One colour per person, picked by their name: muted, so the white initial reads on all of them. */
    private static final int[] COLORS = {0xFF7F77DD, 0xFF1D9E75, 0xFFD85A30, 0xFFD4537E, 0xFF378ADD, 0xFFBA7517};
    static final float SIZE = 44;   // the picture, on the 1920 grid

    boolean open;
    int focus;
    private final Context context;
    private final SharedPreferences prefs;
    private final Runnable redraw;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String account;
    private String status;     // Free memory's progress or result, shown in its row for a while
    private long statusUntil;
    private boolean freeing;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final RectF r = new RectF();
    private final Path path = new Path();

    private final SharedPreferences.OnSharedPreferenceChangeListener changed = this::changed;   // held: prefs keep it weakly

    Corner(Context c, SharedPreferences prefs, Runnable redraw) {
        context = c;
        this.prefs = prefs;
        this.redraw = redraw;
        account = prefs.getString("account", "");
        prefs.registerOnSharedPreferenceChangeListener(changed);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
    }

    /** From CornerReceiver: who chose which account, what Free memory freed. */
    private void changed(SharedPreferences p, String key) {
        if ("account".equals(key)) {
            account = p.getString("account", "");
        } else if ("freedAt".equals(key)) {
            String result = p.getString("freed", "");
            freeing = false;
            show(result.isEmpty() ? "Already freeing memory" : result, 10_000);
        }
        redraw.run();
    }

    // MARK: - Keys

    void toggle() {
        open = !open;
        focus = 0;
    }

    /** Up and Down in the open menu. False at either end (nowhere to go). */
    boolean move(int dy) {
        int next = Math.max(0, Math.min(LABELS.length - 1, focus + dy));
        if (next == focus) return false;
        focus = next;
        return true;
    }

    /** OK on the focused row. Free memory keeps the menu open to show what it freed; the rest close it. */
    void activate() {
        switch (focus) {
            case SWITCH:
                start(new Intent().setComponent(ComponentName.unflattenFromString(PROFILES)));
                open = false;
                break;
            case FREE_MEMORY:
                if (freeing) break;
                freeing = true;
                show("Freeing memory…", 15_000);
                askFreeMemory();
                break;
            case HOME_SETTINGS:
                Intent home = context.getPackageManager().getLeanbackLaunchIntentForPackage(FREE_MY_TV);
                if (home == null) home = context.getPackageManager().getLaunchIntentForPackage(FREE_MY_TV);
                start(home);
                open = false;
                break;
            default:
                start(new Intent(Settings.ACTION_SETTINGS));
                open = false;
                break;
        }
    }

    /** Free My TV does it (it has the TV's ADB) and answers through CornerReceiver; it checks the ask is ours. */
    private void askFreeMemory() {
        Intent ask = new Intent("io.freemytv.FREE_MEMORY").setPackage(FREE_MY_TV);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                context.sendBroadcast(ask, null,
                        android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            } else {
                context.sendBroadcast(ask);
            }
        } catch (RuntimeException e) {
            freeing = false;
            show("Free My TV isn't installed", 5_000);
            return;
        }
        handler.postDelayed(() -> {
            if (!freeing) return;
            freeing = false;
            show("Free My TV didn't answer", 5_000);
            redraw.run();
        }, 15_000);
    }

    private void show(String s, long ms) {
        status = s;
        statusUntil = System.currentTimeMillis() + ms;
        handler.postDelayed(redraw, ms + 50);   // gone again after that
    }

    private void start(Intent i) {
        if (i == null) return;
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {}
    }

    // MARK: - Drawing

    /** The picture, centred on (cx, cy), `radius` across half; a ring round it when focused. */
    void drawPicture(Canvas c, float cx, float cy, float radius, float k, boolean focused) {
        boolean known = account != null && !account.isEmpty();
        fill.setColor(known ? COLORS[Math.abs(account.hashCode()) % COLORS.length] : 0xFF3A3D46);
        c.drawCircle(cx, cy, radius, fill);
        if (known) {
            text.setTypeface(bold);
            text.setTextSize(radius * 0.95f);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            String initial = account.substring(0, account.offsetByCodePoints(0, 1)).toUpperCase();
            c.drawText(initial, cx, cy + radius * 0.34f, text);
            text.setTextAlign(Paint.Align.LEFT);
        } else {
            person(c, cx, cy + radius * 0.05f, radius * 1.1f, 0xE6FFFFFF);
        }
        if (focused) {
            ink.setColor(0xF2FFFFFF);
            ink.setStrokeWidth(3 * k);
            c.drawCircle(cx, cy, radius + 6 * k, ink);
        }
    }

    /** The menu, hanging from the top right with its right edge at `right`, its top at `top`. */
    void drawMenu(Canvas c, float right, float top, float k) {
        float w = 460 * k, pad = 12 * k, headH = 100 * k, rowH = 64 * k;
        float h = pad + headH + LABELS.length * rowH + pad;
        r.set(right - w, top, right, top + h);
        fill.setColor(0xF51C1F26);
        c.drawRoundRect(r, 24 * k, 24 * k, fill);
        ink.setColor(0x26FFFFFF);
        ink.setStrokeWidth(1.5f * k);
        c.drawRoundRect(r, 24 * k, 24 * k, ink);

        // Who: the picture, the name, and what it is
        float x = r.left + pad + 14 * k, cy = top + pad + headH / 2;
        drawPicture(c, x + 26 * k, cy, 26 * k, k, false);
        boolean known = account != null && !account.isEmpty();
        text.setTypeface(bold);
        text.setTextSize(28 * k);
        text.setColor(Color.WHITE);
        float tx = x + 72 * k, tw = right - pad - 14 * k - tx;   // not r: the picture's person drew with it
        c.drawText(TextUtils.ellipsize(known ? account : "Google TV", text, tw, TextUtils.TruncateAt.END).toString(), tx, cy - 2 * k, text);
        text.setTypeface(regular);
        text.setTextSize(19 * k);
        text.setColor(0x99FFFFFF);
        c.drawText(known ? "Google TV account" : "Choose an account to show it here", tx, cy + 26 * k, text);
        fill.setColor(0x1FFFFFFF);
        c.drawRect(r.left + pad, top + pad + headH - 1 * k, r.right - pad, top + pad + headH, fill);

        // The rows
        boolean statusOn = status != null && System.currentTimeMillis() < statusUntil;
        for (int i = 0; i < LABELS.length; i++) {
            float y = top + pad + headH + i * rowH;
            boolean on = i == focus;
            if (on) {
                r.set(right - w + pad, y + 4 * k, right - pad, y + rowH - 4 * k);
                fill.setColor(0xFFF2F2F5);
                c.drawRoundRect(r, 14 * k, 14 * k, fill);
            }
            int color = on ? 0xFF111114 : 0xE6FFFFFF;
            icon(c, i, right - w + pad + 34 * k, y + rowH / 2, 15 * k, color, k);
            String label = i == FREE_MEMORY && statusOn ? status : LABELS[i];
            text.setTextSize((i == FREE_MEMORY && statusOn ? 22 : 25) * k);
            text.setColor(color);
            float lx = right - w + pad + 68 * k;
            c.drawText(TextUtils.ellipsize(label, text, right - pad - 16 * k - lx, TextUtils.TruncateAt.END).toString(),
                    lx, y + rowH / 2 + 9 * k, text);
        }
    }

    /** The rows' icons, drawn: two people, a sparkle, a house, a dial (as the Inputs' TV Settings card). */
    private void icon(Canvas c, int which, float cx, float cy, float s, int color, float k) {
        ink.setColor(color);
        ink.setStrokeWidth(2.4f * k);
        switch (which) {
            case SWITCH:
                person(c, cx + s * 0.45f, cy, s * 1.2f, color);
                person(c, cx - s * 0.45f, cy + s * 0.08f, s * 1.3f, color);
                break;
            case FREE_MEMORY:   // a four-pointed sparkle, and a small one
                sparkle(c, cx - s * 0.15f, cy + s * 0.1f, s * 0.95f);
                sparkle(c, cx + s * 0.75f, cy - s * 0.65f, s * 0.4f);
                break;
            case HOME_SETTINGS:
                path.reset();
                path.moveTo(cx - s * 0.9f, cy - s * 0.05f);
                path.lineTo(cx, cy - s * 0.85f);
                path.lineTo(cx + s * 0.9f, cy - s * 0.05f);
                path.moveTo(cx - s * 0.65f, cy - s * 0.25f);
                path.lineTo(cx - s * 0.65f, cy + s * 0.8f);
                path.lineTo(cx + s * 0.65f, cy + s * 0.8f);
                path.lineTo(cx + s * 0.65f, cy - s * 0.25f);
                c.drawPath(path, ink);
                break;
            default:
                c.drawCircle(cx, cy, s * 0.8f, ink);
                c.drawCircle(cx, cy, s * 0.27f, ink);
                break;
        }
    }

    /** A person: a round head over curved shoulders, `s` tall. */
    private void person(Canvas c, float cx, float cy, float s, int color) {
        ink.setColor(color);
        float stroke = ink.getStrokeWidth();
        ink.setStrokeWidth(Math.max(2f, s * 0.11f));
        c.drawCircle(cx, cy - s * 0.2f, s * 0.2f, ink);
        r.set(cx - s * 0.36f, cy + s * 0.12f, cx + s * 0.36f, cy + s * 0.68f);
        c.drawArc(r, 200, 140, false, ink);
        ink.setStrokeWidth(stroke);
    }

    private void sparkle(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.moveTo(cx, cy - s);
        path.quadTo(cx, cy, cx + s, cy);
        path.quadTo(cx, cy, cx, cy + s);
        path.quadTo(cx, cy, cx - s, cy);
        path.quadTo(cx, cy, cx, cy - s);
        c.drawPath(path, ink);
    }
}
