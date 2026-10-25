package io.freemytv.cinema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Race weekends in the next two weeks, for ps5UI's top row: Formula 1 from Jolpica (the Ergast API's successor) and
 * MotoGP from motogp.com's own data, neither needing a key. Both stream on FanCode in India (F1 through 2028, MotoGP
 * through 2027), so that's the app a race opens. Each comes as a Shows.Show, with a tile and wide art drawn for it.
 */
final class Races {
    private Races() {}

    private static final String TAG = "Cinema";
    private static final long WINDOW_MS = 14 * 86_400_000L, CACHE_MS = 6 * 3_600_000L;
    private static final String FANCODE = "com.fancode.tv";
    private static final String MOTOGP_CATEGORY = "e8c110ad-64aa-4e8e-8a86-f2f152f6a942";
    static final Shows.Service F1 = new Shows.Service("FanCode", FANCODE, "^$", 0xFFE10600);
    static final Shows.Service MOTOGP = new Shows.Service("FanCode", FANCODE, "^$", 0xFFFF6A13);

    interface Listener {
        void onRaces(List<Shows.Show> races);
    }

    static void load(Context c, Listener listener) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<Shows.Show> out = new ArrayList<>();
            try {
                out.addAll(f1(app));
            } catch (Exception e) {
                Log.w(TAG, "f1: " + e);
            }
            try {
                out.addAll(motogp(app));
            } catch (Exception e) {
                Log.w(TAG, "motogp: " + e);
            }
            out.sort((a, b) -> a.airdate.compareTo(b.airdate));   // soonest first (airdate holds the race's time)
            for (Shows.Show s : out) {
                boolean f1 = s.service == F1;
                s.image = tile(f1, s);
                s.wide = poster(f1, s);   // until the photo comes, or if there's none
            }
            main.post(() -> listener.onRaces(out));
            // Then a real photo for each, from Wikimedia Commons
            boolean any = false;
            for (Shows.Show s : out) any |= photo(app, s);
            if (any) main.post(() -> listener.onRaces(out));
        }, "cinema-races").start();
    }

    // MARK: - Formula 1

    private static List<Shows.Show> f1(Context c) throws Exception {
        JSONObject data = new JSONObject(cached(c, "f1.json", "https://api.jolpi.ca/ergast/f1/current.json"));
        JSONArray races = data.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        List<Shows.Show> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < races.length(); i++) {
            JSONObject r = races.getJSONObject(i);
            long race = utc(r.optString("date"), r.optString("time", "12:00:00Z"));
            if (race < now - 3 * 3_600_000L || race > now + WINDOW_MS) continue;
            JSONObject loc = r.getJSONObject("Circuit").getJSONObject("Location");
            List<String[]> sessions = new ArrayList<>();
            String[][] names = {{"FirstPractice", "FP1"}, {"SecondPractice", "FP2"}, {"ThirdPractice", "FP3"},
                    {"SprintQualifying", "Sprint Qualifying"}, {"Sprint", "Sprint"}, {"Qualifying", "Qualifying"}};
            for (String[] n : names) {
                JSONObject s = r.optJSONObject(n[0]);
                if (s != null) sessions.add(new String[] {n[1], s.optString("date"), s.optString("time", "12:00:00Z")});
            }
            sessions.add(new String[] {"Race", r.optString("date"), r.optString("time", "12:00:00Z")});
            Shows.Show s = show(F1, 900_000 + r.optInt("round"), "F1", r.optInt("round"), r.optString("raceName"),
                    loc.optString("locality") + ", " + loc.optString("country"), loc.optString("country"), race, sessions);
            String circuit = r.getJSONObject("Circuit").optString("circuitName");
            // The track first, quoted so the search keeps its whole name: "Circuit of the Americas" "Grand Prix"
            s.queries = new String[] {"\"" + circuit + "\" \"Grand Prix\"", "\"" + circuit + "\" Formula 1",
                    r.optString("raceName") + " Formula 1", "Formula 1 " + loc.optString("locality")};
            out.add(s);
        }
        return out;
    }

    // MARK: - MotoGP

    private static List<Shows.Show> motogp(Context c) throws Exception {
        JSONArray seasons = new JSONArray(cached(c, "motogp-seasons.json", "https://api.motogp.pulselive.com/motogp/v1/results/seasons"));
        String season = null;
        for (int i = 0; i < seasons.length(); i++) {
            if (seasons.getJSONObject(i).optBoolean("current")) season = seasons.getJSONObject(i).optString("id");
        }
        if (season == null) return new ArrayList<>();
        JSONArray events = new JSONArray(cached(c, "motogp-events.json",
                "https://api.motogp.pulselive.com/motogp/v1/results/events?seasonUuid=" + season + "&isFinished=false"));
        List<Shows.Show> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            if (e.optBoolean("test")) continue;
            long start = utc(e.optString("date_start"), "00:00:00Z");
            if (start > now + WINDOW_MS) continue;
            JSONArray list = new JSONArray(cached(c, "motogp-" + e.optString("id") + ".json",
                    "https://api.motogp.pulselive.com/motogp/v1/results/sessions?eventUuid=" + e.optString("id")
                            + "&categoryUuid=" + MOTOGP_CATEGORY));
            List<String[]> sessions = new ArrayList<>();
            long race = start;
            for (int j = 0; j < list.length(); j++) {
                JSONObject s = list.getJSONObject(j);
                String type = s.optString("type"), label;
                switch (type) {
                    case "PR": label = "Practice"; break;
                    case "Q": label = "Qualifying " + s.optInt("number", 1); break;
                    case "SPR": label = "Sprint"; break;
                    case "RAC": label = "Race"; break;
                    default: continue;
                }
                long at = iso(s.optString("date"));
                sessions.add(new String[] {label, String.valueOf(at)});
                if (type.equals("RAC")) race = at;
            }
            if (race < now - 3 * 3_600_000L || race > now + WINDOW_MS) continue;
            JSONObject circuit = e.optJSONObject("circuit"), country = e.optJSONObject("country");
            String place = (circuit == null ? "" : circuit.optString("place") + ", ") + (country == null ? "" : country.optString("name"));
            Shows.Show s = show(MOTOGP, 800_000 + (e.optString("id").hashCode() & 0xFFFF), "MotoGP", 0,
                    titleCase(e.optString("name")), place, country == null ? "" : country.optString("name"), race, sessions);
            // The circuit's own name, without sponsors and the generic words: "Petronas Sepang International Circuit" -> "Sepang"
            String track = circuit == null ? "" : circuit.optString("name").replaceAll("(?i)\\b(petronas|international|circuit|de|del|la|autodromo|autódromo|circuito)\\b", "").trim();
            s.queries = new String[] {"MotoGP " + track, "MotoGP " + (circuit == null ? "" : circuit.optString("place")),
                    "MotoGP " + (country == null ? "" : country.optString("name"))};
            out.add(s);
        }
        return out;
    }

    // MARK: - As a show

    private static Shows.Show show(Shows.Service series, int id, String label, int round, String name, String place,
            String country, long race, List<String[]> sessions) {
        Shows.Show s = new Shows.Show();
        s.id = id;
        s.service = series;
        s.name = name;
        s.years = "";
        DateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
        DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault());
        s.detail = "Race  " + day.format(new Date(race)) + ", " + time.format(new Date(race));
        s.genres = place;
        // In calendar days, here: a race at 1:30 AM tomorrow is "tomorrow", even if it's less than 24 hours away
        long days = Math.max(0, dayNumber(race) - dayNumber(System.currentTimeMillis()));
        s.eyebrow = label + (round > 0 ? " · ROUND " + round : "") + " · "
                + (days == 0 ? "TODAY" : days == 1 ? "TOMORROW" : "IN " + days + " DAYS");
        StringBuilder about = new StringBuilder("Live on FanCode.   ");
        for (String[] sess : sessions) {
            long at = sess.length == 3 ? utc(sess[1], sess[2]) : Long.parseLong(sess[1]);
            about.append(sess[0]).append(" ").append(day.format(new Date(at))).append(", ").append(time.format(new Date(at))).append("   ·   ");
        }
        s.summary = about.substring(0, about.length() - 7);
        s.airdate = String.format(Locale.US, "%015d", race);
        s.poster = country;   // the tile's flag
        return s;
    }

    /** Days since 1970 in this TV's time zone. */
    private static long dayNumber(long at) {
        return (at + TimeZone.getDefault().getOffset(at)) / 86_400_000L;
    }

    /** A country's flag, as emoji (from its name; the countries F1 and MotoGP visit). */
    private static String flag(String country) {
        String[][] codes = {{"Mexico", "MX"}, {"Brazil", "BR"}, {"USA", "US"}, {"United States", "US"}, {"Qatar", "QA"},
                {"Malaysia", "MY"}, {"Spain", "ES"}, {"Portugal", "PT"}, {"Australia", "AU"}, {"Japan", "JP"},
                {"Thailand", "TH"}, {"Indonesia", "ID"}, {"India", "IN"}, {"Italy", "IT"}, {"UAE", "AE"},
                {"Abu Dhabi", "AE"}, {"United Arab Emirates", "AE"}, {"Saudi Arabia", "SA"}, {"Bahrain", "BH"},
                {"Singapore", "SG"}, {"Azerbaijan", "AZ"}, {"China", "CN"}, {"Canada", "CA"}, {"Monaco", "MC"},
                {"Austria", "AT"}, {"UK", "GB"}, {"United Kingdom", "GB"}, {"Great Britain", "GB"}, {"Belgium", "BE"},
                {"Hungary", "HU"}, {"Netherlands", "NL"}, {"Germany", "DE"}, {"France", "FR"}, {"Argentina", "AR"},
                {"Czechia", "CZ"}, {"Czech Republic", "CZ"}, {"San Marino", "SM"}, {"Las Vegas", "US"}};
        for (String[] c : codes) {
            if (country.equalsIgnoreCase(c[0])) {
                return new String(Character.toChars(0x1F1E6 + c[1].charAt(0) - 'A'))
                        + new String(Character.toChars(0x1F1E6 + c[1].charAt(1) - 'A'));
            }
        }
        return "🏁";
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.equals("of") || w.equals("de") ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    // MARK: - Pictures made for a race

    /** The square tile: the series' colour, its name big, the round, the flag and the race day. */
    private static Bitmap tile(boolean f1, Shows.Show s) {
        int n = 440;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int color = s.service.color;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, n, n, color, darker(color, 0.25f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, n, n, p);
        speedLines(c, n, n, 0x26FFFFFF);
        checkers(c, n * 0.62f, n * 0.62f, n, n, 22, 0x33FFFFFF);
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        t.setTextSize(f1 ? 170 : 104);
        c.drawText(f1 ? "F1" : "MotoGP", 30, 230, t);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(34);
        t.setLetterSpacing(0.08f);
        String[] parts = s.detail.replace("Race  ", "").split(", ");
        c.drawText(parts[0].toUpperCase(Locale.getDefault()), 32, 340, t);
        t.setColor(0xCCFFFFFF);
        c.drawText(parts.length > 1 ? parts[1] : "", 32, 386, t);
        t.setTextSize(84);
        t.setLetterSpacing(0);
        c.drawText(flag(s.poster), n - 120, 110, t);
        return b;
    }

    /** The wide backdrop: dark, a glow and speed stripes in the series' colour, a chequered flag melting in. */
    private static Bitmap poster(boolean f1, Shows.Show s) {
        int w = 1280, h = 720;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int color = s.service.color;
        c.drawColor(0xFF09090C);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(w * 0.78f, h * 0.35f, w * 0.7f,
                new int[] {(color & 0xFFFFFF) | 0xCC000000, (color & 0xFFFFFF) | 0x33000000, 0}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        speedLines(c, w, h, 0x1FFFFFFF);
        checkers(c, w * 0.55f, 0, w, h * 0.55f, 34, 0x24FFFFFF);
        TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0x24FFFFFF);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD_ITALIC));
        t.setTextSize(f1 ? 420 : 250);
        t.setTextAlign(Paint.Align.RIGHT);
        c.drawText(f1 ? "F1" : "MotoGP", w - 40, h - 70, t);
        return b;
    }

    private static void speedLines(Canvas c, float w, float h, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        float[][] lines = {{-0.1f, 0.9f, 0.6f, 0.35f, 0.035f}, {0.1f, 1.05f, 0.9f, 0.4f, 0.018f}, {0.35f, 1.1f, 1.1f, 0.5f, 0.05f},
                {-0.2f, 0.6f, 0.4f, 0.1f, 0.012f}};
        for (float[] l : lines) {
            p.setStrokeWidth(h * l[4]);
            c.drawLine(w * l[0], h * l[1], w * l[2], h * l[3], p);
        }
    }

    /** A chequered flag over a box, fading from its far corner. */
    private static void checkers(Canvas c, float left, float top, float right, float bottom, float size, int color) {
        Paint p = new Paint();
        int layer = c.saveLayer(left, top, right, bottom, null);
        p.setColor(color);
        for (float y = top; y < bottom; y += size) {
            for (float x = left; x < right; x += size) {
                if ((Math.round((x - left) / size) + Math.round((y - top) / size)) % 2 == 0) c.drawRect(x, y, x + size, y + size, p);
            }
        }
        Paint fade = new Paint();
        fade.setShader(new LinearGradient(right, top, left, bottom, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        fade.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        c.drawRect(left, top, right, bottom, fade);
        c.restoreToCount(layer);
    }

    private static int darker(int color, float f) {
        return Color.rgb(Math.round(Color.red(color) * f), Math.round(Color.green(color) * f), Math.round(Color.blue(color) * f));
    }

    // MARK: - A real photo, from Wikimedia Commons

    private static final java.util.regex.Pattern NOT_A_PHOTO =
            java.util.regex.Pattern.compile("(?i).*(layout|map|logo|skysat|diagram|plan|aerial|track\\b|svg).*");
    /** A year before 2010 in the name: an old race, with cars and liveries that look nothing like today's. */
    private static final java.util.regex.Pattern OLD = java.util.regex.Pattern.compile(".*\\b(19\\d\\d|200\\d)\\b.*");

    /**
     * The race's backdrop: a photo from Wikimedia Commons (freely licensed), the first wide, large JPEG that isn't a
     * map or a logo among the searches in s.queries, with its author and licence for the credit. Kept a week; a race
     * with none found is asked about again after a day. True if a photo was put on the race.
     */
    private static boolean photo(Context c, Shows.Show s) {
        if (s.queries == null) return false;
        File dir = new File(c.getCacheDir(), "races");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // "track-": found by the track's name; the older "photo-" ones (by race name) are dropped
        for (String old : new String[] {".jpg", ".txt", ".none"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "photo-" + s.id + old).delete();
        }
        File jpg = new File(dir, "track-" + s.id + ".jpg"), credit = new File(dir, "track-" + s.id + ".txt"),
                none = new File(dir, "track-" + s.id + ".none");
        try {
            boolean fresh = jpg.exists() && System.currentTimeMillis() - jpg.lastModified() < 7 * 86_400_000L;
            if (!fresh) {
                if (none.exists() && System.currentTimeMillis() - none.lastModified() < 86_400_000L) return false;
                if (!find(s.queries, jpg, credit)) {
                    //noinspection ResultOfMethodCallIgnored
                    none.createNewFile();
                    none.setLastModified(System.currentTimeMillis());
                    return false;
                }
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            Bitmap b = android.graphics.BitmapFactory.decodeFile(jpg.getPath(), o);
            if (b == null) return false;
            s.wide = b;
            s.credit = credit.exists() ? new String(Shows.read(new FileInputStream(credit)), StandardCharsets.UTF_8) : "Photo: Wikimedia Commons";
            return true;
        } catch (Exception e) {
            Log.w(TAG, "photo " + s.name + ": " + e);
            return false;
        }
    }

    private static boolean find(String[] queries, File jpg, File credit) throws Exception {
        for (String q : queries) {
            String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6"
                    + "&gsrlimit=15&gsrsearch=" + java.net.URLEncoder.encode(q.trim() + " filetype:bitmap", "UTF-8")
                    + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiextmetadatafilter=Artist%7CLicenseShortName"
                    + "&iiurlwidth=1280";
            JSONObject query = new JSONObject(new String(Shows.get(url), StandardCharsets.UTF_8)).optJSONObject("query");
            JSONObject pages = query == null ? null : query.optJSONObject("pages");
            if (pages == null) continue;
            JSONObject best = null;
            int bestIndex = Integer.MAX_VALUE;
            for (java.util.Iterator<String> it = pages.keys(); it.hasNext(); ) {
                JSONObject page = pages.getJSONObject(it.next());
                JSONArray infos = page.optJSONArray("imageinfo");
                if (infos == null || infos.length() == 0) continue;
                JSONObject info = infos.getJSONObject(0);
                int w = info.optInt("width"), h = info.optInt("height");
                if (!"image/jpeg".equals(info.optString("mime")) || w < 1400 || w < h * 1.3f) continue;
                String title = page.optString("title");
                if (NOT_A_PHOTO.matcher(title).matches() || OLD.matcher(title).matches()) continue;
                if (page.optInt("index", 99) < bestIndex) {
                    bestIndex = page.optInt("index", 99);
                    best = info;
                }
            }
            if (best == null) continue;
            byte[] bytes = Shows.get(best.optString("thumburl"));
            try (FileOutputStream out = new FileOutputStream(jpg)) {
                out.write(bytes);
            }
            JSONObject meta = best.optJSONObject("extmetadata");
            String artist = meta == null || meta.optJSONObject("Artist") == null ? ""
                    : android.text.Html.fromHtml(meta.optJSONObject("Artist").optString("value"), 0).toString().trim();
            String licence = meta == null || meta.optJSONObject("LicenseShortName") == null ? ""
                    : meta.optJSONObject("LicenseShortName").optString("value");
            String line = "Photo: " + (artist.isEmpty() ? "" : artist + " · ") + (licence.isEmpty() ? "" : licence + " · ") + "Wikimedia Commons";
            try (FileOutputStream out = new FileOutputStream(credit)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        }
        return false;
    }

    // MARK: - Time and the cache

    private static long utc(String date, String time) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(date + "T" + (time.endsWith("Z") ? time : time + "Z")).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long iso(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** A JSON document, from the cache for 6 hours, else fetched (and the old copy kept if offline). */
    private static String cached(Context c, String name, String url) throws IOException {
        File dir = new File(c.getCacheDir(), "races");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < CACHE_MS) {
            return new String(Shows.read(new FileInputStream(f)), StandardCharsets.UTF_8);
        }
        try {
            byte[] body = Shows.get(url);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(body);
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (f.exists()) return new String(Shows.read(new FileInputStream(f)), StandardCharsets.UTF_8);
            throw e;
        }
    }
}
