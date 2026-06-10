package com.thl.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.thl.reader.tts.EdgeTtsEngine;
import com.thl.reader.tts.SystemTtsEngine;
import com.thl.reader.tts.TtsEngine;
import com.thl.reader.util.PageFactory;

import java.util.ArrayList;
import java.util.List;

public class TtsManager {

    private static final int CHUNK_SIZE = 1500;
    private static final String CHUNK_PREFIX = "chunk_";
    private static final int SYNC_LOCK_MS = 600;

    private TtsEngine engine;
    private boolean isPlaying = false;
    private float speed = 1.0f;

    private final Context context;
    private final PageFactory pageFactory;
    private TtsListener listener;

    private List<String> textChunks = new ArrayList<>();
    private List<Long> chunkAbsPositions = new ArrayList<>();
    private int currentChunkIndex = 0;
    private volatile boolean syncLocked = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface TtsListener {
        void onInitSuccess();
        void onInitFail();
        void onPlayStateChanged(boolean isPlaying);
        void onPageSync(long absPosition);
        /** 引擎切换完成（isEdge=true 代表当前是 Edge TTS） */
        void onEngineChanged(boolean isEdge);
        /** Edge TTS 连接失败，已自动回退到系统 TTS */
        void onEdgeTtsFallback();
    }

    public TtsManager(Context context, PageFactory pageFactory, TtsListener listener) {
        this.context = context;
        this.pageFactory = pageFactory;
        this.listener = listener;
        initEngine(Config.getInstance().isEdgeTts());
    }

    // ── 引擎初始化 ────────────────────────────────────────────────────────────

