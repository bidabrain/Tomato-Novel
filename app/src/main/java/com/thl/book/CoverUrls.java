package com.thl.book;

/**
 * 番茄封面链接是带签名的临时链接（p*-reading-sign.fqnovelpic.com/novel-pic/<id>~…?x-expires=…&x-signature=…），
 * 有效期只有几天，书架保存的旧链接过期后会 403，封面就显示成默认图。
 * 同一张图在 p3-novel.byteimg.com 上不需要签名、长期有效，显示前统一换成这个地址。
 */
public final class CoverUrls {

    private static final String NOVEL_PIC = "/novel-pic/";
    private static final String STABLE_PREFIX = "https://p3-novel.byteimg.com/novel-pic/";
    private static final String STABLE_SUFFIX = "~tplv-resize:225:0.image";

    /** 提前一小时视为过期，避免刚刷新完就失效 */
    private static final long EXPIRE_MARGIN_SECONDS = 3600;

    private CoverUrls() {}

    /** 链接带 x-expires 且已过期（或即将过期） */
    public static boolean isExpired(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String expires = android.net.Uri.parse(url).getQueryParameter("x-expires");
            if (expires == null) return false;
            return Long.parseLong(expires) < System.currentTimeMillis() / 1000 + EXPIRE_MARGIN_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把番茄签名封面链接换成不带签名的长期有效地址；其他链接原样返回 */
    public static String stable(String url) {
        if (url == null) return null;
        int i = url.indexOf(NOVEL_PIC);
        if (i < 0) return url;
        int start = i + NOVEL_PIC.length();
        int end = start;
        while (end < url.length()) {
            char c = url.charAt(end);
            if (c == '~' || c == '?' || c == '/' || c == '#') break;
            end++;
        }
        if (end == start) return url;
        return STABLE_PREFIX + url.substring(start, end) + STABLE_SUFFIX;
    }
}
