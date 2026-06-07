package com.thl.book;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.thl.book.network.dto.RankCategory;

import java.util.List;

/** Vertical adapter showing one section per book category. */
public class BookCategoryAdapter extends RecyclerView.Adapter<BookCategoryAdapter.ViewHolder> {

    public interface OnRefreshListener {
        void onRefresh(int position);
    }

    private List<RankCategory> categories;
    private final BookCardAdapter.OnBookClickListener bookClickListener;
    private final OnRefreshListener refreshListener;

    public BookCategoryAdapter(List<RankCategory> categories,
                                BookCardAdapter.OnBookClickListener bookClickListener,
                                OnRefreshListener refreshListener) {
        this.categories = categories;
        this.bookClickListener = bookClickListener;
        this.refreshListener = refreshListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_book_category, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RankCategory cat = categories.get(position);
        holder.tvCategoryName.setText(cat.name);

        // Build or update inner adapter
        if (holder.cardAdapter == null) {
            holder.cardAdapter = new BookCardAdapter(cat.displayBooks, bookClickListener);
            holder.rvBooks.setLayoutManager(
                    new LinearLayoutManager(holder.rvBooks.getContext(),
                            LinearLayoutManager.HORIZONTAL, false));
            holder.rvBooks.setAdapter(holder.cardAdapter);
        } else {
            holder.cardAdapter.updateBooks(cat.displayBooks);
            holder.rvBooks.scrollToPosition(0);
        }

        holder.btnRefresh.setOnClickListener(v -> {
            if (refreshListener != null) refreshListener.onRefresh(holder.getAdapterPosition());
        });
    }

    /** Replace the full dataset and refresh the list (called after background refresh). */
    public void setCategories(List<RankCategory> newCategories) {
        this.categories = newCategories;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return categories == null ? 0 : categories.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvCategoryName;
        final ImageButton btnRefresh;
        final RecyclerView rvBooks;
        BookCardAdapter cardAdapter;

        ViewHolder(View v) {
            super(v);
            tvCategoryName = v.findViewById(R.id.tv_category_name);
            btnRefresh = v.findViewById(R.id.btn_refresh);
            rvBooks = v.findViewById(R.id.rv_books);
        }
    }
}
