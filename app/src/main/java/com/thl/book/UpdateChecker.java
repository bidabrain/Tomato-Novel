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
    private static final String TAG = "UpdateChecker";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void checkOnLaunch(Context context) {
        executor.execute(() -> {
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
            if (tomatoBooks.isEmpty()) return;

            // 标记所有书为"更新中…"并刷新书架
            for (BookList book : tomatoBooks) {
                DB.bookList().updateCharsetAndMsg(book.getId(), book.getCharset(), "更新中…");
            }
            context.sendBroadcast(new Intent(ACTION_UPDATE_DONE).putExtra("total_new", 0));

            NovelDownloadManager manager = new NovelDownloadManager(context);
            int totalNew = 0;

            for (BookList book : tomatoBooks) {
                String originalMsg = book.getMsg();
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
                context.sendBroadcast(new Intent(ACTION_UPDATE_DONE).putExtra("total_new", 0));
            }

            Log.d(TAG, "Update check done. Total new chapters: " + totalNew);
            context.sendBroadcast(new Intent(ACTION_UPDATE_DONE).putExtra("total_new", totalNew));
        });
    }
}
