package com.thl.book.network;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.thl.book.network.dto.RankBook;
import com.thl.book.network.dto.RankCategory;

import java.util.ArrayList;
import java.util.List;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches book ranking data from the FanqieRankTracker GitHub Pages site.
 * All methods are synchronous — call from a background thread.
 */
public class BookStoreApi {
    private static final String TAG = "BookStoreApi";

    /** Fetch and parse the latest rank data. Returns null on failure. */
    public static List<RankCategory> fetchRankData(String url) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = FanqieClient.get().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "fetchRankData failed: http " + response.code());
                return null;
            }
            String body = response.body().string();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("categories")) return null;

            JsonArray categoriesJson = root.getAsJsonArray("categories");
            List<RankCategory> categories = new ArrayList<>();
            for (JsonElement catEl : categoriesJson) {
                JsonObject catObj = catEl.getAsJsonObject();
                RankCategory category = new RankCategory();
                category.name = getString(catObj, "name");
                category.allBooks = new ArrayList<>();

                if (catObj.has("books") && catObj.get("books").isJsonArray()) {
                    for (JsonElement bookEl : catObj.getAsJsonArray("books")) {
                        if (!bookEl.isJsonObject()) continue;
                        JsonObject bookObj = bookEl.getAsJsonObject();
                        RankBook book = new RankBook();
                        book.title = getString(bookObj, "title");
                        book.author = getString(bookObj, "author");
                        book.reads = getString(bookObj, "reads");
                        book.intro = getString(bookObj, "intro");
                        book.cover = getString(bookObj, "cover");
                        book.url = getString(bookObj, "url");
                        category.allBooks.add(book);
                    }
                }
                if (!category.allBooks.isEmpty()) {
                    categories.add(category);
                }
            }
            return categories;
        } catch (Exception e) {
            Log.e(TAG, "fetchRankData exception", e);
            return null;
        }
    }

    private static String getString(JsonObject obj, String key) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : "";
    }
}
