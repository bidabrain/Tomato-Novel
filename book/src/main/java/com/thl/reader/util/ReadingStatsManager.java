package com.thl.reader.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * 本周累计阅读时间的追踪与存储。
 * 使用 SharedPreferences 存储，按自然周（周一起始）自动重置。
 * 可通过 WebDAV 同步（同周取最大值）。
 */
public class ReadingStatsManager {

    private static final String PREF_NAME = "reading_stats";
    private static final String KEY_WEEK_START    = "week_start";       // "yyyy-MM-dd"
    private static final String KEY_WEEKLY_SECONDS = "weekly_seconds";  // long
    private static final String KEY_SESSION_START  = "session_start";   // long ms
    private static final String KEY_TOTAL_DAYS        = "total_days";          // int
    private static final String KEY_LAST_READING_DATE = "last_reading_date";   // "yyyy-MM-dd"
    private static final String KEY_TODAY_SECONDS     = "today_seconds";       // long
    private static final String KEY_TODAY_DATE        = "today_date";          // "yyyy-MM-dd"

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /** 进入阅读界面时调用，记录本次会话开始时间，同时追踪累计阅读天数。 */
    public static void recordStart(Context context) {
        SharedPreferences p = prefs(context);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date());
        String lastDate = p.getString(KEY_LAST_READING_DATE, "");
        SharedPreferences.Editor editor = p.edit()
                .putLong(KEY_SESSION_START, System.currentTimeMillis());
        if (!today.equals(lastDate)) {
            int totalDays = p.getInt(KEY_TOTAL_DAYS, 0) + 1;
            editor.putInt(KEY_TOTAL_DAYS, totalDays)
                  .putString(KEY_LAST_READING_DATE, today);
        }
        editor.apply();
    }

    /** 离开阅读界面时调用，将本次会话时长累加到本周总计和今日总计。 */
    public static void recordStop(Context context) {
        SharedPreferences p = prefs(context);
        long start = p.getLong(KEY_SESSION_START, 0);
        if (start == 0) return;

        long elapsed = (System.currentTimeMillis() - start) / 1000L;
        p.edit().putLong(KEY_SESSION_START, 0).apply();
        if (elapsed <= 0) return;

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date());

        // 本周累计
        String currentWeek = getCurrentWeekStart();
        String storedWeek  = p.getString(KEY_WEEK_START, "");
        long weekTotal = currentWeek.equals(storedWeek) ? p.getLong(KEY_WEEKLY_SECONDS, 0) : 0;
        weekTotal += elapsed;

        // 今日累计（跨天自动重置）
        String storedDay = p.getString(KEY_TODAY_DATE, "");
        long dayTotal = today.equals(storedDay) ? p.getLong(KEY_TODAY_SECONDS, 0) : 0;
        dayTotal += elapsed;

        p.edit()
                .putString(KEY_WEEK_START, currentWeek)
                .putLong(KEY_WEEKLY_SECONDS, weekTotal)
                .putString(KEY_TODAY_DATE, today)
                .putLong(KEY_TODAY_SECONDS, dayTotal)
                .apply();
    }

    /** 返回今日累计阅读秒数（跨天自动返回 0）。 */
    public static long getTodaySeconds(Context context) {
        SharedPreferences p = prefs(context);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new java.util.Date());
        if (!today.equals(p.getString(KEY_TODAY_DATE, ""))) return 0;
        return p.getLong(KEY_TODAY_SECONDS, 0);
    }

    /** 返回本周累计阅读秒数（不同周自动返回 0）。 */
    public static long getWeeklySeconds(Context context) {
        SharedPreferences p = prefs(context);
        if (!getCurrentWeekStart().equals(p.getString(KEY_WEEK_START, ""))) return 0;
        return p.getLong(KEY_WEEKLY_SECONDS, 0);
    }

    /** 返回本周开始日期字符串（格式 "yyyy-MM-dd"）。 */
    public static String getCurrentWeekStart() {
        Calendar cal = Calendar.getInstance(Locale.US);
        int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=Sun, 2=Mon, ...7=Sat
        int offset = (dow == Calendar.SUNDAY) ? -6 : -(dow - Calendar.MONDAY);
        cal.add(Calendar.DAY_OF_MONTH, offset);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    /**
     * WebDAV 同步时调用：若远程数据属于本周，取本地与远程的最大值。
     * 若不同周则忽略远程数据。
     */
    public static void mergeFromSync(Context context, long remoteSeconds, String remoteWeekStart) {
        String currentWeek = getCurrentWeekStart();
        if (!currentWeek.equals(remoteWeekStart)) return;

        SharedPreferences p = prefs(context);
        String storedWeek = p.getString(KEY_WEEK_START, "");
        long local = currentWeek.equals(storedWeek) ? p.getLong(KEY_WEEKLY_SECONDS, 0) : 0;
        long merged = Math.max(local, remoteSeconds);

        p.edit()
                .putString(KEY_WEEK_START, currentWeek)
                .putLong(KEY_WEEKLY_SECONDS, merged)
                .apply();
    }

    /** 将秒数格式化为人类可读字符串，例如 "1小时23分" 或 "45分钟"。 */
    public static String formatTime(long totalSeconds) {
        if (totalSeconds <= 0) return "0分钟";
        long minutes = totalSeconds / 60;
        if (minutes == 0) return "< 1分钟";
        long hours = minutes / 60;
        long mins  = minutes % 60;
        if (hours == 0) return minutes + "分钟";
        return hours + "小时" + (mins > 0 ? mins + "分" : "");
    }

    /** 返回累计阅读天数。 */
    public static int getCumulativeDays(Context context) {
        return prefs(context).getInt(KEY_TOTAL_DAYS, 0);
    }

    /**
     * WebDAV 同步时调用：取本地与远程累计天数的最大值。
     */
    public static void mergeCumulativeDaysFromSync(Context context, int remoteDays) {
        int local = getCumulativeDays(context);
        int merged = Math.max(local, remoteDays);
        prefs(context).edit().putInt(KEY_TOTAL_DAYS, merged).apply();
    }

    // ── 私有工具 ──────────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
