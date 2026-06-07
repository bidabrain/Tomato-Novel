package com.thl.book;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.thl.book.download.NovelDownloadManager;
import com.thl.reader.db.BookList;

import com.thl.reader.db.DB;

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
    // Skip re-check if last check was less than 1 hour ago
    private static final long CHECK_INTERVAL_MS = 60 * 60 * 1000L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void checkOnLaunch(Context context) {
        executor.execute(() -> {
            List<BookList> tomatoBooks = DB.bookList().findByIsTomato(1);
            if (tomatoBooks.isEmpty()) return;

            NovelDownloadManager manager = new NovelDownloadManager(context);
            int totalNew = 0;

            for (BookList book : tomatoBooks) {
                if (book.getTomatoBookId() == null) continue;
                try {
                    int newChapters = manager.downloadNewChapters(book);
                    totalNew += newChapters;
                    Log.d(TAG, book.getBookname() + ": +" + newChapters + " chapters");
                } catch (Exception e) {
                    Log.w(TAG, "Update failed for " + book.getBookname(), e);
                }
            }

            Log.d(TAG, "Update check done. Total new chapters: " + totalNew);
            // Notify bookshelf to refresh
            Intent intent = new Intent(ACTION_UPDATE_DONE);
            intent.putExtra("total_new", totalNew);
            context.sendBroadcast(intent);
        });
    }
}