    private final TtsEngine.Callback engineCallback = new TtsEngine.Callback() {
        @Override public void onReady() {
            mainHandler.post(() -> { if (listener != null) listener.onInitSuccess(); });
        }
        @Override public void onFail() {
            mainHandler.post(() -> {
                if (listener != null) listener.onInitFail();
                // Edge TTS 连接失败，自动回退到系统 TTS
                if (engine instanceof EdgeTtsEngine) {
                    boolean wasPlaying = isPlaying;
                    isPlaying = false;
                    Config.getInstance().setEdgeTts(false);
                    initEngine(false);
                    if (listener != null) {
                        listener.onEngineChanged(false);
                        listener.onEdgeTtsFallback();
                    }
                    if (wasPlaying) {
                        mainHandler.postDelayed(() -> {
                            if (engine.isReady()) play();
                        }, 300);
                    }
                }
            });
        }
        @Override public void onUtteranceStart(String utteranceId) {
            if (utteranceId.startsWith(CHUNK_PREFIX)) {
                try {
                    currentChunkIndex = Integer.parseInt(utteranceId.substring(CHUNK_PREFIX.length()));
                    if (currentChunkIndex < chunkAbsPositions.size()) {
                        trySync(chunkAbsPositions.get(currentChunkIndex));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        @Override public void onUtteranceDone(String utteranceId) {
            String lastId = CHUNK_PREFIX + (textChunks.size() - 1);
            if (utteranceId.equals(lastId)) {
                isPlaying = false;
                mainHandler.post(() -> {
                    if (listener != null) listener.onPlayStateChanged(false);
                    int next = pageFactory.getCurrentCharter() + 1;
                    if (next < pageFactory.getDirectoryList().size()) {
                        pageFactory.nextChapter();
                        play();
                    }
                });
            }
        }
        @Override public void onUtteranceError(String utteranceId) {
            isPlaying = false;
            mainHandler.post(() -> { if (listener != null) listener.onPlayStateChanged(false); });
        }
        @Override public void onRangeStart(String utteranceId, int charOffset) {
            if (utteranceId.startsWith(CHUNK_PREFIX)) {
                try {
                    int idx = Integer.parseInt(utteranceId.substring(CHUNK_PREFIX.length()));
                    if (idx < chunkAbsPositions.size()) {
                        trySync(chunkAbsPositions.get(idx) + charOffset);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    };

    private void initEngine(boolean useEdge) {
        if (engine != null) engine.destroy();
        engine = useEdge ? new EdgeTtsEngine() : new SystemTtsEngine();
        engine.init(context, engineCallback);
        engine.setSpeed(speed);
    }

    // ── 引擎切换 ──────────────────────────────────────────────────────────────

    /**
     * 切换引擎。如果当前正在播放，切换后自动从当前页继续。
     */
    public void switchEngine(boolean useEdge) {
        Config.getInstance().setEdgeTts(useEdge);
        boolean wasPlaying = isPlaying;
        if (wasPlaying) stop();

        textChunks.clear();
        chunkAbsPositions.clear();
        syncLocked = false;

        initEngine(useEdge);

        if (listener != null) listener.onEngineChanged(useEdge);

        if (wasPlaying) {
            // 等引擎初始化完成后在 onReady 回调里不自动播放，
            // 由于 EdgeTtsEngine.init() 是同步 ready，这里直接 play。
            // SystemTtsEngine 是异步 ready，play() 会在 onReady 后被调用。
            // 用延迟确保两种引擎都已 ready。
            mainHandler.postDelayed(() -> {
                if (engine.isReady()) play();
            }, 200);
        }
    }

    // ── 播放控制 ──────────────────────────────────────────────────────────────

    public void play() {
        if (!engine.isReady()) return;
        int chapterIndex = pageFactory.getCurrentCharter();

        long currentPageBegin = 0;
        if (pageFactory.getCurrentPage() != null) {
            currentPageBegin = pageFactory.getCurrentPage().getBegin();
        }

        long chapterStartPos = 0;
        if (!pageFactory.getDirectoryList().isEmpty() && chapterIndex < pageFactory.getDirectoryList().size()) {
            chapterStartPos = pageFactory.getDirectoryList().get(chapterIndex).getBookCatalogueStartPos();
        } else if (pageFactory.getCurrentPage() != null) {
            chapterStartPos = currentPageBegin;
        }

        String text = pageFactory.getChapterText(chapterIndex);
        if (text == null || text.trim().isEmpty()) return;

        chunkAbsPositions = new ArrayList<>();
        textChunks = splitText(text, chapterStartPos, chunkAbsPositions);

        int startChunk = 0;
        for (int i = 0; i < chunkAbsPositions.size(); i++) {
            if (chunkAbsPositions.get(i) <= currentPageBegin) startChunk = i;
            else break;
        }

        // 裁剪首个 chunk，从当前页精确位置开始
        if (startChunk < chunkAbsPositions.size()) {
            long chunkStart = chunkAbsPositions.get(startChunk);
            int trimOffset = (int) (currentPageBegin - chunkStart);
            if (trimOffset > 0 && trimOffset < textChunks.get(startChunk).length()) {
                textChunks.set(startChunk, textChunks.get(startChunk).substring(trimOffset));
                chunkAbsPositions.set(startChunk, currentPageBegin);
            }
        }

        currentChunkIndex = startChunk;
        syncLocked = false;
        engine.setSpeed(speed);
        speakFrom(startChunk);
        isPlaying = true;
        if (listener != null) listener.onPlayStateChanged(true);
    }

    public void pause() {
        if (!engine.isReady() || !isPlaying) return;
        engine.stop();
        isPlaying = false;
        if (listener != null) listener.onPlayStateChanged(false);
    }

    public void resume() {
        if (!engine.isReady() || isPlaying) return;
        // chunks 为空说明还没有 play() 过，直接走 play() 从当前页加载
        if (textChunks.isEmpty()) {
            play();
            return;
        }
        syncLocked = false;
        engine.setSpeed(speed);
        speakFrom(currentChunkIndex);
        isPlaying = true;
        if (listener != null) listener.onPlayStateChanged(true);
    }

    public void stop() {
        if (!engine.isReady()) return;
        mainHandler.removeCallbacks(pendingSeek);
        engine.stop();
        isPlaying = false;
        textChunks.clear();
        chunkAbsPositions.clear();
        currentChunkIndex = 0;
        syncLocked = false;
        if (listener != null) listener.onPlayStateChanged(false);
    }

    public void nextChapter() {
        stop();
        pageFactory.nextChapter();
        play();
    }

    public void prevChapter() {
        stop();
        pageFactory.preChapter();
        play();
    }

    public void setSpeed(float newSpeed) {
        this.speed = newSpeed;
        engine.setSpeed(newSpeed);
    }

    public void seekToCurrentPage() {
        if (!engine.isReady() || !isPlaying) return;
        textChunks.clear();
        chunkAbsPositions.clear();
        engine.stop();
        syncLocked = false;
        mainHandler.removeCallbacks(pendingSeek);
        mainHandler.postDelayed(pendingSeek, 300);
    }

    private final Runnable pendingSeek = () -> {
        if (engine.isReady() && isPlaying) play();
    };

    public boolean isPlaying() { return isPlaying; }
    public boolean isInitialized() { return engine.isReady(); }
    public boolean isEdgeTts() { return engine instanceof EdgeTtsEngine; }

    public void destroy() {
        if (engine != null) {
            engine.destroy();
            engine = null;
        }
        isPlaying = false;
    }

    // ── 翻页同步 ──────────────────────────────────────────────────────────────

    private void trySync(long pos) {
        if (syncLocked) return;
        if (pageFactory.getCurrentPage() == null) return;
        long pageEnd = pageFactory.getCurrentPage().getEnd();
        if (pos <= pageEnd) return;
        syncLocked = true;
        mainHandler.post(() -> {
            if (listener != null) listener.onPageSync(pos);
            mainHandler.postDelayed(() -> syncLocked = false, SYNC_LOCK_MS);
        });
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private void speakFrom(int startIndex) {
        for (int i = startIndex; i < textChunks.size(); i++) {
            engine.speak(textChunks.get(i), CHUNK_PREFIX + i, i == startIndex);
        }
    }

    private List<String> splitText(String text, long chapterStartPos, List<Long> absPositions) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\r?\n", -1);
        StringBuilder current = new StringBuilder();
        long currentOffset = 0;
        long pendingOffset = 0;

        for (String para : paragraphs) {
            if (current.length() + para.length() > CHUNK_SIZE && current.length() > 0) {
                chunks.add(current.toString());
                absPositions.add(chapterStartPos + currentOffset);
                currentOffset = pendingOffset;
                current = new StringBuilder();
            }
            if (para.length() > CHUNK_SIZE) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    absPositions.add(chapterStartPos + currentOffset);
                    currentOffset = pendingOffset;
                    current = new StringBuilder();
                }
                for (int i = 0; i < para.length(); i += CHUNK_SIZE) {
                    String slice = para.substring(i, Math.min(i + CHUNK_SIZE, para.length()));
                    chunks.add(slice);
                    absPositions.add(chapterStartPos + pendingOffset + i);
                }
                pendingOffset += para.length() + 1;
                currentOffset = pendingOffset;
            } else {
                current.append(para).append("\n");
                pendingOffset += para.length() + 1;
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
            absPositions.add(chapterStartPos + currentOffset);
        }
        return chunks;
    }
}
