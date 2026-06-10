package com.thl.book;

import android.content.Context;

/**
 * SharedPreferences keys and accessors for WebDAV sync configuration.
 */
public class WebDavConfig {

    public static final String KEY_WEBDAV_ENABLED  = "webdav_enabled";
    public static final String KEY_WEBDAV_URL       = "webdav_url";
    public static final String KEY_WEBDAV_USERNAME  = "webdav_username";
    public static final String KEY_WEBDAV_PASSWORD  = "webdav_password";

    /**
     * Timestamp (ms) of the last local bookshelf modification (add / delete).
     * Set to 0 on a fresh install, meaning "I haven't changed anything — take whatever is on the server."
     * Updated every time the user adds or removes a book locally.
     */
    public static final String KEY_BOOKSHELF_LOCAL_MODIFIED = "webdav_bookshelf_local_modified";

    /** Convenience: stamp the current time as the bookshelf's local modification time. */
    public static void markBookshelfModified(Context ctx) {
        SharedPreferencesUtils.saveLong(ctx, KEY_BOOKSHELF_LOCAL_MODIFIED,
                System.currentTimeMillis());
    }

    /** Remote file names written to the WebDAV base directory. */
    public static final String FILE_BOOKSHELF     = "tomato_bookshelf.json";
    public static final String FILE_PROGRESS      = "tomato_progress.json";
    public static final String FILE_READING_STATS = "tomato_reading_stats.json";

    public static boolean isEnabled(Context ctx) {
        return SharedPreferencesUtils.getBoolean(ctx, KEY_WEBDAV_ENABLED, false);
    }

    /** Returns the base URL, always ending with '/'. */
    public static String getUrl(Context ctx) {
        String url = SharedPreferencesUtils.getString(ctx, KEY_WEBDAV_URL, "").trim();
        if (!url.isEmpty() && !url.endsWith("/")) url += "/";
        return url;
    }

    public static String getUsername(Context ctx) {
        return SharedPreferencesUtils.getString(ctx, KEY_WEBDAV_USERNAME, "");
    }

    public static String getPassword(Context ctx) {
        return SharedPreferencesUtils.getString(ctx, KEY_WEBDAV_PASSWORD, "");
    }
}
