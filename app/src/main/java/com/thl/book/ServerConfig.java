package com.thl.book;

import android.content.Context;

/**
 * Single source of truth for server addresses and credentials.
 *
 * Each service has an independent "use custom" toggle stored in SharedPreferences.
 * When the toggle is on AND a non-empty value is saved, the custom value is returned.
 * Otherwise the built-in value compiled into BuildConfig is used as fallback.
 */
public class ServerConfig {

    // ── SharedPreferences keys ────────────────────────────────────────────────

    public static final String KEY_CUSTOM_STORE_ENABLED = "custom_store_enabled";
    public static final String KEY_CUSTOM_STORE_URL     = "custom_store_url";

    // ── Default book store URL (GitHub Pages) ────────────────────────────────

    public static final String DEFAULT_STORE_URL =
            "https://bidabrain.github.io/FanqieRankTracker/api/lastest/all.json";

    // ── Downloader（内嵌服务器，固定 localhost）────────────────────────────────

    public static String getDownloaderUrl(Context ctx) {
        return "http://127.0.0.1:18423";
    }

    public static String getDownloaderPassword(Context ctx) {
        return "";
    }

    // ── Book store ────────────────────────────────────────────────────────────

    public static String getStoreUrl(Context ctx) {
        if (isCustomStoreEnabled(ctx)) {
            String url = SharedPreferencesUtils.getString(ctx, KEY_CUSTOM_STORE_URL, "").trim();
            if (!url.isEmpty()) return url;
        }
        return DEFAULT_STORE_URL;
    }

    public static boolean isCustomStoreEnabled(Context ctx) {
        return SharedPreferencesUtils.getBoolean(ctx, KEY_CUSTOM_STORE_ENABLED, false);
    }
}
