# Tomato Novel Reader

An Android e-book reader with built-in Fanqie Novel (番茄小说) search and auto-update.

## Features

- **Search Fanqie Novel** — search Chinese novels directly from the bookshelf screen
- **One-tap download** — adding a search result to your bookshelf downloads the full novel as a local TXT file automatically
- **Auto-update on launch** — every time the app starts, it checks all Fanqie-sourced books for new chapters and appends them silently in the background
- **Local TXT reading** — smooth canvas-rendered paging, bookmarks, chapter index, font/background customization
- **Mixed shelf** — manually added local TXT files sit alongside Fanqie books; only Fanqie books are ever checked for updates

## Screenshots

_Install the APK and enjoy — screenshots coming soon._

## Download

Get the latest release APK from the [Releases](../../releases) page.

## Build

Requirements: JDK 11+, Android SDK (command-line tools or Android Studio)

```bash
git clone git@github.com:bidabrain/Tomato-Novel.git
cd Tomato-Novel
./gradlew assembleRelease
# APK → app/build/outputs/apk/release/
```

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

## License

This project is for personal, non-commercial use. Please respect the terms of service of Fanqie Novel / 番茄小说 (ByteDance).
