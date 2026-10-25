package io.freemytv.cinema;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * New episodes on the TV's own streaming apps, from TVmaze's web schedule (no key; its licence asks for credit,
 * which the screen gives). The schedule comes in two halves: country IN holds India's services (JioHotstar), the
 * global half the worldwide ones (Netflix, Prime Video). Past days never change, so they're cached for good;
 * today is asked again every 3 hours.
 */
final class Shows {
    private Shows() {}

    static final String TAG = "Cinema";
    private static final int DAYS = 7, MOST = 20, POSTER_W = 440;

    /** A streaming service: its name on cards, its app, how TVmaze names it, and its colour for the glow. */
    static final class Service {
        final String label, pkg;
        final Pattern match;
        final int color;

        Service(String label, String pkg, String match, int color) {
            this.label = label;
            this.pkg = pkg;
            this.match = Pattern.compile(match, Pattern.CASE_INSENSITIVE);
            this.color = color;
        }
    }

    static final Service[] SERVICES = {
        new Service("Netflix", "com.netflix.ninja", "^netflix$", 0xFFE50914),
        new Service("Prime Video", "com.amazon.amazonvideo.livingroom", "prime video", 0xFF1A98FF),
        new Service("JioHotstar", "in.startv.hotstar", "hotstar", 0xFF2D5BFF),
        new Service("Apple TV", "com.apple.atve.androidtv.appletv", "^apple tv", 0xFFB0B4BA),
        new Service("ZEE5", "com.graymatrix.did", "zee5", 0xFF8230C6),
        new Service("SonyLIV", "com.sonyliv", "sony ?liv", 0xFFF28C00),
        new Service("aha", "ahaflix.tv", "^aha$", 0xFFFF6A13),
        new Service("discovery+", "com.discoveryplus.tv.android", "discovery\\+", 0xFF2175D9),
    };

    static final class Show {
        int id;
        String name, years, detail, genres, poster, summary = "";
        double rating;
        Service service;
        String airdate = "";
        volatile Bitmap image;
        /** A race weekend (Races) instead of a show: its own eyebrow line, its photo (or art made for it) and credit. */
        String eyebrow, credit;
        String[] queries;   // what to search Wikimedia Commons for, best first
        Bitmap wide;
    }

    interface Listener {
        /** On the main thread: the list (posters may still be loading), or an empty one. */
        void onShows(List<Show> shows);

        /** On the main thread: a poster arrived. */
        void onPoster();
    }

