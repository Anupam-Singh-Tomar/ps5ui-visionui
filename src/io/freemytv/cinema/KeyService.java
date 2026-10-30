package io.freemytv.cinema;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ps5UI's Home button, the way Free My TV's safe mode does it on Google TV, so one app is all it takes: Google TV's
 * home screen stays the TV's real home (nothing is turned off, so the TV can't get stuck at start), and
 *  - the remote's Home key opens the home screen picked in HomeSetup (ps5UI or visionUI), from any app;
 *  - when Google TV's home comes up anyway (after a start, on waking, backing out of an app), ours opens instead;
 *  - when the TV starts or wakes, Google TV's own "Choose an account" screen first (if picked in HomeSetup);
 *  - on that screen only, the name on the account chosen goes to the corner (CornerReceiver), never the email.
 * The system waits for it on every key, so it runs in a process of its own (:keys) and does next to nothing. When Free
 * My TV's own key service is on, it does all this already: this one stays out of its way.
 */
public class KeyService extends AccessibilityService {
    static final String NAME = "io.freemytv.cinema/io.freemytv.cinema.KeyService";
    private static final String FREE_MY_TV = "io.freemytv/";
    private static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_HOME = LAUNCHERX + ".home.HomeActivity";
    private static final String PROFILES = LAUNCHERX + "/.profile.chooser.ProfileChooserActivity";
    private static final String PROFILES_CLASS = LAUNCHERX + ".profile.chooser.ProfileChooserActivity";
    /** The account picker's buttons read "Name, email, : select to continue with current account" (or "to log in"). */
    private static final Pattern ACCOUNT = Pattern.compile("^([^,@]+), [^,\\s]+@[^,\\s]+,");
    /**
     * How long the TV's home must stay in front before ours replaces it. On waking, Google TV brings its home up, then
     * a dispatcher, then its home again with everything above it cleared, over about two seconds: so while the picker
     * is due (just woken), wait until it settles; otherwise (backing out of an app) a moment is enough.
     */
    private static final long SETTLE_WOKEN_MS = 2_500, SETTLE_MS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** The TV's own home screen (its class), the one ours replaces, besides Google TV's. */
    private String stockHome;
    private boolean freeMyTv;
    /** The screen in front (its class), from the window events; when we opened the picker, and when it showed. */
    private String front = "";
    private boolean profilesDue;
    private long pickerOpenedAt, pickerShownAt;
    private int pickerRetries;
    private String account = "";
    private boolean listening;

    private final Runnable settled = () -> {
        if (!isStockHome(front)) return;   // something else came up meanwhile
        if (profilesDue) {
            profilesDue = false;
            openProfiles();
        } else {
            openHome();
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            onTvOn();
        }
    };

    private final ContentObserver services = new ContentObserver(handler) {
        @Override public void onChange(boolean self) {
            freeMyTv = freeMyTvOn(KeyService.this);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        stockHome = stockHome();
        freeMyTv = freeMyTvOn(this);
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, services);
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        listening = true;
        // Connected in the first minutes after boot: the TV just started (not an app update or a setting change).
        if (SystemClock.elapsedRealtime() < 3 * 60_000) onTvOn();
    }

    @Override public void onDestroy() {
        if (listening) {
            unregisterReceiver(screenOn);
            getContentResolver().unregisterContentObserver(services);
        }
        super.onDestroy();
    }

    @Override public void onInterrupt() {}

