package com.thl.book;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.thl.book.download.NovelDownloadManager;
import com.thl.reader.db.BookList;
import com.thl.reader.db.DB;
import com.thl.reader.db.TomatoBook;
import com.thl.book.network.FanqieApi;
import com.thl.book.network.FanqieClient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks all Fanqie books for new chapters on app launch.
 * Phase 1: parallel chapter-count checks (up to 4 concurrent).
 * Phase 2: sequential full-book downloads for books that have new chapters.
 */
public class UpdateChecker {

    public static final String ACTION_UPDATE_DONE = "com.thl.book.UPDATE_DONE";
    /** Extra boolean: true 表示全部检查完毕，false 表示中间进度刷新 */
    public static final String EXTRA_IS_FINISHED = "is_finished";
    public static final String EXTRA_CURRENT   = "current";    // 当前第几本（1-based）
    public static final String EXTRA_TOTAL     = "total";      // 总本数
    public static final String EXTRA_BOOK_NAME = "book_name";  // 当前书名
    private static final String TAG = "UpdateChecker";

    // 主控线程（串行）
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    // 章节数检查并发线程池
    private static final ExecutorService checkPool = Executors.newFixedThreadPool(4);

    /** 当前是否有更新检查正在后台运行 */
    private static volatile boolean sIsRunning    = false;
    private static volatile int     sCurrentBook  = 0;
    private static volatile int     sTotalBooks   = 0;
    private static volatile String  sCurrentBookName = "";

    public static boolean isRunning()          { return sIsRunning; }
    public static int     getCurrentBook()     { return sCurrentBook; }
    public static int     getTotalBooks()      { return sTotalBooks; }
    public static String  getCurrentBookName() { return sCurrentBookName; }

