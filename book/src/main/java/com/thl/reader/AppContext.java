package com.thl.reader;

import android.app.Application;
import android.content.Context;

import com.thl.reader.db.DB;
import com.thl.reader.util.PageFactory;

public class AppContext extends Application {
    public static volatile Context applicationContext = null;

    @Override
    public void onCreate() {
        super.onCreate();
        applicationContext = getApplicationContext();
        DB.init(this);
        Config.createConfig(this);
        PageFactory.createPageFactory(this);
    }
}
