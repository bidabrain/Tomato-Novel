package com.thl.book.network;

import android.content.Context;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.concurrent.TimeUnit;

public class FanqieClient {

    // ── 服务器配置（固化，修改方式见 README） ─────────────────────────────────
    private static final String DOWNLOADER_URL      = "https://fanqie.meegocloud.pp.ua";
    private static final String DOWNLOADER_PASSWORD = "sakura";
    // ─────────────────────────────────────────────────────────────────────────

    private static OkHttpClient sClient;

    public static OkHttpClient get() {
        if (sClient == null) {
            sClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(chain -> {
                        Request req = chain.request().newBuilder()
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                                .build();
                        return chain.proceed(req);
                    })
                    .build();
        }
        return sClient;
    }

    public static String getDownloaderUrl(Context context) {
        return DOWNLOADER_URL;
    }

    public static String getDownloaderPassword(Context context) {
        return DOWNLOADER_PASSWORD;
    }

    /** @deprecated proxy is no longer used */
    public static String getProxyUrl(Context context) {
        return "";
    }
}
