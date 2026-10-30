# Untested apk: ps5UI 1.1, one app

> **Untested: not tried on a TV yet.** For the tested way, use [Tested apks](../Tested%20apks) instead.
>
> ps5UI 1.1 has Free My TV's safe mode built in (the Home button, the account picker and
> Free memory), so it's one app and nothing else to install, and it never turns anything on the TV off. Don't use
> it together with the tested way: with Free My TV's Home button on, ps5UI 1.1 leaves Home to Free My TV.

## Steps

1. Get [`ps5UI-1.1.apk`](ps5UI-1.1.apk) onto the TV, either way:
   - **From a computer:** USB debugging on (as in the [tested way](../Tested%20apks#steps)), then
     `adb connect <TV IP>:5555` and `adb install -r ps5UI-1.1.apk`.
   - **On the TV:** install the Downloader app (by AFTVnews) from the Play Store, allow it to install
     unknown apps when it asks, and open this address in it:
     `https://github.com/Anupam-Singh-Tomar/ps5ui-visionui/raw/main/Untested%20apk/ps5UI-1.1.apk`
2. Open **ps5UI** (or **visionUI**) from the TV's apps. The first time, **Home button** opens: pick ps5UI or visionUI.
3. The TV asks **Allow USB debugging?**: tick **Always allow** and pick **Allow**. That's it: the remote's Home button
   now opens the one you picked, from any app.

Android 8 or newer. Updates: install the new one over the old one; your app order is kept.

## How 1.1's Home button works

Google TV doesn't let another app be its home screen without turning Google TV's own off, and on some TVs (the Acer
and Kalley ones, at least) that makes the TV get stuck at start. So ps5UI does it the safe way, as Free My TV's safe
mode does: **nothing on the TV is turned off**. Google TV's home stays underneath, and ps5UI's Home button (an
accessibility service, *ps5UI Home button*) opens ps5UI instead:

- **Home** opens ps5UI or visionUI, from any app.
- When Google TV's home comes up anyway (after a start, on waking, backing out of an app), ps5UI opens over it. You
  may see Google TV's home for a moment first.
- When the TV starts or wakes, Google TV's own **Choose an account** screen comes first (turn it off in Home
  settings). On that screen only, ps5UI keeps the name on the account you pick, never the email, for the corner.
  Google doesn't let other apps read account photos, so the corner shows an initial.
- **Free memory** (the corner) closes the apps Android keeps waiting in the background, through the TV's own ADB, and
  shows how much it freed.

To change it later: ps5UI's corner (Up from the top row, by the clock) > **Home settings**. Pick **Google TV** to
give Home back to Google TV. To remove everything, uninstall ps5UI.

**Turning on USB debugging** (needed once, for the Home button and Free memory): Settings > System > About, press OK
on **Android TV OS build** seven times; then Settings > System > Developer options > **USB debugging**. If you'd rather
not, turn on *ps5UI Home button* in Settings > System > Accessibility instead (Free memory then won't work).

Already use Free My TV? Then it handles the Home key itself and ps5UI stays out of its way: pick ps5UI in Free My TV >
Home Settings > Home Button.
