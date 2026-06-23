# 番茄阅读器 Alone 版

一款将 [Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) 服务器**内嵌运行**的 Android 电子书阅读器。无需搭建外部服务器，安装即用。

包名：`com.isayama.tomatoreaderalone`（与原版并存，互不覆盖）

## 功能特性

- **内嵌下载服务器** — 官方 Tomato-Novel-Downloader 二进制随 App 一起安装，App 启动时自动在后台运行，无需外部服务器
- **搜索番茄小说** — 在书架界面直接搜索中文小说
- **一键下载** — 搜索结果可自动下载完整 TXT 文件到本地书架
- **启动自动更新** — 每次打开 App 自动检查书架中番茄小说的新章节，也可手动点击刷新按钮
- **阅读进度显示** — 书架显示每本书当前章节与总章节数
- **最近阅读排序** — 书架按最近阅读时间降序排列
- **本地 TXT 阅读** — 流畅的 Canvas 翻页渲染，支持书签、目录、字体与背景自定义
- **混合书架** — 本地手动添加的 TXT 与番茄小说并排显示，仅番茄小说参与更新检测
- **书城** — 独立的发现标签页，展示番茄小说每日分类榜单
- **WebDAV 同步** — 在设置中配置 WebDAV 地址，同步书架与阅读进度；支持坚果云、Nextcloud 等
- **阅读统计** — 本周累计阅读时间、上次阅读记录
- **单手模式 / 墨水屏模式**
- **缓存清理** — 在服务器设置页可一键清理服务器中间缓存及合并产出 TXT，不影响书架中的书

## 更新日志

### v1.7.3

- **修复书架更新检测卡在旧章节数的问题**：此前检测更新直接采用服务器 `/api/updates` 的 `has_update` 标志，而该标志表示「服务器库 vs 番茄源」。当服务器已自行补全完整副本（如清理服务器缓存后重新下载），该标志恒为 `false`，导致本地副本落后的书籍永远无法更新。现改为用服务器返回的最新章节数（`remote_total`/`local_total`）与本地已下载章节数比较来判断是否下载。
- **修复 `tomato_book` 重复记录导致章节数统计错乱**：同一本书在重复下载时会被重复插入数据库，`findByBookId` 取到旧记录使章节数读数偏小。现 `bookId` 加唯一索引，写入改为按 `bookId` upsert，并新增数据库迁移（v3→v4）自动去重历史重复记录（覆盖安装即生效，无需清数据）。

## 存储路径

```
Android/data/com.isayama.tomatoreaderalone/files/tomato/
├── local/    ← App 读取的书（书名.txt）
└── server/   ← 内嵌服务器的书籍存储
```

内嵌服务器配置和日志存放于内部存储（用户不可见）：
```
/data/data/com.isayama.tomatoreaderalone/files/server_data/
```

---

## 内嵌服务器二进制文件

二进制文件**不随源码提交**（体积较大），需手动下载后放入项目再编译。

### 首次编译前：下载并放置二进制文件

1. 前往 [Tomato-Novel-Downloader Releases](https://github.com/zhongbai2333/Tomato-Novel-Downloader/releases)
2. 找到最新 Release，在 Assets 中下载：
   - `TomatoNovelDownloader-Android_arm64-vX.X.X` → 重命名为 `libserver.so`
   - `TomatoNovelDownloader-Android_arm32-vX.X.X`（或类似名称）→ 重命名为 `libserver.so`
3. 分别放入对应目录：

```
app/src/main/jniLibs/
├── arm64-v8a/
│   └── libserver.so    ← arm64 版本
└── armeabi-v7a/
    └── libserver.so    ← arm32 版本
```

4. 正常编译即可，Gradle 会自动将 `.so` 文件打包进 APK。

### 更新二进制文件（上游 Release 有新版时）

1. 前往 [Releases 页面](https://github.com/zhongbai2333/Tomato-Novel-Downloader/releases) 下载新版 arm64 和 arm32 二进制
2. 重命名为 `libserver.so`，**覆盖**以下两个文件：
   - `app/src/main/jniLibs/arm64-v8a/libserver.so`
   - `app/src/main/jniLibs/armeabi-v7a/libserver.so`
3. 重新编译 APK：
   ```bash
   ./gradlew assembleRelease
   ```
4. 安装到手机（覆盖安装即可，无需卸载，书架数据保留）

> **注意**：无需修改任何 Java 代码，只替换二进制文件后重新编译即可。

### 已知问题

**Android 15 弹出「libserver.so not 16KB aligned」通知**

原因：上游预编译二进制未使用 `-zmax-page-size=16384` 编译，Android 15 系统会对不满足 16KB 对齐的 `.so` 文件发出系统通知。**功能完全正常，不影响使用。** 需上游在构建脚本中加入该链接器参数才能根治，可前往 [zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) 提 issue 请求修复。

---

## 编译

环境要求：JDK 11+、Android SDK（命令行工具或 Android Studio）

**前提：已按上方说明放置好 `libserver.so` 文件**

```bash
git clone <本仓库>
cd Tomato-reader-alone
./gradlew assembleRelease
# APK 输出路径：app/build/outputs/apk/release/
```

不需要配置 `local.properties`，服务器地址已硬编码为 `127.0.0.1:18423`。

---

## 运行原理

```
App 启动
  └─ BookApplication.onCreate()
       └─ 后台线程启动 libserver.so --server --data-dir <filesDir>/server_data
            ├─ 监听 127.0.0.1:18423
            ├─ 书籍保存到 externalFilesDir/tomato/server/
            └─ 等待就绪（最多 15 秒）

用户搜索/下载
  └─ FanqieApi → http://127.0.0.1:18423/api/...
       └─ 下载完成后文件存入 externalFilesDir/tomato/local/书名.txt
```

---

## 致谢

| 项目 | 用途 |
|---|---|
| [gedoor/legado](https://github.com/gedoor/legado) | 整体阅读体验的灵感来源 |
| [yanjunhui2014/ebook_reader](https://github.com/yanjunhui2014/ebook_reader) | 基于 Canvas 的 TXT 翻页引擎（PageFactory / PageWidget） |
| [zhongbai2333/Tomato-Novel-Downloader](https://github.com/zhongbai2333/Tomato-Novel-Downloader) | 内嵌服务器二进制，提供番茄小说 API 支持 |
| [bumptech/glide](https://github.com/bumptech/glide) | 封面图片加载 |
| [square/okhttp](https://github.com/square/okhttp) | HTTP 网络请求 |
| [google/gson](https://github.com/google/gson) | JSON 解析 |
| [androidx Room](https://developer.android.com/jetpack/androidx/releases/room) | 本地数据库 |
| [wen1701/FanqieRankTracker](https://github.com/wen1701/FanqieRankTracker) | 书城每日番茄小说分类榜单数据 |

## 许可

本项目仅供个人非商业用途。请遵守番茄小说 / 字节跳动的服务条款。
