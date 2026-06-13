package com.thl.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;

public class Config {
    private final static String SP_NAME = "config";
    private final static String BOOK_BG_KEY = "bookbg";
    private final static String FONT_TYPE_KEY = "fonttype";
    private final static String FONT_SIZE_KEY = "fontsize";
    private final static String NIGHT_KEY = "night";
    private final static String LIGHT_KEY = "light";
    private final static String SYSTEM_LIGHT_KEY = "systemlight";
    private final static String PAGE_MODE_KEY = "pagemode";
    private final static String LINE_SPACE_KEY = "linespace";
    private final static String EINK_KEY = "eink";
    private final static String EDGE_TTS_KEY = "edge_tts";
    private final static String TTS_NOTICE_SHOWN_KEY = "tts_notice_shown";
    private final static String SINGLE_HAND_KEY = "single_hand";
    // 开启墨水屏前保存的原始设置，用于关闭时恢复
    private final static String PRE_EINK_PAGE_MODE_KEY = "pre_eink_page_mode";
    private final static String PRE_EINK_BOOK_BG_KEY = "pre_eink_book_bg";

    public final static String FONTTYPE_DEFAULT = "";

    public final static int BOOK_BG_DEFAULT = 0;
    public final static int BOOK_BG_1 = 1;
    public final static int BOOK_BG_2 = 2;
    public final static int BOOK_BG_3 = 3;
    public final static int BOOK_BG_4 = 4;

    public final static int PAGE_MODE_SIMULATION = 0;
    public final static int PAGE_MODE_COVER = 1;
    public final static int PAGE_MODE_SLIDE = 2;
    public final static int PAGE_MODE_NONE = 3;

    public final static int BOOK_BG_5 = 5;

    private Context mContext;
    private static Config config;
    private SharedPreferences sp;
    //字体
    private Typeface typeface;
    //字体大小
    private float mFontSize = 0;
    //行间距
    private float mLineSpace = 0;
    //亮度值
    private float light = 0;
    private int bookBG;

    private Config(Context mContext) {
        this.mContext = mContext.getApplicationContext();
        sp = this.mContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized Config getInstance() {
        return config;
    }

    public static synchronized Config createConfig(Context context) {
        if (config == null) {
            config = new Config(context);
        }

        return config;
    }

    public int getPageMode() {
        return sp.getInt(PAGE_MODE_KEY, PAGE_MODE_SIMULATION);
    }

    public void setPageMode(int pageMode) {
        sp.edit().putInt(PAGE_MODE_KEY, pageMode).commit();
    }

    public boolean isEinkMode() {
        return sp.getBoolean(EINK_KEY, false);
    }

    public void setEinkMode(boolean isEink) {
        sp.edit().putBoolean(EINK_KEY, isEink).commit();
    }

    public boolean isEdgeTts() {
        return sp.getBoolean(EDGE_TTS_KEY, true);  // 默认使用 Edge TTS
    }

    public void setEdgeTts(boolean useEdge) {
        sp.edit().putBoolean(EDGE_TTS_KEY, useEdge).commit();
    }

    public boolean isTtsNoticeShown() {
        return sp.getBoolean(TTS_NOTICE_SHOWN_KEY, false);
    }

    public void setTtsNoticeShown() {
        sp.edit().putBoolean(TTS_NOTICE_SHOWN_KEY, true).commit();
    }

    public boolean isSingleHandMode() {
        return sp.getBoolean(SINGLE_HAND_KEY, false);
    }

    public void setSingleHandMode(boolean enabled) {
        sp.edit().putBoolean(SINGLE_HAND_KEY, enabled).commit();
    }

    public int getPreEinkPageMode() {
        return sp.getInt(PRE_EINK_PAGE_MODE_KEY, PAGE_MODE_SIMULATION);
    }

    public void setPreEinkPageMode(int mode) {
        sp.edit().putInt(PRE_EINK_PAGE_MODE_KEY, mode).commit();
    }

    public int getPreEinkBookBg() {
        return sp.getInt(PRE_EINK_BOOK_BG_KEY, BOOK_BG_DEFAULT);
    }

    public void setPreEinkBookBg(int bg) {
        sp.edit().putInt(PRE_EINK_BOOK_BG_KEY, bg).commit();
    }

    public int getBookBgType() {
        return sp.getInt(BOOK_BG_KEY, BOOK_BG_DEFAULT);
    }

    public void setBookBg(int type) {
        sp.edit().putInt(BOOK_BG_KEY, type).commit();
    }

    public Typeface getTypeface(String typeFacePath) {
        Typeface mTypeface;
        if (typeFacePath == null || typeFacePath.equals(FONTTYPE_DEFAULT)) {
            mTypeface = Typeface.DEFAULT;
        } else if (typeFacePath.startsWith("/")) {
            // 本地文件路径（用户导入）
            try {
                mTypeface = Typeface.createFromFile(typeFacePath);
            } catch (Exception e) {
                mTypeface = Typeface.DEFAULT;
            }
        } else {
            mTypeface = Typeface.createFromAsset(mContext.getAssets(), typeFacePath);
        }
        return mTypeface;
    }

    public void setTypeface(String typefacePath) {
        typeface = getTypeface(typefacePath);
        sp.edit().putString(FONT_TYPE_KEY, typefacePath).commit();
    }

    public String getTypefacePath() {
        return sp.getString(FONT_TYPE_KEY, FONTTYPE_DEFAULT);
    }

    public float getFontSize() {
        if (mFontSize == 0) {
            mFontSize = sp.getFloat(FONT_SIZE_KEY, mContext.getResources().getDimension(R.dimen.reading_default_text_size));
        }
        return mFontSize;
    }

    public void setFontSize(float fontSize) {
        mFontSize = fontSize;
        sp.edit().putFloat(FONT_SIZE_KEY, fontSize).commit();
    }

    public float getLineSpace() {
        if (mLineSpace == 0) {
            mLineSpace = sp.getFloat(LINE_SPACE_KEY, mContext.getResources().getDimension(R.dimen.reading_line_spacing));
        }
        return mLineSpace;
    }

    public void setLineSpace(float lineSpace) {
        mLineSpace = lineSpace;
        sp.edit().putFloat(LINE_SPACE_KEY, lineSpace).commit();
    }

    /**
     * 获取夜间还是白天阅读模式,true为夜晚，false为白天
     */
    public boolean getDayOrNight() {
        return sp.getBoolean(NIGHT_KEY, false);
    }

    public void setDayOrNight(boolean isNight) {
        sp.edit().putBoolean(NIGHT_KEY, isNight).commit();
    }

    public Boolean isSystemLight() {
        return sp.getBoolean(SYSTEM_LIGHT_KEY, true);
    }

    public void setSystemLight(Boolean isSystemLight) {
        sp.edit().putBoolean(SYSTEM_LIGHT_KEY, isSystemLight).commit();
    }

    public float getLight() {
        if (light == 0) {
            light = sp.getFloat(LIGHT_KEY, 0.1f);
        }
        return light;
    }

    /**
     * 记录配置文件中亮度值
     */
    public void setLight(float light) {
        this.light = light;
        sp.edit().putFloat(LIGHT_KEY, light).commit();
    }
}
