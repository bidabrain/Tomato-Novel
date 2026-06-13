package com.thl.book;

import androidx.appcompat.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.TimeUnit;

import com.thl.book.base.BaseActivity;

public class SettingsActivity extends BaseActivity {

    private Switch switchStore;
    private View groupStore;
    private EditText etStoreUrl;
    private EditText etWebDavUrl;
    private EditText etWebDavUsername;
    private EditText etWebDavPassword;
    private TextView tvCacheSize;

    @Override
    protected int initLayout() {
        return R.layout.activity_settings;
    }

    @Override
    protected void initView() {
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        switchStore = findViewById(R.id.switch_store);
        groupStore  = findViewById(R.id.group_store);
        etStoreUrl  = findViewById(R.id.et_store_url);
        etWebDavUrl      = findViewById(R.id.et_webdav_url);
        etWebDavUsername = findViewById(R.id.et_webdav_username);
        etWebDavPassword = findViewById(R.id.et_webdav_password);

        // Restore saved state
        switchStore.setChecked(ServerConfig.isCustomStoreEnabled(this));
        refreshLastSyncLabel();
        etStoreUrl.setText(
                SharedPreferencesUtils.getString(this, ServerConfig.KEY_CUSTOM_STORE_URL, ""));
        etWebDavUrl.setText(
                SharedPreferencesUtils.getString(this, WebDavConfig.KEY_WEBDAV_URL, ""));
        etWebDavUsername.setText(
                SharedPreferencesUtils.getString(this, WebDavConfig.KEY_WEBDAV_USERNAME, ""));
        etWebDavPassword.setText(
                SharedPreferencesUtils.getString(this, WebDavConfig.KEY_WEBDAV_PASSWORD, ""));

        // Apply initial enabled state to input groups
        setGroupEnabled(groupStore, switchStore.isChecked());

        switchStore.setOnCheckedChangeListener((btn, checked) ->
                setGroupEnabled(groupStore, checked));

        tvCacheSize = findViewById(R.id.tv_cache_size);
        refreshCacheSizeLabel();
        findViewById(R.id.btn_clear_cache).setOnClickListener(v -> confirmClearCache());

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    @Override
    protected void initData(Bundle savedInstanceState) {}

    private void save() {
        SharedPreferencesUtils.saveBoolean(this,
                ServerConfig.KEY_CUSTOM_STORE_ENABLED, switchStore.isChecked());
        SharedPreferencesUtils.saveString(this,
                ServerConfig.KEY_CUSTOM_STORE_URL,
                etStoreUrl.getText().toString().trim());

        SharedPreferencesUtils.saveString(this,
                WebDavConfig.KEY_WEBDAV_URL,
                etWebDavUrl.getText().toString().trim());
        SharedPreferencesUtils.saveString(this,
                WebDavConfig.KEY_WEBDAV_USERNAME,
                etWebDavUsername.getText().toString().trim());
        SharedPreferencesUtils.saveString(this,
                WebDavConfig.KEY_WEBDAV_PASSWORD,
                etWebDavPassword.getText().toString());

        // Invalidate book store cache so next visit re-fetches from the new URL
        RankDataCache.invalidate();

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void refreshLastSyncLabel() {
        TextView tv = findViewById(R.id.tv_last_sync);
        if (tv == null) return;
        long lastSync = WebDavConfig.getLastSyncAt(this);
        if (lastSync == 0) {
            tv.setText("从未同步");
            return;
        }
        long diffMs = System.currentTimeMillis() - lastSync;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs);
        long hours   = TimeUnit.MILLISECONDS.toHours(diffMs);
        long days    = TimeUnit.MILLISECONDS.toDays(diffMs);
        String label;
        if (minutes < 1)       label = "刚刚同步";
        else if (hours < 1)    label = minutes + " 分钟前";
        else if (days < 1)     label = hours + " 小时前";
        else                   label = days + " 天前";
        tv.setText("上次同步：" + label);
    }

    // ── 缓存清理 ─────────────────────────────────────────────────────────────

    /** server_data/ — 中间缓存文件夹（{book_id}/） */
    private File serverDataDir() {
        return new File(getFilesDir(), "server_data");
    }

    /** tomato/server/ — 服务器合并输出的 TXT 文件 */
    private File serverSaveDir() {
        return new File(getExternalFilesDir(null), "tomato/server");
    }

    /** 两处合计占用字节。 */
    private long cacheTotalBytes() {
        return cacheDirSize(serverDataDir(), true)
             + cacheDirSize(serverSaveDir(), false);
    }

    /**
     * @param dirsOnly true → 只统计子目录（跳过顶层文件如 config.yml）
     *                 false → 统计目录内所有文件
     */
    private long cacheDirSize(File dir, boolean dirsOnly) {
        if (!dir.exists()) return 0;
        long total = 0;
        File[] entries = dir.listFiles();
        if (entries == null) return 0;
        for (File f : entries) {
            if (dirsOnly && !f.isDirectory()) continue;
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private long dirSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            size += f.isDirectory() ? dirSize(f) : f.length();
        }
        return size;
    }

    private void refreshCacheSizeLabel() {
        long bytes = cacheTotalBytes();
        if (bytes == 0) {
            tvCacheSize.setText("当前无缓存");
        } else {
            tvCacheSize.setText("当前缓存：" + formatSize(bytes));
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void confirmClearCache() {
        long bytes = cacheTotalBytes();
        String sizeStr = bytes > 0 ? formatSize(bytes) : "0 B";
        new AlertDialog.Builder(this)
                .setTitle("清理服务器缓存")
                .setMessage("将删除（共 " + sizeStr + "）：\n"
                        + "• 中间缓存文件夹（章节分片、进度文件）\n"
                        + "• 服务器合并输出的 TXT 文件\n\n"
                        + "书架中的 TXT（tomato/local/）不受影响。\n\n确认清理？")
                .setPositiveButton("清理", (d, w) -> clearCache())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearCache() {
        // 1. 删除 server_data/ 下所有子目录
        int dirCount = 0;
        File dataDir = serverDataDir();
        if (dataDir.exists()) {
            File[] entries = dataDir.listFiles();
            if (entries != null) {
                for (File f : entries) {
                    if (f.isDirectory()) { deleteRecursive(f); dirCount++; }
                }
            }
        }

        // 2. 删除 tomato/server/ 下所有文件（保留目录本身）
        int fileCount = 0;
        File saveDir = serverSaveDir();
        if (saveDir.exists()) {
            File[] files = saveDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) { f.delete(); fileCount++; }
                }
            }
        }

        refreshCacheSizeLabel();
        String msg = (dirCount + fileCount > 0)
                ? "已清理 " + dirCount + " 个缓存文件夹、" + fileCount + " 个 TXT 文件"
                : "无缓存可清理";
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** Grey out / re-enable all child views inside a group layout. */
    private void setGroupEnabled(View group, boolean enabled) {
        group.setAlpha(enabled ? 1f : 0.4f);
        if (group instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) group;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                child.setEnabled(enabled);
                child.setFocusable(enabled);
                child.setFocusableInTouchMode(enabled);
            }
        }
    }
}
