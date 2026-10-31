package com.thl.book;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.thl.reader.db.BookList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 书架封面的“上次封面”本地副本：
 * 封面链接加载成功后，把图片另存到 files/covers/（不会被“清除缓存”或系统清理删掉）。
 * 显示时先显示上次的封面，同时加载链接；链接成功则换成最新的，
 * 失败（签名过期、断网）则保留上次的封面；两者都没有才显示默认封面。
 */
public final class CoverCache {

    private static final String TAG = "CoverCache";
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor();

    private CoverCache() {}

    /** 这本书的本地封面文件；无法区分的书返回 null */
    @Nullable
    static File fileFor(Context ctx, BookList book) {
        String key;
        if (book.getTomatoBookId() != null && !book.getTomatoBookId().isEmpty()) {
            key = "t_" + book.getTomatoBookId();
        } else if (book.getBookpath() != null && !book.getBookpath().isEmpty()) {
            key = "p_" + Integer.toHexString((book.getBookname() + "|" + book.getBookpath()).hashCode());
        } else {
            return null;
        }
        File dir = new File(ctx.getFilesDir(), "covers");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".img");
    }

    /** 加载书架封面：链接 → 上次封面 → 默认封面 */
    public static void load(Context ctx, BookList book, ImageView target, int defaultRes) {
        final Context app = ctx.getApplicationContext();
        final File local = fileFor(app, book);
        final String url = book.getCoverUrl();

        RequestBuilder<Drawable> lastCover = null;
        if (local != null && local.exists()) {
            lastCover = Glide.with(ctx).load(local)
                    // 文件内容会被新封面覆盖，用修改时间区分缓存
                    .signature(new ObjectKey(local.lastModified()))
                    .error(defaultRes);
        }

        if (url == null || url.isEmpty()) {
            if (lastCover != null) {
                lastCover.into(target);
            } else {
                Glide.with(ctx).clear(target);
                target.setImageResource(defaultRes);
            }
            return;
        }

        final Object model = CoverUrls.glideModel(url);
        RequestBuilder<Drawable> req = Glide.with(ctx).load(model);
        if (lastCover != null) {
            // 加载链接期间先显示上次的封面；链接失败时保留上次的封面
            req = req.thumbnail(lastCover.clone()).error(lastCover);
        } else {
            req = req.placeholder(defaultRes).error(defaultRes);
        }
        req.listener(new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object m,
                                        Target<Drawable> t, boolean isFirstResource) {
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object m, Target<Drawable> t,
                                           DataSource dataSource, boolean isFirstResource) {
                // 新从网络下载的封面，或本地还没有副本时，另存一份
                if (local != null && (dataSource == DataSource.REMOTE || !local.exists())) {
                    save(app, model, local);
                }
                return false;
            }
        }).into(target);
    }

    /** 从 Glide 的磁盘缓存取出原图文件（不会重复下载），复制到本地封面文件 */
    private static void save(Context app, Object model, File dest) {
        SAVER.execute(() -> {
            File tmp = new File(dest.getPath() + ".tmp");
            try {
                File src = Glide.with(app).asFile().load(model).submit().get();
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(dest)) {
                    dest.delete();
                    tmp.renameTo(dest);
                }
            } catch (Exception e) {
                Log.w(TAG, "save cover failed: " + dest.getName(), e);
                tmp.delete();
            }
        });
    }
}
