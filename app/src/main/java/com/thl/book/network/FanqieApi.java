package com.thl.book.network;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.thl.book.network.dto.ChapterItem;
import com.thl.book.network.dto.SearchItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Fanqie Novel API calls.
 * All methods are synchronous — call from a background thread.
 */
public class FanqieApi {

    private static final String TAG = "FanqieApi";
    private static final String DIR_BASE = "https://fanqienovel.com";

    private final OkHttpClient client;
    private final Gson gson;
    private final String proxyUrl;
    private final String downloaderUrl;
    private final String downloaderPassword;

    public FanqieApi(String proxyUrl, String downloaderUrl, String downloaderPassword) {
        this.client = FanqieClient.get();
        this.gson = new Gson();
        this.proxyUrl = proxyUrl;
        // 去掉末尾斜杠，防止拼出 //api/jobs
        this.downloaderUrl = downloaderUrl != null
                ? downloaderUrl.trim().replaceAll("/+$", "") : "";
        this.downloaderPassword = downloaderPassword == null ? "" : downloaderPassword;
    }

    // ── 搜索 ──────────────────────────────────────────────────────────────────

    /**
     * Search books via Tomato-Novel-Downloader instance.
     * Returns null on network/connection failure, empty list when the server
     * responded successfully but found no results.
     */
    public List<SearchItem> search(String keyword) {
        HttpUrl url = HttpUrl.parse(downloaderUrl + "/api/search")
                .newBuilder()
                .addQueryParameter("q", keyword)
                .build();

        try (Response response = client.newCall(authReq(url.toString()).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return new ArrayList<>();
            String body = response.body().string();
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (!root.has("items")) return new ArrayList<>();
            JsonArray items = root.getAsJsonArray("items");
            List<SearchItem> result = new ArrayList<>();
            for (JsonElement el : items) {
                JsonObject obj = el.getAsJsonObject();
                SearchItem item = new SearchItem();
                item.bookId = obj.has("book_id") ? obj.get("book_id").getAsString() : null;
                item.bookName = obj.has("title") ? obj.get("title").getAsString() : null;
                item.author = obj.has("author") ? obj.get("author").getAsString() : null;
                if (obj.has("raw") && obj.get("raw").isJsonObject()) {
                    JsonObject raw = obj.getAsJsonObject("raw");
                    if (raw.has("thumb_url") && !raw.get("thumb_url").isJsonNull())
                        item.coverUrl = raw.get("thumb_url").getAsString();
                    if (raw.has("abstract") && !raw.get("abstract").isJsonNull())
                        item.summary = raw.get("abstract").getAsString();
                    if (raw.has("serial_count") && !raw.get("serial_count").isJsonNull())
                        item.chapterCount = raw.get("serial_count").getAsInt();
                    if (raw.has("word_number") && !raw.get("word_number").isJsonNull())
                        item.wordNumber = raw.get("word_number").getAsLong();
                    if (raw.has("update_status") && !raw.get("update_status").isJsonNull())
                        item.updateStatus = raw.get("update_status").getAsInt();
                    if (raw.has("score") && !raw.get("score").isJsonNull())
                        item.score = raw.get("score").getAsString();
                    if (raw.has("category") && !raw.get("category").isJsonNull())
                        item.category = raw.get("category").getAsString();
                }
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            // Network or connection error — return null so callers can show the right message
            Log.e(TAG, "search failed", e);
            return null;
        }
    }

    // ── Downloader Jobs API ───────────────────────────────────────────────────

    /**
     * POST /api/jobs {"book_id": bookId} → job id, -1 on failure, -2 if server busy (429).
     */
    public long createJob(String bookId) {
        String json = "{\"book_id\":\"" + bookId + "\"}";
        Request req = authReq(downloaderUrl + "/api/jobs")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "(null)";
            Log.d(TAG, "createJob http=" + resp.code() + " body=" + body);

            if (resp.code() == 429) {
                Log.d(TAG, "createJob 429: server busy");
                return -2; // caller should wait then retry
            }
            if (!resp.isSuccessful()) return -1;
            JsonObject obj = gson.fromJson(body, JsonObject.class);
            return obj.has("id") ? obj.get("id").getAsLong() : -1;
        } catch (Exception e) {
            Log.e(TAG, "createJob exception", e);
            return -1;
        }
    }

    /**
     * Block until no queued/running jobs remain on the server, or timeout (~20 min).
     */
    public void waitForActiveJobToFinish() {
        for (int tick = 0; tick < 600; tick++) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            long activeId = findActiveJob();
            if (activeId < 0) {
                Log.d(TAG, "waitForActiveJobToFinish: no active jobs, proceeding");
                return;
            }
            Log.d(TAG, "waitForActiveJobToFinish: job " + activeId + " still running, tick=" + tick);
        }
    }

