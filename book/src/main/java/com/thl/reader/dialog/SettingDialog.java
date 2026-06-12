package com.thl.reader.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;

import com.thl.reader.Config;
import com.thl.reader.util.DisplayUtils;
import com.thl.reader.view.CircleImageView;

/**
 * Created by Administrator on 2016/7/26 0026.
 */
public class SettingDialog extends Dialog implements View.OnClickListener {

    TextView tv_dark;
    SeekBar sb_brightness;
    TextView tv_bright;
    TextView tv_xitong;
    TextView tv_subtract;
    TextView tv_size;
    TextView tv_add;
    CircleImageView iv_bg_default;
    CircleImageView iv_bg1;
    CircleImageView iv_bg2;
    CircleImageView iv_bg3;
    CircleImageView iv_bg4;
    CircleImageView iv_bg5;
    TextView tv_size_default;
    TextView tv_line_space_subtract;
    TextView tv_line_space;
    TextView tv_line_space_add;
    TextView tv_line_space_default;
    TextView tv_font_name;
    TextView tv_font_pick;
    TextView tv_font_default;

    private Config config;
    private Boolean isSystem;
    private SettingListener mSettingListener;
    private int FONT_SIZE_MIN;
    private int FONT_SIZE_MAX;
    private int currentFontSize;
    private int LINE_SPACE_MIN;
    private int LINE_SPACE_MAX;
    private int LINE_SPACE_STEP;
    private int currentLineSpace;

    private SettingDialog(Context context, boolean flag, OnCancelListener listener) {
        super(context, flag, listener);
    }

    public SettingDialog(Context context) {
        this(context, com.thl.reader.R.style.setting_dialog);
    }