    public static void checkOnLaunch(Context context) {
        if (sIsRunning) return;
        sIsRunning = true;
        sCurrentBook = 0;
        sTotalBooks  = 0;
        sCurrentBookName = "";
        executor.execute(() -> {
            try {
                List<BookList> allTomato = DB.bookList().findByIsTomato(1);
                List<BookList> tomatoBooks = new ArrayList<>();
                for (BookList b : allTomato) {
                    if (b.getTomatoBookId() != null
                            && b.getBookpath() != null
                            && !b.getBookpath().isEmpty()) {
                        tomatoBooks.add(b);
                    }
                }
                if (tomatoBooks.isEmpty()) {
                    context.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                            .setPackage(context.getPackageName())
                            .putExtra("total_new", 0)
                            .putExtra(EXTRA_IS_FINISHED, true));
                    return;
                }

                int total = tomatoBooks.size();
                sTotalBooks = total;

                // 标记所有书为"更新中…"
                for (BookList book : tomatoBooks) {
                    DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), "更新中…");
                }
                broadcast(context, false, 0, total, "");

                // ── Phase 1：检查有无新章节 ───────────────────────────────
                FanqieApi api = new FanqieApi(
                        FanqieClient.getProxyUrl(context),
                        FanqieClient.getDownloaderUrl(context),
                        FanqieClient.getDownloaderPassword(context));

                AtomicInteger checked = new AtomicInteger(0);
                List<BookList> needsDownload = new CopyOnWriteArrayList<>();

                // Phase 1a：优先用服务器 /api/updates（与下载逻辑用同一套计数）
                Map<String, Boolean> serverUpdateMap = fetchServerUpdateMap(api);

                // Phase 1b：对服务器有记录的书直接采用服务器结论
                List<BookList> needFallback = new ArrayList<>();
                for (BookList book : tomatoBooks) {
                    String bookId = book.getTomatoBookId();
                    if (serverUpdateMap.containsKey(bookId)) {
                        int cur = checked.incrementAndGet();
                        sCurrentBook = cur;
                        sCurrentBookName = book.getBookname();
                        broadcast(context, false, cur, total, book.getBookname());
                        if (Boolean.TRUE.equals(serverUpdateMap.get(bookId))) {
                            needsDownload.add(book);
                        } else {
                            String orig = "更新中…".equals(book.getMsg()) ? "" : book.getMsg();
                            DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), orig);
                        }
                        broadcast(context, false, cur, total, book.getBookname());
                    } else {
                        needFallback.add(book);
                    }
                }

                // Phase 1c：服务器无记录的书（缓存被清或首次），降级直接查 fanqie 接口
                List<Future<?>> futures = new ArrayList<>();
                for (BookList book : needFallback) {
                    futures.add(checkPool.submit(() -> {
                        String bookId = book.getTomatoBookId();
                        TomatoBook meta = DB.tomatoBook().findByBookId(bookId);
                        int cur = checked.incrementAndGet();
                        sCurrentBook = cur;
                        sCurrentBookName = book.getBookname();
                        broadcast(context, false, cur, total, book.getBookname());
                        try {
                            int latestTotal = api.getChapterList(bookId).size();
                            if (meta != null && latestTotal > meta.getTotalChapters()) {
                                needsDownload.add(book);
                            } else if (latestTotal == 0) {
                                // 章节列表获取失败，跳过
                            } else {
                                String orig = "更新中…".equals(book.getMsg()) ? "" : book.getMsg();
                                DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), orig);
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Check failed for " + book.getBookname(), e);
                            String orig = "更新中…".equals(book.getMsg()) ? "" : book.getMsg();
                            DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), orig);
                        }
                        broadcast(context, false, cur, total, book.getBookname());
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }

                // ── Phase 2：顺序下载有新章节的书 ─────────────────────────
                NovelDownloadManager manager = new NovelDownloadManager(context);
                int totalNew = 0;
                int dlTotal = needsDownload.size();
                for (int i = 0; i < dlTotal; i++) {
                    BookList book = needsDownload.get(i);
                    sCurrentBook = i + 1;
                    sCurrentBookName = book.getBookname();
                    sTotalBooks = dlTotal;
                    broadcast(context, false, i + 1, dlTotal, book.getBookname());
                    String orig = "更新中…".equals(book.getMsg()) ? "" : book.getMsg();
                    try {
                        int newChapters = manager.downloadNewChapters(book);
                        totalNew += newChapters;
                        Log.d(TAG, book.getBookname() + ": +" + newChapters);
                        if (newChapters == 0) {
                            DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), orig);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Download failed for " + book.getBookname(), e);
                        DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), orig);
                    }
                    broadcast(context, false, i + 1, dlTotal, book.getBookname());
                }

                Log.d(TAG, "Update done. Total new: " + totalNew);
                context.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                        .setPackage(context.getPackageName())
                        .putExtra("total_new", totalNew)
                        .putExtra(EXTRA_IS_FINISHED, true));
            } finally {
                sIsRunning = false;
            }
        });
    }

    /**
     * 调用服务器 /api/updates，等待扫描完成，返回 book_id → has_update 映射。
     * 服务器不可达或无结果时返回空 map，调用方降级为直接查 fanqie 接口。
     */
    private static Map<String, Boolean> fetchServerUpdateMap(FanqieApi api) {
        Map<String, Boolean> result = new HashMap<>();
        JsonObject scan = api.getUpdateScan(true);
        if (scan == null) return result;

        // 轮询直到扫描完成，最多等 60 秒
        for (int tick = 0; tick < 30; tick++) {
            if (!scan.has("running") || !scan.get("running").getAsBoolean()) break;
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            scan = api.getUpdateScan(false);
            if (scan == null) return result;
        }

        for (String key : new String[]{"updates", "no_updates"}) {
            if (!scan.has(key) || !scan.get(key).isJsonArray()) continue;
            JsonArray arr = scan.getAsJsonArray(key);
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                if (!row.has("book_id")) continue;
                String bookId = row.get("book_id").getAsString();
                boolean hasUpdate = row.has("has_update") && row.get("has_update").getAsBoolean();
                result.put(bookId, hasUpdate);
            }
        }
        return result;
    }

    private static void broadcast(Context ctx, boolean finished, int cur, int total, String name) {
        Log.d(TAG, "broadcast → finished=" + finished + " cur=" + cur + "/" + total + " name='" + name + "'");
        ctx.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                .setPackage(ctx.getPackageName())
                .putExtra("total_new", 0)
                .putExtra(EXTRA_IS_FINISHED, finished)
                .putExtra(EXTRA_CURRENT, cur)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_BOOK_NAME, name));
    }
}
