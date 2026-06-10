package com.thl.reader.tts;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * 系统 TTS 引擎封装。利用 TextToSpeech 的原生队列机制一次性入队所有 chunk，
 * 由系统按顺序播放，通过 UtteranceProgressListener 回调进度。
 */
public class SystemTtsEngine implements TtsEngine {

    private TextToSpeech tts;
    private boolean ready = false;
    private Callback callback;

    @Override
    public void init(Context context, Callback callback) {
        this.callback = callback;
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    result = tts.setLanguage(Locale.CHINESE);
                }
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ready = false;
                    callback.onFail();
                } else {
                    ready = true;
                    setupListener();
                    callback.onReady();
                }
            } else {
                callback.onFail();
            }
        });
    }

    private void setupListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                callback.onUtteranceStart(utteranceId);
            }

            @Override
            public void onRangeStart(String utteranceId, int start, int end, int frame) {
                callback.onRangeStart(utteranceId, start);
            }

            @Override
            public void onDone(String utteranceId) {
                callback.onUtteranceDone(utteranceId);
            }

            @Override
            public void onError(String utteranceId) {
                callback.onUtteranceError(utteranceId);
            }
        });
    }

    @Override
    public void setSpeed(float speed) {
        if (tts != null) tts.setSpeechRate(speed);
    }

    @Override
    public void speak(String text, String utteranceId, boolean flushFirst) {
        if (!ready) return;
        int mode = flushFirst ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        tts.speak(text, mode, null, utteranceId);
    }

    @Override
    public void stop() {
        if (tts != null) tts.stop();
    }

    @Override
    public void destroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String getName() {
        return "系统TTS";
    }
}
