package io.freemytv.cinema;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** The home screen: one view, the clock kept current, and the shows asked for again after an hour away. */
public class CinemaActivity extends Activity {
    private CinemaView view;
    private long loadedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            view.invalidate();
            handler.postDelayed(this, 20_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new CinemaView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);   // the view paints every pixel: no need to paint black under it
        view.requestFocus();
        loadedAt = SystemClock.elapsedRealtime();
        HomeSetup.firstRun(this);
    }

    @Override protected void onResume() {
        super.onResume();
        view.refresh();   // apps installed or removed, inputs plugged in, meanwhile
        if (SystemClock.elapsedRealtime() - loadedAt > 3_600_000L) {
            loadedAt = SystemClock.elapsedRealtime();
            view.reloadShows();
        }
        handler.post(tick);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}