    /** GET /api/jobs → return id of first Queued/Running job, or -1. */
    private long findActiveJob() {
        Request req = authReq(downloaderUrl + "/api/jobs").build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return -1;
            JsonObject root = gson.fromJson(resp.body().string(), JsonObject.class);
            if (!root.has("items")) return -1;
            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject job = el.getAsJsonObject();
                String state = job.has("state") ? job.get("state").getAsString() : "";
                if ("queued".equalsIgnoreCase(state) || "running".equalsIgnoreCase(state)) {
                    long id = job.get("id").getAsLong();
                    Log.d(TAG, "reusing active job id=" + id + " state=" + state);
                    return id;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findActiveJob exception", e);
        }
        return -1;
    }

    /** GET /api/jobs?id=<id> → job JsonObject, or null on failure. */
    public JsonObject getJobInfo(long jobId) {
        HttpUrl url = HttpUrl.parse(downloaderUrl + "/api/jobs")
                .newBuilder()
                .addQueryParameter("id", String.valueOf(jobId))
                .build();
        try (Response resp = client.newCall(authReq(url.toString()).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JsonObject root = gson.fromJson(resp.body().string(), JsonObject.class);
            if (!root.has("items") || root.getAsJsonArray("items").size() == 0) return null;
            return root.getAsJsonArray("items").get(0).getAsJsonObject();
        } catch (Exception e) {
            Log.e(TAG, "getJobInfo exception", e);
            return null;
        }
    }

    /** POST /api/jobs/{id}/format {"value": "txt"} */
    public void submitFormatChoice(long jobId) {
        String json = "{\"value\":\"txt\"}";
        Request req = authReq(downloaderUrl + "/api/jobs/" + jobId + "/format")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            Log.d(TAG, "submitFormatChoice http=" + resp.code());
        } catch (Exception e) {
            Log.e(TAG, "submitFormatChoice exception", e);
        }
    }

    /** POST /api/jobs/{id}/book_name {"value": null} → use default name */
    public void submitBookNameChoice(long jobId) {
        Request req = authReq(downloaderUrl + "/api/jobs/" + jobId + "/book_name")
                .post(RequestBody.create("{\"value\":null}", MediaType.parse("application/json")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            Log.d(TAG, "submitBookNameChoice http=" + resp.code());
        } catch (Exception e) {
            Log.e(TAG, "submitBookNameChoice exception", e);
        }
    }

    /**
     * GET /api/library?name={title} → rel_path of matching TXT file, null if not found.
     * Retries a few times to allow async library scan to complete.
     */
    public String findLibraryFile(String title) {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
            HttpUrl url = HttpUrl.parse(downloaderUrl + "/api/library")
                    .newBuilder()
                    .addQueryParameter("name", title)
                    .build();
            try (Response resp = client.newCall(authReq(url.toString()).build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) continue;
                JsonObject root = gson.fromJson(resp.body().string(), JsonObject.class);
                if (!root.has("items")) continue;
                JsonArray items = root.getAsJsonArray("items");
                // 优先找直接的 TXT 文件
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    String kind = item.has("kind") ? item.get("kind").getAsString() : "";
                    String ext = item.has("ext") ? item.get("ext").getAsString() : "";
                    if ("file".equals(kind) && "txt".equals(ext)) {
                        return item.get("rel_path").getAsString();
                    }
                }
                // 如果是目录（书名子目录），进去找 TXT
                for (JsonElement el : items) {
                    JsonObject item = el.getAsJsonObject();
                    if ("dir".equals(item.has("kind") ? item.get("kind").getAsString() : "")) {
                        String subPath = findTxtInDir(item.get("rel_path").getAsString(), title);
                        if (subPath != null) return subPath;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "findLibraryFile exception", e);
            }
        }
        return null;
    }

