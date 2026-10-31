package com.thl.reader.util;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

import com.thl.reader.Config;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

/**
 * 磨砂玻璃效果统一入口。三种状态：
 * - E-ink 模式：关闭模糊并停止刷新（避免墨水屏频繁重绘残影），使用不透明背景；
 * - Android 12+：BlurView 硬件模糊 + 半透明叠加色；
 * - Android 12 以下：不模糊，仅用半透明叠加色作为背景（与 weiboX/haze 的降级一致）。
 */
public final class GlassHelper {

    private static final float BLUR_RADIUS = 20f;

    /** 已 setupWith 过的 BlurView，避免重复初始化 */
    private static final Set<BlurView> sSetup =
            Collections.newSetFromMap(new WeakHashMap<>());

    private GlassHelper() {}

    /** 系统是否支持硬件模糊（BlurTarget 的 RenderNode 路径要求 API 31） */
    public static boolean isBlurSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** 当前是否应显示磨砂玻璃（E-ink 模式下关闭） */
    public static boolean isGlassEnabled(Context context) {
        return !Config.createConfig(context).isEinkMode();
    }

    /**
     * 按当前 E-ink 设置应用到一个 BlurView。
     *
     * @param tint  磨砂玻璃的半透明叠加色
     * @param solid 关闭磨砂玻璃（E-ink）时的不透明背景色
     */
    public static void apply(BlurView view, BlurTarget target, int tint, int solid) {
        apply(view, target, tint, solid, isGlassEnabled(view.getContext()));
    }

    public static void apply(BlurView view, BlurTarget target, int tint, int solid, boolean glass) {
        if (glass && isBlurSupported()) {
            if (!sSetup.contains(view)) {
                view.setupWith(target).setBlurRadius(BLUR_RADIUS);
                sSetup.add(view);
            }
            view.setOverlayColor(tint);
            view.setBlurEnabled(true);
            view.setBlurAutoUpdate(true);
            view.setBackgroundColor(Color.TRANSPARENT);
        } else {
            if (sSetup.contains(view)) {
                view.setBlurAutoUpdate(false);
                view.setBlurEnabled(false);
            }
            view.setBackgroundColor(glass ? tint : solid);
        }
    }
}
