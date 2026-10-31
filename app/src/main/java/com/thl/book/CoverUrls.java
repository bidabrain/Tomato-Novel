package com.thl.book;

import android.net.Uri;

import com.bumptech.glide.load.model.GlideUrl;

/**
 * 番茄封面是带签名的临时链接（?x-expires=…&x-signature=…，有效期只有几天）。
 * 书架保存的旧链接过期后会 403，封面就显示成默认图。
 */
public final class CoverUrls {

    /** 提前一小时视为过期，避免刚刷新完就失效 */
    private static final long EXPIRE_MARGIN_SECONDS = 3600;

    private CoverUrls() {}

    /** 链接带 x-expires 且已过期（或即将过期） */
    public static boolean isExpired(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String expires = Uri.parse(url).getQueryParameter("x-expires");
            if (expires == null) return false;
            long now = System.currentTimeMillis() / 1000;
            return Long.parseLong(expires) < now + EXPIRE_MARGIN_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Glide 加载用的地址：缓存 key 去掉签名参数，
     * 这样链接刷新（签名变化）后仍能命中已缓存的同一张封面。
     */
    public static Object glideModel(String url) {
        if (url == null || url.isEmpty()) return url;
        int q = url.indexOf('?');
        if (q < 0) return url;
        final String cacheKey = url.substring(0, q);
        return new GlideUrl(url) {
            @Override
            public String getCacheKey() {
                return cacheKey;
            }
        };
    }
}
