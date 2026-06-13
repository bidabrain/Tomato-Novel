package com.thl.book.server;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 管理内嵌的 Tomato-Novel-Downloader 服务器进程。
 *
 * 二进制文件来自官方 Release，放置于：
 *   app/src/main/jniLibs/arm64-v8a/libserver.so
 *   app/src/main/jniLibs/armeabi-v7a/libserver.so
 *
 * 安装后 Android 自动解压到 nativeLibraryDir，该目录在 Android 10+ 上允许执行。
 *
 * 存储目录：
 *   config/日志 → filesDir/server_data/
 *   书籍文件    → externalFilesDir/tomato/server/
 */
public class EmbeddedServerManager {

    private static final String TAG = "EmbeddedServer";
    static final int PORT = 18423;

    private static EmbeddedServerManager sInstance;
    private Process mProcess;

    public static synchronized EmbeddedServerManager getInstance() {
        if (sInstance == null) sInstance = new EmbeddedServerManager();
        return sInstance;
    }

    private EmbeddedServerManager() {}

    /**
     * 启动服务器进程。若已在运行则直接返回。
     * 必须在后台线程调用。
     */
    public void start(Context context) {
        if (isRunning()) return;

        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libserver.so");
        if (!binary.exists()) {
            Log.e(TAG, "libserver.so 未找到: " + binary.getAbsolutePath());
            Log.e(TAG, "请将 Release 二进制文件放入 jniLibs 目录后重新编译");
            return;
        }

        File dataDir = new File(context.getFilesDir(), "server_data");
        dataDir.mkdirs();

        File savePath = new File(context.getExternalFilesDir(null), "tomato/server");
        savePath.mkdirs();

        initConfig(dataDir, savePath);

        try {
            binary.setExecutable(true);

            ProcessBuilder pb = new ProcessBuilder(
                    binary.getAbsolutePath(),
                    "--server",
                    "--data-dir", dataDir.getAbsolutePath()
            );
            pb.environment().put("TOMATO_WEB_ADDR", "127.0.0.1:" + PORT);
            pb.environment().put("TOMATO_WEB_PASSWORD", "");
            pb.redirectErrorStream(true);

            mProcess = pb.start();
            Log.i(TAG, "服务器进程已启动");

            // 持续消费 stdout/stderr，防止管道缓冲区阻塞进程
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(mProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, line);
                    }
                } catch (IOException ignored) {}
                Log.i(TAG, "服务器进程已退出");
            }, "server-log").start();

        } catch (IOException e) {
            Log.e(TAG, "启动服务器失败", e);
        }
    }

    /**
     * 阻塞当前线程，直到服务器就绪或超时。
     * 必须在后台线程调用。
     *
     * @param timeoutMs 超时毫秒数
     * @return true 表示服务器已就绪
     */
    public boolean waitUntilReady(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isReady()) return true;
            try { Thread.sleep(300); } catch (InterruptedException e) { break; }
        }
        Log.w(TAG, "等待服务器就绪超时（" + timeoutMs + "ms）");
        return false;
    }

    /** 尝试连接 localhost，返回服务器是否已在响应请求。 */
    public boolean isReady() {
        try {
            URL url = new URL("http://127.0.0.1:" + PORT + "/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200 || code == 401; // 200=无密码 401=有密码但已响应
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回服务器进程是否仍在运行。 */
    public boolean isRunning() {
        if (mProcess == null) return false;
        try {
            mProcess.exitValue();
            return false; // exitValue() 不抛异常说明进程已退出
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    public void stop() {
        if (mProcess != null) {
            mProcess.destroy();
            mProcess = null;
        }
    }

    /**
     * 首次运行时写入 config.yml，设置书籍存储路径。
     * 文件已存在时不覆盖，保留用户/服务器写入的完整配置。
     */
    private void initConfig(File dataDir, File savePath) {
        File configFile = new File(dataDir, "config.yml");
        if (configFile.exists()) return;
        try (FileWriter fw = new FileWriter(configFile)) {
            fw.write("save_path: \"" + savePath.getAbsolutePath() + "\"\n");
            fw.write("novel_format: \"txt\"\n");
            Log.i(TAG, "已创建 config.yml，save_path=" + savePath.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "写入 config.yml 失败", e);
        }
    }
}
