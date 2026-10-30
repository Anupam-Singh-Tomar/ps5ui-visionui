# ps5UI & visionUI

Two home screens for Google TV and Android TV: **ps5UI**, in the manner of the PS5's home screen, and **visionUI**,
in the manner of visionOS. One small app, no Google services, no API keys, drawn by hand on a single canvas so it
stays smooth on a 2 GB TV.

## Quick install (the tested way)

> **Heads-up: version 1.1 is untested.** The home screens themselves (ps5UI and visionUI) were tested on an Acer
> Google TV (Android 14, 2 GB). The Home button, the account picker on waking and Free memory are new in 1.1, built
> in from Free My TV, and haven't been tried on a TV yet. If something goes wrong, uninstall ps5UI and the TV is as
> it was: 1.1 never turns anything on the TV off.

What was tested: installing with adb from a computer, and opening ps5UI from the TV's apps.

1. On the TV, turn on USB debugging: Settings > System > About, press OK on **Android TV OS build** seven times,
   then Settings > System > Developer options > **USB debugging** on. Note the TV's IP address (Settings > Network).
2. Download **[ps5UI.apk](ps5UI.apk)** and [adb](https://developer.android.com/tools/releases/platform-tools) to a
   computer on the same Wi-Fi as the TV, and run:
   ```bash
   adb connect <TV IP>:5555
   adb install -r ps5UI.apk
   ```
   The first time, the TV asks **Allow USB debugging?**: tick **Always allow** and pick **Allow**, then run the
   install again if it stopped.
3. On the TV, open **ps5UI** or **visionUI** from your apps.

Then (untested, new in 1.1): ps5UI opens **Home button** the first time. Pick ps5UI or visionUI and accept **Allow
USB debugging?** on the TV, and the remote's Home button opens it from any app. More in [Install](#install).

![ps5UI: the next race leads the row, its track behind it](screenshots/ps5ui-home.jpg)

## ps5UI

| | |
|---|---|
| ![A new show focused, its art filling the screen](screenshots/ps5ui-show.jpg) | ![Down on your apps: the app's newest show drifts slowly behind](screenshots/ps5ui-apps.jpg) |
| New episodes on your apps, the focused one's art filling the screen | Your apps and inputs, with the app's newest show drifting behind |
| ![The account corner open by the clock](screenshots/ps5ui-corner.jpg) | |
| The account corner: Switch account, Free memory, Home settings, TV settings | |

- **The top row:** this week's new episodes on Netflix, Prime Video, JioHotstar, Apple TV and the rest (from
  [TVmaze](https://www.tvmaze.com/api)). The focused one is bigger and named beside it, with its details and an Open
  button over its art.
- **F1 and MotoGP:** race weekends in the next two weeks lead the row. It opens on the next race; later ones peek in
  from the left. Each race shows its time in your time zone, the whole weekend's sessions, and a real photo of the
  track from Wikimedia Commons, credited on screen.
- **Apps and inputs:** press Down. Your apps (hold OK to move one) end with All apps (hold OK to add or remove). The
  inputs row has Live TV, HDMI with a dot when a device is connected, and TV Settings. Behind them, the focused app's
  newest show (or its colours, as soft moving light) drifts slowly. The focused tile gets a sheen.
- **The account corner:** press Up from the top row to reach the picture by the clock.
- **Sounds:** soft UI clicks, made from scratch by [`tools/make-sounds.py`](tools/make-sounds.py). They follow the
  TV's System sounds setting.
- **Built for a slow TV:** each backdrop is pre-blended with its shading into one picture in the background, tiles
  are drawn as rounded shapes instead of clipped, and along a row the backdrop follows a beat after focus, as on the
  PS5. On the 2 GB test TV, frames take 11–17 ms, with 0–5% slow frames.

## visionUI

| | |
|---|---|
| ![visionUI: a glass window over the trending show](screenshots/visionui-home.jpg) | ![The apps float up as the window steps back](screenshots/visionui-apps.jpg) |
| A frosted glass window over the trending show | Down past the window: it steps back and your apps float up |

- A glass window with a sidebar, service filters (All, Netflix, Prime Video, JioHotstar, Apple TV), Top rated, Your
  apps, a Trending now hero that turns every few seconds, and You might like.
- The apps view: every app floats up as a round icon, one after another, then they bob gently. Your apps come first.

## Install

1. Get [`ps5UI.apk`](ps5UI.apk) onto the TV, either way:
   - **From a computer (tested):** as in [Quick install](#quick-install-the-tested-way).
   - **On the TV (not tested):** install the Downloader app (by AFTVnews) from the Play Store, allow it to install
     unknown apps when it asks, and open this address in it:
     `https://github.com/Anupam-Singh-Tomar/ps5ui-visionui/raw/main/ps5UI.apk`
2. Open **ps5UI** (or **visionUI**) from the TV's apps. The first time, **Home button** opens: pick ps5UI or visionUI.
3. The TV asks **Allow USB debugging?**: tick **Always allow** and pick **Allow**. That's it: the remote's Home button
   now opens the one you picked, from any app.

Android 8 or newer. Tested on an Acer Google TV (Android 14, 2 GB). Updates: install the new `ps5UI.apk` over the old
one; your app order is kept.

### The Home button (new in 1.1, untested)

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

### Building it yourself

You need a JDK 17+ and the Android SDK (`build-tools;34.0.0`, `platforms;android-34`). No Gradle.

```bash
./build.sh
adb install -r out/ps5UI.apk
```

`build.sh` makes `out/ps5UI.apk`, signed with a key of its own (`ps5ui.keystore`, made on the first build). Keep it:
updates must be signed with the same key. (An APK you build won't install over the one here, which has another key:
uninstall that one first.)

## Data

No keys and no accounts. Everything is cached on the TV.

- [TVmaze](https://www.tvmaze.com/api): new episodes, show details and art.
- [Jolpica F1](https://github.com/jolpica/jolpica-f1): the F1 calendar.
- MotoGP's public results API: the MotoGP calendar.
- [Wikimedia Commons](https://commons.wikimedia.org): track photos, each shown with its author and licence.

## Credits

- **[Free My TV](https://github.com/simbasjungles/free-my-tv)** ([freemytv.io](https://freemytv.io)), MIT licensed,
  by the Free My TV contributors. ps5UI's Home button, account picker and Free memory are Free My TV's safe mode,
  built in: its ADB client ([`Adb.java`](src/io/freemytv/cinema/Adb.java)) and its way of handling Google TV's
  home screen. It also shaped how this app is built: one hand-drawn canvas, no Gradle, no libraries. The package
  name (`io.freemytv.cinema`) keeps that link.
- Show data and art: TVmaze and the shows' owners. Track photos: their authors on Wikimedia Commons, under the
  licence shown with each.

Not affiliated with Sony Interactive Entertainment, Apple, Google, or any streaming service. PlayStation, PS5 and
visionOS are trademarks of their owners. ps5UI and visionUI only borrow a style.

## License

[MIT](LICENSE)
