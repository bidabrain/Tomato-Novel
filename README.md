# 番茄阅读器

一款内置番茄小说搜索与自动更新功能的 Android 电子书阅读器。

## 功能特性

- **搜索番茄小说** — 在书架界面直接搜索中文小说
- **一键下载** — 搜索结果可自动下载完整 TXT 文件到本地书架
- **启动自动更新** — 每次打开 App 自动检查书架中番茄小说的新章节，也可手动点击刷新按钮
- **阅读进度显示** — 书架显示每本书当前章节与总章节数
- **最近阅读排序** — 书架按最近阅读时间降序排列
- **本地 TXT 阅读** — 流畅的 Canvas 翻页渲染，支持书签、目录、字体与背景自定义
- **混合书架** — 本地手动添加的 TXT 与番茄小说并排显示，仅番茄小说参与更新检测
- **书城** — 独立的发现标签页，展示番茄小说每日分类榜单；每个类型随机推荐最多 10 本，附封面与简介，点击可一键跳转搜索；各类型配有独立换一批按钮，每次打开 App 自动刷新
- **自定义服务器** — 无需重新编译，在 App 内（右上角菜单 → 服务器设置）即可覆盖内置的下载服务器地址/密码和书城数据源，方便分发未配置 `local.properties` 的 APK
- **WebDAV 同步** — 在服务器设置中配置 WebDAV 地址、用户名和密码，通过右上角菜单的「WebDAV同步」按钮手动触发；书架采用最后修改时间决定方向（含删除传播），阅读进度按书逐条合并取最新值，本周累计阅读时间跨设备取最大值；支持坚果云、Nextcloud 等标准 WebDAV 服务
- **阅读统计** — 书架顶部左侧显示本周累计阅读时间，右侧显示上次读到的书及章节进度；有阅读记录时才显示右侧卡片，无阅读记录时左侧独占全行
- **墨水屏模式** — 专为电子墨水屏设备优化的阅读模式，关闭动画与渐变效果，采用纯黑白配色，减少残影并降低刷新频率对阅读的干扰；通过主界面左上角 **E-ink** 开关全局控制（书架、书城、阅读界面同步切换），阅读界面的背景、翻页、夜间模式在开启时自动锁定。**强烈建议在墨水屏设备（如文石、Kindle、掌阅等）上安装时开启此模式**

## 截图

![书架与书城](screenshot1.jpg)

![阅读界面](screenshot2.jpg)

## 下载

从 [Releases](../../releases) 页面获取最新 APK。

## 编译

环境要求：JDK 11+、Android SDK（命令行工具或 Android Studio）

```bash
git clone git@github.com:bidabrain/Tomato-Novel.git
cd Tomato-Novel
cp local.properties.example local.properties
# 编辑 local.properties，填入服务器地址和密码（详见下方「服务器配置」）
./gradlew assembleDebug
# APK 输出路径：app/build/outputs/apk/debug/
```

## 服务器配置

App 依赖自建的 [Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) 实例进行搜索与下载。

服务器地址和密码**不存储在源码中**，需将 `local.properties.example` 复制为 `local.properties`（已加入 .gitignore）并填写：

```
# local.properties
sdk.dir=/path/to/android/sdk
DOWNLOADER_URL=https://your-downloader-server.example.com
DOWNLOADER_PASSWORD=your-password-here
```

编译时 Gradle 会将这些值注入 `BuildConfig`，打包进 APK 但不出现在源码中。

若 `local.properties` 不存在或字段为空，App 可正常编译，但搜索和下载功能需在 App 内手动配置服务器后才能使用（见下方「应用内服务器设置」）。

## 应用内服务器设置

无需重新编译，可在运行时配置服务器：

1. 打开 App → 点击右上角 **···** → **服务器设置**
2. 开启 **自定义搜索下载服务器**，填入服务器地址和访问密码
3. 开启 **自定义书城数据源**，填入 `latest_ranks.json` 的完整 URL
4. 点击 **保存** — 书城缓存自动清空，新数据源立即生效

开关关闭或输入框留空时，自动回退到编译时内置的地址。

## 致谢

| 项目 | 用途 |
|---|---|
| [gedoor/legado](https://github.com/gedoor/legado) | 整体阅读体验的灵感来源 |
| [yanjunhui2014/ebook_reader](https://github.com/yanjunhui2014/ebook_reader) | 基于 Canvas 的 TXT 翻页引擎（PageFactory / PageWidget） |
| [zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) | 番茄小说 API 接入与代理策略参考 |
| [bumptech/glide](https://github.com/bumptech/glide) | 封面图片加载 |
| [square/okhttp](https://github.com/square/okhttp) | HTTP 网络请求 |
| [google/gson](https://github.com/google/gson) | JSON 解析 |
| [androidx Room](https://developer.android.com/jetpack/androidx/releases/room) | 本地数据库 |
| [wen1701/FanqieRankTracker](https://github.com/wen1701/FanqieRankTracker) | 书城每日番茄小说分类榜单数据 |
| [rany2/edge-tts](https://github.com/rany2/edge-tts) | Edge TTS WebSocket 协议实现参考（Sec-MS-GEC 鉴权算法） |

## 许可

本项目仅供个人非商业用途。请遵守番茄小说 / 字节跳动的服务条款。
