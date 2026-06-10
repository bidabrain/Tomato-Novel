package com.thl.reader.tts;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.util.Collections;

/**
 * 基于 Microsoft Edge TTS 的在线语音引擎。
 * 通过 WebSocket 向微软服务器发送 SSML，接收 MP3 音频流，
 * 用 MediaPlayer 顺序播放各 chunk。不依赖任何额外 SDK，仅用 OkHttp（项目已有）。
 *
 * 注意：Edge TTS 为非官方接口，服务可用性由微软决定。
 */
public class EdgeTtsEngine implements TtsEngine {

    private static final String TAG = "EdgeTtsEngine";
    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String WS_BASE_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=" + TRUSTED_TOKEN;
    private static final String SEC_MS_GEC_VERSION = "1-143.0.3650.75";
    private static final String EDGE_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    // Windows FILETIME epoch 偏移（秒）：从 1601-01-01 到 1970-01-01
    private static final long WIN_EPOCH = 11644473600L;
    private static final String VOICE = "zh-CN-XiaoxiaoNeural";

    private Context context;
    private Callback callback;
    private float speed = 1.0f;
    private boolean ready = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OkHttpClient httpClient;

    // 待播放队列：每项为 [text, utteranceId]
    private final ArrayDeque<String[]> speakQueue = new ArrayDeque<>();
    // 会话 ID：stop() 时自增，用于取消进行中的回调
    private volatile int sessionId = 0;
    private volatile boolean processing = false;

    private MediaPlayer mediaPlayer;
    private File currentTempFile;

    // 连续失败计数：达到阈值时触发 onFail 以便上层 fallback
    private int consecutiveErrors = 0;
    private static final int ERROR_THRESHOLD = 2;

    @Override
    public void init(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .build();
        ready = true;
        callback.onReady();
    }

