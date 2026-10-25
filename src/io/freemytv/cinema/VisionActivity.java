package io.freemytv.cinema;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** visionUI: one view, its clock and the hero's turn kept moving, and the shows asked for again after an hour away. */
public class VisionActivity extends Activity {
    private VisionView view;
    private long loadedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            view.tick();
            handler.postDelayed(this, 9_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new VisionView(this);
        setContentView(view);
        view.requestFocus();
        loadedAt = SystemClock.elapsedRealtime();
    }

    @Override protected void onResume() {
        super.onResume();
        view.refresh();   // apps installed or removed, inputs plugged in, meanwhile
        if (SystemClock.elapsedRealtime() - loadedAt > 3_600_000L) {
            loadedAt = SystemClock.elapsedRealtime();
            view.reloadShows();
        }
        handler.postDelayed(tick, 9_000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
