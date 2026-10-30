# Tested apks: Free My TV (safe mode), then ps5UI 1.0

> **This is the tested way.** It's exactly what runs on the tested TV, an **Acer Google TV, model R4G** (Android 14,
> 2 GB): **Free My TV first, in safe mode, then ps5UI.**

| File | What it is |
|---|---|
| [`FreeMyTV-safe-mode.apk`](FreeMyTV-safe-mode.apk) | Free My TV with a safe mode for the Acer R4G. Install it **first**. |
| [`ps5UI-1.0.apk`](ps5UI-1.0.apk) | ps5UI and visionUI. Install it **second**. |
| [`free-my-tv-safe-mode.patch`](free-my-tv-safe-mode.patch) | The safe mode's changes to Free My TV's source, for anyone who wants to check or rebuild it. |

## Important: why Free My TV runs in safe mode (start loops)

Free My TV normally becomes the TV's home screen by turning Google TV's own home screen off. **On some Google TVs that
stops the TV from starting: it loops at the logo, again and again.** Free My TV's own README reports it on a Kalley
43G200 (it came back only with a USB reflash) and a Huawei TV (only a factory reset), and it now refuses to switch on
those two.

The Acer R4G is the same kind of TV. At every start, Acer's TV Settings app turns about 60 apps back on, Google's
setup app among them, but not Google TV's home. With Google TV's home off, that setup app takes the Home button and
the TV loops at start, and ADB can't stop Acer's start-up job. **The public Free My TV doesn't know about the Acer R4G
yet**: its switch would turn Google TV's home off there.

So the Free My TV here has a **safe mode** for this TV: **Google TV's home and its setup app are
never turned off.** Google TV's home stays the TV's real home underneath, and Free My TV catches the remote's Home
key instead (an accessibility service) and opens ps5UI. Whatever the TV turns back on at start, it still starts.

- **On an Acer R4G: use this Free My TV, never the public one.**
- **On any other TV**, the safe mode doesn't switch on: this Free My TV then switches like the public one. Check
  [Free My TV's README](https://github.com/simbasjungles/free-my-tv) for your TV first, or use the one-app ps5UI 1.1 in
  [Untested apk](../Untested%20apk) (not tried on a TV yet), which never turns anything off.

## Steps

You need a computer on the same Wi-Fi as the TV, with [adb](https://developer.android.com/tools/releases/platform-tools).

1. **On the TV, turn on USB debugging:** Settings > System > About, press OK on **Android TV OS build** seven times,
   then Settings > System > Developer options > **USB debugging** on. Note the TV's IP address (Settings > Network &
   Internet > your Wi-Fi).
2. **Download** [`FreeMyTV-safe-mode.apk`](FreeMyTV-safe-mode.apk) and [`ps5UI-1.0.apk`](ps5UI-1.0.apk) (on each
   file's page, the download button) to the computer, into the folder you run adb from.
3. **Install Free My TV first:**
   ```bash
   adb connect <TV IP>:5555
   adb install -r FreeMyTV-safe-mode.apk
   ```
   On the TV's **Allow USB debugging?**, tick **Always allow** and pick **Allow**. If the install seems stuck, look at
   the TV: Google Play Protect may say *Risky app blocked*; pick *More details* > *Install anyway*.
4. **Turn on Free My TV mode:** on the TV, open Free My TV, then **Home Screen > Free My TV**. Allow USB debugging
   again if the TV asks (this time it's Free My TV itself). It turns off Ambient mode, Play Games and **Google Assistant**,
   to save memory on a 2 GB TV (the TV turns them back on at start, and Free My TV turns them off again), and becomes
   the screensaver. With Assistant off, the remote's mic button doesn't work. Google TV's home stays on.
5. **Then install ps5UI:**
   ```bash
   adb install -r ps5UI-1.0.apk
   ```
6. **Pick it:** in Free My TV, **Home Settings > Home Button > ps5UI** (or visionUI).

That's it. The remote's Home button opens ps5UI, from any app. After a start or on waking, Google TV's own *Choose an
account* screen comes first, then ps5UI. The corner's account name and Free memory work through Free My TV, and
holding Home for a moment opens Free My TV's Control Center.

**Undo:** Free My TV > Home Screen > **Original** puts everything back; then uninstall both in Settings > Apps.

Notes:
- This Free My TV is exactly the one on the tested TV. It's built to turn Google Assistant off, its weather and sunset
  are set for Bengaluru, and its picks for India. To change any of that, rebuild it from source (Free My TV's
  `config.properties`: `disable_assistant=false` keeps Assistant on). Its changes to Free
  My TV are in [`free-my-tv-safe-mode.patch`](free-my-tv-safe-mode.patch), against Free My TV's commit
  `f2d984c`, under Free My TV's MIT licence (see [LICENSE](../LICENSE)).
- Don't also install the untested ps5UI 1.1: it's the same app (signed with another key, so it won't install over
  1.0 anyway). To switch from one to the other, uninstall ps5UI first.
