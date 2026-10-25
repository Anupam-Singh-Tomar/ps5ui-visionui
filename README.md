# ps5UI & visionUI

Two home screens for Google TV and Android TV: **ps5UI**, in the manner of the PS5's home screen, and **visionUI**,
in the manner of visionOS. One small app, no Google services, no API keys, drawn by hand on a single canvas so it
stays smooth on a 2 GB TV.

Built as a companion to **[Free My TV](https://github.com/simbasjungles/free-my-tv)**, which frees a Google TV from
its stock home screen. See [Credits](#credits).

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

You need a JDK 17+, the Android SDK (`build-tools;34.0.0`, `platforms;android-34`) and adb.

```bash
./build.sh
adb connect <TV IP>:5555
adb install -r out/ps5UI.apk
```

`build.sh` makes `out/ps5UI.apk`, signed with a key of its own (`ps5ui.keystore`, made on the first build). Keep it:
updates must be signed with the same key.

ps5UI and visionUI show up in the TV's apps list and as home screens. To make the remote's Home button open one on
a Google TV, use Free My TV (Home Settings > Home Button). Android 8 or newer is needed. It was tested on an Acer
Google TV (Android 14, 2 GB).

### With Free My TV

Free My TV adds two things to ps5UI's account corner:

- **The account name:** when someone picks an account on Google TV's "Choose an account" screen, Free My TV passes
  ps5UI the name on the button they chose (never the email). Google doesn't let other apps read account photos, so
  the corner shows an initial. Without Free My TV it shows a plain person.
- **Free memory:** Free My TV closes the apps Android keeps waiting in the background, through the TV's own ADB, and
  ps5UI shows how much it freed.

## Data

No keys and no accounts. Everything is cached on the TV.

- [TVmaze](https://www.tvmaze.com/api): new episodes, show details and art.
- [Jolpica F1](https://github.com/jolpica/jolpica-f1): the F1 calendar.
- MotoGP's public results API: the MotoGP calendar.
- [Wikimedia Commons](https://commons.wikimedia.org): track photos, each shown with its author and licence.

## Credits

- **[Free My TV](https://github.com/simbasjungles/free-my-tv)** ([freemytv.io](https://freemytv.io)), MIT licensed,
  by the Free My TV contributors. Its Home button handling, account picker and Free Memory are what ps5UI's corner
  and Home key rely on. It also shaped how this app is built: one hand-drawn canvas, no Gradle, no libraries.
  The package name (`io.freemytv.cinema`) keeps that link.
- Show data and art: TVmaze and the shows' owners. Track photos: their authors on Wikimedia Commons, under the
  licence shown with each.

Not affiliated with Sony Interactive Entertainment, Apple, Google, or any streaming service. PlayStation, PS5 and
visionOS are trademarks of their owners. ps5UI and visionUI only borrow a style.

## License

[MIT](LICENSE)
