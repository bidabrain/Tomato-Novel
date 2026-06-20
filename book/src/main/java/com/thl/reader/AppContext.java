package com.thl.reader;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

import com.thl.reader.db.DB;
import com.thl.reader.util.PageFactory;

public class AppContext extends Application {
    public static volatile Context applicationContext = null;

    @Override
    public void onCreate() {
        super.onCreate();
        applicationContext = getApplicationContext();
        DB.init(this);
        Config config = Config.createConfig(this);
        PageFactory.createPageFactory(this);
        // E-ink 开启时强制亮色，关闭时跟随系统明暗模式
        AppCompatDelegate.setDefaultNightMode(config.isEinkMode()
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
