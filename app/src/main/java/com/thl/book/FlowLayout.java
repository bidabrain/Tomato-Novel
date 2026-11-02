package com.thl.book;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * 流式布局：子视图按自身宽度从左到右排列，放不下就换行。
 * 用于书城“更多分类”面板，分类名按字数占宽，窄屏只是多几行，不会截断。
 */
public class FlowLayout extends ViewGroup {

    private int hSpacing;
    private int vSpacing;

    public FlowLayout(Context context) {
        super(context);
    }

    public FlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** 子视图之间的横向 / 纵向间距（px） */
    public void setSpacing(int horizontal, int vertical) {
        hSpacing = horizontal;
        vSpacing = vertical;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int childWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int x = 0, lineHeight = 0, height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childWidthSpec, childHeightSpec);
            int w = child.getMeasuredWidth();
            if (x > 0 && x + w > maxWidth) {
                height += lineHeight + vSpacing;
                x = 0;
                lineHeight = 0;
            }
            x += w + hSpacing;
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
        height += lineHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = getPaddingTop(), lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > maxWidth) {
                y += lineHeight + vSpacing;
                x = 0;
                lineHeight = 0;
            }
            int left = getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w + hSpacing;
            lineHeight = Math.max(lineHeight, h);
        }
    }
}
