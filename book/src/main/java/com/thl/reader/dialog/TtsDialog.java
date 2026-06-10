package com.thl.reader.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.thl.reader.util.DisplayUtils;

public class TtsDialog extends Dialog implements View.OnClickListener {

    private TextView tv_tts_play;
    private TextView tv_tts_prev;
    private TextView tv_tts_next;
    private TextView tv_speed_075;
    private TextView tv_speed_100;
    private TextView tv_speed_150;
    private TextView tv_speed_200;
    private TextView tv_engine_system;
    private TextView tv_engine_edge;

    private TtsListener mListener;
    private float currentSpeed = 1.0f;

    public interface TtsListener {
        void onPlay();
        void onPause();
        void onPrevChapter();
        void onNextChapter();
        void onSpeedChange(float speed);
        void onEngineSwitch(boolean useEdge);
    }

    public TtsDialog(Context context) {
        super(context, com.thl.reader.R.style.setting_dialog);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setGravity(Gravity.BOTTOM);
        setContentView(com.thl.reader.R.layout.dialog_tts);

        tv_tts_play      = findViewById(com.thl.reader.R.id.tv_tts_play);
        tv_tts_prev      = findViewById(com.thl.reader.R.id.tv_tts_prev);
        tv_tts_next      = findViewById(com.thl.reader.R.id.tv_tts_next);
        tv_speed_075     = findViewById(com.thl.reader.R.id.tv_speed_075);
        tv_speed_100     = findViewById(com.thl.reader.R.id.tv_speed_100);
        tv_speed_150     = findViewById(com.thl.reader.R.id.tv_speed_150);
        tv_speed_200     = findViewById(com.thl.reader.R.id.tv_speed_200);
        tv_engine_system = findViewById(com.thl.reader.R.id.tv_engine_system);
        tv_engine_edge   = findViewById(com.thl.reader.R.id.tv_engine_edge);

        tv_tts_play.setOnClickListener(this);
        tv_tts_prev.setOnClickListener(this);
        tv_tts_next.setOnClickListener(this);
        tv_speed_075.setOnClickListener(this);
        tv_speed_100.setOnClickListener(this);
        tv_speed_150.setOnClickListener(this);
        tv_speed_200.setOnClickListener(this);
        tv_engine_system.setOnClickListener(this);
        tv_engine_edge.setOnClickListener(this);

        WindowManager m = getWindow().getWindowManager();
        Display d = m.getDefaultDisplay();
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.width = d.getWidth();
        getWindow().setAttributes(p);

        selectSpeed(1.0f);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == com.thl.reader.R.id.tv_tts_play) {
            if (mListener != null) {
                if (tv_tts_play.getText().toString().startsWith("▶")) {
                    mListener.onPlay();
                } else {
                    mListener.onPause();
                }
            }
        } else if (id == com.thl.reader.R.id.tv_tts_prev) {
            if (mListener != null) mListener.onPrevChapter();
        } else if (id == com.thl.reader.R.id.tv_tts_next) {
            if (mListener != null) mListener.onNextChapter();
        } else if (id == com.thl.reader.R.id.tv_speed_075) {
            changeSpeed(0.75f);
        } else if (id == com.thl.reader.R.id.tv_speed_100) {
            changeSpeed(1.0f);
        } else if (id == com.thl.reader.R.id.tv_speed_150) {
            changeSpeed(1.5f);
        } else if (id == com.thl.reader.R.id.tv_speed_200) {
            changeSpeed(2.0f);
        } else if (id == com.thl.reader.R.id.tv_engine_system) {
            selectEngine(false);
            if (mListener != null) mListener.onEngineSwitch(false);
        } else if (id == com.thl.reader.R.id.tv_engine_edge) {
            selectEngine(true);
            if (mListener != null) mListener.onEngineSwitch(true);
        }
    }

    private void changeSpeed(float speed) {
        currentSpeed = speed;
        selectSpeed(speed);
        if (mListener != null) mListener.onSpeedChange(speed);
    }

    public void setPlayState(boolean isPlaying) {
        if (tv_tts_play != null) {
            tv_tts_play.setText(isPlaying ? "⏸ 暂停" : "▶ 播放");
        }
    }

    /** 同步高亮当前引擎按钮，由外部在 onEngineChanged 回调中调用 */
    public void setEngine(boolean isEdge) {
        if (tv_engine_system == null || tv_engine_edge == null) return;
        selectEngine(isEdge);
    }

    private void selectEngine(boolean isEdge) {
        if (tv_engine_system == null || tv_engine_edge == null) return;
        int selected = com.thl.reader.R.drawable.button_select_bg;
        int normal   = com.thl.reader.R.drawable.button_bg;
        int selColor = getContext().getResources().getColor(com.thl.reader.R.color.read_dialog_button_select);
        int norColor = getContext().getResources().getColor(com.thl.reader.R.color.white);

        tv_engine_system.setBackgroundDrawable(getContext().getResources().getDrawable(isEdge ? normal : selected));
        tv_engine_edge.setBackgroundDrawable(getContext().getResources().getDrawable(isEdge ? selected : normal));
        tv_engine_system.setTextColor(isEdge ? norColor : selColor);
        tv_engine_edge.setTextColor(isEdge ? selColor : norColor);
    }

    private void selectSpeed(float speed) {
        tv_speed_075.setBackgroundDrawable(getContext().getResources().getDrawable(
                speed == 0.75f ? com.thl.reader.R.drawable.button_select_bg : com.thl.reader.R.drawable.button_bg));
        tv_speed_100.setBackgroundDrawable(getContext().getResources().getDrawable(
                speed == 1.0f ? com.thl.reader.R.drawable.button_select_bg : com.thl.reader.R.drawable.button_bg));
        tv_speed_150.setBackgroundDrawable(getContext().getResources().getDrawable(
                speed == 1.5f ? com.thl.reader.R.drawable.button_select_bg : com.thl.reader.R.drawable.button_bg));
        tv_speed_200.setBackgroundDrawable(getContext().getResources().getDrawable(
                speed == 2.0f ? com.thl.reader.R.drawable.button_select_bg : com.thl.reader.R.drawable.button_bg));

        int selectedColor = getContext().getResources().getColor(com.thl.reader.R.color.read_dialog_button_select);
        int normalColor   = getContext().getResources().getColor(com.thl.reader.R.color.white);
        tv_speed_075.setTextColor(speed == 0.75f ? selectedColor : normalColor);
        tv_speed_100.setTextColor(speed == 1.0f  ? selectedColor : normalColor);
        tv_speed_150.setTextColor(speed == 1.5f  ? selectedColor : normalColor);
        tv_speed_200.setTextColor(speed == 2.0f  ? selectedColor : normalColor);
    }

    public void setTtsListener(TtsListener listener) {
        mListener = listener;
    }
}
