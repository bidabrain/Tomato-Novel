package com.thl.book.network;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.util.concurrent.TimeUnit;

public class FanqieClient {

    // Default proxy - can be overridden in Settings
    public static final String DEFAULT_PROXY = "https://api.cenguigui.cn";
    private static final String PREF_NAME = "tomato_prefs";
    private static final String KEY_PROXY = "fanqie_proxy";

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

    public static String getProxyUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PROXY, DEFAULT_PROXY);
    }

    public static void setProxyUrl(Context context, String url) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PROXY, url).apply();
    }
}
