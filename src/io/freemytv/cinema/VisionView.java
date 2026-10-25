package io.freemytv.cinema;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * visionUI: the new shows on your apps as a glass window over a blur of the trending show's art, in visionOS's
 * manner. A glass sidebar on the left (home, all apps, inputs, switch account, settings); in the window, the time,
 * pills that pick a service, and Switch account along the top; Top rated and your apps down the left; the trending
 * show as a big card (it turns every 9 s, or with its arrows); You might like along the bottom. Laid out on a
 * 1920 x 1080 grid. A remote has no pointer, so focus moves to the nearest item in the direction pressed.
 */
final class VisionView extends View implements Choreographer.FrameCallback, Shows.Listener {
    private static final String PROFILES = "com.google.android.apps.tv.launcherx/.profile.chooser.ProfileChooserActivity";
    private static final RectF WINDOW = new RectF(150, 56, 1850, 1024);
    private static final RectF HERO = new RectF(564, 176, 1814, 620);
    private static final float CARD_W = 230, CARD_H = 300, CARD_GAP = 25, CARDS_Y = 690;
    private static final String[] SIDE = {"home", "apps", "inputs", "account", "settings"};
    private static final long HOLD_MS = 550;
    // The apps view: round icons in staggered rows of 7 under the window, which floats back and up
    private static final int COLS = 7;
    private static final float ICON = 140, STEP_X = 220, STEP_Y = 210, FIRST_X = 300, FIRST_Y = 700, VISIBLE_ROWS = 2;
    /** Seconds: the window going back, each icon rising, the gap between one icon and the next. */
    private static final float WINDOW_S = 0.45f, ICON_S = 0.42f, STAGGER_S = 0.035f;

    private float k = 1;
    private List<Shows.Show> shows = Collections.emptyList();
    private boolean loaded;
    private int pill, heroIndex;
    private String focus = "watch";
    private boolean moved;
    private float cardsScroll;
    private boolean animating;
    private long lastFrame;

