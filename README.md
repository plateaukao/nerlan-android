# NerLan (Android)

[![Build](https://github.com/plateaukao/nerlan-android/actions/workflows/build.yaml/badge.svg)](https://github.com/plateaukao/nerlan-android/actions/workflows/build.yaml)   [<img src="https://badgen.net/badge/download/snapshot_apk/green">](https://nightly.link/plateaukao/nerlan-android/workflows/build.yaml/main/app-release.apk.zip)

**[⬇ Download the latest snapshot APK](https://nightly.link/plateaukao/nerlan-android/workflows/build.yaml/main/app-release.apk.zip)** — built from `main` on every push, no GitHub login required (served via [nightly.link](https://nightly.link)). Unzip to get `app-release.apk`.

NerLan is an Android language-learning audio player for Taiwan's National Education Radio (國立教育廣播電台) **Channel+** platform. It makes the station's language-learning programs easy to browse, stream, and download for offline study — wrapped in a modern Jetpack Compose UI with Material 3 Expressive styling.

Looking for iOS? There is a matching iOS app: [plateaukao/nerlan](https://github.com/plateaukao/nerlan).

## Features

- **Browse ~96 programs across 19 languages**, with a wrapping FilterChip language filter to narrow the list.
- **Full episode archives** for every program, loaded with infinite scroll.
- **Background playback** via Media3/ExoPlayer, with a media notification and lock-screen controls.
- **Playback speed** from 0.5× to 2×.
- **Repeat modes**: off / repeat all / repeat one.
- **MP3 downloads** for offline listening, grouped by program and language.
- **Favorites** for both episodes and programs.

## Architecture

- **Data layer** — an OkHttp + kotlinx-serialization client for the Channel+ API (`https://channelplus.ner.gov.tw/api/v1`). See `app/src/main/java/com/example/nerlan/data/` (`ChannelPlusApi.kt`, `Models.kt`, `DownloadManager.kt`, `FavoritesStore.kt`).
- **Playback** — a Media3 `MediaSessionService` (`player/PlaybackService.kt`) hosting ExoPlayer, controlled from the UI through a `MediaController` (`player/PlayerManager.kt`). This gives background playback, the media notification, and lock-screen controls for free.
- **UI** — Jetpack Compose with Material 3 Expressive components (`ui/` and `theme/`), a single-activity app (`MainActivity.kt`).

Min SDK 24 · Target SDK 36 · Kotlin · Jetpack Compose

## Building

Debug build:

```bash
./gradlew assembleDebug
```

Release build — provide your own signing keystore via the standard injected signing flags:

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=/path/to/your.keystore \
  -Pandroid.injected.signing.store.password=... \
  -Pandroid.injected.signing.key.alias=... \
  -Pandroid.injected.signing.key.password=...
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

## Disclaimer

This is an **unofficial** client. The Channel+ API it uses is not publicly documented and may change at any time. All audio content and program material belong to 國立教育廣播電台 (National Education Radio, Taiwan). This project is not affiliated with or endorsed by NER.
