package com.thl.book;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.thl.book.base.BaseActivity;

public class SettingsActivity extends BaseActivity {

    private Switch switchDownloader;
    private Switch switchStore;
    private Switch switchWebDav;
    private View groupDownloader;
    private View groupStore;
    private View groupWebDav;
    private EditText etDownloaderUrl;
    private EditText etDownloaderPassword;
    private EditText etStoreUrl;
    private EditText etWebDavUrl;
    private EditText etWebDavUsername;
    private EditText etWebDavPassword;

    @Override
    protected int initLayout() {
        return R.layout.activity_settings;
    }

    @Override
    protected void initView() {
        findViewById(R.id.iv_back).setOnClickListener(v -> finish());

        switchDownloader = findViewById(R.id.switch_downloader);
        switchStore      = findViewById(R.id.switch_store);
        switchWebDav     = findViewById(R.id.switch_webdav);
        groupDownloader  = findViewById(R.id.group_downloader);
        groupStore       = findViewById(R.id.group_store);
        groupWebDav      = findViewById(R.id.group_webdav);
        etDownloaderUrl      = findViewById(R.id.et_downloader_url);
        etDownloaderPassword = findViewById(R.id.et_downloader_password);
        etStoreUrl           = findViewById(R.id.et_store_url);
        etWebDavUrl      = findViewById(R.id.et_webdav_url);
        etWebDavUsername = findViewById(R.id.et_webdav_username);
        etWebDavPassword = findViewById(R.id.et_webdav_password);

        // Restore saved state
        switchDownloader.setChecked(ServerConfig.isCustomDownloaderEnabled(this));
        switchStore.setChecked(ServerConfig.isCustomStoreEnabled(this));
        switchWebDav.setChecked(WebDavConfig.isEnabled(this));
        etDownloaderUrl.setText(
                SharedPreferencesUtils.getString(this, ServerConfig.KEY_CUSTOM_DOWNLOADER_URL, ""));
        etDownloaderPassword.setText(
                SharedPreferencesUtils.getString(this, ServerConfig.KEY_CUSTOM_DOWNLOADER_PASSWORD, ""));
        etStoreUrl.setText(
                SharedPreferencesUtils.getString(this, ServerConfig.KEY_CUSTOM_STORE_URL, ""));
        etWebDavUrl.setText(
                SharedPreferencesUtils.getString(this, WebDavConfig.KEY_WEBDAV_URL, ""));
        etWebDavUsername.setText(
                SharedPreferencesUtils.getString(this, WebDavConfig.KEY_WEBDAV_USERNAME, ""));
        etWebDavPassword.setText(
                SharedPreferencesUtils.getString(this, WebDavConfig.KEY_WEBDAV_PASSWORD, ""));

        // Apply initial enabled state to input groups
        setGroupEnabled(groupDownloader, switchDownloader.isChecked());
        setGroupEnabled(groupStore, switchStore.isChecked());
        setGroupEnabled(groupWebDav, switchWebDav.isChecked());

        switchDownloader.setOnCheckedChangeListener((btn, checked) ->
                setGroupEnabled(groupDownloader, checked));
        switchStore.setOnCheckedChangeListener((btn, checked) ->
                setGroupEnabled(groupStore, checked));
        switchWebDav.setOnCheckedChangeListener((btn, checked) ->
                setGroupEnabled(groupWebDav, checked));

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    @Override
    protected void initData(Bundle savedInstanceState) {}

    private void save() {
        SharedPreferencesUtils.saveBoolean(this,
                ServerConfig.KEY_CUSTOM_DOWNLOADER_ENABLED, switchDownloader.isChecked());
        SharedPreferencesUtils.saveString(this,
                ServerConfig.KEY_CUSTOM_DOWNLOADER_URL,
                etDownloaderUrl.getText().toString().trim());
        SharedPreferencesUtils.saveString(this,
                ServerConfig.KEY_CUSTOM_DOWNLOADER_PASSWORD,
                etDownloaderPassword.getText().toString());

        SharedPreferencesUtils.saveBoolean(this,
                ServerConfig.KEY_CUSTOM_STORE_ENABLED, switchStore.isChecked());
        SharedPreferencesUtils.saveString(this,
                ServerConfig.KEY_CUSTOM_STORE_URL,
                etStoreUrl.getText().toString().trim());

        SharedPreferencesUtils.saveBoolean(this,
                WebDavConfig.KEY_WEBDAV_ENABLED, switchWebDav.isChecked());
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

    /** Grey out / re-enable all child EditTexts inside a group layout. */
    private void setGroupEnabled(View group, boolean enabled) {
        group.setAlpha(enabled ? 1f : 0.4f);
        if (group instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) group;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                child.setEnabled(enabled);
                child.setFocusable(enabled);
            }
        }
    }
}
