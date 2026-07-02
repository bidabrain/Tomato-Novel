# WebDAV 同步方案与逻辑说明

本文档说明番茄小说阅读器（Tomato-reader）当前的 WebDAV 同步机制，包括同步策略、多设备一致性保证，以及初始数据不被误删/覆盖的保护逻辑。

## 相关文件

| 文件 | 作用 |
|---|---|
| `app/.../WebDavSyncManager.java` | 同步核心：书架/进度/统计的双向同步逻辑，以及 WebDAV 原语（PROPFIND/PUT/GET） |
| `app/.../WebDavConfig.java` | 配置（URL/账号/密码/开关）与同步时间戳的 SharedPreferences 存取 |
| `app/.../BookApplication.java` | 同步的自动触发（监听 App 生命周期） |
| `book/.../util/ReadingStatsManager.java` | 阅读统计的合并逻辑（`mergeFromSync` / `mergeCumulativeDaysFromSync`） |

## 同步的三份数据

| 数据 | 文件名 | 同步策略 |
|---|---|---|
| 书架（`List<BookList>`） | `tomato_bookshelf.json` | **Last-Write-Wins（整体替换）** |
| 阅读进度（id + 位置） | `tomato_progress.json` | **按书籍逐条合并** |
| 阅读统计（周阅读时长/累计天数） | `tomato_reading_stats.json` | **同一周取 MAX 合并** |

---

## 1. 同步的触发时机

只有**自动同步**，没有手动同步按钮，也没有定时任务。

`WebDavSyncManager.sync(...)` 的唯一调用方是 `BookApplication.triggerAutoSync()`，在两个时机触发（`BookApplication.java`）：

- **App 回到前台时**（`onActivityStarted`，started 数从 0→1）
- **App 退到后台时**（`onActivityStopped`，started 数变为 0）

两个时机都会调用 `triggerAutoSync()`：若 `WebDavConfig.isEnabled()` 为 false 则直接返回；否则在**单线程后台执行**，成功后用 `WebDavConfig.saveLastSyncAt()` 记录最后同步时间。

> 注意：`WebDavConfig.markBookshelfModified()` **不是**同步触发器，而是在“添加/删除书籍”时更新本地修改时间戳，供后续同步判断新旧。

---

## 2. 书架同步：Last-Write-Wins（整体替换）

书架不做逐本合并，而是**“谁新谁赢，赢家完整覆盖对方”**。

### 新旧判定

用 JSON 内嵌的 `modifiedAt`（毫秒）与本地 `KEY_BOOKSHELF_LOCAL_MODIFIED` 比较：

```java
boolean remoteNewer = snapshot.modifiedAt > localBookshelfModified;
```

- **远端更新** → 调用 `replaceLocalBookshelf()` 用远端书架整体替换本地，并把本地时间戳更新为远端值。
- **本地更新 / 相等** → 把本地书架 PUT 上传。
- **远端文件不存在（404）** → 直接上传本地。

> PROPFIND 拿到的 `getlastmodified` 只用于“连通性检查 + 文件是否存在”，**冲突判定用的是 JSON 内嵌的 `modifiedAt`**，不用 hash 或版本号。

### 删除如何传播（无 tombstone）

**没有删除标记（tombstone）**，删除是靠“整体替换”作为副作用传播的。`replaceLocalBookshelf()` 以远端书架为准让本地对齐：

```java
// 远端没有、本地有的书 → 从本地删除
if (!inRemote) {
    DB.bookList().deleteById(b.getId());
    if (b.getIsTomato() == 1 && b.getBookpath() != null)
        new File(b.getBookpath()).delete();   // 番茄书还会删掉已下载的 TXT
    removed++;
}
```

- 远端有、本地没有的书 → 新增（番茄书以“待下载”插入；本地文件书仅当实际文件存在时才加入）。
- 匹配键：番茄书用 `tomatoBookId`，其他用 `bookpath`。
- 代码注释明确写道：“Newer side wins and completely replaces the other. This ensures deletions propagate in both directions.”（新的一方胜出并完全替换另一方，从而让删除在两个方向都能传播。）

---

## 3. 阅读进度同步：按书籍逐条合并

进度**不存在删除概念**，所以永远走合并，最安全：

1. 总是先下载远端进度，用 `applyProgress()` 合并到本地；
2. 再把（合并后的）当前进度上传。

逐条判定：按 `tomatoBookId` 或 `bookpath` 匹配到本书后，**谁的 `lastReadAt` 严格更新就用谁的**：

```java
if (local != null && p.lastReadAt > local.getLastReadAt()) {
    DB.bookList().updateBegin(local.getId(), p.begin, p.lastReadAt);
}
```

即使某本书在书架里被删了，残留的进度记录也不会造成问题，因为 `applyProgress()` 只更新“本地存在的书”。

---

## 4. 阅读统计同步：同一周取 MAX

下载远端统计后，用 `ReadingStatsManager.mergeFromSync()`（周秒数）与 `mergeCumulativeDaysFromSync()`（累计天数）合并，再上传：

```java
if (!currentWeek.equals(remoteWeekStart)) return;   // 不是同一周则忽略远端
...
long merged = Math.max(local, remoteSeconds);        // 同一周取较大值
```

累计天数同理：`Math.max(local, remoteDays)`。这样多设备的阅读时长不会互相覆盖变小。

---

## 5. 初始同步的保护逻辑（防误删/误覆盖）

核心保护只有**一处**——“全新安装保护”：

```java
// 本地从未修改（0）且本地无书时，优先信任服务器（防止全新安装用空书架覆盖服务器）
boolean freshInstallNoBooks = (localBookshelfModified == 0)
        && DB.bookList().findAll().isEmpty()
        && snapshot.books != null && !snapshot.books.isEmpty();
if (remoteNewer || freshInstallNoBooks) {
    // 下载远端并替换本地
}
```

- 新装 App 时 `localBookshelfModified` 默认是 `0`。
- 若不加保护，`0` 会被判为“本地不比远端新”，从而**用空书架上传覆盖掉服务器**。
- 因此当 **“本地修改时间=0” 且 “本地书架为空” 且 “远端有书”** 三条件同时成立时，**强制信任服务器**，先把远端下载下来。这是初次同步最关键的安全阀。

---

## 6. 保证了什么 / 没保证什么

### ✅ 已保证

- **全新安装不会用空数据覆盖服务器**（fresh-install 保护）。
- **进度、统计走合并**，不会因冲突而丢失或变小。

### ⚠️ 未保证（结构性风险）

- **书架是 Last-Write-Wins 整体替换，不是逐本合并。**
  两台设备各自做了不同修改时（设备 A 加了书 X，设备 B 加了书 Y），`modifiedAt` 较新的一方会把较旧一方的改动**整体抹掉**。
- **没有任何确认弹窗**，完全自动、后台静默执行。
- **fresh-install 保护只在“本地为空”时生效**，一旦本地有书就不再触发。
- **删除用整体替换而非 tombstone**，无法区分“对方删除了这本书”与“对方还没同步到这本书”。

---

## 7. 可选的改进方向

如需让书架同步更稳健，可考虑：

1. **书籍级合并**：为每本书维护独立的时间戳 + tombstone（删除标记），把书架从“整体替换”改为“逐本 merge”，从根本上消除互相覆盖。
2. **同步前快照备份**：每次覆盖前在 WebDAV 上保留一份上一版本，便于误删后回滚。
3. **可选的冲突提示**：检测到双方都改动时，给出提示或保留双方并集，而非静默取新。
