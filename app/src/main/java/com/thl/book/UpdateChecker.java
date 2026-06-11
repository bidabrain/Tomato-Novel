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

import java.util.ArrayList;
import java.util.List;
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

                // ── Phase 1：并发检查章节数 ────────────────────────────────
                FanqieApi api = new FanqieApi(
                        FanqieClient.getProxyUrl(context),
                        FanqieClient.getDownloaderUrl(context),
                        FanqieClient.getDownloaderPassword(context));

                AtomicInteger checked = new AtomicInteger(0);
                // 收集有新章节的书（保持原顺序）
                List<BookList> needsDownload = new CopyOnWriteArrayList<>();

                List<Future<?>> futures = new ArrayList<>();
                for (BookList book : tomatoBooks) {
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
                                // 无新章节，恢复 msg
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
                // 等待所有检查完成
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
                        .putExtra("total_new", totalNew)
                        .putExtra(EXTRA_IS_FINISHED, true));
            } finally {
                sIsRunning = false;
            }
        });
    }

    private static void broadcast(Context ctx, boolean finished, int cur, int total, String name) {
        ctx.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                .putExtra("total_new", 0)
                .putExtra(EXTRA_IS_FINISHED, finished)
                .putExtra(EXTRA_CURRENT, cur)
                .putExtra(EXTRA_TOTAL, total)
                .putExtra(EXTRA_BOOK_NAME, name));
    }
}
