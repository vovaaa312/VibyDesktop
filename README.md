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

## Roadmap

- [x] Stage 0 — project skeleton, CI
- [x] Stage 1 — yt-dlp engine: binary manager, downloads to MP3 with cover and tags, cookies
- [ ] Stage 2 — SQLite library (same schema as Android)
- [ ] Stage 3 — persistent download queue (pause, cancel, reorder, retry, placeholders)
- [ ] Stage 4 — playback engine, 20-band equalizer, queue, online streaming of pending tracks
- [ ] Stage 5 — Compose UI
- [ ] Stage 6 — lyrics (YouTube Music + subtitles)
- [ ] Stage 7 — YouTube account sync, Track radio
- [ ] Stage 8 — installers (msi / dmg / deb), app updates

## Building

Requirements: JDK 21 (for example the JBR bundled with Android Studio or Temurin 21).

```bash
./gradlew build        # compile + tests
./gradlew :app:run     # run the app
./gradlew :app:packageDistributionForCurrentOS   # installer (needs a JDK with jpackage)
```

## Disclaimer

Personal-use project. Downloading content from YouTube may violate YouTube's Terms of Service or
copyright law. Use it only with content you are authorized to download. Not affiliated with
YouTube or Google.
