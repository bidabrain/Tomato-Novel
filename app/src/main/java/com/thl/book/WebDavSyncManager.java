package com.thl.book;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.thl.reader.db.BookList;
import com.thl.reader.db.DB;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * WebDAV-based sync for bookshelf and reading progress.
 *
 * Two files are kept on the WebDAV server:
 *   tomato_bookshelf.json — full List<BookList> (same format as local export)
 *   tomato_progress.json  — lightweight List<BookProgress> (id + position only)
 *
 * Conflict resolution: compare the file's WebDAV Last-Modified date against
 * the timestamp stored locally from the last successful push.
 *   remote newer → download and apply to local DB
 *   local  newer (or remote missing) → upload to server
 *   equal  → no-op
 *
 * Must be called from a background thread.
 */
public class WebDavSyncManager {

    private static final String TAG = "WebDavSync";
    private static final MediaType JSON_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\"?>"
            + "<D:propfind xmlns:D=\"DAV:\">"
            + "<D:prop><D:getlastmodified/></D:prop>"
            + "</D:propfind>";

    // ── Public API ─────────────────────────────────────────────────────────────

    public interface SyncCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    /** Bookshelf snapshot written to tomato_bookshelf.json. */
    public static class BookshelfSnapshot {
        public long modifiedAt;
        public List<BookList> books;
        BookshelfSnapshot(long modifiedAt, List<BookList> books) {
            this.modifiedAt = modifiedAt;
            this.books = books;
        }
    }

    /** Lightweight progress record stored in tomato_progress.json. */
    public static class BookProgress {
        public String tomatoBookId;
        public String bookpath;
        public String bookname;
        public long begin;
        public long lastReadAt;
    }

