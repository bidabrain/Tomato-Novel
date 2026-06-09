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

    public static final String KEY_CUSTOM_DOWNLOADER_ENABLED  = "custom_downloader_enabled";
    public static final String KEY_CUSTOM_DOWNLOADER_URL      = "custom_downloader_url";
    public static final String KEY_CUSTOM_DOWNLOADER_PASSWORD = "custom_downloader_password";

    public static final String KEY_CUSTOM_STORE_ENABLED = "custom_store_enabled";
    public static final String KEY_CUSTOM_STORE_URL     = "custom_store_url";

    // ── Default book store URL (GitHub Pages) ────────────────────────────────

    public static final String DEFAULT_STORE_URL =
            "https://bidabrain.github.io/FanqieRankTracker/api/lastest/all.json";

    // ── Downloader ────────────────────────────────────────────────────────────

    public static String getDownloaderUrl(Context ctx) {
        if (isCustomDownloaderEnabled(ctx)) {
            String url = SharedPreferencesUtils.getString(ctx, KEY_CUSTOM_DOWNLOADER_URL, "").trim();
            if (!url.isEmpty()) return url;
        }
        return BuildConfig.DOWNLOADER_URL;
    }

    public static String getDownloaderPassword(Context ctx) {
        if (isCustomDownloaderEnabled(ctx)) {
            return SharedPreferencesUtils.getString(ctx, KEY_CUSTOM_DOWNLOADER_PASSWORD, "");
        }
        return BuildConfig.DOWNLOADER_PASSWORD;
    }

    public static boolean isCustomDownloaderEnabled(Context ctx) {
        return SharedPreferencesUtils.getBoolean(ctx, KEY_CUSTOM_DOWNLOADER_ENABLED, false);
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
