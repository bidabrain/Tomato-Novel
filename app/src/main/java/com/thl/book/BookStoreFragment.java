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
import com.thl.book.base.FixedHeaderAdapter;
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
    private FlowLayout expandCategories;
    private TextView moreChip;
    /** 频道切换行、分类行、分类横向滚动容器 */
    private View rowChannel, rowChips;
    private android.widget.HorizontalScrollView hsvChips;
    private TextView tvChannelFemale, tvChannelMale;

    private static final String CHANNEL_FEMALE = "female";
    private static final String CHANNEL_MALE = "male";
    private static final String PREF_STORE_CHANNEL = "store_channel";
    /** 当前频道；数据里没有频道信息时不区分频道 */
    private String currentChannel = CHANNEL_FEMALE;

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

    /** rv_books 布局里原有的 paddingTop / paddingBottom */
    private int rvBooksPadTop;
    private int rvBooksPadBottom;
    /** 头部（搜索 ~ 精选推荐标题），作为列表第一项随书籍滚动 */
    private View storeHeader;

    /** 内容铺满到磨砂玻璃底部导航后面，列表底部留出导航栏高度 */
    public void setBottomInset(int inset) {
        if (rvBooks == null) return;
        rvBooks.setPadding(rvBooks.getPaddingLeft(), rvBooks.getPaddingTop(),
                rvBooks.getPaddingRight(), rvBooksPadBottom + inset);
    }

    /** 内容铺满到磨砂玻璃顶栏后面，列表顶部留出顶栏高度；下拉刷新指示器下移到顶栏下方 */
    public void setTopInset(int inset) {
        if (rvBooks == null) return;
        // 改留白会保持当前第一项的位置不动；原本在顶部时需滚回顶部，否则头部会被压在顶栏下
        boolean atTop = !rvBooks.canScrollVertically(-1);
        rvBooks.setPadding(rvBooks.getPaddingLeft(), rvBooksPadTop + inset,
                rvBooks.getPaddingRight(), rvBooks.getPaddingBottom());
        if (atTop) rvBooks.scrollToPosition(0);
        int d = Math.round(getResources().getDisplayMetrics().density);
        swipeRefresh.setProgressViewOffset(false, inset - 40 * d, inset + 24 * d);
    }

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
        rvBooksPadTop = rvBooks.getPaddingTop();
        rvBooksPadBottom = rvBooks.getPaddingBottom();
        storeHeader = view.findViewById(R.id.store_header);
        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        if (getActivity() instanceof LocalBookshelfActivity) {
            LocalBookshelfActivity act = (LocalBookshelfActivity) getActivity();
            setBottomInset(act.getBottomInset());
            setTopInset(act.getTopInset());
        }
        progressLoading = view.findViewById(R.id.progress_loading);
        layoutError = view.findViewById(R.id.layout_error);
        chipGroup = view.findViewById(R.id.chip_group);
        expandCategories = view.findViewById(R.id.expand_categories);
        rowChannel = view.findViewById(R.id.row_channel);
        rowChips = view.findViewById(R.id.row_chips);
        hsvChips = view.findViewById(R.id.hsv_chips);
        tvChannelFemale = view.findViewById(R.id.tv_channel_female);
        tvChannelMale = view.findViewById(R.id.tv_channel_male);
        moreChip = view.findViewById(R.id.tv_more_chip);
        moreChip.setOnClickListener(v -> toggleExpandCategories());
        tvChannelFemale.setOnClickListener(v -> switchChannel(CHANNEL_FEMALE));
        tvChannelMale.setOnClickListener(v -> switchChannel(CHANNEL_MALE));
        currentChannel = SharedPreferencesUtils.getString(requireContext(),
                PREF_STORE_CHANNEL, CHANNEL_FEMALE);
        int d = Math.round(getResources().getDisplayMetrics().density);
        expandCategories.setSpacing(8 * d, 8 * d);

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

        rvBooks.setHasFixedSize(false);
        gridAdapter = new BookGridAdapter(book ->
                BookDetailActivity.start(requireActivity(),
                        book.title, book.author, book.reads, book.intro, book.cover));
        // 头部作为列表第一项，随书籍一起滚动（之后不能再用 view.findViewById 找头部里的控件）
        FixedHeaderAdapter.attach(rvBooks, storeHeader, gridAdapter, 4);

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
                            // 只在当前频道的分类之间循环切换
                            List<Integer> ids = channelIndices();
                            if (ids.isEmpty()) return false;
                            int pos = Math.max(0, ids.indexOf(selectedIndex));
                            int next = diffX < 0
                                    ? (pos + 1) % ids.size()
                                    : (pos - 1 + ids.size()) % ids.size();
                            selectCategory(ids.get(next));
                            return true;
                        }
                        return false;
                    }
                });
        rvBooks.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            /** 本次手势是否从头部开始（头部有横向滑动的轮播图，不能触发切换分类） */
            private boolean startedOnHeader;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startedOnHeader = rv.findChildViewUnder(e.getX(), e.getY()) == storeHeader;
                }
                if (!startedOnHeader) swipeDetector.onTouchEvent(e);
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
        EditText etStoreSearch = storeHeader.findViewById(R.id.et_store_search);
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
        // 默认选中当前频道（上次选的频道）的第一个分类
        List<Integer> ids = channelIndices();
        this.selectedIndex = ids.isEmpty() ? 0 : ids.get(0);

        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.VISIBLE);
        rowChips.setVisibility(View.VISIBLE);

        if (bannerContainer != null) bannerContainer.setVisibility(View.VISIBLE);
        if (rowFeaturedHeader != null) rowFeaturedHeader.setVisibility(View.VISIBLE);

        buildChips();
        showBooksForIndex(selectedIndex);
        updateBanner(cats);

        // Start auto-scroll
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
        autoScrollHandler.postDelayed(autoScrollRunnable, AUTO_SCROLL_INTERVAL_MS);
    }

    private void updateBanner(List<RankCategory> cats) {
        if (vpBanner == null || bannerDots == null) return;
        List<RankBook> pool = new ArrayList<>();
        // 轮播只推荐当前频道的书
        boolean byChannel = hasChannels();
        for (RankCategory cat : cats) {
            if (byChannel && !currentChannel.equals(cat.channel)) continue;
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

    /** 数据里是否带频道信息（没有时不显示频道切换，所有分类放在一起） */
    private boolean hasChannels() {
        if (categories == null) return false;
        for (RankCategory c : categories) {
            if (c.channel != null && !c.channel.isEmpty()) return true;
        }
        return false;
    }

    /** 当前频道下的分类在 categories 中的下标 */
    private List<Integer> channelIndices() {
        List<Integer> ids = new ArrayList<>();
        if (categories == null) return ids;
        boolean byChannel = hasChannels();
        for (int i = 0; i < categories.size(); i++) {
            if (!byChannel || currentChannel.equals(categories.get(i).channel)) ids.add(i);
        }
        // 当前频道没有任何分类（例如数据变化）时退回显示全部
        if (ids.isEmpty()) {
            for (int i = 0; i < categories.size(); i++) ids.add(i);
        }
        return ids;
    }

    private void switchChannel(String channel) {
        if (categories == null || channel.equals(currentChannel)) return;
        currentChannel = channel;
        SharedPreferencesUtils.saveString(requireContext(), PREF_STORE_CHANNEL, channel);
        List<Integer> ids = channelIndices();
        selectedIndex = ids.isEmpty() ? 0 : ids.get(0);
        expandCategories.setVisibility(View.GONE);
        buildChips();
        showBooksForIndex(selectedIndex);
        updateBanner(categories);
        hsvChips.scrollTo(0, 0);
    }

    private TextView newChip(Context ctx, int index) {
        int padH = dp(ctx, 12);
        int padV = dp(ctx, 6);
        TextView chip = new TextView(ctx);
        chip.setText(categories.get(index).name);
        chip.setTextSize(13);
        chip.setSingleLine(true);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(padH, padV, padH, padV);
        applyChipStyle(chip, index == selectedIndex);
        return chip;
    }

    private void buildChips() {
        if (getContext() == null || categories == null) return;
        Context ctx = requireContext();
        chipGroup.removeAllViews();
        expandCategories.removeAllViews();

        // 频道切换
        boolean byChannel = hasChannels();
        rowChannel.setVisibility(byChannel ? View.VISIBLE : View.GONE);
        if (byChannel) {
            applyChannelStyle(tvChannelFemale, CHANNEL_FEMALE.equals(currentChannel));
            applyChannelStyle(tvChannelMale, CHANNEL_MALE.equals(currentChannel));
        }

        // 分类行：横向滑动，按字数占宽
        List<Integer> ids = channelIndices();
        TextView selectedChip = null;
        for (int index : ids) {
            TextView chip = newChip(ctx, index);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(ctx, 8), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> selectCategory(index));
            chipGroup.addView(chip);
            if (index == selectedIndex) selectedChip = chip;
        }
        // 选中的分类滚到可见位置（两侧多留出渐隐区的宽度）
        if (selectedChip != null) {
            final TextView target = selectedChip;
            hsvChips.post(() -> {
                int left = target.getLeft() - dp(ctx, 32);
                int right = target.getRight() + dp(ctx, 32) - hsvChips.getWidth();
                int x = hsvChips.getScrollX();
                if (left < x) hsvChips.smoothScrollTo(left, 0);
                else if (right > x) hsvChips.smoothScrollTo(right, 0);
            });
        }

        // 展开面板：当前频道全部分类，流式排列自动换行
        for (int index : ids) {
            TextView chip = newChip(ctx, index);
            chip.setOnClickListener(v -> {
                selectCategory(index);
                collapsePanel();
            });
            expandCategories.addView(chip);
        }
        updateMoreChipText();
    }

    /** 频道切换文字样式：选中加粗主色（墨水屏为黑色），未选中次要色 */
    private void applyChannelStyle(TextView tv, boolean selected) {
        boolean eink = Config.createConfig(requireContext()).isEinkMode();
        if (selected) {
            tv.setTextColor(eink ? 0xFF000000 : getResources().getColor(R.color.colorPrimary));
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            tv.setTextColor(eink ? 0xFF666666 : getResources().getColor(R.color.text_secondary));
            tv.setTypeface(null, Typeface.NORMAL);
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
                View.MeasureSpec.makeMeasureSpec(((View) expandCategories.getParent()).getWidth(), View.MeasureSpec.EXACTLY),
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
        // 第 0 项是头部：头部还在屏幕上时不动；否则对齐到第一本书（位于顶栏下方）
        GridLayoutManager glm = (GridLayoutManager) rvBooks.getLayoutManager();
        if (glm != null && glm.findFirstVisibleItemPosition() > 0) {
            glm.scrollToPositionWithOffset(1, 0);
        }
    }

    private void showLoading() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.VISIBLE);
        layoutError.setVisibility(View.GONE);
        rowChips.setVisibility(View.GONE);
        rowChannel.setVisibility(View.GONE);
        expandCategories.setVisibility(View.GONE);
        if (bannerContainer != null) bannerContainer.setVisibility(View.GONE);
        if (rowFeaturedHeader != null) rowFeaturedHeader.setVisibility(View.GONE);
    }

    private void showError() {
        if (getView() == null) return;
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        rowChips.setVisibility(View.GONE);
        rowChannel.setVisibility(View.GONE);
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
