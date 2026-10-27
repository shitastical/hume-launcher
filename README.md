# Hume Launcher

A tvOS 26–style home screen for Android TV, by Petrobot. It replaces the stock launcher with one
modelled on Apple TV — glass dock, full-screen carousel, Control Center, profiles — and is built to
stay smooth on low-end TV boxes like the Xiaomi Mi Box. Plain Java, no Gradle, no third-party
runtime libraries; the APK is about 1 MB.

This is the **US fork** of [petrobot-admin/hume-launcher](https://github.com/petrobot-admin/hume-launcher):
news, streaming services, search and locale are American rather than Indian, and the carousel has
been reworked around sharp artwork that never disappears. What changed, in detail, is
[below](#what-this-fork-changes).

![Home screen](docs/images/home-dock-glass.jpg)

## Features

- **Home**: Liquid Glass dock, 6-across app grid in Apple's measured proportions, focus lift and
  zoom-to-open, More Apps folder, five backgrounds.
- **Carousel**: Continue Watching first (Resume / Remove, expires after 7 days), then US news
  (national, your state, world) and rotating recommendations from your streaming services —
  JustWatch, TMDB, Stremio, or your own Jellyfin library, whose half-watched titles resume in
  Wholphin and can be removed from Continue Watching from the card. Goes
  full screen when Home is idle. Story artwork is shown at full dock width — the sharpest copy each
  publisher offers, never a feed thumbnail — and the shelf keeps its stories up while it refetches.
- **Status row**: internet indicator, time, Refresh, Search, Control Center, profile avatar.
- **Search**: apps, settings and inputs instantly, plus movies and shows on every streaming service
  in the US (typed in English, Spanish or Hindi, or spoken) with **Open** in the app
  you have or **Get** for the one you don't.
- **Weather**: a glass pill with a 5-day forecast panel (Open-Meteo, MET Norway or wttr.in — no keys).
- **Control Center**: Power Off, Memory/Clean, Wi-Fi, Do Not Disturb, audio, sleep timer, inputs,
  settings, search.
- **Profiles**: up to 3, each with its own layout, background and carousel; full-screen switcher.
- **Inputs**: HDMI/AV/TV picker with connector icons, on TVs that have inputs.
- **Presentation mode**: send images, videos and webpages from a browser and loop them full screen;
  can start with the TV (signage).
- **First-time setup** in Apple's style, **multi-page Settings**, permissions asked **on screen**
  (adb only as a last resort), an **Aerial Views screensaver** that Settings can switch on in one
  press, and Android TV navigation sounds.
- **AirPlay**: works with the open-source [AirPlay Server](https://github.com/jqssun/android-airplay-server)
  app; Hume keeps the receiver visible to Macs and iPhones after the TV sleeps.

Full list with screenshots: **[docs/features.md](docs/features.md)**.

## What this fork changes

Upstream ships aimed at India. This fork retargets everything to the US and fixes the carousel, in
six releases (7.7 → 8.2). Nothing here is a setting: these are the defaults, and every profile
gets them.

### 7.7 — The US instead of India

- **News**: Indian newsroom RSS feeds are replaced by US ones — NPR, ABC News, NBC News, The
  Guardian US, BBC US, Politico and (optional) The Hill, each with a national and a world section.
  Local coverage moved to US metros (10TV Columbus, WKYC Cleveland, Fox 8, WDTN Dayton, WKBN,
  WYTV Youngstown, cleveland.com, NBC4, Signal Cleveland, Toledo Blade, Ohio Capital Journal).
- **Lost stories fixed**: NPR and WordPress-based newsrooms put their picture in
  `<content:encoded>`, which the parser never read, so fresh stories from those feeds were dropped
  entirely. It reads it now — which also fixes WordPress feeds generally — alongside `media:content`,
  `media:thumbnail` and `enclosure`.
- **Recommendations**: "popular in India" becomes "popular in the US", over the services Americans
  have: Netflix, Prime Video, Disney+, Hulu, HBO Max, Apple TV, Peacock, Paramount+.
- **Service identifiers corrected**: JustWatch's package names were read off its own offer data
  rather than guessed — in the US, Prime Video is `amazonprime`/`amp`, not `amazonprimevideo`/`prv`
  — so **Open** now lands in the right app. Saved provider choices are filtered against the current
  list, so a profile created before the change cannot end up with an empty carousel.
- **Search, speech and language filter** follow: JustWatch and Cinemeta queries use the US region,
  speech input asks for `en-US` (with `es-US`), and the original-language filter offers English,
  Spanish, French, German, Japanese, Korean, Hindi and Portuguese instead of Indian languages only.
- **Setup** no longer cycles Indian-language greetings.
- **Build**: `build.sh` picks the newest installed build-tools, because the `d8` in 34.0.0
  (R8 8.2.2) crashes on JDK 21 class files; `BT_VERSION` still pins one.

### 7.8 — Local news for all 50 states and DC

- Local news used to depend on a hand-written city table, so most of the country saw national
  stories only. It now derives the state from the weather location — the city you picked, or the
  one found from your internet address — and offers statewide coverage plus the state's biggest
  newsrooms for **all 50 states and DC**: nonprofit statehouse outlets (Ohio Capital Journal,
  States Newsroom's state bureaus), local TV and the large papers. **120 feeds** in all.
- A city with newsrooms of its own still wins over the statewide list.
- **Settings → Top Shelf → Local news state** overrides the state (or follows the location again),
  and **Local newsrooms** shows how many that state brings. Moving the weather location rebuilds
  the shelf.

### 7.9 — Sharp pictures, and a shelf that never blanks

- **Sharp artwork.** Feeds list whatever size their CMS generated and usually list the smallest
  copy first: ABC's first image is 384 px wide, the BBC's 240, and a 1080p dock turns either into
  mush. Every picture a story offers is now kept (with the width its markup or URL claims), and
  `Art.java` asks each publisher's CDN for a bigger rendition where it allows one — WordPress and
  Nexstar `?w=`, TEGNA `_640x360`/`-preview`, BBC `/240/` → `/1536/`, ABC `_384` → `_1600`, NBC's
  `t_nbcnews-fp-*`, NPR's resizer — downloads the candidates one at a time and **measures** them,
  then shows the first copy at least **1280 px** wide. A feed that only has thumbnails falls back
  to the story page's own share image (`og:image` / `twitter:image`). Signed URLs (The Guardian,
  signed S3) are fetched exactly as published, because changing the size breaks the signature.
  Answers are memoised in `art_v2.json`, so a story is resolved once and costs nothing later.
- **Feeds read in parallel, under one deadline.** All feeds (national, world, state, city) are
  fetched at once with a 14-second cap for the whole pass, so one slow or dead newsroom costs its
  own stories instead of leaving the shelf empty. The log line says how it went:
  `news: 14/14 feeds, 12 stories`.
- **The shelf never blanks.** A settings change no longer deletes the cached carousel: the stories
  already on screen stay up while the new ones are fetched. An empty or failed pass keeps what is
  showing and retries after 30 seconds rather than caching an empty shelf.
- **Newsrooms apply where you tick them.** The dialog is now a list of checkboxes with a single
  **Done** (no Cancel that silently discards the selection), each tick saves immediately, the
  carousel refreshes behind the dialog, and at least one newsroom always stays on.
- **Docs** updated throughout: `docs/features.md` (carousel, newsrooms, local states),
  `docs/architecture.md` (the new `Art.java`, and why widths are measured rather than trusted).

### 8.0 — Aerial Views instead of a built-in screensaver

- Hume's own screensaver is gone. It could only ever be the launcher holding the screen awake and
  playing its carousel, which is worse at the job than a real video screensaver and blocked the
  TV's own from starting.
- The screensaver is now **[Aerial Views](https://github.com/theothernt/AerialViews)** (free, open
  source, 4K): Settings → Home → **Screensaver** makes it the TV's screensaver, **Start screensaver
  now** begins it, and **Aerial Views settings** opens its video sources, overlays and playlist.
  Setting it writes the TV's `screensaver_components`, so the launcher asks for
  `WRITE_SECURE_SETTINGS` once — the same on-screen/adb-last-resort path as every other permission
  no TV has a switch for.
- Home no longer holds the screen on, at all, so whichever screensaver the TV is set to starts when
  it should.

### 8.1 — Your own library, and it knows where you stopped

- **Jellyfin as a carousel source.** Settings → Top Shelf → Source → **Jellyfin**: enter the
  server's address, username and password once. The launcher signs in (`/Users/AuthenticateByName`),
  keeps the token, and asks for your account's **Continue Watching**
  (`/Users/{id}/Items/Resume`) — artwork included, at carousel size. A show's card is its backdrop
  and logo, with the episode in the detail line.
- **▶ Resume, not Play.** A half-watched film or episode says **Resume** and how much is left;
  only titles you have not started say **Play**. Pressing it starts the item in
  [Wholphin](https://github.com/damontecres/Wholphin) (installed on the TV) through its
  `wholphin://play` deep link, and Wholphin resumes from the position the server holds.
- **✕ Remove works on library titles.** Right moves the highlight from Resume to Remove, and
  Remove clears the saved position on the server (`PlaybackPositionTicks: 0` — what Jellyfin's own
  *Remove from Continue Watching* does), drops the card from the shelf and from the cached shelf,
  and the title leaves Continue Watching everywhere. Holding Menu on the card offers **Resume** and
  **Remove from Continue Watching**, exactly as it does for another app's entry.
- **Progress survives a restart.** The cached carousel now stores each card's position as well, so
  the frame after a cold start still says Resume instead of falling back to Play while the new
  shelf is being fetched.
- **Plain-http servers work.** `usesCleartextTraffic` is on, so the usual `http://box:8096` on a
  home network is reachable; there is still no web view anywhere in the app.

### 8.2 — Resume opens the video itself, and the picker lists the video apps

- **Resume opens the video, not just the app.** Hume already had Resume — the button has been there
  since 8.1 — but all it could do was launch the app: a card knew the package and the title, never
  *which video*, so SmartTube opened on its own home screen and nothing resumed. Apps do publish
  that id in the media session they expose while playing (measured on the TV: SmartTube sends the
  YouTube video id as `METADATA_KEY_MEDIA_ID`), so the store keeps it alongside the title and
  **▶ Resume** deep-links to `youtube.com/watch?v=<id>` scoped to that app — SmartTube opens that
  video and continues from its own saved position. Apps that publish no id behave exactly as
  before: their launcher opens. (Passing `&t=` was tried and dropped: it makes the link resolve to
  a chooser instead of the app.)
- **Pick the apps on the carousel.** Settings → Continue Watching → **Apps** lists **every app
  Continue Watching can work with** — the streaming services titles open in, SmartTube, and the
  players it knows (YouTube, Plex, Jellyfin, Kodi) — with **Shown** / **Hidden** per
  app. Not the whole system: a file manager, a downloader or a settings app has nothing to resume.
  An app outside that list appears as soon as it has played something, so a title can never hide
  itself. Hiding takes that app's titles off Continue Watching without deleting the history, so
  showing it again brings them back as they were; *Show every app again* clears all the hiding at
  once.
- **SmartTube is one app.** Its stable and beta builds are one row, called **SmartTube** (no
  "beta" in the picker or on the card), and a title opens in whichever of the two has the higher
  version code — hide it once and both channels are hidden.

## Releases

| Version | What it is |
|---|---|
| **8.2** | **Resume opens the actual video instead of only launching the app** (SmartTube and the YouTube apps); Continue Watching → Apps lists the video apps it can resume — streaming services, SmartTube, YouTube, Plex, Jellyfin, Kodi — with Show/Hidden; SmartTube stable and beta as one app |
| 8.1 | Your own Jellyfin library on the shelf: Resume with time left, Remove from Continue Watching, long-press menu, progress kept with the cache |
| 8.0 | Aerial Views as the screensaver; no launcher screensaver, and Home never holds the screen on |
| 7.9 | Sharp news artwork (≥1280 px, `og:image` fallback), parallel feeds with a deadline, a shelf that keeps its stories, newsroom ticks that apply immediately |
| 7.8 | Local news for all 50 states and DC, driven by the weather location, with a state override |
| 7.7 | US news, US streaming services, US search region and locale instead of India |
| 7.6 | Search across apps and every streaming service, Stremio recommendations (upstream) |

## Quick start

```sh
# needs JDK 17+ and Android SDK build-tools 34.0.0 + platform android-34 (see getting started)
VERSION_CODE=108 VERSION_NAME=8.1 ./build.sh   # → build/tvos-home.apk
adb connect <tv-ip>:5555
adb install -r build/tvos-home.apk
adb shell am start -n com.petrobot.hume/.MainActivity
```

## Documentation

| | |
|---|---|
| **[Getting started](docs/getting-started.md)** | Install the tools, build, run on a TV or the emulator, developer shortcuts |
| **[Features](docs/features.md)** | Everything Hume does, by area |
| **[Install and set up](docs/install.md)** | Make it the home screen, permissions, undo |
| **[Presentation mode](docs/presentation.md)** | Sending content from a browser, the player, the web API |
| **[Architecture](docs/architecture.md)** | Code map, build, design decisions, performance |
| **[Contributing](CONTRIBUTING.md)** | Workflow, code style, design and performance rules, testing, pull requests |

## Requirements

Android TV 7.0+ (API 24). Developed and tested on a Xiaomi Mi Box 4 (Android 12) and an Android 9
TV with 1 GB RAM.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Credits

Inter font (SIL OFL) · Nayuki QR Code generator (MIT) · weather from Open-Meteo, MET Norway and
wttr.in · recommendations from JustWatch / TMDB / Stremio Cinemeta, and from your own Jellyfin
server when you connect one (played by [Wholphin](https://github.com/damontecres/Wholphin)) · news
from US newsrooms' public RSS feeds (national, world, and every state and DC).
