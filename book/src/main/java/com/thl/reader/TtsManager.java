package com.thl.reader;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.thl.reader.util.PageFactory;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TtsManager {

    private static final int CHUNK_SIZE = 1500;
    private static final String CHUNK_PREFIX = "chunk_";
    // 翻页后锁定时间（ms），等待 changeChapter 异步完成，防止重复翻页
    private static final int SYNC_LOCK_MS = 600;

    private TextToSpeech tts;
    private boolean isInitialized = false;
    private boolean isPlaying = false;
    private float speed = 1.0f;

    private final PageFactory pageFactory;
    private TtsListener listener;

    private List<String> textChunks = new ArrayList<>();
    private List<Long> chunkAbsPositions = new ArrayList<>();  // 每个 chunk 在书中的绝对字符位置
    private int currentChunkIndex = 0;

    // 翻页节流：翻页后锁定一段时间，避免异步更新期间重复触发
    private volatile boolean syncLocked = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface TtsListener {
        void onInitSuccess();
        void onInitFail();
        void onPlayStateChanged(boolean isPlaying);
        void onPageSync(long absPosition);
    }

    public TtsManager(Context context, PageFactory pageFactory, TtsListener listener) {
        this.pageFactory = pageFactory;
        this.listener = listener;

        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = tts.setLanguage(Locale.CHINESE);
                }
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    isInitialized = false;
                    mainHandler.post(() -> { if (listener != null) listener.onInitFail(); });
                } else {
                    isInitialized = true;
                    setupUtteranceListener();
                    mainHandler.post(() -> { if (listener != null) listener.onInitSuccess(); });
                }
            } else {
                mainHandler.post(() -> { if (listener != null) listener.onInitFail(); });
            }
        });
    }

    private void setupUtteranceListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                if (utteranceId.startsWith(CHUNK_PREFIX)) {
                    try {
                        currentChunkIndex = Integer.parseInt(utteranceId.substring(CHUNK_PREFIX.length()));
                        if (currentChunkIndex < chunkAbsPositions.size()) {
                            trySync(chunkAbsPositions.get(currentChunkIndex));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            // API 26+ 字级别回调，精度更高
            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                if (utteranceId.startsWith(CHUNK_PREFIX)) {
                    try {
                        int idx = Integer.parseInt(utteranceId.substring(CHUNK_PREFIX.length()));
                        if (idx < chunkAbsPositions.size()) {
                            trySync(chunkAbsPositions.get(idx) + start);
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            @Override
            public void onDone(String utteranceId) {
                String lastId = CHUNK_PREFIX + (textChunks.size() - 1);
                if (utteranceId.equals(lastId)) {
                    isPlaying = false;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onPlayStateChanged(false);
                        // 自动翻到下一章
                        int next = pageFactory.getCurrentCharter() + 1;
                        if (next < pageFactory.getDirectoryList().size()) {
                            pageFactory.nextChapter();
                            play();
                        }
                    });
                }
            }

            @Override
            public void onError(String utteranceId) {
                isPlaying = false;
                mainHandler.post(() -> { if (listener != null) listener.onPlayStateChanged(false); });
            }
        });
    }

    /**
     * 只有当 TTS 当前位置超过页面末尾时才翻页。
     * 翻页后锁定 SYNC_LOCK_MS，等待 changeChapter 异步完成，避免连续多次翻页。
     */
    private void trySync(long pos) {
        if (syncLocked) return;
        if (pageFactory.getCurrentPage() == null) return;

        long pageEnd = pageFactory.getCurrentPage().getEnd();
        if (pos <= pageEnd) return;  // 还在当前页范围内，不翻页

        syncLocked = true;
        mainHandler.post(() -> {
            if (listener != null) listener.onPageSync(pos);
            mainHandler.postDelayed(() -> syncLocked = false, SYNC_LOCK_MS);
        });
    }

    public void play() {
        if (!isInitialized) return;
        int chapterIndex = pageFactory.getCurrentCharter();

        // 当前页精确起始位置
        long currentPageBegin = 0;
        if (pageFactory.getCurrentPage() != null) {
            currentPageBegin = pageFactory.getCurrentPage().getBegin();
        }

        // 章节在书中的绝对起始位置
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

        // 找到包含当前页位置的 chunk
        int startChunk = 0;
        for (int i = 0; i < chunkAbsPositions.size(); i++) {
            if (chunkAbsPositions.get(i) <= currentPageBegin) {
                startChunk = i;
            } else {
                break;
            }
        }

        // 裁剪第一个 chunk：去掉当前页之前的部分，确保从当前页第一个字开始朗读
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
        tts.setSpeechRate(speed);
        speakFrom(startChunk);
        isPlaying = true;
        if (listener != null) listener.onPlayStateChanged(true);
    }

    public void pause() {
        if (!isInitialized || !isPlaying) return;
        tts.stop();
        isPlaying = false;
        if (listener != null) listener.onPlayStateChanged(false);
    }

    public void resume() {
        if (!isInitialized || isPlaying || textChunks.isEmpty()) return;
        syncLocked = false;
        tts.setSpeechRate(speed);
        speakFrom(currentChunkIndex);
        isPlaying = true;
        if (listener != null) listener.onPlayStateChanged(true);
    }

    public void stop() {
        if (!isInitialized) return;
        mainHandler.removeCallbacks(pendingSeek);
        tts.stop();
        isPlaying = false;
        textChunks.clear();
        chunkAbsPositions.clear();
        currentChunkIndex = 0;
        syncLocked = false;
        if (listener != null) listener.onPlayStateChanged(false);
    }

    /**
     * 手动翻页后调用：立即停止当前播放，300ms 后从新页面重新开始。
     * 300ms debounce 可以吸收用户连续快速翻页的情况，只从最终落脚页开始读。
     */
    public void seekToCurrentPage() {
        if (!isInitialized || !isPlaying) return;
        // 清空旧 chunk 列表，防止 onDone 误触发自动翻章
        textChunks.clear();
        chunkAbsPositions.clear();
        tts.stop();
        syncLocked = false;
        mainHandler.removeCallbacks(pendingSeek);
        mainHandler.postDelayed(pendingSeek, 300);
    }

    private final Runnable pendingSeek = () -> {
        if (isInitialized && isPlaying) play();
    };

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
        if (tts != null) tts.setSpeechRate(newSpeed);
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void destroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        isPlaying = false;
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private void speakFrom(int startIndex) {
        for (int i = startIndex; i < textChunks.size(); i++) {
            int mode = (i == startIndex) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            tts.speak(textChunks.get(i), mode, null, CHUNK_PREFIX + i);
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
