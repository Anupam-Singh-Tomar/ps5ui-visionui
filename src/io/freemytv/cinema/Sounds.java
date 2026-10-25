package io.freemytv.cinema;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.provider.Settings;

/**
 * ps5UI's interface sounds, minimal soft UI clicks made by tools/make-sounds.py: a light click along a row, a fuller
 * one between rows, a double click for OK, a falling double click for Back, a dull tap at the end of a row. Played
 * as the TV's system sounds (their volume) and only while the TV's System sounds setting is on.
 */
final class Sounds {
    static final int MOVE = 0, ROW = 1, SELECT = 2, BACK = 3, EDGE = 4;
    private static final int[] FILES = {R.raw.move, R.raw.row, R.raw.select, R.raw.back, R.raw.edge};

    private final Context context;
    private final SoundPool pool;
    private final int[] ids = new int[FILES.length];
    private boolean on = true;

    Sounds(Context c) {
        context = c.getApplicationContext();
        pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        for (int i = 0; i < FILES.length; i++) ids[i] = pool.load(context, FILES[i], 1);
        refresh();
    }

    /** The TV's System sounds setting, read again whenever the home screen comes back. */
    void refresh() {
        on = Settings.System.getInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
    }

    void play(int sound) {
        if (on) pool.play(ids[sound], 1, 1, 1, 0, 1);
    }
}
