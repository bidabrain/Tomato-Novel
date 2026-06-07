package com.thl.book;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.thl.book.network.dto.RankBook;

import java.util.List;

/** Horizontal adapter showing individual book cards inside one category section. */
public class BookCardAdapter extends RecyclerView.Adapter<BookCardAdapter.ViewHolder> {

    public interface OnBookClickListener {
        void onBookClick(RankBook book);
    }

    private List<RankBook> books;
    private final OnBookClickListener listener;

    public BookCardAdapter(List<RankBook> books, OnBookClickListener listener) {
        this.books = books;
        this.listener = listener;
    }

    public void updateBooks(List<RankBook> newBooks) {
        this.books = newBooks;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book_card, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RankBook book = books.get(position);
        holder.tvTitle.setText(book.title);
        holder.tvIntro.setText(book.intro);

        Context ctx = holder.itemView.getContext();
        if (book.cover != null && !book.cover.isEmpty()) {
            Glide.with(ctx)
                    .load(book.cover)
                    .placeholder(R.mipmap.cover_default_new)
                    .error(R.mipmap.cover_default_new)
                    .into(holder.ivCover);
        } else {
            Glide.with(ctx).clear(holder.ivCover);
            holder.ivCover.setImageResource(R.mipmap.cover_default_new);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onBookClick(book);
        });
    }

    @Override
    public int getItemCount() {
        return books == null ? 0 : books.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivCover;
        final TextView tvTitle;
        final TextView tvIntro;

        ViewHolder(View v) {
            super(v);
            ivCover = v.findViewById(R.id.iv_cover);
            tvTitle = v.findViewById(R.id.tv_title);
            tvIntro = v.findViewById(R.id.tv_intro);
        }
    }
}
