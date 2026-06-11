package com.thl.book;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.thl.book.download.NovelDownloadManager;
import com.thl.reader.db.BookList;

import com.thl.reader.db.DB;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Checks all Fanqie books for new chapters on app launch.
 * Runs on a single background thread; sends a local broadcast when done.
 */
public class UpdateChecker {

    public static final String ACTION_UPDATE_DONE = "com.thl.book.UPDATE_DONE";
    /** Extra boolean: true 表示全部检查完毕，false 表示中间进度刷新 */
    public static final String EXTRA_IS_FINISHED = "is_finished";
    public static final String EXTRA_CURRENT = "current";   // 当前第几本（1-based）
    public static final String EXTRA_TOTAL   = "total";     // 总本数
    public static final String EXTRA_BOOK_NAME = "book_name"; // 当前书名
    private static final String TAG = "UpdateChecker";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** 当前是否有更新检查正在后台运行 */
    private static volatile boolean sIsRunning = false;
    private static volatile int     sCurrentBook = 0;
    private static volatile int     sTotalBooks  = 0;
    private static volatile String  sCurrentBookName = "";

    public static boolean isRunning()      { return sIsRunning; }
    public static int     getCurrentBook() { return sCurrentBook; }
    public static int     getTotalBooks()  { return sTotalBooks; }
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
                // 只检查已下载完成的书（排除下载中/失败占位）
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

                // 标记所有书为"更新中…"并刷新书架
                int total = tomatoBooks.size();
                sTotalBooks = total;
                for (BookList book : tomatoBooks) {
                    DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), "更新中…");
                }
                context.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                        .putExtra("total_new", 0)
                        .putExtra(EXTRA_IS_FINISHED, false)
                        .putExtra(EXTRA_CURRENT, 0)
                        .putExtra(EXTRA_TOTAL, total)
                        .putExtra(EXTRA_BOOK_NAME, ""));

                NovelDownloadManager manager = new NovelDownloadManager(context);
                int totalNew = 0;

                for (int i = 0; i < tomatoBooks.size(); i++) {
                    BookList book = tomatoBooks.get(i);
                    sCurrentBook = i + 1;
                    sCurrentBookName = book.getBookname();
                    // 开始检查前发一次进度（显示书名）
                    context.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                            .putExtra("total_new", 0)
                            .putExtra(EXTRA_IS_FINISHED, false)
                            .putExtra(EXTRA_CURRENT, i + 1)
                            .putExtra(EXTRA_TOTAL, total)
                            .putExtra(EXTRA_BOOK_NAME, book.getBookname()));
                    String originalMsg = "更新中…".equals(book.getMsg()) ? "" : book.getMsg();
                    try {
                        int newChapters = manager.downloadNewChapters(book);
                        totalNew += newChapters;
                        Log.d(TAG, book.getBookname() + ": +" + newChapters + " chapters");
                        if (newChapters == 0) {
                            // 没有新章节，恢复原来的 msg
                            DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), originalMsg);
                        }
                        // 有新章节时 downloadNewChapters 内部已更新 msg
                    } catch (Exception e) {
                        Log.w(TAG, "Update failed for " + book.getBookname(), e);
                        DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), originalMsg);
                    }
                    // 每本书完成后刷新一次书架
                    context.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                            .putExtra("total_new", 0)
                            .putExtra(EXTRA_IS_FINISHED, false)
                            .putExtra(EXTRA_CURRENT, i + 1)
                            .putExtra(EXTRA_TOTAL, total)
                            .putExtra(EXTRA_BOOK_NAME, book.getBookname()));
                }

                Log.d(TAG, "Update check done. Total new chapters: " + totalNew);
                context.sendBroadcast(new Intent(ACTION_UPDATE_DONE)
                        .putExtra("total_new", totalNew)
                        .putExtra(EXTRA_IS_FINISHED, true));
            } finally {
                sIsRunning = false;
            }
        });
    }
}
