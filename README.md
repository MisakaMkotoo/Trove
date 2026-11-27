<h1 align="center">Trove</h1>

<p align="center"><b>A third-party NetEase Cloud Music client and local player for Android</b><br />Online streaming, local library, desktop library sync — in one app.</p>

<p align="center">
<img src="https://img.shields.io/badge/license-GPLv3-orange" alt="License: GPL-3.0" />
<img src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+" />
<img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF" alt="Kotlin + Compose" />
<img src="https://img.shields.io/badge/version-1.2.0--beta-blue" alt="Version 1.2.0-beta" />
</p>

<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a></p>

> [!IMPORTANT]
> Trove is an unofficial, independently maintained project. It is not affiliated with,
> endorsed by, or connected to NetEase Cloud Music or Apple. Online features rely on
> third-party endpoints and may break at any time; please support the official services.
> The app has no ads and no analytics: apart from the NetEase endpoints themselves it
> uploads nothing, and your login credentials stay on the device.

## Features

### NetEase Cloud Music

- Sign in with a QR code, phone number + password, or SMS code. Requests use the official eapi fingerprint, with an optional web-compatible mode.
- Home: daily recommendations, Private Radar, Heartbeat mode, and recommended playlists.
- Discover: playlist square, new albums, artist chart, and recommended playlists.
- Search hot words and search suggestions.
- Playlists: import by link, create and edit, favourite, reorder, and privacy settings.
- Cloud disk and listening footprints (cloud playback records).
- Artist pages with paged songs and albums, saved albums, similar songs, and comments (hot / newest).
- "Like" is separate from local favourites; liked online tracks live in their own "Liked Songs" playlist.
- Online playback: trial clips play with a hint; failed stream resolutions are classified and retried a bounded number of times; in-process FFmpeg streaming with a local stream cache that pauses on network loss.
- Downloads: multiple quality tiers, custom folder, notification progress, and optional Wi-Fi playlist auto-cache.

### Local library

- Scan device audio and browse or search by track, artist, album, genre, composer, folder, and playlist.
- Recently added, duplicate cleanup, and hiding tracks under 40 seconds.
- Smart playlists built from rules on title, artist, album, duration, format, play count, favourite, and folder.
- Custom playlist covers and artist pictures; album art from embedded files.
- M3U playlist import and export, plus NCM file decoding and import.
- Batch multi-select; "Mine" and "Library" can be merged, and bottom tabs can be shown, hidden, or reordered.

### Playback engine

- Custom FFmpeg + AAudio JNI core: FLAC, MP3, M4A/AAC, OGG, Opus, WAV, AIFF, APE, WavPack, DSF/DFF, WMA, and more.
- Gapless playback and crossfade up to 12 seconds, with blended artwork across the transition.
- WSOLA speed control from 0.5× to 2.0× without pitch change, A-B loop, and a sleep timer (duration or end of track).
- 10-band equalizer with built-in presets and user profiles.
- BS.1770 LUFS dynamic volume normalisation: track / album modes, adjustable target loudness, and clip protection.
- Queue with play next, add to queue, drag reorder, and remove; shuffle and repeat.
- Playback controls: notification and media session, home-screen widget, Quick Settings tile, and system output switching.

### Lyrics

- Word-level (yrc) karaoke, translations, romanisation, duet lines split by singer, and interlude dots.
- Sources tried in order: NetEase → AMLL → LRCLIB → Kugou, with configurable order and toggles, per-track memory, and manual switching.
- When word timings are missing, an ONNX model can force-align them locally (Chinese / English / Japanese); models and dictionaries download on demand.
- System lyrics: Lyricon provider, media notification lyrics, a standalone lyrics notification, and the HyperOS Super Island.
- LRC export and lyric offset calibration.

### Interface

- Apple Music-inspired visuals: glass and blur, Liquid Glass controls, and flowing mesh or sand dynamic backdrops (Kawarp artwork pipeline and an Apple-style blur).
- Five now-playing themes: Standard, Adaptive colours, Apple artwork flow, Distorted pixels, and a GL dynamic backdrop with several mesh presets.
- Independent adaptive text colour, icon style, control style, and accent colour.
- Elastic motion throughout: morphing transitions, 2D lyric physics, dock glass lens, and sticky drag.
- 12 UI languages, light/dark themes, and a reduce-transparency option.
- Data analysis: library growth, audio quality mix, genre distribution, playback outcomes, a listening heatmap, a footprints timeline, and an annual report — all computed locally.
- Novelty effects: cassette, radio crosstalk, vinyl wear, gyroscope knob, and gravity lyrics, each individually toggleable.
- Easter eggs: find them yourself.

