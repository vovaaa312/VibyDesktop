# Viby Desktop

Cross-platform (Windows / macOS / Linux) desktop version of [Viby](https://github.com/vovaaa312/Viby) —
a music player that turns YouTube videos and playlists into a local MP3 library using
[yt-dlp](https://github.com/yt-dlp/yt-dlp) and FFmpeg.

> **Status:** early development. See the roadmap below.

## Architecture

| Module | Language | Contents |
|--------|----------|----------|
| `core` | Java 21  | yt-dlp/ffmpeg/deno binary management, downloads and queue, SQLite library, audio engine + 20-band EQ, lyrics, YouTube account sync, update checks |
| `app`  | Kotlin, Compose Desktop | UI and thin view models over `core` |

Most `core` classes are ported from the Android app, so both versions share the same logic and
database schema.

External tools are downloaded on first launch into the app data directory
(`%LOCALAPPDATA%\Viby`, `~/Library/Application Support/Viby`, `~/.local/share/viby`):
yt-dlp (auto-updated daily), FFmpeg and Deno (JavaScript runtime required by yt-dlp for YouTube).

## Features (1.0)

- Download YouTube videos, playlists and Mixes to MP3/M4A with cover art and tags (yt-dlp + FFmpeg)
- Persistent download queue: pause, cancel, retries, resume after restart, shared files across playlists
- Library of playlists in SQLite (same schema as the Android app), search, sorting, multi-select
- Player with queue, shuffle/repeat, online streaming of tracks that are still downloading
- 20-band equalizer with presets, preamp and user presets
- Lyrics from YouTube Music with subtitle fallback
- Google sign-in: your YouTube playlists (private ones too), save playlists to YouTube, two-way sync
- Track radio, cookies from a browser or cookies.txt for age-restricted/private content
- Media keys, middle-click autoscroll, daily update check against GitHub releases

## Roadmap

- Expandable download queue (per-track reordering) — 1.0.1
- System media controls: Windows SMTC, macOS Now Playing, Linux MPRIS
- Local file import, drag & drop links, library export/import with Android

## Building

Requirements: JDK 21 (for example the JBR bundled with Android Studio or Temurin 21).

```bash
./gradlew build        # compile + tests
./gradlew :app:run     # run the app
./gradlew :app:packageMsi -PpackagingJavaHome=<JDK with jpackage>   # installer
```

### Releases

Push a tag `vX.Y.Z` (matching `version` in `build.gradle.kts`); the `release` workflow builds the
.msi, .dmg and .deb installers and publishes them. Installed apps check the latest release once a day.

Google sign-in needs a Desktop OAuth client: locally a `client_secret*.json` in the project root, in CI
the repository secret `GOOGLE_OAUTH_CLIENT` with the same JSON. Neither is committed.

## Disclaimer

Personal-use project. Downloading content from YouTube may violate YouTube's Terms of Service or
copyright law. Use it only with content you are authorized to download. Not affiliated with
YouTube or Google.