    public SettingDialog(Context context, int themeResId) {
        super(context, themeResId);

    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setGravity(Gravity.BOTTOM);
        setContentView(com.thl.reader.R.layout.dialog_setting);

        tv_dark = (TextView) findViewById(com.thl.reader.R.id.tv_dark);
        sb_brightness = (SeekBar) findViewById(com.thl.reader.R.id.sb_brightness);
        tv_bright = (TextView) findViewById(com.thl.reader.R.id.tv_bright);
        tv_xitong = (TextView) findViewById(com.thl.reader.R.id.tv_xitong);
        tv_subtract = (TextView) findViewById(com.thl.reader.R.id.tv_subtract);
        tv_size = (TextView) findViewById(com.thl.reader.R.id.tv_size);
        tv_add = (TextView) findViewById(com.thl.reader.R.id.tv_add);
        iv_bg_default = (CircleImageView) findViewById(com.thl.reader.R.id.iv_bg_default);
        iv_bg1 = (CircleImageView) findViewById(com.thl.reader.R.id.iv_bg_1);
        iv_bg2 = (CircleImageView) findViewById(com.thl.reader.R.id.iv_bg_2);
        iv_bg3 = (CircleImageView) findViewById(com.thl.reader.R.id.iv_bg_3);
        iv_bg4 = (CircleImageView) findViewById(com.thl.reader.R.id.iv_bg_4);
        iv_bg5 = (CircleImageView) findViewById(com.thl.reader.R.id.iv_bg_5);
        tv_size_default = (TextView) findViewById(com.thl.reader.R.id.tv_size_default);
        tv_line_space_subtract = (TextView) findViewById(com.thl.reader.R.id.tv_line_space_subtract);
        tv_line_space = (TextView) findViewById(com.thl.reader.R.id.tv_line_space);
        tv_line_space_add = (TextView) findViewById(com.thl.reader.R.id.tv_line_space_add);
        tv_line_space_default = (TextView) findViewById(com.thl.reader.R.id.tv_line_space_default);
        tv_font_name = (TextView) findViewById(com.thl.reader.R.id.tv_font_name);
        tv_font_pick = (TextView) findViewById(com.thl.reader.R.id.tv_font_pick);
        tv_font_default = (TextView) findViewById(com.thl.reader.R.id.tv_font_default);

        findViewById(com.thl.reader.R.id.tv_dark).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_bright).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_xitong).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_subtract).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_add).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_size_default).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.iv_bg_default).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.iv_bg_1).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.iv_bg_2).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.iv_bg_3).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.iv_bg_4).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.iv_bg_5).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_line_space_subtract).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_line_space_add).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_line_space_default).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_font_pick).setOnClickListener(this);
        findViewById(com.thl.reader.R.id.tv_font_default).setOnClickListener(this);

        WindowManager m = getWindow().getWindowManager();
        Display d = m.getDefaultDisplay();
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.width = d.getWidth();
        getWindow().setAttributes(p);

        FONT_SIZE_MIN = (int) getContext().getResources().getDimension(com.thl.reader.R.dimen.reading_min_text_size);
        FONT_SIZE_MAX = (int) getContext().getResources().getDimension(com.thl.reader.R.dimen.reading_max_text_size);
        LINE_SPACE_MIN = (int) getContext().getResources().getDimension(com.thl.reader.R.dimen.reading_min_line_spacing);
        LINE_SPACE_MAX = (int) getContext().getResources().getDimension(com.thl.reader.R.dimen.reading_max_line_spacing);
        LINE_SPACE_STEP = com.thl.reader.util.DisplayUtils.dp2px(getContext(), 2);

        config = Config.getInstance();

        //初始化亮度
        isSystem = config.isSystemLight();
        setTextViewSelect(tv_xitong, isSystem);
        setBrightness(config.getLight());

        //初始化字体大小
        currentFontSize = (int) config.getFontSize();
        tv_size.setText(currentFontSize + "");

        //初始化行间距
        currentLineSpace = (int) config.getLineSpace();
        tv_line_space.setText(com.thl.reader.util.DisplayUtils.px2dp(getContext(), currentLineSpace) + "");

        //初始化字体名显示
        String savedPath = config.getTypefacePath();
        if (savedPath == null || savedPath.equals(com.thl.reader.Config.FONTTYPE_DEFAULT)) {
            tv_font_name.setText("系统默认");
        } else {
            String name = savedPath.contains("/") ? savedPath.substring(savedPath.lastIndexOf('/') + 1) : savedPath;
            if (name.contains(".")) name = name.substring(0, name.lastIndexOf('.'));
            tv_font_name.setText(name);
        }

        //初始化字体
        selectBg(config.getBookBgType());

        // 墨水屏模式开启时：锁定背景选择，显示提示，改为白底黑字
        if (config.isEinkMode()) {
            float dim = 0.35f;
            iv_bg_default.setAlpha(dim); iv_bg_default.setClickable(false);
            iv_bg1.setAlpha(dim); iv_bg1.setClickable(false);
            iv_bg2.setAlpha(dim); iv_bg2.setClickable(false);
            iv_bg3.setAlpha(dim); iv_bg3.setClickable(false);
            iv_bg4.setAlpha(dim); iv_bg4.setClickable(false);
            iv_bg5.setAlpha(dim); iv_bg5.setClickable(false);
            View notice = findViewById(com.thl.reader.R.id.tv_eink_lock_notice);
            if (notice != null) notice.setVisibility(View.VISIBLE);
            applyEinkStyle();
        }

        sb_brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress > 10) {
                    changeBright(false, progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    //选择背景
    /** 墨水屏模式：对话框改为白底黑字（递归遍历所有 TextView，包括无 ID 的标签） */
    private void applyEinkStyle() {
        int white = 0xFFFFFFFF;
        int black = 0xFF000000;
        View root = findViewById(com.thl.reader.R.id.layout_setting_root);
        if (root != null) {
            root.setBackgroundColor(white);
            if (root instanceof android.view.ViewGroup) {
                setAllTextViewsColor((android.view.ViewGroup) root, black);
            }
        }
    }

    private void setAllTextViewsColor(android.view.ViewGroup parent, int color) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(color);
            } else if (child instanceof android.view.ViewGroup) {
                setAllTextViewsColor((android.view.ViewGroup) child, color);
            }
        }
    }

    private void selectBg(int type) {
        switch (type) {
            case Config.BOOK_BG_DEFAULT:
                iv_bg_default.setBorderWidth(DisplayUtils.dp2px(getContext(), 2));
                iv_bg1.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg2.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg3.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg4.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg5.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                break;
            case Config.BOOK_BG_1:
                iv_bg_default.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg1.setBorderWidth(DisplayUtils.dp2px(getContext(), 2));
                iv_bg2.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg3.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg4.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg5.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                break;
            case Config.BOOK_BG_2:
                iv_bg_default.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg1.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg2.setBorderWidth(DisplayUtils.dp2px(getContext(), 2));
                iv_bg3.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg4.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg5.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                break;
            case Config.BOOK_BG_3:
                iv_bg_default.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg1.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg2.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg3.setBorderWidth(DisplayUtils.dp2px(getContext(), 2));
                iv_bg4.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg5.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                break;
            case Config.BOOK_BG_4:
                iv_bg_default.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg1.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg2.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg3.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg4.setBorderWidth(DisplayUtils.dp2px(getContext(), 2));
                iv_bg5.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                break;
            case Config.BOOK_BG_5:
                iv_bg_default.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg1.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg2.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg3.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg4.setBorderWidth(DisplayUtils.dp2px(getContext(), 0));
                iv_bg5.setBorderWidth(DisplayUtils.dp2px(getContext(), 2));
                break;
        }
    }

    //设置字体
    public void setBookBg(int type) {
        config.setBookBg(type);
        if (mSettingListener != null) {
            mSettingListener.changeBookBg(type);
        }
    }


    //设置亮度
    public void setBrightness(float brightness) {
        sb_brightness.setProgress((int) (brightness * 100));
    }

    //设置按钮选择的背景
    private void setTextViewSelect(TextView textView, Boolean isSelect) {
        if (isSelect) {
            textView.setBackgroundDrawable(getContext().getResources().getDrawable(com.thl.reader.R.drawable.button_select_bg));
            textView.setTextColor(getContext().getResources().getColor(com.thl.reader.R.color.read_dialog_button_select));
        } else {
            textView.setBackgroundDrawable(getContext().getResources().getDrawable(com.thl.reader.R.drawable.button_bg));
            textView.setTextColor(getContext().getResources().getColor(com.thl.reader.R.color.white));
        }
    }

    private void applyCompat() {
        if (Build.VERSION.SDK_INT < 19) {
            return;
        }
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);//去掉信息栏
    }

    public Boolean isShow() {
        return isShowing();
    }


    public void onClick(View view) {
        int i = view.getId();
        if (i == com.thl.reader.R.id.tv_dark) {
        } else if (i == com.thl.reader.R.id.tv_bright) {
        } else if (i == com.thl.reader.R.id.tv_xitong) {
            isSystem = !isSystem;
            changeBright(isSystem, sb_brightness.getProgress());

        } else if (i == com.thl.reader.R.id.tv_subtract) {
            subtractFontSize();

        } else if (i == com.thl.reader.R.id.tv_add) {
            addFontSize();

        } else if (i == com.thl.reader.R.id.tv_size_default) {
            defaultFontSize();

        } else if (i == com.thl.reader.R.id.tv_font_pick) {
            if (mSettingListener != null) mSettingListener.pickFont();
        } else if (i == com.thl.reader.R.id.tv_font_default) {
            config.setTypeface(com.thl.reader.Config.FONTTYPE_DEFAULT);
            tv_font_name.setText("系统默认");
            if (mSettingListener != null) mSettingListener.changeTypeFace(android.graphics.Typeface.DEFAULT);

        } else if (i == com.thl.reader.R.id.tv_line_space_subtract) {
            subtractLineSpace();
        } else if (i == com.thl.reader.R.id.tv_line_space_add) {
            addLineSpace();
        } else if (i == com.thl.reader.R.id.tv_line_space_default) {
            defaultLineSpace();

        } else if (i == com.thl.reader.R.id.iv_bg_default) {
            setBookBg(Config.BOOK_BG_DEFAULT);
            selectBg(Config.BOOK_BG_DEFAULT);

        } else if (i == com.thl.reader.R.id.iv_bg_1) {
            setBookBg(Config.BOOK_BG_1);
            selectBg(Config.BOOK_BG_1);

        } else if (i == com.thl.reader.R.id.iv_bg_2) {
            setBookBg(Config.BOOK_BG_2);
            selectBg(Config.BOOK_BG_2);

        } else if (i == com.thl.reader.R.id.iv_bg_3) {
            setBookBg(Config.BOOK_BG_3);
            selectBg(Config.BOOK_BG_3);

        } else if (i == com.thl.reader.R.id.iv_bg_4) {
            setBookBg(Config.BOOK_BG_4);
            selectBg(Config.BOOK_BG_4);

        } else if (i == com.thl.reader.R.id.iv_bg_5) {
            setBookBg(Config.BOOK_BG_5);
            selectBg(Config.BOOK_BG_5);
        }
    }

    //变大书本字体
    private void addFontSize() {
        if (currentFontSize < FONT_SIZE_MAX) {
            currentFontSize += 1;
            tv_size.setText(currentFontSize + "");
            config.setFontSize(currentFontSize);
            if (mSettingListener != null) {
                mSettingListener.changeFontSize(currentFontSize);
            }
        }
    }

    private void defaultFontSize() {
        currentFontSize = (int) getContext().getResources().getDimension(com.thl.reader.R.dimen.reading_default_text_size);
        tv_size.setText(currentFontSize + "");
        config.setFontSize(currentFontSize);
        if (mSettingListener != null) {
            mSettingListener.changeFontSize(currentFontSize);
        }
    }

    //变小书本字体
    private void subtractFontSize() {
        if (currentFontSize > FONT_SIZE_MIN) {
            currentFontSize -= 1;
            tv_size.setText(currentFontSize + "");
            config.setFontSize(currentFontSize);
            if (mSettingListener != null) {
                mSettingListener.changeFontSize(currentFontSize);
            }
        }
    }

    //增大行间距
    private void addLineSpace() {
        if (currentLineSpace < LINE_SPACE_MAX) {
            currentLineSpace += LINE_SPACE_STEP;
            tv_line_space.setText(com.thl.reader.util.DisplayUtils.px2dp(getContext(), currentLineSpace) + "");
            config.setLineSpace(currentLineSpace);
            if (mSettingListener != null) {
                mSettingListener.changeLineSpace(currentLineSpace);
            }
        }
    }

    //减小行间距
    private void subtractLineSpace() {
        if (currentLineSpace > LINE_SPACE_MIN) {
            currentLineSpace -= LINE_SPACE_STEP;
            tv_line_space.setText(com.thl.reader.util.DisplayUtils.px2dp(getContext(), currentLineSpace) + "");
            config.setLineSpace(currentLineSpace);
            if (mSettingListener != null) {
                mSettingListener.changeLineSpace(currentLineSpace);
            }
        }
    }

    //恢复默认行间距
    private void defaultLineSpace() {
        currentLineSpace = (int) getContext().getResources().getDimension(com.thl.reader.R.dimen.reading_line_spacing);
        tv_line_space.setText(com.thl.reader.util.DisplayUtils.px2dp(getContext(), currentLineSpace) + "");
        config.setLineSpace(currentLineSpace);
        if (mSettingListener != null) {
            mSettingListener.changeLineSpace(currentLineSpace);
        }
    }

    //改变亮度
    public void changeBright(Boolean isSystem, int brightness) {
        float light = (float) (brightness / 100.0);
        setTextViewSelect(tv_xitong, isSystem);
        config.setSystemLight(isSystem);
        config.setLight(light);
        if (mSettingListener != null) {
            mSettingListener.changeSystemBright(isSystem, light);
        }
    }

    public void setFontName(String name) {
        if (tv_font_name != null) tv_font_name.setText(name);
    }

    public void setSettingListener(SettingListener settingListener) {
        this.mSettingListener = settingListener;
    }

    public interface SettingListener {
        void changeSystemBright(Boolean isSystem, float brightness);

        void changeFontSize(int fontSize);

        void changeTypeFace(Typeface typeface);

        void changeBookBg(int type);

        void changeLineSpace(int lineSpace);

        void pickFont();
    }

}