package com.thl.reader.tts;

import android.content.Context;

/**
 * TTS 引擎抽象接口，系统 TTS 和 Edge TTS 均实现此接口。
 * TtsManager 通过该接口驱动播放，切换引擎时只需替换实现。
 */
public interface TtsEngine {

    interface Callback {
        void onReady();
        void onFail();
        /** chunk 开始朗读 */
        void onUtteranceStart(String utteranceId);
        /** chunk 朗读完毕 */
        void onUtteranceDone(String utteranceId);
        void onUtteranceError(String utteranceId);
        /** 字级别位置回调（系统 TTS 支持，Edge TTS 暂不支持） */
        void onRangeStart(String utteranceId, int charOffset);
    }

    void init(Context context, Callback callback);
    void setSpeed(float speed);

    /**
     * 朗读一段文字。
     * @param flushFirst true=清空队列后重新开始，false=追加到队列末尾
     */
    void speak(String text, String utteranceId, boolean flushFirst);

    void stop();
    void destroy();
    boolean isReady();
    String getName();
}
