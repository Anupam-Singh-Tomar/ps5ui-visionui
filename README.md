# ps5UI & visionUI

Two home screens for Google TV and Android TV: **ps5UI**, in the manner of the PS5's home screen, and **visionUI**,
in the manner of visionOS. One small app, no Google services, no API keys, drawn by hand on a single canvas so it
stays smooth on a 2 GB TV.

## Install

There are two folders. **Use [Tested apks](Tested%20apks).**

| Folder | What's in it | Tested? |
|---|---|---|
| **[Tested apks](Tested%20apks)** | Free My TV (safe mode) **first**, then ps5UI 1.0. Install steps inside. | **Yes**, on an Acer Google TV R4G (Android 14, 2 GB) |
| [Untested apk](Untested%20apk) | ps5UI 1.1: one app, Free My TV's Home button built in. Install steps inside. | **No**, not tried on a TV yet |

### Important: why Free My TV runs in safe mode (start loops)

Free My TV normally becomes the TV's home screen by turning Google TV's own home screen off. **On some Google TVs that
stops the TV from starting: it loops at the logo, again and again.** Free My TV's own README reports it on a Kalley
43G200 (it came back only with a USB reflash) and a Huawei TV (only a factory reset), and it now refuses to switch on
those two.

The Acer R4G is the same kind of TV. At every start, Acer's TV Settings app turns about 60 apps back on, Google's
setup app among them, but not Google TV's home. With Google TV's home off, that setup app takes the Home button and
the TV loops at start, and ADB can't stop Acer's start-up job. **The public Free My TV doesn't know about the Acer R4G
yet**: its switch would turn Google TV's home off there.

So the Free My TV in [Tested apks](Tested%20apks) has a **safe mode** for this TV: **Google TV's home and its setup app are
never turned off.** Google TV's home stays the TV's real home underneath, and Free My TV catches the remote's Home
key instead (an accessibility service) and opens ps5UI. Whatever the TV turns back on at start, it still starts.

- **On an Acer R4G: use this Free My TV, never the public one.**
- **On any other TV**, the safe mode doesn't switch on: this Free My TV then switches like the public one. Check
  [Free My TV's README](https://github.com/simbasjungles/free-my-tv) for your TV first, or use the one-app ps5UI 1.1 in
  [Untested apk](Untested%20apk) (not tried on a TV yet), which never turns anything off.

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

## Building it yourself

You need a JDK 17+ and the Android SDK (`build-tools;34.0.0`, `platforms;android-34`). No Gradle.

```bash
./build.sh
adb install -r out/ps5UI.apk
```

`build.sh` makes `out/ps5UI.apk`, signed with a key of its own (`ps5ui.keystore`, made on the first build). Keep it:
updates must be signed with the same key. (An APK you build won't install over the ones here, which have other keys:
uninstall ps5UI first.)

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