    static void load(Context c, Listener listener) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<Show> shows = new ArrayList<>();
            try {
                shows = collect(app);
            } catch (Exception e) {
                Log.w(TAG, "schedule: " + e);
            }
            List<Show> result = shows;
            main.post(() -> listener.onShows(result));
            for (Show s : result) {
                if (s.poster == null) continue;
                try {
                    s.image = poster(app, s);
                    main.post(listener::onPoster);
                } catch (Exception e) {
                    Log.w(TAG, "poster " + s.name + ": " + e);
                }
            }
        }, "cinema-shows").start();
    }

    private static List<Show> collect(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        List<Service> installed = new ArrayList<>();
        for (Service s : SERVICES) {
            try {
                pm.getPackageInfo(s.pkg, 0);
                installed.add(s);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        Map<Integer, Show> byId = new LinkedHashMap<>();
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < DAYS; i++) {
            String date = day.format(cal.getTime());
            for (String country : new String[] {"IN", ""}) {
                String json = schedule(c, date, country, i == 0);
                if (json != null) add(new JSONArray(json), installed, byId);
            }
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
        List<Show> list = new ArrayList<>(byId.values());
        // Newest first; among the same day, the better rated.
        list.sort((a, b) -> {
            int d = b.airdate.compareTo(a.airdate);
            return d != 0 ? d : Double.compare(b.rating, a.rating);
        });
        return list.size() > MOST ? new ArrayList<>(list.subList(0, MOST)) : list;
    }

    private static void add(JSONArray episodes, List<Service> installed, Map<Integer, Show> byId) {
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject ep = episodes.optJSONObject(i);
            JSONObject show = ep == null ? null : ep.optJSONObject("_embedded") == null ? null
                    : ep.optJSONObject("_embedded").optJSONObject("show");
            if (show == null) continue;
            JSONObject channel = show.optJSONObject("webChannel");
            if (channel == null) continue;
            String channelName = channel.optString("name");
            Service service = null;
            for (Service s : installed) if (s.match.matcher(channelName).find()) service = s;
            if (service == null) continue;
            int id = show.optInt("id");
            Show s = byId.get(id);
            String airdate = ep.optString("airdate", "");
            if (s != null && s.airdate.compareTo(airdate) >= 0) continue;   // keep the newest episode
            if (s == null) {
                s = new Show();
                s.id = id;
                byId.put(id, s);
            }
            s.service = service;
            s.airdate = airdate;
            s.name = show.optString("name");
            JSONObject image = show.optJSONObject("image");
            s.poster = image == null ? null : image.optString("original", image.optString("medium", null));
            JSONObject rating = show.optJSONObject("rating");
            s.rating = rating == null ? 0 : rating.optDouble("average", 0);
            if (Double.isNaN(s.rating)) s.rating = 0;
            String from = year(show.optString("premiered", "")), to = year(show.optString("ended", ""));
            s.years = from.isEmpty() ? "" : to.isEmpty() ? from + "–" : from.equals(to) ? from : from + "–" + to;
            int season = ep.optInt("season", 0);
            s.detail = season > 0 ? "Season " + season : show.optString("type", "");
            JSONArray genres = show.optJSONArray("genres");
            StringBuilder g = new StringBuilder();
            for (int k = 0; genres != null && k < genres.length() && k < 3; k++) {
                if (g.length() > 0) g.append(" · ");
                g.append(genres.optString(k));
            }
            s.genres = g.toString();
            String summary = show.optString("summary", "");
            s.summary = summary.equals("null") ? "" : android.text.Html.fromHtml(summary, android.text.Html.FROM_HTML_MODE_COMPACT)
                    .toString().replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * The show's wide background art (TVmaze's images, type "background", the main one first), at about 1280 px wide,
     * or null when it has none. Cached by show. Off the main thread.
     */
    static Bitmap backdrop(Context c, Show s) {
        File dir = new File(c.getCacheDir(), "backdrops");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, s.id + ".jpg"), none = new File(dir, s.id + ".none");
        try {
            byte[] bytes;
            if (f.exists()) {
                bytes = read(new FileInputStream(f));
            } else {
                if (none.exists()) return null;
                JSONArray images = new JSONArray(new String(get("https://api.tvmaze.com/shows/" + s.id + "/images"),
                        StandardCharsets.UTF_8));
                String url = null;
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.getJSONObject(i);
                    if (!"background".equals(img.optString("type"))) continue;
                    JSONObject res = img.optJSONObject("resolutions");
                    JSONObject size = res == null ? null : res.optJSONObject("original");
                    if (size == null) continue;
                    if (url == null || img.optBoolean("main")) url = size.optString("url");
                }
                if (url == null) {
                    //noinspection ResultOfMethodCallIgnored
                    none.createNewFile();
                    return null;
                }
                bytes = get(url);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(bytes);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1280) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            Log.w(TAG, "backdrop " + s.name + ": " + e);
            return null;
        }
    }

    private static String year(String date) {
        return date != null && date.length() >= 4 && !date.equals("null") ? date.substring(0, 4) : "";
    }

    /** One half-day of the schedule: from the cache, or TVmaze (today: again after 3 hours). */
    private static String schedule(Context c, String date, String country, boolean today) {
        File dir = new File(c.getCacheDir(), "tvmaze");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, date + "-" + (country.isEmpty() ? "world" : country) + ".json");
        boolean fresh = f.exists() && (!today || System.currentTimeMillis() - f.lastModified() < 3 * 3_600_000L);
        if (fresh) {
            try {
                return new String(read(new FileInputStream(f)), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
        try {
            byte[] body = get("https://api.tvmaze.com/schedule/web?date=" + date + "&country=" + country);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(body);
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "tvmaze " + date + " " + country + ": " + e.getMessage());
            if (!f.exists()) return null;
            try {   // offline: today's older copy is better than nothing
                return new String(read(new FileInputStream(f)), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    /** The poster at about the centre card's size, cached by show. */
    private static Bitmap poster(Context c, Show s) throws IOException {
        File dir = new File(c.getCacheDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, s.id + ".jpg");
        byte[] bytes;
        if (f.exists()) {
            bytes = read(new FileInputStream(f));
        } else {
            bytes = get(s.poster);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= POSTER_W) o.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(10_000);
        http.setReadTimeout(15_000);
        http.setRequestProperty("User-Agent", "Cinema home screen (Android TV)");
        try {
            if (http.getResponseCode() != 200) throw new IOException("HTTP " + http.getResponseCode());
            return read(http.getInputStream());
        } finally {
            http.disconnect();
        }
    }

    static byte[] read(InputStream in) throws IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = is.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
