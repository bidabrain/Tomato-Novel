package com.thl.book.download;

import android.content.Context;
import android.util.Log;

import com.thl.book.network.FanqieApi;
import com.thl.book.network.FanqieClient;
import com.thl.book.network.dto.ChapterItem;
import com.thl.reader.db.BookList;
import com.thl.reader.db.DB;
import com.thl.reader.db.TomatoBook;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;

/**
 * Orchestrates full-book download and incremental chapter updates.
 * All public methods are synchronous — call from a background thread.
 */
public class NovelDownloadManager {

    private static final String TAG = "NovelDownloadMgr";
    // Delay between chapter requests to be polite to the proxy
    private static final long REQUEST_DELAY_MS = 200;

    public interface ProgressCallback {
        void onProgress(int downloaded, int total);
        void onComplete();
        void onError(String message);
    }

    private final FanqieApi api;

    public NovelDownloadManager(Context context) {
        this.api = new FanqieApi(FanqieClient.getProxyUrl(context));
    }

    /**
     * Download all chapters of a book and write them to outputPath (UTF-8 TXT).
     * Also saves BookList and TomatoBook records to SQLite.
     */
    public void downloadFull(String bookId, String bookName, String author,
                              String coverUrl, String outputPath,
                              ProgressCallback callback) {
        List<ChapterItem> chapters = api.getChapterList(bookId);
        if (chapters.isEmpty()) {
            callback.onError("获取章节列表失败");
            return;
        }

        File outFile = new File(outputPath);
        outFile.getParentFile().mkdirs();

        int total = chapters.size();
        int downloaded = 0;
        String lastChapterId = null;

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outFile, false), "UTF-8"))) {

            // Write book title as first line
            writer.write(bookName);
            writer.write("\r\n");
            writer.write("作者：" + author);
            writer.write("\r\n\r\n");

            for (ChapterItem chapter : chapters) {
                try {
                    String content = api.getChapterContent(chapter.itemId);
                    writer.write(HtmlToTxt.formatChapter(chapter.title, content));
                    writer.flush();
                    lastChapterId = chapter.itemId;
                    downloaded++;
                    callback.onProgress(downloaded, total);
                    Thread.sleep(REQUEST_DELAY_MS);
                } catch (Exception e) {
                    Log.w(TAG, "Failed chapter " + chapter.itemId + ": " + e.getMessage());
                    // Write placeholder so chapter index is not broken
                    writer.write("\r\n" + chapter.title + "\r\n\r\n\u3000\u3000（下载失败）\r\n");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadFull failed", e);
            callback.onError("写入文件失败: " + e.getMessage());
            return;
        }

        // Save TomatoBook record
        TomatoBook tomatoBook = new TomatoBook();
        tomatoBook.setBookId(bookId);
        tomatoBook.setBookName(bookName);
        tomatoBook.setAuthor(author);
        tomatoBook.setCoverUrl(coverUrl);
        tomatoBook.setTotalChapters(total);
        tomatoBook.setLastChapterId(lastChapterId);
        tomatoBook.setLastCheckedAt(System.currentTimeMillis());
        DB.save(tomatoBook);

        // Save BookList record
        BookList bookList = new BookList();
        bookList.setBookname(bookName);
        bookList.setBookpath(outputPath);
        bookList.setIsTomato(1);
        bookList.setTomatoBookId(bookId);
        bookList.setMsg(author + " · " + total + "章");
        bookList.setCharset("UTF-8");
        DB.save(bookList);

        callback.onComplete();
    }

    /**
     * Check for new chapters and append them to the existing TXT file.
     * Returns the number of new chapters downloaded (0 if up-to-date).
     */
    public int downloadNewChapters(BookList bookList) {
        String bookId = bookList.getTomatoBookId();
        TomatoBook meta = DB.tomatoBook().findByBookId(bookId);
        if (meta == null) return 0;
        List<ChapterItem> allChapters = api.getChapterList(bookId);
        if (allChapters.isEmpty()) return 0;

        // Find index of last downloaded chapter
        int lastIndex = -1;
        for (int i = 0; i < allChapters.size(); i++) {
            if (allChapters.get(i).itemId.equals(meta.getLastChapterId())) {
                lastIndex = i;
                break;
            }
        }

        List<ChapterItem> newChapters = (lastIndex >= 0 && lastIndex < allChapters.size() - 1)
                ? allChapters.subList(lastIndex + 1, allChapters.size())
                : (lastIndex < 0 ? allChapters : java.util.Collections.emptyList());

        if (newChapters.isEmpty()) {
            meta.setLastCheckedAt(System.currentTimeMillis());
            DB.save(meta);
            return 0;
        }

        // Append new chapters to the existing TXT file
        String lastChapterId = meta.getLastChapterId();
        int count = 0;
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(new File(bookList.getBookpath()), true), "UTF-8"))) {

            for (ChapterItem chapter : newChapters) {
                try {
                    String content = api.getChapterContent(chapter.itemId);
                    writer.write(HtmlToTxt.formatChapter(chapter.title, content));
                    writer.flush();
                    lastChapterId = chapter.itemId;
                    count++;
                    Thread.sleep(REQUEST_DELAY_MS);
                } catch (Exception e) {
                    Log.w(TAG, "Failed new chapter " + chapter.itemId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "downloadNewChapters write failed", e);
        }

        if (count > 0) {
            // Update metadata
            meta.setTotalChapters(allChapters.size());
            meta.setLastChapterId(lastChapterId);
            meta.setLastCheckedAt(System.currentTimeMillis());
            DB.save(meta);

            // Force BookUtil to re-cache on next open by clearing charset
            DB.bookList().updateCharsetAndMsg(bookList.getId(), null,
                    meta.getAuthor() + " · " + allChapters.size() + "章（新+" + count + "）");
        } else {
            meta.setLastCheckedAt(System.currentTimeMillis());
            DB.save(meta);
        }

        return count;
    }

    /** Returns the default directory for downloaded TXT files. */
    public static File getTomatoDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "tomato");
        dir.mkdirs();
        return dir;
    }
}
