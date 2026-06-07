package com.thl.book.download;

import android.content.Context;
import android.util.Log;

import com.google.gson.JsonObject;
import com.thl.book.network.FanqieApi;
import com.thl.book.network.FanqieClient;
import com.thl.book.network.dto.ChapterItem;
import com.thl.reader.db.BookList;
import com.thl.reader.db.DB;
import com.thl.reader.db.TomatoBook;

import java.io.File;
import java.util.List;

/**
 * Orchestrates full-book download via Tomato-Novel-Downloader jobs API.
 * All public methods are synchronous — call from a background thread.
 */
public class NovelDownloadManager {

    private static final String TAG = "NovelDownloadMgr";

    public interface ProgressCallback {
        void onProgress(int downloaded, int total);
        void onComplete();
        void onError(String message);
        /** Called to show a status message (e.g. waiting in queue). Default: no-op. */
        default void onStatus(String message) {}
    }

    private final FanqieApi api;

    public NovelDownloadManager(Context context) {
        this.api = new FanqieApi(
                FanqieClient.getProxyUrl(context),
                FanqieClient.getDownloaderUrl(context),
                FanqieClient.getDownloaderPassword(context)
        );
    }

    /**
     * Download a full book via the Downloader's jobs API, save as TXT,
     * and add to the bookshelf DB.
     */
    public void downloadFull(String bookId, String bookName, String author,
                              String coverUrl, String outputPath,
                              ProgressCallback callback) {

        // ── 1. 创建下载任务 ───────────────────────────────────────────────────
        long jobId = api.createJob(bookId);
        if (jobId == -2) {
            // Server busy — wait for existing job to finish, then retry
            callback.onStatus("服务器有其他任务正在排队，请稍候…");
            api.waitForActiveJobToFinish();
            callback.onStatus("正在下载《" + bookName + "》…");
            jobId = api.createJob(bookId);
        }
        if (jobId < 0) {
            callback.onError("创建下载任务失败，请检查服务器连接和密码");
            return;
        }
        Log.d(TAG, "job created id=" + jobId);

        // ── 2. 轮询任务状态 ───────────────────────────────────────────────────
        String jobTitle = bookName;
        String jobAuthor = author;
        int totalChapters = 0;

        int nullStreak = 0;
        for (int tick = 0; tick < 150; tick++) { // 最多等 5 分钟
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }

            JsonObject job = api.getJobInfo(jobId);
            if (job == null) {
                if (++nullStreak >= 3) {
                    callback.onError("连续获取任务状态失败，请检查网络");
                    return;
                }
                continue; // 单次网络抖动，跳过本轮
            }
            nullStreak = 0;

            // 更新书名 / 作者
            if (job.has("title") && !job.get("title").isJsonNull())
                jobTitle = job.get("title").getAsString();
            if (job.has("author") && !job.get("author").isJsonNull())
                jobAuthor = job.get("author").getAsString();

            // 更新进度
            if (job.has("progress") && !job.get("progress").isJsonNull()) {
                JsonObject prog = job.getAsJsonObject("progress");
                int done = prog.has("saved_chapters") ? prog.get("saved_chapters").getAsInt() : 0;
                int total = prog.has("chapter_total") ? prog.get("chapter_total").getAsInt() : 0;
                if (total > 0) {
                    totalChapters = total;
                    callback.onProgress(done, total);
                }
            }

            // 处理格式选择（选 txt）
            if (job.has("format_options") && !job.get("format_options").isJsonNull()) {
                Log.d(TAG, "job waiting for format choice, submitting txt");
                api.submitFormatChoice(jobId);
                continue;
            }

            // 处理书名选择（用默认值）
            if (job.has("book_name_options") && !job.get("book_name_options").isJsonNull()) {
                Log.d(TAG, "job waiting for book_name choice, submitting default");
                api.submitBookNameChoice(jobId);
                continue;
            }

            String state = job.has("state") ? job.get("state").getAsString() : "";
            Log.d(TAG, "job state=" + state);

            if ("done".equalsIgnoreCase(state)) break;

            if ("failed".equalsIgnoreCase(state)) {
                String msg = job.has("message") && !job.get("message").isJsonNull()
                        ? job.get("message").getAsString() : "下载失败";
                callback.onError(msg);
                return;
            }

            if ("canceled".equalsIgnoreCase(state)) {
                callback.onError("下载已取消");
                return;
            }
        }

