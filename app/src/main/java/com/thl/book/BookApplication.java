package com.thl.book;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import com.thl.book.server.EmbeddedServerManager;
import com.thl.reader.AppContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class BookApplication extends AppContext {

    private static final String TAG = "WebDavAutoSync";

    /** 当前处于 started 状态的 Activity 数量，归零说明 app 退到后台 */
    private final AtomicInteger startedActivities = new AtomicInteger(0);
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        NotifyHelper.init(this);
        startEmbeddedServer();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle bundle) {
                ActivityTack.tack.addActivity(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                if (startedActivities.getAndIncrement() == 0) {
                    // app 从后台回到前台，触发同步拉取最新数据
                    triggerAutoSync();
                }
            }

            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {
                if (startedActivities.decrementAndGet() == 0) {
                    // 所有 Activity 都进入 stopped → app 进入后台，触发同步上传数据
                    triggerAutoSync();
                }
            }

            @Override public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                ActivityTack.tack.removeActivity(activity);
            }
        });
    }

    private void startEmbeddedServer() {
        final android.content.Context appCtx = getApplicationContext();
        new Thread(() -> {
            EmbeddedServerManager server = EmbeddedServerManager.getInstance();
            server.start(appCtx);
            boolean ready = server.waitUntilReady(15_000);
            if (ready) {
                Log.d(TAG, "内嵌服务器就绪");
            } else {
                Log.w(TAG, "内嵌服务器启动超时，搜索/下载功能可能不可用");
            }
        }, "embedded-server-start").start();
    }

    private void triggerAutoSync() {
        if (!WebDavConfig.isEnabled(this)) return;
        final android.content.Context appCtx = getApplicationContext();
        syncExecutor.execute(() ->
                WebDavSyncManager.sync(appCtx, new WebDavSyncManager.SyncCallback() {
                    @Override
                    public void onSuccess(String message) {
                        Log.d(TAG, "Auto sync success: " + message);
                        WebDavConfig.saveLastSyncAt(appCtx);
                        // 通知前台 Activity 刷新界面（如阅读时间、同步标签）
                        appCtx.sendBroadcast(
                                new android.content.Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                        .setPackage(appCtx.getPackageName())
                                        .putExtra("total_new", 0)
                                        .putExtra(UpdateChecker.EXTRA_IS_FINISHED, true)
                                        .putExtra(UpdateChecker.EXTRA_CURRENT, 0)
                                        .putExtra(UpdateChecker.EXTRA_TOTAL, 0));
                    }
                    @Override
                    public void onError(String error) {
                        Log.w(TAG, "Auto sync failed: " + error);
                        NotifyHelper.send(appCtx, "WebDAV 同步失败", error);
                    }
                })
        );
    }
}
