package com.thl.book;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.concurrent.atomic.AtomicInteger;

public class NotifyHelper {

    private static final String CHANNEL_ID = "tomato_download";
    private static final AtomicInteger idGen = new AtomicInteger(1000);

    public static void init(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "番茄下载", NotificationManager.IMPORTANCE_DEFAULT);
            context.getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }

    public static void send(Context context, String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.icon)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context).notify(idGen.getAndIncrement(), builder.build());
        } catch (SecurityException ignored) {
            // 用户未授予通知权限，静默忽略
        }
    }
}
