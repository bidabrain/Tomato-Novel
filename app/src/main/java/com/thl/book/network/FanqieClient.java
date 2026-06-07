package com.thl.book.network;

import android.content.Context;

import com.thl.book.BuildConfig;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.concurrent.TimeUnit;

public class FanqieClient {

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
        return BuildConfig.DOWNLOADER_URL;
    }

    public static String getDownloaderPassword(Context context) {
        return BuildConfig.DOWNLOADER_PASSWORD;
    }

    /** @deprecated proxy is no longer used */
    public static String getProxyUrl(Context context) {
        return "";
    }
}