    private String findTxtInDir(String dirRelPath, String title) {
        HttpUrl url = HttpUrl.parse(downloaderUrl + "/api/library")
                .newBuilder()
                .addQueryParameter("path", dirRelPath)
                .addQueryParameter("name", title)
                .build();
        try (Response resp = client.newCall(authReq(url.toString()).build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) return null;
            JsonObject root = gson.fromJson(resp.body().string(), JsonObject.class);
            if (!root.has("items")) return null;
            for (JsonElement el : root.getAsJsonArray("items")) {
                JsonObject item = el.getAsJsonObject();
                if ("file".equals(item.has("kind") ? item.get("kind").getAsString() : "")
                        && "txt".equals(item.has("ext") ? item.get("ext").getAsString() : "")) {
                    return item.get("rel_path").getAsString();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findTxtInDir exception", e);
        }
        return null;
    }

    /** GET /download/{relPath} → save to outputPath. Returns true on success. */
    public boolean downloadFileToPath(String relPath, String outputPath) {
        String encodedPath = relPath.replace(" ", "%20");
        Request req = authReq(downloaderUrl + "/download/" + encodedPath).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                Log.e(TAG, "downloadFileToPath failed: http " + resp.code());
                return false;
            }
            File outFile = new File(outputPath);
            outFile.getParentFile().mkdirs();
            try (InputStream in = resp.body().byteStream();
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "downloadFileToPath exception", e);
            return false;
        }
    }

    // ── 章节列表（目录页直接获取，不需要认证）────────────────────────────────

    /** Get ordered chapter list for a book. Returns empty list on failure. */
    public List<ChapterItem> getChapterList(String bookId) {
        HttpUrl url = HttpUrl.parse(DIR_BASE + "/api/reader/directory/detail")
                .newBuilder()
                .addQueryParameter("bookId", bookId)
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return new ArrayList<>();
            String body = response.body().string();
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (!root.has("data") || root.get("data").isJsonNull()) return new ArrayList<>();
            JsonObject data = root.getAsJsonObject("data");

            // chapterListWithVolume: array of volumes, each volume is an array of chapter objects
            if (data.has("chapterListWithVolume") && data.get("chapterListWithVolume").isJsonArray()) {
                JsonArray volumes = data.getAsJsonArray("chapterListWithVolume");
                List<ChapterItem> result = new ArrayList<>();
                for (JsonElement vol : volumes) {
                    if (vol.isJsonArray()) {
                        for (JsonElement el : vol.getAsJsonArray()) {
                            if (el.isJsonObject()) result.add(gson.fromJson(el, ChapterItem.class));
                        }
                    } else if (vol.isJsonObject()) {
                        result.add(gson.fromJson(vol, ChapterItem.class));
                    }
                }
                if (!result.isEmpty()) return result;
            }

            // Fallback
            for (String key : new String[]{"chapterList", "chapter_list", "item_list"}) {
                if (data.has(key) && data.get(key).isJsonArray()) {
                    List<ChapterItem> result = new ArrayList<>();
                    for (JsonElement el : data.getAsJsonArray(key)) {
                        if (el.isJsonObject()) result.add(gson.fromJson(el, ChapterItem.class));
                    }
                    if (!result.isEmpty()) return result;
                }
            }
            return new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "getChapterList exception", e);
            return new ArrayList<>();
        }
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────

    private Request.Builder authReq(String url) {
        Request.Builder b = new Request.Builder().url(url);
        if (!downloaderPassword.isEmpty()) {
            b.header("x-tomato-password", downloaderPassword);
        }
        return b;
    }
}
