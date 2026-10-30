package io.freemytv.cinema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * For the corner (Corner): the account chosen on Google TV's picker (its name only), from the Home button (KeyService)
 * or from Free My TV's. Kept in the home screen's preferences, where the corner hears it even when it opens later.
 */
public class CornerReceiver extends BroadcastReceiver {
    static final String ACCOUNT = "io.freemytv.cinema.ACCOUNT";

    @Override public void onReceive(Context c, Intent intent) {
        if (!ACCOUNT.equals(intent.getAction())) return;
        String name = intent.getStringExtra("name");
        if (name == null || name.length() > 60 || name.contains("@")) return;   // a name, never an address
        c.getSharedPreferences("cinema", Context.MODE_PRIVATE).edit().putString("account", name.trim()).apply();
    }
}
