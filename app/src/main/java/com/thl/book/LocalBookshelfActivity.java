package com.thl.book;

import androidx.appcompat.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import com.lcodecore.tkrefreshlayout.RefreshListenerAdapter;
import com.lcodecore.tkrefreshlayout.TwinklingRefreshLayout;
import com.lcodecore.tkrefreshlayout.header.progresslayout.ProgressLayout;
import com.thl.book.base.BaseActivity;
import com.thl.book.base.SingleAdapter;
import com.thl.book.base.SuperViewHolder;
import com.thl.reader.Config;
import com.thl.reader.ReadActivity;
import com.thl.reader.db.BookList;
import com.thl.reader.filechooser.FileChooserActivity;
import com.thl.reader.util.FileUtils;
import com.thl.reader.util.ReadingStatsManager;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.thl.reader.db.DB;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LocalBookshelfActivity extends BaseActivity implements View.OnClickListener {

    private TwinklingRefreshLayout refreshLayout;
    private RecyclerView mRecyclerView;
    private EditText etSearch;

    private SingleAdapter<BookList> adapter;
    private List<BookList> bookLists;
    private View ib_more;

    private View containerBookshelf;
    private FrameLayout containerBookStore;
    private TextView tvTitle;
    private BookStoreFragment bookStoreFragment;
    private SwitchCompat swEink;
    private SwitchCompat swWebDav;

    private TextView tvUpdateStatus;

    // 新视图字段
    private TextView tvGreeting;
    private View ibHistory;
    private androidx.cardview.widget.CardView cardContinueBanner;
    private TextView tvBannerTitle, tvBannerProgress;
    private ImageView ivBannerCover;
    private View vBannerProgressFill;
    private View rowStats;
    private TextView tvStatWeekly, tvStatDays, tvStatBooks;
    private TextView tvManage, tvSort;
    private TextView tvSyncTime;
    private View ibSearch;
    private View rowShelfHeader;

    // 顶部横栏折叠（继续阅读 + 阅读统计）
    private boolean headerCollapsed = false;
    private boolean headerAnimating = false;
    private int accumulatedDy = 0;
    private android.animation.ValueAnimator headerAnimator;
    private long headerCooldownUntil = 0;
    private boolean collapsedThisGesture = false;
    private static final int COLLAPSE_THRESHOLD_DP = 10;
    private static final long HEADER_COOLDOWN_MS = 300;

    // 排序
    private static final int SORT_RECENT_READ  = 0;
    private static final int SORT_RECENT_ADDED = 1;
    private int currentSort = SORT_RECENT_READ;

    // 搜索历史
    private static final String PREF_SEARCH_HISTORY = "search_history";
    private static final int MAX_HISTORY = 10;

    // 旧 ID 兼容（view 对象保持以防 applyEinkMode 等方法引用）
    private View rowTopCards;
    private View cardContinueReading;
    private View cardWeeklyTime;
    private TextView tvWeeklyTime;
    private TextView tvWeeklyNumber;
    private TextView tvWeeklyUnit;
    private TextView tvSyncLabel;
    private TextView tvContinueTitle;
    private TextView tvContinueProgress;
    private ImageView ivContinueCover;

    private CustomPopWindow popWindow;
    private boolean isDel = false;
    private boolean coverRefreshDone = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> importLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) importBookshelf(uri); });

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            executor.execute(() -> {
                List<BookList> books = getBooks();
                boolean hasPending = false;
                for (BookList b : books) {
                    if (b.getBookpath() == null || b.getBookpath().isEmpty()) {
                        hasPending = true;
                        break;
                    }
                }
                final boolean keepPolling = hasPending;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    bookLists.clear();
                    bookLists.addAll(books);
                    adapter.notifyDataSetChanged();
                    updateContinueCard();
                    if (keepPolling) pollHandler.postDelayed(this, 3000);
                });
            });
        }
    };

    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int newCount = intent.getIntExtra("total_new", 0);
            boolean isFinished = intent.getBooleanExtra(UpdateChecker.EXTRA_IS_FINISHED, true);
            int current = intent.getIntExtra(UpdateChecker.EXTRA_CURRENT, 0);
            int total = intent.getIntExtra(UpdateChecker.EXTRA_TOTAL, 0);
            String bookName = intent.getStringExtra(UpdateChecker.EXTRA_BOOK_NAME);
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler.post(pollRunnable);
            if (isFinished) {
                showUpdateBanner(false, null, 0, 0);
                if (newCount > 0) {
                    Toast.makeText(context, "已更新 " + newCount + " 个新章节", Toast.LENGTH_SHORT).show();
                }
            } else {
                showUpdateBanner(true, bookName, current, total);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int initLayout() {
        return R.layout.activity_book;
    }

    @Override
    protected void initView() {
        tvTitle = (TextView) findViewById(R.id.tv_title);
        tvTitle.setText("书架");

        containerBookshelf = findViewById(R.id.container_bookshelf);
        containerBookStore = (FrameLayout) findViewById(R.id.container_book_store);

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_bookshelf) {
                showBookshelf();
                return true;
            } else if (id == R.id.nav_book_store) {
                showBookStore();
                return true;
            }
            return false;
        });

        // 新视图
        tvGreeting = findViewById(R.id.tv_greeting);
        ibHistory = findViewById(R.id.ib_history);
        cardContinueBanner = findViewById(R.id.card_continue_banner);
        tvBannerTitle = findViewById(R.id.tv_banner_title);
        tvBannerProgress = findViewById(R.id.tv_banner_progress);
        ivBannerCover = findViewById(R.id.iv_banner_cover);
        vBannerProgressFill = findViewById(R.id.v_banner_progress_fill);
        rowStats = findViewById(R.id.row_stats);
        tvStatWeekly = findViewById(R.id.tv_stat_weekly);
        tvStatDays = findViewById(R.id.tv_stat_days);
        tvStatBooks = findViewById(R.id.tv_stat_books);
        tvManage = findViewById(R.id.tv_manage);
        tvSort = findViewById(R.id.tv_sort);
        tvSyncTime = findViewById(R.id.tv_sync_time);
        ibSearch = findViewById(R.id.ib_search);
        rowShelfHeader = findViewById(R.id.row_shelf_header);

        // 旧 ID 兼容
        rowTopCards = findViewById(R.id.row_top_cards);
        cardContinueReading = findViewById(R.id.card_continue_reading);
        cardWeeklyTime = findViewById(R.id.card_weekly_time);
        tvWeeklyTime = findViewById(R.id.tv_weekly_time);
        tvWeeklyNumber = findViewById(R.id.tv_weekly_number);
        tvWeeklyUnit = findViewById(R.id.tv_weekly_unit);
        tvSyncLabel = findViewById(R.id.tv_sync_label);
        tvContinueTitle = findViewById(R.id.tv_continue_title);
        tvContinueProgress = findViewById(R.id.tv_continue_progress);

        etSearch = (EditText) findViewById(R.id.et_search);
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String query = etSearch.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    saveSearchHistory(query);
                    Intent intent = new Intent(this, SearchResultActivity.class);
                    intent.putExtra(SearchResultActivity.EXTRA_QUERY, query);
                    startActivity(intent);
                }
                return true;
            }
            return false;
        });

        if (ibHistory != null) {
            ibHistory.setOnClickListener(v -> showHistoryPopup());
        }

        refreshLayout = (TwinklingRefreshLayout) findViewById(R.id.refresh);
        mRecyclerView = (RecyclerView) findViewById(R.id.recyclerview);
        ProgressLayout headerView = new ProgressLayout(this);
        refreshLayout.setHeaderView(headerView);
        refreshLayout.setEnableLoadmore(false);
        refreshLayout.setAutoLoadMore(false);
        refreshLayout.setOverScrollRefreshShow(true);
        ib_more = findViewById(R.id.ib_more);
        ib_more.setVisibility(View.VISIBLE);
        ib_more.setOnClickListener(this);

        tvUpdateStatus = findViewById(R.id.tv_update_status);

        // 书架操作
        if (cardContinueBanner != null) {
            cardContinueBanner.setOnClickListener(v -> {
                if (bookLists != null) {
                    for (BookList b : bookLists) {
                        if (b.getLastReadAt() > 0 && b.getBookpath() != null && !b.getBookpath().isEmpty()) {
                            ReadActivity.openBook(b, LocalBookshelfActivity.this);
                            break;
                        }
                    }
                }
            });
        }

        if (tvManage != null) {
            tvManage.setOnClickListener(v -> {
                isDel = !isDel;
                adapter.notifyDataSetChanged();
            });
        }

        if (tvSort != null) {
            tvSort.setOnClickListener(v -> showSortPopup());
        }

        // 电纸书模式开关
        swEink = findViewById(R.id.sw_eink);
        swEink.setVisibility(View.VISIBLE);
        Config config = Config.createConfig(this);
        swEink.setChecked(config.isEinkMode());
        applyEinkMode(config.isEinkMode());
        applySwitchColors();
        swEink.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setEinkMode(isChecked);
            AppCompatDelegate.setDefaultNightMode(isChecked
                    ? AppCompatDelegate.MODE_NIGHT_NO
                    : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            applyEinkMode(isChecked);
            if (bookStoreFragment != null) {
                bookStoreFragment.onEinkModeChanged();
            }
        });

        // WebDAV 同步开关
        swWebDav = findViewById(R.id.sw_webdav);
        swWebDav.setVisibility(View.VISIBLE);
        swWebDav.setChecked(WebDavConfig.isEnabled(this));
        refreshWebDavSwitchEnabled();
        swWebDav.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferencesUtils.saveBoolean(this, WebDavConfig.KEY_WEBDAV_ENABLED, isChecked);
            refreshSyncLabel();
        });
    }

    private void applySwitchColors() {
        int[][] states = {{android.R.attr.state_checked}, {}};
        int[] trackColors = {getResources().getColor(R.color.colorPrimary), 0xFFDDDDDD};
        ColorStateList trackTint = new ColorStateList(states, trackColors);
        if (swEink != null && swEink.getTrackDrawable() != null) {
            DrawableCompat.setTintList(swEink.getTrackDrawable(), trackTint);
        }
        if (swWebDav != null && swWebDav.getTrackDrawable() != null) {
            DrawableCompat.setTintList(swWebDav.getTrackDrawable(), trackTint);
        }
    }

    private void refreshWebDavSwitchEnabled() {
        if (swWebDav == null) return;
        boolean hasUrl = !WebDavConfig.getUrl(this).isEmpty();
        swWebDav.setEnabled(hasUrl);
        swWebDav.setAlpha(hasUrl ? 1f : 0.4f);
        if (!hasUrl) swWebDav.setChecked(false);
        refreshSyncLabel();
    }

    private void refreshSyncLabel() {
        // Update header sync time label
        if (tvSyncTime != null) {
            if (!WebDavConfig.isEnabled(this)) {
                tvSyncTime.setVisibility(View.GONE);
            } else {
                long lastSync = WebDavConfig.getLastSyncAt(this);
                String text;
                if (lastSync == 0) {
                    text = "从未同步";
                } else {
                    long diffMs = System.currentTimeMillis() - lastSync;
                    long minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(diffMs);
                    long hours   = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(diffMs);
                    long days    = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMs);
                    String ago;
                    if (minutes < 1)     ago = "刚刚";
                    else if (hours < 1)  ago = minutes + "分前";
                    else if (days < 1)   ago = hours + "时前";
                    else                 ago = days + "天前";
                    text = "同步:" + ago;
                }
                tvSyncTime.setText(text);
                tvSyncTime.setVisibility(View.VISIBLE);
            }
        }
        // Also keep the legacy tvSyncLabel (now hidden, but keep logic)
        if (tvSyncLabel != null) {
            tvSyncLabel.setVisibility(View.GONE);
        }
    }

    private void applyEinkMode(boolean eink) {
        View root = findViewById(R.id.activity_album);
        if (root != null) {
            root.setBackgroundColor(eink ? 0xFFFFFFFF : getResources().getColor(R.color.bg_activity));
        }
        // Banner card background
        if (cardContinueBanner != null) {
            cardContinueBanner.setCardBackgroundColor(
                    eink ? 0xFFEEEEEE : getResources().getColor(R.color.color_container));
        }
        // Stats row background
        if (rowStats != null) {
            rowStats.setBackgroundResource(eink ? R.drawable.bg_continue_card_eink : R.drawable.bg_card_rounded);
        }
    }

    @Override
    protected void initData(Bundle savedInstanceState) {
        refreshLayout.setOnRefreshListener(new RefreshListenerAdapter() {
            @Override
            public void onPullingDown(TwinklingRefreshLayout refreshLayout, float fraction) {
                // 仅当列表确实处于顶部、且是用户真实下拉时才展开。
                // 关键：同一次手势里若刚发生过折叠，禁止本次手势再触发展开——
                // 因为折叠会把短列表顶到顶部、把这同一次上滑误判成“下拉”，导致回弹。
                if (headerCollapsed && !headerAnimating && !collapsedThisGesture
                        && fraction > 0.15f
                        && mRecyclerView != null
                        && !mRecyclerView.canScrollVertically(-1)) {
                    setHeaderCollapsed(false, true);
                }
            }

            @Override
            public void onRefresh(final TwinklingRefreshLayout refreshLayout) {
                showUpdateBanner(true, null, 0, 0);
                if (!UpdateChecker.isRunning()) {
                    UpdateChecker.checkOnLaunch(LocalBookshelfActivity.this);
                }
                executor.execute(() -> {
                    List<BookList> books = getBooks();
                    runOnUiThread(() -> {
                        if (isDestroyed() || isFinishing()) return;
                        bookLists.clear();
                        bookLists.addAll(books);
                        adapter.notifyDataSetChanged();
                        updateContinueCard();
                        refreshLayout.finishRefreshing();
                    });
                });
            }
        });

        mRecyclerView.setLayoutManager(new GridLayoutManager(this, 4));
        adapter = new SingleAdapter<BookList>(LocalBookshelfActivity.this, R.layout.item_book) {
            @Override
            protected void bindData(SuperViewHolder holder, BookList book) {
                LinearLayout lltDel = holder.getView(R.id.llt_del);
                if (isDel) {
                    lltDel.setVisibility(View.VISIBLE);
                    lltDel.setOnClickListener(v -> {
                        executor.execute(() -> {
                            DB.bookList().deleteById(book.getId());
                            WebDavConfig.markBookshelfModified(LocalBookshelfActivity.this);
                            if (book.getIsTomato() == 1) {
                                String path = book.getBookpath();
                                if (path != null && !path.isEmpty()) {
                                    new File(path).delete();
                                    // 同步删除服务器副本（tomato/server/书名.txt）
                                    File serverFile = new File(
                                            path.replace("/tomato/local/", "/tomato/server/"));
                                    serverFile.delete();
                                }
                            }
                            List<BookList> books = getBooks();
                            runOnUiThread(() -> {
                                if (isDestroyed() || isFinishing()) return;
                                bookLists.clear();
                                bookLists.addAll(books);
                                adapter.notifyDataSetChanged();
                                updateContinueCard();
                            });
                        });
                    });
                } else {
                    lltDel.setVisibility(View.GONE);
                }

                ImageView ivCover = holder.getView(R.id.iv_cover);
                TextView tvName = holder.getView(R.id.tv_name);
                TextView tvMsg = holder.getView(R.id.tv_msg);
                View tomatoBadge = holder.getView(R.id.v_tomato_badge);
                View localBadge = holder.getView(R.id.tv_local_badge);
                TextView tvUpdateBadge = holder.getView(R.id.tv_update_badge);
                TextView tvDownloading = holder.getView(R.id.tv_downloading);

                String coverUrl = book.getCoverUrl();
                if (coverUrl != null && !coverUrl.isEmpty()) {
                    Glide.with(LocalBookshelfActivity.this)
                            .load(coverUrl)
                            .placeholder(R.mipmap.cover_default_new)
                            .error(R.mipmap.cover_default_new)
                            .into(ivCover);
                } else {
                    ivCover.setImageResource(R.mipmap.cover_default_new);
                }

                tvName.setText(book.getBookname());

                String rawMsg = book.getMsg() != null ? book.getMsg() : "";
                java.util.regex.Matcher badgeMatcher =
                        java.util.regex.Pattern.compile("（新\\+\\d+）").matcher(rawMsg);
                String updateText = badgeMatcher.find() ? badgeMatcher.group() : "";

                String progressText = "";
                if (book.getChapterProgress() != null) {
                    progressText = book.getChapterProgress();
                } else if (book.getIsTomato() == 1 && updateText.isEmpty()) {
                    progressText = rawMsg.replaceAll("（新\\+\\d+）", "").trim();
                }
                tvMsg.setText(progressText);

                if (!updateText.isEmpty()) {
                    tvUpdateBadge.setText(updateText);
                    tvUpdateBadge.setVisibility(View.VISIBLE);
                } else {
                    tvUpdateBadge.setVisibility(View.GONE);
                }

                // 番茄原创 / 本地 badge
                if (book.getIsTomato() == 1) {
                    tomatoBadge.setVisibility(View.VISIBLE);
                    if (localBadge != null) localBadge.setVisibility(View.GONE);
                } else {
                    tomatoBadge.setVisibility(View.GONE);
                    if (localBadge != null) localBadge.setVisibility(View.VISIBLE);
                }

                boolean isDownloading = book.getBookpath() == null || book.getBookpath().isEmpty();
                holder.getRootView().setAlpha(1f);
                tvDownloading.setVisibility(isDownloading ? View.VISIBLE : View.GONE);
                if (isDownloading) {
                    holder.getRootView().setOnClickListener(v -> retryDownload(book));
                } else {
                    holder.getRootView().setOnClickListener(v ->
                            ReadActivity.openBook(book, LocalBookshelfActivity.this));
                }

                // 进度条填充
                View fillView = holder.getView(R.id.v_progress_fill);
                if (fillView != null) {
                    int progressPct = parseProgressPercent(book.getChapterProgress());
                    setProgressFillWidth(fillView, progressPct);
                }
            }
        };

        bookLists = new ArrayList<>();
        adapter.setData(bookLists);
        mRecyclerView.setAdapter(adapter);

        setupHeaderCollapse();

        UpdateChecker.checkOnLaunch(this);
        showUpdateBanner(true, null, 0, 0);
    }

    // ──────────── 顶部横栏折叠（上滑收起，下拉展开） ────────────

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // 每次新手势按下时复位“本次手势已折叠”标记，
        // 这样新的一次下拉手势才允许展开，而引起折叠的那次上滑手势不会回弹展开。
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            collapsedThisGesture = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private int dp2px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** 记录可折叠视图的展开高度/外边距：[measuredHeight, topMargin, originalLpHeight] */
    private final java.util.WeakHashMap<View, int[]> headerMetrics = new java.util.WeakHashMap<>();

    private void setupHeaderCollapse() {
        if (mRecyclerView == null) return;
        final int threshold = dp2px(COLLAPSE_THRESHOLD_DP);
        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy == 0 || headerAnimating) return;
                // 折叠/展开动画刚结束的冷却期内，忽略 overscroll 回弹等抖动滚动
                if (android.os.SystemClock.uptimeMillis() < headerCooldownUntil) {
                    accumulatedDy = 0;
                    return;
                }
                // 方向反转时重置累计量
                if ((dy > 0) != (accumulatedDy >= 0)) accumulatedDy = 0;
                accumulatedDy += dy;
                if (accumulatedDy > threshold && !headerCollapsed) {
                    collapsedThisGesture = true;
                    setHeaderCollapsed(true, true);
                } else if (accumulatedDy < -threshold && headerCollapsed) {
                    setHeaderCollapsed(false, true);
                }
            }
        });
    }

    /** 当前需要参与折叠、且内容上可见的视图 */
    private java.util.List<View> collapsibleViews() {
        java.util.List<View> views = new java.util.ArrayList<>();
        if (cardContinueBanner != null && cardContinueBanner.getVisibility() == View.VISIBLE) {
            views.add(cardContinueBanner);
        }
        if (rowStats != null && rowStats.getVisibility() == View.VISIBLE) {
            views.add(rowStats);
        }
        return views;
    }

    private void captureMetrics(View v) {
        if (headerMetrics.containsKey(v)) return;
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
        int measured = v.getHeight() > 0 ? v.getHeight() : lp.height;
        headerMetrics.put(v, new int[]{measured, lp.topMargin, lp.height});
    }

    private void applyFraction(View v, float f) {
        int[] m = headerMetrics.get(v);
        if (m == null) return;
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.height = f >= 1f ? m[2] : Math.round(m[0] * f);
        lp.topMargin = Math.round(m[1] * f);
        v.setLayoutParams(lp);
        v.setAlpha(f);
    }

    private void setHeaderCollapsed(boolean collapse, boolean animate) {
        headerCollapsed = collapse;
        accumulatedDy = 0;

        final java.util.List<View> views = collapsibleViews();
        if (views.isEmpty()) return;
        for (View v : views) captureMetrics(v);

        if (headerAnimator != null) {
            headerAnimator.cancel();
            headerAnimator = null;
        }

        final float target = collapse ? 0f : 1f;
        if (!animate) {
            for (View v : views) applyFraction(v, target);
            return;
        }

        final float start = collapse ? 1f : 0f;
        headerAnimator = android.animation.ValueAnimator.ofFloat(start, target);
        headerAnimator.setDuration(220);
        headerAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            for (View v : views) applyFraction(v, f);
        });
        headerAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(android.animation.Animator animation) {
                headerAnimating = true;
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                headerAnimating = false;
                headerCooldownUntil = android.os.SystemClock.uptimeMillis() + HEADER_COOLDOWN_MS;
                for (View v : views) applyFraction(v, target);
            }
        });
        headerAnimator.start();
    }

    /**
     * 解析 "读至第X章 / 共Y章" 格式，返回 0-100 百分比；无法解析返回 0。
     */
    private int parseProgressPercent(String chapterProgress) {
        if (chapterProgress == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("读至第(\\d+)章 / 共(\\d+)章")
                        .matcher(chapterProgress);
        if (m.find()) {
            try {
                int current = Integer.parseInt(m.group(1));
                int total   = Integer.parseInt(m.group(2));
                if (total > 0) return (int) (current * 100L / total);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /**
     * 用 post() 等待 layout 完成后再按百分比设置填充 View 宽度。
     */
    private void setProgressFillWidth(final View fillView, final int percent) {
        final View parent = (View) fillView.getParent();
        if (parent == null) return;
        parent.post(() -> {
            int trackWidth = parent.getWidth();
            int fillWidth = (int) (trackWidth * percent / 100f);
            ViewGroup.LayoutParams lp = fillView.getLayoutParams();
            lp.width = fillWidth;
            fillView.setLayoutParams(lp);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(UpdateChecker.ACTION_UPDATE_DONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }

        requestStoragePermissionThenInit();
        refreshWebDavSwitchEnabled();
        showUpdateBanner(UpdateChecker.isRunning(),
                UpdateChecker.getCurrentBookName(),
                UpdateChecker.getCurrentBook(),
                UpdateChecker.getTotalBooks());
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(updateReceiver);
        pollHandler.removeCallbacks(pollRunnable);
    }

    private void initFirstData() {
        executor.execute(() -> {
            List<BookList> books = getBooks();
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                bookLists.clear();
                bookLists.addAll(books);
                adapter.notifyDataSetChanged();
                updateContinueCard();
                updateGreeting();
                boolean hasPending = false;
                for (BookList b : books) {
                    if (b.getBookpath() == null || b.getBookpath().isEmpty()) {
                        hasPending = true;
                        break;
                    }
                }
                if (hasPending) {
                    pollHandler.removeCallbacks(pollRunnable);
                    pollHandler.postDelayed(pollRunnable, 3000);
                }
                refreshMissingCovers(books);
            });
        });
    }

    private void saveLocalBooks(List<BookList> toSave) {
        for (BookList bookList : toSave) {
            if (DB.bookList().findByBookpath(bookList.getBookpath()) != null) continue;
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(bookList.getBookpath()),
                    FileUtils.getCharset(bookList.getBookpath()))) {
                BufferedReader br = new BufferedReader(reader);
                StringBuilder buf = new StringBuilder();
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null && lines < 6) {
                    buf.append(line);
                    lines++;
                }
                bookList.setMsg(buf.toString());
            } catch (Exception e) {
                Log.e("Bookshelf", "msg read failed", e);
            }
            DB.save(bookList);
            WebDavConfig.markBookshelfModified(this);
        }
    }

    private List<BookList> getBooks() {
        List<BookList> books = DB.bookList().findAll();
        for (BookList book : books) {
            if (book.getBookpath() == null || book.getBookpath().isEmpty()) continue;
            List<com.thl.reader.db.BookCatalogue> chapters =
                    DB.catalogue().findByBookpath(book.getBookpath());
            if (chapters.isEmpty()) continue;
            java.util.Collections.sort(chapters,
                    (a, b) -> Long.compare(a.getBookCatalogueStartPos(), b.getBookCatalogueStartPos()));
            int currentChapter = 0;
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).getBookCatalogueStartPos() <= book.getBegin()) {
                    currentChapter = i + 1;
                }
            }
            if (currentChapter > 0) {
                book.setChapterProgress("读至第" + currentChapter + "章 / 共" + chapters.size() + "章");
            } else {
                book.setChapterProgress("共" + chapters.size() + "章，尚未开始");
            }
        }
        return books;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.add_book:
                startActivity(new Intent(this, FileChooserActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.ib_more:
                View menuView = LayoutInflater.from(this).inflate(R.layout.view_popu2, null);
                // Wire clicks for LinearLayout menu items
                menuView.findViewById(R.id.tv_about).setOnClickListener(this);
                menuView.findViewById(R.id.tv_donate).setOnClickListener(this);
                menuView.findViewById(R.id.add_book).setOnClickListener(this);
                menuView.findViewById(R.id.find_book).setOnClickListener(this);
                menuView.findViewById(R.id.tv_settings).setOnClickListener(this);
                menuView.findViewById(R.id.tv_export).setOnClickListener(this);
                menuView.findViewById(R.id.tv_import).setOnClickListener(this);
                menuView.findViewById(R.id.tv_fanqie_import).setOnClickListener(this);
                popWindow = new CustomPopWindow.PopupWindowBuilder(this)
                        .setView(menuView)
                        .enableBackgroundDark(false)
                        .setFocusable(true)
                        .setOutsideTouchable(true)
                        .create();
                popWindow.showAsDropDown(ib_more, 0, 0);
                break;

            case R.id.find_book:
                startActivity(new Intent(this, FindBookActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.tv_about:
                startActivity(new Intent(this, AboutActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.tv_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.tv_export:
                exportBookshelf();
                popWindow.dissmiss();
                break;

            case R.id.tv_import:
                importLauncher.launch("*/*");
                popWindow.dissmiss();
                break;

            case R.id.tv_fanqie_import:
                startActivity(new Intent(this, FanqieImportActivity.class));
                popWindow.dissmiss();
                break;

            case R.id.tv_donate:
                startActivity(new Intent(this, DonateActivity.class));
                popWindow.dissmiss();
                break;
        }
    }

    private void showBookshelf() {
        containerBookshelf.setVisibility(View.VISIBLE);
        containerBookStore.setVisibility(View.GONE);
        tvTitle.setText("书架");
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ib_more.setVisibility(View.VISIBLE);
        if (ibSearch != null) ibSearch.setVisibility(View.GONE);
        if (swEink != null) swEink.setVisibility(View.VISIBLE);
        if (swWebDav != null) swWebDav.setVisibility(View.VISIBLE);
        updateGreeting();
    }

    private void showBookStore() {
        containerBookshelf.setVisibility(View.GONE);
        containerBookStore.setVisibility(View.VISIBLE);
        tvTitle.setText("书城");
        tvTitle.setGravity(android.view.Gravity.CENTER);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        ib_more.setVisibility(View.GONE);
        if (ibSearch != null) ibSearch.setVisibility(View.GONE);
        if (swEink != null) swEink.setVisibility(View.GONE);
        if (swWebDav != null) swWebDav.setVisibility(View.GONE);
        if (tvGreeting != null) tvGreeting.setVisibility(View.GONE);

        if (bookStoreFragment == null) {
            bookStoreFragment = new BookStoreFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container_book_store, bookStoreFragment)
                    .commit();
        }
    }

    private void updateGreeting() {
        if (tvGreeting == null) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String timeGreet = hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
        long seconds = ReadingStatsManager.getTodaySeconds(this);
        String timeStr = ReadingStatsManager.formatTime(seconds);
        tvGreeting.setText(timeGreet + "，今天已阅读 " + timeStr);
        tvGreeting.setVisibility(View.VISIBLE);
    }

    private void updateContinueCard() {
        boolean hasBooks = !bookLists.isEmpty();

        // Show/hide new banner card
        BookList lastRead = null;
        for (BookList b : bookLists) {
            if (b.getLastReadAt() > 0 && b.getBookpath() != null && !b.getBookpath().isEmpty()) {
                lastRead = b;
                break;
            }
        }

        if (cardContinueBanner != null) {
            if (lastRead != null) {
                cardContinueBanner.setVisibility(View.VISIBLE);
                tvBannerTitle.setText(lastRead.getBookname());
                String progress = lastRead.getChapterProgress();
                tvBannerProgress.setText(progress != null ? progress : "");

                String coverUrl = lastRead.getCoverUrl();
                if (coverUrl != null && !coverUrl.isEmpty()) {
                    Glide.with(this).load(coverUrl)
                            .placeholder(R.drawable.bg_banner_no_cover)
                            .error(R.drawable.bg_banner_no_cover)
                            .into(ivBannerCover);
                } else {
                    ivBannerCover.setImageDrawable(
                            getResources().getDrawable(R.drawable.bg_banner_no_cover));
                }

                // Progress bar fill
                if (vBannerProgressFill != null) {
                    int pct = parseProgressPercent(lastRead.getChapterProgress());
                    setProgressFillWidth(vBannerProgressFill, pct);
                }
            } else {
                cardContinueBanner.setVisibility(View.GONE);
            }
        }

        // Stats row
        if (rowStats != null) {
            if (hasBooks) {
                rowStats.setVisibility(View.VISIBLE);
                // Weekly time
                long seconds = ReadingStatsManager.getWeeklySeconds(this);
                if (tvStatWeekly != null) tvStatWeekly.setText(ReadingStatsManager.formatTime(seconds));
                // Cumulative days
                int days = ReadingStatsManager.getCumulativeDays(this);
                if (tvStatDays != null) tvStatDays.setText(days + "天");
                // Book count
                if (tvStatBooks != null) tvStatBooks.setText(bookLists.size() + "本");
            } else {
                rowStats.setVisibility(View.GONE);
            }
        }

        if (rowShelfHeader != null) rowShelfHeader.setVisibility(hasBooks ? View.VISIBLE : View.GONE);
        refreshSyncLabel();

        // 折叠状态下数据刷新会让横栏恢复完整高度，需重新收起
        if (headerCollapsed && mRecyclerView != null) {
            mRecyclerView.post(() -> setHeaderCollapsed(true, false));
        }
    }

    // ──────────── 搜索历史 ────────────

    private void saveSearchHistory(String query) {
        List<String> history = loadSearchHistory();
        history.remove(query);
        history.add(0, query);
        if (history.size() > MAX_HISTORY) history = history.subList(0, MAX_HISTORY);
        JSONArray arr = new JSONArray();
        for (String s : history) arr.put(s);
        getSharedPreferences(PREF_SEARCH_HISTORY, MODE_PRIVATE)
                .edit().putString("list", arr.toString()).apply();
    }

    private List<String> loadSearchHistory() {
        String json = getSharedPreferences(PREF_SEARCH_HISTORY, MODE_PRIVATE)
                .getString("list", "[]");
        List<String> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) result.add(arr.getString(i));
        } catch (JSONException ignored) {}
        return result;
    }

    private void showHistoryPopup() {
        List<String> history = loadSearchHistory();
        if (history.isEmpty()) {
            Toast.makeText(this, "暂无搜索历史", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.bg_popup_card);
        container.setPadding(0, dp(6), 0, dp(6));

        for (String query : history) {
            TextView item = new TextView(this);
            item.setText(query);
            item.setTextSize(14);
            item.setTextColor(getResources().getColor(R.color.text_primary));
            item.setPadding(dp(16), dp(12), dp(16), dp(12));
            item.setBackgroundResource(R.drawable.bg_item_sel);
            item.setOnClickListener(v -> {
                etSearch.setText(query);
                Intent intent = new Intent(this, SearchResultActivity.class);
                intent.putExtra(SearchResultActivity.EXTRA_QUERY, query);
                startActivity(intent);
            });
            container.addView(item);
        }

        PopupWindow pw = new PopupWindow(container,
                ibHistory.getWidth() + etSearch.getWidth() + dp(4),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setElevation(8);
        pw.showAsDropDown(etSearch, 0, 0);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ──────────── 排序 ────────────

    private void showSortPopup() {
        String[] options = {"最近阅读", "最近添加"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(options, currentSort, (dialog, which) -> {
                    currentSort = which;
                    dialog.dismiss();
                    if (tvSort != null) tvSort.setText(options[which] + " ▼");
                    executor.execute(() -> {
                        List<BookList> books = getBooks();
                        books = sortBooks(books);
                        final List<BookList> sorted = books;
                        runOnUiThread(() -> {
                            if (isDestroyed() || isFinishing()) return;
                            bookLists.clear();
                            bookLists.addAll(sorted);
                            adapter.notifyDataSetChanged();
                        });
                    });
                })
                .show();
    }

    private List<BookList> sortBooks(List<BookList> books) {
        if (currentSort == SORT_RECENT_ADDED) {
            Collections.sort(books, (a, b) -> Long.compare(b.getId(), a.getId()));
        } else {
            Collections.sort(books, (a, b) -> Long.compare(b.getLastReadAt(), a.getLastReadAt()));
        }
        return books;
    }

    private void refreshMissingCovers(List<BookList> books) {
        if (coverRefreshDone) return;
        coverRefreshDone = true;
        List<BookList> needRefresh = new java.util.ArrayList<>();
        for (BookList b : books) {
            if (b.getIsTomato() == 1
                    && b.getTomatoBookId() != null
                    && (b.getCoverUrl() == null || b.getCoverUrl().isEmpty())) {
                needRefresh.add(b);
            }
        }
        if (needRefresh.isEmpty()) return;
        final Context appCtx = getApplicationContext();
        executor.execute(() -> {
            com.thl.book.download.NovelDownloadManager mgr =
                    new com.thl.book.download.NovelDownloadManager(appCtx);
            boolean anyUpdated = false;
            for (BookList b : needRefresh) {
                String fresh = mgr.fetchFreshCoverUrl(b.getTomatoBookId(), b.getBookname());
                if (fresh != null) {
                    DB.bookList().updateDownloadResult(
                            b.getTomatoBookId(), b.getBookname(),
                            b.getBookpath() != null ? b.getBookpath() : "",
                            b.getMsg() != null ? b.getMsg() : "",
                            b.getCharset() != null ? b.getCharset() : "UTF-8",
                            fresh);
                    anyUpdated = true;
                }
            }
            if (anyUpdated) {
                List<BookList> updated = getBooks();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    bookLists.clear();
                    bookLists.addAll(updated);
                    adapter.notifyDataSetChanged();
                    updateContinueCard();
                });
            }
        });
    }

    private void showUpdateBanner(boolean show, String bookName, int current, int total) {
        if (tvUpdateStatus == null) return;
        tvUpdateStatus.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            String text;
            if (total > 0 && current > 0 && bookName != null && !bookName.isEmpty()) {
                String name = bookName.length() > 12 ? bookName.substring(0, 12) + "…" : bookName;
                text = "正在检查更新 " + current + "/" + total + "《" + name + "》";
            } else {
                text = "正在检查更新…";
            }
            tvUpdateStatus.setText(text);
        }
    }

    private void requestPermissins(PermissionUtils.OnPermissionListener listener) {
        String[] permissions = {
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };
        PermissionUtils.requestPermissions(this, 0, permissions, listener);
    }

    private void requestStoragePermissionThenInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                initFirstData();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("需要存储权限")
                        .setMessage("应用需要「所有文件访问权限」才能读写书籍缓存文件及手动添加本地图书，请在下一页面中为本应用开启该权限。")
                        .setPositiveButton("去授权", (d, w) -> {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("暂时跳过", (d, w) -> initFirstData())
                        .setCancelable(false)
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissins(new PermissionUtils.OnPermissionListener() {
                @Override
                public void onPermissionGranted() { initFirstData(); }
                @Override
                public void onPermissionDenied(String[] deniedPermissions) {
                    initFirstData();
                }
            });
        } else {
            initFirstData();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionUtils.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    private long mExitTime;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isDel) {
            isDel = false;
            adapter.notifyDataSetChanged();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            if (System.currentTimeMillis() - mExitTime > 2000) {
                mExitTime = System.currentTimeMillis();
                Toast.makeText(this, "再次点击返回确认退出", Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ──────────── 导出书架 ────────────

    private void exportBookshelf() {
        executor.execute(() -> {
            List<BookList> books = DB.bookList().findAll();
            String json = new Gson().toJson(books);
            String filename = "tomato_bookshelf_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date())
                    + ".json";
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File out = new File(dir, filename);
            try (FileWriter w = new FileWriter(out)) {
                w.write(json);
                runOnUiThread(() -> Toast.makeText(this,
                        "已导出到 Downloads/" + filename, Toast.LENGTH_LONG).show());
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ──────────── 导入书架 ────────────

    private void importBookshelf(android.net.Uri uri) {
        executor.execute(() -> {
            String json;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                json = sb.toString();
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "读取文件失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }

            List<BookList> imported;
            try {
                imported = new Gson().fromJson(json,
                        new TypeToken<List<BookList>>() {}.getType());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "文件格式错误", Toast.LENGTH_SHORT).show());
                return;
            }
            if (imported == null || imported.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this,
                        "备份文件中没有书籍", Toast.LENGTH_SHORT).show());
                return;
            }

            int added = 0;
            int skipped = 0;
            for (BookList book : imported) {
                if (book.getIsTomato() == 1 && book.getTomatoBookId() != null) {
                    String outputPath = new File(
                            com.thl.book.download.NovelDownloadManager.getTomatoDir(this),
                            book.getBookname().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
                    ).getAbsolutePath();

                    if (new java.io.File(outputPath).exists()) { skipped++; continue; }

                    List<BookList> existing = DB.bookList().findByTomatoBookId(book.getTomatoBookId());
                    BookList placeholder;
                    if (!existing.isEmpty()) {
                        placeholder = existing.get(0);
                        placeholder.setBookpath("");
                        placeholder.setMsg("下载中…");
                        DB.bookList().update(placeholder);
                    } else {
                        placeholder = new BookList();
                        placeholder.setBookname(book.getBookname());
                        placeholder.setBookpath("");
                        placeholder.setIsTomato(1);
                        placeholder.setTomatoBookId(book.getTomatoBookId());
                        placeholder.setMsg("下载中…");
                        placeholder.setCoverUrl(book.getCoverUrl());
                        DB.save(placeholder);
                    }

                    final Context appCtx = getApplicationContext();
                    final String bookId = book.getTomatoBookId();
                    final String bookName = book.getBookname();
                    final String coverUrl = book.getCoverUrl();
                    downloadExecutor.execute(() -> {
                        com.thl.book.download.NovelDownloadManager mgr =
                                new com.thl.book.download.NovelDownloadManager(appCtx);
                        mgr.downloadFull(bookId, bookName, null, coverUrl, outputPath,
                                new com.thl.book.download.NovelDownloadManager.ProgressCallback() {
                                    @Override public void onProgress(int d, int t) {}
                                    @Override public void onComplete() {
                                        NotifyHelper.send(appCtx, "下载完成",
                                                "《" + bookName + "》已添加到书架");
                                        appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                                .setPackage(appCtx.getPackageName())
                                                .putExtra("total_new", 0));
                                    }
                                    @Override public void onError(String msg) {
                                        DB.bookList().updateDownloadResult(
                                                bookId, bookName, "", "下载失败，点击重试", null, coverUrl);
                                        NotifyHelper.send(appCtx, "下载失败",
                                                "《" + bookName + "》" + msg);
                                        appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                                .setPackage(appCtx.getPackageName())
                                                .putExtra("total_new", 0));
                                    }
                                });
                    });
                    added++;

                } else if (book.getIsTomato() == 0
                        && book.getBookpath() != null
                        && new File(book.getBookpath()).exists()) {
                    if (DB.bookList().findByBookpath(book.getBookpath()) != null) {
                        skipped++;
                        continue;
                    }
                    book.setId(0);
                    DB.save(book);
                    added++;
                } else {
                    skipped++;
                }
            }

            final int finalAdded = added;
            final int finalSkipped = skipped;
            if (finalAdded > 0) WebDavConfig.markBookshelfModified(this);
            sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                    .setPackage(getPackageName())
                    .putExtra("total_new", 0));
            runOnUiThread(() -> Toast.makeText(this,
                    "导入完成：新增 " + finalAdded + " 本，跳过 " + finalSkipped + " 本",
                    Toast.LENGTH_SHORT).show());
        });
    }

    private void retryDownload(BookList book) {
        if (book.getTomatoBookId() == null) return;
        String outputPath = new java.io.File(
                com.thl.book.download.NovelDownloadManager.getTomatoDir(this),
                book.getBookname().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"
        ).getAbsolutePath();
        final Context appCtx = getApplicationContext();

        downloadExecutor.execute(() -> {
            DB.bookList().updateDownloadResult(
                    book.getTomatoBookId(), book.getBookname(), "", "下载中…", null, book.getCoverUrl());
            sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                    .setPackage(getPackageName())
                    .putExtra("total_new", 0));
            runOnUiThread(() -> Toast.makeText(this,
                    "《" + book.getBookname() + "》重新下载中，完成后通知", Toast.LENGTH_SHORT).show());

            com.thl.book.download.NovelDownloadManager manager =
                    new com.thl.book.download.NovelDownloadManager(appCtx);
            manager.downloadFull(book.getTomatoBookId(), book.getBookname(), null, book.getCoverUrl(), outputPath,
                    new com.thl.book.download.NovelDownloadManager.ProgressCallback() {
                        @Override public void onProgress(int d, int t) {}

                        @Override
                        public void onComplete() {
                            NotifyHelper.send(appCtx, "下载完成",
                                    "《" + book.getBookname() + "》已添加到书架");
                            appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .setPackage(appCtx.getPackageName())
                                    .putExtra("total_new", 0));
                        }

                        @Override
                        public void onError(String message) {
                            DB.bookList().updateDownloadResult(
                                    book.getTomatoBookId(), book.getBookname(), "", "下载失败，点击重试", null, book.getCoverUrl());
                            NotifyHelper.send(appCtx, "下载失败",
                                    "《" + book.getBookname() + "》" + message);
                            appCtx.sendBroadcast(new Intent(UpdateChecker.ACTION_UPDATE_DONE)
                                    .setPackage(appCtx.getPackageName())
                                    .putExtra("total_new", 0));
                        }
                    });
        });
    }

    private static String[] splitTimeDisplay(long totalSeconds) {
        if (totalSeconds <= 0) return new String[]{"0", "分钟"};
        long minutes = totalSeconds / 60;
        if (minutes == 0) return new String[]{"<1", "分钟"};
        long hours = minutes / 60;
        long mins  = minutes % 60;
        if (hours == 0) return new String[]{String.valueOf(minutes), "分钟"};
        if (mins == 0)  return new String[]{String.valueOf(hours), "小时"};
        return new String[]{hours + ":" + String.format("%02d", mins), "小时"};
    }
}
