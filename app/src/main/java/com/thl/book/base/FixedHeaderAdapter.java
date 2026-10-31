package com.thl.book.base;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 把布局里已有的一块头部视图作为列表第一项，随列表一起滚动（配合 ConcatAdapter 使用）。
 * 视图在 Activity/Fragment 里照常 findViewById 拿引用，再交给本 Adapter 移入列表，引用不受影响。
 */
public class FixedHeaderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final View header;

    /**
     * @param header 头部视图，会从原父布局中移除
     * @param recyclerView 目标列表；头部用负的左右外边距抵消列表的左右 padding，保持原来的通栏宽度
     */
    public FixedHeaderAdapter(View header, RecyclerView recyclerView) {
        this.header = header;
        if (header.getParent() instanceof ViewGroup) {
            ((ViewGroup) header.getParent()).removeView(header);
        }
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -recyclerView.getPaddingLeft();
        lp.rightMargin = -recyclerView.getPaddingRight();
        header.setLayoutParams(lp);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        RecyclerView.ViewHolder holder = new RecyclerView.ViewHolder(header) {};
        // 同一个 View 实例只能有一个 ViewHolder，禁止回收复用
        holder.setIsRecyclable(false);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {}

    @Override
    public int getItemCount() {
        return 1;
    }

    /**
     * 给列表装上「头部 + 内容」：头部在 GridLayoutManager 下占满一整行。
     */
    public static void attach(RecyclerView recyclerView, View header,
                              RecyclerView.Adapter<?> content, int spanCount) {
        FixedHeaderAdapter headerAdapter = new FixedHeaderAdapter(header, recyclerView);
        GridLayoutManager glm = new GridLayoutManager(recyclerView.getContext(), spanCount);
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return position == 0 ? spanCount : 1;
            }
        });
        recyclerView.setLayoutManager(glm);
        recyclerView.setAdapter(new ConcatAdapter(headerAdapter, content));
    }
}
