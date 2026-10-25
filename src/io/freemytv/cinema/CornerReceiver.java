package io.freemytv.cinema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * From Free My TV, for the corner (Corner): the account chosen on Google TV's picker (its name only), and what Free
 * memory freed. Kept in the home screen's preferences, where the corner hears them even when it opens later.
 */
public class CornerReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        android.content.SharedPreferences.Editor prefs = c.getSharedPreferences("cinema", Context.MODE_PRIVATE).edit();
        if ("io.freemytv.cinema.ACCOUNT".equals(action)) {
            String name = intent.getStringExtra("name");
            if (name == null || name.length() > 60 || name.contains("@")) return;   // a name, never an address
            prefs.putString("account", name.trim()).apply();
        } else if ("io.freemytv.cinema.FREED".equals(action)) {
            String text = intent.getStringExtra("text");
            prefs.putString("freed", text == null ? "" : text).putLong("freedAt", System.currentTimeMillis()).apply();
        }
    }
}