        // ── 3. 在库中找到生成的 TXT 文件 ─────────────────────────────────────
        callback.onProgress(totalChapters, totalChapters > 0 ? totalChapters : 1);
        callback.onStatus("服务器已完成，正在获取文件…");
        String relPath = api.findLibraryFile(jobTitle);
        if (relPath == null) {
            callback.onError("在服务器库中找不到下载完成的文件，请检查服务器格式设置（需为 txt）");
            return;
        }
        Log.d(TAG, "found library file: " + relPath);

        // ── 4. 下载 TXT 到本地 ────────────────────────────────────────────────
        callback.onStatus("正在下载到本地…");
        boolean ok = api.downloadFileToPath(relPath, outputPath);
        if (!ok) {
            callback.onError("从服务器下载文件失败");
            return;
        }

        // ── 5. 写入数据库 ─────────────────────────────────────────────────────
        TomatoBook tomatoBook = new TomatoBook();
        tomatoBook.setBookId(bookId);
        tomatoBook.setBookName(jobTitle);
        tomatoBook.setAuthor(jobAuthor);
        tomatoBook.setCoverUrl(coverUrl);
        tomatoBook.setTotalChapters(totalChapters);
        tomatoBook.setLastChapterId(null);
        tomatoBook.setLastCheckedAt(System.currentTimeMillis());
        DB.save(tomatoBook);

        BookList bookList = new BookList();
        bookList.setBookname(jobTitle);
        bookList.setBookpath(outputPath);
        bookList.setIsTomato(1);
        bookList.setTomatoBookId(bookId);
        bookList.setMsg(jobAuthor + (totalChapters > 0 ? " · " + totalChapters + "章" : ""));
        bookList.setCharset("UTF-8");
        DB.save(bookList);

        callback.onComplete();
    }

    /**
     * Check for new chapters. With the Downloader-based approach,
     * re-submit a job and replace the file.
     */
    public int downloadNewChapters(BookList bookList) {
        String bookId = bookList.getTomatoBookId();
        if (bookId == null) return 0;

        TomatoBook meta = DB.tomatoBook().findByBookId(bookId);
        if (meta == null) return 0;

        // Get current chapter count from fanqienovel.com directory
        List<ChapterItem> allChapters = api.getChapterList(bookId);
        if (allChapters.isEmpty()) return 0;

        int latestTotal = allChapters.size();
        if (latestTotal <= meta.getTotalChapters()) {
            meta.setLastCheckedAt(System.currentTimeMillis());
            DB.save(meta);
            return 0;
        }

        // New chapters exist — re-download full book via Downloader
        long jobId = api.createJob(bookId);
        if (jobId == -2) {
            api.waitForActiveJobToFinish();
            jobId = api.createJob(bookId);
        }
        if (jobId < 0) return 0;

        int nullStreak = 0;
        for (int tick = 0; tick < 150; tick++) { // 最多等 5 分钟
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            JsonObject job = api.getJobInfo(jobId);
            if (job == null) {
                if (++nullStreak >= 3) return 0;
                continue;
            }
            nullStreak = 0;

            if (job.has("format_options") && !job.get("format_options").isJsonNull())
                api.submitFormatChoice(jobId);
            if (job.has("book_name_options") && !job.get("book_name_options").isJsonNull())
                api.submitBookNameChoice(jobId);

            String state = job.has("state") ? job.get("state").getAsString() : "";
            if ("done".equalsIgnoreCase(state)) break;
            if ("failed".equalsIgnoreCase(state) || "canceled".equalsIgnoreCase(state)) return 0;
        }

        String jobTitle = meta.getBookName();
        String relPath = api.findLibraryFile(jobTitle);
        if (relPath == null) return 0;

        boolean ok = api.downloadFileToPath(relPath, bookList.getBookpath());
        if (!ok) return 0;

        int newCount = latestTotal - meta.getTotalChapters();
        meta.setTotalChapters(latestTotal);
        meta.setLastCheckedAt(System.currentTimeMillis());
        DB.save(meta);

        DB.bookList().updateCharsetAndMsg(bookList.getId(), null,
                meta.getAuthor() + " · " + latestTotal + "章（新+" + newCount + "）");

        return newCount;
    }

    /** Returns the default directory for downloaded TXT files. */
    public static File getTomatoDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "tomato");
        dir.mkdirs();
        return dir;
    }
}
