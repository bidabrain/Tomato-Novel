# Tomato Novel Reader

An Android e-book reader with built-in Fanqie Novel (番茄小说) search and auto-update.

## Features

- **Search Fanqie Novel** — search Chinese novels directly from the bookshelf screen
- **One-tap download** — adding a search result downloads the full novel as a local TXT file automatically
- **Auto-update on launch** — checks all Fanqie-sourced books for new chapters on every app start; a manual refresh button is also available on the bookshelf
- **Reading progress on shelf** — bookshelf shows current chapter and total chapters for each book
- **Recently-read sorting** — bookshelf is sorted by last read time, most recent first
- **Local TXT reading** — smooth canvas-rendered paging, bookmarks, chapter index, font/background customization
- **Mixed shelf** — manually added local TXT files sit alongside Fanqie books; only Fanqie books are checked for updates
- **Book store** — a dedicated discovery tab showing daily Fanqie Novel rankings by genre; each genre section displays up to 10 randomly picked titles with cover, synopsis, and a one-tap search shortcut; each section has its own refresh button to reshuffle, and the rankings are refreshed automatically every time the app is opened
- **Custom server settings** — override the built-in download server address/password and the book store data source from within the app (via the ··· menu → 服务器设置), no recompilation required; useful when distributing an APK built without `local.properties`

## Screenshots

![Tomato Novel Reader](screenshot.jpg)

## Download

Get the latest release APK from the [Releases](../../releases) page.

## Build

Requirements: JDK 11+, Android SDK (command-line tools or Android Studio)

```bash
git clone git@github.com:bidabrain/Tomato-Novel.git
cd Tomato-Novel
cp local.properties.example local.properties
# Edit local.properties and fill in your server URL and password (see Server Configuration below)
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/
```

## Server Configuration

The app connects to a self-hosted [Tomato-Novel-Downloader](https://github.com/Dlankevi/Tomato-Novel-Downloader) instance for search and download.

Credentials are **not stored in source code**. Instead, copy `local.properties.example` to `local.properties` (which is git-ignored) and fill in your own values:

```
# local.properties
sdk.dir=/path/to/android/sdk
DOWNLOADER_URL=https://your-downloader-server.example.com
DOWNLOADER_PASSWORD=your-password-here
```

At build time, Gradle reads these values and injects them into `BuildConfig`. The compiled APK contains them, but they never appear in the source code.

If `local.properties` is missing or the fields are empty, the app will build successfully but search and download will not work until a custom server is configured inside the app (see **In-app server settings** below).

## In-app server settings

Server addresses and credentials can also be configured at runtime without rebuilding:

1. Open the app → tap **···** (top-right menu) → **服务器设置**
2. Toggle **自定义搜索下载服务器** to enter a custom downloader URL and password
3. Toggle **自定义书城数据源** to point the book store at a different `latest_ranks.json` endpoint
4. Tap **保存** — the book store cache is cleared automatically so the new source takes effect immediately

When a toggle is off, the app falls back to the value compiled in via `local.properties`. When a toggle is on but the field is left empty, the built-in value is also used as a fallback.

## Acknowledgements

This project stands on the shoulders of several open-source works:

| Project | Role |
|---|---|
| [gedoor/legado](https://github.com/gedoor/legado) | Inspiration for the overall reading experience |
| [yanjunhui2014/ebook_reader](https://github.com/yanjunhui2014/ebook_reader) | Canvas-based TXT paging engine (PageFactory / PageWidget) that powers the reader |
| [Dlankevi/Tomato-Novel-Downloader](https://github.com/Dlankevi/Tomato-Novel-Downloader) | Research reference for Fanqie Novel API endpoints and proxy strategy |
| [bumptech/glide](https://github.com/bumptech/glide) | Cover image loading |
| [square/okhttp](https://github.com/square/okhttp) | HTTP networking |
| [google/gson](https://github.com/google/gson) | JSON parsing |
| [androidx Room](https://developer.android.com/jetpack/androidx/releases/room) | Local database (replaced LitePal) |
| [wen1701/FanqieRankTracker](https://github.com/wen1701/FanqieRankTracker) | Book store ranking data — daily Fanqie Novel charts powering the in-app book discovery feed |

## License

This project is for personal, non-commercial use. Please respect the terms of service of Fanqie Novel / 番茄小说 (ByteDance).
