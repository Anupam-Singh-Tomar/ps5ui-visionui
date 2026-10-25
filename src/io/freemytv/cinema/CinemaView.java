package io.freemytv.cinema;

import android.content.ActivityNotFoundException;
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
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;

import java.text.DateFormat;
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
 * ps5UI: the home screen in the PS5's manner, on one canvas laid out on a 1920 x 1080 grid. At the top, a row of big
 * square tiles for the new shows on your apps (the focused one bigger, named beside it), the show's title, details
 * and an Open button, over its art filling the screen. Below, peeking in at the bottom: your apps, then the inputs;
 * Down scrolls the page up to them, the focused one's name big at the top and its show or race (or just its colours)
 * drifting slowly behind. Your apps end with
 * All apps; hold OK on one to move it, or in All apps to put it on your row or take it off.
 */
final class CinemaView extends View implements Choreographer.FrameCallback, Shows.Listener {
    /** Z_TOP: the corner by the clock (Corner), above the shows. */
    private static final int Z_TOP = -1, Z_SHOWS = 0, Z_BUTTON = 1, Z_APPS = 2, Z_INPUTS = 3;
    private static final float LEFT = 96;
    // Shows: big squares at the top
    private static final float TILE = 172, TILE_BIG = 252, GAP = 22, ROW_Y = 96;
    // The rows below (page coordinates): 16:9 tiles under a heading
    private static final float CARD_W = 272, CARD_H = 153, CARD_GAP = 26, APPS_Y = 960, INPUTS_Y = 1240;
    /** How far the page scrolls up for the rows below. */
    private static final float BELOW = 560;
    private static final long HOLD_MS = 550;
    private static final int GRID_COLS = 6;
    private static final float GRID_W = 248, GRID_H = 140, GRID_GAP = 28, GRID_TOP = 220;

    private float k = 1;
    private int zone = Z_SHOWS, focusShow, focusApp, focusInput;
    private float pos, pageY, appsScroll, inputsScroll;   // animated
    private boolean animating, evenFrame;
    private long lastFrame;