    @Override
    public void setSpeed(float speed) {
        this.speed = speed;
        // 如果 MediaPlayer 正在播放，立即调整当前 chunk 的速度
        mainHandler.post(() -> {
            if (mediaPlayer != null && mediaPlayer.isPlaying() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    PlaybackParams params = new PlaybackParams();
                    params.setSpeed(speed);
                    mediaPlayer.setPlaybackParams(params);
                } catch (Exception ignored) {}
            }
        });
    }

    @Override
    public void speak(String text, String utteranceId, boolean flushFirst) {
        if (!ready) return;
        if (flushFirst) {
            stopInternal();
        }
        speakQueue.offer(new String[]{text, utteranceId});
        if (!processing) {
            processNext(sessionId);
        }
    }

    private void processNext(int mySession) {
        if (mySession != sessionId || speakQueue.isEmpty()) {
            processing = false;
            return;
        }
        processing = true;
        String[] item = speakQueue.poll();
        if (item == null) { processing = false; return; }
        fetchAndPlay(item[0], item[1], mySession);
    }

    /** 通过 WebSocket 获取 MP3 音频，收完后用 MediaPlayer 播放 */
    private void fetchAndPlay(String text, String utteranceId, int mySession) {
        String connectionId = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US);
        String requestId    = UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        String secMsGec = computeSecMsGec();

        Log.d(TAG, "Sec-MS-GEC=" + secMsGec + "  version=" + SEC_MS_GEC_VERSION);

        mainHandler.post(() -> {
            if (mySession == sessionId) callback.onUtteranceStart(utteranceId);
        });

        String muid = generateMuid();
        // Sec-MS-GEC 和 Sec-MS-GEC-Version 是 URL 查询参数，不是 HTTP 头
        String wsUrl = WS_BASE_URL
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", EDGE_UA)
                .header("Cookie", "muid=" + muid + ";")
                .build();

        httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                ws.send(buildSpeechConfig());
                ws.send(buildSsml(requestId, text));
            }

            @Override
            public void onMessage(WebSocket ws, String msg) {
                if (msg.contains("Path:turn.end")) {
                    ws.close(1000, null);
                    if (mySession == sessionId) {
                        playAudio(audioBuffer.toByteArray(), utteranceId, mySession);
                    }
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                // Binary frame 格式：[2字节 headerLen][header 文本][MP3 数据]
                byte[] data = bytes.toByteArray();
                if (data.length > 2) {
                    int headerLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                    int audioStart = 2 + headerLen;
                    if (audioStart < data.length) {
                        audioBuffer.write(data, audioStart, data.length - audioStart);
                    }
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error: " + t.getMessage());
                consecutiveErrors++;
                if (mySession == sessionId) {
                    if (consecutiveErrors >= ERROR_THRESHOLD) {
                        Log.e(TAG, "Reached error threshold, triggering onFail for fallback");
                        mainHandler.post(() -> callback.onFail());
                    } else {
                        mainHandler.post(() -> {
                            callback.onUtteranceError(utteranceId);
                            processNext(mySession);
                        });
                    }
                }
            }
        });
    }

    private void playAudio(byte[] audioData, String utteranceId, int mySession) {
        consecutiveErrors = 0;  // 成功收到音频，重置错误计数
        if (audioData.length == 0) {
            mainHandler.post(() -> {
                if (mySession == sessionId) {
                    callback.onUtteranceDone(utteranceId);
                    processNext(mySession);
                }
            });
            return;
        }
        try {
            File tempFile = File.createTempFile("edge_tts_", ".mp3", context.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioData);
            }
            mainHandler.post(() -> {
                if (mySession != sessionId) {
                    tempFile.delete();
                    return;
                }
                try {
                    releasePrevPlayer();
                    currentTempFile = tempFile;
                    mediaPlayer = new MediaPlayer();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build());
                    }
                    mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                    mediaPlayer.prepare();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PlaybackParams params = new PlaybackParams();
                        params.setSpeed(speed);
                        mediaPlayer.setPlaybackParams(params);
                    }
                    mediaPlayer.setOnCompletionListener(mp -> {
                        cleanupPlayer();
                        if (mySession == sessionId) {
                            callback.onUtteranceDone(utteranceId);
                            processNext(mySession);
                        }
                    });
                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        cleanupPlayer();
                        if (mySession == sessionId) {
                            callback.onUtteranceError(utteranceId);
                            processNext(mySession);
                        }
                        return true;
                    });
                    mediaPlayer.start();
                } catch (Exception e) {
                    Log.e(TAG, "MediaPlayer error: " + e.getMessage());
                    tempFile.delete();
                    if (mySession == sessionId) {
                        callback.onUtteranceError(utteranceId);
                        processNext(mySession);
                    }
                }
            });
        } catch (IOException e) {
            Log.e(TAG, "Temp file error: " + e.getMessage());
            mainHandler.post(() -> {
                if (mySession == sessionId) {
                    callback.onUtteranceError(utteranceId);
                    processNext(mySession);
                }
            });
        }
    }

    private void releasePrevPlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (currentTempFile != null) {
            currentTempFile.delete();
            currentTempFile = null;
        }
    }

    private void cleanupPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (currentTempFile != null) {
            currentTempFile.delete();
            currentTempFile = null;
        }
    }

    /** 停止播放并清空队列，不改变 ready 状态 */
    private void stopInternal() {
        sessionId++;  // 使所有进行中的回调失效
        speakQueue.clear();
        processing = false;
        mainHandler.post(this::releasePrevPlayer);
    }

    @Override
    public void stop() {
        stopInternal();
    }

    @Override
    public void destroy() {
        stopInternal();
        if (httpClient != null) {
            httpClient.dispatcher().cancelAll();
        }
        ready = false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String getName() {
        return "Edge TTS";
    }

    // ── Edge TTS 协议构建 ───────────────────────────────────────────────────

    private String timestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private String buildSpeechConfig() {
        return "X-Timestamp:" + timestamp() + "\r\n" +
               "Content-Type:application/json; charset=utf-8\r\n" +
               "Path:speech.config\r\n\r\n" +
               "{\"context\":{\"synthesis\":{\"audio\":{" +
               "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
               "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
    }

    private String buildSsml(String requestId, String text) {
        // 转义 XML 特殊字符
        text = text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                      "<voice name='" + VOICE + "'>" +
                      "<prosody rate='" + speedToRate(speed) + "'>" + text + "</prosody>" +
                      "</voice></speak>";
        return "X-RequestId:" + requestId + "\r\n" +
               "Content-Type:application/ssml+xml\r\n" +
               "X-Timestamp:" + timestamp() + "\r\n" +
               "Path:ssml\r\n\r\n" + ssml;
    }

    /** 将播放速度转换为 SSML rate 字符串，例如 1.5 → "+50%" */
    private String speedToRate(float speed) {
        int percent = Math.round((speed - 1.0f) * 100);
        return (percent >= 0 ? "+" : "") + percent + "%";
    }

    /**
     * 计算 Sec-MS-GEC 请求头。
     * 算法（来自 rany2/edge-tts drm.py）：
     * 1. 获取 Unix 时间（秒）
     * 2. 加上 Windows FILETIME epoch 偏移（11644473600 秒）
     * 3. 先在秒级对齐到 300 秒（5 分钟）边界
     * 4. 再乘以 10_000_000 转为 100ns 单位
     * 5. 拼上 TRUSTED_TOKEN 后取 SHA-256 大写十六进制
     */
    private String computeSecMsGec() {
        long unixSec = System.currentTimeMillis() / 1000L;
        long winSec = unixSec + WIN_EPOCH;    // 转为 Windows 时间戳（秒）
        long roundedSec = winSec - (winSec % 300); // 对齐到 5 分钟边界（秒级）
        long ticks100ns = roundedSec * 10_000_000L; // 转为 100ns 单位
        String input = ticks100ns + TRUSTED_TOKEN;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** 生成 16 字节随机大写十六进制字符串，用作 muid cookie */
    private String generateMuid() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
