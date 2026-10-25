package io.freemytv.cinema;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Inputs row: Live TV, each HDMI port (the name you gave it, and whether a device is sending a signal), the TV's
 * own input picker and its settings. The same way Free My TV finds them (its HomeApps), in brief.
 */
final class Inputs {
    private Inputs() {}

    static final int LIVE = 0, HDMI = 1, PICKER = 2, SETTINGS = 3;

    static final class Input {
        int kind;
        String label, detail;
        boolean connected;
        Intent intent;
    }

    static List<Input> load(Context c) {
        List<Input> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> hdmi = new ArrayList<>();
        String tuner = null;
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) hdmi.add(info);
                    // The maker's hardware tuner ("…/HW<id>") before any app's
                    if (info.getType() == TvInputInfo.TYPE_TUNER && (tuner == null || info.getId().contains("/HW"))) {
                        tuner = info.getId();
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        if (tuner != null) {
            Intent live = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (live.resolveActivity(pm) != null) out.add(input(LIVE, "Live TV", "Channels", true, live));
        }
        Collections.sort(hdmi, (a, b) -> a.getId().compareTo(b.getId()));
        Pattern digit = Pattern.compile("(\\d)");
        for (int i = 0; i < hdmi.size(); i++) {
            TvInputInfo info = hdmi.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {}
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {}
            boolean on = state == TvInputManager.INPUT_STATE_CONNECTED;
            String label = custom != null && custom.length() > 0 ? custom.toString() : "HDMI " + number;
            Intent view = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
            out.add(input(HDMI, label, on ? "Connected" : "No signal", on, view));
        }
        Intent picker = new Intent("com.android.tv.action.VIEW_INPUTS");
        if (picker.resolveActivity(pm) != null) out.add(input(PICKER, "All inputs", "The TV's list", true, picker));
        out.add(input(SETTINGS, "TV Settings", "Picture, sound", true, new Intent(Settings.ACTION_SETTINGS)));
        return out;
    }

    private static Input input(int kind, String label, String detail, boolean connected, Intent intent) {
        Input in = new Input();
        in.kind = kind;
        in.label = label;
        in.detail = detail;
        in.connected = connected;
        in.intent = intent;
        return in;
    }
}