    /**
     * Perform a full bidirectional sync (bookshelf + progress).
     *
     * Bookshelf — last-write-wins (full replacement):
     *   Compare localModifiedAt (updated on every local add/delete) vs remote Last-Modified.
     *   Newer side wins and completely replaces the other.
     *   This ensures deletions propagate in both directions.
     *   Default localModifiedAt = 0 means "fresh install, take whatever is on the server".
     *
     * Progress — per-book merge:
     *   Always download remote, update any book whose remote lastReadAt is newer, then upload.
     *   Progress has no concept of deletion so a merge is always safe.
     *
     * Must be called from a background thread; callback is invoked on the same thread.
     */
    public static void sync(Context context, SyncCallback callback) {
        String baseUrl  = WebDavConfig.getUrl(context);
        String username = WebDavConfig.getUsername(context);
        String password = WebDavConfig.getPassword(context);

        if (baseUrl.isEmpty()) {
            callback.onError("WebDAV 地址未配置，请先在服务器设置中填写");
            return;
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        String auth = Credentials.basic(username, password);
        Gson gson = new Gson();
        StringBuilder log = new StringBuilder();

        try {
            // ── 1. Sync bookshelf (last-write-wins, timestamp embedded in JSON) ─
            String bookshelfUrl = baseUrl + WebDavConfig.FILE_BOOKSHELF;
            long localBookshelfModified = SharedPreferencesUtils.getLong(
                    context, WebDavConfig.KEY_BOOKSHELF_LOCAL_MODIFIED, 0L);

            // PROPFIND only to check connectivity and file existence (not for timestamp)
            long remoteExists = getRemoteLastModified(client, auth, bookshelfUrl);
            if (remoteExists < 0) {
                throw new Exception("无法连接 WebDAV 服务器，请检查地址和账号密码");
            }

            List<BookList> localBooks;
            if (remoteExists > 0) {
                // File exists: download and read the embedded timestamp
                String remoteJson = getFile(client, auth, bookshelfUrl);
                BookshelfSnapshot snapshot = parseSnapshot(remoteJson, gson);
                if (snapshot.modifiedAt > localBookshelfModified) {
                    // Remote is newer → apply to local
                    int[] result = replaceLocalBookshelf(snapshot);
                    SharedPreferencesUtils.saveLong(context,
                            WebDavConfig.KEY_BOOKSHELF_LOCAL_MODIFIED, snapshot.modifiedAt);
                    log.append("书架已从服务器同步（新增 ").append(result[0])
                            .append(" 本，移除 ").append(result[1]).append(" 本）\n");
                } else {
                    // Local is newer or equal → upload
                    localBooks = DB.bookList().findAll();
                    putFile(client, auth, bookshelfUrl,
                            gson.toJson(new BookshelfSnapshot(localBookshelfModified, localBooks)));
                    log.append("书架已上传到服务器\n");
                }
            } else {
                // Remote file doesn't exist → upload local bookshelf
                localBooks = DB.bookList().findAll();
                putFile(client, auth, bookshelfUrl,
                        gson.toJson(new BookshelfSnapshot(localBookshelfModified, localBooks)));
                log.append("书架已上传到服务器\n");
            }

            // ── 2. Sync reading progress (per-book merge) ──────────────────────
            String progressUrl = baseUrl + WebDavConfig.FILE_PROGRESS;
            localBooks = DB.bookList().findAll();

            long remoteProgressTime = getRemoteLastModified(client, auth, progressUrl);
            if (remoteProgressTime < 0) {
                throw new Exception("无法连接 WebDAV 服务器");
            }
            if (remoteProgressTime > 0) {
                // Remote file exists: merge into local (take newer progress per book)
                String remoteJson = getFile(client, auth, progressUrl);
                int updated = applyProgress(remoteJson, gson);
                if (updated > 0) {
                    log.append("进度：从服务器更新 ").append(updated).append(" 本\n");
                    localBooks = DB.bookList().findAll();
                }
            }
            // Always upload current (merged) progress
            putFile(client, auth, progressUrl, gson.toJson(buildProgressList(localBooks)));
            log.append("阅读进度已上传到服务器\n");

            callback.onSuccess(log.toString().trim());

        } catch (Exception e) {
            Log.e(TAG, "WebDAV sync error", e);
            callback.onError("同步失败：" + e.getMessage());
        }
    }

    // ── WebDAV primitives ──────────────────────────────────────────────────────

    /**
     * PROPFIND to retrieve the Last-Modified timestamp of a remote file.
     * @return last-modified in ms; 0 if the file doesn't exist (404); -1 on network/auth error
     */
    private static long getRemoteLastModified(OkHttpClient client, String auth, String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .method("PROPFIND", RequestBody.create(
                            PROPFIND_BODY, MediaType.get("text/xml; charset=utf-8")))
                    .header("Authorization", auth)
                    .header("Depth", "0")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.code() == 404) return 0;
                if (response.code() != 207 && !response.isSuccessful()) return -1;
                String xml = response.body() != null ? response.body().string() : "";
                return parseLastModified(xml);
            }
        } catch (Exception e) {
            Log.w(TAG, "PROPFIND failed for " + url + ": " + e.getMessage());
            return -1;
        }
    }

    /** Extract getlastmodified value from a WebDAV multistatus XML response. */
    private static long parseLastModified(String xml) {
        try {
            // Match any namespace prefix: <D:getlastmodified>, <lp1:getlastmodified>, etc.
            int start = xml.indexOf("getlastmodified>");
            if (start < 0) return 0;
            start += "getlastmodified>".length();
            int end = xml.indexOf("<", start);
            if (end < 0) return 0;
            String dateStr = xml.substring(start, end).trim();
            // RFC 1123: "Tue, 10 Jun 2026 10:00:00 GMT"
            SimpleDateFormat sdf = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            Date date = sdf.parse(dateStr);
            return date != null ? date.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void putFile(OkHttpClient client, String auth, String url, String content)
            throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .put(RequestBody.create(content, JSON_TYPE))
                .header("Authorization", auth)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("上传失败 (HTTP " + response.code() + ")");
            }
        }
    }

    private static String getFile(OkHttpClient client, String auth, String url)
            throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", auth)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("下载失败 (HTTP " + response.code() + ")");
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    // ── Data helpers ───────────────────────────────────────────────────────────

    /**
     * Parse remote JSON into a BookshelfSnapshot.
     * Supports both new format {"modifiedAt":...,"books":[...]}
     * and legacy format (plain array [...]).
     */
    private static BookshelfSnapshot parseSnapshot(String json, Gson gson) {
        try {
            BookshelfSnapshot s = gson.fromJson(json, BookshelfSnapshot.class);
            if (s != null && s.books != null) return s;
        } catch (Exception ignored) {}
        // Legacy: plain array
        try {
            List<BookList> books = gson.fromJson(json,
                    new TypeToken<List<BookList>>() {}.getType());
            return new BookshelfSnapshot(0, books != null ? books : new ArrayList<>());
        } catch (Exception ignored) {}
        return new BookshelfSnapshot(0, new ArrayList<>());
    }

    private static List<BookProgress> buildProgressList(List<BookList> books) {
        List<BookProgress> list = new ArrayList<>(books.size());
        for (BookList book : books) {
            BookProgress p = new BookProgress();
            p.tomatoBookId = book.getTomatoBookId();
            p.bookpath     = book.getBookpath();
            p.bookname     = book.getBookname();
            p.begin        = book.getBegin();
            p.lastReadAt   = book.getLastReadAt();
            list.add(p);
        }
        return list;
    }

    /**
     * Fully replace the local bookshelf with the remote one (last-write-wins).
     *
     * - Books in remote but not local → add (Tomato: inserted as "待下载"; local: only if file exists)
     * - Books in local but not remote → remove from DB (Tomato: also delete the downloaded TXT file)
     * - Books present in both → keep as-is (progress is handled separately)
     *
     * @return int[]{added, removed}
     */
    private static int[] replaceLocalBookshelf(BookshelfSnapshot snapshot) {
        List<BookList> remote = snapshot.books;
        if (remote == null) return new int[]{0, 0};

        List<BookList> local = DB.bookList().findAll();
        int added = 0, removed = 0;

        // Build lookup sets for quick membership checks
        java.util.Set<String> remoteTomatoIds = new java.util.HashSet<>();
        java.util.Set<String> remotePaths     = new java.util.HashSet<>();
        for (BookList b : remote) {
            if (b.getIsTomato() == 1 && b.getTomatoBookId() != null)
                remoteTomatoIds.add(b.getTomatoBookId());
            else if (b.getBookpath() != null)
                remotePaths.add(b.getBookpath());
        }

        // Remove local books that are absent from remote
        for (BookList b : local) {
            boolean inRemote = b.getIsTomato() == 1
                    ? remoteTomatoIds.contains(b.getTomatoBookId())
                    : b.getBookpath() != null && remotePaths.contains(b.getBookpath());
            if (!inRemote) {
                DB.bookList().deleteById(b.getId());
                // For Tomato books, also delete the downloaded TXT file
                if (b.getIsTomato() == 1 && b.getBookpath() != null)
                    new File(b.getBookpath()).delete();
                removed++;
            }
        }

        // Add remote books missing from local
        java.util.Set<String> localTomatoIds = new java.util.HashSet<>();
        java.util.Set<String> localPaths     = new java.util.HashSet<>();
        for (BookList b : local) {
            if (b.getIsTomato() == 1 && b.getTomatoBookId() != null)
                localTomatoIds.add(b.getTomatoBookId());
            else if (b.getBookpath() != null)
                localPaths.add(b.getBookpath());
        }

        for (BookList book : remote) {
            if (book.getIsTomato() == 1) {
                if (book.getTomatoBookId() == null || book.getTomatoBookId().isEmpty()) continue;
                if (!localTomatoIds.contains(book.getTomatoBookId())) {
                    book.setId(0);
                    book.setBookpath(null);
                    book.setMsg("待下载");
                    DB.bookList().insert(book);
                    added++;
                }
            } else {
                if (book.getBookpath() == null) continue;
                if (!new File(book.getBookpath()).exists()) continue;
                if (!localPaths.contains(book.getBookpath())) {
                    book.setId(0);
                    DB.bookList().insert(book);
                    added++;
                }
            }
        }

        return new int[]{added, removed};
    }

    /**
     * Apply remote progress entries to the local DB.
     * Only updates a book when the remote lastReadAt is strictly newer.
     * @return number of books whose progress was updated
     */
    private static int applyProgress(String json, Gson gson) {
        List<BookProgress> remoteList;
        try {
            remoteList = gson.fromJson(json,
                    new TypeToken<List<BookProgress>>() {}.getType());
        } catch (Exception e) {
            return 0;
        }
        if (remoteList == null) return 0;

        int updated = 0;
        for (BookProgress p : remoteList) {
            BookList local = null;
            if (p.tomatoBookId != null && !p.tomatoBookId.isEmpty()) {
                List<BookList> found = DB.bookList().findByTomatoBookId(p.tomatoBookId);
                if (found != null && !found.isEmpty()) local = found.get(0);
            } else if (p.bookpath != null && !p.bookpath.isEmpty()) {
                local = DB.bookList().findByBookpath(p.bookpath);
            }
            if (local != null && p.lastReadAt > local.getLastReadAt()) {
                DB.bookList().updateBegin(local.getId(), p.begin, p.lastReadAt);
                updated++;
            }
        }
        return updated;
    }
}
