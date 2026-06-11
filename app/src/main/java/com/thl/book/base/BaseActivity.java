package com.thl.book.base;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.thl.book.R;

/**
 * 功能描述：activity 基类
 **/
public abstract class BaseActivity extends AppCompatActivity {

    protected Activity mContext;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // 状态栏/导航栏透明，内容延伸其后
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(initLayout());
        // 白色 title bar → 状态栏图标用深色
        WindowInsetsControllerCompat ctrl =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        ctrl.setAppearanceLightStatusBars(true);
        ctrl.setAppearanceLightNavigationBars(true);
        applyEdgeToEdgeInsets();
        initView();
        initData(savedInstanceState);
        initListener();
    }

    /**
     * 通用 edge-to-edge inset 处理：
     * - title_bar：加 statusBar 高度作为 paddingTop（背景自动延伸到状态栏）
     * - bottom_nav：加 navigationBar 高度作为 paddingBottom（底部导航延伸）
     * - bottom_action：同上（底部操作栏，如保存按钮区域）
     */
    private void applyEdgeToEdgeInsets() {
        // ── 顶部：状态栏 inset → title bar ────────────────────────────────
        View titleBar = findViewById(R.id.title_bar);
        if (titleBar != null) {
            ViewCompat.setOnApplyWindowInsetsListener(titleBar, (v, insets) -> {
                int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(0, top, 0, 0);
                return insets;
            });
        }

        // ── 底部：导航栏 inset → bottom_nav 或 bottom_action 或 根视图 ──
        View bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            final int origBottom = bottomNav.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
                int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        origBottom + nav);
                return insets;
            });
            return;
        }
        View bottomAction = findViewById(R.id.bottom_action);
        if (bottomAction != null) {
            final int origBottom = bottomAction.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(bottomAction, (v, insets) -> {
                int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        origBottom + nav);
                return insets;
            });
            return;
        }
        // 无 bottom_nav / bottom_action：root 加底部 padding，防内容被导航栏遮挡
        View root = getWindow().getDecorView().findViewById(android.R.id.content);
        if (root instanceof android.view.ViewGroup) {
            View child = ((android.view.ViewGroup) root).getChildAt(0);
            if (child != null) {
                final int origBottom = child.getPaddingBottom();
                ViewCompat.setOnApplyWindowInsetsListener(child, (v, insets) -> {
                    int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                            origBottom + nav);
                    return insets;
                });
            }
        }
    }

    protected void initListener() {
    }

    protected abstract void initView();

    protected abstract void initData(Bundle savedInstanceState);

    protected abstract int initLayout();

}