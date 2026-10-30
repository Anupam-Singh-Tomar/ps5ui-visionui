package io.freemytv.cinema;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free memory (the corner): closes the apps Android keeps waiting in the background, through the TV's own ADB, as Free
 * My TV does (`am kill-all` stops only those it may: nothing on screen, playing or needed by the system, so not ps5UI
 * or its Home button). On a 2 GB TV that gives the next app room before it starts.
 */
final class MemoryCleaner {
    private MemoryCleaner() {}

    interface Done {
        /** `finished` false while the TV waits for debugging to be allowed. */
        void done(String text, boolean finished);
    }

    private static final Pattern AVAILABLE = Pattern.compile("MemAvailable:\\s+(\\d+) kB");
    private static final AtomicBoolean running = new AtomicBoolean();

    /**
     * Frees what it can on a background thread; `done` gets the result on the main thread. One at a time: false (and
     * no `done`) while one is already running. The first time, the TV asks to allow debugging (up to a minute).
     */
    static boolean clean(Context c, Done done) {
        if (!running.compareAndSet(false, true)) return false;
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String text;
            try (Adb adb = Adb.connect(app, 60_000, () -> main.post(() -> done.done("Allow debugging on the TV…", false)))) {
                long before = available(adb);
                adb.shell("am kill-all");
                SystemClock.sleep(1_500);   // the killed processes take a moment to give their memory back
                long after = available(adb);
                text = "Freed " + Math.max(0, after - before) + " MB · " + after + " MB free";
            } catch (IOException e) {
                text = "Couldn't free memory: " + e.getMessage();
            }
            running.set(false);
            String result = text;
            main.post(() -> done.done(result, true));
        }, "free-memory").start();
        return true;
    }

    /** MemAvailable, in MB. */
    private static long available(Adb adb) throws IOException {
        Matcher m = AVAILABLE.matcher(adb.shell("cat /proc/meminfo"));
        return m.find() ? Long.parseLong(m.group(1)) / 1024 : 0;
    }
}
