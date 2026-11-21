<p align="center"><img src="brand/icon-source.png" width="140" alt="Trove" /></p>

<h1 align="center">Trove</h1>

<p align="center"><b>An Apple Music-inspired Android music player</b><br />Local library, NetEase streaming, desktop library sync — in one app.</p>

<p align="center">
<img src="https://img.shields.io/badge/license-GPLv3-orange" alt="License: GPL-3.0" />
<img src="https://img.shields.io/badge/platform-Android%2012%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+" />
<img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF" alt="Kotlin + Compose" />
</p>

<p align="center"><a href="README.md">English</a> · <a href="README.zh-CN.md">简体中文</a></p>

> [!IMPORTANT]
> Trove is an unofficial, independently maintained project. It is not affiliated with,
> endorsed by, or connected to Apple or NetEase Cloud Music. Online features rely on
> third-party endpoints and may break at any time; please support the official services.
> The app contains no account system, ads, or analytics uploads.

## Features

**Interface**
- Frosted-glass, Apple Music–style UI with four now-playing themes: Standard, Adaptive colours, Album artwork flow (Kawarp pipeline), and Distorted pixels.
- Spring-based lyric scrolling: the active line leads while further lines trail behind with staggered springs; the last lines can still reach the focus slot.
- Optional "Adaptive text colour" that samples colours from the artwork and works with any player theme.
- 12 UI languages, light/dark themes, and a reduce-transparency option.

**Playback**
- Queue with play next, add to queue, drag reorder, remove; shuffle and repeat.
- Mini player and fullscreen player with seek, volume, and quality badge.
- Embedded FFmpeg decoding: FLAC, MP3, M4A/AAC, OGG, Opus, WAV, AIFF, APE, WavPack, DSF/DFF, WMA, and more.
- 10-band equalizer, volume normalization, crossfade up to 12 seconds.

**NetEase Cloud Music**
- Import playlists by link; tracks are matched locally or streamed online (no forced download).
- Daily recommendations, Private Radar, and Heartbeat mode on the home tab.
- Online lyrics are fetched from NetEase first, falling back to LRCLIB / Kugou.
- Online tracks can be favourited; favourites are persisted separately and appear in the "Favourites" playlist.
- Online playback goes through a local stream cache (800 MB LRU) and pauses when the network drops.

**Local library**
- Scan device audio; browse and search tracks, artists, albums, genres, composers, and playlists.
- Recently added, duplicate cleanup, hide tracks under 40 seconds, reorder bottom tabs.
- Custom playlist covers and artist pictures; album art from embedded files.

**Sync and import**
- LAN sync with the desktop music library.
- NCM file decoding and import.
- Last.fm scrobbling and listening statistics.

## Download

Get the APK from [Releases](https://github.com/MisakaMkotoo/Trove/releases). Current version: **1.2.0 Beta** (`FavFoldMusic-v1.2.0-beta.apk`).

Requires Android 12 (API 31) or newer. You will need to allow installs from this source once.

## Building

Requirements:

- JDK 21
- Android SDK 36 (`compileSdk = 36`, `minSdk = 31`)
- Android NDK 30.x (see `ndkVersion` in `androidApp/build.gradle.kts`)
- The Gradle wrapper bundled in this repository

Prebuilt FFmpeg `.so` files are not distributed with the repository (`androidApp/src/main/jniLibs/` is ignored). Generate them first:

```bash
bash scripts/build-ffmpeg-android.sh all
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
scripts/        FFmpeg / OpenSSL build scripts, icon and tooling scripts
tools/          Font subsetting and romanization data
brand/          App icon sources
```

## Upstream and acknowledgements

Trove is an independent derivative of [Exodi-dio/tune](https://github.com/Exodi-dio/tune) (GPL-3.0). The player, UI, NetEase integration, and sync features have been substantially reworked; it is not an official branch of Tune.

- Interface design language: [Airmedy](https://github.com/misa198/airmedy) by misa198 (GPL-3.0).
- Some artist-page interactions adapted from [BitChord](https://github.com/kushagrasinghx/BitChord) (GPL-3.0).
- Third-party components and provenance are listed in [NOTICE](NOTICE).

## License

Licensed under the **GNU General Public License v3.0 (GPLv3)** — see [LICENSE](LICENSE). As a GPLv3 derivative, redistributing modified builds requires keeping the copyright notices and license, and providing the corresponding source.
