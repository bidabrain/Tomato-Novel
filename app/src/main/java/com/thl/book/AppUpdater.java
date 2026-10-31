package com.thl.book;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 手动检查 app 新版本：对比 GitHub 最新 Release 的 tag 与本地 versionName，
 * 有新版本时列出 Release 中的 APK 供选择，下载后调起系统安装。
 */
public class AppUpdater {

    private static final String LATEST_URL =
            "https://api.github.com/repos/bidabrain/Tomato-Novel/releases/latest";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    /** 安装权限授权后回到页面时继续安装 */
    private File pendingApk;

    public AppUpdater(Activity activity) {
        this.activity = activity;
    }

    private static class Asset {
        String name;
        String url;
        long size;
    }

    public void check() {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                Request req = new Request.Builder()
                        .url(LATEST_URL)
                        .header("Accept", "application/vnd.github+json")
                        .build();
                String body;
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new Exception("HTTP " + resp.code());
                    }
                    body = resp.body().string();
                }
                JsonObject release = JsonParser.parseString(body).getAsJsonObject();
                String tag = str(release, "tag_name");
                String notes = str(release, "body");
                List<Asset> assets = new ArrayList<>();
                JsonArray arr = release.has("assets") ? release.getAsJsonArray("assets") : new JsonArray();
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    Asset a = new Asset();
                    a.name = str(o, "name");
                    a.url = str(o, "browser_download_url");
                    a.size = o.has("size") ? o.get("size").getAsLong() : 0;
                    if (a.name.toLowerCase(Locale.ROOT).endsWith(".apk")) assets.add(a);
                }
                String local = currentVersion();
                main.post(() -> onReleaseFetched(local, tag, notes, assets));
            } catch (Exception e) {
                main.post(() -> {
                    if (activity.isFinishing()) return;
                    Toast.makeText(activity, "检查更新失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void onReleaseFetched(String local, String tag, String notes, List<Asset> assets) {
        if (activity.isFinishing()) return;
        if (compareVersion(tag, local) <= 0) {
            new AlertDialog.Builder(activity)
                    .setTitle("检查更新")
                    .setMessage("已是最新版本（" + local + "）")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        if (assets.isEmpty()) {
            Toast.makeText(activity, "发现新版本 " + tag + "，但 Release 中没有 APK", Toast.LENGTH_LONG).show();
            return;
        }
        String msg = "当前版本：" + local + "\n最新版本：" + tag
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setNegativeButton("取消", null);
        if (assets.size() == 1) {
            b.setPositiveButton("下载更新", (d, w) -> download(assets.get(0)));
        } else {
            b.setPositiveButton("选择安装包", (d, w) -> chooseAsset(assets));
        }
        b.show();
    }

    private void chooseAsset(List<Asset> assets) {
        String[] items = new String[assets.size()];
        for (int i = 0; i < items.length; i++) {
            Asset a = assets.get(i);
            items[i] = a.name + String.format(Locale.ROOT, "（%.1f MB）", a.size / 1024f / 1024f);
        }
        new AlertDialog.Builder(activity)
                .setTitle("选择要下载的安装包")
                .setItems(items, (d, which) -> download(assets.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void download(Asset asset) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, 0);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("正在下载 " + asset.name)
                .setView(bar)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            File dir = new File(activity.getExternalFilesDir(null), "apk");
            File out = new File(dir, asset.name);
            try {
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
                Request req = new Request.Builder().url(asset.url).build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new Exception("HTTP " + resp.code());
                    }
                    long total = resp.body().contentLength();
                    if (total <= 0) total = asset.size;
                    try (InputStream in = resp.body().byteStream();
                         OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        long read = 0;
                        int lastPct = -1, n;
                        while ((n = in.read(buf)) != -1) {
                            os.write(buf, 0, n);
                            read += n;
                            if (total > 0) {
                                int pct = (int) (read * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    main.post(() -> bar.setProgress(pct));
                                }
                            }
                        }
                    }
                }
                main.post(() -> {
                    dialog.dismiss();
                    install(out);
                });
            } catch (Exception e) {
                out.delete();
                main.post(() -> {
                    dialog.dismiss();
                    if (activity.isFinishing()) return;
                    Toast.makeText(activity, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void install(File apk) {
        if (activity.isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            pendingApk = apk;
            new AlertDialog.Builder(activity)
                    .setTitle("需要安装权限")
                    .setMessage("请允许本应用安装未知来源应用，返回后将自动继续安装。")
                    .setPositiveButton("去设置", (d, w) -> activity.startActivity(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()))))
                    .setNegativeButton("取消", (d, w) -> pendingApk = null)
                    .show();
            return;
        }
        pendingApk = null;
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileProvider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    /** 在 Activity.onResume 中调用：从安装权限设置页返回后继续安装 */
    public void onResume() {
        if (pendingApk == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && activity.getPackageManager().canRequestPackageInstalls()) {
            install(pendingApk);
        }
    }

    private String currentVersion() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 比较 "v1.7.4" / "1.7.1-202606140005" 这类版本号，只看 '-' 之前的数字段 */
    static int compareVersion(String a, String b) {
        int[] x = parse(a), y = parse(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        int dash = v.indexOf('-');
        if (dash >= 0) v = v.substring(0, dash);
        String[] parts = v.split("\\.");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { r[i] = Integer.parseInt(parts[i].replaceAll("\\D", "")); } catch (Exception ignored) {}
        }
        return r;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
