package com.thl.book.network;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.thl.book.network.dto.ChapterItem;
import com.thl.book.network.dto.SearchItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fanqie Novel API calls.
 * All methods are synchronous — call from a background thread.
 */
public class FanqieApi {

    private static final String TAG = "FanqieApi";
    private static final String SEARCH_BASE = "https://api.fanqienovel.com";
    private static final String DIR_BASE = "https://fanqienovel.com";

    private final OkHttpClient client;
    private final Gson gson;
    private final String proxyUrl;

    public FanqieApi(String proxyUrl) {
        this.client = FanqieClient.get();
        this.gson = new Gson();
        this.proxyUrl = proxyUrl;
    }

    /** Search books by keyword. Returns empty list on failure. */
    public List<SearchItem> search(String keyword) {
        HttpUrl url = HttpUrl.parse(SEARCH_BASE + "/api/author/search/search_book/v1/")
                .newBuilder()
                .addQueryParameter("query", keyword)
                .addQueryParameter("page_count", "10")
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return new ArrayList<>();
            String body = response.body().string();
            JsonObject root = gson.fromJson(body, JsonObject.class);
            JsonArray items = root
                    .getAsJsonObject("data")
                    .getAsJsonArray("search_book_data");
            List<SearchItem> result = new ArrayList<>();
            for (JsonElement el : items) {
                result.add(gson.fromJson(el, SearchItem.class));
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "search failed", e);
            return new ArrayList<>();
        }
    }

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
            JsonObject data = root.getAsJsonObject("data");

            // The key name varies across API versions
            JsonArray chapters = null;
            for (String key : new String[]{"chapterList", "chapter_list", "item_list", "allItemIds"}) {
                if (data.has(key) && data.get(key).isJsonArray()) {
                    chapters = data.getAsJsonArray(key);
                    break;
                }
            }
            if (chapters == null) return new ArrayList<>();

            List<ChapterItem> result = new ArrayList<>();
            for (JsonElement el : chapters) {
                result.add(gson.fromJson(el, ChapterItem.class));
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "getChapterList failed", e);
            return new ArrayList<>();
        }
    }

    /**
     * Download chapter content via third-party proxy.
     * Returns plain text content, or null on failure.
     * Fetches up to 20 chapters per call; here we fetch one at a time for simplicity.
     */
    public String getChapterContent(String itemId) throws IOException {
        HttpUrl url = HttpUrl.parse(proxyUrl + "/reading/reader/batch_full/v")
                .newBuilder()
                .addQueryParameter("item_ids", itemId)
                .addQueryParameter("aid", "1967")
                .build();

        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            String body = response.body().string();
            JsonObject root = gson.fromJson(body, JsonObject.class);

            // Try to find content in various response shapes
            JsonObject data = root.has("data") ? root.getAsJsonObject("data") : root;
            if (data.has("chapterData")) {
                JsonObject chapterData = data.getAsJsonArray("chapterData")
                        .get(0).getAsJsonObject();
                if (chapterData.has("content")) {
                    return chapterData.get("content").getAsString();
                }
            }
            // Fallback: look for content field directly
            if (data.has("content")) {
                return data.get("content").getAsString();
            }
            throw new IOException("Unexpected response shape for item_id=" + itemId);
        }
    }
}