    private final Map<Integer, Bitmap> backdrops = new LinkedHashMap<Integer, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> e) {
            return size() > 6;
        }
    };
    private final Map<Integer, Bitmap> softs = new HashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Bitmap bgFrom, bgTo;
    private float bgT = 1;

    // Apps (shared with ps5UI: the same row, in the same order)
    private final Map<String, App> apps = new HashMap<>();
    private final List<App> sortedApps = new ArrayList<>();
    private final List<String> favourites = new ArrayList<>();
    private final SharedPreferences prefs;
    /** Your apps first, then the rest A to Z: the apps view's order. */
    private final List<App> homeApps = new ArrayList<>();

    // The apps view: `appsTime` runs from 0 (the window) up while it opens and back down while it closes.
    private boolean appsOpen;
    private float appsTime, appsRowScroll, appsRowTarget;
    private int appFocus;
    /** The window drawn once as it goes back, so the floating icons cost a frame only themselves. */
    private Bitmap snapshot;
    private final Paint snapshotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** What can take focus, laid out again for every frame and every key: an id and its place on screen. */
    private final List<String> ids = new ArrayList<>();
    private final List<RectF> rects = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean okDown, held;
    private final Runnable hold = () -> {
        held = true;
        onHold();
    };

    private final Map<String, Bitmap> badges = new HashMap<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint();
    private final Paint mask = new Paint();
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Path clip = new Path(), shape = new Path();
    private final RectF r = new RectF(), q = new RectF();
    private final Rect src = new Rect();
    private LinearGradient heroShade, heroBottom, cardShade, glassTop;
    private final Map<String, StaticLayout> layouts = new HashMap<>();
    private final DateFormat clock;
    private final SimpleDateFormat date = new SimpleDateFormat("EEE d MMM", Locale.getDefault());

    private static final class App {
        String pkg, label;
        Bitmap banner, round;
    }

    VisionView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = c.getSharedPreferences("cinema", Context.MODE_PRIVATE);
        clock = android.text.format.DateFormat.getTimeFormat(c);
        line.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xF2FFFFFF);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        text.setTypeface(regular);
        body.setTypeface(regular);
        refresh();
        Shows.load(c, this);
    }

    void refresh() {
        loadApps();
        invalidate();
    }

    void reloadShows() {
        Shows.load(getContext(), this);
    }

    /** Every 9 s: the next trending show, unless you're on the hero card. */
    void tick() {
        // Not while the apps view is up: the window it shows is a still picture.
        if (appsTime <= 0 && !focus.equals("watch") && !focus.equals("prev") && !focus.equals("next")) turnHero(1);
        invalidate();   // and the clock
    }

    // MARK: - Data

    @Override public void onShows(List<Shows.Show> list) {
        shows = list;
        loaded = true;
        if (!moved) focus = "watch";   // the first frame came before the shows: start on Watch, not where it fell back to
        for (Shows.Show s : shows) badge(s.service.pkg);
        pill = Math.min(pill, pills().size() - 1);
        heroIndex = 0;
        heroChanged();
    }

    @Override public void onPoster() {
        if (bgTo == null) heroChanged();
        invalidate();
    }

    private void badge(String pkg) {
        if (badges.containsKey(pkg)) return;
        Bitmap b = null;
        try {
            Drawable d = getContext().getPackageManager().getApplicationIcon(pkg);
            int size = 88;
            Bitmap ic = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(ic);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(ic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            new Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f, p);
        } catch (PackageManager.NameNotFoundException ignored) {}
        badges.put(pkg, b);
    }

    private void loadApps() {
        PackageManager pm = getContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> found = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (found.containsKey(pkg) || pkg.equals(getContext().getPackageName())
                    || pkg.equals("com.google.android.apps.tv.launcherx") || pkg.equals("com.google.android.tungsten.setupwraith")
                    || pkg.equals("com.android.tv.settings")) continue;
            App a = apps.get(pkg);
            if (a == null) {
                a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.banner = banner(ri.activityInfo.loadBanner(pm), ri.activityInfo.loadIcon(pm));
                a.round = roundIcon(ri.activityInfo.loadIcon(pm), a.banner);
            }
            found.put(pkg, a);
        }
        apps.clear();
        apps.putAll(found);
        sortedApps.clear();
        sortedApps.addAll(found.values());
        sortedApps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        favourites.clear();
        String saved = prefs.getString("favourites", null);
        if (saved != null) {
            for (String pkg : saved.split(",")) if (apps.containsKey(pkg) && !favourites.contains(pkg)) favourites.add(pkg);
        } else {
            for (Shows.Service s : Shows.SERVICES) if (apps.containsKey(s.pkg) && favourites.size() < 6) favourites.add(s.pkg);
        }
        orderHomeApps();
    }

    private void orderHomeApps() {
        homeApps.clear();
        for (String pkg : favourites) homeApps.add(apps.get(pkg));
        for (App a : sortedApps) if (!favourites.contains(a.pkg)) homeApps.add(a);
        appFocus = Math.min(appFocus, Math.max(0, homeApps.size() - 1));
    }

    /**
     * A round icon, as visionOS draws apps: the app's icon filling a circle, on its banner's colour where the icon
     * leaves gaps.
     */
    private static Bitmap roundIcon(Drawable ic, Bitmap banner) {
        int s = 192;
        Bitmap square = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(square);
        c.drawColor(vivid(banner));
        if (ic != null) {
            ic.setBounds(0, 0, s, s);
            ic.draw(c);
        }
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return b;
    }

    /** The banner's most colourful tone, dimmed a little to sit behind an icon. */
    private static int vivid(Bitmap banner) {
        Bitmap small = Bitmap.createScaledBitmap(banner, 32, 18, true);
        float[] hsv = new float[3];
        double red = 0, green = 0, blue = 0, weight = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int px = small.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                double w = hsv[1] * hsv[1] * hsv[2] + 0.01;
                red += Color.red(px) * w;
                green += Color.green(px) * w;
                blue += Color.blue(px) * w;
                weight += w;
            }
        }
        Color.colorToHSV(Color.rgb((int) (red / weight), (int) (green / weight), (int) (blue / weight)), hsv);
        hsv[2] = Math.min(0.75f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static Bitmap banner(Drawable banner, Drawable ic) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
        } else {
            c.drawColor(0xFF2A2A2E);
            if (ic != null) {
                ic.setBounds(w / 2 - 54, h / 2 - 54, w / 2 + 54, h / 2 + 54);
                ic.draw(c);
            }
        }
        return b;
    }

    private static Bitmap soft(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 24, Math.max(1, 24 * b.getHeight() / b.getWidth()), true);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 96, Math.max(1, 96 * b.getHeight() / b.getWidth()), true);
        return Bitmap.createScaledBitmap(mid, 384, Math.max(1, 384 * b.getHeight() / b.getWidth()), true);
    }

    // MARK: - What's shown

    /** "All", then each service with a show this week. */
    private List<String> pills() {
        List<String> out = new ArrayList<>();
        out.add("All");
        for (Shows.Service s : Shows.SERVICES) {
            for (Shows.Show sh : shows) {
                if (sh.service == s) {
                    out.add(s.label);
                    break;
                }
            }
        }
        return out;
    }

    private List<Shows.Show> filtered() {
        if (pill == 0) return shows;
        String label = pills().get(Math.min(pill, pills().size() - 1));
        List<Shows.Show> out = new ArrayList<>();
        for (Shows.Show s : shows) if (s.service.label.equals(label)) out.add(s);
        return out;
    }

    private Shows.Show hero() {
        List<Shows.Show> list = filtered();
        return list.isEmpty() ? null : list.get(Math.floorMod(heroIndex, list.size()));
    }

    /** The three best rated (else the first three), the hero aside. */
    private List<Shows.Show> topRated() {
        List<Shows.Show> list = new ArrayList<>(filtered());
        list.remove(hero());
        list.sort((a, b) -> Double.compare(b.rating, a.rating));
        return list.size() > 3 ? list.subList(0, 3) : list;
    }

    private List<Shows.Show> liked() {
        List<Shows.Show> list = new ArrayList<>(filtered());
        list.remove(hero());
        return list;
    }

    private void turnHero(int dir) {
        if (filtered().size() < 2) return;
        heroIndex += dir;
        heroChanged();
    }

    /** The background follows the hero: its wide art, else its poster, blurred. */
    private void heroChanged() {
        Shows.Show s = hero();
        Bitmap bg = null;
        if (s != null) {
            Bitmap art = backdrops.get(s.id);
            Bitmap base = art != null ? art : s.image;
            if (base != null) {
                int key = s.id * 2 + (art != null ? 1 : 0);
                bg = softs.get(key);
                if (bg == null) {
                    bg = soft(base);
                    softs.put(key, bg);
                }
            }
            if (art == null) fetchBackdrop(s);
        }
        if (bg != bgTo) {
            bgFrom = bgTo;
            bgTo = bg;
            bgT = 0;
        }
        startAnimation();
    }

    private void fetchBackdrop(Shows.Show s) {
        if (backdrops.containsKey(s.id)) return;
        Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            Bitmap art = Shows.backdrop(app, s);
            if (art == null) return;
            handler.post(() -> {
                backdrops.put(s.id, art);
                if (hero() == s) heroChanged();
            });
        });
    }

    // MARK: - Layout: everything that can take focus, where it is

    private void layout() {
        ids.clear();
        rects.clear();
        for (int i = 0; i < SIDE.length; i++) add("side" + i, 52, 352 + i * 84, 116, 416 + i * 84);
        text.setTypeface(medium);
        text.setTextSize(24);
        float x = 548;
        List<String> labels = pills();
        for (int i = 0; i < labels.size(); i++) {
            float w = text.measureText(labels.get(i)) + 60;
            add("pill" + i, x, 92, x + w, 148);
            x += w + 14;
        }
        add("profile", 1570, 92, 1810, 148);
        List<Shows.Show> top = topRated();
        for (int i = 0; i < top.size(); i++) add("top" + i, 206, 236 + i * 112, 512, 336 + i * 112);
        for (int i = 0; i < Math.min(4, favourites.size()); i++) add("app" + i, 198, 656 + i * 86, 520, 726 + i * 86);
        if (hero() != null) {
            text.setTextSize(24);
            add("watch", 594, 548, 594 + text.measureText(watchLabel()) + 100, 604);
            add("prev", 1690, 206, 1742, 258);
            add("next", 1754, 206, 1806, 258);
        }
        List<Shows.Show> like = liked();
        for (int i = 0; i < like.size(); i++) {
            float cx = 564 + i * (CARD_W + CARD_GAP) - cardsScroll;
            add("card" + i, cx, CARDS_Y, cx + CARD_W, CARDS_Y + CARD_H);
        }
        if (!ids.contains(focus)) focus = ids.contains("watch") ? "watch" : ids.contains("app0") ? "app0" : "side0";
    }

    private void add(String id, float left, float top, float right, float bottom) {
        ids.add(id);
        rects.add(new RectF(left, top, right, bottom));
    }

    private RectF rectOf(String id) {
        int i = ids.indexOf(id);
        return i < 0 ? null : rects.get(i);
    }

    private String watchLabel() {
        Shows.Show s = hero();
        return s == null ? "" : "Watch on " + s.service.label;
    }

    // MARK: - Keys

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return go(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return go(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP: return go(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN: return go(0, 1);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    held = false;
                    handler.postDelayed(hold, HOLD_MS);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (appsOpen) closeApps();
                else focus = "watch";
                startAnimation();
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            handler.removeCallbacks(hold);
            if (okDown && !held) onClick();
            okDown = false;
            return true;
        }
        return super.onKeyUp(keyCode, e);
    }

    /**
     * To the nearest item in the direction pressed: ahead of the focused one, scored by the distance ahead plus the
     * distance aside (aside counts less for items that overlap it across), as Android's own focus search does.
     */
    private boolean go(int dx, int dy) {
        if (appsOpen) {
            int n = homeApps.size(), next = appFocus + dx + dy * COLS;
            if (dy < 0 && next < 0) {
                closeApps();   // Up from the top row: the window comes forward again
            } else if (dx != 0 || next < n) {
                appFocus = Math.max(0, Math.min(n - 1, next));
                keepAppInView();
            }
            startAnimation();
            return true;
        }
        layout();
        RectF from = rectOf(focus);
        if (from == null) return true;
        String best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < ids.size(); i++) {
            RectF to = rects.get(i);
            if (ids.get(i).equals(focus)) continue;
            float ahead, aside;
            boolean overlap;
            // Only what starts past the edge we're leaving counts (a little overlap forgiven), as Android's focus search
            // does: the left column's lower rows are not "below" a card beside them.
            if (dx != 0) {
                ahead = dx > 0 ? to.left - from.right : from.left - to.right;
                if (ahead < -10) continue;
                overlap = to.top < from.bottom && to.bottom > from.top;
                aside = Math.abs(to.centerY() - from.centerY());
            } else {
                ahead = dy > 0 ? to.top - from.bottom : from.top - to.bottom;
                if (ahead < -10) continue;
                overlap = to.left < from.right && to.right > from.left;
                aside = Math.abs(to.centerX() - from.centerX());
            }
            float score = Math.max(0, ahead) + aside * (overlap ? 0.3f : 2.2f);
            if (score < bestScore) {
                bestScore = score;
                best = ids.get(i);
            }
        }
        if (best != null) {
            focus = best;
            moved = true;
            if (focus.startsWith("card")) keepCardInView(Integer.parseInt(focus.substring(4)));
        } else if (dy > 0) {
            openApps();   // Down past the bottom of the window: the apps float up in front of it
        }
        startAnimation();
        return true;
    }

    // MARK: - The apps view

    private void openApps() {
        if (appsOpen || homeApps.isEmpty()) return;
        appsOpen = true;
        if (appsTime <= 0) {
            // A still picture of the window, taken now, is what goes back
            if (snapshot == null || snapshot.getWidth() != getWidth() || snapshot.getHeight() != getHeight()) {
                snapshot = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            }
            snapshot.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(snapshot);
            c.scale(k, k);
            drawWindowAndAll(c, true);
        }
        startAnimation();
    }

    private void closeApps() {
        appsOpen = false;
        startAnimation();
    }

    private void keepAppInView() {
        int row = appFocus / COLS;
        float top = row * STEP_Y - appsRowTarget;
        if (top < 0) appsRowTarget = row * STEP_Y;
        if (top > (VISIBLE_ROWS - 1) * STEP_Y) appsRowTarget = (row - VISIBLE_ROWS + 1) * STEP_Y;
    }

    /** How far the opening has got for the window (0 to 1, eased). */
    private float windowProgress() {
        float t = Math.min(1, appsTime / WINDOW_S);
        return t * t * (3 - 2 * t);
    }

    /** How long the whole opening takes: the window, then the last visible icon rising. */
    private float appsEnd() {
        int visible = Math.min(homeApps.size(), COLS * ((int) VISIBLE_ROWS + 1));
        return Math.max(WINDOW_S, WINDOW_S * 0.4f + visible * STAGGER_S + ICON_S);
    }

    private float cardsTarget;

    private void keepCardInView(int i) {
        float x = 564 + i * (CARD_W + CARD_GAP) - cardsTarget;
        if (x < 564) cardsTarget -= 564 - x;
        if (x + CARD_W > 1814) cardsTarget += x + CARD_W - 1814;
    }

    private void onClick() {
        if (appsOpen) {
            if (appFocus < homeApps.size()) open(homeApps.get(appFocus).pkg);
            return;
        }
        Shows.Show h = hero();
        if (focus.startsWith("side")) {
            switch (SIDE[Integer.parseInt(focus.substring(4))]) {
                case "home": focus = "watch"; break;
                case "apps": openApps(); break;
                case "inputs": start(new Intent("com.android.tv.action.VIEW_INPUTS")); break;
                case "account": start(new Intent().setComponent(ComponentName.unflattenFromString(PROFILES))); break;
                default: start(new Intent(Settings.ACTION_SETTINGS)); break;
            }
        } else if (focus.startsWith("pill")) {
            pill = Integer.parseInt(focus.substring(4));
            heroIndex = 0;
            cardsTarget = 0;
            heroChanged();
        } else if (focus.equals("profile")) {
            start(new Intent().setComponent(ComponentName.unflattenFromString(PROFILES)));
        } else if (focus.startsWith("top")) {
            List<Shows.Show> top = topRated();
            int i = Integer.parseInt(focus.substring(3));
            if (i < top.size()) open(top.get(i).service.pkg);
        } else if (focus.startsWith("app")) {
            int i = Integer.parseInt(focus.substring(3));
            if (i < favourites.size()) open(favourites.get(i));
        } else if (focus.equals("watch")) {
            if (h != null) open(h.service.pkg);
        } else if (focus.equals("prev")) {
            turnHero(-1);
        } else if (focus.equals("next")) {
            turnHero(1);
        } else if (focus.startsWith("card")) {
            List<Shows.Show> like = liked();
            int i = Integer.parseInt(focus.substring(4));
            if (i < like.size()) open(like.get(i).service.pkg);
        }
        startAnimation();
    }

    /**
     * In the apps view, held OK puts the app in Your apps or takes it out (the same row ps5UI shows). The icons keep
     * their places until the view closes, so nothing jumps under the remote.
     */
    private void onHold() {
        if (!appsOpen || appFocus >= homeApps.size()) return;
        String pkg = homeApps.get(appFocus).pkg;
        if (!favourites.remove(pkg)) favourites.add(pkg);
        prefs.edit().putString("favourites", TextUtils.join(",", favourites)).apply();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void open(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        start(i);
    }

    private void start(Intent i) {
        if (i == null) return;
        try {
            getContext().startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {}
    }

    // MARK: - Animation (by time: this TV drops frames when it's busy)

    private void startAnimation() {
        if (!animating) {
            animating = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        invalidate();
    }

    @Override public void doFrame(long frameTimeNanos) {
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (frameTimeNanos - lastFrame) / 1e9f);
        lastFrame = frameTimeNanos;
        float ease = 1 - (float) Math.exp(-dt / 0.085f);
        boolean more = false;
        if (Math.abs(cardsTarget - cardsScroll) > 1) {
            cardsScroll += (cardsTarget - cardsScroll) * ease;
            more = true;
        } else {
            cardsScroll = cardsTarget;
        }
        if (bgT < 1) {
            bgT = Math.min(1, bgT + dt / 0.5f);
            more = true;
        }
        // The apps view opening (time runs up), closing (back down, a little faster), and its rows scrolling
        float end = appsEnd();
        if (appsOpen && appsTime < end) {
            appsTime = Math.min(end, appsTime + dt);
            more = true;
        } else if (!appsOpen && appsTime > 0) {
            appsTime = Math.max(0, appsTime - dt * 1.6f);
            more = true;
        }
        if (appsOpen) more = true;   // open, the icons keep floating
        if (Math.abs(appsRowTarget - appsRowScroll) > 1) {
            appsRowScroll += (appsRowTarget - appsRowScroll) * ease;
            more = true;
        } else {
            appsRowScroll = appsRowTarget;
        }
        invalidate();
        if (more) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            animating = false;
            lastFrame = 0;
        }
    }

    // MARK: - Drawing (in 1920 x 1080 units: the canvas is scaled once)

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        heroShade = new LinearGradient(HERO.left, 0, HERO.left + 820, 0, new int[] {0xE6000000, 0x80000000, 0x00000000},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        heroBottom = new LinearGradient(0, HERO.bottom, 0, HERO.top + 160, new int[] {0xCC000000, 0x00000000}, null, Shader.TileMode.CLAMP);
        cardShade = new LinearGradient(0, 0, 0, 1, new int[] {0x00000000, 0x80000000, 0xF2000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        glassTop = new LinearGradient(0, WINDOW.top, 0, WINDOW.top + 160, new int[] {0x1FFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        mask.setShader(new LinearGradient(0, 0, 360, 0, new int[] {0x00000000, 0xFF000000}, null, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas c) {
        layout();
        c.save();
        c.scale(k, k);
        drawBackground(c);
        if (appsTime <= 0 || snapshot == null) {
            drawWindowAndAll(c, false);
            c.restore();
            return;
        }
        c.restore();
        drawSnapshot(c);
        c.save();
        c.scale(k, k);
        drawApps(c);
        c.restore();
    }

    private boolean still;   // drawing the still picture of the window: no focus on it

    private void drawWindowAndAll(Canvas c, boolean picture) {
        still = picture;
        drawSidebar(c);
        drawWindow(c);
        drawTopBar(c);
        drawTopRated(c);
        drawYourApps(c);
        drawHero(c);
        drawLiked(c);
        still = false;
    }

    /** The window going back: smaller, higher, fainter, from its still picture (in screen pixels). */
    private void drawSnapshot(Canvas c) {
        float w = windowProgress(), scale = 1 - 0.5f * w;
        c.save();
        c.translate(960 * k, (540 - 250 * w) * k);
        c.scale(scale, scale);
        c.translate(-960 * k, -540 * k);
        snapshotPaint.setAlpha(Math.round(255 * (1 - 0.45f * w)));
        c.drawBitmap(snapshot, 0, 0, snapshotPaint);
        c.restore();
    }

    /**
     * The apps, as visionOS's Home View: round icons in staggered rows. Each rises from below in turn, a little past
     * its place and back, and once all are up they float, each on its own slow swell.
     */
    private void drawApps(Canvas c) {
        float start = WINDOW_S * 0.4f;   // the icons begin while the window is still going back
        boolean settled = appsOpen && appsTime >= appsEnd();
        float now = android.os.SystemClock.uptimeMillis() / 1000f;
        int firstRow = (int) (appsRowScroll / STEP_Y);
        for (int i = 0; i < homeApps.size(); i++) {
            App a = homeApps.get(i);
            int row = i / COLS, col = i % COLS;
            float cy = FIRST_Y + row * STEP_Y - appsRowScroll;
            // Only the rows that fit; while the rows scroll, the one leaving and the one coming fade at the edges
            float rel = (cy - FIRST_Y) / STEP_Y;
            if (rel < -0.6f || rel > VISIBLE_ROWS - 0.4f) continue;
            float edge = Math.min(1, Math.min(1 - Math.max(0, -rel) * 1.6f, 1 - Math.max(0, rel - (VISIBLE_ROWS - 1)) * 1.6f));
            float cx = FIRST_X + col * STEP_X + (row % 2 == 1 ? STEP_X / 2 : 0);
            int order = Math.max(0, i - firstRow * COLS);
            float p = Math.max(0, Math.min(1, (appsTime - start - order * STAGGER_S) / ICON_S));
            if (p <= 0) continue;
            float e = easeOutBack(p), alpha = Math.min(1, p * 1.8f) * Math.max(0, edge);
            float scale = 0.5f + 0.5f * e;
            float y = cy + (1 - e) * 180;
            if (settled) y += (float) Math.sin(now * 1.5f + i * 0.9f) * 5;
            boolean lit = appsOpen && i == appFocus;
            if (lit) scale *= 1.14f;
            float rad = ICON / 2 * scale;
            // Its shadow on the floor, its picture, the glass rim
            fill.setColor(Color.argb(Math.round(90 * alpha), 0, 0, 0));
            c.drawOval(cx - rad * 0.8f, y + rad * 0.88f, cx + rad * 0.8f, y + rad * 1.12f, fill);
            q.set(cx - rad, y - rad, cx + rad, y + rad);
            fill.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(a.round, null, q, fill);
            fill.setAlpha(255);
            line.setStrokeWidth(2);
            line.setColor(Color.argb(Math.round(0x59 * alpha), 255, 255, 255));
            c.drawOval(q, line);
            if (lit) {
                q.inset(-8, -8);
                ring.setStrokeWidth(4);
                c.drawOval(q, ring);
            }
            if (favourites.contains(a.pkg)) {   // in Your apps
                fill.setColor(Color.argb(Math.round(255 * alpha), 0x51, 0xB3, 0xF9));
                c.drawCircle(cx + rad * 0.72f, y - rad * 0.72f, 10, fill);
            }
            text.setTypeface(medium);
            text.setTextSize(22);
            text.setColor(Color.argb(Math.round((lit ? 255 : 200) * alpha), 255, 255, 255));
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(TextUtils.ellipsize(a.label, text, STEP_X - 20, TextUtils.TruncateAt.END).toString(),
                    cx, y + rad + 38, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        float hint = Math.min(1, Math.max(0, (appsTime - WINDOW_S) / 0.3f));
        if (hint > 0) {
            text.setTypeface(regular);
            text.setTextSize(20);
            text.setColor(Color.argb(Math.round(150 * hint), 255, 255, 255));
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("OK opens   ·   hold OK to add to Your apps or take out   ·   Up or Back returns", 960, 1056, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** Past its place and back: the overshoot that makes an icon feel like it floated up. */
    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1, u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** The room behind the window: the trending show's art, blurred and dimmed, fading from one to the next. */
    private void drawBackground(Canvas c) {
        c.drawColor(0xFF15151B);
        if (bgFrom != null && bgT < 1) cover(c, bgFrom, 0, 0, 1920, 1080, 255);
        if (bgTo != null) cover(c, bgTo, 0, 0, 1920, 1080, bgFrom == null ? 255 : Math.round(255 * bgT));
        c.drawColor(0x73000000);
    }

    /** A picture filling a box, cut to its shape (keeping the top, where the faces are). */
    private void cover(Canvas c, Bitmap b, float left, float top, float right, float bottom, int alpha) {
        float want = (right - left) / (bottom - top), have = (float) b.getWidth() / b.getHeight();
        if (have > want) {
            int w = Math.round(b.getHeight() * want);
            src.set((b.getWidth() - w) / 2, 0, (b.getWidth() + w) / 2, b.getHeight());
        } else {
            int h = Math.round(b.getWidth() / want);
            int y = Math.round((b.getHeight() - h) * 0.25f);
            src.set(0, y, b.getWidth(), y + h);
        }
        q.set(left, top, right, bottom);
        fill.setAlpha(alpha);
        c.drawBitmap(b, src, q, fill);
        fill.setAlpha(255);
    }

    /** Frosted glass: a brighter copy of the blurred room inside the shape, a light top and a fine edge. */
    private void glass(Canvas c, RectF box, float radius, int tint) {
        c.save();
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(tint);
        c.drawRect(box, fill);
        c.restore();
        line.setStrokeWidth(1.5f);
        line.setColor(0x40FFFFFF);
        c.drawRoundRect(box, radius, radius, line);
    }

    private void drawWindow(Canvas c) {
        glass(c, WINDOW, 52, 0x24FFFFFF);
        c.save();
        clip.reset();
        clip.addRoundRect(WINDOW, 52, 52, Path.Direction.CW);
        c.clipPath(clip);
        shade.setShader(glassTop);
        c.drawRect(WINDOW.left, WINDOW.top, WINDOW.right, WINDOW.top + 160, shade);
        shade.setShader(null);
        c.restore();
        // The two panels on the left
        glass(c, r(186, 176, 532, 590), 34, 0x14FFFFFF);
        glass(c, r(186, 606, 532, 1000), 34, 0x14FFFFFF);
    }

    private RectF r(float left, float top, float right, float bottom) {
        r.set(left, top, right, bottom);
        return r;
    }

    private boolean on(String id) {
        return !still && !appsOpen && id.equals(focus);
    }

    /** Focus: lit glass and a white ring, as a gaze lights a visionOS control. */
    private void focusRing(Canvas c, RectF box, float radius) {
        q.set(box);
        q.inset(-5, -5);
        ring.setStrokeWidth(3);
        c.drawRoundRect(q, radius + 5, radius + 5, ring);
    }

    private void drawSidebar(Canvas c) {
        glass(c, r(40, 336, 128, 772), 44, 0x26FFFFFF);
        for (int i = 0; i < SIDE.length; i++) {
            RectF box = rectOf("side" + i);
            if (box == null) continue;
            boolean lit = on("side" + i);
            if (lit) {
                fill.setColor(0xF2FFFFFF);
                c.drawOval(box, fill);
            }
            drawIcon(c, SIDE[i], box.centerX(), box.centerY(), 30, lit ? 0xFF16161C : 0xE6FFFFFF);
        }
    }

    /** Line icons drawn by hand: home, apps, inputs, account, settings, play, the arrows. */
    private void drawIcon(Canvas c, String name, float cx, float cy, float s, int color) {
        icon.setColor(color);
        icon.setStrokeWidth(s * 0.09f);
        icon.setStyle(Paint.Style.STROKE);
        shape.reset();
        float h = s / 2;
        switch (name) {
            case "home":
                shape.moveTo(cx - h, cy - h * 0.05f);
                shape.lineTo(cx, cy - h * 0.85f);
                shape.lineTo(cx + h, cy - h * 0.05f);
                shape.moveTo(cx - h * 0.72f, cy - h * 0.3f);
                shape.lineTo(cx - h * 0.72f, cy + h * 0.8f);
                shape.lineTo(cx + h * 0.72f, cy + h * 0.8f);
                shape.lineTo(cx + h * 0.72f, cy - h * 0.3f);
                c.drawPath(shape, icon);
                break;
            case "apps":
                for (int i = 0; i < 4; i++) {
                    float x = cx - h * 0.85f + (i % 2) * h, y = cy - h * 0.85f + (i / 2) * h;
                    c.drawRoundRect(x, y, x + h * 0.7f, y + h * 0.7f, h * 0.18f, h * 0.18f, icon);
                }
                break;
            case "inputs":
                shape.moveTo(cx - h, cy - h * 0.45f);
                shape.lineTo(cx + h, cy - h * 0.45f);
                shape.lineTo(cx + h, cy + h * 0.05f);
                shape.lineTo(cx + h * 0.6f, cy + h * 0.45f);
                shape.lineTo(cx - h * 0.6f, cy + h * 0.45f);
                shape.lineTo(cx - h, cy + h * 0.05f);
                shape.close();
                c.drawPath(shape, icon);
                break;
            case "account":
                c.drawCircle(cx, cy - h * 0.35f, h * 0.38f, icon);
                q.set(cx - h * 0.8f, cy + h * 0.2f, cx + h * 0.8f, cy + h * 1.5f);
                c.drawArc(q, 200, 140, false, icon);
                break;
            case "settings":
                c.drawCircle(cx, cy, h * 0.75f, icon);
                c.drawCircle(cx, cy, h * 0.25f, icon);
                break;
            case "play":
                icon.setStyle(Paint.Style.FILL);
                shape.moveTo(cx - h * 0.35f, cy - h * 0.55f);
                shape.lineTo(cx + h * 0.6f, cy);
                shape.lineTo(cx - h * 0.35f, cy + h * 0.55f);
                shape.close();
                c.drawPath(shape, icon);
                break;
            case "prev":
            case "next": {
                float d = name.equals("next") ? 1 : -1;
                shape.moveTo(cx - d * h * 0.25f, cy - h * 0.5f);
                shape.lineTo(cx + d * h * 0.25f, cy);
                shape.lineTo(cx - d * h * 0.25f, cy + h * 0.5f);
                c.drawPath(shape, icon);
                break;
            }
            default:
                break;
        }
    }

    private void drawTopBar(Canvas c) {
        // The time, where the reference has its search
        glass(c, r(190, 92, 520, 148), 28, 0x1FFFFFFF);
        Date now = new Date();
        text.setTypeface(medium);
        text.setTextSize(24);
        text.setColor(0xF2FFFFFF);
        c.drawText(clock.format(now), 220, 129, text);
        text.setTypeface(regular);
        text.setColor(0x99FFFFFF);
        c.drawText(date.format(now), 220 + 20 + measure(clock.format(now), medium, 24), 129, text);
        // Service pills
        List<String> labels = pills();
        for (int i = 0; i < labels.size(); i++) {
            RectF box = rectOf("pill" + i);
            if (box == null) continue;
            boolean picked = i == pill, lit = on("pill" + i);
            fill.setColor(lit ? 0xFFFFFFFF : picked ? 0x99000000 : 0x00000000);
            c.drawRoundRect(box, 28, 28, fill);
            text.setTypeface(medium);
            text.setTextSize(24);
            text.setColor(lit ? 0xFF16161C : picked ? 0xFFFFFFFF : 0xCCFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText(labels.get(i), box.centerX(), box.centerY() + 9, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
        // Switch account
        RectF p = rectOf("profile");
        boolean lit = on("profile");
        if (lit) {
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(p, 28, 28, fill);
        } else {
            glass(c, p, 28, 0x1FFFFFFF);
        }
        fill.setColor(lit ? 0xFF2B2B33 : 0x40FFFFFF);
        c.drawCircle(p.left + 30, p.centerY(), 20, fill);
        drawIcon(c, "account", p.left + 30, p.centerY() + 1, 24, lit ? 0xFFFFFFFF : 0xF2FFFFFF);
        text.setTypeface(medium);
        text.setTextSize(22);
        text.setColor(lit ? 0xFF16161C : 0xF2FFFFFF);
        c.drawText("Switch account", p.left + 62, p.centerY() + 8, text);
    }

    private float measure(String s, Typeface face, float size) {
        text.setTypeface(face);
        text.setTextSize(size);
        return text.measureText(s);
    }

    private void heading(Canvas c, String s, float x, float y) {
        text.setTypeface(bold);
        text.setTextSize(24);
        text.setColor(0xF2FFFFFF);
        c.drawText(s, x, y, text);
        text.setTypeface(regular);
    }

    private void drawTopRated(Canvas c) {
        heading(c, "Top rated", 210, 218);
        List<Shows.Show> top = topRated();
        for (int i = 0; i < top.size(); i++) {
            RectF box = rectOf("top" + i);
            if (box == null) continue;
            Shows.Show s = top.get(i);
            c.save();
            clip.reset();
            clip.addRoundRect(box, 18, 18, Path.Direction.CW);
            c.clipPath(clip);
            Bitmap art = backdrops.get(s.id);
            Bitmap img = art != null ? art : s.image;
            if (img != null) cover(c, img, box.left, box.top, box.right, box.bottom, 255);
            else {
                fill.setColor(0xFF2A2A33);
                c.drawRect(box, fill);
            }
            cardShade.setLocalMatrix(heightMatrix(box));
            shade.setShader(cardShade);
            c.drawRect(box, shade);
            shade.setShader(null);
            c.restore();
            text.setTypeface(bold);
            text.setTextSize(22);
            text.setColor(Color.WHITE);
            c.drawText(TextUtils.ellipsize(s.name, text, box.width() - 90, TextUtils.TruncateAt.END).toString(),
                    box.left + 16, box.bottom - 16, text);
            if (s.rating > 0) chip(c, String.format(Locale.US, "★ %.1f", s.rating), box.right - 12, box.bottom - 40, true);
            if (on("top" + i)) focusRing(c, box, 18);
        }
    }

    private final android.graphics.Matrix matrix = new android.graphics.Matrix();

    /** Scales the unit-height card shade to a box. */
    private android.graphics.Matrix heightMatrix(RectF box) {
        matrix.setScale(1, box.height());
        matrix.postTranslate(0, box.top);
        return matrix;
    }

    /** A small glass chip; `right`: x is its right edge. */
    private float chip(Canvas c, String s, float x, float y, boolean right) {
        text.setTypeface(medium);
        text.setTextSize(17);
        float w = text.measureText(s) + 24;
        float left = right ? x - w : x;
        q.set(left, y, left + w, y + 30);
        fill.setColor(0x59000000);
        c.drawRoundRect(q, 15, 15, fill);
        line.setStrokeWidth(1);
        line.setColor(0x40FFFFFF);
        c.drawRoundRect(q, 15, 15, line);
        text.setColor(0xF2FFFFFF);
        c.drawText(s, left + 12, y + 21, text);
        text.setTypeface(regular);
        return w;
    }

    private void drawYourApps(Canvas c) {
        heading(c, "Your apps", 210, 642);
        for (int i = 0; i < Math.min(4, favourites.size()); i++) {
            RectF box = rectOf("app" + i);
            App a = apps.get(favourites.get(i));
            if (box == null || a == null) continue;
            boolean lit = on("app" + i);
            if (lit) {
                fill.setColor(0x33FFFFFF);
                c.drawRoundRect(box, 20, 20, fill);
            }
            q.set(box.left + 10, box.top + 7, box.left + 112, box.top + 64);
            c.save();
            clip.reset();
            clip.addRoundRect(q, 12, 12, Path.Direction.CW);
            c.clipPath(clip);
            fill.setAlpha(255);   // the paint may still hold a glass tint's transparency
            c.drawBitmap(a.banner, null, q, fill);
            c.restore();
            text.setTypeface(medium);
            text.setTextSize(22);
            text.setColor(0xF2FFFFFF);
            c.drawText(TextUtils.ellipsize(a.label, text, 150, TextUtils.TruncateAt.END).toString(), box.left + 128, box.top + 33, text);
            text.setTypeface(regular);
            text.setTextSize(17);
            text.setColor(0x99FFFFFF);
            c.drawText("Open", box.left + 128, box.top + 58, text);
            fill.setColor(lit ? 0xFFFFFFFF : 0x33FFFFFF);
            c.drawCircle(box.right - 30, box.centerY(), 20, fill);
            drawIcon(c, "play", box.right - 28, box.centerY(), 20, lit ? 0xFF16161C : 0xFFFFFFFF);
            if (lit) focusRing(c, box, 20);
        }
        if (favourites.isEmpty()) {
            text.setTextSize(20);
            text.setColor(0x99FFFFFF);
            c.drawText("Press Down for all apps: hold OK to add", 210, 690, text);
        }
    }

    private void drawHero(Canvas c) {
        Shows.Show s = hero();
        c.save();
        clip.reset();
        clip.addRoundRect(HERO, 36, 36, Path.Direction.CW);
        c.clipPath(clip);
        fill.setColor(0xFF1B1B22);
        c.drawRect(HERO, fill);
        if (s == null) {
            c.restore();
            text.setTextSize(28);
            text.setColor(0xB3FFFFFF);
            c.drawText(loaded ? "Nothing new on your apps this week." : "Finding what's new on your apps…",
                    HERO.left + 40, HERO.centerY(), text);
            return;
        }
        Bitmap art = backdrops.get(s.id);
        if (art != null) {
            cover(c, art, HERO.left, HERO.top, HERO.right, HERO.bottom, 255);
        } else if (s.image != null) {
            // The poster on the right, melting into its own blurred colours on the left
            Bitmap wash = softs.get(s.id * 2);
            if (wash != null) cover(c, wash, HERO.left, HERO.top, HERO.right, HERO.bottom, 255);
            float pw = HERO.height() * s.image.getWidth() / s.image.getHeight();
            q.set(HERO.right - pw, HERO.top, HERO.right, HERO.bottom);
            int layer = c.saveLayer(q, null);
            fill.setAlpha(255);
            c.drawBitmap(s.image, null, q, fill);
            c.translate(q.left, 0);
            c.drawRect(0, HERO.top, 360, HERO.bottom, mask);
            c.restoreToCount(layer);
        }
        shade.setShader(heroShade);
        c.drawRect(HERO, shade);
        shade.setShader(heroBottom);
        c.drawRect(HERO, shade);
        shade.setShader(null);
        c.restore();
        line.setStrokeWidth(1.5f);
        line.setColor(0x33FFFFFF);
        c.drawRoundRect(HERO, 36, 36, line);
        // "Trending now"
        float w = chip(c, "Trending now", HERO.left + 30, HERO.top + 28, false);
        fill.setColor(0xFFFF6B3D);
        c.drawCircle(HERO.left + 30 + w + 14, HERO.top + 43, 6, fill);
        // Arrows
        for (String id : new String[] {"prev", "next"}) {
            RectF box = rectOf(id);
            boolean lit = on(id);
            fill.setColor(lit ? 0xFFFFFFFF : 0x4D000000);
            c.drawOval(box, fill);
            if (!lit) {
                line.setColor(0x4DFFFFFF);
                c.drawOval(box, line);
            }
            drawIcon(c, id, box.centerX(), box.centerY(), 26, lit ? 0xFF16161C : 0xFFFFFFFF);
        }
        // Title, tags, about, Watch
        text.setTypeface(bold);
        text.setTextSize(58);
        text.setColor(Color.WHITE);
        c.drawText(TextUtils.ellipsize(s.name, text, 760, TextUtils.TruncateAt.END).toString(), HERO.left + 28, HERO.top + 236, text);
        float x = HERO.left + 30;
        String[] tags = s.genres.isEmpty() ? new String[0] : s.genres.split(" · ");
        for (int i = 0; i < Math.min(2, tags.length); i++) x += chip(c, tags[i], x, HERO.top + 258, false) + 10;
        String meta = (s.years + "   " + s.detail).trim();
        if (s.rating > 0) meta += String.format(Locale.US, "   ★ %.1f", s.rating);
        text.setTypeface(regular);
        text.setTextSize(19);
        text.setColor(0xCCFFFFFF);
        c.drawText(meta, x + 8, HERO.top + 280, text);
        if (!s.summary.isEmpty()) {
            StaticLayout about = layout("hero" + s.id, s.summary, 20, 0xD9FFFFFF, 700, 2);
            c.save();
            c.translate(HERO.left + 30, HERO.top + 304);
            about.draw(c);
            c.restore();
        }
        RectF watch = rectOf("watch");
        boolean lit = on("watch");
        fill.setColor(lit ? 0xFFFFFFFF : 0xE6FFFFFF);
        c.drawRoundRect(watch, 28, 28, fill);
        drawIcon(c, "play", watch.left + 40, watch.centerY(), 26, 0xFF16161C);
        text.setTypeface(medium);
        text.setTextSize(24);
        text.setColor(0xFF16161C);
        c.drawText(watchLabel(), watch.left + 66, watch.centerY() + 9, text);
        if (lit) focusRing(c, watch, 28);
        // Where it is: the app's icon, bottom right
        Bitmap badge = badges.get(s.service.pkg);
        if (badge != null) {
            q.set(HERO.right - 78, HERO.bottom - 78, HERO.right - 30, HERO.bottom - 30);
            fill.setAlpha(255);
            c.drawBitmap(badge, null, q, fill);
        }
    }

    private StaticLayout layout(String key, String s, float size, int color, int width, int lines) {
        StaticLayout l = layouts.get(key);
        if (l == null) {
            TextPaint p = new TextPaint(body);
            p.setTextSize(size);
            p.setColor(color);
            l = StaticLayout.Builder.obtain(s, 0, s.length(), p, width).setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(4, 1).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            if (layouts.size() > 80) layouts.clear();
            layouts.put(key, l);
        }
        return l;
    }

    private void drawLiked(Canvas c) {
        heading(c, "You might like", 568, 670);
        List<Shows.Show> like = liked();
        c.save();
        c.clipRect(HERO.left - 8, CARDS_Y - 8, HERO.right + 8, CARDS_Y + CARD_H + 8);
        for (int i = 0; i < like.size(); i++) {
            RectF box = rectOf("card" + i);
            if (box == null || box.right < HERO.left - 10 || box.left > HERO.right + 10) continue;
            Shows.Show s = like.get(i);
            c.save();
            clip.reset();
            clip.addRoundRect(box, 26, 26, Path.Direction.CW);
            c.clipPath(clip);
            if (s.image != null) cover(c, s.image, box.left, box.top, box.right, box.bottom, 255);
            else {
                fill.setColor(0xFF2A2A33);
                c.drawRect(box, fill);
            }
            cardShade.setLocalMatrix(heightMatrix(box));
            shade.setShader(cardShade);
            c.drawRect(box, shade);
            shade.setShader(null);
            c.restore();
            if (!s.genres.isEmpty()) chip(c, s.genres.split(" · ")[0], box.left + 14, box.top + 14, false);
            text.setTypeface(bold);
            text.setTextSize(26);
            text.setColor(Color.WHITE);
            c.drawText(TextUtils.ellipsize(s.name, text, box.width() - 28, TextUtils.TruncateAt.END).toString(),
                    box.left + 14, box.bottom - 78, text);
            if (!s.summary.isEmpty()) {
                StaticLayout about = layout("card" + s.id, s.summary, 15, 0xCCFFFFFF, Math.round(box.width() - 78), 3);
                c.save();
                c.translate(box.left + 14, box.bottom - 66);
                about.draw(c);
                c.restore();
            }
            boolean lit = on("card" + i);
            fill.setColor(lit ? 0xFFFFFFFF : 0xF2FFFFFF);
            c.drawCircle(box.right - 34, box.bottom - 34, 22, fill);
            drawIcon(c, "play", box.right - 32, box.bottom - 34, 22, 0xFF16161C);
            if (lit) focusRing(c, box, 26);
        }
        c.restore();
    }
}
