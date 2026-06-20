package com.thl.book;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.thl.book.network.BookStoreApi;
import com.thl.book.network.dto.RankBook;
import com.thl.book.network.dto.RankCategory;
import com.thl.reader.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BookStoreFragment extends Fragment {

    private RecyclerView rvBooks;
    private SwipeRefreshLayout swipeRefresh;
    private View progressLoading;
    private View layoutError;
    private LinearLayout chipGroup;
    private LinearLayout expandCategories;
    private TextView moreChip;

    // Banner
    private View bannerContainer;
    private ViewPager2 vpBanner;
    private LinearLayout bannerDots;
    private View rowFeaturedHeader;
    private TextView tvRefreshBooks;
    private BannerAdapter bannerAdapter;
    private final List<RankBook> bannerBooks = new ArrayList<>();
    private final Handler autoScrollHandler = new Handler(Looper.getMainLooper());
    private Runnable autoScrollRunnable;
    private static final int AUTO_SCROLL_INTERVAL_MS = 3000;

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
        chipGroup = view.findViewById(R.id.chip_group);
        expandCategories = view.findViewById(R.id.expand_categories);

        // Banner views
        bannerContainer = view.findViewById(R.id.banner_container);
        vpBanner = view.findViewById(R.id.vp_banner);
        bannerDots = view.findViewById(R.id.banner_dots);
        rowFeaturedHeader = view.findViewById(R.id.row_featured_header);
        tvRefreshBooks = view.findViewById(R.id.tv_refresh_books);

        view.findViewById(R.id.btn_retry).setOnClickListener(v -> fetchData());

        // Banner adapter setup
        bannerAdapter = new BannerAdapter(bannerBooks);
        vpBanner.setAdapter(bannerAdapter);
        vpBanner.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateDots(position);
            }
        });

        // Banner click: open book detail
        bannerAdapter.setOnItemClickListener(book -> {
            if (book != null) {
                BookDetailActivity.start(requireActivity(),
                        book.title, book.author, book.reads, book.intro, book.cover);
            }
        });

        // Auto-scroll runnable
        autoScrollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!bannerBooks.isEmpty()) {
                    int next = (vpBanner.getCurrentItem() + 1) % bannerBooks.size();
                    vpBanner.setCurrentItem(next, true);
                    autoScrollHandler.postDelayed(this, AUTO_SCROLL_INTERVAL_MS);
                }
            }
        };

        // Refresh books button
        if (tvRefreshBooks != null) {
            tvRefreshBooks.setOnClickListener(v -> {
                if (categories != null && !categories.isEmpty()) {
                    RankDataCache.randomizeCategory(categories.get(selectedIndex));
                    showBooksForIndex(selectedIndex);
                    updateBanner(categories);
                }
            });
        }

        rvBooks.setLayoutManager(new GridLayoutManager(requireContext(), 4));
        rvBooks.setHasFixedSize(false);
        gridAdapter = new BookGridAdapter(book ->
                BookDetailActivity.start(requireActivity(),
                        book.title, book.author, book.reads, book.intro, book.cover));
        rvBooks.setAdapter(gridAdapter);

        // Left/right swipe to change category
        GestureDetector swipeDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    private static final int SWIPE_MIN_DISTANCE = 80;
                    private static final int SWIPE_MIN_VELOCITY = 200;

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                          float velocityX, float velocityY) {
                        if (e1 == null || e2 == null || categories == null || categories.isEmpty())
                            return false;
                        float diffX = e2.getX() - e1.getX();
                        float diffY = e2.getY() - e1.getY();
                        if (Math.abs(diffX) > Math.abs(diffY)
                                && Math.abs(diffX) > SWIPE_MIN_DISTANCE
                                && Math.abs(velocityX) > SWIPE_MIN_VELOCITY) {
                            int next = diffX < 0
                                    ? (selectedIndex + 1) % categories.size()
                                    : (selectedIndex - 1 + categories.size()) % categories.size();
                            selectCategory(next);
                            return true;
                        }
                        return false;
                    }
                });
        rvBooks.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                swipeDetector.onTouchEvent(e);
                return false;
            }
        });

        swipeRefresh.setColorSchemeResources(R.color.colorPrimary);
        swipeRefresh.setOnRefreshListener(() -> {
            if (categories != null) {
                for (RankCategory cat : categories) {
                    RankDataCache.randomizeCategory(cat);
                }
                showBooksForIndex(selectedIndex);
                updateBanner(categories);
                swipeRefresh.setRefreshing(false);
            } else {
                fetchData();
            }
        });

        // Store search bar
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

        // Sync eink background
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        view.setBackgroundColor(eink ? 0xFFFFFFFF : getResources().getColor(R.color.bg_activity));

        if (RankDataCache.hasData()) {
            showCategories(RankDataCache.getCategories());
            refreshInBackground();
        } else {
            fetchData();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!bannerBooks.isEmpty()) {
            autoScrollHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_INTERVAL_MS);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
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
                    updateBanner(categories);
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
        chipGroup.setVisibility(View.VISIBLE);

        if (bannerContainer != null) bannerContainer.setVisibility(View.VISIBLE);
        if (rowFeaturedHeader != null) rowFeaturedHeader.setVisibility(View.VISIBLE);

        buildChips();
        showBooksForIndex(0);
        updateBanner(cats);

        // Start auto-scroll
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
        autoScrollHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_INTERVAL_MS);
    }

    private void updateBanner(List<RankCategory> cats) {
        if (vpBanner == null || bannerDots == null) return;
        List<RankBook> pool = new ArrayList<>();
        for (RankCategory cat : cats) {
            if (cat.displayBooks != null) pool.addAll(cat.displayBooks);
        }
        Collections.shuffle(pool);
        bannerBooks.clear();
        for (int i = 0; i < Math.min(3, pool.size()); i++) bannerBooks.add(pool.get(i));
        bannerAdapter.notifyDataSetChanged();
        updateDots(0);
    }

    private void updateDots(int selectedPosition) {
        if (bannerDots == null) return;
        bannerDots.removeAllViews();
        int size = bannerBooks.size();
        int dotSize = dp(requireContext(), 8);
        int dotMargin = dp(requireContext(), 3);
        for (int i = 0; i < size; i++) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotSize, dotSize);
            lp.setMargins(dotMargin, 0, dotMargin, 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_status_badge);
            // Set color
            int color = (i == selectedPosition) ? 0xFFFFFFFF : 0x80FFFFFF;
            dot.setBackgroundColor(color);
            // Use rounded corners via background shape
            android.graphics.drawable.GradientDrawable dotDrawable = new android.graphics.drawable.GradientDrawable();
            dotDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotDrawable.setColor(color);
            dot.setBackground(dotDrawable);
            bannerDots.addView(dot);
        }
    }

    /** 动态生成分类 Chip，显示前4个（等宽）+ 更多分类按钮，并构建展开面板 */
    private void buildChips() {
        if (getContext() == null || categories == null) return;
        chipGroup.removeAllViews();
        expandCategories.removeAllViews();
        expandCategories.setVisibility(View.GONE);
        moreChip = null;

        Context ctx = requireContext();
        int dp8 = dp(ctx, 8);
        int dp4 = dp(ctx, 4);

        int visibleCount = Math.min(4, categories.size());
        for (int i = 0; i < visibleCount; i++) {
            final int index = i;
            TextView chip = new TextView(ctx);
            chip.setText(categories.get(i).name);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp4, dp8, dp4, dp8);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp4, 0, dp4, 0);
            chip.setLayoutParams(lp);

            applyChipStyle(chip, i == selectedIndex);
            chip.setOnClickListener(v -> selectCategory(index));
            chipGroup.addView(chip);
        }

        if (categories.size() > 4) {
            moreChip = new TextView(ctx);
            updateMoreChipText();
            moreChip.setTextSize(13);
            moreChip.setSingleLine(true);
            moreChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            moreChip.setGravity(android.view.Gravity.CENTER);
            moreChip.setPadding(dp4, dp8, dp4, dp8);
            moreChip.setBackgroundResource(R.drawable.bg_more_categories);
            moreChip.setTextColor(selectedIndex >= 4
                    ? getResources().getColor(R.color.colorPrimary) : 0xFF6B5A53);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp4, 0, dp4, 0);
            moreChip.setLayoutParams(lp);

            moreChip.setOnClickListener(v -> toggleExpandCategories());
            chipGroup.addView(moreChip);

            buildExpandedGrid(ctx, dp8, dp4);
        }
    }

    private void buildExpandedGrid(Context ctx, int dp8, int dp4) {
        int padH = dp(ctx, 12);
        int padV = dp(ctx, 8);
        expandCategories.setPadding(padH, padV, padH, padV);

        LinearLayout currentRow = null;
        int countInRow = 0;

        for (int i = 4; i < categories.size(); i++) {
            final int index = i;

            if (countInRow == 0) {
                currentRow = new LinearLayout(ctx);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.setMargins(0, 0, 0, dp4 * 2);
                currentRow.setLayoutParams(rowLp);
                expandCategories.addView(currentRow);
            }

            TextView chip = new TextView(ctx);
            chip.setText(categories.get(i).name);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp4, dp8, dp4, dp8);

            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            chipLp.setMargins(dp4, 0, dp4, 0);
            chip.setLayoutParams(chipLp);

            applyChipStyle(chip, index == selectedIndex);
            chip.setOnClickListener(v -> {
                selectCategory(index);
                collapsePanel();
            });
            currentRow.addView(chip);
            countInRow++;

            if (countInRow == 5) countInRow = 0;
        }

        // 补齐最后一行空位
        if (countInRow > 0 && currentRow != null) {
            for (int i = countInRow; i < 5; i++) {
                View spacer = new View(ctx);
                LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, 0, 1f);
                spLp.setMargins(dp4, 0, dp4, 0);
                spacer.setLayoutParams(spLp);
                currentRow.addView(spacer);
            }
        }
    }

    private void updateMoreChipText() {
        if (moreChip == null) return;
        boolean expanded = expandCategories != null
                && expandCategories.getVisibility() == View.VISIBLE;
        moreChip.setText(expanded ? "收起 ▲" : "更多 ▼");
    }

    private void toggleExpandCategories() {
        if (expandCategories.getVisibility() == View.VISIBLE) {
            collapsePanel();
        } else {
            expandPanel();
        }
    }

    private void expandPanel() {
        expandCategories.setVisibility(View.VISIBLE);
        expandCategories.measure(
                View.MeasureSpec.makeMeasureSpec(chipGroup.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int targetHeight = expandCategories.getMeasuredHeight();
        ViewGroup.LayoutParams lp = expandCategories.getLayoutParams();
        lp.height = 0;
        expandCategories.requestLayout();

        ValueAnimator anim = ValueAnimator.ofInt(0, targetHeight);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(va -> {
            lp.height = (int) va.getAnimatedValue();
            expandCategories.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                expandCategories.requestLayout();
            }
        });
        anim.start();
        updateMoreChipText();
    }

    private void collapsePanel() {
        int startHeight = expandCategories.getHeight();
        ViewGroup.LayoutParams lp = expandCategories.getLayoutParams();

        ValueAnimator anim = ValueAnimator.ofInt(startHeight, 0);
        anim.setDuration(180);
        anim.setInterpolator(new AccelerateInterpolator());
        anim.addUpdateListener(va -> {
            lp.height = (int) va.getAnimatedValue();
            expandCategories.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expandCategories.setVisibility(View.GONE);
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                updateMoreChipText();
            }
        });
        anim.start();
    }

    private void applyChipStyle(TextView chip, boolean selected) {
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        if (selected) {
            chip.setBackgroundResource(eink ? R.drawable.bg_chip_selected_eink : R.drawable.bg_chip_selected);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTypeface(null, Typeface.BOLD);
        } else {
            chip.setBackgroundResource(R.drawable.bg_chip);
            chip.setTextColor(eink ? 0xFF000000 : getResources().getColor(R.color.text_primary));
            chip.setTypeface(null, Typeface.NORMAL);
        }
    }

    public void onEinkModeChanged() {
        if (getView() == null) return;
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        getView().setBackgroundColor(eink ? 0xFFFFFFFF
                : getResources().getColor(R.color.bg_activity));
        if (categories != null) buildChips();
    }

    private void selectCategory(int index) {
        if (categories == null || categories.isEmpty() || index == selectedIndex) return;
        selectedIndex = index;
        buildChips();
        showBooksForIndex(index);
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
        chipGroup.setVisibility(View.GONE);
        expandCategories.setVisibility(View.GONE);
        if (bannerContainer != null) bannerContainer.setVisibility(View.GONE);
        if (rowFeaturedHeader != null) rowFeaturedHeader.setVisibility(View.GONE);
    }

    private void showError() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        swipeRefresh.setVisibility(View.GONE);
        chipGroup.setVisibility(View.GONE);
        expandCategories.setVisibility(View.GONE);
        if (bannerContainer != null) bannerContainer.setVisibility(View.GONE);
        if (rowFeaturedHeader != null) rowFeaturedHeader.setVisibility(View.GONE);
    }

    private static int dp(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    // ── Banner Adapter ────────────────────────────────────────────────────────

    static class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.VH> {

        interface OnItemClickListener { void onClick(RankBook book); }

        private final List<RankBook> books;
        private OnItemClickListener listener;

        BannerAdapter(List<RankBook> books) {
            this.books = books;
        }

        void setOnItemClickListener(OnItemClickListener l) {
            this.listener = l;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Build banner item layout programmatically
            FrameLayout frame = new FrameLayout(parent.getContext());
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // Full cover image
            ImageView ivCover = new ImageView(parent.getContext());
            ivCover.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(ivCover);

            // Gradient overlay
            View gradient = new View(parent.getContext());
            gradient.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            gradient.setBackgroundResource(R.drawable.bg_banner_gradient);
            frame.addView(gradient);

            // Text overlay
            LinearLayout textArea = new LinearLayout(parent.getContext());
            FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            textLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            textArea.setLayoutParams(textLp);
            textArea.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(parent.getContext(), 14);
            textArea.setPadding(pad, pad, pad, pad);

            // Category tag
            TextView tvCategory = new TextView(parent.getContext());
            tvCategory.setTextSize(9);
            tvCategory.setTextColor(0xFFFFFFFF);
            tvCategory.setBackgroundColor(0x66000000);
            int tagPadH = dp(parent.getContext(), 6);
            int tagPadV = dp(parent.getContext(), 2);
            tvCategory.setPadding(tagPadH, tagPadV, tagPadH, tagPadV);
            textArea.addView(tvCategory);

            // Title
            TextView tvTitle = new TextView(parent.getContext());
            tvTitle.setTextSize(17);
            tvTitle.setTypeface(null, Typeface.BOLD);
            tvTitle.setTextColor(0xFFFFFFFF);
            tvTitle.setMaxLines(3);
            tvTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            titleLp.topMargin = dp(parent.getContext(), 8);
            tvTitle.setLayoutParams(titleLp);
            textArea.addView(tvTitle);

            // Author
            TextView tvAuthor = new TextView(parent.getContext());
            tvAuthor.setTextSize(12);
            tvAuthor.setTextColor(0xCCFFFFFF);
            tvAuthor.setMaxLines(1);
            tvAuthor.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams authorLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            authorLp.topMargin = dp(parent.getContext(), 4);
            tvAuthor.setLayoutParams(authorLp);
            textArea.addView(tvAuthor);

            // Read button
            TextView btnRead = new TextView(parent.getContext());
            btnRead.setTextSize(12);
            btnRead.setTextColor(0xFFFFFFFF);
            btnRead.setBackgroundResource(R.drawable.bg_read_btn_outline);
            btnRead.setText("立即阅读 →");
            int btnPadH = dp(parent.getContext(), 12);
            int btnPadV = dp(parent.getContext(), 5);
            btnRead.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnLp.topMargin = dp(parent.getContext(), 10);
            btnRead.setLayoutParams(btnLp);
            textArea.addView(btnRead);

            frame.addView(textArea);

            return new VH(frame, ivCover, tvCategory, tvTitle, tvAuthor);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RankBook book = books.get(position);
            holder.tvTitle.setText(book.title);
            holder.tvAuthor.setText(book.author != null ? book.author : "");
            holder.tvCategory.setVisibility(View.GONE); // Category tag hidden by default

            if (book.cover != null && !book.cover.isEmpty()) {
                Glide.with(holder.ivCover.getContext())
                        .load(book.cover)
                        .placeholder(R.mipmap.cover_default_new)
                        .error(R.mipmap.cover_default_new)
                        .into(holder.ivCover);
            } else {
                holder.ivCover.setImageResource(R.mipmap.cover_default_new);
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(book);
            });
        }

        @Override
        public int getItemCount() { return books.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView ivCover;
            final TextView tvCategory, tvTitle, tvAuthor;
            VH(View v, ImageView ivCover, TextView tvCategory, TextView tvTitle, TextView tvAuthor) {
                super(v);
                this.ivCover = ivCover;
                this.tvCategory = tvCategory;
                this.tvTitle = tvTitle;
                this.tvAuthor = tvAuthor;
            }
        }
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
            if (holder.tvAuthor != null) {
                holder.tvAuthor.setText(book.author != null ? book.author : "");
            }
            if (holder.tvReads != null) {
                holder.tvReads.setText(formatReads(book.reads));
            }
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

        private String formatReads(String reads) {
            if (reads == null || reads.isEmpty()) return "";
            try {
                long num = Long.parseLong(reads.replaceAll("[^0-9]", ""));
                if (num >= 10000) {
                    double wan = num / 10000.0;
                    return String.format("%.1f万好评", wan);
                }
                return reads;
            } catch (NumberFormatException e) {
                return reads;
            }
        }

        @Override
        public int getItemCount() { return books == null ? 0 : books.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final ImageView ivCover;
            final TextView tvTitle, tvAuthor, tvReads;
            VH(View v) {
                super(v);
                ivCover = v.findViewById(R.id.iv_cover);
                tvTitle = v.findViewById(R.id.tv_title);
                tvAuthor = v.findViewById(R.id.tv_author);
                tvReads = v.findViewById(R.id.tv_reads);
            }
        }
    }
}