    /** Ours on Home's release; the press itself is held back, so the TV's own home never sees it. */
    @Override protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME || freeMyTv || HomeSetup.home(this).equals(HomeSetup.OFF)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) openHome();
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (freeMyTv) return;
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) onWindow(e);
        else onAccount(e);
    }

    private void onWindow(AccessibilityEvent e) {
        CharSequence cls = e.getClassName(), pkg = e.getPackageName();
        if (cls == null || pkg == null) return;
        String name = cls.toString();
        // System UI's panels and plain views (volume, dialogs' frames) come and go over a screen: not screens.
        if ("com.android.systemui".contentEquals(pkg) || name.startsWith("android.widget.") || name.startsWith("android.view.")) return;
        long now = SystemClock.elapsedRealtime();
        front = name;
        if (name.equals(PROFILES_CLASS)) pickerShownAt = now;
        handler.removeCallbacks(settled);
        if (!isStockHome(name) || HomeSetup.home(this).equals(HomeSetup.OFF)) return;
        // The picker we opened, closed again by Google TV before anyone could choose (it never showed, or showed for
        // a moment): it's still due. Twice at most, so Google TV can't keep us at it.
        if (pickerOpenedAt != 0 && now - pickerOpenedAt < 6_000 && pickerRetries < 2
                && (pickerShownAt < pickerOpenedAt || now - pickerShownAt < 1_500)) {
            profilesDue = true;
            pickerRetries++;
        }
        handler.postDelayed(settled, profilesDue ? SETTLE_WOKEN_MS : SETTLE_MS);
    }

    /** On Google TV's account picker only: the account chosen, or the one focused that is already in use. */
    private void onAccount(AccessibilityEvent e) {
        int type = e.getEventType();
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_FOCUSED) return;
        if (!PROFILES_CLASS.equals(front) || e.getContentDescription() == null) return;
        String desc = e.getContentDescription().toString();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED && !desc.contains("current account")) return;
        Matcher m = ACCOUNT.matcher(desc);
        if (!m.find()) return;
        String name = m.group(1).trim();
        if (name.equals(account)) return;
        account = name;
        sendBroadcast(new Intent(CornerReceiver.ACCOUNT).setPackage(getPackageName()).putExtra("name", name));
    }

    /** The TV started or woke: Google TV's account picker first, as Google TV does, over whatever comes up. */
    private void onTvOn() {
        if (freeMyTv || !HomeSetup.picker(this) || HomeSetup.home(this).equals(HomeSetup.OFF)) return;
        if (!installed(LAUNCHERX)) return;
        profilesDue = true;
        pickerRetries = 0;
        // Waking may resume an app instead of a home screen: open it over the app once the TV has settled. Over
        // Google TV's home, the window events do it, once that has settled.
        handler.postDelayed(() -> {
            if (!profilesDue || isStockHome(front)) return;
            profilesDue = false;
            openProfiles();
        }, 4_500);
    }

    private void openProfiles() {
        try {
            startActivity(new Intent().setComponent(ComponentName.unflattenFromString(PROFILES))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            pickerOpenedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) {   // not a Google TV, or it won't open
            openHome();
        }
    }

    /** By name: a HOME intent would resolve to the TV's own home screen here. If ours won't open, the TV's own. */
    private void openHome() {
        Class<?> which = HomeSetup.home(this).equals(HomeSetup.VISION) ? VisionActivity.class : CinemaActivity.class;
        try {
            startActivity(new Intent(this, which).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {}
        }
    }

    /** The home screen Android itself would open (Google TV's, Android TV's...), unless that's already ours. */
    private String stockHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo a = r == null ? null : r.activityInfo;
        if (a == null || a.packageName.equals(getPackageName()) || a.packageName.equals("android")) return GOOGLE_HOME;
        return a.targetActivity != null ? a.targetActivity : a.name;   // an alias shows up under its real class
    }

    private boolean isStockHome(String cls) {
        return GOOGLE_HOME.equals(cls) || cls.equals(stockHome);
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether our service is on (HomeSetup turns it on). */
    static boolean on(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && (":" + list + ":").contains(":" + NAME + ":");
    }

    /** Whether Free My TV's key service is on: it handles Home itself (its Home Button can pick ps5UI). */
    static boolean freeMyTvOn(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && (":" + list).contains(":" + FREE_MY_TV);
    }
}