    // Shows
    private List<Shows.Show> shows = Collections.emptyList();
    private boolean loaded;
    private final Map<Integer, Bitmap> backdrops = new LinkedHashMap<Integer, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> e) {
            return size() > 6;
        }
    };
    private final Map<Integer, Bitmap> softPosters = new HashMap<>();
    private final java.util.Set<Integer> noBackdrop = new java.util.HashSet<>();   // shows with no wide art
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int heroToken;

    // Apps: every app; your row in your order, then All apps.
    private final Map<String, App> apps = new HashMap<>();
    private final List<App> sortedApps = new ArrayList<>();
    private final List<String> favourites = new ArrayList<>();
    private int moving = -1;
    private boolean sheet;
    private int sheetFocus;
    private float sheetScroll;

    private List<Inputs.Input> inputs = Collections.emptyList();
    private final SharedPreferences prefs;
    private final Sounds sounds;
    private final Corner corner;

    /**
     * The backdrop: wide art filling the screen; or, for a show without any, its poster sharp on the right over a
     * blur of its colours; or an app's glow. Fades from one to the next.
     */
    private static final class Hero {
        final Bitmap art, soft;
        /** Down on the rows: the app's colour, as light drifting behind it (over its art, if it has some); 0 for a show. */
        final int color;
        Bitmap lights;   // for an app without art: its lights, painted again each frame

        Hero(Bitmap art, Bitmap soft) {
            this(art, soft, 0);
        }

        Hero(Bitmap art, Bitmap soft, int color) {
            this.art = art;
            this.soft = soft;
            this.color = color;
        }

        boolean live() {
            return color != 0;
        }

        boolean same(Hero o) {
            return o != null && o.art == art && o.soft == soft && o.color == color;
        }
    }

    /** The light behind All apps and the inputs: a cool slate. */
    private static final int NEUTRAL = 0xFF4A5C88;
    /** Seconds the rows' backdrop has been drifting, and when focus last moved (for the sheen across the tile). */
    private float ambientT, focusSince;

    private Hero heroFrom, heroTo;
    private float heroT = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean okDown, held;
    private final Runnable hold = () -> {
        held = true;
        onHold();
    };

    private final Map<String, Bitmap> badges = new HashMap<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Pictures made once so a frame draws one layer where it would draw several (this TV's GPU is the limit)
    private Bitmap scrims, scrimsSmall;
    private android.graphics.RenderNode showsNode;
    private final Map<Integer, Bitmap> tints = new HashMap<>();
    private final Map<Bitmap, Bitmap> shaded = new LinkedHashMap<Bitmap, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Bitmap, Bitmap> e) {
            return size() > 4;   // about 3.5 MB each
        }
    };
    private final java.util.Set<Bitmap> composing = new java.util.HashSet<>();
    private final Map<String, Bitmap> drifting = new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) {
            return size() > 3;
        }
    };
    private final java.util.Set<String> composingDrift = new java.util.HashSet<>();
    private final ExecutorService composer = Executors.newSingleThreadExecutor();
    private final Map<Bitmap, Bitmap> fadedPosters = new java.util.WeakHashMap<>();
    private final Map<Bitmap, BitmapShader> imageShaders = new java.util.WeakHashMap<>();
    private final Map<Integer, android.graphics.ColorFilter> shades = new HashMap<>();
    private final android.graphics.Matrix imageAt = new android.graphics.Matrix();
    private final RectF imageSrc = new RectF(), lightRect = new RectF();
    private final Canvas lightCanvas = new Canvas();
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint blobPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix sheenAt = new android.graphics.Matrix();
    private final TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Path shape = new Path();
    private final RectF r = new RectF(), glyph = new RectF();
    private final Rect src = new Rect();
    private StaticLayout summary;
    private String summaryFor;
    private final DateFormat clock;

    private static final class App {
        String pkg, label;
        Bitmap banner;
        int color;
    }

    CinemaView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = c.getSharedPreferences("cinema", Context.MODE_PRIVATE);
        sounds = new Sounds(c);
        corner = new Corner(c, prefs, this::invalidate);
        clock = android.text.format.DateFormat.getTimeFormat(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xF2FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        head.setColor(Color.WHITE);
        head.setTypeface(regular);
        text.setTypeface(regular);
        body.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        refresh();
        Shows.load(c, this);
        Races.load(c, this::onRaces);
    }

    /** Apps and inputs, read again whenever the home screen comes back (cheap). */
    void refresh() {
        sounds.refresh();
        loadApps();
        inputs = Inputs.load(getContext());
        focusInput = Math.min(focusInput, Math.max(0, inputs.size() - 1));
        if (zone >= Z_APPS) setHero(heroFor(), false);
        invalidate();
    }

    void reloadShows() {
        Shows.load(getContext(), this);
        Races.load(getContext(), this::onRaces);
    }

    // MARK: - Data

    /** The top row: race weekends in the next two weeks first (soonest first), then the new shows. */
    private List<Shows.Show> tvShows = Collections.emptyList(), races = Collections.emptyList();
    private boolean movedShows;   // moved along the top row yet: until then it starts on the next race

    /** Where the top row starts: the next race (later ones peek in from the left), or the first show if none. */
    private int startShow() {
        return Math.max(0, races.size() - 1);
    }

    private void onRaces(List<Shows.Show> list) {
        races = list;
        merge();
    }

    @Override public void onShows(List<Shows.Show> list) {
        tvShows = list;
        loaded = true;
        merge();
    }

    /**
     * Both lists in one row: the races to the left of the shows, the soonest next to them, so Left from the first
     * show reaches the next race. It starts on the next race; once you've moved, focus stays on the same item when
     * the other list arrives.
     */
    private void merge() {
        Shows.Show was = focusShow < shows.size() ? shows.get(focusShow) : null;
        List<Shows.Show> all = new ArrayList<>(races);
        Collections.reverse(all);
        all.addAll(tvShows);
        shows = all;
        int keep = was == null || !movedShows ? -1 : shows.indexOf(was);
        focusShow = keep >= 0 ? keep : startShow();
        for (Shows.Show s : shows) badge(s.service.pkg);
        if (shows.isEmpty() && zone >= Z_SHOWS && zone <= Z_BUTTON) zone = Z_APPS;   // nothing new: start on your apps
        pos = focusShow;
        focusChanged();
    }

    @Override public void onPoster() {
        if (zone <= Z_BUTTON && heroTo == null) setHero(heroFor(), true);
        invalidate();
    }

    /** The app's own icon, round, for the show's eyebrow line. */
    private void badge(String pkg) {
        if (badges.containsKey(pkg)) return;
        Bitmap b = null;
        try {
            Drawable d = getContext().getPackageManager().getApplicationIcon(pkg);
            int size = 72;
            Bitmap icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(icon);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
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
            App a = apps.get(pkg);   // keep the pictures already made
            if (a == null) {
                a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.banner = banner(ri.activityInfo.loadBanner(pm), ri.activityInfo.loadIcon(pm));
                a.color = vivid(a.banner);
            }
            found.put(pkg, a);
        }
        apps.clear();
        apps.putAll(found);
        sortedApps.clear();
        sortedApps.addAll(found.values());
        sortedApps.sort((a, b) -> {
            int ra = rank(a.pkg), rb = rank(b.pkg);
            return ra != rb ? Integer.compare(ra, rb) : a.label.compareToIgnoreCase(b.label);
        });
        favourites.clear();
        String saved = prefs.getString("favourites", null);
        if (saved != null) {
            for (String pkg : saved.split(",")) if (apps.containsKey(pkg) && !favourites.contains(pkg)) favourites.add(pkg);
        } else {
            for (App a : sortedApps) if (rank(a.pkg) < 100 && favourites.size() < 8) favourites.add(a.pkg);
        }
        focusApp = Math.min(focusApp, favourites.size());   // the last tile is All apps
        sheetFocus = Math.min(sheetFocus, Math.max(0, sortedApps.size() - 1));
    }

    private void saveFavourites() {
        prefs.edit().putString("favourites", TextUtils.join(",", favourites)).apply();
    }

    private static int rank(String pkg) {
        for (int i = 0; i < Shows.SERVICES.length; i++) if (Shows.SERVICES[i].pkg.equals(pkg)) return i;
        if (pkg.equals("com.google.android.youtube.tv")) return 1;
        return 100;
    }

    /** A 16:9 picture of the app: its TV banner, else its icon on a dark card. */
    private static Bitmap banner(Drawable banner, Drawable icon) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(c);
        } else {
            c.drawColor(0xFF2A2A2E);
            if (icon != null) {
                icon.setBounds(w / 2 - 54, h / 2 - 54, w / 2 + 54, h / 2 + 54);
                icon.draw(c);
            }
        }
        return b;
    }

    /** A soft, blurred copy for a poster's backdrop (shrunk hard, then scaled up with filtering). */
    private static Bitmap soft(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 24, Math.max(1, 24 * b.getHeight() / b.getWidth()), true);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, 96, Math.max(1, 96 * b.getHeight() / b.getWidth()), true);
        return Bitmap.createScaledBitmap(mid, 384, Math.max(1, 384 * b.getHeight() / b.getWidth()), true);
    }

    /** A soft round light, white fading out to its edge: tinted and stretched for the drifting light behind the rows. */
    private static final Bitmap BLOB = blob();

    private static Bitmap blob() {
        int s = 128;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f, new int[] {0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, s, s, p);
        return b;
    }

    private static int hueShift(int color, float degrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + degrees + 360) % 360;
        return Color.HSVToColor(hsv);
    }

    /** The banner's most colourful tone (Netflix's red, not the grey its red and white average to). */
    private static int vivid(Bitmap banner) {
        Bitmap small = Bitmap.createScaledBitmap(banner, 32, 18, true);
        float[] hsv = new float[3];
        double red = 0, green = 0, blue = 0, weight = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int px = small.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                double w = hsv[1] * hsv[1] * hsv[2];   // saturated and bright counts most
                red += Color.red(px) * w;
                green += Color.green(px) * w;
                blue += Color.blue(px) * w;
                weight += w;
            }
        }
        if (weight < 1) return 0xFF3A3A44;
        Color.colorToHSV(Color.rgb((int) (red / weight), (int) (green / weight), (int) (blue / weight)), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.2f);
        hsv[2] = Math.min(0.85f, Math.max(0.55f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    // MARK: - What's focused

    private boolean onAllApps() {
        return zone == Z_APPS && focusApp == favourites.size();
    }

    /**
     * Along a row, the backdrop follows a beat after focus (as on the PS5): the tiles slide first, on their own, then
     * the backdrop fades; holding a direction skips the ones passed by. The slide and the fade in the same frames were
     * the slowest frames this TV draws. Up and Down change it at once: the page scroll takes longer, and the fade is
     * best over its start.
     */
    private final Runnable heroLater = () -> setHero(heroFor(), true);
    private int heroZone = Integer.MIN_VALUE;

    private void focusChanged() {
        focusSince = ambientT;
        handler.removeCallbacks(heroLater);
        if (zone == heroZone) {
            handler.postDelayed(heroLater, 200);
        } else {
            heroZone = zone;
            setHero(heroFor(), true);
        }
        if (zone <= Z_BUTTON && focusShow < shows.size()) {
            requestBackdrop(shows.get(focusShow));
            // The neighbours' backdrops made ready too, so the next move fades between finished pictures
            for (int i : new int[] {focusShow - 1, focusShow + 1}) {
                if (i < 0 || i >= shows.size() || composing.size() > 1) continue;
                Hero next = heroFor(shows.get(i));
                if (next != null) shadedFor(next);
            }
        }
        prepareDrift();
        startAnimation();
    }

    /** The backdrop for what's focused now: a show's art (or its poster); down on the rows, living light. */
    private Hero heroFor() {
        if (zone <= Z_BUTTON && focusShow < shows.size()) return heroFor(shows.get(focusShow));
        if (zone == Z_APPS && focusApp < favourites.size()) {
            App a = apps.get(favourites.get(focusApp));
            return a == null ? null : new Hero(artFor(a.pkg), null, a.color);
        }
        return zone >= Z_APPS ? new Hero(null, null, NEUTRAL) : null;   // All apps, the inputs
    }

    private Hero heroFor(Shows.Show s) {
        if (s.wide != null) return new Hero(s.wide, null);   // a race: its own backdrop
        Bitmap art = backdrops.get(s.id);
        if (art != null) return new Hero(art, null);
        if (s.image == null) return null;
        Bitmap softPoster = softPosters.get(s.id);
        if (softPoster == null) {
            softPoster = soft(s.image);
            softPosters.put(s.id, softPoster);
        }
        return new Hero(s.image, softPoster);
    }

    /**
     * Something on that app to fill the screen, as the PS5 does: FanCode's next race, a service's newest show with
     * wide art (asking for each in turn; shows with none are passed over).
     */
    private Bitmap artFor(String pkg) {
        for (List<Shows.Show> list : java.util.Arrays.asList(races, tvShows)) {
            for (Shows.Show s : list) {
                if (!s.service.pkg.equals(pkg) || noBackdrop.contains(s.id)) continue;
                if (s.wide != null) return s.wide;
                Bitmap art = backdrops.get(s.id);
                if (art == null) requestBackdrop(s);
                return art;
            }
        }
        return null;
    }

    private void setHero(Hero h, boolean fade) {
        if (h == null ? heroTo == null : h.same(heroTo)) return;
        heroFrom = fade ? heroTo : null;
        heroTo = h;
        heroT = fade ? 0 : 1;
        startAnimation();
    }

    /** The show's wide art, fetched in the background a moment after focus settles on it. */
    private void requestBackdrop(Shows.Show s) {
        if (s.wide != null || backdrops.containsKey(s.id) || noBackdrop.contains(s.id)) return;   // races bring their own
        int token = ++heroToken;
        handler.postDelayed(() -> {
            if (token != heroToken) return;
            Context app = getContext().getApplicationContext();
            worker.execute(() -> {
                Bitmap got = Shows.backdrop(app, s);
                // At most 1280 wide: plenty for a backdrop under shading, and far less for the GPU to read each frame
                Bitmap art = got == null || got.getWidth() <= 1280 ? got
                        : Bitmap.createScaledBitmap(got, 1280, Math.max(1, 1280 * got.getHeight() / got.getWidth()), true);
                handler.post(() -> {
                    if (art != null) {
                        art.prepareToDraw();
                        backdrops.put(s.id, art);
                    }
                    else noBackdrop.add(s.id);
                    // If it's for what's focused (a show, or its app below; with none, the app tries its next show)
                    setHero(heroFor(), true);
                });
            });
        }, 250);
    }

    // MARK: - Keys

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return move(-1, 0, e);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return move(1, 0, e);
            case KeyEvent.KEYCODE_DPAD_UP: return move(0, -1, e);
            case KeyEvent.KEYCODE_DPAD_DOWN: return move(0, 1, e);
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
                int before = where();
                if (corner.open) {
                    corner.open = false;
                } else if (moving >= 0) {
                    drop();
                } else if (sheet) {
                    sheet = false;
                } else if (zone != Z_SHOWS && !shows.isEmpty()) {
                    zone = Z_SHOWS;   // back to the top
                    focusChanged();
                } else {
                    int first = startShow();   // the next race, the later ones to its left
                    if (focusShow != first) {
                        focusShow = first;
                        focusChanged();
                    }
                }
                if (where() != before) sounds.play(Sounds.BACK);
                startAnimation();
                return true;   // a home screen has nowhere to go back to
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
     * Where focus is, as one number: compared before and after a key, so the TV's own navigation sound plays only
     * when something moved (none at the end of a row, as on Google TV's home).
     */
    private int where() {
        return java.util.Arrays.hashCode(new int[] {zone, focusShow, focusApp, focusInput, sheetFocus, moving, sheet ? 1 : 0,
                corner.open ? 1 : 0, corner.focus});
    }

    /** A move, and its sound: a tick along a row, a deeper one between rows, a soft bump where there's nowhere to go. */
    private boolean move(int dx, int dy, KeyEvent e) {
        int before = where();
        boolean wasSheet = sheet, wasMenu = corner.open;
        moveFocus(dx, dy);
        if (where() == before) {
            if (e.getRepeatCount() == 0) sounds.play(Sounds.EDGE);   // not over and over while the key is held
        } else {
            sounds.play(dy != 0 && !wasSheet && !wasMenu ? Sounds.ROW : Sounds.MOVE);   // in All apps and the menu: a list
        }
        return true;
    }

    private void moveFocus(int dx, int dy) {
        if (corner.open) {
            if (dy != 0) corner.move(dy);
            startAnimation();
            return;
        }
        if (sheet) {
            int n = sortedApps.size(), next = sheetFocus + dx + dy * GRID_COLS;
            if (dy == 0 || (next >= 0 && next < n)) sheetFocus = Math.max(0, Math.min(n - 1, next));
        } else if (moving >= 0) {
            // Lifted: Left and Right carry it along your row; Up and Down put it down.
            if (dy != 0) {
                drop();
            } else {
                int to = moving + dx;
                if (to >= 0 && to < favourites.size()) {
                    Collections.swap(favourites, moving, to);
                    moving = to;
                    focusApp = to;
                }
            }
        } else if (dy != 0) {
            int next = zone + dy;
            if (shows.isEmpty() && next >= Z_SHOWS && next <= Z_BUTTON) next = zone;   // nothing up there
            if (next == Z_INPUTS && inputs.isEmpty()) next = zone;
            if (next >= Z_TOP && next <= Z_INPUTS && next != zone) {
                zone = next;
                focusChanged();
            }
        } else if (zone == Z_SHOWS) {
            int next = Math.max(0, Math.min(shows.size() - 1, focusShow + dx));
            if (next != focusShow) {
                focusShow = next;
                movedShows = true;
                focusChanged();
            }
        } else if (zone == Z_APPS) {
            int next = Math.max(0, Math.min(favourites.size(), focusApp + dx));
            if (next != focusApp) {
                focusApp = next;
                focusChanged();
            }
        } else if (zone == Z_INPUTS) {
            focusInput = Math.max(0, Math.min(inputs.size() - 1, focusInput + dx));
        }
        startAnimation();
    }

    private void onClick() {
        sounds.play(Sounds.SELECT);
        if (corner.open) {
            corner.activate();
        } else if (zone == Z_TOP) {
            corner.toggle();
        } else if (sheet) {
            if (sheetFocus < sortedApps.size()) open(sortedApps.get(sheetFocus).pkg);
        } else if (moving >= 0) {
            drop();
        } else if (zone <= Z_BUTTON) {
            if (focusShow < shows.size()) open(shows.get(focusShow).service.pkg);
        } else if (zone == Z_APPS) {
            if (onAllApps()) {
                sheet = true;
                sheetFocus = 0;
                sheetScroll = 0;
            } else {
                open(favourites.get(focusApp));
            }
        } else if (focusInput < inputs.size()) {
            start(inputs.get(focusInput).intent);
        }
        startAnimation();
    }

    /** Held OK: on your row, lifts the app to move it; in All apps, puts it on your row or takes it off. */
    private void onHold() {
        if (sheet) {
            if (sheetFocus >= sortedApps.size()) return;
            String pkg = sortedApps.get(sheetFocus).pkg;
            if (!favourites.remove(pkg)) favourites.add(pkg);
            saveFavourites();
            focusApp = Math.min(focusApp, favourites.size());
        } else if (zone == Z_APPS && focusApp < favourites.size()) {
            moving = focusApp;
        } else {
            return;
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        sounds.play(Sounds.SELECT);
        startAnimation();
    }

    private void drop() {
        moving = -1;
        saveFavourites();
        focusChanged();
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

    // MARK: - Animation

    private void startAnimation() {
        if (!animating) {
            animating = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
        invalidate();
    }

    /**
     * By time, not by frame: this TV is often busy and drops frames, and a move must still land in about a quarter
     * of a second (eased, 85 ms time constant), a backdrop fade in 280 ms.
     */
    @Override public void doFrame(long frameTimeNanos) {
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (frameTimeNanos - lastFrame) / 1e9f);
        lastFrame = frameTimeNanos;
        float ease = 1 - (float) Math.exp(-dt / 0.085f);
        boolean more = false;
        ambientT += dt;
        float d = focusShow - pos;
        if (Math.abs(d) > 0.01f) {
            pos += d * ease;
            more = true;
        } else {
            pos = focusShow;
        }
        float[] targets = {zone >= Z_APPS ? BELOW : 0, rowTarget(appsScroll, focusApp), rowTarget(inputsScroll, focusInput)};
        float[] values = {pageY, appsScroll, inputsScroll};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(targets[i] - values[i]) > 1) {
                values[i] += (targets[i] - values[i]) * ease;
                more = true;
            } else {
                values[i] = targets[i];
            }
        }
        pageY = values[0];
        appsScroll = values[1];
        inputsScroll = values[2];
        if (heroT < 1) {
            heroT = Math.min(1, heroT + dt / 0.28f);
            more = true;
        }
        if (sheet) {
            float top = GRID_TOP + (sheetFocus / GRID_COLS) * (GRID_H + GRID_GAP + 40) - sheetScroll;
            float target = top > 860 ? sheetScroll + top - 860 : top < GRID_TOP ? sheetScroll - (GRID_TOP - top) : sheetScroll;
            if (Math.abs(target - sheetScroll) > 1) {
                sheetScroll += (target - sheetScroll) * ease;
                more = true;
            } else {
                sheetScroll = target;
            }
        }
        // Down on the rows the backdrop keeps drifting, drawn on every other frame (an even 30 a second): smooth for
        // something this slow, and half the work for this TV. It stops while another app is in front.
        boolean live = heroTo != null && heroTo.live() && !sheet && getWindowVisibility() == VISIBLE;
        evenFrame = !evenFrame;
        if (more || evenFrame) invalidate();
        if (more || live) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            animating = false;
            lastFrame = 0;
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startAnimation();
    }

    /** Scrolls a row below so its focused card stays on screen with a margin. */
    private static float rowTarget(float scroll, int index) {
        float step = CARD_W + CARD_GAP, x = LEFT + index * step - scroll;
        if (x < LEFT) return scroll - (LEFT - x);
        if (x + CARD_W > 1920 - LEFT) return scroll + (x + CARD_W - (1920 - LEFT));
        return scroll;
    }

    // MARK: - Drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        ring.setStrokeWidth(3 * k);
        // The shading over the backdrop (dark to the left, the bottom and the top, for the text): three gradients,
        // made into one small picture once. Smooth enough stretched, and one layer a frame for this TV's GPU, not three.
        int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
        scrims = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas sc = new Canvas(scrims);
        sc.scale(sw / (float) w, sh / (float) h);
        scrim.setShader(new LinearGradient(0, 0, 1250 * k, 0, new int[] {0xE6000000, 0x99000000, 0x00000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, w, h, scrim);
        scrim.setShader(new LinearGradient(0, h, 0, 420 * k, new int[] {0xF7000000, 0xB3000000, 0x00000000},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        sc.drawRect(0, 420 * k, w, h, scrim);
        scrim.setShader(new LinearGradient(0, 0, 0, 380 * k, new int[] {0xB3000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        sc.drawRect(0, 0, w, 380 * k, scrim);
        scrim.setShader(null);
        scrimsSmall = Bitmap.createScaledBitmap(scrims, 128, 72, true);   // for the lights, which are that size
        tints.clear();
        shaded.clear();
        drifting.clear();
        sheen.setShader(new LinearGradient(0, 0, 130 * k, 0, new int[] {0x00FFFFFF, 0x47FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        summary = null;
    }

    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xFF0B0B0F);
        if (sheet) {   // it covers everything: nothing under it is drawn
            drawSheet(c);
            drawClock(c);
            return;
        }
        drawHero(c);
        c.save();
        c.translate(0, -pageY * k);
        // The shows fade out as the page scrolls down to the rows, where the focused one's name takes their place.
        float showsAlpha = 1 - Math.min(1, pageY / BELOW * 1.6f);
        if (showsAlpha > 0.01f) {
            if (showsAlpha < 0.99f && c.isHardwareAccelerated() && android.os.Build.VERSION.SDK_INT >= 29) {
                // Faded as they're drawn, each piece on its own, not drawn whole off screen and then faded: the same
                // look in a short fade, without a second pass over half the screen
                if (showsNode == null) {
                    showsNode = new android.graphics.RenderNode("shows");
                    showsNode.setHasOverlappingRendering(false);
                }
                showsNode.setPosition(0, 0, getWidth(), Math.round(820 * k));
                showsNode.setAlpha(showsAlpha);
                Canvas rc = showsNode.beginRecording();
                drawShows(rc);
                drawInfo(rc);
                showsNode.endRecording();
                c.drawRenderNode(showsNode);
            } else if (showsAlpha < 0.99f) {
                int layer = c.saveLayerAlpha(0, 0, getWidth(), 820 * k, Math.round(255 * showsAlpha));
                drawShows(c);
                drawInfo(c);
                c.restoreToCount(layer);
            } else {
                drawShows(c);
                drawInfo(c);
            }
        }
        // Only what's on screen (the inputs are below it until you go down)
        if (onScreen(APPS_Y)) drawApps(c);
        if (onScreen(INPUTS_Y)) drawInputs(c);
        c.restore();
        drawBelowTitle(c);
        drawClock(c);
        if (corner.open) drawCornerMenu(c);
    }

    /** Whether a row of cards at this page height (with its heading above it) is on screen at the current scroll. */
    private boolean onScreen(float rowY) {
        return rowY - 70 - pageY < 1080 && rowY + CARD_H + 10 - pageY > 0;
    }

    // The backdrop

    private void drawHero(Canvas c) {
        boolean fading = heroFrom != null && heroT < 1, shadedAll = true;
        if (fading) shadedAll = drawHero(c, heroFrom, 1);
        if (heroTo != null) shadedAll &= drawHero(c, heroTo, heroFrom == null ? 1 : heroT);
        else if (fading) {
            fill.setColor(Color.argb(Math.round(255 * heroT), 11, 11, 15));
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            fill.setAlpha(255);
        }
        if (shadedAll) return;   // the shading is already on the pictures
        r.set(0, 0, getWidth(), getHeight());
        c.drawBitmap(scrims, null, r, layerPaint);
    }

    /**
     * Wide art fills the screen; a poster stands sharp on the right, fading into its own blurred colours. Once made,
     * one picture with the shading already on it (see {@link #shadedFor}). True if the shading was drawn with it.
     */
    private boolean drawHero(Canvas c, Hero h, float alpha) {
        if (h.live()) {
            drawLive(c, h, alpha);
            return true;
        }
        Bitmap done = shadedFor(h);
        if (done != null) {
            r.set(0, 0, getWidth(), getHeight());
            fill.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(done, null, r, fill);
            fill.setAlpha(255);
            return true;
        }
        if (h.soft != null) drawCover(c, h.soft, alpha);
        if (h.art == null) return false;
        if (h.art.getWidth() >= h.art.getHeight()) {
            drawCover(c, h.art, alpha);
            return false;
        }
        float ph = getHeight(), pw = ph * h.art.getWidth() / h.art.getHeight();
        r.set(getWidth() - pw, 0, getWidth(), ph);
        Bitmap faded = fadedPosters.get(h.art);
        if (faded == null) {
            faded = fadeLeft(h.art);
            fadedPosters.put(h.art, faded);
        }
        fill.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(faded, null, r, fill);
        fill.setAlpha(255);
        return false;
    }

    /**
     * The backdrop as drawn, shading and all, as one opaque picture: made in the background the first time it's
     * shown (it's drawn in its parts until then). The GPU then fills the screen once a frame instead of two or three
     * times, which is most of what a frame costs on this TV.
     */
    private Bitmap shadedFor(Hero h) {
        if (h.art == null || scrims == null) return null;
        Bitmap b = shaded.get(h.art);
        if (b == null && composing.add(h.art)) {
            Bitmap art = h.art, soft = h.soft, sc = scrims;
            float ratio = (float) getHeight() / getWidth();
            composer.execute(() -> {
                Bitmap out = compose(art, soft, sc, ratio);
                handler.post(() -> {
                    composing.remove(art);
                    if (out == null) return;
                    out.prepareToDraw();   // sent to the GPU now, in the background, not in the middle of a fade
                    shaded.put(art, out);
                    invalidate();
                });
            });
        }
        return b;
    }

    private static Bitmap compose(Bitmap art, Bitmap soft, Bitmap overlay, float ratio) {   // overlay: the shading (and an app's tint)
        try {
            int w = 1280, h = Math.round(1280 * ratio);   // the art is no bigger
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            RectF all = new RectF(0, 0, w, h);
            Rect from = new Rect();
            c.drawColor(0xFF0B0B0F);
            if (art.getWidth() >= art.getHeight()) {
                cover(art, (float) w / h, from);
                c.drawBitmap(art, from, all, p);
            } else {
                if (soft != null) {
                    cover(soft, (float) w / h, from);
                    c.drawBitmap(soft, from, all, p);
                }
                float pw = h * art.getWidth() / (float) art.getHeight();
                c.drawBitmap(fadeLeft(art), null, new RectF(w - pw, 0, w, h), p);
            }
            c.drawBitmap(overlay, null, all, p);
            out.setHasAlpha(false);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The part of a picture that fills a screen of this shape (keeping the top, where the faces are). */
    private static void cover(Bitmap b, float want, Rect into) {
        float have = (float) b.getWidth() / b.getHeight();
        if (have > want) {
            int w = Math.round(b.getHeight() * want);
            into.set((b.getWidth() - w) / 2, 0, (b.getWidth() + w) / 2, b.getHeight());
        } else {
            int h = Math.round(b.getWidth() / want);
            into.set(0, (b.getHeight() - h) / 4, b.getWidth(), (b.getHeight() - h) / 4 + h);
        }
    }

    /** The poster with its left edge melting into the wash (over its first 420 of 1080), made once, not every frame. */
    private static Bitmap fadeLeft(Bitmap poster) {
        Bitmap b = poster.copy(Bitmap.Config.ARGB_8888, true);
        if (b == null) return poster;
        float edge = 420f * b.getHeight() / 1080;
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setShader(new LinearGradient(0, 0, edge, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, edge, b.getHeight(), p);
        return b;
    }

    /**
     * Down on the rows, the backdrop lives: the app's show or race drifting slowly (zooming and panning over half a
     * minute) under its colour, or, for an app without one, three soft lights in its colour wandering on their own
     * slow paths. Never in step, so it doesn't loop visibly.
     */
    private void drawLive(Canvas c, Hero h, float alpha) {
        float w = getWidth(), ht = getHeight(), t = ambientT, tau = (float) (2 * Math.PI);
        if (h.art != null) {
            Bitmap done = driftFor(h.art, h.color);   // the art with its tint and shading on, once made
            c.save();
            float s = 1.09f + 0.05f * (float) Math.sin(t * tau / 26);
            c.scale(s, s, w * (0.5f + 0.08f * (float) Math.sin(t * tau / 34)), ht * (0.45f + 0.08f * (float) Math.cos(t * tau / 29)));
            drawCover(c, done != null ? done : h.art, alpha);
            c.restore();
            if (done != null) return;   // one layer a frame, not two
        } else {
            // The lights are drawn small on the CPU (cheap at this size), then stretched over the screen in one go
            if (h.lights == null) h.lights = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888);
            paintLights(h.lights, h.color, t);
        }
        r.set(0, 0, w, ht);
        layerPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(h.art != null ? tintFor(h.color) : h.lights, null, r, layerPaint);
        layerPaint.setAlpha(255);
    }

    /** Three soft lights in the colour (and its neighbours) wandering on their own slow paths, on near-black. */
    private void paintLights(Bitmap b, int color, float t) {
        float w = b.getWidth(), ht = b.getHeight(), tau = (float) (2 * Math.PI);
        b.eraseColor(0xFF0B0B0F);
        lightCanvas.setBitmap(b);
        for (int i = 0; i < 3; i++) {
            float cx = w * (0.66f + 0.24f * (float) Math.sin(t * tau / (21 + i * 8) + i * 2.1f));
            float cy = ht * (0.34f + 0.26f * (float) Math.cos(t * tau / (25 + i * 6) + i * 1.3f));
            float radius = w * (0.40f + 0.07f * (float) Math.sin(t * tau / (17 + i * 5) + i));
            lightRect.set(cx - radius, cy - radius * 0.8f, cx + radius, cy + radius * 0.8f);
            blobPaint.setColorFilter(new android.graphics.PorterDuffColorFilter(
                    i == 0 ? color : hueShift(color, i == 1 ? 26 : -22), PorterDuff.Mode.SRC_IN));
            blobPaint.setAlpha(Math.round(255 * 0.8f * (i == 0 ? 1 : 0.7f)));
            lightCanvas.drawBitmap(BLOB, null, lightRect, blobPaint);
        }
        lightCanvas.drawBitmap(scrimsSmall, 0, 0, null);   // the shading too: one layer on screen
        lightCanvas.setBitmap(null);
    }

    /** Over an app's drifting art: darker, with a glow of its colour up on the right. Made once per colour. */
    private Bitmap tintFor(int color) {
        Bitmap b = tints.get(color);
        if (b != null) return b;
        int w = 480, h = 270, rgb = color & 0xFFFFFF;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0x5A000000);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(w * 0.72f, h * 0.3f, w * 0.62f, new int[] {0x66000000 | rgb, 0x26000000 | rgb, rgb},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        c.drawBitmap(scrims, null, new RectF(0, 0, w, h), layerPaint);   // and the shading: one layer over the art
        tints.put(color, b);
        return b;
    }

    /**
     * An app's art with its tint and shading already on, as one opaque picture that drifts as a whole: made in the
     * background the first time (drawn in its two layers until then), or ahead of time for the apps beside the
     * focused one (prepareDrift). The shading drifts a few percent with it, which doesn't show.
     */
    private Bitmap driftFor(Bitmap art, int color) {
        String key = System.identityHashCode(art) + ":" + color;
        Bitmap b = drifting.get(key);
        if (b == null && scrims != null && composingDrift.add(key)) {
            Bitmap tint = tintFor(color);
            float ratio = (float) getHeight() / getWidth();
            composer.execute(() -> {
                Bitmap out = compose(art, null, tint, ratio);
                handler.post(() -> {
                    composingDrift.remove(key);
                    if (out == null) return;
                    out.prepareToDraw();
                    drifting.put(key, out);
                    invalidate();
                });
            });
        }
        return b;
    }

    /** The drifting pictures of the focused app and, on the row, its neighbours: ready before they're needed. */
    private void prepareDrift() {
        if (scrims == null) return;
        for (int i = focusApp - (zone >= Z_APPS ? 1 : 0); i <= focusApp + (zone >= Z_APPS ? 1 : 0); i++) {
            if (i < 0 || i >= favourites.size() || composingDrift.size() > 1) continue;
            App a = apps.get(favourites.get(i));
            Bitmap art = a == null ? null : readyArt(a.pkg);
            if (art != null) driftFor(art, a.color);
        }
    }

    /** As artFor, but only what's already here: asks for nothing. */
    private Bitmap readyArt(String pkg) {
        for (List<Shows.Show> list : java.util.Arrays.asList(races, tvShows)) {
            for (Shows.Show s : list) {
                if (!s.service.pkg.equals(pkg) || noBackdrop.contains(s.id)) continue;
                return s.wide != null ? s.wide : backdrops.get(s.id);
            }
        }
        return null;
    }

    /** A picture filling the screen, cut to its shape (keeping the top, where the faces are). */
    private void drawCover(Canvas c, Bitmap b, float alpha) {
        cover(b, (float) getWidth() / getHeight(), src);
        r.set(0, 0, getWidth(), getHeight());
        fill.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(b, src, r, fill);
        fill.setAlpha(255);
    }

    private void drawClock(Canvas c) {
        head.setTypeface(regular);
        head.setTextSize(30 * k);
        head.setColor(0xE6FFFFFF);
        head.setTextAlign(Paint.Align.RIGHT);
        String time = clock.format(new Date());
        c.drawText(time, (1920 - LEFT) * k, 70 * k, head);
        head.setTextAlign(Paint.Align.LEFT);
        // The corner's picture, just left of the time, in the empty band above the shows (they start at ROW_Y)
        if (sheet) return;
        float radius = Corner.SIZE / 2 * k;
        float cx = (1920 - LEFT) * k - head.measureText(time) - 24 * k - radius;
        corner.drawPicture(c, cx, 59 * k, radius, k, zone == Z_TOP);
    }

    /** The corner's menu, hanging under the picture, its right edge with the time's. */
    private void drawCornerMenu(Canvas c) {
        corner.drawMenu(c, (1920 - LEFT) * k, 100 * k, k);
    }

    // The top: shows

    /** The row: from the focused tile's place at the left edge, bigger and named, the rest after it. */
    private void drawShows(Canvas c) {
        int n = shows.size();
        if (n == 0) {
            text.setTextSize(30 * k);
            text.setColor(0x99FFFFFF);
            c.drawText(loaded ? "Nothing new on your apps this week." : "Finding what's new on your apps…",
                    LEFT * k, (ROW_Y + 120) * k, text);
            return;
        }
        String label = shows.get(focusShow).name;
        text.setTypeface(regular);
        text.setTextSize(34 * k);
        float labelW = Math.min(620 * k, text.measureText(label) + 64 * k);
        boolean here = zone == Z_SHOWS && !sheet;
        for (int i = 0; i < n; i++) {
            float d = i - pos, a = Math.min(1, Math.abs(d));
            float size = (TILE + (TILE_BIG - TILE) * (1 - a)) * k;
            // Before the focused tile: off to the left; just after it: past its name; between: in between.
            float x;
            if (d <= 0) x = (LEFT + d * (TILE + GAP)) * k;
            else if (d < 1) x = LEFT * k + d * ((TILE_BIG + GAP) * k + labelW);
            else x = (LEFT + TILE_BIG + GAP) * k + labelW + (d - 1) * (TILE + GAP) * k;
            if (x + size < -10 * k || x > getWidth()) continue;
            float y = ROW_Y * k;
            r.set(x, y, x + size, y + size);
            boolean on = i == focusShow;
            float radius = 22 * k * size / (TILE_BIG * k);
            Bitmap img = shows.get(i).image;
            if (img == null) {
                fill.setColor(0xFF22222A);
                c.drawRoundRect(r, radius, radius, fill);
            } else {
                int side = Math.min(img.getWidth(), img.getHeight());
                int top = Math.round((img.getHeight() - side) * 0.3f);
                imageSrc.set((img.getWidth() - side) / 2f, top, (img.getWidth() + side) / 2f, top + side);
                drawRounded(c, img, imageSrc, r, radius, !on || !here ? (on ? 0x1A : 0x4D) : 0);
            }
            if (img == null && (!on || !here)) {
                fill.setColor(on ? 0x1A000000 : 0x4D000000);
                c.drawRoundRect(r, radius, radius, fill);
            }
            if (on && here) {
                r.inset(-6 * k, -6 * k);
                c.drawRoundRect(r, radius + 6 * k, radius + 6 * k, ring);
                r.inset(6 * k, 6 * k);
            }
            if (on && a < 0.5f) {
                text.setColor(Color.argb(Math.round(255 * (1 - a * 2)), 255, 255, 255));
                text.setTextSize(34 * k);
                c.drawText(TextUtils.ellipsize(label, text, labelW - 44 * k, TextUtils.TruncateAt.END).toString(),
                        r.right + 30 * k, r.centerY() + 12 * k, text);
            }
        }
    }

    /** Under the shows: what it is and where, the title, details, a few lines about it, and Open. */
    private void drawInfo(Canvas c) {
        if (shows.isEmpty()) return;
        Shows.Show s = shows.get(focusShow);
        float x = LEFT * k, y = 450 * k;
        Bitmap badge = badges.get(s.service.pkg);
        text.setTextSize(22 * k);
        text.setColor(0xCCFFFFFF);
        text.setLetterSpacing(0.12f);
        float ex = x;
        if (badge != null) {
            r.set(x, y - 26 * k, x + 34 * k, y + 8 * k);
            image.setAlpha(255);
            c.drawBitmap(badge, null, r, image);   // not `fill`: the tiles' shade may have just left it see-through
            ex += 48 * k;
        }
        c.drawText(s.eyebrow != null ? s.eyebrow : "NEW ON " + s.service.label.toUpperCase(Locale.getDefault()), ex, y, text);
        text.setLetterSpacing(0);
        head.setTypeface(bold);
        head.setTextSize(68 * k);
        head.setColor(Color.WHITE);
        c.drawText(TextUtils.ellipsize(s.name, head, 1150 * k, TextUtils.TruncateAt.END).toString(), x - 4 * k, y + 84 * k, head);
        head.setTypeface(regular);
        StringBuilder m = new StringBuilder();
        if (!s.years.isEmpty()) m.append(s.years);
        if (!s.detail.isEmpty()) m.append(m.length() > 0 ? "   ·   " : "").append(s.detail);
        if (!s.genres.isEmpty()) m.append(m.length() > 0 ? "   ·   " : "").append(s.genres);
        if (s.rating > 0) m.append(m.length() > 0 ? "   ·   " : "").append(String.format(Locale.US, "★ %.1f", s.rating));
        text.setTextSize(26 * k);
        text.setColor(0xD9FFFFFF);
        c.drawText(TextUtils.ellipsize(m.toString(), text, 1150 * k, TextUtils.TruncateAt.END).toString(), x, y + 130 * k, text);
        if (!s.summary.isEmpty()) {
            if (summary == null || !s.summary.equals(summaryFor)) {
                body.setTextSize(25 * k);
                body.setColor(0xB3FFFFFF);
                summary = StaticLayout.Builder.obtain(s.summary, 0, s.summary.length(), body, Math.round(1000 * k))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6 * k, 1)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                summaryFor = s.summary;
            }
            c.save();
            c.translate(x, y + 156 * k);
            summary.draw(c);
            c.restore();
        }
        pill(c, "Open " + s.service.label, x, 720 * k, zone == Z_BUTTON && !sheet);
        if (s.credit != null) {   // a race's photo: its author and licence, as Wikimedia Commons asks
            text.setTextSize(17 * k);
            text.setColor(0x80FFFFFF);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(TextUtils.ellipsize(s.credit, text, 760 * k, TextUtils.TruncateAt.END).toString(),
                    (1920 - LEFT) * k, 762 * k, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** A pill button: focused, white with dark text; otherwise dark glass. */
    private void pill(Canvas c, String label, float x, float y, boolean on) {
        text.setTextSize(26 * k);
        float w = text.measureText(label) + 76 * k, h = 64 * k;
        r.set(x, y, x + w, y + h);
        fill.setColor(on ? 0xFFFFFFFF : 0x40FFFFFF);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        if (on) {
            r.inset(-6 * k, -6 * k);
            c.drawRoundRect(r, h / 2 + 6 * k, h / 2 + 6 * k, ring);
        }
        text.setColor(on ? 0xFF111114 : Color.WHITE);
        c.drawText(label, x + 38 * k, y + 41 * k, text);
    }

    // Below: your apps and the inputs

    private void heading(Canvas c, String s, float y, String hint) {
        head.setTypeface(bold);
        head.setTextSize(28 * k);
        head.setColor(0xF2FFFFFF);
        c.drawText(s, LEFT * k, y * k, head);
        head.setTypeface(regular);
        if (hint != null) {
            text.setTextSize(22 * k);
            text.setColor(0x99FFFFFF);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(hint, (1920 - LEFT) * k, y * k, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawApps(Canvas c) {
        boolean here = zone == Z_APPS && !sheet;
        String hint = !here ? null : moving >= 0 ? "← → to move   ·   OK to put it down"
                : onAllApps() ? "Hold OK in All apps to add or remove" : "Hold OK to move";
        heading(c, "Apps", APPS_Y - 24, hint);
        float y = APPS_Y * k, w = CARD_W * k, h = CARD_H * k, radius = 16 * k;
        for (int i = 0; i <= favourites.size(); i++) {
            float x = (LEFT + i * (CARD_W + CARD_GAP) - appsScroll) * k;
            if (x + w < 0 || x > getWidth()) continue;
            boolean on = here && i == focusApp, lifted = on && moving == i;
            float lift = lifted ? -16 * k : 0;
            r.set(x, y + lift, x + w, y + h + lift);
            if (i < favourites.size()) {
                App a = apps.get(favourites.get(i));
                if (a != null) drawRounded(c, a.banner, null, r, radius, on ? 0 : here ? 0x4D : 0x33);
            } else {
                drawAllApps(c, r, radius);
                if (!on) {
                    fill.setColor(here ? 0x4D000000 : 0x33000000);
                    c.drawRoundRect(r, radius, radius, fill);
                }
            }
            if (on && !lifted) {
                drawSheen(c, r, radius);
            }
            if (on) {
                r.inset(-6 * k, -6 * k);
                ring.setColor(lifted ? 0xFF51B3F9 : 0xF2FFFFFF);
                c.drawRoundRect(r, radius + 6 * k, radius + 6 * k, ring);
                ring.setColor(0xF2FFFFFF);
            }
        }
    }

    /** A band of light sliding across the focused tile, a moment after you land on it and then every 5 seconds. */
    private void drawSheen(Canvas c, RectF box, float radius) {
        float phase = ambientT - focusSince - 0.3f;
        if (phase < 0) return;
        float sweep = (phase % 5f) / 0.9f;
        if (sweep >= 1) return;
        float eased = sweep * sweep * (3 - 2 * sweep);
        sheenAt.setRotate(20);
        sheenAt.postTranslate(box.left - 200 * k + eased * (box.width() + 280 * k), box.top);
        sheen.getShader().setLocalMatrix(sheenAt);
        c.drawRoundRect(box, radius, radius, sheen);
    }

    /**
     * A picture (or the part of it in `from`) filling a rounded box: drawn as the box's shape, its pixels from the
     * picture, which is much cheaper on this TV than cutting the corners off with a clip.
     */
    private void drawRounded(Canvas c, Bitmap b, RectF from, RectF box, float radius) {
        drawRounded(c, b, from, box, radius, 0);
    }

    /**
     * The same, darkened by `shade` (the alpha a black wash over it would have) as it's drawn: one pass, where a
     * wash on top would be a second over every tile.
     */
    private void drawRounded(Canvas c, Bitmap b, RectF from, RectF box, float radius, int shade) {
        if (shade > 0) {
            android.graphics.ColorFilter f = shades.get(shade);
            if (f == null) {
                int m = 255 - shade;
                f = new android.graphics.LightingColorFilter(Color.rgb(m, m, m), 0);
                shades.put(shade, f);
            }
            image.setColorFilter(f);
        }
        drawImage(c, b, from, box, radius);
        image.setColorFilter(null);
    }

    private void drawImage(Canvas c, Bitmap b, RectF from, RectF box, float radius) {
        BitmapShader s = imageShaders.get(b);
        if (s == null) {
            s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(b, s);
        }
        if (from == null) imageSrc.set(0, 0, b.getWidth(), b.getHeight());
        else if (from != imageSrc) imageSrc.set(from);
        imageAt.setRectToRect(imageSrc, box, android.graphics.Matrix.ScaleToFit.FILL);
        s.setLocalMatrix(imageAt);
        image.setShader(s);
        c.drawRoundRect(box, radius, radius, image);
        image.setShader(null);
    }

    /** All apps: four small squares on dark glass. */
    private void drawAllApps(Canvas c, RectF box, float radius) {
        fill.setColor(0xFF2B2B33);
        c.drawRoundRect(box, radius, radius, fill);
        float s = box.height() * 0.16f, gap = box.height() * 0.07f, cx = box.centerX(), cy = box.centerY() - box.height() * 0.08f;
        fill.setColor(0xF2FFFFFF);
        for (int q = 0; q < 4; q++) {
            float x = cx - s - gap / 2 + (q % 2) * (s + gap), y = cy - s - gap / 2 + (q / 2) * (s + gap);
            c.drawRoundRect(x, y, x + s, y + s, s * 0.25f, s * 0.25f, fill);
        }
        text.setTextSize(box.height() * 0.13f);
        text.setColor(0xD9FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("All apps", cx, box.bottom - box.height() * 0.1f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawInputs(Canvas c) {
        if (inputs.isEmpty()) return;
        boolean here = zone == Z_INPUTS && !sheet;
        heading(c, "Inputs", INPUTS_Y - 24, null);
        float y = INPUTS_Y * k, w = CARD_W * k, h = CARD_H * k, radius = 16 * k;
        for (int i = 0; i < inputs.size(); i++) {
            float x = (LEFT + i * (CARD_W + CARD_GAP) - inputsScroll) * k;
            if (x + w < 0 || x > getWidth()) continue;
            boolean on = here && i == focusInput;
            r.set(x, y, x + w, y + h);
            drawInputCard(c, inputs.get(i), r, on);
            if (on) {
                r.set(x, y, x + w, y + h);
                r.inset(-6 * k, -6 * k);
                c.drawRoundRect(r, radius + 6 * k, radius + 6 * k, ring);
            }
        }
    }

    /** An input: a drawn symbol and its name on glass, a green dot while a device sends a signal. */
    private void drawInputCard(Canvas c, Inputs.Input in, RectF box, boolean on) {
        float radius = 16 * k;
        fill.setColor(on ? 0xFFF2F2F5 : 0xCC1E1E26);
        c.drawRoundRect(box, radius, radius, fill);
        int ink = on ? 0xFF1D1D1F : in.kind == Inputs.HDMI && !in.connected ? 0x99FFFFFF : 0xF2FFFFFF;
        float s = box.height(), cx = box.left + 64 * k, cy = box.top + s * 0.3f;
        stroke.setStrokeWidth(s * 0.035f);
        stroke.setColor(ink);
        shape.reset();
        switch (in.kind) {
            case Inputs.LIVE:   // a screen on a stand (its own rectangle: `box` may be `r`)
                glyph.set(cx - s * 0.2f, cy - s * 0.13f, cx + s * 0.2f, cy + s * 0.11f);
                c.drawRoundRect(glyph, s * 0.025f, s * 0.025f, stroke);
                c.drawLine(cx - s * 0.08f, cy + s * 0.18f, cx + s * 0.08f, cy + s * 0.18f, stroke);
                break;
            case Inputs.HDMI:   // the port's shape
                shape.moveTo(cx - s * 0.18f, cy - s * 0.08f);
                shape.lineTo(cx + s * 0.18f, cy - s * 0.08f);
                shape.lineTo(cx + s * 0.18f, cy + s * 0.01f);
                shape.lineTo(cx + s * 0.11f, cy + s * 0.08f);
                shape.lineTo(cx - s * 0.11f, cy + s * 0.08f);
                shape.lineTo(cx - s * 0.18f, cy + s * 0.01f);
                shape.close();
                c.drawPath(shape, stroke);
                break;
            case Inputs.PICKER:   // a list
                for (int q = 0; q < 3; q++) {
                    float y = cy - s * 0.08f + q * s * 0.08f;
                    c.drawLine(cx - s * 0.15f, y, cx + s * 0.15f, y, stroke);
                }
                break;
            default:   // settings: a dial
                c.drawCircle(cx, cy, s * 0.12f, stroke);
                c.drawCircle(cx, cy, s * 0.04f, stroke);
                break;
        }
        if (in.kind == Inputs.HDMI && in.connected) {
            fill.setColor(0xFF30D158);
            c.drawCircle(box.right - 22 * k, box.top + 22 * k, 7 * k, fill);
        }
        head.setTypeface(bold);
        head.setTextSize(26 * k);
        head.setColor(ink);
        c.drawText(TextUtils.ellipsize(in.label, head, box.width() - 48 * k, TextUtils.TruncateAt.END).toString(),
                box.left + 24 * k, box.bottom - 50 * k, head);
        head.setTypeface(regular);
        text.setTextSize(19 * k);
        text.setColor(on ? 0x991D1D1F : 0x80FFFFFF);
        c.drawText(in.detail, box.left + 24 * k, box.bottom - 20 * k, text);
    }

    /** Scrolled down to the rows: the focused app's or input's name, big at the top, where the shows were. */
    private void drawBelowTitle(Canvas c) {
        float t = Math.min(1, pageY / BELOW);
        if (t < 0.4f || sheet) return;
        int alpha = Math.round(255 * (t - 0.4f) / 0.6f);
        String eyebrow, title;
        if (zone == Z_INPUTS && focusInput < inputs.size()) {
            Inputs.Input in = inputs.get(focusInput);
            eyebrow = in.kind == Inputs.HDMI ? (in.connected ? "INPUT   ·   CONNECTED" : "INPUT   ·   NO SIGNAL") : "INPUTS";
            title = in.label;
        } else if (onAllApps()) {
            eyebrow = "LIBRARY   ·   " + sortedApps.size() + " APPS";
            title = "All apps";
        } else if (focusApp < favourites.size()) {
            App a = apps.get(favourites.get(focusApp));
            eyebrow = moving >= 0 ? "MOVING" : "YOUR APPS   ·   " + (focusApp + 1) + " OF " + favourites.size();
            title = a == null ? "" : a.label;
        } else {
            return;
        }
        float x = LEFT * k;
        text.setTextSize(22 * k);
        text.setColor(Color.argb(alpha * 204 / 255, 255, 255, 255));
        text.setLetterSpacing(0.12f);
        c.drawText(eyebrow, x, 190 * k, text);
        text.setLetterSpacing(0);
        head.setTypeface(bold);
        head.setTextSize(76 * k);
        head.setColor(Color.argb(alpha, 255, 255, 255));
        c.drawText(TextUtils.ellipsize(title, head, 1200 * k, TextUtils.TruncateAt.END).toString(), x - 4 * k, 280 * k, head);
        head.setTypeface(regular);
    }

    /** All apps over the home screen: every app; OK opens, held OK adds it to your row or takes it off. */
    private void drawSheet(Canvas c) {
        head.setTypeface(regular);
        head.setTextSize(48 * k);
        head.setColor(Color.WHITE);
        c.drawText("All apps", LEFT * k, 120 * k, head);
        text.setTextSize(22 * k);
        text.setColor(0x99FFFFFF);
        c.drawText("OK opens   ·   hold OK to add to your row or take off   ·   Back closes", (LEFT + 4) * k, 160 * k, text);
        float w = GRID_W * k, h = GRID_H * k, radius = 14 * k;
        float left = (1920 - (GRID_COLS * GRID_W + (GRID_COLS - 1) * GRID_GAP)) / 2;
        c.save();
        c.clipRect(0, (GRID_TOP - 30) * k, getWidth(), getHeight());
        for (int i = 0; i < sortedApps.size(); i++) {
            App a = sortedApps.get(i);
            float x = (left + (i % GRID_COLS) * (GRID_W + GRID_GAP)) * k;
            float y = (GRID_TOP + (i / GRID_COLS) * (GRID_H + GRID_GAP + 40) - sheetScroll) * k;
            if (y + h < 0 || y > getHeight()) continue;
            boolean on = i == sheetFocus;
            r.set(x, y, x + w, y + h);
            drawRounded(c, a.banner, null, r, radius, on ? 0 : 0x4D);
            if (favourites.contains(a.pkg)) {
                fill.setColor(0xFF51B3F9);
                c.drawCircle(x + w - 16 * k, y + 16 * k, 10 * k, fill);
            }
            if (on) {
                r.inset(-5 * k, -5 * k);
                c.drawRoundRect(r, radius + 5 * k, radius + 5 * k, ring);
            }
            text.setTextSize(18 * k);
            text.setColor(on ? 0xFFFFFFFF : 0x99FFFFFF);
            c.drawText(TextUtils.ellipsize(a.label, text, w, TextUtils.TruncateAt.END).toString(), x, y + h + 28 * k, text);
        }
        c.restore();
    }
}