### Data and sync

- Desktop music library sync over LAN, mesh, or public addresses, with pairing codes and certificate fingerprint pinning.
- Settings and data backup export and restore.
- Manage and clear the stream cache, cover cache, and library cache.
- In-app update check via GitHub Releases.

## Download

Get the APK from [Releases](https://github.com/MisakaMkotoo/Trove/releases). Current version: **1.2.0-beta**.
APKs are split per ABI (`arm64-v8a` preferred, plus `armeabi-v7a` and `x86_64`).

Requires **Android 12 (API 31) or newer** (`targetSdk = 36`). You will need to allow installs from this source once.

## Tech stack

- Kotlin Multiplatform: `androidApp` + `sharedLogic`, Compose Multiplatform 1.11.1 / Material3.
- Native playback core: FFmpeg 8.1.2 + AAudio (JNI), OpenSSL 3.0.x.
- ONNX Runtime 1.32.0 (trimmed operator set) for lyric forced alignment.
- Media3 media session, Haze blur, Vico charts, DataStore.
- Toolchain: Kotlin 2.4.10, AGP 9.3.1, Gradle 9.5.0, JDK 21, NDK 30.0.16248370.

## Building

Requirements:

- JDK 21
- Android SDK 37 (`compileSdk = 37`, `targetSdk = 36`, `minSdk = 31`)
- Android NDK 30.0.16248370
- The Gradle wrapper bundled in this repository

Prebuilt FFmpeg and ONNX Runtime `.so` files are not distributed with the repository (`androidApp/src/main/jniLibs/` is ignored). Generate them first:

```bash
# FFmpeg for all ABIs
bash scripts/build-ffmpeg-android.sh all

# ONNX Runtime with the trimmed operator set (lyric alignment)
bash scripts/build-onnxruntime-android.sh
```

Then:

```bash
# Debug build
./gradlew :androidApp:assembleProdDebug

# Release build (requires signing env vars)
export MOBILE_KEYSTORE_FILE=/path/to/keystore
export MOBILE_KEYSTORE_PASSWORD=...
export MOBILE_KEY_ALIAS=...
export MOBILE_KEY_PASSWORD=...
./gradlew :androidApp:assembleProdRelease
```

Without the signing variables the release build falls back to the debug signature, which is only suitable for local testing.

## Project layout

```text
androidApp/     Android app (Compose UI, playback service, NetEase, sync)
sharedLogic/    Shared playback queue and library models
scripts/        FFmpeg / OpenSSL / ONNX Runtime build and tooling scripts
tools/          Font subsetting and romanization data
licenses/       Full third-party license texts
brand/          App icon sources
fastlane/       Store metadata
```

## Upstream and acknowledgements

Trove started from **Tune** by [exodi_dio](https://github.com/Exodi-dio) (GPL-3.0), a local music player,
and has since been rebuilt into a NetEase Cloud Music third-party client. The player, UI, NetEase integration,
and sync features have been substantially rewritten; it is not an official branch of Tune.

- Interface design language: [Airmedy](https://github.com/misa198/airmedy) by misa198 (GPL-3.0).
- Some artist-page interactions adapted from [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0).
- Lyric forced-alignment pipeline and ONNX models follow [MeloX-Android](https://github.com/lladlam/MeloX-Android) (GPL-3.0), which also provides the SF Symbols icon mapping.
- NetEase endpoints, lyric source ranking, and request fingerprinting are heavily informed by [MeiloX](https://github.com/MeiloX/MeiloX) (GPL-3.0); no code is reused directly.
- Glass motion adapted from Glasmio (NEORUAA) and AndroidLiquidGlass (Kyant0); the artwork-flow backdrop is based on Kawarp (Better Lyrics); NCM decoding follows ncmdump.
- Full component and provenance list: [NOTICE](NOTICE).

## License

Licensed under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE).
As a GPLv3 derivative, redistributing modified builds requires keeping the copyright notices and license, and providing the corresponding source.
