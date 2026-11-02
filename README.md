# Viby Desktop

> Viby also has an Android version: [vovaaa312/Viby](https://github.com/vovaaa312/Viby).

Desktop version (Windows / macOS / Linux) of Viby — a music player that turns YouTube videos and
playlists into a local library using [yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg, and
plays it with a built-in equalizer.

Download the installer for your system from the [latest release](https://github.com/vovaaa312/VibyDesktop/releases/latest).
No Java or other tools need to be installed: on first launch Viby downloads yt-dlp, FFmpeg and
Deno into its data folder, checks them by SHA-256 and keeps yt-dlp up to date.

## Features

**Downloads**
- Videos, playlists and YouTube Mixes to MP3 or M4A with cover art and tags
- Download queue that survives restarts: pause, resume, cancel, automatic retries and waiting
  for the network
- Drag to reorder: the top job always downloads first; tracks inside a playlist job can be
  reordered too, including above or below the one that is downloading
- Expandable job cards with per-track status, "download first", retry and failure details
- A video already in the playlist asks before adding it again; files are shared between playlists
- Track radio: a playlist of similar tracks built from any song
- Cookies from a browser or a cookies.txt file for private and age-restricted content

**Library and player**
- Playlists with search, sorting (including the original YouTube order), multi-select and a
  context menu: play, add to queue, play next, radio, send to another playlist, delete
- Import of local audio files into any playlist
- Queue panel with drag-and-drop reordering and a reset back to the playlist order; shuffle
  turns off back into the original order; repeat one / all
- Pending tracks play right away as an online stream and jump to the front of the downloads
- Playback position, queue and shuffle order are restored after a restart
- 20-band equalizer with presets, preamp and your own presets
- Lyrics from YouTube Music with a subtitle fallback
- Media keys, volume by mouse wheel

**YouTube account**
- Google sign-in: download your own playlists, private ones included
- Save any playlist to YouTube as a private playlist
- For playlists that have a YouTube original, adding, sending and deleting tracks asks whether to
  change it in Viby only or in Viby and on YouTube

**App**
- Spotify / YouTube Music style interface that adapts to narrow and short windows
- Colour themes: Viby (violet), Spotify (green), YouTube (red)
- Daily update check: the new installer is downloaded, verified by SHA-256 and started from the app

App data lives in `%LOCALAPPDATA%\Viby`, `~/Library/Application Support/Viby` or
`~/.local/share/viby`; music goes to `~/Music/Viby` by default (changeable in the settings).

## Architecture

| Module | Language | Contents |
|--------|----------|----------|
| `core` | Java 21  | yt-dlp / FFmpeg / Deno management, download service and queue, SQLite library, audio engine with the equalizer, lyrics, YouTube account and sync, updates |
| `app`  | Kotlin, Compose Multiplatform | UI and state on top of `core` |

Most of `core` is ported from the Android app, so both versions share the logic and the
database schema.

## Building

Requirements: JDK 21 (for example Temurin 21 or the JBR bundled with Android Studio).

```bash
./gradlew build        # compile and run the tests
./gradlew :app:run     # run the app
./gradlew :app:packageMsi -PpackagingJavaHome=<JDK with jpackage>   # Windows installer
```

Google sign-in needs a Desktop OAuth client: locally a `client_secret*.json` in the project root,
in CI the repository secret `GOOGLE_OAUTH_CLIENT` with the same JSON. Neither is committed;
builds without it simply hide sign-in.

## Releases

Set `version` in `build.gradle.kts` and push a tag `vX.Y.Z`: the `release` workflow builds the
.msi, .dmg and .deb installers and publishes them as a GitHub release. To add installers to an
existing release, run the workflow manually with the tag and the platforms to build.

## Disclaimer

Personal-use project. Downloading content from YouTube may violate YouTube's Terms of Service or
copyright law. Use it only with content you are allowed to download. Not affiliated with YouTube
or Google.
