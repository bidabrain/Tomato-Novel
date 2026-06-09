package com.thl.book;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.thl.book.network.BookStoreApi;
import com.thl.book.network.dto.RankBook;
import com.thl.book.network.dto.RankCategory;
import com.thl.reader.Config;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookStoreFragment extends Fragment {

    private RecyclerView rvBooks;
    private SwipeRefreshLayout swipeRefresh;
    private View progressLoading;
    private View layoutError;
    private HorizontalScrollView scrollChips;
    private LinearLayout chipGroup;

    private BookGridAdapter gridAdapter;
    private List<RankCategory> categories;
    private int selectedIndex = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_book_store, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rvBooks = view.findViewById(R.id.rv_books);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        progressLoading = view.findViewById(R.id.progress_loading);
        layoutError = view.findViewById(R.id.layout_error);
        scrollChips = view.findViewById(R.id.scroll_chips);
        chipGroup = view.findViewById(R.id.chip_group);

        view.findViewById(R.id.btn_retry).setOnClickListener(v -> fetchData());

        rvBooks.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        rvBooks.setHasFixedSize(false);
        gridAdapter = new BookGridAdapter(book ->
                BookDetailActivity.start(requireActivity(),
                        book.title, book.author, book.reads, book.intro, book.cover));
        rvBooks.setAdapter(gridAdapter);

        swipeRefresh.setColorSchemeResources(R.color.colorPrimary);
        swipeRefresh.setOnRefreshListener(() -> {
            if (categories != null) {
                for (com.thl.book.network.dto.RankCategory cat : categories) {
                    RankDataCache.randomizeCategory(cat);
                }
                showBooksForIndex(selectedIndex);
                swipeRefresh.setRefreshing(false);
            } else {
                fetchData();
            }
        });

        // 书城搜索栏
        EditText etStoreSearch = view.findViewById(R.id.et_store_search);
        etStoreSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = etStoreSearch.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    Intent intent = new Intent(requireContext(), SearchResultActivity.class);
                    intent.putExtra(SearchResultActivity.EXTRA_QUERY, query);
                    startActivity(intent);
                }
                return true;
            }
            return false;
        });

        // 初始化时同步 eink 背景
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        view.setBackgroundColor(eink ? 0xFFFFFFFF : getResources().getColor(R.color.bg_activity));

        if (RankDataCache.hasData()) {
            showCategories(RankDataCache.getCategories());
            refreshInBackground();
        } else {
            fetchData();
        }
    }

    private void fetchData() {
        showLoading();
        final String url = ServerConfig.getStoreUrl(requireContext());
        executor.execute(() -> {
            List<RankCategory> result = BookStoreApi.fetchRankData(url);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (result != null && !result.isEmpty()) {
                    RankDataCache.setCategories(result);
                    showCategories(RankDataCache.getCategories());
                } else {
                    showError();
                }
            });
        });
    }

    private void refreshInBackground() {
        final String url = ServerConfig.getStoreUrl(requireContext());
        executor.execute(() -> {
            List<RankCategory> result = BookStoreApi.fetchRankData(url);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) return;
                if (result != null && !result.isEmpty()) {
                    RankDataCache.setCategories(result);
                    categories = RankDataCache.getCategories();
                    buildChips();
                    showBooksForIndex(selectedIndex);
                }
            });
        });
    }

    private void showCategories(List<RankCategory> cats) {
        if (getView() == null) return;
        this.categories = cats;
        this.selectedIndex = 0;

        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.VISIBLE);
        scrollChips.setVisibility(View.VISIBLE);

        buildChips();
        showBooksForIndex(0);
    }

    /** 动态生成分类 Chip，选中高亮 */
    private void buildChips() {
        if (getContext() == null || categories == null) return;
        chipGroup.removeAllViews();
        Context ctx = requireContext();
        int dp8 = dp(ctx, 8);
        int dp20 = dp(ctx, 20);
        int dp4 = dp(ctx, 4);

        for (int i = 0; i < categories.size(); i++) {
            final int index = i;
            TextView chip = new TextView(ctx);
            chip.setText(categories.get(i).name);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setPadding(dp20, dp8, dp20, dp8);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp4, 0, dp4, 0);
            chip.setLayoutParams(lp);

            applyChipStyle(chip, i == selectedIndex);

            chip.setOnClickListener(v -> {
                if (selectedIndex == index) return;
                selectedIndex = index;
                buildChips();       // 刷新所有 chip 样式
                showBooksForIndex(index);
                // 滚动让选中 chip 可见
                scrollChips.post(() -> scrollChips.smoothScrollTo(v.getLeft(), 0));
            });
            chipGroup.addView(chip);
        }
    }

    private void applyChipStyle(TextView chip, boolean selected) {
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        if (selected) {
            chip.setBackgroundResource(eink ? R.drawable.bg_chip_selected_eink : R.drawable.bg_chip_selected);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTypeface(null, Typeface.BOLD);
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(eink ? 0xFF000000 : 0xFF221C19);
            chip.setTypeface(null, Typeface.NORMAL);
        }
    }

    /** 电纸书模式切换时由 LocalBookshelfActivity 调用 */
    public void onEinkModeChanged() {
        if (getView() == null) return;
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        getView().setBackgroundColor(eink ? 0xFFFFFFFF
                : getResources().getColor(R.color.bg_activity));
        if (categories != null) buildChips();
    }

    private void showBooksForIndex(int index) {
        if (categories == null || index >= categories.size()) return;
        gridAdapter.setBooks(categories.get(index).displayBooks);
        rvBooks.scrollToPosition(0);
    }

    private void showLoading() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.GONE);
        scrollChips.setVisibility(View.GONE);
    }

    private void showError() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        swipeRefresh.setVisibility(View.GONE);
        scrollChips.setVisibility(View.GONE);
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    // ── 网格 Adapter ─────────────────────────────────────────────────────────

    static class BookGridAdapter extends RecyclerView.Adapter<BookGridAdapter.VH> {

        interface OnBookClickListener { void onBookClick(RankBook book); }

        private List<RankBook> books;
        private final OnBookClickListener listener;

        BookGridAdapter(OnBookClickListener listener) {
            this.listener = listener;
        }

        void setBooks(List<RankBook> books) {
            this.books = books;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_book_grid, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RankBook book = books.get(position);
            holder.tvTitle.setText(book.title);
            holder.tvIntro.setText(book.intro);
            if (book.cover != null && !book.cover.isEmpty()) {
                Glide.with(holder.ivCover.getContext())
                        .load(book.cover)
                        .placeholder(R.mipmap.cover_default_new)
                        .error(R.mipmap.cover_default_new)
                        .into(holder.ivCover);
            } else {
                Glide.with(holder.ivCover.getContext()).clear(holder.ivCover);
                holder.ivCover.setImageResource(R.mipmap.cover_default_new);
            }
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onBookClick(book);
            });
        }

        @Override
        public int getItemCount() { return books == null ? 0 : books.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView ivCover;
            final TextView tvTitle, tvIntro;
            VH(View v) {
                super(v);
                ivCover = v.findViewById(R.id.iv_cover);
                tvTitle = v.findViewById(R.id.tv_title);
                tvIntro = v.findViewById(R.id.tv_intro);
            }
        }
    }
}
